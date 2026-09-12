package main

import (
	"encoding/json"
	"flag"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool {
		origin := r.Header.Get("Origin")
		if origin == "" {
			return true // Non-browser clients (Go Agent, Android App, curl)
		}

		// 1. Same-Origin check: Origin host matches Request Host
		u, err := url.Parse(origin)
		if err == nil {
			reqHost := r.Host
			if h, _, errHost := net.SplitHostPort(reqHost); errHost == nil {
				reqHost = h
			}
			originHost := u.Hostname()
			if strings.EqualFold(originHost, reqHost) {
				return true
			}
		}

		// 2. If ALLOWED_ORIGINS env is explicitly configured, enforce strict whitelist
		allowedEnv := os.Getenv("ALLOWED_ORIGINS")
		if allowedEnv != "" {
			if allowedEnv == "*" {
				return true
			}
			parts := strings.Split(allowedEnv, ",")
			for _, p := range parts {
				p = strings.TrimSpace(p)
				if p != "" && (origin == p || strings.HasPrefix(origin, p)) {
					return true
				}
			}
			return false // Reject if not in explicit ALLOWED_ORIGINS
		}

		// 3. Default whitelist: Localhost, loopback, LAN RFC1918 subnets
		if u != nil {
			host := u.Hostname()
			if host == "localhost" || host == "127.0.0.1" || host == "::1" {
				return true
			}
			ip := net.ParseIP(host)
			if ip != nil {
				if ip.IsLoopback() || ip.IsPrivate() {
					return true
				}
			}
		}

		// Reject untrusted cross-origin requests
		return false
	},
}

type APIServer struct {
	hub          *Hub
	auth         *AuthManager
	store        *PersistenceStore
	assetsDir    string
	downloadsDir string
}

func NewAPIServer(hub *Hub, auth *AuthManager, store *PersistenceStore, assetsDir, downloadsDir string) *APIServer {
	_ = os.MkdirAll(downloadsDir, 0755)
	return &APIServer{
		hub:          hub,
		auth:         auth,
		store:        store,
		assetsDir:    assetsDir,
		downloadsDir: downloadsDir,
	}
}

func (s *APIServer) RegisterRoutes(mux *http.ServeMux) {
	// WebSockets
	mux.HandleFunc("/connect_client", s.handleConnectClient)
	mux.HandleFunc("/register_agent", s.handleRegisterAgent)

	// Authentication & Identity
	mux.HandleFunc("/api/login", s.handleLogin)
	mux.HandleFunc("/api/logout", s.handleLogout)
	mux.HandleFunc("/api/register", s.handleRegister)
	mux.HandleFunc("/api/me", s.handleMe)
	mux.HandleFunc("/api/auth-status", s.handleAuthStatus)
	mux.HandleFunc("/api/user/ai-config", s.handleUserAIConfig)

	// Server Config & Metadata
	mux.HandleFunc("/api/version", s.handleVersion)
	mux.HandleFunc("/api/ice_servers", s.handleIceServers)
	mux.HandleFunc("/api/turn", s.handleTurn)
	mux.HandleFunc("/api/default_settings", s.handleDefaultSettings)
	mux.HandleFunc("/api/license_status", s.handleLicenseStatus)
	mux.HandleFunc("/api/activate", s.handleActivate)

	// Devices & Management
	mux.HandleFunc("/devices", s.handleDevicesRoot) // GET /devices requested by devices.js line 233
	mux.HandleFunc("/api/devices", s.handleDevices)
	mux.HandleFunc("/api/devices/", s.handleDeviceOperations)
	mux.HandleFunc("/api/tags", s.handleTags)
	mux.HandleFunc("/api/shortcuts", s.handleShortcuts)

	// Batch File & Task Operations
	mux.HandleFunc("/api/files", s.handleFiles)
	mux.HandleFunc("/upload", s.handleUpload)
	mux.HandleFunc("/downloads/", s.handleDownloads)
	mux.HandleFunc("/api/tasks", s.handleTasks)
	mux.HandleFunc("/api/tasks/details", s.handleTaskDetails)

	// Sharing
	mux.HandleFunc("/api/share/create", s.handleShareCreate)
	mux.HandleFunc("/api/share/list", s.handleShareList)
	mux.HandleFunc("/api/share/info", s.handleShareInfo)
	mux.HandleFunc("/api/share/revoke", s.handleShareRevoke)
	mux.HandleFunc("/api/share/extend", s.handleShareExtend)
	mux.HandleFunc("/api/share/update", s.handleShareUpdate)
	mux.HandleFunc("/api/share/redeem_card", s.handleShareRedeem)
	mux.HandleFunc("/api/server/addresses", s.handleServerAddresses)

	// Admin
	mux.HandleFunc("/api/admin/users", s.handleAdminUsers)
	mux.HandleFunc("/api/admin/users/create", s.handleAdminUserCreate)
	mux.HandleFunc("/api/admin/users/delete", s.handleAdminUserDelete)
	mux.HandleFunc("/api/admin/users/reset_password", s.handleAdminUserResetPassword)
	mux.HandleFunc("/api/admin/users/rename", s.handleAdminUserRename)
	mux.HandleFunc("/api/admin/users/update", s.handleAdminUserUpdate)
	mux.HandleFunc("/api/admin/users/update_note", s.handleAdminUserUpdateNote)
	mux.HandleFunc("/api/admin/users/kick", s.handleAdminUserKick)
	mux.HandleFunc("/api/admin/assign", s.handleAdminAssign)

	// Snapshots & Assets
	mux.HandleFunc("/snapshots/", s.handleSnapshotServe)

	// Static Web Assets (SPA fallback)
	mux.HandleFunc("/", s.handleSPA)
}

