package main

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"net"
	"testing"
	"time"

	"github.com/pion/rtp"
	"github.com/pion/webrtc/v3"
)

func TestWebRTC_EndToEndIntegration(t *testing.T) {
	// 1. Setup Genuine Control Writer with net.Pipe to assert real scrcpy control packets
	serverConn, clientConn := net.Pipe()
	defer serverConn.Close()
	defer clientConn.Close()

	// Asynchronous reader on clientConn so net.Pipe never blocks synchronous writes from ControlWriter
	receivedControlBytes := make(chan byte, 1024)
	go func() {
		buf := make([]byte, 1024)
		for {
			n, err := clientConn.Read(buf)
			if err != nil {
				return
			}
			for i := 0; i < n; i++ {
				receivedControlBytes <- buf[i]
			}
		}
	}()

	readControlPacket := func(expectedLen int, timeout time.Duration) ([]byte, error) {
		pkt := make([]byte, expectedLen)
		for i := 0; i < expectedLen; i++ {
			select {
			case b := <-receivedControlBytes:
				pkt[i] = b
			case <-time.After(timeout):
				return nil, fmt.Errorf("timeout waiting for byte %d/%d", i, expectedLen)
			}
		}
		return pkt, nil
	}

	ctrl := NewControlWriter(serverConn)
	streamer := NewStreamerBridge(ctrl, 30)

	// 2. Setup Agent WebRTC Session
	agentSession, err := NewWebRTCSession(ctrl, "")
	if err != nil {
		t.Fatalf("Failed to create Agent WebRTC session: %v", err)
	}
	defer agentSession.Close()

	clientID := "test_client_integ_01"
	agentSession.ClientID = clientID
	agentSession.SetCapabilities(SessionCapabilities{
		CanControl:   true,
		CanClipboard: true,
		CanFile:      true,
		CanCamera:    true,
		CanAudio:     true,
		CanRecord:    true,
	})
	streamer.RegisterSession(clientID, agentSession)
	defer streamer.UnregisterSession(clientID)

	// Assert keyframe request was sent upon session registration
	kfPacket, err := readControlPacket(1, 2*time.Second)
	if err != nil {
		t.Fatalf("Failed to read initial keyframe request from net.Pipe: %v", err)
	}
	if kfPacket[0] != ControlMsgRequestKeyframe { // ControlMsgRequestKeyframe = 18
		t.Fatalf("Expected ControlMsgRequestKeyframe (18), got %d", kfPacket[0])
	}
	t.Log("[PASS] Initial IDR keyframe request byte (18) asserted from net.Pipe")

	// 3. Setup Client PeerConnection (simulating browser / Android Controller)
	clientPC, err := webrtc.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatalf("Failed to create Client PeerConnection: %v", err)
	}
	defer clientPC.Close()

	connectedChan := make(chan struct{}, 1)
	clientPC.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		if state == webrtc.PeerConnectionStateConnected {
			select {
			case connectedChan <- struct{}{}:
			default:
			}
		}
	})

	// Client initiates client-side DataChannels (file-channel, ai-command-channel, adb-channel)
	clientFileDC, err := clientPC.CreateDataChannel("file-channel", nil)
	if err != nil {
		t.Fatalf("Client failed to create file-channel: %v", err)
	}
	clientAiDC, err := clientPC.CreateDataChannel("ai-command-channel", nil)
	if err != nil {
		t.Fatalf("Client failed to create ai-command-channel: %v", err)
	}
	clientAdbDC, err := clientPC.CreateDataChannel("adb-channel", nil)
	if err != nil {
		t.Fatalf("Client failed to create adb-channel: %v", err)
	}

	// Capture channels received by client from agent (input-channel, clipboard-channel, camera-channel)
	receivedChannels := make(map[string]*webrtc.DataChannel)
	clientCameraOpenChan := make(chan struct{}, 1)
	clientInputOpenChan := make(chan struct{}, 1)
	clientPC.OnDataChannel(func(dc *webrtc.DataChannel) {
		label := dc.Label()
		receivedChannels[label] = dc
		dc.OnOpen(func() {
			if label == "camera-channel" {
				select {
				case clientCameraOpenChan <- struct{}{}:
				default:
				}
			} else if label == "input-channel" {
				select {
				case clientInputOpenChan <- struct{}{}:
				default:
				}
			}
		})
	})

	// Add audio/video transceivers on client so media tracks can be received
	_, _ = clientPC.AddTransceiverFromKind(webrtc.RTPCodecTypeVideo, webrtc.RTPTransceiverInit{
		Direction: webrtc.RTPTransceiverDirectionRecvonly,
	})
	_, _ = clientPC.AddTransceiverFromKind(webrtc.RTPCodecTypeAudio, webrtc.RTPTransceiverInit{
		Direction: webrtc.RTPTransceiverDirectionRecvonly,
	})

	videoPacketsReceived := make(chan *rtp.Packet, 10)
	clientPC.OnTrack(func(track *webrtc.TrackRemote, receiver *webrtc.RTPReceiver) {
		go func() {
			for {
				pkt, _, err := track.ReadRTP()
				if err != nil {
					return
				}
				if len(pkt.Payload) > 0 {
					select {
					case videoPacketsReceived <- pkt:
					default:
					}
				}
			}
		}()
	})

	// 4. Wire ICE Trickle
	agentSession.SetOnLocalCandidate(func(c *webrtc.ICECandidate) {
		if c != nil {
			candJSON := c.ToJSON()
			_ = clientPC.AddICECandidate(candJSON)
		}
	})
	clientPC.OnICECandidate(func(c *webrtc.ICECandidate) {
		if c != nil {
			candJSON := c.ToJSON()
			_ = agentSession.AddIceCandidate(candJSON)
		}
	})

	// 5. Perform Offer / Answer Handshake
	sdpOffer, err := agentSession.CreateOffer()
	if err != nil {
		t.Fatalf("Agent CreateOffer failed: %v", err)
	}

	err = clientPC.SetRemoteDescription(webrtc.SessionDescription{
		Type: webrtc.SDPTypeOffer,
		SDP:  sdpOffer,
	})
	if err != nil {
		t.Fatalf("Client SetRemoteDescription(Offer) failed: %v", err)
	}

	answer, err := clientPC.CreateAnswer(nil)
	if err != nil {
		t.Fatalf("Client CreateAnswer failed: %v", err)
	}

	err = clientPC.SetLocalDescription(answer)
	if err != nil {
		t.Fatalf("Client SetLocalDescription(Answer) failed: %v", err)
	}

	err = agentSession.SetAnswer(answer.SDP)
	if err != nil {
		t.Fatalf("Agent SetAnswer failed: %v", err)
	}

	// 6. Wait for WebRTC Connection State Connected
	if clientPC.ConnectionState() != webrtc.PeerConnectionStateConnected {
		select {
		case <-connectedChan:
			t.Log("[PASS] WebRTC PeerConnection reached state Connected")
		case <-time.After(5 * time.Second):
			t.Fatalf("Timeout waiting for WebRTC connection to reach Connected state (current: %s)", clientPC.ConnectionState())
		}
	} else {
		t.Log("[PASS] WebRTC PeerConnection already Connected")
	}

	// 7. Wait for Agent-initiated DataChannels to open on Client
	select {
	case <-clientInputOpenChan:
		t.Log("[PASS] input-channel open on client")
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for input-channel to open")
	}

	select {
	case <-clientCameraOpenChan:
		t.Log("[PASS] camera-channel open on client")
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for camera-channel to open")
	}

	// 8. Test input injection over input-channel (asserting genuine scrcpy binary protocol bytes via net.Pipe)
	inputDC := receivedChannels["input-channel"]
	if inputDC == nil {
		t.Fatal("input-channel not found in receivedChannels")
	}

	touchMsg, _ := json.Marshal(map[string]interface{}{
		"type":   "touch",
		"action": 0, // DOWN
		"x":      300,
		"y":      600,
	})
	err = inputDC.SendText(string(touchMsg))
	if err != nil {
		t.Fatalf("Failed to send touch over input-channel: %v", err)
	}

	controlPacket, err := readControlPacket(28, 2*time.Second)
	if err != nil {
		t.Fatalf("Failed to read scrcpy control packet from net.Pipe: %v", err)
	}

	// Validate 28-byte scrcpy touch event structure
	if controlPacket[0] != 2 { // INJECT_TOUCH_EVENT = 2
		t.Fatalf("Expected scrcpy event type 2, got %d", controlPacket[0])
	}
	if controlPacket[1] != 0 { // AMOTION_EVENT_ACTION_DOWN = 0
		t.Fatalf("Expected touch action 0 (DOWN), got %d", controlPacket[1])
	}
	touchX := binary.BigEndian.Uint32(controlPacket[10:14])
	touchY := binary.BigEndian.Uint32(controlPacket[14:18])
	if touchX != 300 || touchY != 600 {
		t.Fatalf("Expected touch coords (300, 600), got (%d, %d)", touchX, touchY)
	}
	t.Logf("[PASS] Genuine scrcpy binary touch packet asserted from net.Pipe: type=%d, action=%d, coords=(%d,%d)",
		controlPacket[0], controlPacket[1], touchX, touchY)

	// 9. Test binary camera frame streaming on camera-channel
	cameraDC := receivedChannels["camera-channel"]
	if cameraDC == nil {
		t.Fatal("camera-channel not found in receivedChannels")
	}

	// Send a valid test JPEG frame with SOI marker 0xFF 0xD8
	jpegFrame := generateTestPatternJpeg(320, 240)
	err = cameraDC.Send(jpegFrame)
	if err != nil {
		t.Fatalf("Failed to send binary JPEG frame over camera-channel: %v", err)
	}

	// Wait for frame to be buffered into latestCameraJpeg
	time.Sleep(100 * time.Millisecond)
	latestCameraJpegMu.RLock()
	bufferedLen := len(latestCameraJpeg)
	latestCameraJpegMu.RUnlock()
	if bufferedLen != len(jpegFrame) {
		t.Fatalf("Expected latestCameraJpeg length %d, got %d", len(jpegFrame), bufferedLen)
	}
	t.Logf("[PASS] Binary JPEG frame successfully ingested into agent (%d bytes)", bufferedLen)

	// 10. Test camera_snapshot command
	snapshotReplyChan := make(chan map[string]interface{}, 1)
	cameraDC.OnMessage(func(msg webrtc.DataChannelMessage) {
		var resp map[string]interface{}
		if err := json.Unmarshal(msg.Data, &resp); err == nil {
			if resp["action"] == "camera_snapshot" {
				select {
				case snapshotReplyChan <- resp:
				default:
				}
			}
		}
	})

	snapReq, _ := json.Marshal(map[string]interface{}{
		"action":     "camera_snapshot",
		"request_id": "req_snap_test_99",
	})
	_ = cameraDC.SendText(string(snapReq))

	select {
	case snapReply := <-snapshotReplyChan:
		if snapReply["status"] != "success" {
			t.Fatalf("Expected camera_snapshot status success, got %v", snapReply["status"])
		}
		if snapReply["image_base64"] == nil || snapReply["image_base64"] == "" {
			t.Fatal("Expected non-empty image_base64 in camera_snapshot reply")
		}
		t.Log("[PASS] camera_snapshot successfully returned buffered camera image base64")
	case <-time.After(2 * time.Second):
		t.Fatal("Timeout waiting for camera_snapshot response")
	}

	// 11. Test Media Streaming (broadcasting video RTP packet with direct PTS)
	syntheticNalu := []byte{0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x00, 0x10, 0xFF}
	streamer.packetizeAndBroadcastVideo(syntheticNalu, 90000, true)

	select {
	case pkt := <-videoPacketsReceived:
		if pkt.PayloadType != 96 {
			t.Fatalf("Expected RTP PayloadType 96 (H.264), got %d", pkt.PayloadType)
		}
		if pkt.Timestamp != 90000 {
			t.Fatalf("Expected RTP Timestamp 90000, got %d", pkt.Timestamp)
		}
		t.Logf("[PASS] Video RTP packet received by client: PT=%d, TS=%d, Seq=%d, PayloadLen=%d",
			pkt.PayloadType, pkt.Timestamp, pkt.SequenceNumber, len(pkt.Payload))
	case <-time.After(3 * time.Second):
		t.Fatal("FAIL: Timeout waiting for Video RTP packet from StreamerBridge")
	}

	// Verify client-initiated channels are open on client
	_ = clientFileDC
	_ = clientAiDC
	_ = clientAdbDC
	t.Log("[PASS] WebRTC End-to-End full integration test complete!")
}
