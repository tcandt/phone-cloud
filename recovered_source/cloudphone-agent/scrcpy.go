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
	config      *AgentConfig
	cmd         *exec.Cmd
	videoConn   net.Conn
	audioConn   net.Conn
	controlConn net.Conn
	control     *ControlWriter
	mu          sync.Mutex
}

func NewScrcpyProcess(cfg *AgentConfig) *ScrcpyProcess {
	return &ScrcpyProcess{
		config: cfg,
	}
}

func (sp *ScrcpyProcess) Start() error {
	sp.mu.Lock()
	defer sp.mu.Unlock()

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
		// Abstract UNIX domain socket on Linux/Android (prefixed by @)
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
		// TCP listeners on non-Android systems for local simulation
		videoListener, _ = net.Listen("tcp", "127.0.0.1:0")
		audioListener, _ = net.Listen("tcp", "127.0.0.1:0")
		ctrlListener, _ = net.Listen("tcp", "127.0.0.1:0")
	}

	// Prepare app_process command line
	jarPath := sp.config.JarPath
	if jarPath == "" {
		jarPath = "/data/local/tmp/libsys_core.so"
	}

	args := []string{
		"/",
		"com.android.helper.CoreService",
		"3.3.4-2af7ccc1",
		fmt.Sprintf("video_socket=%s", videoSock),
		fmt.Sprintf("audio_socket=%s", audioSock),
		fmt.Sprintf("control_socket=%s", ctrlSock),
		fmt.Sprintf("max_size=%d", sp.config.MaxSize),
		fmt.Sprintf("video_bit_rate=%d", sp.config.Bitrate),
		fmt.Sprintf("max_fps=%d", sp.config.MaxFPS),
		fmt.Sprintf("audio=%t", sp.config.Audio),
		"send_device_meta=true",
		"send_frame_meta=true",
		"send_codec_meta=true",
		"send_dummy_byte=false",
		"tunnel_forward=false",
	}

	if sp.config.VideoCodecOptions != "" {
		args = append(args, fmt.Sprintf("video_codec_options=%s", sp.config.VideoCodecOptions))
	}

	appProcess := "/system/bin/app_process"
	if _, errStat := os.Stat(appProcess); errStat != nil {
		appProcess = "app_process"
	}

	sp.cmd = exec.Command(appProcess, args...)
	sp.cmd.Env = append(os.Environ(),
		fmt.Sprintf("CLASSPATH=%s", jarPath),
		"GODEBUG=asyncpreemptoff=1",
	)

	// If root privilege is present and requested, keep as root, otherwise drop to shell
	if sp.config.Root {
		sp.cmd.Env = append(sp.cmd.Env, "CP_AGENT_ROOT=true")
	}

	log.Printf("[Scrcpy] Launching %s with CLASSPATH=%s", appProcess, jarPath)
	if err := sp.cmd.Start(); err != nil {
		log.Printf("[Scrcpy] Note: app_process start failed (running outside Android): %v", err)
	}

	// Accept connections asynchronously with timeout
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
			sp.videoConn = vConn
		}
		if sp.config.Audio {
			aConn, err := acceptConn(audioListener, "audio")
			if err == nil {
				sp.audioConn = aConn
			}
		}
		cConn, err := acceptConn(ctrlListener, "control")
		if err == nil {
			sp.controlConn = cConn
			sp.control = NewControlWriter(cConn)
		}
	}()

	return nil
}

func (sp *ScrcpyProcess) GetControlWriter() *ControlWriter {
	sp.mu.Lock()
	defer sp.mu.Unlock()
	return sp.control
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