// handleConnectClient handles browser Web console WebSocket sessions
func (s *APIServer) handleConnectClient(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("[Signaling] WebSocket upgrade failed: %v", err)
		return
	}

	token := r.URL.Query().Get("token")
	shareToken := r.URL.Query().Get("share_token")

	user, authenticated := s.auth.ValidateToken(token)

	// Validate share link if provided
	var activeShare *ShareRecord
	if shareToken != "" && s.store != nil {
		activeShare = s.store.GetShare(shareToken)
		if activeShare != nil && !activeShare.ExpiresAt.IsZero() {
			if time.Now().After(activeShare.ExpiresAt) {
				activeShare = nil // Expired share link
			}
		}
	}

	// Verify share link password/PIN if required
	if activeShare != nil && activeShare.RequirePassword {
		reqPass := r.URL.Query().Get("password")
		if reqPass == "" {
			reqPass = r.URL.Query().Get("pin")
		}
		if reqPass != activeShare.Password {
			_ = conn.WriteJSON(SignalingMessage{
				MessageType: "error",
				Error:       "Unauthorized access: valid password or PIN required for this share link",
			})
			conn.Close()
			return
		}
	}

	if !authenticated && activeShare == nil && !s.auth.noAuth {
		_ = conn.WriteJSON(SignalingMessage{
			MessageType: "error",
			Error:       "Unauthorized access: valid session token or active share link required",
		})
		conn.Close()
		return
	}

	clientID := uuid.New().String()
	client := &Client{
		ID:         clientID,
		Conn:       conn,
		ShareToken: shareToken,
	}
	if activeShare != nil && !activeShare.ExpiresAt.IsZero() {
		client.TokenExpiry = activeShare.ExpiresAt
	} else if user != nil && user.ExpiresAt != "" {
		if expTime, err := time.Parse(time.RFC3339, user.ExpiresAt); err == nil {
			client.TokenExpiry = expTime
		}
	}
	if user != nil {
		client.UserID = user.ID
		client.Role = user.Role
	}

	// Compute session capabilities
	var clientCaps map[string]interface{}
	if activeShare != nil {
		clientCaps = map[string]interface{}{
			"can_view":      true,
			"can_control":   !activeShare.ViewOnly,
			"can_clipboard": activeShare.AllowClipboard,
			"can_file":      activeShare.AllowFileTx,
			"can_shell":     false,
			"expires_at":    activeShare.ExpiresAt,
			"session_id":    clientID,
		}
	} else if user != nil {
		clientCaps = map[string]interface{}{
			"can_view":      true,
			"can_control":   true,
			"can_clipboard": true,
			"can_file":      true,
			"can_shell":     user.Role == "admin",
			"session_id":    clientID,
		}
	} else {
		clientCaps = map[string]interface{}{
			"can_view":      true,
			"can_control":   true,
			"can_clipboard": true,
			"can_file":      true,
			"can_shell":     false,
			"session_id":    clientID,
		}
	}

	s.hub.RegisterClient(client)
	defer func() {
		s.hub.UnregisterClient(client)
		conn.Close()
	}()

	// Send initial configuration (ICE servers)
	_ = conn.WriteJSON(SignalingMessage{
		MessageType: "config",
		IceServers:  s.hub.iceServers,
	})

	// Send filtered device list according to user RBAC or share binding
	filteredDevices := s.filterDevicesForClient(user, activeShare)
	_ = conn.WriteJSON(SignalingMessage{
		MessageType: "device_list_update",
		Type:        "device_list_update",
		Devices:     filteredDevices,
	})

	for {
		_, rawMsg, err := conn.ReadMessage()
		if err != nil {
			break
		}

		var msg map[string]interface{}
		if err := json.Unmarshal(rawMsg, &msg); err != nil {
			continue
		}

		msgType, _ := msg["message_type"].(string)
		if msgType == "" {
			msgType, _ = msg["type"].(string)
		}
		deviceID, _ := msg["device_id"].(string)

		// Enforce device access permission for targeted device operations
		if deviceID != "" && !canAccessDevice(user, activeShare, s.auth.noAuth, deviceID) {
			_ = conn.WriteJSON(SignalingMessage{
				MessageType: "error",
				Error:       "Access denied: device not assigned to your account",
			})
			continue
		}

		// Enforce view-only restrictions on share links
		if activeShare != nil && activeShare.ViewOnly {
			if msgType == "command" || msgType == "group_control_event" {
				_ = conn.WriteJSON(SignalingMessage{
					MessageType: "error",
					Error:       "Action forbidden: share link is in view-only mode",
				})
				continue
			}
		}

		switch msgType {
		case "connect":
			client.ActiveDevice = deviceID
			if dev, ok := s.hub.GetDevice(deviceID); ok {
				_ = conn.WriteJSON(SignalingMessage{
					MessageType: "device_info",
					DeviceInfo:  dev.Info,
				})
			}
		case "forward":
			if deviceID != "" {
				client.ActiveDevice = deviceID
				s.hub.ForwardToAgent(deviceID, map[string]interface{}{
					"type":         "client_msg",
					"client_id":    client.ID,
					"payload":      msg["payload"],
					"capabilities": clientCaps,
				})
			}
		case "inject_data":
			channel, _ := msg["channel"].(string)
			if activeShare != nil && activeShare.ViewOnly && channel == "input-channel" {
				_ = conn.WriteJSON(SignalingMessage{
					MessageType: "error",
					Error:       "Action forbidden: share link is in view-only mode",
				})
				continue
			}
			if activeShare != nil && !activeShare.AllowClipboard && channel == "clipboard-channel" {
				_ = conn.WriteJSON(SignalingMessage{
					MessageType: "error",
					Error:       "Action forbidden: clipboard synchronization is disabled for this share link",
				})
				continue
			}

			targets, _ := msg["target_device_ids"].([]interface{})
			if len(targets) > 0 {
				for _, t := range targets {
					if devID, ok := t.(string); ok {
						if canAccessDevice(user, activeShare, s.auth.noAuth, devID) {
							s.hub.ForwardToAgent(devID, map[string]interface{}{
								"type":    "inject_data",
								"action":  "inject_data",
								"channel": channel,
								"payload": msg["payload"],
							})
						}
					}
				}
			} else if deviceID != "" && canAccessDevice(user, activeShare, s.auth.noAuth, deviceID) {
				s.hub.ForwardToAgent(deviceID, map[string]interface{}{
					"type":    "inject_data",
					"action":  "inject_data",
					"channel": channel,
					"payload": msg["payload"],
				})
			}
		case "command":
			canShell, _ := clientCaps["can_shell"].(bool)
			if !canShell {
				_ = conn.WriteJSON(SignalingMessage{
					MessageType: "error",
					Error:       "Action forbidden: shell execution capability required",
				})
				continue
			}
			if deviceID != "" {
				s.hub.ForwardToAgent(deviceID, map[string]interface{}{
					"action":     "command",
					"request_id": msg["request_id"],
					"command":    msg["command"],
					"client_id":  client.ID,
					"can_shell":  true,
				})
			}
		case "start_preview":
			s.hub.SubscribePreview(deviceID, client)
			s.hub.ForwardToAgent(deviceID, map[string]interface{}{
				"action":     "start_preview",
				"device_id":  deviceID,
				"fps":        msg["fps"],
				"max_size":   msg["max_size"],
				"bitrate":    msg["bitrate"],
				"stay_awake": msg["stay_awake"],
			})
		case "stop_preview":
			if s.hub.UnsubscribePreview(deviceID, client.ID) {
				s.hub.ForwardToAgent(deviceID, map[string]interface{}{
					"action":    "stop_preview",
					"device_id": deviceID,
				})
			}
		case "group_control_event":
			targets, _ := msg["target_device_ids"].([]interface{})
			for _, t := range targets {
				if devID, ok := t.(string); ok {
					if canAccessDevice(user, activeShare, s.auth.noAuth, devID) {
						s.hub.ForwardToAgent(devID, map[string]interface{}{
							"action":  "group_control_event",
							"payload": msg["event"],
						})
					}
				}
			}
		case "screenshot_request":
			if deviceID != "" {
				s.hub.ForwardToAgent(deviceID, map[string]interface{}{
					"action":    "screenshot",
					"client_id": client.ID,
				})
			}
		}
	}
}

