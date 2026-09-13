package main

import (
	"crypto/rand"
	"encoding/binary"
	"io"
	"log"
	"net"
	"sync"

	"github.com/pion/rtp"
	"github.com/pion/rtp/codecs"
)

type StreamerBridge struct {
	sessionsMu      sync.RWMutex
	sessions        map[string]*WebRTCSession
	control         *ControlWriter
	previewStreamer *PreviewStreamer
	fps             int
	running         bool
	mu              sync.Mutex

	// Codec Configuration Cache (SPS / PPS)
	cachedCodecConfig []byte
	configMu          sync.RWMutex

	// Shared Media Timeline Epoch & Reference Clock (immutable after initial packet)
	timeline *MediaTimeline

	// Video RTP State (90,000 Hz clock)
	videoPayloader codecs.H264Payloader
	videoSSRC      uint32

	// Audio RTP State (48,000 Hz clock)
	audioPayloader codecs.OpusPayloader
	audioSSRC      uint32
}

// MediaTimeline manages an immutable common temporal origin epoch (microsecond hardware PTS)
// and maps both Video (90 kHz) and Audio (48 kHz) to synchronized RTP timestamps.
type MediaTimeline struct {
	mu           sync.RWMutex
	initialized  bool
	epochPtsUs   int64  // Immutable reference origin in microseconds
	videoRtpBase uint32 // 90 kHz base RTP timestamp
	audioRtpBase uint32 // 48 kHz base RTP timestamp
	generation   uint32 // Monotonic discontinuity generation counter
	lastVideoPts int64
	lastAudioPts int64
}

func NewMediaTimeline(vBase, aBase uint32) *MediaTimeline {
	return &MediaTimeline{
		videoRtpBase: vBase,
		audioRtpBase: aBase,
	}
}

// Reset atomically resets the common timeline origin upon major discontinuity
func (mt *MediaTimeline) Reset() {
	mt.mu.Lock()
	defer mt.mu.Unlock()
	mt.initialized = false
	mt.epochPtsUs = 0
	mt.generation++
	mt.lastVideoPts = 0
	mt.lastAudioPts = 0
}

// ComputeVideoTimestamp maps video PTS (microseconds) to 90 kHz RTP timestamp relative to common epoch
func (mt *MediaTimeline) ComputeVideoTimestamp(ptsUs uint64) uint32 {
	mt.mu.Lock()
	defer mt.mu.Unlock()

	pts := int64(ptsUs)
	if !mt.initialized {
		mt.epochPtsUs = pts
		mt.initialized = true
	}
	mt.lastVideoPts = pts

	diffUs := pts - mt.epochPtsUs
	// H.264 Clock Rate: 90,000 Hz
	offset := (diffUs * 90000) / 1000000
	return mt.videoRtpBase + uint32(offset)
}

// ComputeAudioTimestamp maps audio PTS (microseconds) to 48 kHz RTP timestamp relative to common epoch
func (mt *MediaTimeline) ComputeAudioTimestamp(audioPtsUs uint64) uint32 {
	mt.mu.Lock()
	defer mt.mu.Unlock()

	pts := int64(audioPtsUs)
	if !mt.initialized {
		mt.epochPtsUs = pts
		mt.initialized = true
	}
	mt.lastAudioPts = pts

	diffUs := pts - mt.epochPtsUs
	// Opus Audio Clock Rate: 48,000 Hz
	offset := (diffUs * 48000) / 1000000
	return mt.audioRtpBase + uint32(offset)
}

func (mt *MediaTimeline) IsInitialized() bool {
	mt.mu.RLock()
	defer mt.mu.RUnlock()
	return mt.initialized
}

func (mt *MediaTimeline) EpochPtsUs() int64 {
	mt.mu.RLock()
	defer mt.mu.RUnlock()
	return mt.epochPtsUs
}

