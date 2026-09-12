package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
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
// the Recovered Signaling Server, feeding identical inputs and performing differential
// schema, status and deterministic value comparison.
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
		"-no-auth",
		"-data", tempDirOrig,
	)
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
		"-no-auth",
		"-data", tempDirRec,
	)
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

	// 4. Test Matrix: Compare HTTP Endpoints
	endpoints := []struct {
		name   string
		method string
		path   string
		body   string
	}{
		{"API_Version", "GET", "/api/version", ""},
		{"Auth_Status", "GET", "/api/auth-status", ""},
		{"Default_Settings", "GET", "/api/default_settings", ""},
		{"License_Status", "GET", "/api/license_status", ""},
		{"Devices_API", "GET", "/api/devices", ""},
		{"Devices_Path", "GET", "/devices", ""},
		{"ICE_Servers", "GET", "/api/ice_servers", ""},
		{"Login_Empty", "POST", "/api/login", `{"username":"","password":""}`},
		{"Login_Invalid", "POST", "/api/login", `{"username":"fake","password":"wrong"}`},
		{"NotFound_Handler", "GET", "/api/nonexistent_test_endpoint", ""},
		{"Share_List", "GET", "/api/share/list", ""},
		{"Share_Info_Empty", "GET", "/api/share/info?token=invalid_token_123", ""},
		{"Tasks_Details_Empty", "GET", "/api/tasks/details?task_id=nonexistent", ""},
		{"Server_Addresses", "GET", "/api/server/addresses", ""},
		{"Shortcuts_List", "GET", "/api/shortcuts", ""},
		{"Tags_List", "GET", "/api/tags", ""},
		{"Method_Not_Allowed_Delete_Version", "DELETE", "/api/version", ""},
	}

	type DiffResult struct {
		Endpoint       string
		OrigStatus     int
		RecStatus      int
		OrigKeys       []string
		RecKeys        []string
		StatusMatch    bool
		SchemaMatch    bool
		Discrepancies  []string
	}

	var results []DiffResult

	for _, tc := range endpoints {
		t.Run(tc.name, func(t *testing.T) {
			urlOrig := fmt.Sprintf("http://127.0.0.1:%d%s", portOrig, tc.path)
			urlRec := fmt.Sprintf("http://127.0.0.1:%d%s", portRec, tc.path)

			respOrig, bodyOrig, errOrig := doRequest(client, tc.method, urlOrig, tc.body)
			if errOrig != nil {
				t.Fatalf("Error querying original server (%s): %v", tc.name, errOrig)
			}
			respRec, bodyRec, errRec := doRequest(client, tc.method, urlRec, tc.body)
			if errRec != nil {
				t.Fatalf("Error querying recovered server (%s): %v", tc.name, errRec)
			}

			diff := DiffResult{
				Endpoint:    tc.path,
				OrigStatus:  respOrig.StatusCode,
				RecStatus:   respRec.StatusCode,
				StatusMatch: respOrig.StatusCode == respRec.StatusCode,
				SchemaMatch: true,
			}

			if !diff.StatusMatch {
				diff.Discrepancies = append(diff.Discrepancies, fmt.Sprintf("HTTP status mismatch: orig=%d, rec=%d", respOrig.StatusCode, respRec.StatusCode))
				t.Logf("WARN: %s Status mismatch: orig=%d, rec=%d", tc.name, respOrig.StatusCode, respRec.StatusCode)
			}

			// If JSON, compare JSON keys
			var jsonOrig, jsonRec map[string]interface{}
			errJOrig := json.Unmarshal(bodyOrig, &jsonOrig)
			errJRec := json.Unmarshal(bodyRec, &jsonRec)

			if errJOrig == nil && errJRec == nil {
				for k := range jsonOrig {
					diff.OrigKeys = append(diff.OrigKeys, k)
					if _, ok := jsonRec[k]; !ok {
						diff.SchemaMatch = false
						diff.Discrepancies = append(diff.Discrepancies, fmt.Sprintf("Recovered missing key: %q", k))
					}
				}
				for k := range jsonRec {
					diff.RecKeys = append(diff.RecKeys, k)
					if _, ok := jsonOrig[k]; !ok {
						diff.SchemaMatch = false
						diff.Discrepancies = append(diff.Discrepancies, fmt.Sprintf("Recovered extra key: %q", k))
					}
				}
				for k, vOrig := range jsonOrig {
					if vRec, ok := jsonRec[k]; ok {
						if k == "version" || k == "status" || k == "license_source" || k == "activated" {
							strOrig := fmt.Sprintf("%v", vOrig)
							strRec := fmt.Sprintf("%v", vRec)
							if strOrig != strRec {
								diff.SchemaMatch = false
								diff.Discrepancies = append(diff.Discrepancies, fmt.Sprintf("Value mismatch on %q: orig=%s, rec=%s", k, strOrig, strRec))
							}
						}
					}
				}
			}

			if !diff.StatusMatch || !diff.SchemaMatch {
				t.Errorf("Differential parity mismatch on %s: %v", tc.path, diff.Discrepancies)
			}

			results = append(results, diff)
			t.Logf("Endpoint [%s %s]: Orig=%d, Rec=%d, SchemaMatch=%v, Discrepancies=%v\nOrigBody: %s\nRecBody:  %s",
				tc.method, tc.path, diff.OrigStatus, diff.RecStatus, diff.SchemaMatch, diff.Discrepancies, string(bodyOrig), string(bodyRec))
		})
	}

	// 5. Test WebSocket Protocol Differential Comparison
	t.Run("WebSocket_AgentRegistration_Differential", func(t *testing.T) {
		wsOrigURL := url.URL{Scheme: "ws", Host: fmt.Sprintf("127.0.0.1:%d", portOrig), Path: "/register_agent"}
		wsRecURL := url.URL{Scheme: "ws", Host: fmt.Sprintf("127.0.0.1:%d", portRec), Path: "/register_agent"}

		// Connect to Original
		connOrig, respOrig, errOrig := websocket.DefaultDialer.Dial(wsOrigURL.String(), nil)
		if errOrig != nil {
			t.Logf("Original WS dial result: err=%v, resp=%v", errOrig, respOrig)
		} else {
			defer connOrig.Close()
		}

		// Connect to Recovered
		connRec, respRec, errRec := websocket.DefaultDialer.Dial(wsRecURL.String(), nil)
		if errRec != nil {
			t.Logf("Recovered WS dial result: err=%v, resp=%v", errRec, respRec)
		} else {
			defer connRec.Close()
		}

		if (errOrig == nil) != (errRec == nil) {
			t.Errorf("WebSocket handshake mismatch: origErr=%v, recErr=%v", errOrig, errRec)
		} else {
			t.Logf("WebSocket handshake status matches: both connected successfully")
		}
	})

	// 6. Print Consolidated Gate C Differential Parity Report
	t.Logf("\n=== GATE C: ORIGINAL VS RECOVERED DIFFERENTIAL PARITY REPORT ===")
	matchCount := 0
	for _, r := range results {
		status := "PASS"
		if !r.StatusMatch || !r.SchemaMatch {
			status = "DIFF"
		} else {
			matchCount++
		}
		t.Logf("[%s] %s | OrigStatus: %d, RecStatus: %d | Discrepancies: %v",
			status, r.Endpoint, r.OrigStatus, r.RecStatus, r.Discrepancies)
	}
	t.Logf("Parity Summary: %d/%d endpoints exactly matched original v0.3.6\n", matchCount, len(results))
}

func doRequest(client *http.Client, method, targetURL, body string) (*http.Response, []byte, error) {
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
