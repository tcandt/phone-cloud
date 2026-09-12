package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func setupTestServer(t *testing.T) (*APIServer, *http.ServeMux, func()) {
	tmpDir, err := os.MkdirTemp("", "cloudphone-test-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}

	dataDir := tmpDir + "/data"
	downloadsDir := tmpDir + "/downloads"
	assetsDir := tmpDir + "/assets"
	_ = os.MkdirAll(assetsDir, 0755)

	store := NewPersistenceStore(dataDir)
	hub := NewHub(store)
	auth := NewAuthManager(true, store)
	apiServer := NewAPIServer(hub, auth, store, assetsDir, downloadsDir)

	mux := http.NewServeMux()
	apiServer.RegisterRoutes(mux)

	cleanup := func() {
		_ = os.RemoveAll(tmpDir)
	}

	return apiServer, mux, cleanup
}

func TestProtocolConformance_PublicAPIs(t *testing.T) {
	_, mux, cleanup := setupTestServer(t)
	defer cleanup()

	// 1. GET /api/version
	req := httptest.NewRequest(http.MethodGet, "/api/version", nil)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("Expected 200 for /api/version, got %d", rec.Code)
	}

	var versionResp map[string]interface{}
	if err := json.Unmarshal(rec.Body.Bytes(), &versionResp); err != nil {
		t.Errorf("Invalid version response JSON: %v", err)
	}
	if versionResp["version"] != "0.3.6" && versionResp["version"] != "v0.3.6" {
		t.Errorf("Expected version 0.3.6, got %v", versionResp["version"])
	}

	// 2. GET /api/default_settings
	req = httptest.NewRequest(http.MethodGet, "/api/default_settings", nil)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("Expected 200 for /api/default_settings, got %d", rec.Code)
	}

	// 3. GET /api/license_status
	req = httptest.NewRequest(http.MethodGet, "/api/license_status", nil)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("Expected 200 for /api/license_status, got %d", rec.Code)
	}
}

func TestProtocolConformance_DeviceListSchema(t *testing.T) {
	server, mux, cleanup := setupTestServer(t)
	defer cleanup()

	// Register a simulated agent
	server.hub.RegisterAgent("test-phone-01", &DeviceHardwareInfo{
		AndroidModel:   "Pixel 7",
		AndroidVersion: "14",
		AppVersion:     "0.3.6",
		Displays: []DisplayInfo{
			{ID: 0, XRes: 1080, YRes: 2400},
		},
	}, nil)

	// GET /devices (Format expected by devices.js line 233)
	req := httptest.NewRequest(http.MethodGet, "/devices", nil)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("Expected 200 for /devices, got %d", rec.Code)
	}

	var devList []map[string]interface{}
	if err := json.Unmarshal(rec.Body.Bytes(), &devList); err != nil {
		t.Fatalf("Invalid /devices JSON: %v", err)
	}

	if len(devList) != 1 {
		t.Fatalf("Expected 1 device in list, got %d", len(devList))
	}

	dev := devList[0]
	if dev["device_id"] != "test-phone-01" {
		t.Errorf("Expected device_id 'test-phone-01', got %v", dev["device_id"])
	}
	if dev["online"] != true {
		t.Errorf("Expected online true, got %v", dev["online"])
	}
	if _, ok := dev["device_info"]; !ok {
		t.Errorf("Missing device_info field in /devices response")
	}
}

func TestProtocolConformance_AuthAndPolicy(t *testing.T) {
	_, mux, cleanup := setupTestServer(t)
	defer cleanup()

	// 1. Login default admin
	loginPayload := `{"username":"admin","password":"admin123"}`
	req := httptest.NewRequest(http.MethodPost, "/api/login", strings.NewReader(loginPayload))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("Expected 200 for /api/login, got %d", rec.Code)
	}

	var loginResp map[string]interface{}
	_ = json.Unmarshal(rec.Body.Bytes(), &loginResp)
	token, ok := loginResp["token"].(string)
	if !ok || token == "" {
		t.Fatalf("Login response did not return token")
	}

	// 2. GET /api/me with token
	req = httptest.NewRequest(http.MethodGet, "/api/me", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("Expected 200 for /api/me, got %d", rec.Code)
	}

	// 3. POST /api/user/ai-config
	aiPayload := `{"base_url":"https://api.test.com/v1","api_key":"sk-12345","model":"gpt-4o"}`
	req = httptest.NewRequest(http.MethodPost, "/api/user/ai-config", strings.NewReader(aiPayload))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("Expected 200 for POST /api/user/ai-config, got %d", rec.Code)
	}

	// 4. Parity: GET /api/user/ai-config returns 405 Method Not Allowed
	req = httptest.NewRequest(http.MethodGet, "/api/user/ai-config", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("Expected 405 for GET /api/user/ai-config, got %d", rec.Code)
	}

	// 5. Verification: GET /api/me contains the saved ai_config
	req = httptest.NewRequest(http.MethodGet, "/api/me", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("Expected 200 for GET /api/me, got %d", rec.Code)
	}

	var meResp map[string]interface{}
	_ = json.Unmarshal(rec.Body.Bytes(), &meResp)
	aiResp, _ := meResp["ai_config"].(map[string]interface{})
	if aiResp["ai_model"] != "gpt-4o" || aiResp["ai_api_key"] != "sk-12345" {
		t.Errorf("AI config in /api/me parity failed, got: %v", aiResp)
	}
}

