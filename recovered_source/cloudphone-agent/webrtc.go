package main

import (
	"encoding/json"
	"log"
	"strings"

	"github.com/pion/webrtc/v3"
)

type WebRTCSession struct {
	pc         *webrtc.PeerConnection
	videoTrack *webrtc.TrackLocalStaticSample
	audioTrack *webrtc.TrackLocalStaticSample
	ctrl       *ControlWriter
	inputDC    *webrtc.DataChannel
	clipDC     *webrtc.DataChannel
	cameraDC   *webrtc.DataChannel
}

func parseICEServers(raw string) []webrtc.ICEServer {
	if raw == "" {
		return []webrtc.ICEServer{
			{URLs: []string{"stun:stun.l.google.com:19302"}},
		}
	}
	var servers []webrtc.ICEServer
	if err := json.Unmarshal([]byte(raw), &servers); err == nil && len(servers) > 0 {
		return servers
	}
	urls := strings.Split(raw, ",")
	var cleanURLs []string
	for _, u := range urls {
		u = strings.TrimSpace(u)
		if u != "" {
			cleanURLs = append(cleanURLs, u)
		}
	}
	if len(cleanURLs) > 0 {
		return []webrtc.ICEServer{{URLs: cleanURLs}}
	}
	return []webrtc.ICEServer{
		{URLs: []string{"stun:stun.l.google.com:19302"}},
	}
}

func NewWebRTCSession(ctrl *ControlWriter, iceServersRaw string) (*WebRTCSession, error) {
	m := &webrtc.MediaEngine{}
	if err := m.RegisterCodec(webrtc.RTPCodecParameters{
		RTPCodecCapability: webrtc.RTPCodecCapability{
			MimeType:     webrtc.MimeTypeH264,
			ClockRate:    90000,
			Channels:     0,
			SDPFmtpLine:  "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
			RTCPFeedback: []webrtc.RTCPFeedback{{Type: "nack"}, {Type: "nack", Parameter: "pli"}},
		},
		PayloadType: 96,
	}, webrtc.RTPCodecTypeVideo); err != nil {
		return nil, err
	}

	if err := m.RegisterCodec(webrtc.RTPCodecParameters{
		RTPCodecCapability: webrtc.RTPCodecCapability{
			MimeType:     webrtc.MimeTypeOpus,
			ClockRate:    48000,
			Channels:     2,
			SDPFmtpLine:  "minptime=10;useinbandfec=1",
			RTCPFeedback: nil,
		},
		PayloadType: 111,
	}, webrtc.RTPCodecTypeAudio); err != nil {
		return nil, err
	}

	api := webrtc.NewAPI(webrtc.WithMediaEngine(m))
	pc, err := api.NewPeerConnection(webrtc.Configuration{
		ICEServers: parseICEServers(iceServersRaw),
	})
	if err != nil {
		return nil, err
	}

	// Create Tracks
	vTrack, err := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeH264}, "video", "cloudphone-video")
	if err != nil {
		pc.Close()
		return nil, err
	}
	vSender, err := pc.AddTrack(vTrack)
	if err != nil {
		pc.Close()
		return nil, err
	}

	// Listen for RTCP PLI (Picture Loss Indication) from browser to request IDR keyframe
	go func() {
		rtcpBuf := make([]byte, 1500)
		for {
			if _, _, rtcpErr := vSender.Read(rtcpBuf); rtcpErr != nil {
				return
			}
			if ctrl != nil {
				_ = ctrl.RequestKeyframe()
			}
		}
	}()

	aTrack, err := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeOpus}, "audio", "cloudphone-audio")
	if err == nil {
		_, _ = pc.AddTrack(aTrack)
	}

	session := &WebRTCSession{
		pc:         pc,
		videoTrack: vTrack,
		audioTrack: aTrack,
		ctrl:       ctrl,
	}

	// Create channels that web-app expects from agent (pc.ondatachannel)
	session.inputDC, _ = pc.CreateDataChannel("input-channel", nil)
	session.clipDC, _ = pc.CreateDataChannel("clipboard-channel", nil)
	session.cameraDC, _ = pc.CreateDataChannel("camera-channel", nil)

	session.setupInputChannel(session.inputDC)
	session.setupClipboardChannel(session.clipDC)

	// Pre-create file and command channels for direct readiness
	fileDC, _ := pc.CreateDataChannel("file-channel", nil)
	if fileDC != nil {
		setupFileChannel(fileDC)
	}
	aiCmdDC, _ := pc.CreateDataChannel("ai-command-channel", nil)
	if aiCmdDC != nil {
		setupAiCommandChannel(aiCmdDC)
	}

	// Listen for browser-created DataChannels (file-channel, ai-command-channel, adb-channel)
	pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		label := dc.Label()
		log.Printf("[WebRTC] Remote DataChannel opened: %s", label)
		switch label {
		case "file-channel":
			setupFileChannel(dc)
		case "ai-command-channel":
			setupAiCommandChannel(dc)
		case "adb-channel":
			setupAdbChannel(dc)
		case "input-channel":
			session.setupInputChannel(dc)
		case "clipboard-channel":
			session.setupClipboardChannel(dc)
		}
	})

	return session, nil
}

