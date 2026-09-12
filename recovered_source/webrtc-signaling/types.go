package main

import (
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// DisplayInfo matches the structure expected by web-app/src/stores/devices.js
type DisplayInfo struct {
	ID   int `json:"id"`
	XRes int `json:"x_res"`
	YRes int `json:"y_res"`
}

// DeviceHardwareInfo represents device metadata reported by the Agent
type DeviceHardwareInfo struct {
	AndroidModel   string        `json:"android_model"`
	AndroidSerial  string        `json:"android_serial"`
	AndroidVersion string        `json:"android_version"`
	AppVersion     string        `json:"app_version"`
	Displays       []DisplayInfo `json:"displays"`
}

// Device represents a registered Android agent device
type Device struct {
	ID                string              `json:"id"`
	Name              string              `json:"name"`
	Status            string              `json:"status"` // "online" or "offline"
	Online            bool                `json:"online"`
	FirstSeen         time.Time           `json:"first_seen"`
	LastSeen          time.Time           `json:"last_seen"`
	LastOffline       *time.Time          `json:"last_offline,omitempty"`
	Info              *DeviceHardwareInfo `json:"info,omitempty"`
	Snapshot          string              `json:"snapshot,omitempty"`
	ActiveConnections int                 `json:"active_connections"`
	Clients           []string            `json:"clients,omitempty"`
	Conn              *websocket.Conn     `json:"-"`
	mu                sync.Mutex          `json:"-"`
}

// Client represents a connected browser web console session
type Client struct {
	ID           string
	UserID       string
	Role         string
	Conn         *websocket.Conn
	ActiveDevice string
	ShareToken   string
	TokenExpiry  time.Time
	mu           sync.Mutex
}

// SignalingMessage defines the protocol envelope for WebSocket communications
type SignalingMessage struct {
	MessageType     string      `json:"message_type,omitempty"`
	Type            string      `json:"type,omitempty"`
	DeviceID        string      `json:"device_id,omitempty"`
	RequestID       string      `json:"request_id,omitempty"`
	Command         string      `json:"command,omitempty"`
	Channel         string      `json:"channel,omitempty"`
	TargetDeviceIDs []string    `json:"target_device_ids,omitempty"`
	Payload         interface{} `json:"payload,omitempty"`
	Data            string      `json:"data,omitempty"`
	Devices         interface{} `json:"devices,omitempty"`
	DeviceInfo      interface{} `json:"device_info,omitempty"`
	IceServers      interface{} `json:"ice_servers,omitempty"`
	Error           string      `json:"error,omitempty"`
}

// User represents an administrative or guest user
type User struct {
	ID              string    `json:"id"`
	Username        string    `json:"username"`
	PasswordHash    string    `json:"password_hash,omitempty"`
	Role            string    `json:"role"` // "admin" or "user"
	Note            string    `json:"note"`
	CreatedAt       time.Time `json:"created_at"`
	AssignedDevices []string  `json:"assigned_devices"`
	ExpiresAt       string    `json:"expires_at"` // empty = never
}

// ShareRecord stores device sharing configuration
type ShareRecord struct {
	Token           string                 `json:"token"`
	DeviceID        string                 `json:"device_id"`
	RequirePassword bool                   `json:"require_password"`
	Password        string                 `json:"password,omitempty"`
	AllowClipboard  bool                   `json:"allow_clipboard"`
	AllowFileTx     bool                   `json:"allow_file_tx"`
	ViewOnly        bool                   `json:"view_only"`
	ExpiresAt       time.Time              `json:"expires_at"`
	CreatedAt       time.Time              `json:"created_at"`
	Creator         string                 `json:"creator"`
	GuestSettings   map[string]interface{} `json:"guest_settings,omitempty"`
	CardCode        string                 `json:"card_code,omitempty"`
	AccessMode      string                 `json:"access_mode,omitempty"`
}

// Tag represents a device categorization tag
type Tag struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Color string `json:"color"`
}

// IceServerConfig represents STUN/TURN server configuration for WebRTC ICE
type IceServerConfig struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username,omitempty"`
	Credential string   `json:"credential,omitempty"`
}

// UserPolicy represents restricted control settings for a user
type UserPolicy struct {
	ForbidBitrate    bool                   `json:"forbid_bitrate"`
	ForbidFPS        bool                   `json:"forbid_fps"`
	ForbidResolution bool                   `json:"forbid_resolution"`
	ForbidAudio      bool                   `json:"forbid_audio"`
	Settings         map[string]interface{} `json:"settings,omitempty"`
}

// AIConfig represents LLM/AI settings for a user
type AIConfig struct {
	BaseURL  string `json:"base_url"`
	APIKey   string `json:"api_key"`
	Model    string `json:"model"`
	Provider string `json:"provider,omitempty"`
}

// StoredFileItem represents a server-stored file for batch distribution
type StoredFileItem struct {
	Name       string    `json:"name"`
	Size       int64     `json:"size"`
	UploadTime time.Time `json:"upload_time"`
}

// BatchTask represents a multi-device asynchronous job
type BatchTask struct {
	TaskID    string                       `json:"task_id"`
	Type      string                       `json:"type"`
	Payload   string                       `json:"payload"`
	CreatedAt time.Time                    `json:"created_at"`
	Devices   map[string]*TaskDeviceStatus `json:"devices"`
	Targets   []string                     `json:"-"`
	DestPath  string                       `json:"-"`
	Status    string                       `json:"-"`
}

// TaskDeviceStatus represents progress of a task on a single device
type TaskDeviceStatus struct {
	DeviceID  string    `json:"device_id"`
	Status    string    `json:"status"`
	Progress  int       `json:"progress"`
	Result    string    `json:"result"`
	UpdatedAt time.Time `json:"updated_at"`
}
