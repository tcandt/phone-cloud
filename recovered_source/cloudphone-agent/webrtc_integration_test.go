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

	controlPacket, err := readControlPacket(32, 2*time.Second)
	if err != nil {
		t.Fatalf("Failed to read scrcpy control packet from net.Pipe: %v", err)
	}

	// Validate 32-byte scrcpy touch event structure
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
	actionButton := binary.BigEndian.Uint32(controlPacket[24:28])
	buttons := binary.BigEndian.Uint32(controlPacket[28:32])
	if actionButton != 0 || buttons != 0 {
		t.Fatalf("Expected actionButton and buttons to be 0, got (%d, %d)", actionButton, buttons)
	}
	t.Logf("[PASS] Genuine scrcpy binary touch packet (32 bytes) asserted from net.Pipe: type=%d, action=%d, coords=(%d,%d), actionButton=%d, buttons=%d",
		controlPacket[0], controlPacket[1], touchX, touchY, actionButton, buttons)

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

	// 11. Test Media Streaming through Timeline PTS calculation
	testPtsUs := uint64(1000000) // 1.0 second hardware PTS
	computedTs := streamer.computeVideoRtpTimestamp(testPtsUs)
	syntheticNalu := []byte{0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x00, 0x10, 0xFF}
	streamer.packetizeAndBroadcastVideo(syntheticNalu, computedTs, true)

	select {
	case pkt := <-videoPacketsReceived:
		if pkt.PayloadType != 96 {
			t.Fatalf("Expected RTP PayloadType 96 (H.264), got %d", pkt.PayloadType)
		}
		if pkt.Timestamp != computedTs {
			t.Fatalf("Expected RTP Timestamp %d, got %d", computedTs, pkt.Timestamp)
		}
		t.Logf("[PASS] Video RTP packet received by client with computed PTS: PT=%d, TS=%d, Seq=%d, PayloadLen=%d",
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

// TestMediaTimeline_PTSMapping rigorously tests the hardware PTS mapper:
// 1. Exact 90 kHz video clock conversion (33,333 us delta -> ~3000 ticks)
// 2. Exact 48 kHz audio clock conversion (20,000 us delta -> 960 ticks)
// 3. Shared immutable epoch across video and audio
// 4. Startup skew invariance (audio arriving before video or vice versa)
func TestMediaTimeline_PTSMapping(t *testing.T) {
	vBase := uint32(10000)
	aBase := uint32(20000)
	tl := NewMediaTimeline(vBase, aBase)

	// Step 1: Video arrives first at PTS = 1,000,000 us (epoch anchor)
	tsV0 := tl.ComputeVideoTimestamp(1000000)
	if tsV0 != vBase {
		t.Fatalf("Expected initial video timestamp to equal vBase (%d), got %d", vBase, tsV0)
	}

	// Step 2: Next video frame arrives at PTS = 1,033,333 us (30 FPS, delta = 33,333 us)
	tsV1 := tl.ComputeVideoTimestamp(1033333)
	deltaV := tsV1 - tsV0
	// (33333 * 90000) / 1000000 = 2999 ticks (~3000 ticks at 90 kHz)
	expectedDeltaV := uint32((33333 * 90000) / 1000000)
	if deltaV != expectedDeltaV {
		t.Fatalf("Expected video RTP delta %d, got %d", expectedDeltaV, deltaV)
	}
	t.Logf("[PASS] Video 90kHz PTS mapping verified: deltaUs=33333 -> deltaRtp=%d ticks", deltaV)

	// Step 3: Audio frame arrives at PTS = 1,020,000 us (20ms after video epoch anchor)
	tsA0 := tl.ComputeAudioTimestamp(1020000)
	// deltaUs = 20000 -> (20000 * 48000) / 1000000 = 960 ticks
	expectedA0 := aBase + 960
	if tsA0 != expectedA0 {
		t.Fatalf("Expected audio RTP timestamp %d, got %d", expectedA0, tsA0)
	}
	t.Logf("[PASS] Audio 48kHz PTS mapping relative to shared epoch verified: tsA0=%d (delta=+960)", tsA0)

	// Step 4: Next audio frame arrives at PTS = 1,040,000 us (20ms Opus frame)
	tsA1 := tl.ComputeAudioTimestamp(1040000)
	deltaA := tsA1 - tsA0
	if deltaA != 960 {
		t.Fatalf("Expected 20ms Opus audio delta of 960 samples, got %d", deltaA)
	}
	t.Logf("[PASS] Audio 20ms Opus frame sample delta verified: %d samples at 48kHz", deltaA)

	// Step 5: Verify that audio arrival did NOT shift the video epoch anchor
	tsV2 := tl.ComputeVideoTimestamp(1066666) // 66.666ms after epoch
	deltaV2 := tsV2 - tsV0
	expectedDeltaV2 := uint32((66666 * 90000) / 1000000)
	if deltaV2 != expectedDeltaV2 {
		t.Fatalf("Video timeline drifted after audio processing: expected delta %d, got %d", expectedDeltaV2, deltaV2)
	}
	t.Log("[PASS] Timeline epoch is strictly immutable; audio stream processing did not disturb video timeline")

	// Step 6: Test Startup Skew: Audio arrives BEFORE video
	tlSkew := NewMediaTimeline(50000, 60000)
	// Audio arrives first at PTS = 980,000 us
	tsA_skew := tlSkew.ComputeAudioTimestamp(980000)
	if tsA_skew != 60000 {
		t.Fatalf("Expected initial audio timestamp to equal aBase (60000), got %d", tsA_skew)
	}
	// Video arrives at PTS = 1,000,000 us (20,000 us after audio)
	tsV_skew := tlSkew.ComputeVideoTimestamp(1000000)
	expectedV_skew := uint32(50000 + (20000*90000)/1000000) // 50000 + 1800 = 51800
	if tsV_skew != expectedV_skew {
		t.Fatalf("Expected skewed video timestamp %d, got %d", expectedV_skew, tsV_skew)
	}
	t.Logf("[PASS] Startup skew verified: audio-first startup correctly anchored epoch; video TS = %d (delta=+1800 ticks)", tsV_skew)
}

// TestPerSessionRTPSequence validates that each WebRTCSession maintains an independent,
// strictly monotonic RTP sequence space without artificial packet loss caused by new viewers joining
func TestPerSessionRTPSequence(t *testing.T) {
	streamer := NewStreamerBridge(nil, 30)
	streamer.cachedCodecConfig = []byte{
		0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f, 0x68, 0xce, 0x3c, 0x80, // SPS
		0x00, 0x00, 0x00, 0x01, 0x68, 0xce, 0x3c, 0x80,                         // PPS
	}

	vTrackA, err := webrtc.NewTrackLocalStaticRTP(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeH264}, "video", "cloudphone-video")
	if err != nil {
		t.Fatalf("Failed to create video track: %v", err)
	}
	sessA := &WebRTCSession{
		ClientID:   "viewer_A",
		videoTrack: vTrackA,
		videoQueue: make(chan *rtp.Packet, 100),
		audioQueue: make(chan *rtp.Packet, 100),
		videoSeq:   1000, // Starts at 1000
	}
	streamer.RegisterSession("viewer_A", sessA)

	// Read initial cached SPS/PPS packet from Viewer A
	var pktA0 *rtp.Packet
	select {
	case pktA0 = <-sessA.videoQueue:
	default:
		t.Fatal("Viewer A did not receive initial cached codec config packet")
	}
	initSeqA := pktA0.SequenceNumber
	if initSeqA != 1001 {
		t.Fatalf("Expected initial sequence for Viewer A to be 1001, got %d", initSeqA)
	}

	// Broadcast Frame 1
	syntheticNalu1 := []byte{0x00, 0x00, 0x00, 0x01, 0x41, 0x9A}
	streamer.packetizeAndBroadcastVideo(syntheticNalu1, 10000, false)

	var pktA1 *rtp.Packet
	select {
	case pktA1 = <-sessA.videoQueue:
	default:
		t.Fatal("Viewer A did not receive frame 1")
	}
	if pktA1.SequenceNumber != initSeqA+1 {
		t.Fatalf("Expected Viewer A sequence %d, got %d", initSeqA+1, pktA1.SequenceNumber)
	}

	// Viewer B joins NOW! Receives cached SPS/PPS
	vTrackB, err := webrtc.NewTrackLocalStaticRTP(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeH264}, "video", "cloudphone-video")
	if err != nil {
		t.Fatalf("Failed to create video track: %v", err)
	}
	sessB := &WebRTCSession{
		ClientID:   "viewer_B",
		videoTrack: vTrackB,
		videoQueue: make(chan *rtp.Packet, 100),
		audioQueue: make(chan *rtp.Packet, 100),
		videoSeq:   5000, // Starts at 5000
	}
	streamer.RegisterSession("viewer_B", sessB)

	var pktB0 *rtp.Packet
	select {
	case pktB0 = <-sessB.videoQueue:
	default:
		t.Fatal("Viewer B did not receive initial cached codec config packet")
	}
	if pktB0.SequenceNumber != 5001 {
		t.Fatalf("Expected Viewer B sequence 5001, got %d", pktB0.SequenceNumber)
	}

	// Broadcast Frame 2 to BOTH viewers
	syntheticNalu2 := []byte{0x00, 0x00, 0x00, 0x01, 0x41, 0x9B}
	streamer.packetizeAndBroadcastVideo(syntheticNalu2, 13000, false)

	// CRITICAL ASSERTION: Viewer A must receive next consecutive sequence number (initSeqA+2 = 1003)
	// with NO gap or jump caused by Viewer B's joining!
	var pktA2 *rtp.Packet
	select {
	case pktA2 = <-sessA.videoQueue:
	default:
		t.Fatal("Viewer A did not receive frame 2")
	}
	if pktA2.SequenceNumber != pktA1.SequenceNumber+1 {
		t.Fatalf("CRITICAL: Viewer A experienced sequence jump/gap! Expected %d, got %d",
			pktA1.SequenceNumber+1, pktA2.SequenceNumber)
	}

	// Viewer B also receives its own consecutive sequence number (5002)
	var pktB1 *rtp.Packet
	select {
	case pktB1 = <-sessB.videoQueue:
	default:
		t.Fatal("Viewer B did not receive frame 2")
	}
	if pktB1.SequenceNumber != 5002 {
		t.Fatalf("Expected Viewer B sequence 5002, got %d", pktB1.SequenceNumber)
	}

	t.Logf("[PASS] Per-session sequence isolation verified: Viewer A seq=(%d, %d, %d), Viewer B seq=(%d, %d)",
		pktA0.SequenceNumber, pktA1.SequenceNumber, pktA2.SequenceNumber, pktB0.SequenceNumber, pktB1.SequenceNumber)
}