func (s *APIServer) filterDevicesForClient(user *User, share *ShareRecord) []*Device {
	all := s.hub.GetAllDevices()
	if s.auth.noAuth {
		return all
	}
	if share != nil {
		var res []*Device
		for _, d := range all {
			if d.ID == share.DeviceID {
				res = append(res, d)
			}
		}
		return res
	}
	if user == nil {
		return nil
	}
	if user.Role == "admin" {
		return all
	}
	if len(user.AssignedDevices) == 0 {
		return nil // Default deny
	}
	var res []*Device
	for _, d := range all {
		for _, aid := range user.AssignedDevices {
			if d.ID == aid {
				res = append(res, d)
				break
			}
		}
	}
	return res
}

func canAccessDevice(user *User, share *ShareRecord, noAuth bool, targetDeviceID string) bool {
	if noAuth {
		return true
	}
	if share != nil {
		if share.DeviceID != "" && share.DeviceID != targetDeviceID {
			return false
		}
		return true
	}
	if user == nil {
		return false
	}
	if user.Role == "admin" {
		return true
	}
	if len(user.AssignedDevices) == 0 {
		return false // Default deny: empty assigned devices means ZERO devices
	}
	for _, id := range user.AssignedDevices {
		if id == targetDeviceID {
			return true
		}
	}
	return false
}

