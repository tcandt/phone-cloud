package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/url"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"

	"github.com/gorilla/websocket"
	"github.com/pion/webrtc/v3"
)

func getEnvStr(key, fallback string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return fallback
}

func getEnvInt(key string, fallback int) int {
	if val := os.Getenv(key); val != "" {
		if i, err := strconv.Atoi(val); err == nil {
			return i
		}
	}
	return fallback
}

func getEnvBool(key string, fallback bool) bool {
	if val := os.Getenv(key); val != "" {
		return val == "true" || val == "1"
	}
	return fallback
}

func getAndroidProp(prop string) string {
	out, err := exec.Command("getprop", prop).Output()
	if err == nil {
		return strings.TrimSpace(string(out))
	}
	return "unknown"
}

func main() {
	cfg := &AgentConfig{
		SignalingURL:      getEnvStr("CP_AGENT_SIGNALING", "ws://127.0.0.1:8443"),
		DeviceID:          getEnvStr("CP_AGENT_ID", "android-local"),
		JarPath:           getEnvStr("CP_AGENT_JAR", "/data/local/tmp/libsys_core.so"),
		ExternalAddr:      getEnvStr("CP_AGENT_EXTERNAL_ADDR", ""),
		WebRTCPort:        getEnvInt("CP_AGENT_WEBRTC_PORT", 0),
		MaxSize:           getEnvInt("CP_AGENT_MAX_SIZE", 1080),
		Bitrate:           getEnvInt("CP_AGENT_BITRATE", 8000000),
		MaxFPS:            getEnvInt("CP_AGENT_MAX_FPS", 60),
		VideoCodecOptions: getEnvStr("CP_AGENT_VIDEO_CODEC_OPTIONS", ""),
		SnapshotInterval:  getEnvInt("CP_AGENT_SNAPSHOT_INTERVAL", 5),
		Root:              getEnvBool("CP_AGENT_ROOT", false),
		Audio:             getEnvBool("CP_AGENT_AUDIO", true),
		IceServers:        getEnvStr("CP_AGENT_ICE_SERVERS", ""),
		Debug:             getEnvBool("CP_AGENT_DEBUG", false),
		CameraAddr:        getEnvStr("CP_AGENT_CAMERA_ADDR", ""),
		ForceCamera:       getEnvBool("CP_AGENT_FORCE_CAMERA", false),
	}

	flag.StringVar(&cfg.SignalingURL, "signaling", cfg.SignalingURL, "Signaling server URL")
	flag.StringVar(&cfg.DeviceID, "id", cfg.DeviceID, "Device unique ID")
	flag.StringVar(&cfg.JarPath, "jar", cfg.JarPath, "Path to libsys_core.so (scrcpy-server JAR)")
	flag.IntVar(&cfg.MaxSize, "max-size", cfg.MaxSize, "Maximum video resolution limit")
	flag.IntVar(&cfg.Bitrate, "bitrate", cfg.Bitrate, "Video streaming bitrate in bps")
	flag.IntVar(&cfg.MaxFPS, "max-fps", cfg.MaxFPS, "Maximum video frame rate")
	flag.BoolVar(&cfg.Audio, "audio", cfg.Audio, "Enable audio forwarding")
	flag.BoolVar(&cfg.Root, "root", cfg.Root, "Run with root/privileged access")
	flag.Parse()

	log.Printf("====================================================")
	log.Printf("   ScrcpyOverWebRTC Cloudphone Agent v0.3.6 (Recovered)")
	log.Printf("   Device ID: %s", cfg.DeviceID)
	log.Printf("   Signaling: %s", cfg.SignalingURL)
	log.Printf("====================================================")

	// 1. Launch scrcpy-server helper process
	scrcpy := NewScrcpyProcess(cfg)
	if err := scrcpy.Start(); err != nil {
		log.Printf("[Agent] Warning starting scrcpy: %v", err)
	}
	defer scrcpy.Close()

	var activeSession *WebRTCSession
	var streamer *StreamerBridge
	previewStreamer := NewPreviewStreamer(cfg.DeviceID)

	// 2. Persistent Signaling Loop
	for {
		log.Printf("[Agent] Connecting to signaling server: %s", cfg.SignalingURL)
		u, err := url.Parse(cfg.SignalingURL)
		if err != nil {
			log.Printf("[Agent] Invalid signaling URL: %v", err)
			time.Sleep(3 * time.Second)
			continue
		}

		// Connect to /register_agent
		wsScheme := "ws"
		if u.Scheme == "https" || u.Scheme == "wss" {
			wsScheme = "wss"
		}
		agentEndpoint := fmt.Sprintf("%s://%s/register_agent?id=%s", wsScheme, u.Host, url.QueryEscape(cfg.DeviceID))

		ws, _, err := websocket.DefaultDialer.Dial(agentEndpoint, nil)
		if err != nil {
			log.Printf("[Agent] Connection failed: %v, retrying in 3s...", err)
			time.Sleep(3 * time.Second)
			continue
		}

		log.Printf("[Agent] Successfully connected to signaling server")
		previewStreamer.SetWebSocket(ws)

		// Report device hardware metadata
		hwInfo := map[string]interface{}{
			"android_model":   getAndroidProp("ro.product.model"),
			"android_serial":  getAndroidProp("ro.serialno"),
			"android_version": getAndroidProp("ro.build.version.release"),
			"app_version":     "0.3.6",
			"displays": []map[string]interface{}{
				{"id": 0, "x_res": 1080, "y_res": 1920},
			},
		}

		_ = ws.WriteJSON(map[string]interface{}{
			"action": "register",
			"info":   hwInfo,
		})

		// Message handling loop
		for {
			_, raw, err := ws.ReadMessage()
			if err != nil {
				log.Printf("[Agent] WebSocket read error: %v", err)
				break
			}

			var msg map[string]interface{}
			if err := json.Unmarshal(raw, &msg); err != nil {
				continue
			}

			action, _ := msg["action"].(string)
			msgType, _ := msg["type"].(string)
			clientID, _ := msg["client_id"].(string)

			// WebRTC Signaling negotiation routed from browser client
			if msgType == "client_msg" {
				payload, _ := msg["payload"].(map[string]interface{})
				pType, _ := payload["type"].(string)

				switch pType {
				case "request-offer":
					log.Printf("[Agent] Received request-offer from client: %s", clientID)
					if activeSession != nil {
						activeSession.Close()
					}
					if streamer != nil {
						streamer.Stop()
					}

					ctrl := scrcpy.GetControlWriter()
					session, err := NewWebRTCSession(ctrl)
					if err != nil {
						log.Printf("[Agent] Failed to create WebRTC session: %v", err)
						continue
					}
					activeSession = session

					// Start streaming tracks
					if scrcpy.videoConn != nil {
						streamer = NewStreamerBridge(session.videoTrack, session.audioTrack, ctrl, cfg.MaxFPS)
						go streamer.StreamVideo(scrcpy.videoConn)
						if scrcpy.audioConn != nil {
							go streamer.StreamAudio(scrcpy.audioConn)
						}
					}

					sdpOffer, err := session.CreateOffer()
					if err != nil {
						log.Printf("[Agent] CreateOffer failed: %v", err)
						continue
					}

					_ = ws.WriteJSON(map[string]interface{}{
						"type":           "offer",
						"client_id":      clientID,
						"sdp":            sdpOffer,
						"camera_support": true,
					})

				case "answer":
					if activeSession != nil {
						sdp, _ := payload["sdp"].(string)
						_ = activeSession.SetAnswer(sdp)
						log.Printf("[Agent] Answer applied successfully")
					}

				case "ice-candidate":
					if activeSession != nil {
						candMap, _ := payload["candidate"].(map[string]interface{})
						candStr, _ := candMap["candidate"].(string)
						var sdpMid *string
						if mid, ok := candMap["sdpMid"].(string); ok {
							sdpMid = &mid
						}
						var mlineIndex *uint16
						if idx, ok := candMap["sdpMLineIndex"].(float64); ok {
							u16 := uint16(idx)
							mlineIndex = &u16
						}
						_ = activeSession.AddIceCandidate(webrtc.ICECandidateInit{
							Candidate:     candStr,
							SDPMid:        sdpMid,
							SDPMLineIndex: mlineIndex,
						})
					}
				}
			} else if action == "command" {
				cmdStr, _ := msg["command"].(string)
				reqID, _ := msg["request_id"].(string)
				out, err := exec.Command("sh", "-c", cmdStr).CombinedOutput()
				outStr := string(out)
				if err != nil && outStr == "" {
					outStr = err.Error()
				}
				_ = ws.WriteJSON(map[string]interface{}{
					"action":     "command_result",
					"request_id": reqID,
					"client_id":  clientID,
					"output":     outStr,
				})
			} else if action == "start_preview" || msgType == "start_preview" {
				fps, _ := msg["fps"].(float64)
				maxSize, _ := msg["max_size"].(float64)
				bitrate, _ := msg["bitrate"].(float64)
				previewStreamer.Start(int(fps), int(maxSize), int(bitrate))

				if streamer == nil && scrcpy.videoConn != nil {
					ctrl := scrcpy.GetControlWriter()
					streamer = NewStreamerBridge(nil, nil, ctrl, cfg.MaxFPS)
					streamer.SetPreviewStreamer(previewStreamer)
					go streamer.StreamVideo(scrcpy.videoConn)
				}
				if ctrl := scrcpy.GetControlWriter(); ctrl != nil {
					_ = ctrl.RequestKeyframe()
				}
				log.Printf("[Agent] Preview stream active (fps=%v, maxSize=%v, bitrate=%v)", fps, maxSize, bitrate)

			} else if action == "stop_preview" || msgType == "stop_preview" {
				previewStreamer.Stop()
				log.Printf("[Agent] Preview stream stopped")

			} else if action == "group_control_event" || msgType == "group_control_event" {
				ctrl := scrcpy.GetControlWriter()
				if ctrl != nil {
					payload, _ := msg["payload"].(map[string]interface{})
					if payload == nil {
						payload, _ = msg["event"].(map[string]interface{})
					}
					eType, _ := payload["type"].(string)
					switch eType {
					case "touch":
						act, _ := payload["action"].(float64)
						id, _ := payload["id"].(float64)
						x, _ := payload["x"].(float64)
						y, _ := payload["y"].(float64)
						w, _ := payload["w"].(float64)
						h, _ := payload["h"].(float64)
						_ = ctrl.SendTouch(byte(act), int64(id), int(x), int(y), int(w), int(h), 1.0)
					case "inject_keycode":
						act, _ := payload["action"].(float64)
						key, _ := payload["keycode"].(float64)
						rep, _ := payload["repeat"].(float64)
						meta, _ := payload["meta"].(float64)
						_ = ctrl.SendKeycode(byte(act), int(key), int(rep), int(meta))
					case "inject_text":
						txt, _ := payload["text"].(string)
						_ = ctrl.SendText(txt)
					case "scroll":
						x, _ := payload["x"].(float64)
						y, _ := payload["y"].(float64)
						w, _ := payload["w"].(float64)
						h, _ := payload["h"].(float64)
						sH, _ := payload["scrollH"].(float64)
						sV, _ := payload["scrollV"].(float64)
						_ = ctrl.SendScroll(int(x), int(y), int(w), int(h), float32(sH), float32(sV))
					}
				}
			} else if action == "inject_data" || msgType == "inject_data" {
				channel, _ := msg["channel"].(string)
				payload := msg["payload"]
				ctrl := scrcpy.GetControlWriter()
				if ctrl != nil && channel == "clipboard-channel" {
					if pMap, ok := payload.(map[string]interface{}); ok {
						txt, _ := pMap["text"].(string)
						paste, _ := pMap["paste"].(bool)
						_ = ctrl.SetClipboard(txt, paste)
					} else if txt, ok := payload.(string); ok {
						_ = ctrl.SetClipboard(txt, false)
					}
				}
			}
		}

		ws.Close()
		time.Sleep(2 * time.Second)
	}
}