func NewStreamerBridge(ctrl *ControlWriter, fps int) *StreamerBridge {
	if fps <= 0 {
		fps = 60
	}
	var b [4]byte
	_, _ = rand.Read(b[:])
	vBase := binary.BigEndian.Uint32(b[:])
	_, _ = rand.Read(b[:])
	aBase := binary.BigEndian.Uint32(b[:])
	_, _ = rand.Read(b[:])
	vSSRC := binary.BigEndian.Uint32(b[:])
	_, _ = rand.Read(b[:])
	aSSRC := binary.BigEndian.Uint32(b[:])

	return &StreamerBridge{
		sessions:       make(map[string]*WebRTCSession),
		control:        ctrl,
		fps:            fps,
		running:        true,
		timeline:       NewMediaTimeline(vBase, aBase),
		videoSSRC:      vSSRC,
		audioSSRC:      aSSRC,
	}
}

// ResetTimeline resets the shared media timeline epoch on reconnect or major discontinuity
func (sb *StreamerBridge) ResetTimeline() {
	sb.timeline.Reset()
}

// computeVideoRtpTimestamp maps video PTS (microseconds) to 90 kHz RTP timestamp relative to common epoch
func (sb *StreamerBridge) computeVideoRtpTimestamp(ptsUs uint64) uint32 {
	return sb.timeline.ComputeVideoTimestamp(ptsUs)
}

// computeAudioRtpTimestamp maps audio PTS (microseconds) to 48 kHz RTP timestamp relative to common epoch
func (sb *StreamerBridge) computeAudioRtpTimestamp(audioPtsUs uint64) uint32 {
	return sb.timeline.ComputeAudioTimestamp(audioPtsUs)
}

func (sb *StreamerBridge) SetPreviewStreamer(p *PreviewStreamer) {
	sb.mu.Lock()
	defer sb.mu.Unlock()
	sb.previewStreamer = p
}

func (sb *StreamerBridge) RegisterSession(clientID string, sess *WebRTCSession) {
	sb.sessionsMu.Lock()
	sb.sessions[clientID] = sess
	sb.sessionsMu.Unlock()

	// Immediately deliver cached SPS/PPS codec configuration to the new session as RTP packets
	// using the session's own monotonic sequence numbering (isolated from other viewers)
	sb.configMu.RLock()
	if len(sb.cachedCodecConfig) > 0 {
		configPacketsData := parseH264ConfigPackets(sb.cachedCodecConfig)
		for i, pData := range configPacketsData {
			isLast := (i == len(configPacketsData)-1)
			pkt := &rtp.Packet{
				Header: rtp.Header{
					Version:        2,
					PayloadType:    96,
					SequenceNumber: sess.NextVideoSeq(),
					Timestamp:      sb.timeline.videoRtpBase,
					SSRC:           sb.videoSSRC,
					Marker:         isLast,
				},
				Payload: pData,
			}
			sess.EnqueueVideoPacket(pkt, true)
		}
	}
	sb.configMu.RUnlock()

	// Proactively request an IDR Keyframe so the new viewer renders immediately
	if sb.control != nil {
		_ = sb.control.RequestKeyframe()
	}

	log.Printf("[Streamer] Registered WebRTC session for client: %s (Total active: %d)", clientID, len(sb.sessions))
}

func (sb *StreamerBridge) UnregisterSession(clientID string) {
	sb.sessionsMu.Lock()
	defer sb.sessionsMu.Unlock()
	delete(sb.sessions, clientID)
	log.Printf("[Streamer] Unregistered WebRTC session for client: %s (Remaining active: %d)", clientID, len(sb.sessions))
}

// packetizeAndBroadcastVideo packetizes H.264 payload into MTU fragments with exact PTS RTP timestamp
// and delivers to each active session using isolated per-session sequence numbers
func (sb *StreamerBridge) packetizeAndBroadcastVideo(payload []byte, rtpTimestamp uint32, isKeyFrame bool) {
	packetsData := sb.videoPayloader.Payload(1200, payload)
	if len(packetsData) == 0 {
		return
	}

	sb.sessionsMu.RLock()
	defer sb.sessionsMu.RUnlock()
	if len(sb.sessions) == 0 {
		return
	}

	for _, sess := range sb.sessions {
		for i, pData := range packetsData {
			isLast := (i == len(packetsData)-1)
			pkt := &rtp.Packet{
				Header: rtp.Header{
					Version:        2,
					PayloadType:    96,
					SequenceNumber: sess.NextVideoSeq(),
					Timestamp:      rtpTimestamp,
					SSRC:           sb.videoSSRC,
					Marker:         isLast,
				},
				Payload: pData,
			}
			sess.EnqueueVideoPacket(pkt, isKeyFrame)
		}
	}
}

