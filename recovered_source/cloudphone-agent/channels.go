package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/pion/webrtc/v3"
)

var (
	latestCameraJpeg   []byte
	latestCameraJpegMu sync.RWMutex
	cameraStreaming    bool
	cameraFacing       = "environment" // "environment" or "user"
	cameraOrientation  = 0
	cameraFps          = 30
	cameraFrameChan    = make(chan []byte, 16)
	cameraStateMu      sync.RWMutex

	cameraFrameConsumerOnce sync.Once
	cameraConsumerCb        func([]byte)
	cameraConsumerCbMu      sync.RWMutex
)

// SetCameraFrameConsumer registers a callback to receive incoming camera frames (e.g. virtual camera device / sink)
func SetCameraFrameConsumer(cb func([]byte)) {
	cameraConsumerCbMu.Lock()
	cameraConsumerCb = cb
	cameraConsumerCbMu.Unlock()
}

func initCameraBridgeConsumer() {
	cameraFrameConsumerOnce.Do(func() {
		go func() {
			var frameCount int
			lastStatTime := time.Now()
			for frame := range cameraFrameChan {
				frameCount++
				now := time.Now()
				elapsed := now.Sub(lastStatTime)
				if elapsed >= 1*time.Second {
					measuredFps := int(float64(frameCount) / elapsed.Seconds())
					if measuredFps > 0 {
						cameraStateMu.Lock()
						cameraFps = measuredFps
						cameraStateMu.Unlock()
					}
					frameCount = 0
					lastStatTime = now
				}

				cameraConsumerCbMu.RLock()
				cb := cameraConsumerCb
				cameraConsumerCbMu.RUnlock()
				if cb != nil {
					cb(frame)
				}
			}
		}()
	})
}

// generateTestPatternJpeg generates a valid JPEG test frame with color gradient
func generateTestPatternJpeg(width, height int) []byte {
	img := image.NewRGBA(image.Rect(0, 0, width, height))
	for y := 0; y < height; y++ {
		for x := 0; x < width; x++ {
			c := color.RGBA{
				R: uint8((x * 255) / width),
				G: uint8((y * 255) / height),
				B: 128,
				A: 255,
			}
			img.Set(x, y, c)
		}
	}
	var buf bytes.Buffer
	_ = jpeg.Encode(&buf, img, &jpeg.Options{Quality: 75})
	return buf.Bytes()
}

type UploadSession struct {
	Path            string
	Size            int64
	Sha256          string
	InstallOnFinish bool
	File            *os.File
	BytesReceived   int64
	Hasher          io.Writer
}

var (
	activeUploads   = make(map[string]*UploadSession)
	activeUploadsMu sync.Mutex
)

// sanitizeFilePath cleans rawPath, prevents directory traversal escapes, and returns safe path
func sanitizeFilePath(rawPath string) (string, error) {
	if rawPath == "" {
		if runtime.GOOS == "windows" {
			return ".\\", nil
		}
		return "/sdcard", nil
	}

	// Check traversal across Unix and Windows notations regardless of current GOOS
	norm := strings.ReplaceAll(rawPath, "\\", "/")
	if strings.Contains(norm, "../") || strings.HasSuffix(norm, "..") || strings.Contains(rawPath, "..") {
		return "", fmt.Errorf("directory traversal forbidden: %s", rawPath)
	}

	clean := filepath.Clean(rawPath)
	return clean, nil
}