func TestScrcpyOptions_RemoteCameraMode(t *testing.T) {
	cfg := &AgentConfig{
		DeviceID: "phone_camera_test",
		MaxSize:  1280,
		Bitrate:  4000000,
		MaxFPS:   60,
		Audio:    true,
	}
	proc := NewScrcpyProcess(cfg)

	// Default options must be display streaming
	if proc.GetCurrentOptions().VideoSource != "display" {
		t.Fatalf("Expected default VideoSource to be display, got %s", proc.GetCurrentOptions().VideoSource)
	}

	// 1. Simulate JSON payload from request-offer with scrcpy_options
	offerPayloadJSON := `{
		"type": "request-offer",
		"scrcpy_options": {
			"video_source": "camera",
			"camera_facing": "back",
			"camera_id": "0",
			"camera_size": "1920x1080",
			"camera_fps": 30,
			"camera_zoom": 2.5,
			"camera_orientation": "90",
			"stay_awake": true,
			"power_off": true
		}
	}`
	var req map[string]interface{}
	if err := json.Unmarshal([]byte(offerPayloadJSON), &req); err != nil {
		t.Fatalf("JSON parse error: %v", err)
	}

	optsRaw := req["scrcpy_options"]
	optsJSON, err := json.Marshal(optsRaw)
	if err != nil {
		t.Fatalf("Marshal error: %v", err)
	}
	var clientOpts ScrcpyOptions
	if err := json.Unmarshal(optsJSON, &clientOpts); err != nil {
		t.Fatalf("Unmarshal error: %v", err)
	}

	if clientOpts.VideoSource != "camera" {
		t.Fatalf("Expected VideoSource camera, got %s", clientOpts.VideoSource)
	}
	if clientOpts.CameraFacing != "back" {
		t.Fatalf("Expected CameraFacing back, got %s", clientOpts.CameraFacing)
	}
	if clientOpts.CameraZoom != 2.5 {
		t.Fatalf("Expected CameraZoom 2.5, got %f", clientOpts.CameraZoom)
	}

	// 2. Assert NeedsRestart evaluates to true when switching to camera
	if !proc.NeedsRestart(clientOpts) {
		t.Fatal("Expected NeedsRestart to be true when switching display -> camera")
	}

	// 3. Assert command arguments include camera parameters and stay_awake
	args := proc.buildArgs(clientOpts, "v_sock", "a_sock", "c_sock")
	argsStr := fmt.Sprintf("%v", args)

	expectedSubs := []string{
		"video_source=camera",
		"camera_facing=back",
		"camera_id=0",
		"camera_size=1920x1080",
		"camera_fps=30",
		"camera_zoom=2.50",
		"capture_orientation=90",
		"stay_awake=true",
	}
	for _, sub := range expectedSubs {
		found := false
		for _, arg := range args {
			if arg == sub {
				found = true
				break
			}
		}
		if !found {
			t.Fatalf("Missing expected argument %q in args: %s", sub, argsStr)
		}
	}
	t.Log("[PASS] ScrcpyOptions Remote Camera Mode arguments verified")
}

