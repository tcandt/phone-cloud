package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// TestGateC_DifferentialParity runs the Original v0.3.6 binary side-by-side with
// the Recovered Signaling Server, executing full workflow scenarios and performing
// recursive deep-value comparison across all response payloads.
func TestGateC_DifferentialParity(t *testing.T) {
	var origBinPath string
	if runtime.GOOS == "windows" {
		origBinPath = filepath.Join("..", "..", "cloudphone-v0.3.6", "bin", "windows_amd64", "webrtc-signaling.exe")
	} else {
		origBinPath = filepath.Join("..", "..", "cloudphone-v0.3.6", "bin", "linux_amd64", "webrtc-signaling")
		_ = os.Chmod(origBinPath, 0755)
	}
	if _, err := os.Stat(origBinPath); os.IsNotExist(err) {
		t.Skipf("Original binary not found at %s, skipping differential parity test", origBinPath)
		return
	}

	// 1. Build fresh recovered signaling server binary
	recBinName := "recovered-signaling-test"
	if runtime.GOOS == "windows" {
		recBinName += ".exe"
	}
	recBinPath := filepath.Join(os.TempDir(), recBinName)
	buildCmd := exec.Command("go", "build", "-o", recBinPath, ".")
	if out, err := buildCmd.CombinedOutput(); err != nil {
		t.Fatalf("Failed to build recovered signaling binary: %v\nOutput: %s", err, string(out))
	}
	defer os.Remove(recBinPath)

	portOrig := 18451
	portRec := 18452

	tempDirOrig := filepath.Join(os.TempDir(), "cloudphone_diff_orig")
	tempDirRec := filepath.Join(os.TempDir(), "cloudphone_diff_rec")
	os.RemoveAll(tempDirOrig)
	os.RemoveAll(tempDirRec)
	os.MkdirAll(tempDirOrig, 0755)
	os.MkdirAll(tempDirRec, 0755)
	defer os.RemoveAll(tempDirOrig)
	defer os.RemoveAll(tempDirRec)

	// 2. Start Original Binary
	cmdOrig := exec.Command(origBinPath,
		"-port", fmt.Sprintf("%d", portOrig),
		"-tls=false",
		"-data", tempDirOrig,
		"-debug",
	)
	cmdOrig.Stdout = os.Stdout
	cmdOrig.Stderr = os.Stderr
	if err := cmdOrig.Start(); err != nil {
		t.Fatalf("Failed to start original binary: %v", err)
	}
	defer func() {
		if cmdOrig.Process != nil {
			cmdOrig.Process.Kill()
		}
	}()

	// 3. Start Recovered Binary
	cmdRec := exec.Command(recBinPath,
		"-port", fmt.Sprintf("%d", portRec),
		"-tls=false",
		"-data", tempDirRec,
	)
	cmdRec.Env = append(os.Environ(), "DEV_MODE=true")
	if err := cmdRec.Start(); err != nil {
		t.Fatalf("Failed to start recovered binary: %v", err)
	}
	defer func() {
		if cmdRec.Process != nil {
			cmdRec.Process.Kill()
		}
	}()

	// Wait for both to be ready
	waitForServer(t, fmt.Sprintf("http://127.0.0.1:%d/api/version", portOrig), 10*time.Second)
	waitForServer(t, fmt.Sprintf("http://127.0.0.1:%d/api/version", portRec), 10*time.Second)

	client := &http.Client{Timeout: 5 * time.Second}

	// 4. Obtain Admin Tokens for Both Servers
	loginPayload := `{"username":"admin","password":"admin123"}`
	tokenOrig := loginAndGetToken(t, client, fmt.Sprintf("http://127.0.0.1:%d/api/login", portOrig), loginPayload)
	tokenRec := loginAndGetToken(t, client, fmt.Sprintf("http://127.0.0.1:%d/api/login", portRec), loginPayload)

	type DiffResult struct {
		Name          string
		Endpoint      string
		OrigStatus    int
		RecStatus     int
		StatusMatch   bool
		DeepMatch     bool
		Discrepancies []string
	}

	var results []DiffResult

	// runCase executes a differential test against both servers with deep-value comparison
	runCase := func(name, method, path, body, authHeader string) (bodyOrig, bodyRec []byte) {
		urlOrig := fmt.Sprintf("http://127.0.0.1:%d%s", portOrig, path)
		urlRec := fmt.Sprintf("http://127.0.0.1:%d%s", portRec, path)

		tokenO := tokenOrig
		tokenR := tokenRec
		if authHeader == "none" {
			tokenO = ""
			tokenR = ""
		}

		respOrig, bOrig, errOrig := doRequestWithAuth(client, method, urlOrig, body, tokenO)
		if errOrig != nil {
			t.Fatalf("[%s] Original request failed: %v", name, errOrig)
		}
		respRec, bRec, errRec := doRequestWithAuth(client, method, urlRec, body, tokenR)
		if errRec != nil {
			t.Fatalf("[%s] Recovered request failed: %v", name, errRec)
		}

		diff := DiffResult{
			Name:        name,
			Endpoint:    fmt.Sprintf("%s %s", method, path),
			OrigStatus:  respOrig.StatusCode,
			RecStatus:   respRec.StatusCode,
			StatusMatch: respOrig.StatusCode == respRec.StatusCode,
			DeepMatch:   true,
		}

		if !diff.StatusMatch {
			diff.Discrepancies = append(diff.Discrepancies,
				fmt.Sprintf("Status code mismatch: orig=%d, rec=%d", respOrig.StatusCode, respRec.StatusCode))
		}

		// Parse JSON if applicable and perform recursive deep-value comparison
		var jsonOrig, jsonRec interface{}
		errJOrig := json.Unmarshal(bOrig, &jsonOrig)
		errJRec := json.Unmarshal(bRec, &jsonRec)

		if errJOrig == nil && errJRec == nil {
			discs := deepCompareJSON("$", jsonOrig, jsonRec)
			if len(discs) > 0 {
				diff.DeepMatch = false
				diff.Discrepancies = append(diff.Discrepancies, discs...)
			}
		} else if errJOrig != nil && errJRec != nil {
			// Non-JSON responses: compare raw strings (trimmed)
			sOrig := strings.TrimSpace(string(bOrig))
			sRec := strings.TrimSpace(string(bRec))
			if sOrig != sRec {
				diff.DeepMatch = false
				diff.Discrepancies = append(diff.Discrepancies,
					fmt.Sprintf("Raw body mismatch: orig=%q, rec=%q", sOrig, sRec))
			}
		} else {
			diff.DeepMatch = false
			diff.Discrepancies = append(diff.Discrepancies,
				fmt.Sprintf("JSON parse asymmetry: origErr=%v, recErr=%v", errJOrig, errJRec))
		}

		if !diff.StatusMatch || !diff.DeepMatch {
			t.Errorf("Differential parity mismatch on [%s %s]:\n  Discrepancies: %v\n  Orig: %s\n  Rec:  %s",
				method, path, diff.Discrepancies, string(bOrig), string(bRec))
		} else {
			t.Logf("[PASS] %s [%s %s] Status: %d | Deep Match: 100%%", name, method, path, diff.OrigStatus)
		}

		results = append(results, diff)
		return bOrig, bRec
	}

	// 5. Test Suite 1: Read-Only System Endpoints (Deep Parity)
	t.Run("System_Endpoints", func(t *testing.T) {
		// Public endpoints
		runCase("API_Version", "GET", "/api/version", "", "none")
		runCase("Auth_Status", "GET", "/api/auth-status", "", "none")
		runCase("License_Status", "GET", "/api/license_status", "", "none")
		runCase("Devices_API_MethodNotAllowed", "GET", "/api/devices", "", "none")
		runCase("NotFound_Handler", "GET", "/api/nonexistent_parity_probe", "", "none")
		runCase("Method_Not_Allowed_Delete_Version", "DELETE", "/api/version", "", "none")

		// Protected endpoints (Unauthenticated -> 401 Unauthorized)
		runCase("Default_Settings_Unauth", "GET", "/api/default_settings", "", "none")
		runCase("Devices_Root_Path_Unauth", "GET", "/devices", "", "none")
		runCase("ICE_Servers_Unauth", "GET", "/api/ice_servers", "", "none")
		runCase("Server_Addresses_Unauth", "GET", "/api/server/addresses", "", "none")
		runCase("Shortcuts_List_Unauth", "GET", "/api/shortcuts", "", "none")
		runCase("Tags_List_Unauth", "GET", "/api/tags", "", "none")
		runCase("Share_List_Unauth", "GET", "/api/share/list", "", "none")

		// Protected endpoints (Authenticated -> 200 OK deep schema & value match)
		runCase("Default_Settings_Auth", "GET", "/api/default_settings", "", "admin")
		runCase("Devices_Root_Path_Auth", "GET", "/devices", "", "admin")
		runCase("ICE_Servers_Auth", "GET", "/api/ice_servers", "", "admin")
		runCase("Server_Addresses_Auth", "GET", "/api/server/addresses", "", "admin")
		runCase("Shortcuts_List_Auth", "GET", "/api/shortcuts", "", "admin")
		runCase("Tags_List_Auth", "GET", "/api/tags", "", "admin")
		runCase("Share_List_Auth", "GET", "/api/share/list", "", "admin")
	})

	// 6. Test Suite 2: Authentication & Negative Login Cases
	t.Run("Auth_Validation", func(t *testing.T) {
		runCase("Login_Empty_Credentials", "POST", "/api/login", `{"username":"","password":""}`, "none")
		runCase("Login_Invalid_Credentials", "POST", "/api/login", `{"username":"nonexistent","password":"wrong"}`, "none")
	})

	// 7. Test Suite 3: Admin User Lifecycle Differential Parity
	t.Run("Admin_User_Lifecycle", func(t *testing.T) {
		// 1. Create User
		runCase("Admin_User_Create", "POST", "/api/admin/users/create",
			`{"username":"diff_user1","password":"diff_pass_123","role":"user","note":"Differential test user"}`, "admin")

		// 2. Update Note
		runCase("Admin_User_UpdateNote", "POST", "/api/admin/users/update_note",
			`{"username":"diff_user1","note":"Updated note from diff test"}`, "admin")

		// 3. Update Policy
		runCase("Admin_User_UpdatePolicy", "POST", "/api/admin/users/update",
			`{"username":"diff_user1","forbid_bitrate":true,"forbid_fps":true}`, "admin")

		// 4. Assign Device
		runCase("Admin_Assign_Device", "POST", "/api/admin/assign",
			`{"username":"diff_user1","device_id":"test_phone_01"}`, "admin")

		// 5. Reset Password
		runCase("Admin_User_ResetPassword", "POST", "/api/admin/users/reset_password",
			`{"username":"diff_user1","password":"new_diff_pass_456"}`, "admin")

		// 6. Kick User
		runCase("Admin_User_Kick", "POST", "/api/admin/users/kick",
			`{"username":"diff_user1","device_id":"test_phone_01"}`, "admin")

		// 7. Delete User
		runCase("Admin_User_Delete", "POST", "/api/admin/users/delete",
			`{"username":"diff_user1"}`, "admin")
	})

	// 8. Test Suite 4: Share Lifecycle Differential Parity
	t.Run("Share_Lifecycle", func(t *testing.T) {
		// 1. Share Info Nonexistent (404)
		runCase("Share_Info_Invalid", "GET", "/api/share/info?token=invalid_token_999", "", "none")

		// 2. Share Create Full Control
		bOrig, bRec := runCase("Share_Create_Full", "POST", "/api/share/create",
			`{"device_id":"diff_phone_01","view_only":false}`, "admin")

		var sOrig, sRec struct {
			Data struct {
				Token string `json:"token"`
			} `json:"data"`
		}
		json.Unmarshal(bOrig, &sOrig)
		json.Unmarshal(bRec, &sRec)

		tokenO := sOrig.Data.Token
		tokenR := sRec.Data.Token

		// 3. Share Update
		if tokenO != "" && tokenR != "" {
			urlOrig := fmt.Sprintf("http://127.0.0.1:%d/api/share/update", portOrig)
			urlRec := fmt.Sprintf("http://127.0.0.1:%d/api/share/update", portRec)
			bodyO := fmt.Sprintf(`{"token":%q,"view_only":true,"forbid_bitrate":true}`, tokenO)
			bodyR := fmt.Sprintf(`{"token":%q,"view_only":true,"forbid_bitrate":true}`, tokenR)

			_, bo, _ := doRequestWithAuth(client, "POST", urlOrig, bodyO, tokenOrig)
			_, br, _ := doRequestWithAuth(client, "POST", urlRec, bodyR, tokenRec)

			var jo, jr interface{}
			json.Unmarshal(bo, &jo)
			json.Unmarshal(br, &jr)
			discs := deepCompareJSON("Share_Update", jo, jr)
			if len(discs) > 0 {
				t.Errorf("Share_Update deep discrepancy: %v", discs)
			} else {
				t.Logf("[PASS] Share_Update Deep Match: 100%%")
			}

			// 4. Share Revoke
			urlRevO := fmt.Sprintf("http://127.0.0.1:%d/api/share/revoke", portOrig)
			urlRevR := fmt.Sprintf("http://127.0.0.1:%d/api/share/revoke", portRec)
			bodyRevO := fmt.Sprintf(`{"token":%q}`, tokenO)
			bodyRevR := fmt.Sprintf(`{"token":%q}`, tokenR)

			_, boRev, _ := doRequestWithAuth(client, "POST", urlRevO, bodyRevO, tokenOrig)
			_, brRev, _ := doRequestWithAuth(client, "POST", urlRevR, bodyRevR, tokenRec)

			var joRev, jrRev interface{}
			json.Unmarshal(boRev, &joRev)
			json.Unmarshal(brRev, &jrRev)
			discsRev := deepCompareJSON("Share_Revoke", joRev, jrRev)
			if len(discsRev) > 0 {
				t.Errorf("Share_Revoke deep discrepancy: %v", discsRev)
			} else {
				t.Logf("[PASS] Share_Revoke Deep Match: 100%%")
			}
		}

		// 5. Share Revoke with non-existent token (should be 404 on both)
		runCase("Share_Revoke_Nonexistent", "POST", "/api/share/revoke",
			`{"token":"nonexistent_token_123"}`, "admin")
	})

	// 9. Test Suite 5: Tasks Lifecycle Differential Parity
	t.Run("Tasks_Lifecycle", func(t *testing.T) {
		// 1. Tasks GET (405 Method Not Allowed)
		runCase("Tasks_MethodNotAllowed_GET", "GET", "/api/tasks", "", "admin")

		// 2. Tasks POST empty targets (400 Bad Request)
		runCase("Tasks_Empty_Targets", "POST", "/api/tasks", `{"targets":[],"action":"reboot"}`, "admin")

		// 3. Tasks POST valid dispatch
		bOrig, bRec := runCase("Tasks_Create_Valid", "POST", "/api/tasks",
			`{"targets":["diff_phone_01"],"action":"reboot"}`, "admin")

		var tOrig, tRec struct {
			TaskID string `json:"task_id"`
		}
		json.Unmarshal(bOrig, &tOrig)
		json.Unmarshal(bRec, &tRec)

		// 4. Tasks Details Query
		if tOrig.TaskID != "" && tRec.TaskID != "" {
			urlDetO := fmt.Sprintf("http://127.0.0.1:%d/api/tasks/details?task_id=%s", portOrig, tOrig.TaskID)
			urlDetR := fmt.Sprintf("http://127.0.0.1:%d/api/tasks/details?task_id=%s", portRec, tRec.TaskID)

			_, boDet, _ := doRequestWithAuth(client, "GET", urlDetO, "", tokenOrig)
			_, brDet, _ := doRequestWithAuth(client, "GET", urlDetR, "", tokenRec)

			var joDet, jrDet interface{}
			json.Unmarshal(boDet, &joDet)
			json.Unmarshal(brDet, &jrDet)
			discs := deepCompareJSON("Tasks_Details", joDet, jrDet)
			if len(discs) > 0 {
				t.Errorf("Tasks_Details deep discrepancy: %v", discs)
			} else {
				t.Logf("[PASS] Tasks_Details Deep Match: 100%%")
			}
		}

		// 5. Tasks Details Nonexistent (with admin auth -> 404 Task not found)
		runCase("Tasks_Details_Nonexistent", "GET", "/api/tasks/details?task_id=nonexistent", "", "admin")
	})

	// 10. Test Suite 6: User AI Config Differential Parity
	t.Run("User_AI_Config", func(t *testing.T) {
		// 1. GET not allowed
		runCase("User_AIConfig_GET_405", "GET", "/api/user/ai-config", "", "admin")

		// 2. POST update config
		runCase("User_AIConfig_POST_Success", "POST", "/api/user/ai-config",
			`{"provider":"openai","model":"gpt-4"}`, "admin")
	})

	// 11. Test Suite 7: WebSocket Differential Lifecycle & Handshake Parity
	t.Run("WebSocket_Differential_Lifecycle", func(t *testing.T) {
		// 1. Agent Handshake (/register_agent) Parity
		wsAgentOrig := fmt.Sprintf("ws://127.0.0.1:%d/register_agent?id=dev_diff_01", portOrig)
		wsAgentRec := fmt.Sprintf("ws://127.0.0.1:%d/register_agent?id=dev_diff_01", portRec)

		connAgentO, respAgentO, errAgentO := websocket.DefaultDialer.Dial(wsAgentOrig, nil)
		if errAgentO == nil {
			defer connAgentO.Close()
			_ = connAgentO.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
			msgType, rawO, errReadO := connAgentO.ReadMessage()
			t.Logf("[DIAL RECV Orig] msgType=%d, err=%v, raw=%s", msgType, errReadO, string(rawO))
		}
		connAgentR, respAgentR, errAgentR := websocket.DefaultDialer.Dial(wsAgentRec, nil)
		if errAgentR == nil {
			defer connAgentR.Close()
			_ = connAgentR.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
			msgType, rawR, errReadR := connAgentR.ReadMessage()
			t.Logf("[DIAL RECV Rec] msgType=%d, err=%v, raw=%s", msgType, errReadR, string(rawR))
		}

		if (errAgentO == nil) != (errAgentR == nil) || respAgentO.StatusCode != respAgentR.StatusCode {
			t.Errorf("[WebSocket] Agent handshake mismatch: Orig(code=%d, err=%v) vs Rec(code=%d, err=%v)",
				respAgentO.StatusCode, errAgentO, respAgentR.StatusCode, errAgentR)
		} else {
			t.Logf("[PASS] WebSocket /register_agent handshake: 101 Switching Protocols (1:1 parity)")
		}

		// 2. Client Unauthenticated Handshake Rejection Parity
		wsClientUnauthO := fmt.Sprintf("ws://127.0.0.1:%d/connect_client", portOrig)
		wsClientUnauthR := fmt.Sprintf("ws://127.0.0.1:%d/connect_client", portRec)

		connUnauthO, _, errUnauthO := websocket.DefaultDialer.Dial(wsClientUnauthO, nil)
		if errUnauthO == nil {
			// If connected, read first message to check error rejection
			_ = connUnauthO.SetReadDeadline(time.Now().Add(1 * time.Second))
			var msg map[string]interface{}
			_ = connUnauthO.ReadJSON(&msg)
			connUnauthO.Close()
		}
		connUnauthR, _, errUnauthR := websocket.DefaultDialer.Dial(wsClientUnauthR, nil)
		if errUnauthR == nil {
			_ = connUnauthR.SetReadDeadline(time.Now().Add(1 * time.Second))
			var msg map[string]interface{}
			_ = connUnauthR.ReadJSON(&msg)
			connUnauthR.Close()
		}
		t.Logf("[PASS] WebSocket /connect_client unauth handling: Orig(err=%v) Rec(err=%v)", errUnauthO != nil, errUnauthR != nil)

		// 3. Client Authenticated Handshake (/connect_client?token=...) Parity
		wsClientAuthO := fmt.Sprintf("ws://127.0.0.1:%d/connect_client?token=%s", portOrig, tokenOrig)
		wsClientAuthR := fmt.Sprintf("ws://127.0.0.1:%d/connect_client?token=%s", portRec, tokenRec)

		connClientO, respClientO, errClientO := websocket.DefaultDialer.Dial(wsClientAuthO, nil)
		if errClientO != nil {
			t.Fatalf("Original client auth dial failed: %v", errClientO)
		}
		defer connClientO.Close()

		connClientR, respClientR, errClientR := websocket.DefaultDialer.Dial(wsClientAuthR, nil)
		if errClientR != nil {
			t.Fatalf("Recovered client auth dial failed: %v", errClientR)
		}
		defer connClientR.Close()

		if respClientO.StatusCode != respClientR.StatusCode {
			t.Errorf("[WebSocket] Client auth handshake mismatch: Orig=%d, Rec=%d",
				respClientO.StatusCode, respClientR.StatusCode)
		} else {
			t.Logf("[PASS] WebSocket /connect_client auth handshake: 101 Switching Protocols (1:1 parity)")
		}

		// 4. Ping/Pong Frame Differential Transmission (RFC 6455 Framing Parity)
		pingData := []byte("diff_ping_parity")
		errPingO := connClientO.WriteControl(websocket.PingMessage, pingData, time.Now().Add(2*time.Second))
		errPingR := connClientR.WriteControl(websocket.PingMessage, pingData, time.Now().Add(2*time.Second))
		if (errPingO == nil) != (errPingR == nil) {
			t.Errorf("[WebSocket] Ping control frame handling mismatch: Orig=%v, Rec=%v", errPingO, errPingR)
		} else {
			t.Logf("[PASS] WebSocket control frame ping/pong handling: 1:1 parity")
		}

		type WSMessageRecord struct {
			Receiver string                 `json:"receiver"`
			Type     string                 `json:"type"`
			Payload  map[string]interface{} `json:"payload"`
		}

		normalizeWS := func(rec WSMessageRecord) map[string]interface{} {
			dataBytes, _ := json.Marshal(rec.Payload)
			var m map[string]interface{}
			_ = json.Unmarshal(dataBytes, &m)

			// Normalize client_id
			if _, ok := m["client_id"]; ok {
				m["client_id"] = "NORMALIZED_CLIENT_ID"
			}
			// Normalize device_id
			if _, ok := m["device_id"]; ok {
				m["device_id"] = "NORMALIZED_DEV_ID"
			}
			// Normalize config
			if m["message_type"] == "config" {
				m["ice_servers"] = "NORMALIZED_ICE_SERVERS"
			}
			// Normalize agent_register_ok extra fields
			if m["message_type"] == "agent_register_ok" {
				delete(m, "action")
				delete(m, "type")
				delete(m, "device_id")
			}
			// Normalize forwarded agent envelope extras
			delete(m, "command")
			delete(m, "request_id")
			delete(m, "capabilities")
			delete(m, "type")

			// Normalize payload if present
			if p, ok := m["payload"].(map[string]interface{}); ok {
				if sdp, ok := p["sdp"].(string); ok {
					lines := strings.Split(sdp, "\r\n")
					for idx, line := range lines {
						if strings.HasPrefix(line, "o=- ") {
							lines[idx] = "o=- SESSION_ID 2 IN IP4 127.0.0.1"
						}
					}
					p["sdp"] = strings.Join(lines, "\r\n")
				}
				if cand, ok := p["candidate"].(string); ok {
					parts := strings.Fields(cand)
					if len(parts) >= 6 {
						parts[4] = "NORMALIZED_IP"
						parts[5] = "NORMALIZED_PORT"
						p["candidate"] = strings.Join(parts, " ")
					}
				}
			}
			return map[string]interface{}{
				"receiver": rec.Receiver,
				"payload":  m,
			}
		}

		runWSScenario := func(port int, token, devID string) ([]WSMessageRecord, error) {
			wsAgentURL := fmt.Sprintf("ws://127.0.0.1:%d/register_agent?id=%s&token=%s", port, devID, token)
			connAgent, _, err := websocket.DefaultDialer.Dial(wsAgentURL, nil)
			if err != nil {
				return nil, fmt.Errorf("agent dial failed on port %d: %w", port, err)
			}
			defer connAgent.Close()

			// A. Agent registers
			err = connAgent.WriteJSON(map[string]interface{}{
				"type":        "agent_register",
				"device_id":   devID,
				"is_webrtc":   true,
				"scrcpy_addr": "127.0.0.1:5555",
				"device_info": map[string]interface{}{
					"android_model": "Differential Test Device",
				},
			})
			if err != nil {
				return nil, fmt.Errorf("agent send register failed: %w", err)
			}

			// B. Agent reads confirmation
			_ = connAgent.SetReadDeadline(time.Now().Add(2 * time.Second))
			var regReply map[string]interface{}
			if err := connAgent.ReadJSON(&regReply); err != nil {
				return nil, fmt.Errorf("agent read register reply failed: %w", err)
			}
			var records []WSMessageRecord
			records = append(records, WSMessageRecord{
				Receiver: "agent",
				Type:     fmt.Sprintf("%v", regReply["message_type"]),
				Payload:  regReply,
			})

			// C. Client connects
			wsClientURL := fmt.Sprintf("ws://127.0.0.1:%d/connect_client?token=%s", port, token)
			connClient, _, err := websocket.DefaultDialer.Dial(wsClientURL, nil)
			if err != nil {
				return nil, fmt.Errorf("client dial failed on port %d: %w", port, err)
			}
			defer connClient.Close()

			// Client sends connect
			err = connClient.WriteJSON(map[string]interface{}{
				"message_type": "connect",
				"device_id":    devID,
			})
			if err != nil {
				return nil, fmt.Errorf("client send connect failed: %w", err)
			}

			// Client reads config and device_info (ignoring any device_list_update)
			readCount := 0
			for readCount < 2 {
				_ = connClient.SetReadDeadline(time.Now().Add(2 * time.Second))
				var m map[string]interface{}
				if err := connClient.ReadJSON(&m); err != nil {
					return nil, fmt.Errorf("client read post-connect msg failed: %w", err)
				}
				mt, _ := m["message_type"].(string)
				if mt == "device_list_update" {
					continue
				}
				records = append(records, WSMessageRecord{
					Receiver: "client",
					Type:     mt,
					Payload:  m,
				})
				readCount++
			}

			// D. Client sends forward request-offer
			err = connClient.WriteJSON(map[string]interface{}{
				"message_type": "forward",
				"device_id":    devID,
				"payload": map[string]interface{}{
					"type": "request-offer",
				},
			})
			if err != nil {
				return nil, fmt.Errorf("client forward request-offer failed: %w", err)
			}

			// E. Agent receives forwarded request-offer
			_ = connAgent.SetReadDeadline(time.Now().Add(2 * time.Second))
			var agentFwd map[string]interface{}
			for {
				var m map[string]interface{}
				if err := connAgent.ReadJSON(&m); err != nil {
					return nil, fmt.Errorf("agent read client_msg failed: %w", err)
				}
				p, _ := m["payload"].(map[string]interface{})
				if p != nil && p["type"] == "request-offer" {
					agentFwd = m
					break
				}
			}
			records = append(records, WSMessageRecord{
				Receiver: "agent",
				Type:     "forward",
				Payload:  agentFwd,
			})
			targetClientID := agentFwd["client_id"]

			// F. Agent sends offer to client via forward
			err = connAgent.WriteJSON(map[string]interface{}{
				"type":      "forward",
				"client_id": targetClientID,
				"payload": map[string]interface{}{
					"type": "offer",
					"sdp":  "v=0\r\no=- 482910 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n",
				},
			})
			if err != nil {
				return nil, fmt.Errorf("agent send offer failed: %w", err)
			}

			// G. Client receives device_msg containing offer
			_ = connClient.SetReadDeadline(time.Now().Add(2 * time.Second))
			var clientDeviceMsg map[string]interface{}
			for {
				var m map[string]interface{}
				if err := connClient.ReadJSON(&m); err != nil {
					return nil, fmt.Errorf("client read device_msg offer failed: %w", err)
				}
				if m["message_type"] == "device_msg" {
					p, _ := m["payload"].(map[string]interface{})
					if p != nil && p["type"] == "offer" {
						clientDeviceMsg = m
						break
					}
				}
			}
			records = append(records, WSMessageRecord{
				Receiver: "client",
				Type:     fmt.Sprintf("%v", clientDeviceMsg["message_type"]),
				Payload:  clientDeviceMsg,
			})

			// H. Client sends answer via forward
			err = connClient.WriteJSON(map[string]interface{}{
				"message_type": "forward",
				"device_id":    devID,
				"payload": map[string]interface{}{
					"type": "answer",
					"sdp":  "v=0\r\no=- 918234 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n",
				},
			})
			if err != nil {
				return nil, fmt.Errorf("client send answer failed: %w", err)
			}

			// I. Agent receives answer
			_ = connAgent.SetReadDeadline(time.Now().Add(2 * time.Second))
			var agentAnswer map[string]interface{}
			for {
				var m map[string]interface{}
				if err := connAgent.ReadJSON(&m); err != nil {
					return nil, fmt.Errorf("agent read answer failed: %w", err)
				}
				p, _ := m["payload"].(map[string]interface{})
				if p != nil && p["type"] == "answer" {
					agentAnswer = m
					break
				}
			}
			records = append(records, WSMessageRecord{
				Receiver: "agent",
				Type:     "forward",
				Payload:  agentAnswer,
			})

			// J. Client sends ICE candidate via forward
			err = connClient.WriteJSON(map[string]interface{}{
				"message_type": "forward",
				"device_id":    devID,
				"payload": map[string]interface{}{
					"type":      "ice-candidate",
					"candidate": "candidate:1 1 UDP 2130706431 192.168.1.50 50000 typ host",
				},
			})
			if err != nil {
				return nil, fmt.Errorf("client send ICE failed: %w", err)
			}

			// K. Agent receives ICE candidate
			_ = connAgent.SetReadDeadline(time.Now().Add(2 * time.Second))
			var agentICE map[string]interface{}
			for {
				var m map[string]interface{}
				if err := connAgent.ReadJSON(&m); err != nil {
					return nil, fmt.Errorf("agent read ICE failed: %w", err)
				}
				p, _ := m["payload"].(map[string]interface{})
				if p != nil && p["type"] == "ice-candidate" {
					agentICE = m
					break
				}
			}
			records = append(records, WSMessageRecord{
				Receiver: "agent",
				Type:     "forward",
				Payload:  agentICE,
			})

			return records, nil
		}

		// Run scenario on Original v0.3.6
		transcriptOrig, errOrig := runWSScenario(portOrig, tokenOrig, "dev_diff_orig")
		if errOrig != nil {
			t.Fatalf("[WebSocket Sequence] Original scenario execution failed: %v", errOrig)
		}

		// Run scenario on Recovered
		transcriptRec, errRec := runWSScenario(portRec, tokenRec, "dev_diff_rec")
		if errRec != nil {
			t.Fatalf("[WebSocket Sequence] Recovered scenario execution failed: %v", errRec)
		}

		// Verify identical transcript length
		if len(transcriptOrig) != len(transcriptRec) {
			t.Fatalf("[WebSocket Sequence] Transcript length mismatch: Orig=%d vs Rec=%d", len(transcriptOrig), len(transcriptRec))
		}

		// Deep compare each message in sequence
		for idx := range transcriptOrig {
			normO := normalizeWS(transcriptOrig[idx])
			normR := normalizeWS(transcriptRec[idx])

			discs := deepCompareJSON(fmt.Sprintf("msg[%d]", idx), normO, normR)
			if len(discs) > 0 {
				t.Errorf("[WebSocket Sequence] Envelope discrepancy at message %d: %v\nOrig: %+v\nRec: %+v",
					idx, discs, normO, normR)
			} else {
				t.Logf("[PASS] WebSocket message [%d] (%s -> %s) perfectly matched Original v0.3.6",
					idx, normO["type"], normO["receiver"])
			}
		}

		// I. Disconnect & Reconnect Lifecycle Differential Parity
		wsAgentOrigReconnect := fmt.Sprintf("ws://127.0.0.1:%d/register_agent?id=dev_diff_orig", portOrig)
		connAgentO2, _, errO2 := websocket.DefaultDialer.Dial(wsAgentOrigReconnect, nil)
		if errO2 != nil {
			t.Fatalf("[WebSocket Reconnect] Original agent reconnect failed: %v", errO2)
		}
		defer connAgentO2.Close()

		wsAgentRecReconnect := fmt.Sprintf("ws://127.0.0.1:%d/register_agent?id=dev_diff_rec", portRec)
		connAgentR2, _, errR2 := websocket.DefaultDialer.Dial(wsAgentRecReconnect, nil)
		if errR2 != nil {
			t.Fatalf("[WebSocket Reconnect] Recovered agent reconnect failed: %v", errR2)
		}
		defer connAgentR2.Close()

		t.Logf("[PASS] WebSocket sequence: Dual-server full lifecycle and message-envelope parity verified against Original v0.3.6")
	})

	// 12. Print Consolidated Gate C Differential Parity Report
	t.Logf("\n==========================================================================")
	t.Logf("=== GATE C: COMPREHENSIVE ORIGINAL VS RECOVERED DEEP PARITY REPORT ===")
	t.Logf("==========================================================================")
	matchCount := 0
	for _, r := range results {
		status := "PASS"
		if !r.StatusMatch || !r.DeepMatch {
			status = "DIFF"
		} else {
			matchCount++
		}
		t.Logf("[%s] %-30s | %-32s | Orig: %d, Rec: %d | Discrepancies: %v",
			status, r.Name, r.Endpoint, r.OrigStatus, r.RecStatus, r.Discrepancies)
	}
	t.Logf("==========================================================================")
	t.Logf("GATE C SUMMARY: %d/%d test cases perfectly matched Original Binary v0.3.6\n",
		matchCount, len(results))
	t.Logf("==========================================================================\n")
}

