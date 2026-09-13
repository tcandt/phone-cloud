package main

import (
	"fmt"
	"log"
	"net"
	"os"
	"os/exec"
	"runtime"
	"sync"
	"time"
)

type ScrcpyProcess struct {
	config         *AgentConfig
	cmd            *exec.Cmd
	videoConn      net.Conn
	audioConn      net.Conn
	controlConn    net.Conn
	control        *ControlWriter
	currentOptions ScrcpyOptions
	mu             sync.Mutex
}

func NewScrcpyProcess(cfg *AgentConfig) *ScrcpyProcess {
	defaultOpts := ScrcpyOptions{
		VideoSource:  "display",
		MaxSize:      cfg.MaxSize,
		VideoBitRate: cfg.Bitrate,
		MaxFPS:       cfg.MaxFPS,
		StayAwake:    false,
	}
	if cfg.Audio {
		aud := true
		defaultOpts.Audio = &aud
	}
	return &ScrcpyProcess{
		config:         cfg,
		currentOptions: defaultOpts,
	}
}

func (sp *ScrcpyProcess) buildArgs(opts ScrcpyOptions, videoSock, audioSock, ctrlSock string) []string {
	maxSize := sp.config.MaxSize
	if opts.MaxSize > 0 {
		maxSize = opts.MaxSize
	}
	bitrate := sp.config.Bitrate
	if opts.VideoBitRate > 0 {
		bitrate = opts.VideoBitRate
	}
	maxFPS := sp.config.MaxFPS
	if opts.MaxFPS > 0 {
		maxFPS = opts.MaxFPS
	}
	audio := sp.config.Audio
	if opts.Audio != nil {
		audio = *opts.Audio
	}

	args := []string{
		"/",
		"com.android.helper.CoreService",
		"3.3.4-2af7ccc1",
		fmt.Sprintf("video_socket=%s", videoSock),
		fmt.Sprintf("audio_socket=%s", audioSock),
		fmt.Sprintf("control_socket=%s", ctrlSock),
		fmt.Sprintf("max_size=%d", maxSize),
		fmt.Sprintf("video_bit_rate=%d", bitrate),
		fmt.Sprintf("max_fps=%d", maxFPS),
		fmt.Sprintf("audio=%t", audio),
		"send_device_meta=true",
		"send_frame_meta=true",
		"send_codec_meta=true",
		"send_dummy_byte=false",
		"tunnel_forward=false",
	}

	if opts.VideoSource == "camera" {
		args = append(args, "video_source=camera")
		if opts.CameraFacing != "" {
			args = append(args, fmt.Sprintf("camera_facing=%s", opts.CameraFacing))
		}
		if opts.CameraID != "" {
			args = append(args, fmt.Sprintf("camera_id=%s", opts.CameraID))
		}
		if opts.CameraSize != "" {
			args = append(args, fmt.Sprintf("camera_size=%s", opts.CameraSize))
		}
		if opts.CameraFPS > 0 {
			args = append(args, fmt.Sprintf("camera_fps=%d", opts.CameraFPS))
		}
		if opts.CameraHighSpeed {
			args = append(args, "camera_high_speed=true")
		}
		if opts.CameraAr != "" {
			args = append(args, fmt.Sprintf("camera_ar=%s", opts.CameraAr))
		}
		if opts.CameraZoom > 0 {
			args = append(args, fmt.Sprintf("camera_zoom=%.2f", opts.CameraZoom))
		}
		if opts.CameraOrientation != "" && opts.CameraOrientation != "auto" {
			args = append(args, fmt.Sprintf("capture_orientation=%s", opts.CameraOrientation))
		}
	} else {
		args = append(args, "video_source=display")
	}

	// Remote hardware camera streaming requires stay_awake so device stays awake with screen off
	if opts.StayAwake || opts.VideoSource == "camera" {
		args = append(args, "stay_awake=true")
	}

	if sp.config.VideoCodecOptions != "" {
		args = append(args, fmt.Sprintf("video_codec_options=%s", sp.config.VideoCodecOptions))
	}

	return args
}