// isCriticalSystemPath checks if a path points to sensitive root filesystems or system trees that must not be deleted or corrupted
func isCriticalSystemPath(path string) bool {
	norm := strings.ReplaceAll(path, "\\", "/")
	for strings.Contains(norm, "//") {
		norm = strings.ReplaceAll(norm, "//", "/")
	}
	normLower := strings.ToLower(norm)

	// Root directories that must NEVER be wiped or deleted in their entirety
	exactForbidden := []string{
		"/", "/data", "/sdcard", "/storage", "/storage/emulated", "/storage/emulated/0",
		"c:", "c:/",
	}
	for _, f := range exactForbidden {
		if normLower == f || strings.TrimSuffix(normLower, "/") == strings.TrimSuffix(f, "/") {
			return true
		}
	}

	// Critical OS system trees where neither the tree NOR ANY SUBPATH can be touched
	criticalTrees := []string{
		"/system",
		"/vendor",
		"/apex",
		"/etc",
		"/proc",
		"/sys",
		"/dev",
		"/boot",
		"/recovery",
		"/data/system",
		"c:/windows",
		"c:/program files",
		"c:/program files (x86)",
	}
	for _, tree := range criticalTrees {
		if normLower == tree || strings.HasPrefix(normLower, tree+"/") {
			return true
		}
	}

	return false
}