// packetizeAndBroadcastAudio packetizes Opus payload into RTP packets with exact PTS RTP timestamp
// and delivers to each active session using isolated per-session sequence numbers
func (sb *StreamerBridge) packetizeAndBroadcastAudio(payload []byte, rtpTimestamp uint32) {
	packetsData := sb.audioPayloader.Payload(1200, payload)
	if len(packetsData) == 0 {
		return
	}

	sb.sessionsMu.RLock()
	defer sb.sessionsMu.RUnlock()
	if len(sb.sessions) == 0 {
		return
	}

	for _, sess := range sb.sessions {
		for _, pData := range packetsData {
			pkt := &rtp.Packet{
				Header: rtp.Header{
					Version:        2,
					PayloadType:    111,
					SequenceNumber: sess.NextAudioSeq(),
					Timestamp:      rtpTimestamp,
					SSRC:           sb.audioSSRC,
					Marker:         true,
				},
				Payload: pData,
			}
			sess.EnqueueAudioPacket(pkt)
		}
	}
}

// StreamVideo reads H.264 video packets from scrcpy video socket and broadcasts to all active WebRTC sessions
func (sb *StreamerBridge) StreamVideo(conn net.Conn) {
	defer conn.Close()

	// 1. Read Video Codec Header: CodecID (4B) + Width (4B) + Height (4B) = 12 Bytes
	headerBuf := make([]byte, 12)
	if _, err := io.ReadFull(conn, headerBuf); err != nil {
		log.Printf("[Streamer] Failed to read video codec header: %v", err)
		return
	}

	codecID := binary.BigEndian.Uint32(headerBuf[0:4])
	width := binary.BigEndian.Uint32(headerBuf[4:8])
	height := binary.BigEndian.Uint32(headerBuf[8:12])
	log.Printf("[Streamer] Video Header: Codec=0x%x, Dimensions=%dx%d", codecID, width, height)

	frameHeader := make([]byte, 12)

	for sb.running {
		// 2. Read Frame Header: PTS (8B) + Packet Size (4B) = 12 Bytes
		if _, err := io.ReadFull(conn, frameHeader); err != nil {
			log.Printf("[Streamer] Video frame header read error: %v", err)
			break
		}

		rawPts := binary.BigEndian.Uint64(frameHeader[0:8])
		packetSize := binary.BigEndian.Uint32(frameHeader[8:12])

		isConfig := (rawPts & PacketFlagConfig) != 0
		isKeyFrame := (rawPts & PacketFlagKeyFrame) != 0
		ptsUs := rawPts & PacketPtsMask

		payload := make([]byte, packetSize)
		if _, err := io.ReadFull(conn, payload); err != nil {
			log.Printf("[Streamer] Video payload read error: %v", err)
			break
		}

		// Handle SPS/PPS Codec Configuration
		if isConfig {
			sb.configMu.Lock()
			sb.cachedCodecConfig = make([]byte, len(payload))
			copy(sb.cachedCodecConfig, payload)
			sb.configMu.Unlock()

			rtpTimestamp := sb.computeVideoRtpTimestamp(ptsUs)
			sb.packetizeAndBroadcastVideo(payload, rtpTimestamp, true)

			if sb.previewStreamer != nil && sb.previewStreamer.IsActive() {
				_ = sb.previewStreamer.SendFrame(payload, true, ptsUs)
			}
			continue
		}

		// Direct Hardware PTS Passthrough to RTP Timestamp
		rtpTimestamp := sb.computeVideoRtpTimestamp(ptsUs)
		sb.packetizeAndBroadcastVideo(payload, rtpTimestamp, isKeyFrame)

		// WebSocket Fallback Preview Stream (PREV framing)
		if sb.previewStreamer != nil && sb.previewStreamer.IsActive() {
			_ = sb.previewStreamer.SendFrame(payload, isKeyFrame, ptsUs)
		}
	}
}