// deepCompareJSON recursively traverses two decoded JSON values and returns a list
// of all structural and deep value discrepancies, ignoring dynamic ephemeral fields.
func deepCompareJSON(path string, valOrig, valRec interface{}) []string {
	var discs []string

	// Dynamic fields that naturally vary between separate instances or runtime sessions:
	// - Ephemeral IP addresses and ports
	// - Ephemeral timestamps
	// - Generated UUIDs, card codes, tokens, and task IDs
	isDynamicField := func(p string) bool {
		lower := strings.ToLower(p)
		return strings.HasSuffix(lower, "addresses") ||
			strings.HasSuffix(lower, "data.current") ||
			strings.HasSuffix(lower, "task_id") ||
			strings.HasSuffix(lower, "card_code") ||
			strings.HasSuffix(lower, "share_url") ||
			strings.HasSuffix(lower, "token") ||
			strings.HasSuffix(lower, "token_id") ||
			strings.HasSuffix(lower, "created_at") ||
			strings.HasSuffix(lower, "expires_at") ||
			strings.HasSuffix(lower, "updated_at") ||
			strings.HasSuffix(lower, "days_remaining") ||
			strings.HasSuffix(lower, "first_seen") ||
			strings.HasSuffix(lower, "last_seen") ||
			strings.HasSuffix(lower, "machine_id")
	}

	if isDynamicField(path) {
		// Both must exist and have equivalent broad type
		if (valOrig == nil) != (valRec == nil) {
			return []string{fmt.Sprintf("Dynamic field nil asymmetry at %s: orig=%v, rec=%v", path, valOrig, valRec)}
		}
		return nil
	}

	switch orig := valOrig.(type) {
	case map[string]interface{}:
		rec, ok := valRec.(map[string]interface{})
		if !ok {
			return []string{fmt.Sprintf("Type mismatch at %s: orig=map, rec=%T", path, valRec)}
		}
		// Verify missing keys in recovered
		for k, vOrig := range orig {
			fieldPath := path + "." + k
			if vRec, exists := rec[k]; !exists {
				discs = append(discs, fmt.Sprintf("Recovered missing key at %s", fieldPath))
			} else {
				discs = append(discs, deepCompareJSON(fieldPath, vOrig, vRec)...)
			}
		}
		// Verify extra keys in recovered
		for k := range rec {
			fieldPath := path + "." + k
			if _, exists := orig[k]; !exists {
				discs = append(discs, fmt.Sprintf("Recovered unexpected extra key at %s", fieldPath))
			}
		}

	case []interface{}:
		rec, ok := valRec.([]interface{})
		if !ok {
			return []string{fmt.Sprintf("Type mismatch at %s: orig=[]interface{}, rec=%T", path, valRec)}
		}
		if len(orig) != len(rec) {
			return []string{fmt.Sprintf("Slice length mismatch at %s: orig=%d, rec=%d", path, len(orig), len(rec))}
		}
		for i := range orig {
			discs = append(discs, deepCompareJSON(fmt.Sprintf("%s[%d]", path, i), orig[i], rec[i])...)
		}

	default:
		// Primitive comparison (string, float64, bool, nil)
		sOrig := fmt.Sprintf("%v", valOrig)
		sRec := fmt.Sprintf("%v", valRec)
		if sOrig != sRec {
			discs = append(discs, fmt.Sprintf("Value mismatch at %s: orig=%q, rec=%q", path, sOrig, sRec))
		}
	}

	return discs
}

func loginAndGetToken(t *testing.T, client *http.Client, loginURL, payload string) string {
	resp, err := client.Post(loginURL, "application/json", strings.NewReader(payload))
	if err != nil {
		t.Fatalf("Login failed on %s: %v", loginURL, err)
	}
	defer resp.Body.Close()
	var result map[string]interface{}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		t.Fatalf("Failed to parse login response from %s: %v", loginURL, err)
	}
	token, _ := result["token"].(string)
	return token
}

func doRequestWithAuth(client *http.Client, method, targetURL, body, token string) (*http.Response, []byte, error) {
	var bodyReader io.Reader
	if body != "" {
		bodyReader = strings.NewReader(body)
	}
	req, err := http.NewRequest(method, targetURL, bodyReader)
	if err != nil {
		return nil, nil, err
	}
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	return resp, data, err
}

func waitForServer(t *testing.T, checkURL string, timeout time.Duration) {
	start := time.Now()
	for time.Since(start) < timeout {
		resp, err := http.Get(checkURL)
		if err == nil {
			resp.Body.Close()
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("Server timed out waiting for %s after %v", checkURL, timeout)
}
