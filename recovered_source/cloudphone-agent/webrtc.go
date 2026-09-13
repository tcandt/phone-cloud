package main

import (
	"crypto/rand"
	"encoding/binary"
	"encoding/json"
	"log"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/pion/rtp"
	"github.com/pion/webrtc/v3"
)

type WebRTCSession struct {
	ClientID         string
	pc               *webrtc.PeerConnection
	videoTrack       *webrtc.TrackLocalStaticRTP
	audioTrack       *webrtc.TrackLocalStaticRTP
	ctrl             *ControlWriter
	inputDC          *webrtc.DataChannel
	clipDC           *webrtc.DataChannel
	cameraDC         *webrtc.DataChannel
	Caps             SessionCapabilities
	mu               sync.RWMutex

	// Per-session RTP sequence numbers (monotonic, isolated per viewer)
	seqMu    sync.Mutex
	videoSeq uint16
	audioSeq uint16

	// Media queues for non-blocking distribution
	videoQueue       chan *rtp.Packet
	audioQueue       chan *rtp.Packet
	closed           bool
	closeOnce        sync.Once

	// ICE Candidate Trickle Callback
	onLocalCandidate func(candidate *webrtc.ICECandidate)

	// PLI Throttling
	lastPliTime      time.Time
	pliMu            sync.Mutex
}

func (s *WebRTCSession) NextVideoSeq() uint16 {
	s.seqMu.Lock()
	defer s.seqMu.Unlock()
	s.videoSeq++
	return s.videoSeq
}

func (s *WebRTCSession) NextAudioSeq() uint16 {
	s.seqMu.Lock()
	defer s.seqMu.Unlock()
	s.audioSeq++
	return s.audioSeq
}

func (s *WebRTCSession) CurrentVideoSeq() uint16 {
	s.seqMu.Lock()
	defer s.seqMu.Unlock()
	return s.videoSeq
}

func (s *WebRTCSession) CurrentAudioSeq() uint16 {
	s.seqMu.Lock()
	defer s.seqMu.Unlock()
	return s.audioSeq
}

func (s *WebRTCSession) SetOnLocalCandidate(cb func(candidate *webrtc.ICECandidate)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.onLocalCandidate = cb
}

func (s *WebRTCSession) EnqueueVideoPacket(packet *rtp.Packet, isKeyFrame bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.closed || s.videoTrack == nil || packet == nil {
		return
	}
	select {
	case s.videoQueue <- packet:
	default:
		// Queue is full: if keyframe, drop oldest stale packet and push
		if isKeyFrame {
			select {
			case <-s.videoQueue:
			default:
			}
			select {
			case s.videoQueue <- packet:
			default:
			}
		}
	}
}

func (s *WebRTCSession) EnqueueAudioPacket(packet *rtp.Packet) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.closed || s.audioTrack == nil || packet == nil {
		return
	}
	select {
	case s.audioQueue <- packet:
	default:
		// Congestion: drop audio packet to prevent latency accumulation
	}
}

