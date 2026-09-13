package main

// Scrcpy Control Message Types (matching com.android.helper.control.ControlMessage)
const (
	ControlMsgInjectKeycode            = 0
	ControlMsgInjectText               = 1
	ControlMsgInjectTouchEvent         = 2
	ControlMsgInjectScrollEvent        = 3
	ControlMsgBackOrScreenOn           = 4
	ControlMsgExpandNotificationPanel  = 5
	ControlMsgExpandSettingsPanel      = 6
	ControlMsgCollapsePanels           = 7
	ControlMsgGetClipboard             = 8
	ControlMsgSetClipboard             = 9
	ControlMsgSetDisplayPower          = 10
	ControlMsgRotateDevice             = 11
	ControlMsgUhidCreate               = 12
	ControlMsgUhidInput                = 13
	ControlMsgUhidDestroy              = 14
	ControlMsgOpenHardKeyboardSettings = 15
	ControlMsgStartApp                 = 16
	ControlMsgResetVideo               = 17
	ControlMsgRequestKeyframe          = 18 // Custom WebRTC IDR sync extension
	ControlMsgSetBitrate               = 19 // Custom WebRTC BWE bitrate adaptation extension
)

// Stream packet flags (matching com.android.helper.device.Streamer)
const (
	PacketFlagConfig   uint64 = 0x8000000000000000
	PacketFlagKeyFrame uint64 = 0x4000000000000000
	PacketPtsMask      uint64 = 0x3fffffffffffffff
)

// AgentConfig encapsulates all runtime parameters from CLI or Environment
type AgentConfig struct {
	SignalingURL       string
	DeviceID           string
	JarPath            string
	ExternalAddr       string
	WebRTCPort         int
	Resolution         int
	Bitrate            int
	MaxSize            int
	MaxFPS             int
	VideoCodecOptions  string
	SnapshotInterval   int
	Root               bool
	Audio              bool
	IceServers         string
	Debug              bool
	CameraAddr         string
	ForceCamera        bool
	AgentSecret        string
	Standalone         bool
}

// ScrcpyOptions encapsulates client-requested streaming parameters received in request-offer
type ScrcpyOptions struct {
	VideoSource       string  `json:"video_source"`       // "display" or "camera"
	CameraFacing      string  `json:"camera_facing"`      // "front", "back", "external"
	CameraID          string  `json:"camera_id"`          // e.g. "0", "1"
	CameraSize        string  `json:"camera_size"`        // e.g. "1920x1080", "1280x720"
	CameraFPS         int     `json:"camera_fps"`         // e.g. 30, 60
	CameraHighSpeed   bool    `json:"camera_high_speed"`
	CameraAr          string  `json:"camera_ar"`          // e.g. "16:9", "4:3"
	CameraZoom        float64 `json:"camera_zoom"`        // e.g. 1.0, 2.0
	CameraOrientation string  `json:"camera_orientation"` // e.g. "auto", "0", "90"
	StayAwake         bool    `json:"stay_awake"`
	PowerOff          bool    `json:"power_off"`
	MaxSize           int     `json:"max_size"`
	VideoBitRate      int     `json:"video_bit_rate"`
	MaxFPS            int     `json:"max_fps"`
	Audio             *bool   `json:"audio"`
	AudioSource       string  `json:"audio_source"`
}

// SessionCapabilities defines permissions granted to a client session
type SessionCapabilities struct {
	CanControl    bool `json:"can_control"`
	CanClipboard  bool `json:"can_clipboard"`
	CanFile       bool `json:"can_file"`
	CanShell      bool `json:"can_shell"`
	CanCamera     bool `json:"can_camera"`
	CanAudio      bool `json:"can_audio"`
	CanRecord     bool `json:"can_record"`
	CanInstallAPK bool `json:"can_install_apk"`
}

// Input JSON structures received from WebRTC 'input-channel' (useWebRTC.js)
type TouchInputMessage struct {
	Type       string `json:"type"` // "touch"
	ID         int64  `json:"id"`
	Seq        int    `json:"seq"`
	ClientTsMs int64  `json:"client_ts_ms"`
	Action     int    `json:"action"` // 0=DOWN, 1=UP, 2=MOVE
	X          int    `json:"x"`
	Y          int    `json:"y"`
	W          int    `json:"w"`
	H          int    `json:"h"`
}

type KeycodeEventMessage struct {
	Type    string `json:"type"` // "inject_keycode"
	Action  int    `json:"action"` // 0=DOWN, 1=UP
	Keycode int    `json:"keycode"`
	Repeat  int    `json:"repeat"`
	Meta    int    `json:"meta"`
}

type TextInputMessage struct {
	Type string `json:"type"` // "inject_text"
	Text string `json:"text"`
}

type ScrollInputMessage struct {
	Type       string  `json:"type"` // "inject_scroll" or "scroll"
	Seq        int     `json:"seq"`
	ClientTsMs int64   `json:"client_ts_ms"`
	X          int     `json:"x"`
	Y          int     `json:"y"`
	W          int     `json:"w"`
	H          int     `json:"h"`
	ScrollH    float32 `json:"scroll_h"`
	ScrollV    float32 `json:"scroll_v"`
}

type ClipboardMessage struct {
	Type   string `json:"type"` // "set_clipboard" or "get_clipboard"
	Text   string `json:"text"`
	Paste  bool   `json:"paste"`
	Source string `json:"source"`
}

// File Channel Types
type FileInfoItem struct {
	Name  string `json:"name"`
	Path  string `json:"path"`
	IsDir bool   `json:"is_dir"`
	Size  int64  `json:"size"`
	Mtime int64  `json:"mtime"`
}

type FileChannelCmd struct {
	Type            string `json:"type"`
	Path            string `json:"path,omitempty"`
	Size            int64  `json:"size,omitempty"`
	Sha256          string `json:"sha256,omitempty"`
	InstallOnFinish bool   `json:"install_on_finish,omitempty"`
	RequestID       string `json:"request_id,omitempty"`
}

type FileListReply struct {
	Type    string         `json:"type"`
	Success bool           `json:"success"`
	Path    string         `json:"path"`
	Files   []FileInfoItem `json:"files"`
	Error   string         `json:"error,omitempty"`
}

type FileSimpleReply struct {
	Type    string `json:"type"`
	Success bool   `json:"success"`
	Error   string `json:"error,omitempty"`
}

type FileDownloadReply struct {
	Type      string `json:"type"`
	Success   bool   `json:"success"`
	Size      int64  `json:"size"`
	RequestID string `json:"request_id"`
	Error     string `json:"error,omitempty"`
}

type FileInstallStatus struct {
	Type      string `json:"type"`
	Status    string `json:"status"` // "installing", "success", "failed"
	Message   string `json:"message,omitempty"`
	RequestID string `json:"request_id"`
}

// AI Command Channel Types
type AICommandRequest struct {
	RequestID string `json:"request_id"`
	Command   string `json:"command"`
}

type AICommandResponse struct {
	RequestID string `json:"request_id"`
	Output    string `json:"output"`
	ExitCode  int    `json:"exit_code"`
}
