package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
)

func getEnv(key, fallback string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return fallback
}

func main() {
	portFlag := flag.String("port", getEnv("PORT", "8443"), "Signaling server listen port")
	hostFlag := flag.String("host", getEnv("HOST", "0.0.0.0"), "Signaling server bind host")
	assetsFlag := flag.String("assets", getEnv("ASSETS", "./assets"), "Path to web frontend static assets")
	dataFlag := flag.String("data", getEnv("DATA_DIR", "./data"), "Directory for persistent user data")
	downloadsFlag := flag.String("downloads", getEnv("DOWNLOADS_DIR", "./downloads"), "Directory for downloaded recordings & files")
	certFlag := flag.String("cert", getEnv("TLS_CERT", "./certs/server.crt"), "TLS certificate file")
	keyFlag := flag.String("key", getEnv("TLS_KEY", "./certs/server.key"), "TLS private key file")
	tlsFlag := flag.Bool("tls", getEnv("USE_TLS", "true") != "false", "Enable HTTPS (TLS)")
	noAuthFlag := flag.Bool("no-auth", os.Getenv("NO_AUTH") == "true", "Disable login authentication")
	iceServersFlag := flag.String("ice_servers", getEnv("ICE_SERVERS", ""), "Comma-separated STUN/TURN ICE server URLs")
	stunServerFlag := flag.String("stun_server", "stun:stun.l.google.com:19302", "STUN server address (Deprecated, use -ice_servers)")
	debugFlag := flag.Bool("debug", os.Getenv("DEBUG") == "true", "Enable verbose debug logs")
	versionFlag := flag.Bool("version", false, "Show version information")

	flag.Parse()

	if *versionFlag {
		fmt.Println("ScrcpyOverWebRTC Signaling Server v0.3.6 (Recovered)")
		return
	}

	log.Printf("====================================================")
	log.Printf("   ScrcpyOverWebRTC Signaling Server v0.3.6 (Recovered)")
	log.Printf("====================================================")

	store := NewPersistenceStore(*dataFlag)

	hub := NewHub(store)
	if *iceServersFlag != "" {
		urls := strings.Split(*iceServersFlag, ",")
		hub.iceServers = []IceServerConfig{{URLs: urls}}
	} else if *stunServerFlag != "" {
		hub.iceServers = []IceServerConfig{{URLs: []string{*stunServerFlag}}}
	}

	auth := NewAuthManager(*noAuthFlag, store)
	apiServer := NewAPIServer(hub, auth, store, *assetsFlag, *downloadsFlag)

	mux := http.NewServeMux()
	apiServer.RegisterRoutes(mux)

	addr := fmt.Sprintf("%s:%s", *hostFlag, *portFlag)
	useTLS := false
	if *tlsFlag {
		if _, errCert := os.Stat(*certFlag); errCert == nil {
			if _, errKey := os.Stat(*keyFlag); errKey == nil {
				useTLS = true
			}
		}
	}

	server := &http.Server{
		Addr:    addr,
		Handler: mux,
	}

	go func() {
		if useTLS {
			log.Printf("[Server] Starting HTTPS & WSS server on https://%s", addr)
			if err := server.ListenAndServeTLS(*certFlag, *keyFlag); err != nil && err != http.ErrServerClosed {
				log.Fatalf("[Server] TLS Listen error: %v", err)
			}
		} else {
			log.Printf("[Server] Starting HTTP & WS server on http://%s", addr)
			if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
				log.Fatalf("[Server] Listen error: %v", err)
			}
		}
	}()

	if *debugFlag {
		log.Println("[Server] Debug logging enabled")
	}

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, os.Interrupt, syscall.SIGTERM)
	<-quit
	log.Println("[Server] Shutting down signaling server...")
}