// handleRegisterAgent handles incoming WebSocket connections from cloudphone-agent on Android
func (s *APIServer) handleRegisterAgent(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("[Agent] WebSocket upgrade failed: %v", err)
		return
	}

	deviceID := r.URL.Query().Get("id")
	if deviceID == "" {
		deviceID = "android-" + uuid.New().String()[:8]
	}

	agentSecret := os.Getenv("AGENT_SECRET")
	if !s.auth.noAuth {
		isDev := os.Getenv("DEV_MODE") == "true" || os.Getenv("DEBUG") == "true" || flag.Lookup("test.v") != nil
		trimmedSecret := strings.ToLower(strings.TrimSpace(agentSecret))
		insecureDefaults := map[string]bool{
			"cloudphone_production_agent_secret_2026": true,
			"changeme":                                 true,
			"secret":                                   true,
			"admin":                                    true,
			"123456":                                   true,
			"password":                                 true,
			"default":                                  true,
		}
		if (trimmedSecret == "" || insecureDefaults[trimmedSecret]) && !isDev {
			log.Printf("[Agent] FATAL/DENY: Insecure or unconfigured AGENT_SECRET in production mode! Rejecting agent registration for device: %s", deviceID)
			_ = conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.ClosePolicyViolation, "AGENT_SECRET must be configured securely in production"), time.Now().Add(time.Second))
			conn.Close()
			return
		}

		if agentSecret != "" {
			token := r.URL.Query().Get("token")
			if token == "" {
				token = r.URL.Query().Get("secret")
			}
			if token != agentSecret {
				log.Printf("[Agent] Unauthorized registration attempt for device: %s", deviceID)
				_ = conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.ClosePolicyViolation, "Unauthorized agent secret"), time.Now().Add(time.Second))
				conn.Close()
				return
			}
		}
	}

	dev := s.hub.RegisterAgent(deviceID, nil, conn)
	defer func() {
		s.hub.UnregisterAgent(deviceID)
		conn.Close()
	}()

	for {
		msgTypeInt, rawMsg, err := conn.ReadMessage()
		if err != nil {
			break
		}

		if msgTypeInt == websocket.BinaryMessage {
			// Binary H.264 PREV frame from Agent, forward directly to web preview subscribers!
			s.hub.BroadcastPreviewBinary(deviceID, rawMsg)
			continue
		}

		var payload map[string]interface{}
		if err := json.Unmarshal(rawMsg, &payload); err != nil {
			continue
		}

		action, _ := payload["action"].(string)
		msgType, _ := payload["type"].(string)

		if action == "register" || msgType == "register" {
			if idVal, ok := payload["device_id"].(string); ok && idVal != "" {
				s.hub.mu.Lock()
				if dev.ID != idVal {
					delete(s.hub.devices, dev.ID)
					dev.ID = idVal
					dev.Name = idVal
					s.hub.devices[idVal] = dev
					deviceID = idVal
				}
				s.hub.mu.Unlock()
			}
			infoBytes, _ := json.Marshal(payload["device_info"])
			if len(infoBytes) <= 4 {
				infoBytes, _ = json.Marshal(payload["info"])
			}
			var hwInfo DeviceHardwareInfo
			_ = json.Unmarshal(infoBytes, &hwInfo)
			dev.Info = &hwInfo
			s.hub.BroadcastDeviceList()
			_ = conn.WriteJSON(map[string]interface{}{
				"action":    "registered",
				"type":      "registered",
				"device_id": dev.ID,
			})
		} else if action == "snapshot" || msgType == "snapshot" {
			// Snapshot update
		} else if action == "command_result" || msgType == "command_result" {
			clientID, _ := payload["client_id"].(string)
			if clientID != "" {
				s.hub.ForwardToClient(clientID, SignalingMessage{
					MessageType: "command_result",
					DeviceID:    deviceID,
					Payload:     payload,
				})
			}
		} else if msgType == "offer" || msgType == "ice-candidate" || msgType == "answer" {
			clientID, _ := payload["client_id"].(string)
			if clientID != "" {
				s.hub.ForwardToClient(clientID, SignalingMessage{
					MessageType: "device_msg",
					DeviceID:    deviceID,
					Payload:     payload,
				})
			}
		}
	}
}

func (s *APIServer) authenticateRequest(r *http.Request) (*User, bool) {
	if s.auth == nil || s.auth.noAuth {
		return &User{ID: "anonymous", Username: "anonymous", Role: "admin"}, true
	}
	authHeader := r.Header.Get("Authorization")
	token := strings.TrimPrefix(authHeader, "Bearer ")
	if token == "" {
		token = r.URL.Query().Get("token")
	}
	if token == "" {
		return nil, false
	}
	return s.auth.ValidateToken(token)
}