func (sp *ScrcpyProcess) NeedsRestart(opts ScrcpyOptions) bool {
	sp.mu.Lock()
	defer sp.mu.Unlock()

	// If video_source is explicitly specified and changed (e.g. display -> camera, or camera -> display)
	if opts.VideoSource != "" && opts.VideoSource != sp.currentOptions.VideoSource {
		return true
	}

	// If in camera mode, check if camera attributes changed
	if opts.VideoSource == "camera" || (opts.VideoSource == "" && sp.currentOptions.VideoSource == "camera") {
		if opts.CameraFacing != "" && opts.CameraFacing != sp.currentOptions.CameraFacing {
			return true
		}
		if opts.CameraID != "" && opts.CameraID != sp.currentOptions.CameraID {
			return true
		}
		if opts.CameraSize != "" && opts.CameraSize != sp.currentOptions.CameraSize {
			return true
		}
		if opts.CameraFPS > 0 && opts.CameraFPS != sp.currentOptions.CameraFPS {
			return true
		}
		if opts.CameraZoom > 0 && opts.CameraZoom != sp.currentOptions.CameraZoom {
			return true
		}
		if opts.CameraOrientation != "" && opts.CameraOrientation != sp.currentOptions.CameraOrientation {
			return true
		}
	}

	// If max_size changed significantly
	if opts.MaxSize > 0 && opts.MaxSize != sp.currentOptions.MaxSize {
		return true
	}

	return false
}

func (sp *ScrcpyProcess) Start() error {
	sp.mu.Lock()
	defer sp.mu.Unlock()
	return sp.startLocked(nil)
}

func (sp *ScrcpyProcess) Restart(opts ScrcpyOptions, streamer *StreamerBridge) error {
	sp.mu.Lock()
	defer sp.mu.Unlock()

	log.Printf("[Scrcpy] Reconfiguring helper: VideoSource=%s, Facing=%s, ID=%s, Size=%s, FPS=%d, Zoom=%.2f, StayAwake=%t",
		opts.VideoSource, opts.CameraFacing, opts.CameraID, opts.CameraSize, opts.CameraFPS, opts.CameraZoom, opts.StayAwake)

	if sp.cmd != nil && sp.cmd.Process != nil {
		_ = sp.cmd.Process.Kill()
		_ = sp.cmd.Wait()
		sp.cmd = nil
	}
	if sp.videoConn != nil {
		_ = sp.videoConn.Close()
		sp.videoConn = nil
	}
	if sp.audioConn != nil {
		_ = sp.audioConn.Close()
		sp.audioConn = nil
	}

	sp.currentOptions = opts
	if err := sp.startLocked(streamer); err != nil {
		return fmt.Errorf("failed to restart scrcpy: %w", err)
	}
	return nil
}