// setupFileChannel sets up message handlers for 'file-channel'
func setupFileChannel(dc *webrtc.DataChannel, s *WebRTCSession) {
	var currentUpload *UploadSession

	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		if s != nil && !s.Caps.CanFile {
			log.Printf("[FileChannel] Action rejected: session does not have CanFile permission")
			return
		}
		if !msg.IsString {
			// Binary chunk for upload
			if currentUpload == nil || currentUpload.File == nil {
				log.Printf("[FileChannel] Received binary chunk but no active upload session")
				return
			}

			n, err := currentUpload.File.Write(msg.Data)
			if err != nil {
				log.Printf("[FileChannel] Error writing upload chunk: %v", err)
				return
			}
			currentUpload.BytesReceived += int64(n)

			if currentUpload.BytesReceived >= currentUpload.Size {
				currentUpload.File.Close()
				destPath := currentUpload.Path
				install := currentUpload.InstallOnFinish

				log.Printf("[FileChannel] Upload complete for %s (%d bytes)", destPath, currentUpload.BytesReceived)

				// Verify SHA256 if provided
				match := true
				if currentUpload.Sha256 != "" {
					f, err := os.Open(destPath)
					if err == nil {
						h := sha256.New()
						_, _ = io.Copy(h, f)
						f.Close()
						calcHash := hex.EncodeToString(h.Sum(nil))
						match = strings.EqualFold(calcHash, currentUpload.Sha256)
					} else {
						match = false
					}
				}

				if !match {
					log.Printf("[FileChannel] SHA256 mismatch for %s", destPath)
					ackBytes, _ := json.Marshal(FileSimpleReply{
						Type:    "upload_ack",
						Success: false,
						Error:   "SHA256 hash mismatch",
					})
					_ = dc.SendText(string(ackBytes))
					currentUpload = nil
					return
				}

				// If APK and install requested
				if install && strings.HasSuffix(strings.ToLower(destPath), ".apk") {
					go handleApkInstall(dc, destPath, "upload_auto")
				}

				ackBytes, _ := json.Marshal(FileSimpleReply{
					Type:    "upload_ack",
					Success: true,
				})
				_ = dc.SendText(string(ackBytes))

				currentUpload = nil
			}
			return
		}

		// JSON command
		var cmd FileChannelCmd
		if err := json.Unmarshal(msg.Data, &cmd); err != nil {
			log.Printf("[FileChannel] Invalid JSON command: %v", err)
			return
		}

		switch cmd.Type {
		case "list":
			reqPath, err := sanitizeFilePath(cmd.Path)
			if err != nil {
				replyBytes, _ := json.Marshal(FileListReply{
					Type:    "list_reply",
					Success: false,
					Path:    cmd.Path,
					Error:   err.Error(),
				})
				_ = dc.SendText(string(replyBytes))
				return
			}

			entries, err := os.ReadDir(reqPath)
			if err != nil {
				replyBytes, _ := json.Marshal(FileListReply{
					Type:    "list_reply",
					Success: false,
					Path:    reqPath,
					Error:   err.Error(),
				})
				_ = dc.SendText(string(replyBytes))
				return
			}

			var fileList []FileInfoItem
			for _, e := range entries {
				info, err := e.Info()
				if err != nil {
					continue
				}
				fileList = append(fileList, FileInfoItem{
					Name:  e.Name(),
					Path:  filepath.Join(reqPath, e.Name()),
					IsDir: e.IsDir(),
					Size:  info.Size(),
					Mtime: info.ModTime().Unix(),
				})
			}

			replyBytes, _ := json.Marshal(FileListReply{
				Type:    "list_reply",
				Success: true,
				Path:    reqPath,
				Files:   fileList,
			})
			_ = dc.SendText(string(replyBytes))

		case "mkdir":
			cleanPath, err := sanitizeFilePath(cmd.Path)
			if err != nil {
				replyBytes, _ := json.Marshal(FileSimpleReply{
					Type:    "mkdir_reply",
					Success: false,
					Error:   err.Error(),
				})
				_ = dc.SendText(string(replyBytes))
				return
			}

			err = os.MkdirAll(cleanPath, 0755)
			reply := FileSimpleReply{
				Type:    "mkdir_reply",
				Success: err == nil,
			}
			if err != nil {
				reply.Error = err.Error()
			}
			replyBytes, _ := json.Marshal(reply)
			_ = dc.SendText(string(replyBytes))

		case "delete":
			cleanPath, err := sanitizeFilePath(cmd.Path)
			if err != nil || isCriticalSystemPath(cleanPath) {
				errMsg := "deletion of critical system directory or invalid path forbidden"
				if err != nil {
					errMsg = err.Error()
				}
				replyBytes, _ := json.Marshal(FileSimpleReply{
					Type:    "delete_reply",
					Success: false,
					Error:   errMsg,
				})
				_ = dc.SendText(string(replyBytes))
				return
			}

			err = os.RemoveAll(cleanPath)
			reply := FileSimpleReply{
				Type:    "delete_reply",
				Success: err == nil,
			}
			if err != nil {
				reply.Error = err.Error()
			}
			replyBytes, _ := json.Marshal(reply)
			_ = dc.SendText(string(replyBytes))

		case "upload_start":
			cleanPath, err := sanitizeFilePath(cmd.Path)
			if err != nil || isCriticalSystemPath(cleanPath) {
				errMsg := "upload destination invalid or inside critical system directory"
				if err != nil {
					errMsg = err.Error()
				}
				replyBytes, _ := json.Marshal(FileSimpleReply{
					Type:    "upload_reply",
					Success: false,
					Error:   errMsg,
				})
				_ = dc.SendText(string(replyBytes))
				return
			}

			dir := filepath.Dir(cleanPath)
			_ = os.MkdirAll(dir, 0755)
			f, err := os.Create(cleanPath)
			if err != nil {
				replyBytes, _ := json.Marshal(FileSimpleReply{
					Type:    "upload_reply",
					Success: false,
					Error:   err.Error(),
				})
				_ = dc.SendText(string(replyBytes))
				return
			}

			currentUpload = &UploadSession{
				Path:            cleanPath,
				Size:            cmd.Size,
				Sha256:          cmd.Sha256,
				InstallOnFinish: cmd.InstallOnFinish,
				File:            f,
			}

			replyBytes, _ := json.Marshal(FileSimpleReply{
				Type:    "upload_reply",
				Success: true,
			})
			_ = dc.SendText(string(replyBytes))

		case "download_start":
			cleanPath, err := sanitizeFilePath(cmd.Path)
			if err != nil {
				replyBytes, _ := json.Marshal(FileDownloadReply{
					Type:      "download_reply",
					Success:   false,
					RequestID: cmd.RequestID,
					Error:     err.Error(),
				})
				_ = dc.SendText(string(replyBytes))
				return
			}
			go handleDownload(dc, cleanPath, cmd.RequestID)

		case "install_apk":
			cleanPath, err := sanitizeFilePath(cmd.Path)
			if err != nil {
				finalBytes, _ := json.Marshal(FileInstallStatus{
					Type:      "install_status",
					Status:    "failed",
					Message:   err.Error(),
					RequestID: cmd.RequestID,
				})
				_ = dc.SendText(string(finalBytes))
				return
			}
			go handleApkInstall(dc, cleanPath, cmd.RequestID)
		}
	})
}