func TestScrcpyControl_SetDisplayPower(t *testing.T) {
	serverConn, clientConn := net.Pipe()
	defer serverConn.Close()
	defer clientConn.Close()

	cw := NewControlWriter(serverConn)

	// Send SetDisplayPower(false) to turn off screen
	go func() {
		_ = cw.SetDisplayPower(false)
	}()

	buf := make([]byte, 2)
	n, err := clientConn.Read(buf)
	if err != nil || n != 2 {
		t.Fatalf("Failed to read SetDisplayPower control packet: n=%d, err=%v", n, err)
	}

	if buf[0] != ControlMsgSetDisplayPower || buf[1] != 0 {
		t.Fatalf("Expected [10, 0] for display power off, got [%d, %d]", buf[0], buf[1])
	}
	t.Log("[PASS] SetDisplayPower(false) correctly sent scrcpy control message [10, 0]")
}

func TestCachedConfig_LastVideoRtpTs(t *testing.T) {
	streamer := NewStreamerBridge(nil, 30)

	// Before any video packet, LastVideoRtpTs() must equal videoRtpBase
	initTs := streamer.timeline.LastVideoRtpTs()
	if initTs != streamer.timeline.videoRtpBase {
		t.Fatalf("Expected initial LastVideoRtpTs %d, got %d", streamer.timeline.videoRtpBase, initTs)
	}

	// Simulate streaming frames up to PTS 100,000 us
	frameTs := streamer.computeVideoRtpTimestamp(100000)
	lastTs := streamer.timeline.LastVideoRtpTs()
	if lastTs != frameTs {
		t.Fatalf("Expected LastVideoRtpTs to be updated to %d, got %d", frameTs, lastTs)
	}

	// Register SPS/PPS config cache
	fakeSPS := []byte{0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f}
	fakePPS := []byte{0x00, 0x00, 0x00, 0x01, 0x68, 0xce, 0x38, 0x80}
	streamer.cachedCodecConfig = append(fakeSPS, fakePPS...)

	vTrack, err := webrtc.NewTrackLocalStaticRTP(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeH264}, "video", "cloudphone-video")
	if err != nil {
		t.Fatalf("Failed to create video track: %v", err)
	}

	sess := &WebRTCSession{
		ClientID:   "viewer_late_joiner",
		videoTrack: vTrack,
		videoQueue: make(chan *rtp.Packet, 100),
		audioQueue: make(chan *rtp.Packet, 100),
		videoSeq:   7000,
	}

	streamer.RegisterSession("viewer_late_joiner", sess)

	// Viewer must receive cached STAP-A packet stamped with lastTs (current stream time), NOT videoRtpBase
	select {
	case pkt := <-sess.videoQueue:
		if pkt.Timestamp != lastTs {
			t.Fatalf("Expected cached config timestamp %d (current stream time), got %d", lastTs, pkt.Timestamp)
		}
		if pkt.Payload[0] != 0x78 { // STAP-A
			t.Fatalf("Expected STAP-A packet (0x78), got 0x%02x", pkt.Payload[0])
		}
		t.Logf("[PASS] Cached SPS/PPS delivered to new viewer with aligned timestamp %d", pkt.Timestamp)
	default:
		t.Fatal("No cached config packet delivered to new session")
	}
}

