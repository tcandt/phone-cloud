package main

import (
	"encoding/binary"
	"math"
	"net"
	"sync"
)

// ControlWriter serializes input events into the binary protocol expected by scrcpy-server
type ControlWriter struct {
	conn         net.Conn
	mu           sync.Mutex
	clipboardSeq uint64
}

func NewControlWriter(conn net.Conn) *ControlWriter {
	return &ControlWriter{conn: conn}
}

// SendTouch injects a touch/pointer event
func (cw *ControlWriter) SendTouch(action byte, pointerID int64, x, y, w, h int, pressure float32) error {
	cw.mu.Lock()
	defer cw.mu.Unlock()

	buf := make([]byte, 32)
	buf[0] = ControlMsgInjectTouchEvent
	buf[1] = action
	binary.BigEndian.PutUint64(buf[2:10], uint64(pointerID))
	binary.BigEndian.PutUint32(buf[10:14], uint32(x))
	binary.BigEndian.PutUint32(buf[14:18], uint32(y))
	binary.BigEndian.PutUint16(buf[18:20], uint16(w))
	binary.BigEndian.PutUint16(buf[20:22], uint16(h))

	// Pressure fixed-point u16 conversion
	pressureClamped := float32(math.Max(0.0, math.Min(1.0, float64(pressure))))
	binary.BigEndian.PutUint16(buf[22:24], uint16(pressureClamped*65535.0))

	binary.BigEndian.PutUint32(buf[24:28], 0) // actionButton
	binary.BigEndian.PutUint32(buf[28:32], 0) // buttons

	if cw.conn == nil {
		return nil
	}
	_, err := cw.conn.Write(buf)
	return err
}

// SendKeycode injects a physical or navigation key event (e.g. Back=4, Home=3)
func (cw *ControlWriter) SendKeycode(action byte, keycode, repeat, meta int) error {
	cw.mu.Lock()
	defer cw.mu.Unlock()

	buf := make([]byte, 14)
	buf[0] = ControlMsgInjectKeycode
	buf[1] = action
	binary.BigEndian.PutUint32(buf[2:6], uint32(keycode))
	binary.BigEndian.PutUint32(buf[6:10], uint32(repeat))
	binary.BigEndian.PutUint32(buf[10:14], uint32(meta))

	if cw.conn == nil {
		return nil
	}
	_, err := cw.conn.Write(buf)
	return err
}

// SendText injects a string into the focused text input field
func (cw *ControlWriter) SendText(text string) error {
	cw.mu.Lock()
	defer cw.mu.Unlock()

	textBytes := []byte(text)
	buf := make([]byte, 5+len(textBytes))
	buf[0] = ControlMsgInjectText
	binary.BigEndian.PutUint32(buf[1:5], uint32(len(textBytes)))
	copy(buf[5:], textBytes)

	if cw.conn == nil {
		return nil
	}
	_, err := cw.conn.Write(buf)
	return err
}

// SendScroll injects horizontal/vertical scroll wheel events matching ControlMessageReader.java:
// [type:1B][position:12B][hScroll:2B (int16)][vScroll:2B (int16)][buttons:4B (uint32)]
// Position: [x:4B][y:4B][w:2B][h:2B]
// Fixed-point: Helper decodes float val = (short / 32768.0) * 16.0 = short / 2048.0
// Therefore short = clamp(val * 2048.0, -32768, 32767)
func (cw *ControlWriter) SendScroll(x, y, w, h int, hScroll, vScroll float32) error {
	return cw.SendScrollWithButtons(x, y, w, h, hScroll, vScroll, 0)
}

func (cw *ControlWriter) SendScrollWithButtons(x, y, w, h int, hScroll, vScroll float32, buttons uint32) error {
	cw.mu.Lock()
	defer cw.mu.Unlock()

	buf := make([]byte, 21)
	buf[0] = ControlMsgInjectScrollEvent // 3
	binary.BigEndian.PutUint32(buf[1:5], uint32(x))
	binary.BigEndian.PutUint32(buf[5:9], uint32(y))
	binary.BigEndian.PutUint16(buf[9:11], uint16(w))
	binary.BigEndian.PutUint16(buf[11:13], uint16(h))

	clampShort := func(val float32) int16 {
		scaled := val * 2048.0
		if scaled > 32767.0 {
			return 32767
		}
		if scaled < -32768.0 {
			return -32768
		}
		return int16(scaled)
	}

	binary.BigEndian.PutUint16(buf[13:15], uint16(clampShort(hScroll)))
	binary.BigEndian.PutUint16(buf[15:17], uint16(clampShort(vScroll)))
	binary.BigEndian.PutUint32(buf[17:21], buttons)

	if cw.conn == nil {
		return nil
	}
	_, err := cw.conn.Write(buf)
	return err
}

// SetBitrate adjusts hardware encoder bitrate in real-time (WebRTC BWE)
func (cw *ControlWriter) SetBitrate(bitrate int) error {
	cw.mu.Lock()
	defer cw.mu.Unlock()

	buf := make([]byte, 5)
	buf[0] = ControlMsgSetBitrate
	binary.BigEndian.PutUint32(buf[1:5], uint32(bitrate))

	if cw.conn == nil {
		return nil
	}
	_, err := cw.conn.Write(buf)
	return err
}

// RequestKeyframe requests an immediate IDR I-frame synchronization from MediaCodec
func (cw *ControlWriter) RequestKeyframe() error {
	cw.mu.Lock()
	defer cw.mu.Unlock()

	if cw.conn == nil {
		return nil
	}
	_, err := cw.conn.Write([]byte{ControlMsgRequestKeyframe})
	return err
}

// SetClipboard sends text to the Android clipboard matching ControlMessageReader.java:
// [type:1B][sequence:8B][text_length:4B][text:N B][paste:1B]
func (cw *ControlWriter) SetClipboard(text string, paste bool) error {
	cw.mu.Lock()
	defer cw.mu.Unlock()

	textBytes := []byte(text)
	buf := make([]byte, 14+len(textBytes))
	buf[0] = ControlMsgSetClipboard // 9
	cw.clipboardSeq++
	binary.BigEndian.PutUint64(buf[1:9], cw.clipboardSeq)
	binary.BigEndian.PutUint32(buf[9:13], uint32(len(textBytes)))
	copy(buf[13:13+len(textBytes)], textBytes)
	if paste {
		buf[13+len(textBytes)] = 1
	} else {
		buf[13+len(textBytes)] = 0
	}

	if cw.conn == nil {
		return nil
	}
	_, err := cw.conn.Write(buf)
	return err
}

// SetDisplayPower powers on or off the physical display (ControlMsgSetDisplayPower = 10)
func (cw *ControlWriter) SetDisplayPower(on bool) error {
	cw.mu.Lock()
	defer cw.mu.Unlock()

	buf := make([]byte, 2)
	buf[0] = ControlMsgSetDisplayPower // 10
	if on {
		buf[1] = 1
	} else {
		buf[1] = 0
	}

	if cw.conn == nil {
		return nil
	}
	_, err := cw.conn.Write(buf)
	return err
}

// UpdateConn updates the underlying connection upon scrcpy restart
func (cw *ControlWriter) UpdateConn(conn net.Conn) {
	cw.mu.Lock()
	defer cw.mu.Unlock()
	if cw.conn != nil {
		_ = cw.conn.Close()
	}
	cw.conn = conn
}

func (cw *ControlWriter) Close() error {
	cw.mu.Lock()
	defer cw.mu.Unlock()
	if cw.conn != nil {
		return cw.conn.Close()
	}
	return nil
}