func (sp *ScrcpyProcess) startLocked(streamer *StreamerBridge) error {
	videoSock := fmt.Sprintf("cp_vid_%s", sp.config.DeviceID)
	audioSock := fmt.Sprintf("cp_aud_%s", sp.config.DeviceID)
	ctrlSock := fmt.Sprintf("cp_ctrl_%s", sp.config.DeviceID)

	var videoListener, audioListener, ctrlListener net.Listener
	var err error

	networkType := "unix"
	if runtime.GOOS == "windows" {
		networkType = "tcp"
	}

	if networkType == "unix" {
		videoListener, err = net.Listen("unix", "@"+videoSock)
		if err != nil {
			return fmt.Errorf("failed to listen video socket: %w", err)
		}
		audioListener, err = net.Listen("unix", "@"+audioSock)
		if err != nil {
			videoListener.Close()
			return fmt.Errorf("failed to listen audio socket: %w", err)
		}
		ctrlListener, err = net.Listen("unix", "@"+ctrlSock)
		if err != nil {
			videoListener.Close()
			audioListener.Close()
			return fmt.Errorf("failed to listen control socket: %w", err)
		}
	} else {
		videoListener, _ = net.Listen("tcp", "127.0.0.1:0")
		audioListener, _ = net.Listen("tcp", "127.0.0.1:0")
		ctrlListener, _ = net.Listen("tcp", "127.0.0.1:0")
	}

	jarPath := sp.config.JarPath
	if jarPath == "" {
		jarPath = "/data/local/tmp/libsys_core.so"
	}

	args := sp.buildArgs(sp.currentOptions, videoSock, audioSock, ctrlSock)

	appProcess := "/system/bin/app_process"
	if _, errStat := os.Stat(appProcess); errStat != nil {
		appProcess = "app_process"
	}

	sp.cmd = exec.Command(appProcess, args...)
	sp.cmd.Env = append(os.Environ(),
		fmt.Sprintf("CLASSPATH=%s", jarPath),
		"GODEBUG=asyncpreemptoff=1",
	)

	if sp.config.Root {
		sp.cmd.Env = append(sp.cmd.Env, "CP_AGENT_ROOT=true")
	}

	log.Printf("[Scrcpy] Launching %s with args: %v", appProcess, args)
	if err := sp.cmd.Start(); err != nil {
		log.Printf("[Scrcpy] Note: app_process start failed (running outside Android): %v", err)
	}

	acceptConn := func(listener net.Listener, name string) (net.Conn, error) {
		ch := make(chan net.Conn, 1)
		errCh := make(chan error, 1)
		go func() {
			c, err := listener.Accept()
			if err != nil {
				errCh <- err
			} else {
				ch <- c
			}
		}()
		select {
		case c := <-ch:
			log.Printf("[Scrcpy] %s socket connected", name)
			return c, nil
		case err := <-errCh:
			return nil, err
		case <-time.After(5 * time.Second):
			return nil, fmt.Errorf("timeout waiting for %s socket connection", name)
		}
	}

	go func() {
		defer videoListener.Close()
		defer audioListener.Close()
		defer ctrlListener.Close()

		vConn, err := acceptConn(videoListener, "video")
		if err == nil {
			sp.mu.Lock()
			sp.videoConn = vConn
			sp.mu.Unlock()
			if streamer != nil {
				go streamer.StreamVideo(vConn)
			}
		}

		audioEnabled := sp.config.Audio
		if sp.currentOptions.Audio != nil {
			audioEnabled = *sp.currentOptions.Audio
		}
		if audioEnabled {
			aConn, err := acceptConn(audioListener, "audio")
			if err == nil {
				sp.mu.Lock()
				sp.audioConn = aConn
				sp.mu.Unlock()
				if streamer != nil {
					go streamer.StreamAudio(aConn, streamer.previewStreamer)
				}
			}
		}

		cConn, err := acceptConn(ctrlListener, "control")
		if err == nil {
			sp.mu.Lock()
			sp.controlConn = cConn
			if sp.control == nil {
				sp.control = NewControlWriter(cConn)
			} else {
				sp.control.UpdateConn(cConn)
			}
			sp.mu.Unlock()
			if streamer != nil {
				streamer.SetControlWriter(sp.control)
				_ = sp.control.RequestKeyframe()
			}
		}
	}()

	return nil
}

func (sp *ScrcpyProcess) GetControlWriter() *ControlWriter {
	sp.mu.Lock()
	defer sp.mu.Unlock()
	return sp.control
}

func (sp *ScrcpyProcess) GetCurrentOptions() ScrcpyOptions {
	sp.mu.Lock()
	defer sp.mu.Unlock()
	return sp.currentOptions
}

func (sp *ScrcpyProcess) Close() {
	sp.mu.Lock()
	defer sp.mu.Unlock()

	if sp.control != nil {
		sp.control.Close()
	}
	if sp.videoConn != nil {
		sp.videoConn.Close()
	}
	if sp.audioConn != nil {
		sp.audioConn.Close()
	}
	if sp.cmd != nil && sp.cmd.Process != nil {
		_ = sp.cmd.Process.Kill()
	}
}