func (s *WebRTCSession) SetCapabilities(caps SessionCapabilities) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.Caps = caps
	log.Printf("[Agent] Session capabilities applied (Client: %s): Control=%v, Clip=%v, File=%v, Shell=%v",
		s.ClientID, caps.CanControl, caps.CanClipboard, caps.CanFile, caps.CanShell)
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
	tokens := strings.Split(raw, ",")
	for _, tok := range tokens {
		tok = strings.TrimSpace(tok)
		if tok == "" {
			continue
		}
		if strings.HasPrefix(tok, "turn:") || strings.HasPrefix(tok, "turns:") {
			u, err := url.Parse(tok)
			if err == nil && u.User != nil {
				username := u.User.Username()
				password, _ := u.User.Password()
				u.User = nil
				cleanURL := u.String()
				servers = append(servers, webrtc.ICEServer{
					URLs:           []string{cleanURL},
					Username:       username,
					Credential:     password,
					CredentialType: webrtc.ICECredentialTypePassword,
				})
				continue
			}
		}
		servers = append(servers, webrtc.ICEServer{
			URLs: []string{tok},
		})
	}
	if len(servers) > 0 {
		return servers
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

	// Create Tracks using TrackLocalStaticRTP for direct hardware PTS passthrough
	vTrack, err := webrtc.NewTrackLocalStaticRTP(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeH264}, "video", "cloudphone-video")
	if err != nil {
		pc.Close()
		return nil, err
	}
	vSender, err := pc.AddTrack(vTrack)
	if err != nil {
		pc.Close()
		return nil, err
	}

	aTrack, err := webrtc.NewTrackLocalStaticRTP(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeOpus}, "audio", "cloudphone-audio")
	if err == nil {
		_, _ = pc.AddTrack(aTrack)
	}

	var seqBytes [4]byte
	_, _ = rand.Read(seqBytes[:])
	vSeqInit := binary.BigEndian.Uint16(seqBytes[0:2])
	aSeqInit := binary.BigEndian.Uint16(seqBytes[2:4])

	session := &WebRTCSession{
		pc:         pc,
		videoTrack: vTrack,
		audioTrack: aTrack,
		ctrl:       ctrl,
		videoQueue: make(chan *rtp.Packet, 120),
		audioQueue: make(chan *rtp.Packet, 120),
		videoSeq:   vSeqInit,
		audioSeq:   aSeqInit,
	}

	// Non-blocking worker goroutine for video RTP packet dispatch
	go func() {
		for packet := range session.videoQueue {
			session.mu.RLock()
			vt := session.videoTrack
			closed := session.closed
			session.mu.RUnlock()
			if !closed && vt != nil {
				_ = vt.WriteRTP(packet)
			}
		}
	}()

	// Non-blocking worker goroutine for audio RTP packet dispatch
	go func() {
		for packet := range session.audioQueue {
			session.mu.RLock()
			at := session.audioTrack
			closed := session.closed
			session.mu.RUnlock()
			if !closed && at != nil {
				_ = at.WriteRTP(packet)
			}
		}
	}()

	// Listen for RTCP PLI (Picture Loss Indication) from browser to request IDR keyframe (throttled to max 1 / 500ms)
	go func() {
		rtcpBuf := make([]byte, 1500)
		for {
			if _, _, rtcpErr := vSender.Read(rtcpBuf); rtcpErr != nil {
				return
			}
			session.pliMu.Lock()
			now := time.Now()
			if now.Sub(session.lastPliTime) >= 500*time.Millisecond {
				session.lastPliTime = now
				session.pliMu.Unlock()
				if ctrl != nil {
					_ = ctrl.RequestKeyframe()
				}
			} else {
				session.pliMu.Unlock()
			}
		}
	}()

	// ICE Candidate Trickle handler
	pc.OnICECandidate(func(c *webrtc.ICECandidate) {
		session.mu.RLock()
		cb := session.onLocalCandidate
		session.mu.RUnlock()
		if cb != nil {
			cb(c)
		}
	})

	// Create channels that web-app and Android Controller expect from agent (pc.ondatachannel)
	session.inputDC, _ = pc.CreateDataChannel("input-channel", nil)
	session.clipDC, _ = pc.CreateDataChannel("clipboard-channel", nil)
	session.cameraDC, _ = pc.CreateDataChannel("camera-channel", nil)

	session.setupInputChannel(session.inputDC)
	session.setupClipboardChannel(session.clipDC)
	setupCameraChannel(session.cameraDC, session)

	// Listen for client/browser-created DataChannels (file-channel, ai-command-channel, adb-channel)
	pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		label := dc.Label()
		log.Printf("[WebRTC] Remote DataChannel opened: %s", label)
		switch label {
		case "file-channel":
			setupFileChannel(dc, session)
		case "ai-command-channel":
			setupAiCommandChannel(dc, session)
		case "adb-channel":
			setupAdbChannel(dc, session)
		case "input-channel":
			session.setupInputChannel(dc)
		case "clipboard-channel":
			session.setupClipboardChannel(dc)
		case "camera-channel":
			setupCameraChannel(dc, session)
		}
	})

	return session, nil
}

func (s *WebRTCSession) setupInputChannel(dc *webrtc.DataChannel) {
	if dc == nil {
		return
	}
	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		s.mu.RLock()
		canControl := s.Caps.CanControl
		s.mu.RUnlock()
		if !canControl {
			log.Printf("[Agent] Input rejected: session does not have CanControl permission (view-only)")
			return
		}
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
		s.mu.RLock()
		canClipboard := s.Caps.CanClipboard
		s.mu.RUnlock()
		if !canClipboard {
			log.Printf("[Agent] Clipboard rejected: session does not have CanClipboard permission")
			return
		}
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
	s.closeOnce.Do(func() {
		s.mu.Lock()
		s.closed = true
		s.mu.Unlock()

		if s.pc != nil {
			_ = s.pc.Close()
		}
		close(s.videoQueue)
		close(s.audioQueue)
	})
}

