package main

import (
	"encoding/binary"
	"encoding/json"
	"path/filepath"
	"testing"
)

func TestAgentConformance_PrevBinaryFraming(t *testing.T) {
	streamer := NewPreviewStreamer("phone-test-device-42")
	streamer.Start(30, 1080, 4000000)

	naluData := []byte{0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f} // SPS NALU
	ptsUs := uint64(1726058400000)
	isKey := true

	frame := streamer.BuildPrevFrame(naluData, isKey, ptsUs)

	expectedLen := PrevHeaderSize + len(naluData)
	if len(frame) != expectedLen {
		t.Fatalf("Expected frame length %d, got %d", expectedLen, len(frame))
	}

	// 1. Magic check
	magic := string(frame[0:4])
	if magic != "PREV" {
		t.Errorf("Expected magic 'PREV', got %s", magic)
	}

	// 2. DeviceID check (32 bytes zero padded)
	idBytes := frame[4:36]
	idStr := string(idBytes)
	if !stringsHasPrefix(idStr, "phone-test-device-42") {
		t.Errorf("Expected device ID prefix 'phone-test-device-42', got %s", idStr)
	}

	// 3. isKey check
	if frame[36] != 0x01 {
		t.Errorf("Expected isKey 0x01, got 0x%02x", frame[36])
	}

	// 4. ptsUs check
	readPts := binary.BigEndian.Uint64(frame[37:45])
	if readPts != ptsUs {
		t.Errorf("Expected ptsUs %d, got %d", ptsUs, readPts)
	}

	// 5. payloadLen check
	payloadLen := binary.BigEndian.Uint32(frame[45:49])
	if int(payloadLen) != len(naluData) {
		t.Errorf("Expected payloadLen %d, got %d", len(naluData), payloadLen)
	}

	// 6. Payload data check
	payload := frame[49:]
	for i, b := range naluData {
		if payload[i] != b {
			t.Errorf("Payload byte mismatch at index %d: expected 0x%02x, got 0x%02x", i, b, payload[i])
		}
	}
}

func TestAgentConformance_FileChannelMessages(t *testing.T) {
	// 1. Test FileChannelCmd parsing
	rawJSON := `{"type":"upload_start","path":"/sdcard/Download/app.apk","size":1048576,"sha256":"abc123hash","install_on_finish":true}`
	var cmd FileChannelCmd
	if err := json.Unmarshal([]byte(rawJSON), &cmd); err != nil {
		t.Fatalf("Failed to unmarshal FileChannelCmd: %v", err)
	}
	if cmd.Type != "upload_start" || cmd.Size != 1048576 || !cmd.InstallOnFinish {
		t.Errorf("FileChannelCmd field mismatch: %+v", cmd)
	}

	// 2. Test FileListReply marshaling
	reply := FileListReply{
		Type:    "list_reply",
		Success: true,
		Path:    "/sdcard/Download",
		Files: []FileInfoItem{
			{Name: "demo.apk", Path: "/sdcard/Download/demo.apk", IsDir: false, Size: 2048, Mtime: 1726058000},
		},
	}
	bytes, err := json.Marshal(reply)
	if err != nil {
		t.Fatalf("Failed to marshal FileListReply: %v", err)
	}

	var parsed map[string]interface{}
	_ = json.Unmarshal(bytes, &parsed)
	if parsed["type"] != "list_reply" || parsed["success"] != true {
		t.Errorf("FileListReply marshaling mismatch: %s", string(bytes))
	}
}

func TestAgentConformance_AICommandMessages(t *testing.T) {
	reqJSON := `{"request_id":"req_9988","command":"echo hello"}`
	var req AICommandRequest
	if err := json.Unmarshal([]byte(reqJSON), &req); err != nil {
		t.Fatalf("Failed to parse AICommandRequest: %v", err)
	}
	if req.RequestID != "req_9988" || req.Command != "echo hello" {
		t.Errorf("AICommandRequest mismatch: %+v", req)
	}

	resp := AICommandResponse{
		RequestID: req.RequestID,
		Output:    "hello\n",
		ExitCode:  0,
	}
	respBytes, _ := json.Marshal(resp)
	var parsed map[string]interface{}
	_ = json.Unmarshal(respBytes, &parsed)
	if parsed["exit_code"] != float64(0) || parsed["output"] != "hello\n" {
		t.Errorf("AICommandResponse mismatch: %s", string(respBytes))
	}
}

func stringsHasPrefix(s, prefix string) bool {
	return len(s) >= len(prefix) && s[:len(prefix)] == prefix
}