func handleDownload(dc *webrtc.DataChannel, filePath string, requestID string) {
	info, err := os.Stat(filePath)
	if err != nil {
		replyBytes, _ := json.Marshal(FileDownloadReply{
			Type:      "download_reply",
			Success:   false,
			RequestID: requestID,
			Error:     err.Error(),
		})
		_ = dc.SendText(string(replyBytes))
		return
	}

	file, err := os.Open(filePath)
	if err != nil {
		replyBytes, _ := json.Marshal(FileDownloadReply{
			Type:      "download_reply",
			Success:   false,
			RequestID: requestID,
			Error:     err.Error(),
		})
		_ = dc.SendText(string(replyBytes))
		return
	}
	defer file.Close()

	// Initial reply with file size
	replyBytes, _ := json.Marshal(FileDownloadReply{
		Type:      "download_reply",
		Success:   true,
		Size:      info.Size(),
		RequestID: requestID,
	})
	_ = dc.SendText(string(replyBytes))

	// Stream file chunks with backpressure
	buf := make([]byte, 16384)
	for {
		// High-watermark check: pause disk reading if buffer exceeds 1MB
		for dc.BufferedAmount() > 1024*1024 {
			time.Sleep(10 * time.Millisecond)
		}
		n, err := file.Read(buf)
		if n > 0 {
			if sendErr := dc.Send(buf[:n]); sendErr != nil {
				log.Printf("[FileChannel] Send error during download: %v", sendErr)
				return
			}
		}
		if err != nil {
			break
		}
	}
}

func handleApkInstall(dc *webrtc.DataChannel, apkPath string, requestID string) {
	statusBytes, _ := json.Marshal(FileInstallStatus{
		Type:      "install_status",
		Status:    "installing",
		Message:   "Executing pm install...",
		RequestID: requestID,
	})
	_ = dc.SendText(string(statusBytes))

	cmd := exec.Command("pm", "install", "-r", apkPath)
	out, err := cmd.CombinedOutput()

	finalStatus := "success"
	msg := string(out)
	if err != nil {
		finalStatus = "failed"
		if msg == "" {
			msg = err.Error()
		}
	}

	finalBytes, _ := json.Marshal(FileInstallStatus{
		Type:      "install_status",
		Status:    finalStatus,
		Message:   msg,
		RequestID: requestID,
	})
	_ = dc.SendText(string(finalBytes))
}