// GET /devices - returns format expected by web-app/src/stores/devices.js
func (s *APIServer) handleDevicesRoot(w http.ResponseWriter, r *http.Request) {
	token := r.URL.Query().Get("token")
	if token == "" {
		token = r.Header.Get("Authorization")
		token = strings.TrimPrefix(token, "Bearer ")
	}
	shareToken := r.URL.Query().Get("share_token")

	user, authenticated := s.auth.ValidateToken(token)
	var activeShare *ShareRecord
	if shareToken != "" && s.store != nil {
		activeShare = s.store.GetShare(shareToken)
		if activeShare != nil && !activeShare.ExpiresAt.IsZero() && time.Now().After(activeShare.ExpiresAt) {
			activeShare = nil
		}
	}

	if !authenticated && activeShare == nil && !s.auth.noAuth {
		http.Error(w, "Unauthorized: valid session token or active share link required", http.StatusUnauthorized)
		return
	}

	devices := s.filterDevicesForClient(user, activeShare)
	type DeviceListItem struct {
		DeviceID    string              `json:"device_id"`
		DeviceInfo  *DeviceHardwareInfo `json:"device_info"`
		Online      bool                `json:"online"`
		FirstSeen   time.Time           `json:"first_seen"`
		LastSeen    time.Time           `json:"last_seen"`
		ClientCount int                 `json:"client_count"`
		Clients     []string            `json:"clients"`
	}

	result := make([]DeviceListItem, 0, len(devices))
	for _, d := range devices {
		clients := d.Clients
		if clients == nil {
			clients = make([]string, 0)
		}
		result = append(result, DeviceListItem{
			DeviceID:    d.ID,
			DeviceInfo:  d.Info,
			Online:      d.Online,
			FirstSeen:   d.FirstSeen,
			LastSeen:    d.LastSeen,
			ClientCount: d.ActiveConnections,
			Clients:     clients,
		})
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(result)
}

// GET /api/devices - original v0.3.6 disallows GET on /api/devices (only GET /devices is used for list)
func (s *APIServer) handleDevices(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.authenticateRequest(r); !ok {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	if r.Method == http.MethodGet {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}
	devices := s.hub.GetAllDevices()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(devices)
}

// /api/devices/{id} operations (DELETE offline device)
func (s *APIServer) handleDeviceOperations(w http.ResponseWriter, r *http.Request) {
	deviceID := strings.TrimPrefix(r.URL.Path, "/api/devices/")
	if deviceID == "" {
		http.Error(w, "Device ID required", http.StatusBadRequest)
		return
	}

	if r.Method == http.MethodDelete {
		dev, ok := s.hub.GetDevice(deviceID)
		if ok && dev.Online {
			http.Error(w, "Cannot delete an online device", http.StatusBadRequest)
			return
		}

		if s.store != nil {
			s.store.RemoveOfflineDevice(deviceID)
		}
		s.hub.mu.Lock()
		delete(s.hub.devices, deviceID)
		s.hub.mu.Unlock()

		s.hub.BroadcastDeviceList()
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("Deleted"))
		return
	}

	dev, ok := s.hub.GetDevice(deviceID)
	if !ok {
		http.Error(w, "Device not found", http.StatusNotFound)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(dev)
}

// GET /api/files - list files available in downloadsDir
func (s *APIServer) handleFiles(w http.ResponseWriter, r *http.Request) {
	entries, err := os.ReadDir(s.downloadsDir)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode([]StoredFileItem{})
		return
	}

	files := make([]StoredFileItem, 0, len(entries))
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		files = append(files, StoredFileItem{
			Name:       e.Name(),
			Size:       info.Size(),
			UploadTime: info.ModTime(),
		})
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(files)
}

// POST /upload?type=file&name=filename
func (s *APIServer) handleUpload(w http.ResponseWriter, r *http.Request) {
	fileName := r.URL.Query().Get("name")

	// Check if multipart form upload
	if file, header, err := r.FormFile("file"); err == nil {
		defer file.Close()
		if fileName == "" {
			fileName = header.Filename
		}
		fileName = filepath.Base(fileName)
		destPath := filepath.Join(s.downloadsDir, fileName)
		destFile, err := os.Create(destPath)
		if err != nil {
			http.Error(w, "Failed to create destination file: "+err.Error(), http.StatusInternalServerError)
			return
		}
		defer destFile.Close()

		if _, err := io.Copy(destFile, file); err != nil {
			http.Error(w, "Failed to write file: "+err.Error(), http.StatusInternalServerError)
			return
		}

		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("Upload successful"))
		return
	}

	if fileName == "" {
		fileName = "upload-" + time.Now().Format("20060102150405")
	}
	fileName = filepath.Base(fileName) // Sanitize

	destPath := filepath.Join(s.downloadsDir, fileName)
	destFile, err := os.Create(destPath)
	if err != nil {
		http.Error(w, "Failed to create destination file: "+err.Error(), http.StatusInternalServerError)
		return
	}
	defer destFile.Close()

	if _, err := io.Copy(destFile, r.Body); err != nil {
		http.Error(w, "Failed to write file: "+err.Error(), http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("Upload successful"))
}

// GET /downloads/{name}
func (s *APIServer) handleDownloads(w http.ResponseWriter, r *http.Request) {
	http.StripPrefix("/downloads/", http.FileServer(http.Dir(s.downloadsDir))).ServeHTTP(w, r)
}

// POST /api/tasks and GET /api/tasks
func (s *APIServer) handleTasks(w http.ResponseWriter, r *http.Request) {
	user, ok := s.authenticateRequest(r)
	if !ok {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	if r.Method == http.MethodPost {
		// Only administrators can dispatch device commands and batch tasks
		if !s.auth.noAuth && user != nil && user.Role != "admin" {
			http.Error(w, "Forbidden: batch tasks execution requires administrator privilege", http.StatusForbidden)
			return
		}
		var req struct {
			Type     string   `json:"type"`
			Targets  []string `json:"targets"`
			Payload  string   `json:"payload"`
			DestPath string   `json:"dest_path"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "Invalid task request: "+err.Error(), http.StatusBadRequest)
			return
		}

		taskID := uuid.New().String()
		task := &BatchTask{
			TaskID:    taskID,
			Type:      req.Type,
			Targets:   req.Targets,
			Payload:   req.Payload,
			DestPath:  req.DestPath,
			Status:    "running",
			CreatedAt: time.Now(),
			Devices:   make(map[string]*TaskDeviceStatus),
		}

		for _, t := range req.Targets {
			task.Devices[t] = &TaskDeviceStatus{
				Status:   "pending",
				Progress: 0,
				Message:  "Queued",
			}
		}

		if s.store != nil {
			s.store.SaveTask(task)
		}

		// Dispatch to target devices asynchronously
		go func() {
			for _, target := range req.Targets {
				task.Devices[target].Status = "running"
				task.Devices[target].Progress = 50

				var cmdStr string
				switch req.Type {
				case "install":
					cmdStr = "pm install -r " + req.DestPath
				case "start_app":
					cmdStr = "monkey -p " + req.Payload + " -c android.intent.category.LAUNCHER 1"
				case "stop_app":
					cmdStr = "am force-stop " + req.Payload
				case "uninstall":
					cmdStr = "pm uninstall " + req.Payload
				case "clear_data":
					cmdStr = "pm clear " + req.Payload
				case "shell":
					cmdStr = req.Payload
				case "reboot":
					cmdStr = "reboot"
				default:
					cmdStr = req.Payload
				}

				sent := s.hub.ForwardToAgent(target, map[string]interface{}{
					"action":     "command",
					"command":    cmdStr,
					"request_id": taskID,
					"can_shell":  true,
				})

				if sent {
					task.Devices[target].Status = "success"
					task.Devices[target].Progress = 100
					task.Devices[target].Message = "Completed"
				} else {
					task.Devices[target].Status = "failed"
					task.Devices[target].Message = "Device unreachable"
				}
			}
			task.Status = "completed"
			if s.store != nil {
				s.store.SaveTask(task)
			}
		}()

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"status":  "success",
			"task_id": taskID,
		})
		return
	}

	http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
}

// GET /api/tasks/details?task_id=...
func (s *APIServer) handleTaskDetails(w http.ResponseWriter, r *http.Request) {
	taskID := r.URL.Query().Get("task_id")
	if taskID == "" {
		http.Error(w, "task_id required", http.StatusBadRequest)
		return
	}

	if s.store != nil {
		task := s.store.GetTask(taskID)
		if task != nil {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(task)
			return
		}
	}

	http.Error(w, "Task not found", http.StatusNotFound)
}

// GET & POST /api/user/ai-config
func (s *APIServer) handleUserAIConfig(w http.ResponseWriter, r *http.Request) {
	username := "admin" // Default
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if user, ok := s.auth.ValidateToken(token); ok && user != nil {
		username = user.Username
	}

	if r.Method == http.MethodPost {
		var cfg AIConfig
		if err := json.NewDecoder(r.Body).Decode(&cfg); err != nil {
			http.Error(w, "Invalid AI config: "+err.Error(), http.StatusBadRequest)
			return
		}
		if s.store != nil {
			s.store.SaveAIConfig(username, &cfg)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("Saved"))
		return
	}

	// GET
	var cfg *AIConfig
	if s.store != nil {
		cfg = s.store.GetAIConfig(username)
	}
	if cfg == nil {
		cfg = &AIConfig{
			BaseURL: "https://api.openai.com/v1",
			Model:   "gpt-4o",
		}
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(cfg)
}

// POST /api/admin/users/rename
func (s *APIServer) handleAdminUserRename(w http.ResponseWriter, r *http.Request) {
	user, ok := s.authenticateRequest(r)
	if !ok || user == nil || user.Role != "admin" {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	var req struct {
		OldUsername string `json:"old_username"`
		NewUsername string `json:"new_username"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.NewUsername == "" {
		http.Error(w, "Invalid request", http.StatusBadRequest)
		return
	}

	if s.auth.RenameUser(req.OldUsername, req.NewUsername) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("Renamed"))
	} else {
		http.Error(w, "User not found", http.StatusNotFound)
	}
}

// POST /api/admin/users/update
func (s *APIServer) handleAdminUserUpdate(w http.ResponseWriter, r *http.Request) {
	user, ok := s.authenticateRequest(r)
	if !ok || user == nil || user.Role != "admin" {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	var req struct {
		Username         string                 `json:"username"`
		ForbidBitrate    bool                   `json:"forbid_bitrate"`
		ForbidFPS        bool                   `json:"forbid_fps"`
		ForbidResolution bool                   `json:"forbid_resolution"`
		ForbidAudio      bool                   `json:"forbid_audio"`
		Settings         map[string]interface{} `json:"settings"`
		ExpireSeconds    int64                  `json:"expire_seconds"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Username == "" {
		http.Error(w, "Invalid request", http.StatusBadRequest)
		return
	}

	if s.store != nil {
		s.store.SaveUserPolicy(req.Username, &UserPolicy{
			ForbidBitrate:    req.ForbidBitrate,
			ForbidFPS:        req.ForbidFPS,
			ForbidResolution: req.ForbidResolution,
			ForbidAudio:      req.ForbidAudio,
			Settings:         req.Settings,
		})

		user := s.store.GetUser(req.Username)
		if user != nil && req.ExpireSeconds > 0 {
			user.ExpiresAt = time.Now().Add(time.Duration(req.ExpireSeconds) * time.Second).Format(time.RFC3339)
			s.store.SaveUser(user)
		}
	}

	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("Updated"))
}

// POST /api/admin/users/update_note
func (s *APIServer) handleAdminUserUpdateNote(w http.ResponseWriter, r *http.Request) {
	user, ok := s.authenticateRequest(r)
	if !ok || user == nil || user.Role != "admin" {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	var req struct {
		Username string `json:"username"`
		Note     string `json:"note"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Username == "" {
		http.Error(w, "Invalid request", http.StatusBadRequest)
		return
	}

	if s.store != nil {
		user := s.store.GetUser(req.Username)
		if user != nil {
			user.Note = req.Note
			s.store.SaveUser(user)
		}
	}
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("Note updated"))
}

// POST /api/admin/users/kick
func (s *APIServer) handleAdminUserKick(w http.ResponseWriter, r *http.Request) {
	user, ok := s.authenticateRequest(r)
	if !ok || user == nil || user.Role != "admin" {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	var req struct {
		Username string `json:"username"`
		DeviceID string `json:"device_id"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)

	s.hub.KickUser(req.Username, req.DeviceID)
	s.hub.KickClientsByUserID(req.Username)
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("Kicked"))
}

// POST /api/share/update
func (s *APIServer) handleShareUpdate(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Token            string                 `json:"token"`
		ForbidBitrate    bool                   `json:"forbid_bitrate"`
		ForbidFPS        bool                   `json:"forbid_fps"`
		ForbidResolution bool                   `json:"forbid_resolution"`
		ForbidAudio      bool                   `json:"forbid_audio"`
		ViewOnly         bool                   `json:"view_only"`
		CanControl       *bool                  `json:"can_control"`
		GuestSettings    map[string]interface{} `json:"guest_settings"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Token == "" {
		http.Error(w, "Invalid request", http.StatusBadRequest)
		return
	}

	if s.store != nil {
		share := s.store.GetShare(req.Token)
		if share != nil {
			share.GuestSettings = req.GuestSettings
			if req.CanControl != nil {
				share.ViewOnly = !*req.CanControl
			} else if req.ViewOnly {
				share.ViewOnly = true
			}
			s.store.SaveShare(share)

			// Real-time capability update pushed to active WebRTC agent sessions
			canControl := !share.ViewOnly
			s.hub.UpdateShareCaps(share.Token, map[string]interface{}{
				"can_control":   canControl,
				"can_clipboard": canControl && share.AllowClipboard,
				"can_file":      canControl && share.AllowFileTx,
				"can_shell":     false,
			})
		}
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"code": 0,
		"msg":  "success",
	})
}

// Standard handlers
func (s *APIServer) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Bad request", http.StatusBadRequest)
		return
	}
	if req.Username == "" || req.Password == "" {
		http.Error(w, "Username and password required", http.StatusBadRequest)
		return
	}

	token, user, ok := s.auth.Authenticate(req.Username, req.Password)
	if !ok {
		http.Error(w, "Invalid username or password", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"token":            token,
		"user":             user,
		"assigned_devices": user.AssignedDevices,
	})
}

func (s *APIServer) handleLogout(w http.ResponseWriter, r *http.Request) {
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	s.auth.InvalidateToken(token)
	w.WriteHeader(http.StatusOK)
}

func (s *APIServer) handleRegister(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Bad request", http.StatusBadRequest)
		return
	}

	user, err := s.auth.RegisterUser(req.Username, req.Password, "user", "")
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(user)
}

func (s *APIServer) handleMe(w http.ResponseWriter, r *http.Request) {
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	user, ok := s.auth.ValidateToken(token)
	if !ok {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}

	var policy *UserPolicy
	if s.store != nil {
		policy = s.store.GetUserPolicy(user.Username)
	}

	resp := map[string]interface{}{
		"id":               user.ID,
		"username":         user.Username,
		"role":             user.Role,
		"assigned_devices": user.AssignedDevices,
		"expires_at":       user.ExpiresAt,
	}
	if policy != nil {
		resp["forbid_bitrate"] = policy.ForbidBitrate
		resp["forbid_fps"] = policy.ForbidFPS
		resp["forbid_resolution"] = policy.ForbidResolution
		resp["forbid_audio"] = policy.ForbidAudio
		resp["settings"] = policy.Settings
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}

func (s *APIServer) handleAuthStatus(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"noAuth": s.auth.noAuth,
	})
}

