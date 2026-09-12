package main

import (
	"encoding/binary"
	"io"
	"log"
	"net"
	"sync"
	"time"

	"github.com/pion/webrtc/v3"
	"github.com/pion/webrtc/v3/pkg/media"
)

type StreamerBridge struct {
	videoTrack      *webrtc.TrackLocalStaticSample
	audioTrack      *webrtc.TrackLocalStaticSample
	control         *ControlWriter
	previewStreamer *PreviewStreamer
	fps             int
	running         bool
	mu              sync.Mutex
}

func NewStreamerBridge(videoTrack, audioTrack *webrtc.TrackLocalStaticSample, ctrl *ControlWriter, fps int) *StreamerBridge {
	if fps <= 0 {
		fps = 60
	}
	return &StreamerBridge{
		videoTrack: videoTrack,
		audioTrack: audioTrack,
		control:    ctrl,
		fps:        fps,
		running:    true,
	}
}

func (sb *StreamerBridge) SetPreviewStreamer(p *PreviewStreamer) {
	sb.mu.Lock()
	defer sb.mu.Unlock()
	sb.previewStreamer = p
}

// StreamVideo reads H.264 video packets from scrcpy video socket and pushes them to WebRTC
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
	sampleDuration := time.Duration(1000/sb.fps) * time.Millisecond

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

		// WebRTC RTP Track
		if sb.videoTrack != nil {
			_ = sb.videoTrack.WriteSample(media.Sample{
				Data:     payload,
				Duration: sampleDuration,
			})
		}

		// WebSocket Fallback Preview Stream (PREV framing)
		if sb.previewStreamer != nil && sb.previewStreamer.IsActive() {
			_ = sb.previewStreamer.SendFrame(payload, isConfig || isKeyFrame, ptsUs)
		}

		if isConfig || isKeyFrame {
			// Periodic keyframe log
		}
	}
}

// StreamAudio reads Opus/audio packets from scrcpy audio socket and pushes them to WebRTC and preview streamer
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
		packetSize := binary.BigEndian.Uint32(audioHeader[8:12])
		payload := make([]byte, packetSize)
		if _, err := io.ReadFull(conn, payload); err != nil {
			break
		}

		if sb.audioTrack != nil {
			_ = sb.audioTrack.WriteSample(media.Sample{
				Data:     payload,
				Duration: 20 * time.Millisecond,
			})
		}
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
