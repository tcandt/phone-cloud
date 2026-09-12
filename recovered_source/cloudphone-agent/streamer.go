package main

import (
	"encoding/binary"
	"io"
	"log"
	"net"
	"sync"
	"time"

	"github.com/pion/webrtc/v3/pkg/media"
)

type StreamerBridge struct {
	sessionsMu       sync.RWMutex
	sessions         map[string]*WebRTCSession
	control          *ControlWriter
	previewStreamer  *PreviewStreamer
	fps              int
	running          bool
	mu               sync.Mutex

	// Codec Configuration Cache (SPS / PPS)
	cachedCodecConfig []byte
	configMu          sync.RWMutex

	// Shared Media Timeline Epoch & Reference Clock
	timelineMu     sync.Mutex
	hasCommonEpoch bool
	basePtsUs      uint64

	// Video PTS Timeline Tracking
	prevVideoPtsUs uint64
	hasVideoPrev   bool

	// Audio PTS Timeline Tracking
	prevAudioPtsUs uint64
	hasAudioPrev   bool
}

func NewStreamerBridge(ctrl *ControlWriter, fps int) *StreamerBridge {
	if fps <= 0 {
		fps = 60
	}
	return &StreamerBridge{
		sessions: make(map[string]*WebRTCSession),
		control:  ctrl,
		fps:      fps,
		running:  true,
	}
}

// ResetTimeline resets the shared media timeline epoch on reconnect or major discontinuity
func (sb *StreamerBridge) ResetTimeline() {
	sb.timelineMu.Lock()
	defer sb.timelineMu.Unlock()
	sb.hasCommonEpoch = false
	sb.basePtsUs = 0
	sb.hasVideoPrev = false
	sb.prevVideoPtsUs = 0
	sb.hasAudioPrev = false
	sb.prevAudioPtsUs = 0
}

// establishOrGetEpoch maps microsecond presentation timestamps to a shared reference clock
func (sb *StreamerBridge) establishOrGetEpoch(ptsUs uint64) (uint64, bool) {
	sb.timelineMu.Lock()
	defer sb.timelineMu.Unlock()
	if !sb.hasCommonEpoch {
		sb.basePtsUs = ptsUs
		sb.hasCommonEpoch = true
		return 0, true
	}
	if ptsUs >= sb.basePtsUs {
		return ptsUs - sb.basePtsUs, false
	}
	return 0, false
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

	// Immediately deliver cached SPS/PPS codec configuration to the new session
	sb.configMu.RLock()
	if len(sb.cachedCodecConfig) > 0 {
		configSample := media.Sample{
			Data:     sb.cachedCodecConfig,
			Duration: 0,
		}
		sess.EnqueueVideoSample(configSample, true)
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
	nominalDuration := time.Duration(1000/sb.fps) * time.Millisecond

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

			// Codec config is delivered with 0 duration so RTP timestamp does not advance
			sample := media.Sample{
				Data:     payload,
				Duration: 0,
			}
			sb.broadcastVideoSample(sample, true)

			if sb.previewStreamer != nil && sb.previewStreamer.IsActive() {
				_ = sb.previewStreamer.SendFrame(payload, true, ptsUs)
			}
			continue
		}

		// Calculate frame duration from microsecond PTS delta and map to shared media timeline
		relPtsUs, _ := sb.establishOrGetEpoch(ptsUs)
		_ = relPtsUs

		var sampleDuration time.Duration
		if !sb.hasVideoPrev {
			sampleDuration = nominalDuration
			sb.hasVideoPrev = true
		} else if ptsUs > sb.prevVideoPtsUs {
			deltaUs := ptsUs - sb.prevVideoPtsUs
			// Discontinuity check: if gap > 3 seconds, reset timeline to nominal
			if deltaUs > 3000000 {
				log.Printf("[Streamer] Video discontinuity detected (%d us gap), resetting timeline epoch", deltaUs)
				sb.ResetTimeline()
				sampleDuration = nominalDuration
			} else {
				// Microsecond delta is converted to time.Duration.
				// Pion's TrackLocalStaticSample uses this duration to advance RTP timestamp (90 kHz for H.264 video).
				sampleDuration = time.Duration(deltaUs) * time.Microsecond
			}
		} else {
			// PTS reset / wrap / backward timestamp: reset base and use nominal duration
			sb.ResetTimeline()
			sampleDuration = nominalDuration
		}
		sb.prevVideoPtsUs = ptsUs

		sample := media.Sample{
			Data:     payload,
			Duration: sampleDuration,
		}

		// WebRTC RTP Track Fan-out (Non-blocking bounded queue)
		sb.broadcastVideoSample(sample, isKeyFrame)

		// WebSocket Fallback Preview Stream (PREV framing)
		if sb.previewStreamer != nil && sb.previewStreamer.IsActive() {
			_ = sb.previewStreamer.SendFrame(payload, isKeyFrame, ptsUs)
		}
	}
}

func (sb *StreamerBridge) broadcastVideoSample(sample media.Sample, isKeyFrame bool) {
	sb.sessionsMu.RLock()
	defer sb.sessionsMu.RUnlock()
	for _, sess := range sb.sessions {
		sess.EnqueueVideoSample(sample, isKeyFrame)
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
	nominalAudioDuration := 20 * time.Millisecond

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

		// Dynamic Audio Frame Duration from PTS mapped onto shared timeline
		relAudioPtsUs, _ := sb.establishOrGetEpoch(audioPtsUs)
		_ = relAudioPtsUs

		var audioDuration time.Duration
		if !sb.hasAudioPrev {
			audioDuration = nominalAudioDuration
			sb.hasAudioPrev = true
		} else if audioPtsUs > sb.prevAudioPtsUs {
			deltaUs := audioPtsUs - sb.prevAudioPtsUs
			if deltaUs > 1000000 || deltaUs < 1000 {
				audioDuration = nominalAudioDuration
			} else {
				// Microsecond delta is converted to time.Duration.
				// Pion advances RTP timestamp according to Opus's 48 kHz clock rate.
				audioDuration = time.Duration(deltaUs) * time.Microsecond
			}
		} else {
			audioDuration = nominalAudioDuration
		}
		sb.prevAudioPtsUs = audioPtsUs

		sample := media.Sample{
			Data:     payload,
			Duration: audioDuration,
		}

		// WebRTC RTP Track Fan-out
		sb.sessionsMu.RLock()
		for _, sess := range sb.sessions {
			sess.EnqueueAudioSample(sample)
		}
		sb.sessionsMu.RUnlock()

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