func (s *WebRTCSession) setupInputChannel(dc *webrtc.DataChannel) {
	if dc == nil {
		return
	}
	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		if s.ctrl == nil {
			return
		}
		var base struct {
			Type string `json:"type"`
		}
		if err := json.Unmarshal(msg.Data, &base); err != nil {
			return
		}

		switch base.Type {
		case "touch":
			var t TouchInputMessage
			if err := json.Unmarshal(msg.Data, &t); err == nil {
				_ = s.ctrl.SendTouch(byte(t.Action), t.ID, t.X, t.Y, t.W, t.H, 1.0)
			}
		case "inject_keycode":
			var k KeycodeEventMessage
			if err := json.Unmarshal(msg.Data, &k); err == nil {
				_ = s.ctrl.SendKeycode(byte(k.Action), k.Keycode, k.Repeat, k.Meta)
			}
		case "inject_text":
			var txt TextInputMessage
			if err := json.Unmarshal(msg.Data, &txt); err == nil {
				_ = s.ctrl.SendText(txt.Text)
			}
		case "inject_scroll", "scroll":
			var sc ScrollInputMessage
			if err := json.Unmarshal(msg.Data, &sc); err == nil {
				_ = s.ctrl.SendScroll(sc.X, sc.Y, sc.W, sc.H, sc.ScrollH, sc.ScrollV)
			}
		}
	})
}

func (s *WebRTCSession) setupClipboardChannel(dc *webrtc.DataChannel) {
	if dc == nil {
		return
	}
	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		var clip ClipboardMessage
		if err := json.Unmarshal(msg.Data, &clip); err == nil && clip.Type == "set_clipboard" {
			if s.ctrl != nil {
				_ = s.ctrl.SetClipboard(clip.Text, clip.Paste)
			}
		}
	})
}

func (s *WebRTCSession) CreateOffer() (string, error) {
	offer, err := s.pc.CreateOffer(nil)
	if err != nil {
		return "", err
	}
	if err := s.pc.SetLocalDescription(offer); err != nil {
		return "", err
	}
	return offer.SDP, nil
}

func (s *WebRTCSession) SetAnswer(sdp string) error {
	return s.pc.SetRemoteDescription(webrtc.SessionDescription{
		Type: webrtc.SDPTypeAnswer,
		SDP:  sdp,
	})
}

func (s *WebRTCSession) AddIceCandidate(cand webrtc.ICECandidateInit) error {
	return s.pc.AddICECandidate(cand)
}

func (s *WebRTCSession) Close() {
	if s.pc != nil {
		_ = s.pc.Close()
	}
}