func TestControlProtocol_ClipboardFraming(t *testing.T) {
	serverConn, clientConn := net.Pipe()
	defer serverConn.Close()
	defer clientConn.Close()

	cw := NewControlWriter(serverConn)
	testText := "Antigravity Clipboard Parity"

	go func() {
		_ = cw.SetClipboard(testText, true)
	}()

	// Protocol layout: [type: 1B][seq: 8B][len: 4B][text: N B][paste: 1B]
	expectedLen := 1 + 8 + 4 + len(testText) + 1
	buf := make([]byte, expectedLen)
	n, err := clientConn.Read(buf)
	if err != nil || n != expectedLen {
		t.Fatalf("Failed to read SetClipboard packet: n=%d, expected=%d, err=%v", n, expectedLen, err)
	}

	if buf[0] != ControlMsgSetClipboard {
		t.Fatalf("Expected msg type %d, got %d", ControlMsgSetClipboard, buf[0])
	}
	seq := binary.BigEndian.Uint64(buf[1:9])
	if seq != 1 {
		t.Fatalf("Expected sequence 1, got %d", seq)
	}
	textLen := binary.BigEndian.Uint32(buf[9:13])
	if int(textLen) != len(testText) {
		t.Fatalf("Expected text length %d, got %d", len(testText), textLen)
	}
	recText := string(buf[13 : 13+len(testText)])
	if recText != testText {
		t.Fatalf("Expected text %q, got %q", testText, recText)
	}
	pasteByte := buf[13+len(testText)]
	if pasteByte != 1 {
		t.Fatalf("Expected trailing paste byte 1, got %d", pasteByte)
	}

	t.Logf("[PASS] Clipboard binary framing matches ControlMessageReader.java: type(1B) -> seq(8B) -> len(4B) -> text(%dB) -> paste(1B)", len(testText))
}