// setupAdbChannel bridges WebRTC DataChannel to either local interactive shell (useAdb.js) or local ADB daemon (127.0.0.1:5555)
func setupAdbChannel(dc *webrtc.DataChannel, s *WebRTCSession) {
	log.Printf("[AdbChannel] Setting up ADB channel handler...")

	var isInitialized bool
	var initMu sync.Mutex
	var shellCmd *exec.Cmd
	var shellStdin io.WriteCloser
	var adbConn net.Conn

	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		if s != nil && !s.Caps.CanShell {
			log.Printf("[AdbChannel] Action rejected: session does not have CanShell permission")
			return
		}
		initMu.Lock()
		defer initMu.Unlock()

		if !isInitialized {
			isInitialized = true

			// Check if message is JSON init from useAdb.js: {"type":"init", "rows":24, "cols":80}
			var initMsg struct {
				Type string `json:"type"`
				Rows int    `json:"rows"`
				Cols int    `json:"cols"`
			}
			if err := json.Unmarshal(msg.Data, &initMsg); err == nil && initMsg.Type == "init" {
				log.Printf("[AdbChannel] Starting interactive shell for WebADB (rows=%d, cols=%d)...", initMsg.Rows, initMsg.Cols)
				var cmd *exec.Cmd
				if runtime.GOOS == "windows" {
					cmd = exec.Command("cmd.exe")
				} else {
					shPath := "/system/bin/sh"
					if _, err := os.Stat(shPath); err != nil {
						shPath = "sh"
					}
					cmd = exec.Command(shPath, "-i")
				}

				stdin, err := cmd.StdinPipe()
				if err != nil {
					log.Printf("[AdbChannel] Failed to create stdin pipe: %v", err)
					_ = dc.Close()
					return
				}
				stdout, err := cmd.StdoutPipe()
				if err != nil {
					log.Printf("[AdbChannel] Failed to create stdout pipe: %v", err)
					_ = dc.Close()
					return
				}
				stderr, err := cmd.StderrPipe()
				if err != nil {
					log.Printf("[AdbChannel] Failed to create stderr pipe: %v", err)
					_ = dc.Close()
					return
				}

				shellCmd = cmd
				shellStdin = stdin

				if err := cmd.Start(); err != nil {
					log.Printf("[AdbChannel] Failed to start shell: %v", err)
					_ = dc.Close()
					return
				}

				// Forward stdout/stderr to DataChannel
				forwardPipe := func(r io.Reader) {
					buf := make([]byte, 4096)
					for {
						n, err := r.Read(buf)
						if n > 0 {
							if sendErr := dc.Send(buf[:n]); sendErr != nil {
								return
							}
						}
						if err != nil {
							return
						}
					}
				}
				go forwardPipe(stdout)
				go forwardPipe(stderr)

				go func() {
					_ = cmd.Wait()
					_ = dc.Close()
				}()
				return
			}

			// Not a JSON init -> Treat as raw ADB protocol connecting to 127.0.0.1:5555
			conn, err := net.DialTimeout("tcp", "127.0.0.1:5555", 3*time.Second)
			if err != nil {
				log.Printf("[AdbChannel] Could not connect to local adbd (127.0.0.1:5555): %v", err)
				_ = dc.Close()
				return
			}
			adbConn = conn

			// Forward ADB -> DC
			go func() {
				defer adbConn.Close()
				defer dc.Close()
				buf := make([]byte, 16384)
				for {
					n, err := adbConn.Read(buf)
					if n > 0 {
						if sendErr := dc.Send(buf[:n]); sendErr != nil {
							return
						}
					}
					if err != nil {
						return
					}
				}
			}()

			// Send first packet to adbConn
			if len(msg.Data) > 0 {
				_, _ = adbConn.Write(msg.Data)
			}
			return
		}

		// Channel already initialized
		if shellStdin != nil {
			_, _ = shellStdin.Write(msg.Data)
		} else if adbConn != nil {
			_, _ = adbConn.Write(msg.Data)
		}
	})

	dc.OnClose(func() {
		initMu.Lock()
		defer initMu.Unlock()
		if shellStdin != nil {
			_ = shellStdin.Close()
		}
		if shellCmd != nil && shellCmd.Process != nil {
			_ = shellCmd.Process.Kill()
		}
		if adbConn != nil {
			_ = adbConn.Close()
		}
	})
}

