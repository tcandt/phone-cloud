package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"log"
	"net"
	"net/url"
	"strings"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

// SignalingDialer manages robust WebSocket connections to signaling servers with custom DNS & TLS SNI
type SignalingDialer struct {
	resolver         *net.Resolver
	tlsConfig        *tls.Config
	handshakeTimeout time.Duration
	ipIndex          uint32
}

// NewSignalingDialer initializes custom dual-stack Happy Eyeballs resolver and TLS configuration
func NewSignalingDialer(insecureTLS bool) *SignalingDialer {
	// Custom net.Resolver with 3-second lookup timeout
	res := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			d := net.Dialer{
				Timeout: 3 * time.Second,
			}
			return d.DialContext(ctx, network, address)
		},
	}

	return &SignalingDialer{
		resolver:         res,
		handshakeTimeout: 8 * time.Second,
		tlsConfig: &tls.Config{
			InsecureSkipVerify: insecureTLS,
			MinVersion:         tls.VersionTLS12,
		},
	}
}

// ParseSignalingURL parses and normalizes any signaling URL format (ws://, wss://, http://, https://, domain, IPv4, IPv6)
func ParseSignalingURL(raw string, deviceID string, secret string) (string, string, error) {
	raw = strings.TrimSpace(raw)
	if !strings.Contains(raw, "://") {
		raw = "ws://" + raw
	}

	u, err := url.Parse(raw)
	if err != nil {
		return "", "", fmt.Errorf("invalid signaling URL %q: %w", raw, err)
	}

	wsScheme := "ws"
	if u.Scheme == "https" || u.Scheme == "wss" {
		wsScheme = "wss"
	}

	host := u.Host
	if host == "" {
		host = "127.0.0.1:8443"
	}

	// Extract hostname for TLS SNI
	sniHostname := host
	if h, _, err := net.SplitHostPort(host); err == nil {
		sniHostname = h
	}
	sniHostname = strings.TrimPrefix(strings.TrimSuffix(sniHostname, "]"), "[")

	// Build clean registration query
	q := url.Values{}
	q.Set("id", deviceID)
	if secret != "" {
		q.Set("secret", secret)
		q.Set("token", secret)
	}

	path := u.Path
	if path == "" || path == "/" {
		path = "/register_agent"
	} else if !strings.HasSuffix(path, "/register_agent") {
		path = strings.TrimSuffix(path, "/") + "/register_agent"
	}

	finalURL := (&url.URL{
		Scheme:   wsScheme,
		Host:     host,
		Path:     path,
		RawQuery: q.Encode(),
	}).String()

	return finalURL, sniHostname, nil
}

// Dial connects to the signaling server using round-robin DNS candidate rotation and proper TLS SNI
func (sd *SignalingDialer) Dial(targetURL string, sniHostname string) (*websocket.Conn, error) {
	u, err := url.Parse(targetURL)
	if err != nil {
		return nil, fmt.Errorf("invalid target URL %q: %w", targetURL, err)
	}

	host := u.Hostname()
	port := u.Port()
	if port == "" {
		if u.Scheme == "wss" || u.Scheme == "https" {
			port = "443"
		} else {
			port = "80"
		}
	}

	// 1. Resolve all A and AAAA addresses with custom dual-stack resolver
	var targetCandidates []string
	if net.ParseIP(host) != nil {
		// Literal IP provided
		targetCandidates = append(targetCandidates, net.JoinHostPort(host, port))
	} else {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		ips, resolveErr := sd.resolver.LookupIPAddr(ctx, host)
		cancel()

		if resolveErr == nil && len(ips) > 0 {
			// Atomic round-robin index rotation across reconnect attempts
			startIdx := int(atomic.AddUint32(&sd.ipIndex, 1)) % len(ips)
			for i := 0; i < len(ips); i++ {
				idx := (startIdx + i) % len(ips)
				targetCandidates = append(targetCandidates, net.JoinHostPort(ips[idx].IP.String(), port))
			}
		} else {
			targetCandidates = append(targetCandidates, net.JoinHostPort(host, port))
		}
	}

	// 2. Iterate candidates with round-robin failover
	var lastErr error
	for _, candidateAddr := range targetCandidates {
		netDialer := &net.Dialer{
			Timeout:   5 * time.Second,
			KeepAlive: 30 * time.Second,
			Resolver:  sd.resolver,
		}

		tlsConf := sd.tlsConfig.Clone()
		if sniHostname != "" && net.ParseIP(sniHostname) == nil {
			tlsConf.ServerName = sniHostname
		}

		dialer := websocket.Dialer{
			NetDialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return netDialer.DialContext(ctx, network, candidateAddr)
			},
			TLSClientConfig:  tlsConf,
			HandshakeTimeout: sd.handshakeTimeout,
			Subprotocols:     nil,
		}

		conn, resp, err := dialer.Dial(targetURL, nil)
		if err == nil {
			log.Printf("[SignalingDialer] Connected to %s via %s (SNI: %s)", targetURL, candidateAddr, sniHostname)
			return conn, nil
		}

		lastErr = err
		if resp != nil {
			log.Printf("[SignalingDialer] Candidate %s rejected (status %d): %v", candidateAddr, resp.StatusCode, err)
		} else {
			log.Printf("[SignalingDialer] Candidate %s failed: %v, attempting next...", candidateAddr, err)
		}
	}

	return nil, fmt.Errorf("all resolved candidates failed, last error: %w", lastErr)
}

// BackoffTracker manages exponential reconnect backoff with reset
type BackoffTracker struct {
	current    time.Duration
	initial    time.Duration
	max        time.Duration
	factor     float64
	stableTime time.Duration
	connectAt  time.Time
}

func NewBackoffTracker() *BackoffTracker {
	return &BackoffTracker{
		current:    1 * time.Second,
		initial:    1 * time.Second,
		max:        15 * time.Second,
		factor:     2.0,
		stableTime: 20 * time.Second,
	}
}

func (b *BackoffTracker) OnConnected() {
	b.connectAt = time.Now()
}

func (b *BackoffTracker) NextWait() time.Duration {
	if !b.connectAt.IsZero() && time.Since(b.connectAt) >= b.stableTime {
		// Connection was stable for over 20 seconds, reset backoff to initial
		b.current = b.initial
	}

	wait := b.current
	next := time.Duration(float64(b.current) * b.factor)
	if next > b.max {
		next = b.max
	}
	b.current = next
	b.connectAt = time.Time{}
	return wait
}
