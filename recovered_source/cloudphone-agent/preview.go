package main

import (
	"encoding/binary"
	"sync"

	"github.com/gorilla/websocket"
)

const (
	PrevMagic      = "PREV"
	PrevHeaderSize = 49
)

type PreviewStreamer struct {
	deviceID string
	ws       *websocket.Conn
	mu       sync.Mutex
	active   bool
	fps      int
	maxSize  int
	bitrate  int
}

func NewPreviewStreamer(deviceID string) *PreviewStreamer {
	return &PreviewStreamer{
		deviceID: deviceID,
		active:   false,
		fps:      30,
		maxSize:  1080,
		bitrate:  4000000,
	}
}

func (p *PreviewStreamer) SetWebSocket(ws *websocket.Conn) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.ws = ws
}

func (p *PreviewStreamer) Start(fps, maxSize, bitrate int) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.active = true
	if fps > 0 {
		p.fps = fps
	}
	if maxSize > 0 {
		p.maxSize = maxSize
	}
	if bitrate > 0 {
		p.bitrate = bitrate
	}
}

func (p *PreviewStreamer) Stop() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.active = false
}

func (p *PreviewStreamer) IsActive() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.active
}

// BuildPrevFrame constructs a 49-byte PREV binary packet
// [4B Magic 'PREV'][32B DeviceID][1B isKey][8B ptsUs (BE)][4B payloadLen (BE)][NALU Payload]
func (p *PreviewStreamer) BuildPrevFrame(nalu []byte, isKey bool, ptsUs uint64) []byte {
	payloadLen := len(nalu)
	frame := make([]byte, PrevHeaderSize+payloadLen)

	// 0..3: Magic 'PREV'
	copy(frame[0:4], []byte(PrevMagic))

	// 4..35: DeviceID (32 bytes zero-padded)
	devIDBytes := []byte(p.deviceID)
	if len(devIDBytes) > 32 {
		devIDBytes = devIDBytes[:32]
	}
	copy(frame[4:36], devIDBytes)

	// 36: isKey (1 byte)
	if isKey {
		frame[36] = 0x01
	} else {
		frame[36] = 0x00
	}

	// 37..44: ptsUs (8 bytes BigEndian uint64)
	binary.BigEndian.PutUint64(frame[37:45], ptsUs)

	// 45..48: payloadLen (4 bytes BigEndian uint32)
	binary.BigEndian.PutUint32(frame[45:49], uint32(payloadLen))

	// 49..end: NALU Payload
	copy(frame[49:], nalu)

	return frame
}

// SendFrame sends a binary PREV frame over the WebSocket connection
func (p *PreviewStreamer) SendFrame(nalu []byte, isKey bool, ptsUs uint64) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if !p.active || p.ws == nil {
		return nil
	}

	packet := p.BuildPrevFrame(nalu, isKey, ptsUs)
	return p.ws.WriteMessage(websocket.BinaryMessage, packet)
}

// SendAudio sends a binary AUDO frame [4B 'AUDO'][payload] over the WebSocket connection
func (p *PreviewStreamer) SendAudio(payload []byte) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if !p.active || p.ws == nil {
		return nil
	}

	packet := make([]byte, 4+len(payload))
	copy(packet[0:4], []byte("AUDO"))
	copy(packet[4:], payload)
	return p.ws.WriteMessage(websocket.BinaryMessage, packet)
}