// setupAiCommandChannel executes shell commands requested via P2P
func setupAiCommandChannel(dc *webrtc.DataChannel, sess *WebRTCSession) {
	if sess == nil {
		if dc != nil {
			_ = dc.Close()
		}
		return
	}
	sess.mu.RLock()
	canShell := sess.Caps.CanShell
	sess.mu.RUnlock()
	if !canShell {
		log.Printf("[AICommand] Rejected AI command channel: session has no shell capability (ClientID: %s)", sess.ClientID)
		_ = dc.SendText(`{"error":"Permission denied: Shell execution is forbidden for this session","exit_code":-1}`)
		_ = dc.Close()
		return
	}

	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		sess.mu.RLock()
		canShellMsg := sess.Caps.CanShell
		sess.mu.RUnlock()
		if !canShellMsg {
			log.Printf("[AICommand] Dropped command: session lacks shell capability (ClientID: %s)", sess.ClientID)
			_ = dc.SendText(`{"error":"Permission denied: Shell capability required","exit_code":126}`)
			return
		}

		var req AICommandRequest
		if err := json.Unmarshal(msg.Data, &req); err != nil {
			log.Printf("[AICommand] Invalid request JSON: %v", err)
			return
		}

		go func() {
			var cmd *exec.Cmd
			if runtime.GOOS == "windows" {
				cmd = exec.Command("cmd.exe", "/c", req.Command)
			} else {
				cmd = exec.Command("sh", "-c", req.Command)
			}
			out, err := cmd.CombinedOutput()
			exitCode := 0
			if err != nil {
				if exitErr, ok := err.(*exec.ExitError); ok {
					exitCode = exitErr.ExitCode()
				} else {
					exitCode = -1
				}
			}

			respBytes, _ := json.Marshal(AICommandResponse{
				RequestID: req.RequestID,
				Output:    string(out),
				ExitCode:  exitCode,
			})
			_ = dc.SendText(string(respBytes))
		}()
	})
}

