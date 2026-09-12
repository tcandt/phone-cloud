package main

import (
	"encoding/json"
	"log"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Hub manages active devices, web clients, preview subscriptions and state
type Hub struct {
	devices     map[string]*Device
	clients     map[string]*Client
	shares      map[string]*ShareRecord
	tags        []Tag
	iceServers  []IceServerConfig
	previewSubs map[string]map[string]*Client // deviceID -> clientID -> Client
	store       *PersistenceStore
	mu          sync.RWMutex
}

func NewHub(store *PersistenceStore) *Hub {
	h := &Hub{
		devices:     make(map[string]*Device),
		clients:     make(map[string]*Client),
		shares:      make(map[string]*ShareRecord),
		tags:        make([]Tag, 0),
		previewSubs: make(map[string]map[string]*Client),
		store:       store,
		iceServers: []IceServerConfig{
			{URLs: []string{"stun:stun.l.google.com:19302"}},
		},
	}

	// Restore offline devices from store
	if store != nil {
		for _, offDev := range store.GetOfflineDevices() {
			h.devices[offDev.ID] = offDev
		}
	}

	return h
}

// RegisterAgent registers a newly connected Android Agent
func (h *Hub) RegisterAgent(id string, info *DeviceHardwareInfo, conn *websocket.Conn) *Device {
	h.mu.Lock()
	defer h.mu.Unlock()

	dev, exists := h.devices[id]
	now := time.Now()
	if !exists {
		dev = &Device{
			ID:        id,
			Name:      id,
			FirstSeen: now,
		}
		h.devices[id] = dev
	}
	dev.Status = "online"
	dev.Online = true
	dev.LastSeen = now
	dev.Conn = conn
	if info != nil {
		dev.Info = info
	}

	log.Printf("[Hub] Agent registered: %s (online)", id)
	go h.BroadcastDeviceList()
	return dev
}

// UnregisterAgent handles disconnection of an Android Agent
func (h *Hub) UnregisterAgent(id string) {
	h.mu.Lock()
	dev, exists := h.devices[id]
	if exists {
		dev.mu.Lock()
		now := time.Now()
		dev.Status = "offline"
		dev.Online = false
		dev.LastOffline = &now
		dev.Conn = nil
		dev.ActiveConnections = 0
		dev.mu.Unlock()
		if h.store != nil {
			h.store.SaveOfflineDevice(dev)
		}
		log.Printf("[Hub] Agent unregistered: %s (offline)", id)
	}
	h.mu.Unlock()

	if exists {
		go h.BroadcastDeviceList()
	}
}

// RegisterClient adds an active web browser client session
func (h *Hub) RegisterClient(c *Client) {
	h.mu.Lock()
	h.clients[c.ID] = c
	h.mu.Unlock()

	log.Printf("[Hub] Client connected: %s (Total clients: %d)", c.ID, len(h.clients))
}

// UnregisterClient removes an active web browser client session
func (h *Hub) UnregisterClient(c *Client) {
	h.mu.Lock()
	delete(h.clients, c.ID)
	// Remove from all preview subscriptions
	for devID, subs := range h.previewSubs {
		delete(subs, c.ID)
		if len(subs) == 0 {
			delete(h.previewSubs, devID)
			// Inform agent stop preview
			go h.ForwardToAgent(devID, map[string]interface{}{
				"action":    "stop_preview",
				"device_id": devID,
			})
		}
	}
	h.mu.Unlock()

	log.Printf("[Hub] Client disconnected: %s", c.ID)
}

// SubscribePreview subscribes a client to binary preview stream of a device
func (h *Hub) SubscribePreview(deviceID string, client *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()

	subs, ok := h.previewSubs[deviceID]
	if !ok {
		subs = make(map[string]*Client)
		h.previewSubs[deviceID] = subs
	}
	subs[client.ID] = client
}

// UnsubscribePreview unsubscribes a client from preview
func (h *Hub) UnsubscribePreview(deviceID string, clientID string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()

	subs, ok := h.previewSubs[deviceID]
	if !ok {
		return false
	}
	delete(subs, clientID)
	if len(subs) == 0 {
		delete(h.previewSubs, deviceID)
		return true // No more subscribers
	}
	return false
}

// BroadcastPreviewBinary forwards a binary PREV frame from an agent to all subscribed web clients
func (h *Hub) BroadcastPreviewBinary(deviceID string, data []byte) {
	h.mu.RLock()
	subs, ok := h.previewSubs[deviceID]
	if !ok || len(subs) == 0 {
		h.mu.RUnlock()
		return
	}
	clients := make([]*Client, 0, len(subs))
	for _, c := range subs {
		clients = append(clients, c)
	}
	h.mu.RUnlock()

	for _, c := range clients {
		c.mu.Lock()
		if c.Conn != nil {
			_ = c.Conn.WriteMessage(websocket.BinaryMessage, data)
		}
		c.mu.Unlock()
	}
}

// KickUser forcefully disconnects a user session from a device
func (h *Hub) KickUser(username string, deviceID string) int {
	h.mu.Lock()
	defer h.mu.Unlock()

	kickedCount := 0
	for _, c := range h.clients {
		if c.UserID == username || username == "" {
			if deviceID == "" || c.ActiveDevice == deviceID {
				c.mu.Lock()
				if c.Conn != nil {
					_ = c.Conn.WriteJSON(SignalingMessage{
						MessageType: "error",
						Error:       "Your session was terminated by an administrator",
					})
					_ = c.Conn.Close()
				}
				c.mu.Unlock()
				delete(h.clients, c.ID)
				kickedCount++
			}
		}
	}
	return kickedCount
}

// GetDevice returns a device by ID
func (h *Hub) GetDevice(id string) (*Device, bool) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	d, ok := h.devices[id]
	return d, ok
}

