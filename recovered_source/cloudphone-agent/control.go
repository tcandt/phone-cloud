package main

import (
	"encoding/binary"
	"math"
	"net"
	"sync"
)

// ControlWriter serializes input events into the binary protocol expected by scrcpy-server
type ControlWriter struct {
	conn net.Conn
	mu   sync.Mutex
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

// SendScroll injects horizontal/vertical scroll wheel events
func (cw *ControlWriter) SendScroll(x, y, w, h int, hScroll, vScroll float32) error {
	cw.mu.Lock()
	defer cw.mu.Unlock()

	buf := make([]byte, 21)
	buf[0] = ControlMsgInjectScrollEvent
	binary.BigEndian.PutUint32(buf[1:5], uint32(x))
	binary.BigEndian.PutUint32(buf[5:9], uint32(y))
	binary.BigEndian.PutUint16(buf[9:11], uint16(w))
	binary.BigEndian.PutUint16(buf[11:13], uint16(h))

	// Fixed-point 16.16 conversion
	binary.BigEndian.PutUint32(buf[13:17], uint32(int32(hScroll*65536.0)))
	binary.BigEndian.PutUint32(buf[17:21], uint32(int32(vScroll*65536.0)))

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

// SetClipboard sends text to the Android clipboard
func (cw *ControlWriter) SetClipboard(text string, paste bool) error {
	cw.mu.Lock()
	defer cw.mu.Unlock()

	textBytes := []byte(text)
	buf := make([]byte, 14+len(textBytes))
	buf[0] = ControlMsgSetClipboard
	binary.BigEndian.PutUint64(buf[1:9], 0) // sequence
	if paste {
		buf[9] = 1
	} else {
		buf[9] = 0
	}
	binary.BigEndian.PutUint32(buf[10:14], uint32(len(textBytes)))
	copy(buf[14:], textBytes)

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