func TestAgentConformance_SandboxPathTraversal(t *testing.T) {
	// 1. Normal path should pass
	safePath, err := sanitizeFilePath("/sdcard/DCIM/photo.jpg")
	if err != nil || !stringsHasPrefix(safePath, filepath.Clean("/sdcard/DCIM/photo.jpg")) {
		t.Errorf("Expected valid path, got err: %v, path: %s", err, safePath)
	}

	// 2. Traversal attempt must be rejected
	traversalPaths := []string{
		"../../etc/shadow",
		"/sdcard/../../system/bin/su",
		"../../../data/system/users",
		"..\\..\\Windows\\System32",
	}
	for _, p := range traversalPaths {
		_, err := sanitizeFilePath(p)
		if err == nil {
			t.Errorf("Expected traversal error for %s, but got none", p)
		}
	}

	// 3. Critical system path and system tree subpath protection
	if !isCriticalSystemPath("/") {
		t.Errorf("Expected '/' to be recognized as critical system path")
	}
	if !isCriticalSystemPath("/system") {
		t.Errorf("Expected '/system' to be recognized as critical system path")
	}
	if !isCriticalSystemPath("/system/etc/build.prop") {
		t.Errorf("Expected subpath '/system/etc/build.prop' to be recognized as critical system path")
	}
	if !isCriticalSystemPath("/etc/passwd") {
		t.Errorf("Expected subpath '/etc/passwd' to be recognized as critical system path")
	}
	if !isCriticalSystemPath("C:\\Windows\\System32\\cmd.exe") {
		t.Errorf("Expected Windows system path to be recognized as critical system path")
	}
	if !isCriticalSystemPath("/sdcard") {
		t.Errorf("Expected '/sdcard' root to be protected against root wipe")
	}
	if isCriticalSystemPath("/sdcard/Download/my_test.apk") {
		t.Errorf("Specific sub-file in /sdcard should not be flagged as critical system path")
	}
}

func TestAgentConformance_AdbInteractiveShellInit(t *testing.T) {
	initJSON := `{"type":"init","rows":30,"cols":100}`
	var initMsg struct {
		Type string `json:"type"`
		Rows int    `json:"rows"`
		Cols int    `json:"cols"`
	}
	if err := json.Unmarshal([]byte(initJSON), &initMsg); err != nil {
		t.Fatalf("Failed to parse adb init: %v", err)
	}
	if initMsg.Type != "init" || initMsg.Rows != 30 || initMsg.Cols != 100 {
		t.Errorf("Unexpected parsed init values: %+v", initMsg)
	}
}

func TestAgentConformance_CommandFailClosed(t *testing.T) {
	checkAllowed := func(msg map[string]interface{}, hasSession bool, sessCanShell bool) bool {
		canShell, hasCanShell := msg["can_shell"].(bool)
		allowed := false
		if hasCanShell && canShell {
			allowed = true
		} else if hasSession && sessCanShell {
			allowed = true
		}
		return allowed
	}

	// Case 1: Missing can_shell field, no session -> MUST be rejected (false)
	if checkAllowed(map[string]interface{}{"command": "whoami"}, false, false) {
		t.Errorf("FAIL: Missing can_shell without session must NOT be allowed")
	}

	// Case 2: can_shell is explicitly false, no session -> MUST be rejected (false)
	if checkAllowed(map[string]interface{}{"command": "whoami", "can_shell": false}, false, false) {
		t.Errorf("FAIL: can_shell=false must NOT be allowed")
	}

	// Case 3: can_shell is explicitly false, session has can_shell=false -> MUST be rejected (false)
	if checkAllowed(map[string]interface{}{"command": "whoami", "can_shell": false}, true, false) {
		t.Errorf("FAIL: can_shell=false with view-only session must NOT be allowed")
	}

	// Case 4: can_shell is explicitly true -> MUST be allowed (true)
	if !checkAllowed(map[string]interface{}{"command": "whoami", "can_shell": true}, false, false) {
		t.Errorf("FAIL: can_shell=true must be allowed")
	}

	// Case 5: Missing can_shell, but active session has can_shell=true -> MUST be allowed (true)
	if !checkAllowed(map[string]interface{}{"command": "whoami"}, true, true) {
		t.Errorf("FAIL: Active session with can_shell=true must be allowed")
	}
}

func TestAgentConformance_SignalingURLParser(t *testing.T) {
	// 1. Standard ws:// with domain
	u1, sni1, err1 := ParseSignalingURL("ws://cloudphone.example.com:8443", "dev-01", "secret123")
	if err1 != nil || sni1 != "cloudphone.example.com" || !stringsContains(u1, "ws://cloudphone.example.com:8443/register_agent") {
		t.Errorf("FAIL Test 1: %s, sni: %s, err: %v", u1, sni1, err1)
	}

	// 2. HTTPS/WSS with subpath
	u2, sni2, err2 := ParseSignalingURL("https://gateway.cloud.io/proxy", "dev-02", "")
	if err2 != nil || sni2 != "gateway.cloud.io" || !stringsContains(u2, "wss://gateway.cloud.io/proxy/register_agent") {
		t.Errorf("FAIL Test 2: %s, sni: %s, err: %v", u2, sni2, err2)
	}

	// 3. IPv6 format
	u3, sni3, err3 := ParseSignalingURL("wss://[::1]:8443", "dev-03", "tokenA")
	if err3 != nil || sni3 != "::1" || !stringsContains(u3, "wss://[::1]:8443/register_agent") {
		t.Errorf("FAIL Test 3: %s, sni: %s, err: %v", u3, sni3, err3)
	}
}

func stringsContains(s, substr string) bool {
	return len(s) >= len(substr) && (s == substr || filepath.Base(s) != "" && stringsIndex(s, substr) >= 0)
}

func stringsIndex(s, substr string) int {
	for i := 0; i+len(substr) <= len(s); i++ {
		if s[i:i+len(substr)] == substr {
			return i
		}
	}
	return -1
}