// GetAllDevices returns a snapshot list of all devices
func (h *Hub) GetAllDevices() []*Device {
	h.mu.RLock()
	defer h.mu.RUnlock()

	list := make([]*Device, 0, len(h.devices))
	for _, d := range h.devices {
		list = append(list, d)
	}
	return list
}

// GetPreviewSubscribers returns all active preview client subscribers for a device
func (h *Hub) GetPreviewSubscribers(deviceID string) []*Client {
	h.mu.RLock()
	defer h.mu.RUnlock()

	subs, ok := h.previewSubs[deviceID]
	if !ok {
		return nil
	}
	res := make([]*Client, 0, len(subs))
	for _, c := range subs {
		res = append(res, c)
	}
	return res
}

// BroadcastDeviceList broadcasts the active device list to all connected web clients
func (h *Hub) BroadcastDeviceList() {
	devices := h.GetAllDevices()

	msg := SignalingMessage{
		MessageType: "device_list_update",
		Type:        "device_list_update",
		Devices:     devices,
	}

	data, err := json.Marshal(msg)
	if err != nil {
		return
	}

	h.mu.RLock()
	clients := make([]*Client, 0, len(h.clients))
	for _, c := range h.clients {
		clients = append(clients, c)
	}
	h.mu.RUnlock()

	for _, c := range clients {
		c.mu.Lock()
		if c.Conn != nil {
			_ = c.Conn.WriteMessage(websocket.TextMessage, data)
		}
		c.mu.Unlock()
	}
}

// ForwardToAgent sends a signaling message to a target device Agent
func (h *Hub) ForwardToAgent(deviceID string, msg interface{}) bool {
	h.mu.RLock()
	dev, ok := h.devices[deviceID]
	h.mu.RUnlock()

	if !ok || dev == nil {
		return false
	}

	dev.mu.Lock()
	defer dev.mu.Unlock()

	if dev.Conn == nil {
		return false
	}

	data, err := json.Marshal(msg)
	if err != nil {
		return false
	}
	return dev.Conn.WriteMessage(websocket.TextMessage, data) == nil
}

// ForwardToClient sends a message to a specific web client session
func (h *Hub) ForwardToClient(clientID string, msg interface{}) bool {
	h.mu.RLock()
	c, ok := h.clients[clientID]
	h.mu.RUnlock()

	if !ok || c == nil {
		return false
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	if c.Conn == nil {
		return false
	}

	data, err := json.Marshal(msg)
	if err != nil {
		return false
	}
	return c.Conn.WriteMessage(websocket.TextMessage, data) == nil
}
