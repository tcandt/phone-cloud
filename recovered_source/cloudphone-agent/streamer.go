package main

import (
	"crypto/rand"
	"encoding/binary"
	"io"
	"log"
	"net"
	"sync"
	"time"

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

	// Shared Media Timeline Epoch & Reference Clock
	timelineMu     sync.Mutex
	hasCommonEpoch bool
	basePtsUs      uint64
	baseWallClock  time.Time

	// Video RTP State (90,000 Hz clock)
	videoPayloader codecs.H264Payloader
	videoSeq       uint16
	videoRtpBase   uint32
	videoSSRC      uint32
	prevVideoPtsUs uint64
	hasVideoPrev   bool

	// Audio RTP State (48,000 Hz clock)
	audioPayloader codecs.OpusPayloader
	audioSeq       uint16
	audioRtpBase   uint32
	audioSSRC      uint32
	prevAudioPtsUs uint64
	hasAudioPrev   bool
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
		sessions:     make(map[string]*WebRTCSession),
		control:      ctrl,
		fps:          fps,
		running:      true,
		videoRtpBase: vBase,
		audioRtpBase: aBase,
		videoSSRC:    vSSRC,
		audioSSRC:    aSSRC,
	}
}

// ResetTimeline resets the shared media timeline epoch on reconnect or major discontinuity
func (sb *StreamerBridge) ResetTimeline() {
	sb.timelineMu.Lock()
	defer sb.timelineMu.Unlock()
	sb.hasCommonEpoch = false
	sb.basePtsUs = 0
	sb.baseWallClock = time.Time{}
	sb.hasVideoPrev = false
	sb.prevVideoPtsUs = 0
	sb.hasAudioPrev = false
	sb.prevAudioPtsUs = 0
}

// computeVideoRtpTimestamp maps video PTS (microseconds) to 90 kHz RTP timestamp relative to common epoch
func (sb *StreamerBridge) computeVideoRtpTimestamp(ptsUs uint64) uint32 {
	sb.timelineMu.Lock()
	defer sb.timelineMu.Unlock()

	now := time.Now()
	if !sb.hasCommonEpoch {
		sb.basePtsUs = ptsUs
		sb.baseWallClock = now
		sb.hasCommonEpoch = true
	}

	var elapsedUs uint64
	if ptsUs >= sb.basePtsUs {
		elapsedUs = ptsUs - sb.basePtsUs
	} else {
		// Timestamp backwards: re-anchor epoch
		sb.basePtsUs = ptsUs
		sb.baseWallClock = now
		elapsedUs = 0
	}

	// Discontinuity check (> 3s gap)
	if sb.hasVideoPrev && ptsUs > sb.prevVideoPtsUs && (ptsUs-sb.prevVideoPtsUs) > 3000000 {
		sb.basePtsUs = ptsUs
		sb.baseWallClock = now
		elapsedUs = 0
	}
	sb.prevVideoPtsUs = ptsUs
	sb.hasVideoPrev = true

	// H.264 Clock Rate: 90,000 Hz
	rtpOffset := uint32((elapsedUs * 90000) / 1000000)
	return sb.videoRtpBase + rtpOffset
}

// computeAudioRtpTimestamp maps audio PTS (microseconds) to 48 kHz RTP timestamp relative to common epoch
func (sb *StreamerBridge) computeAudioRtpTimestamp(audioPtsUs uint64) uint32 {
	sb.timelineMu.Lock()
	defer sb.timelineMu.Unlock()

	now := time.Now()
	if !sb.hasCommonEpoch {
		sb.basePtsUs = audioPtsUs
		sb.baseWallClock = now
		sb.hasCommonEpoch = true
	}

	var elapsedUs uint64
	if audioPtsUs >= sb.basePtsUs {
		elapsedUs = audioPtsUs - sb.basePtsUs
	} else {
		sb.basePtsUs = audioPtsUs
		sb.baseWallClock = now
		elapsedUs = 0
	}

	if sb.hasAudioPrev && audioPtsUs > sb.prevAudioPtsUs && (audioPtsUs-sb.prevAudioPtsUs) > 3000000 {
		sb.basePtsUs = audioPtsUs
		sb.baseWallClock = now
		elapsedUs = 0
	}
	sb.prevAudioPtsUs = audioPtsUs
	sb.hasAudioPrev = true

	// Opus Audio Clock Rate: 48,000 Hz
	rtpOffset := uint32((elapsedUs * 48000) / 1000000)
	return sb.audioRtpBase + rtpOffset
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
	sb.configMu.RLock()
	if len(sb.cachedCodecConfig) > 0 {
		configPacketsData := sb.videoPayloader.Payload(1200, sb.cachedCodecConfig)
		sb.timelineMu.Lock()
		for i, pData := range configPacketsData {
			sb.videoSeq++
			isLast := (i == len(configPacketsData)-1)
			pkt := &rtp.Packet{
				Header: rtp.Header{
					Version:        2,
					PayloadType:    96,
					SequenceNumber: sb.videoSeq,
					Timestamp:      sb.videoRtpBase,
					SSRC:           sb.videoSSRC,
					Marker:         isLast,
				},
				Payload: pData,
			}
			sess.EnqueueVideoPacket(pkt, true)
		}
		sb.timelineMu.Unlock()
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

// packetizeAndBroadcastVideo packetizes H.264 payload into MTU fragments with the exact PTS RTP timestamp and broadcasts
func (sb *StreamerBridge) packetizeAndBroadcastVideo(payload []byte, rtpTimestamp uint32, isKeyFrame bool) {
	packetsData := sb.videoPayloader.Payload(1200, payload)
	if len(packetsData) == 0 {
		return
	}

	sb.timelineMu.Lock()
	packets := make([]*rtp.Packet, len(packetsData))
	for i, pData := range packetsData {
		sb.videoSeq++
		isLast := (i == len(packetsData)-1)
		packets[i] = &rtp.Packet{
			Header: rtp.Header{
				Version:        2,
				PayloadType:    96,
				SequenceNumber: sb.videoSeq,
				Timestamp:      rtpTimestamp,
				SSRC:           sb.videoSSRC,
				Marker:         isLast,
			},
			Payload: pData,
		}
	}
	sb.timelineMu.Unlock()

	sb.sessionsMu.RLock()
	defer sb.sessionsMu.RUnlock()
	for _, sess := range sb.sessions {
		for _, pkt := range packets {
			sess.EnqueueVideoPacket(pkt, isKeyFrame)
		}
	}
}

// packetizeAndBroadcastAudio packetizes Opus payload into RTP packets with the exact PTS RTP timestamp and broadcasts
func (sb *StreamerBridge) packetizeAndBroadcastAudio(payload []byte, rtpTimestamp uint32) {
	packetsData := sb.audioPayloader.Payload(1200, payload)
	if len(packetsData) == 0 {
		return
	}

	sb.timelineMu.Lock()
	packets := make([]*rtp.Packet, len(packetsData))
	for i, pData := range packetsData {
		sb.audioSeq++
		packets[i] = &rtp.Packet{
			Header: rtp.Header{
				Version:        2,
				PayloadType:    111,
				SequenceNumber: sb.audioSeq,
				Timestamp:      rtpTimestamp,
				SSRC:           sb.audioSSRC,
				Marker:         false,
			},
			Payload: pData,
		}
	}
	sb.timelineMu.Unlock()

	sb.sessionsMu.RLock()
	defer sb.sessionsMu.RUnlock()
	for _, sess := range sb.sessions {
		for _, pkt := range packets {
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