func TestProtocolConformance_BatchOperationsAndFiles(t *testing.T) {
	_, mux, cleanup := setupTestServer(t)
	defer cleanup()

	// 1. Upload a file via POST /upload?type=file&name=test-app.apk
	fileData := []byte("PK\x03\x04DummyApkContentForTestingConformance1234567890")
	req := httptest.NewRequest(http.MethodPost, "/upload?type=file&name=test-app.apk", bytes.NewReader(fileData))
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("Expected 200 for /upload, got %d", rec.Code)
	}

	// 2. List files via GET /api/files
	req = httptest.NewRequest(http.MethodGet, "/api/files", nil)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("Expected 200 for /api/files, got %d", rec.Code)
	}

	var files []map[string]interface{}
	_ = json.Unmarshal(rec.Body.Bytes(), &files)
	if len(files) != 1 || files[0]["name"] != "test-app.apk" {
		t.Errorf("Expected test-app.apk in /api/files list, got: %v", files)
	}

	// 3. Create batch task via POST /api/tasks
	taskPayload := `{
		"type": "install",
		"targets": ["dev-01", "dev-02"],
		"payload": "http://localhost:8443/downloads/test-app.apk",
		"dest_path": "/sdcard/Download/test-app.apk"
	}`
	req = httptest.NewRequest(http.MethodPost, "/api/tasks", strings.NewReader(taskPayload))
	req.Header.Set("Content-Type", "application/json")
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("Expected 200 for POST /api/tasks, got %d", rec.Code)
	}

	var taskResp map[string]interface{}
	_ = json.Unmarshal(rec.Body.Bytes(), &taskResp)
	taskID, ok := taskResp["task_id"].(string)
	if !ok || taskID == "" {
		t.Fatalf("Failed to obtain task_id from task creation")
	}

	// 4. Query task details via GET /api/tasks/details?task_id=...
	req = httptest.NewRequest(http.MethodGet, "/api/tasks/details?task_id="+taskID, nil)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("Expected 200 for /api/tasks/details, got %d", rec.Code)
	}

	var detailsResp map[string]interface{}
	_ = json.Unmarshal(rec.Body.Bytes(), &detailsResp)
	if detailsResp["task_id"] != taskID {
		t.Errorf("Task ID mismatch in details: expected %s, got %v", taskID, detailsResp["task_id"])
	}
}

func TestProtocolConformance_AdminManagement(t *testing.T) {
	server, mux, cleanup := setupTestServer(t)
	defer cleanup()

	// 1. Create user via POST /api/admin/users/create
	userPayload := `{"username":"tester","password":"pwd123"}`
	req := httptest.NewRequest(http.MethodPost, "/api/admin/users/create", strings.NewReader(userPayload))
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("Expected 200 for user create, got %d", rec.Code)
	}

	// 2. Update note via POST /api/admin/users/update_note
	notePayload := `{"username":"tester","note":"QA Device Farm"}`
	req = httptest.NewRequest(http.MethodPost, "/api/admin/users/update_note", strings.NewReader(notePayload))
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("Expected 200 for update_note, got %d", rec.Code)
	}

	// 3. Rename user via POST /api/admin/users/rename
	renamePayload := `{"old_username":"tester","new_username":"tester_renamed"}`
	req = httptest.NewRequest(http.MethodPost, "/api/admin/users/rename", strings.NewReader(renamePayload))
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("Expected 200 for rename, got %d", rec.Code)
	}

	// 4. Update policy via POST /api/admin/users/update
	policyPayload := `{
		"username": "tester_renamed",
		"forbid_bitrate": true,
		"forbid_fps": false,
		"expire_seconds": 86400
	}`
	req = httptest.NewRequest(http.MethodPost, "/api/admin/users/update", strings.NewReader(policyPayload))
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("Expected 200 for user policy update, got %d", rec.Code)
	}

	// 5. Kick user via POST /api/admin/users/kick
	kickPayload := `{"username":"tester_renamed","device_id":"dev-01"}`
	req = httptest.NewRequest(http.MethodPost, "/api/admin/users/kick", strings.NewReader(kickPayload))
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("Expected 200 for kick, got %d", rec.Code)
	}

	// 6. Delete offline device via DELETE /api/devices/{id}
	// First register offline device
	server.hub.store.SaveOfflineDevice(&Device{
		ID:        "offline-phone-99",
		FirstSeen: time.Now(),
	})

	delReq := httptest.NewRequest(http.MethodDelete, "/api/devices/offline-phone-99", nil)
	delRec := httptest.NewRecorder()
	mux.ServeHTTP(delRec, delReq)

	if delRec.Code != http.StatusOK {
		t.Errorf("Expected 200 for deleting offline device, got %d", delRec.Code)
	}
}

func TestProtocolConformance_ShareUpdate(t *testing.T) {
	server, mux, cleanup := setupTestServer(t)
	defer cleanup()

	// 1. Create a share
	server.store.SaveShare(&ShareRecord{
		Token:    "share_abc123",
		DeviceID: "phone-01",
	})

	// 2. Update share via POST /api/share/update
	updatePayload := `{
		"token": "share_abc123",
		"forbid_bitrate": true,
		"guest_settings": {"max_fps": 30}
	}`
	req := httptest.NewRequest(http.MethodPost, "/api/share/update", strings.NewReader(updatePayload))
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("Expected 200 for /api/share/update, got %d", rec.Code)
	}

	var resp map[string]interface{}
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if resp["code"] != float64(0) {
		t.Errorf("Expected code 0 in share update response, got %v", resp["code"])
	}
}