func (s *APIServer) handleVersion(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"version":    "v0.3.6",
		"git_commit": "2693ef1",
		"build_time": "2026-09-07T09:58:25Z",
	})
}

func (s *APIServer) handleIceServers(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(s.hub.iceServers)
}

func (s *APIServer) handleTurn(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(s.hub.iceServers)
}

func (s *APIServer) handleDefaultSettings(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{})
}

func (s *APIServer) handleLicenseStatus(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"activated":              false,
		"current_devices":        len(s.hub.devices),
		"customer":               "",
		"days_remaining":         50,
		"error_msg":              "",
		"expires_at":             "2026-11-01",
		"license_expired":        false,
		"license_source":         "built-in",
		"machine_id":             "8AD9-A7EF-87FB-E780",
		"max_devices":            20,
		"post_promo_max_devices": 10,
		"promo":                  true,
		"status":                 "valid",
	})
}

func (s *APIServer) handleActivate(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"status":  "success",
		"message": "Activated successfully",
	})
}

func (s *APIServer) handleTags(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"tags":       s.hub.tags,
		"deviceTags": map[string]interface{}{},
	})
}

func (s *APIServer) handleShortcuts(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode([]map[string]interface{}{})
}

func (s *APIServer) handleShareCreate(w http.ResponseWriter, r *http.Request) {
	var req ShareRecord
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Bad request", http.StatusBadRequest)
		return
	}
	req.Token = uuid.New().String()[:12]
	req.CreatedAt = time.Now()
	if s.store != nil {
		s.store.SaveShare(&req)
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(req)
}

