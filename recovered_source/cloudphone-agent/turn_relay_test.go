package main

import (
	"fmt"
	"net"
	"testing"
	"time"

	"github.com/pion/turn/v2"
	"github.com/pion/webrtc/v3"
)

// TestTURNRelay_EndToEnd proves that WebRTC PeerConnections can establish connectivity
// and transfer data strictly through a TURN relay server (ICETransportPolicyRelay).
func TestTURNRelay_EndToEnd(t *testing.T) {
	// 1. Start lightweight in-process TURN server on 127.0.0.1:0
	udpListener, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Failed to listen UDP for TURN: %v", err)
	}
	defer udpListener.Close()

	turnPort := udpListener.LocalAddr().(*net.UDPAddr).Port
	realm := "cloudphone"
	usersMap := map[string][]byte{
		"testuser": turn.GenerateAuthKey("testuser", realm, "testpass"),
	}

	server, err := turn.NewServer(turn.ServerConfig{
		Realm: realm,
		AuthHandler: func(username string, realm string, srcAddr net.Addr) ([]byte, bool) {
			if key, ok := usersMap[username]; ok {
				return key, true
			}
			return nil, false
		},
		PacketConnConfigs: []turn.PacketConnConfig{
			{
				PacketConn: udpListener,
				RelayAddressGenerator: &turn.RelayAddressGeneratorStatic{
					RelayAddress: net.ParseIP("127.0.0.1"),
					Address:      "127.0.0.1",
				},
			},
		},
	})
	if err != nil {
		t.Fatalf("Failed to create TURN server: %v", err)
	}
	defer server.Close()

	// 2. Configure two PeerConnections with ICETransportPolicyRelay
	iceServers := []webrtc.ICEServer{
		{
			URLs:           []string{fmt.Sprintf("turn:127.0.0.1:%d?transport=udp", turnPort)},
			Username:       "testuser",
			Credential:     "testpass",
			CredentialType: webrtc.ICECredentialTypePassword,
		},
	}

	cfg := webrtc.Configuration{
		ICEServers:         iceServers,
		ICETransportPolicy: webrtc.ICETransportPolicyRelay, // Enforce relay ONLY!
	}

	api := webrtc.NewAPI()
	pcA, err := api.NewPeerConnection(cfg)
	if err != nil {
		t.Fatalf("Failed to create pcA: %v", err)
	}
	defer pcA.Close()

	pcB, err := api.NewPeerConnection(cfg)
	if err != nil {
		t.Fatalf("Failed to create pcB: %v", err)
	}
	defer pcB.Close()

	// 3. Verify that all candidates gathered are ONLY relay candidates
	gatherRelayOnly := true
	pcA.OnICECandidate(func(c *webrtc.ICECandidate) {
		if c != nil {
			if c.Typ != webrtc.ICECandidateTypeRelay {
				gatherRelayOnly = false
				t.Errorf("Unexpected non-relay candidate on pcA: %s (type: %s)", c.String(), c.Typ.String())
			}
			_ = pcB.AddICECandidate(c.ToJSON())
		}
	})
	pcB.OnICECandidate(func(c *webrtc.ICECandidate) {
		if c != nil {
			if c.Typ != webrtc.ICECandidateTypeRelay {
				gatherRelayOnly = false
				t.Errorf("Unexpected non-relay candidate on pcB: %s (type: %s)", c.String(), c.Typ.String())
			}
			_ = pcA.AddICECandidate(c.ToJSON())
		}
	})

	connected := make(chan struct{})
	pcA.OnConnectionStateChange(func(s webrtc.PeerConnectionState) {
		if s == webrtc.PeerConnectionStateConnected {
			select {
			case <-connected:
			default:
				close(connected)
			}
		}
	})

	dcA, err := pcA.CreateDataChannel("relay-test", nil)
	if err != nil {
		t.Fatalf("Failed to create data channel: %v", err)
	}

	msgReceived := make(chan string, 1)
	pcB.OnDataChannel(func(dcB *webrtc.DataChannel) {
		dcB.OnMessage(func(msg webrtc.DataChannelMessage) {
			msgReceived <- string(msg.Data)
		})
	})

	dcA.OnOpen(func() {
		_ = dcA.SendText("PING_THROUGH_TURN_RELAY")
	})

	// Exchange SDP
	offer, err := pcA.CreateOffer(nil)
	if err != nil {
		t.Fatalf("CreateOffer error: %v", err)
	}
	_ = pcA.SetLocalDescription(offer)
	_ = pcB.SetRemoteDescription(offer)

	answer, err := pcB.CreateAnswer(nil)
	if err != nil {
		t.Fatalf("CreateAnswer error: %v", err)
	}
	_ = pcB.SetLocalDescription(answer)
	_ = pcA.SetRemoteDescription(answer)

	select {
	case <-connected:
		t.Logf("PeerConnections connected over TURN relay successfully")
	case <-time.After(8 * time.Second):
		t.Fatalf("Timed out waiting for TURN relay connection")
	}

	select {
	case msg := <-msgReceived:
		if msg != "PING_THROUGH_TURN_RELAY" {
			t.Errorf("Unexpected payload: %s", msg)
		} else {
			t.Logf("DataChannel message received successfully via TURN relay: %s", msg)
		}
	case <-time.After(8 * time.Second):
		t.Fatalf("Timed out waiting for DataChannel message through relay")
	}

	if !gatherRelayOnly {
		t.Errorf("Relay transport policy was violated: non-relay candidates were observed")
	}
}