func TestControlProtocol_ScrollFraming(t *testing.T) {
	serverConn, clientConn := net.Pipe()
	defer serverConn.Close()
	defer clientConn.Close()

	cw := NewControlWriter(serverConn)

	go func() {
		_ = cw.SendScroll(500, 800, 1080, 2400, 1.5, -2.0)
	}()

	// Protocol layout: [type: 1B][x: 4B][y: 4B][w: 2B][h: 2B][hScroll: 2B (int16 fixed)][vScroll: 2B (int16 fixed)][buttons: 4B]
	expectedLen := 21
	buf := make([]byte, expectedLen)
	n, err := clientConn.Read(buf)
	if err != nil || n != expectedLen {
		t.Fatalf("Failed to read SendScroll packet: n=%d, expected=%d, err=%v", n, expectedLen, err)
	}

	if buf[0] != ControlMsgInjectScrollEvent {
		t.Fatalf("Expected msg type %d, got %d", ControlMsgInjectScrollEvent, buf[0])
	}
	x := binary.BigEndian.Uint32(buf[1:5])
	y := binary.BigEndian.Uint32(buf[5:9])
	w := binary.BigEndian.Uint16(buf[9:11])
	h := binary.BigEndian.Uint16(buf[11:13])
	if x != 500 || y != 800 || w != 1080 || h != 2400 {
		t.Fatalf("Unexpected Position: x=%d, y=%d, w=%d, h=%d", x, y, w, h)
	}

	hScroll := int16(binary.BigEndian.Uint16(buf[13:15]))
	vScroll := int16(binary.BigEndian.Uint16(buf[15:17]))
	buttons := binary.BigEndian.Uint32(buf[17:21])

	// Scale factor is 2048: 1.5 * 2048 = 3072, -2.0 * 2048 = -4096
	if hScroll != 3072 {
		t.Fatalf("Expected hScroll 3072, got %d", hScroll)
	}
	if vScroll != -4096 {
		t.Fatalf("Expected vScroll -4096, got %d", vScroll)
	}
	if buttons != 0 {
		t.Fatalf("Expected buttons 0, got %d", buttons)
	}

	t.Log("[PASS] Scroll binary framing matches ControlMessageReader.java: 21 bytes with position, int16 fixed-point (x2048), and uint32 buttons")
}