func (s *APIServer) handleShareList(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	var shares []*ShareRecord
	if s.store != nil {
		shares = s.store.GetAllShares()
	}
	if shares == nil {
		shares = make([]*ShareRecord, 0)
	}
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"code": 0,
		"data": shares,
	})
}

func (s *APIServer) handleShareInfo(w http.ResponseWriter, r *http.Request) {
	token := r.URL.Query().Get("token")
	if s.store != nil {
		share := s.store.GetShare(token)
		if share != nil {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(share)
			return
		}
	}
	http.Error(w, "Share token not found", http.StatusNotFound)
}

func (s *APIServer) handleShareRevoke(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Token string `json:"token"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	if s.store != nil && req.Token != "" {
		s.store.DeleteShare(req.Token)
		s.hub.KickClientsByShareToken(req.Token)
	}
	w.WriteHeader(http.StatusOK)
}

func (s *APIServer) handleShareExtend(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
}

func (s *APIServer) handleShareRedeem(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
}

func (s *APIServer) handleServerAddresses(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"addresses": []string{r.Host},
			"current":   r.Host,
		},
	})
}



func (s *APIServer) handleAdminUsers(w http.ResponseWriter, r *http.Request) {
	user, ok := s.authenticateRequest(r)
	if !ok || user == nil || user.Role != "admin" {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(s.auth.ListUsers())
}

func (s *APIServer) handleAdminUserCreate(w http.ResponseWriter, r *http.Request) {
	user, ok := s.authenticateRequest(r)
	if !ok || user == nil || user.Role != "admin" {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	s.handleRegister(w, r)
}

func (s *APIServer) handleAdminUserDelete(w http.ResponseWriter, r *http.Request) {
	user, ok := s.authenticateRequest(r)
	if !ok || user == nil || user.Role != "admin" {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	var req struct {
		Username string `json:"username"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	s.auth.DeleteUser(req.Username)
	w.WriteHeader(http.StatusOK)
}

func (s *APIServer) handleAdminUserResetPassword(w http.ResponseWriter, r *http.Request) {
	user, ok := s.authenticateRequest(r)
	if !ok || user == nil || user.Role != "admin" {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	var req struct {
		Username    string `json:"username"`
		NewPassword string `json:"new_password"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	_ = s.auth.ResetPassword(req.Username, req.NewPassword)
	w.WriteHeader(http.StatusOK)
}

func (s *APIServer) handleAdminAssign(w http.ResponseWriter, r *http.Request) {
	user, ok := s.authenticateRequest(r)
	if !ok || user == nil || user.Role != "admin" {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	w.WriteHeader(http.StatusOK)
}

func (s *APIServer) handleSnapshotServe(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "image/jpeg")
	w.WriteHeader(http.StatusOK)
}

func (s *APIServer) handleSPA(w http.ResponseWriter, r *http.Request) {
	path := filepath.Join(s.assetsDir, filepath.Clean(r.URL.Path))
	if info, err := os.Stat(path); err == nil && !info.IsDir() {
		http.ServeFile(w, r, path)
		return
	}
	// Fallback to index.html for Vue SPA history routing
	http.ServeFile(w, r, filepath.Join(s.assetsDir, "index.html"))
}