// setupCameraChannel handles camera control commands (camera_start, camera_stop, camera_switch, camera_snapshot, camera_status)
// and accepts binary JPEG frames streamed from browser client camera
func setupCameraChannel(dc *webrtc.DataChannel, s *WebRTCSession) {
	if dc == nil {
		return
	}

	initCameraBridgeConsumer()

	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		if s != nil {
			s.mu.RLock()
			canCamera := s.Caps.CanCamera
			s.mu.RUnlock()

			if !canCamera {
				resp, _ := json.Marshal(map[string]interface{}{
					"status":  "error",
					"message": "Permission denied: session does not have CanCamera capability",
				})
				_ = dc.Send(resp)
				return
			}
		}

		// 1. Binary Frame Path: Browser client sends raw JPEG ArrayBuffer at ~30 FPS
		if len(msg.Data) >= 2 && msg.Data[0] == 0xFF && msg.Data[1] == 0xD8 {
			data := msg.Data
			latestCameraJpegMu.Lock()
			latestCameraJpeg = make([]byte, len(data))
			copy(latestCameraJpeg, data)
			latestCameraJpegMu.Unlock()

			cameraStateMu.Lock()
			cameraStreaming = true
			cameraStateMu.Unlock()

			// Distribute to internal camera socket / virtual device channel if listening
			select {
			case cameraFrameChan <- data:
			default:
				select {
				case <-cameraFrameChan:
				default:
				}
				select {
				case cameraFrameChan <- data:
				default:
				}
			}
			return
		}

		// 2. Control Command Path
		var cmd struct {
			Action    string                 `json:"action"`
			RequestID string                 `json:"request_id"`
			Params    map[string]interface{} `json:"params"`
			Lens      string                 `json:"lens"`
		}
		if err := json.Unmarshal(msg.Data, &cmd); err != nil {
			return
		}

		log.Printf("[CameraChannel] Received camera command: action=%s, req_id=%s", cmd.Action, cmd.RequestID)

		switch cmd.Action {
		case "camera_start", "start":
			cameraStateMu.Lock()
			cameraStreaming = true
			cameraStateMu.Unlock()

			// Proactively signal client over DataChannel to begin streaming camera frames
			_ = dc.SendText(`{"action":"start"}`)

			resp, _ := json.Marshal(map[string]interface{}{
				"status":     "success",
				"action":     "camera_start",
				"request_id": cmd.RequestID,
				"active":     true,
				"streaming":  true,
			})
			_ = dc.SendText(string(resp))

		case "camera_stop", "stop":
			cameraStateMu.Lock()
			cameraStreaming = false
			cameraStateMu.Unlock()

			// Signal client to stop camera capture
			_ = dc.SendText(`{"action":"stop"}`)

			resp, _ := json.Marshal(map[string]interface{}{
				"status":     "success",
				"action":     "camera_stop",
				"request_id": cmd.RequestID,
				"active":     false,
				"streaming":  false,
			})
			_ = dc.SendText(string(resp))

		case "camera_switch", "switch":
			cameraStateMu.Lock()
			lens := cmd.Lens
			if lens == "" && cmd.Params != nil && cmd.Params["lens"] != nil {
				lens = fmt.Sprintf("%v", cmd.Params["lens"])
			}
			if lens == "" {
				if cameraFacing == "environment" || cameraFacing == "back" {
					cameraFacing = "user"
					lens = "front"
				} else {
					cameraFacing = "environment"
					lens = "back"
				}
			} else {
				if lens == "front" || lens == "user" {
					cameraFacing = "user"
				} else {
					cameraFacing = "environment"
				}
			}
			facing := cameraFacing
			cameraStateMu.Unlock()

			// Notify peer of lens switch
			switchCmd, _ := json.Marshal(map[string]interface{}{
				"action": "switch",
				"lens":   lens,
				"facing": facing,
			})
			_ = dc.SendText(string(switchCmd))

			resp, _ := json.Marshal(map[string]interface{}{
				"status":     "success",
				"action":     "camera_switch",
				"request_id": cmd.RequestID,
				"lens":       lens,
				"facing":     facing,
			})
			_ = dc.SendText(string(resp))

		case "camera_status", "status":
			cameraStateMu.RLock()
			streaming := cameraStreaming
			facing := cameraFacing
			fps := cameraFps
			cameraStateMu.RUnlock()

			latestCameraJpegMu.RLock()
			hasFrame := len(latestCameraJpeg) > 0
			latestCameraJpegMu.RUnlock()

			resp, _ := json.Marshal(map[string]interface{}{
				"status":     "success",
				"action":     "camera_status",
				"request_id": cmd.RequestID,
				"supported":  true,
				"streaming":  streaming,
				"facing":     facing,
				"fps":        fps,
				"has_frame":  hasFrame,
			})
			_ = dc.SendText(string(resp))

		case "camera_snapshot", "snapshot":
			latestCameraJpegMu.RLock()
			var jpegData []byte
			if len(latestCameraJpeg) > 0 {
				jpegData = make([]byte, len(latestCameraJpeg))
				copy(jpegData, latestCameraJpeg)
			}
			latestCameraJpegMu.RUnlock()

			// Strict Parity: If no frame has been streamed yet, return no_frame_available error
			if len(jpegData) == 0 {
				resp, _ := json.Marshal(map[string]interface{}{
					"status":     "error",
					"action":     "camera_snapshot",
					"request_id": cmd.RequestID,
					"error":      "no_frame_available",
				})
				_ = dc.SendText(string(resp))
				return
			}

			imgBase64 := base64.StdEncoding.EncodeToString(jpegData)
			resp, _ := json.Marshal(map[string]interface{}{
				"status":       "success",
				"action":       "camera_snapshot",
				"request_id":   cmd.RequestID,
				"timestamp":    time.Now().UnixMilli(),
				"size":         len(jpegData),
				"mime_type":    "image/jpeg",
				"image_base64": imgBase64,
			})
			_ = dc.SendText(string(resp))

		default:
			resp, _ := json.Marshal(map[string]interface{}{
				"status":     "success",
				"action":     cmd.Action,
				"request_id": cmd.RequestID,
			})
			_ = dc.SendText(string(resp))
		}
	})
}