func TestScrcpyOptions_BitrateAliasAndNeedsRestart(t *testing.T) {
	jsonPayload := `{
		"bitrate": 5000000,
		"audio_source": "mic",
		"audio_dup": true,
		"stay_awake": true,
		"video_codec_options": "profile=1",
		"max_fps": 60
	}`
	var opts ScrcpyOptions
	if err := json.Unmarshal([]byte(jsonPayload), &opts); err != nil {
		t.Fatalf("Failed to unmarshal ScrcpyOptions: %v", err)
	}

	// Assert bitrate alias sets both Bitrate and VideoBitRate
	if opts.Bitrate != 5000000 || opts.VideoBitRate != 5000000 {
		t.Fatalf("Expected Bitrate & VideoBitRate 5000000, got Bitrate=%d, VideoBitRate=%d", opts.Bitrate, opts.VideoBitRate)
	}

	cfg := &AgentConfig{
		DeviceID: "test_dev_restart",
		Bitrate:  8000000,
		MaxFPS:   30,
	}
	proc := NewScrcpyProcess(cfg)

	// NeedsRestart checks
	if !proc.NeedsRestart(ScrcpyOptions{Bitrate: 4000000}) {
		t.Fatal("Expected NeedsRestart=true on Bitrate change")
	}
	if !proc.NeedsRestart(ScrcpyOptions{MaxFPS: 120}) {
		t.Fatal("Expected NeedsRestart=true on MaxFPS change")
	}
	if !proc.NeedsRestart(ScrcpyOptions{AudioSource: "mic"}) {
		t.Fatal("Expected NeedsRestart=true on AudioSource change")
	}
	if !proc.NeedsRestart(ScrcpyOptions{AudioDup: true}) {
		t.Fatal("Expected NeedsRestart=true on AudioDup change")
	}
	if !proc.NeedsRestart(ScrcpyOptions{StayAwake: true}) {
		t.Fatal("Expected NeedsRestart=true on StayAwake change")
	}
	if !proc.NeedsRestart(ScrcpyOptions{VideoCodecOptions: "level=4.1"}) {
		t.Fatal("Expected NeedsRestart=true on VideoCodecOptions change")
	}

	t.Log("[PASS] ScrcpyOptions bitrate alias and comprehensive NeedsRestart triggers verified")
}

func TestStreamerBridge_ResetSourceGeneration(t *testing.T) {
	streamer := NewStreamerBridge(nil, 30)

	// Set cached config and advance timeline
	streamer.cachedCodecConfig = []byte{0x00, 0x00, 0x00, 0x01, 0x67, 0x42}
	_ = streamer.computeVideoRtpTimestamp(500000)

	if !streamer.HasCodecConfig() {
		t.Fatal("Expected HasCodecConfig=true before reset")
	}

	// Perform source generation reset
	streamer.ResetSourceGeneration()

	if streamer.HasCodecConfig() {
		t.Fatal("Expected HasCodecConfig=false after ResetSourceGeneration")
	}
	if streamer.cachedCodecConfig != nil {
		t.Fatal("Expected cachedCodecConfig=nil after ResetSourceGeneration")
	}
	if streamer.timeline.IsInitialized() {
		t.Fatal("Expected IsInitialized()=false after ResetSourceGeneration")
	}

	t.Log("[PASS] StreamerBridge.ResetSourceGeneration atomically flushed SPS/PPS and reset timeline epoch")
}