// StreamAudio reads Opus packets from scrcpy audio socket and broadcasts to all active WebRTC sessions and preview streamer
func (sb *StreamerBridge) StreamAudio(conn net.Conn, preview *PreviewStreamer) {
	defer conn.Close()

	// 1. Read Audio Codec Header: CodecID (4B)
	headerBuf := make([]byte, 4)
	if _, err := io.ReadFull(conn, headerBuf); err != nil {
		log.Printf("[Streamer] Failed to read audio codec header: %v", err)
		return
	}

	audioHeader := make([]byte, 12)

	for sb.running {
		if _, err := io.ReadFull(conn, audioHeader); err != nil {
			break
		}

		rawPts := binary.BigEndian.Uint64(audioHeader[0:8])
		packetSize := binary.BigEndian.Uint32(audioHeader[8:12])
		audioPtsUs := rawPts & PacketPtsMask

		payload := make([]byte, packetSize)
		if _, err := io.ReadFull(conn, payload); err != nil {
			break
		}

		// Direct Hardware PTS Passthrough to RTP Timestamp (shared epoch with video)
		audioRtpTimestamp := sb.computeAudioRtpTimestamp(audioPtsUs)
		sb.packetizeAndBroadcastAudio(payload, audioRtpTimestamp)

		if preview != nil && preview.IsActive() {
			_ = preview.SendAudio(payload)
		}
	}
}

func (sb *StreamerBridge) Stop() {
	sb.mu.Lock()
	defer sb.mu.Unlock()
	sb.running = false
}

// parseH264ConfigPackets parses cached SPS/PPS NAL units and formats them for immediate RTP transmission.
// If both SPS and PPS are present, it wraps them into an RFC 6184 STAP-A aggregation packet.
func parseH264ConfigPackets(config []byte) [][]byte {
	if len(config) == 0 {
		return nil
	}
	// Split Annex B stream by start codes 0x000001 or 0x00000001
	var nalus [][]byte
	start := -1
	for i := 0; i < len(config); {
		if i+3 <= len(config) && config[i] == 0x00 && config[i+1] == 0x00 && config[i+2] == 0x01 {
			if start != -1 && i > start {
				nalus = append(nalus, config[start:i])
			}
			i += 3
			start = i
			continue
		}
		if i+4 <= len(config) && config[i] == 0x00 && config[i+1] == 0x00 && config[i+2] == 0x00 && config[i+3] == 0x01 {
			if start != -1 && i > start {
				nalus = append(nalus, config[start:i])
			}
			i += 4
			start = i
			continue
		}
		i++
	}
	if start != -1 && start < len(config) {
		nalus = append(nalus, config[start:])
	}
	if len(nalus) == 0 {
		nalus = append(nalus, config)
	}

	var sps, pps []byte
	var otherNalus [][]byte
	for _, nalu := range nalus {
		if len(nalu) == 0 {
			continue
		}
		naluType := nalu[0] & 0x1F
		if naluType == 7 {
			sps = nalu
		} else if naluType == 8 {
			pps = nalu
		} else {
			otherNalus = append(otherNalus, nalu)
		}
	}

	var packets [][]byte
	// If both SPS and PPS are present, bundle into RFC 6184 STAP-A (Type 24)
	if len(sps) > 0 && len(pps) > 0 {
		stapA := make([]byte, 1+2+len(sps)+2+len(pps))
		stapA[0] = 0x78 // STAP-A: F=0, NRI=3, Type=24
		binary.BigEndian.PutUint16(stapA[1:3], uint16(len(sps)))
		copy(stapA[3:3+len(sps)], sps)
		offset := 3 + len(sps)
		binary.BigEndian.PutUint16(stapA[offset:offset+2], uint16(len(pps)))
		copy(stapA[offset+2:], pps)
		packets = append(packets, stapA)
	} else {
		if len(sps) > 0 {
			packets = append(packets, sps)
		}
		if len(pps) > 0 {
			packets = append(packets, pps)
		}
	}
	for _, n := range otherNalus {
		packets = append(packets, n)
	}
	return packets
}

