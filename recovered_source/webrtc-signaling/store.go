package main

import (
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type StoreData struct {
	Users          map[string]*User          `json:"users"`
	Shares         map[string]*ShareRecord   `json:"shares"`
	Tags           []Tag                     `json:"tags"`
	DeviceTags     map[string][]string       `json:"device_tags"`
	OfflineDevices map[string]*Device        `json:"offline_devices"`
	Tasks          map[string]*BatchTask     `json:"tasks"`
	AIConfigs      map[string]*AIConfig      `json:"ai_configs"`
	UserPolicies   map[string]*UserPolicy    `json:"user_policies"`
}

type PersistenceStore struct {
	filePath string
	data     StoreData
	mu       sync.RWMutex
}

func NewPersistenceStore(dataDir string) *PersistenceStore {
	_ = os.MkdirAll(dataDir, 0755)
	store := &PersistenceStore{
		filePath: filepath.Join(dataDir, "state.json"),
		data: StoreData{
			Users:          make(map[string]*User),
			Shares:         make(map[string]*ShareRecord),
			Tags:           make([]Tag, 0),
			DeviceTags:     make(map[string][]string),
			OfflineDevices: make(map[string]*Device),
			Tasks:          make(map[string]*BatchTask),
			AIConfigs:      make(map[string]*AIConfig),
			UserPolicies:   make(map[string]*UserPolicy),
		},
	}
	store.Load()
	return store
}

func (s *PersistenceStore) Load() {
	s.mu.Lock()
	defer s.mu.Unlock()

	loaded := false
	bakFile := s.filePath + ".bak"

	// Helper to attempt loading from a given path
	loadPath := func(path string) bool {
		f, err := os.Open(path)
		if err != nil {
			return false
		}
		defer f.Close()

		if err := json.NewDecoder(f).Decode(&s.data); err != nil {
			log.Printf("[Store] Warning decoding %s: %v", path, err)
			return false
		}
		return true
	}

	if loadPath(s.filePath) {
		loaded = true
	} else if loadPath(bakFile) {
		log.Printf("[Store] Primary state missing or corrupted, successfully recovered state from %s", bakFile)
		loaded = true
	}

	if !loaded {
		log.Printf("[Store] Initializing fresh state store at %s", s.filePath)
	}

	if s.data.Users == nil {
		s.data.Users = make(map[string]*User)
	}
	if s.data.Shares == nil {
		s.data.Shares = make(map[string]*ShareRecord)
	}
	if s.data.DeviceTags == nil {
		s.data.DeviceTags = make(map[string][]string)
	}
	if s.data.OfflineDevices == nil {
		s.data.OfflineDevices = make(map[string]*Device)
	}
	if s.data.Tasks == nil {
		s.data.Tasks = make(map[string]*BatchTask)
	}
	if s.data.AIConfigs == nil {
		s.data.AIConfigs = make(map[string]*AIConfig)
	}
	if s.data.UserPolicies == nil {
		s.data.UserPolicies = make(map[string]*UserPolicy)
	}

	log.Printf("[Store] Restored %d users, %d shares, %d offline devices from disk",
		len(s.data.Users), len(s.data.Shares), len(s.data.OfflineDevices))
}

func (s *PersistenceStore) Save() {
	s.mu.RLock()
	defer s.mu.RUnlock()

	_ = os.MkdirAll(filepath.Dir(s.filePath), 0755)
	tmpFile := s.filePath + ".tmp"
	bakFile := s.filePath + ".bak"

	f, err := os.Create(tmpFile)
	if err != nil {
		log.Printf("[Store] Error creating state tmp file: %v", err)
		return
	}

	encoder := json.NewEncoder(f)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(&s.data); err != nil {
		f.Close()
		_ = os.Remove(tmpFile)
		log.Printf("[Store] Error encoding state: %v", err)
		return
	}

	// Flush OS disk buffers to ensure persistence durability
	_ = f.Sync()
	_ = f.Close()

	// Rotate existing file to .bak before replacing with .tmp
	if _, err := os.Stat(s.filePath); err == nil {
		_ = os.Remove(bakFile)
		if err := os.Rename(s.filePath, bakFile); err != nil {
			log.Printf("[Store] Warning rotating to .bak: %v", err)
		}
	}

	// Rename tmp to primary state file
	if err := os.Rename(tmpFile, s.filePath); err != nil {
		log.Printf("[Store] Error replacing state file: %v (restoring backup)", err)
		_ = os.Rename(bakFile, s.filePath)
		return
	}
}

func (s *PersistenceStore) SaveUser(u *User) {
	s.mu.Lock()
	s.data.Users[u.Username] = u
	s.mu.Unlock()
	s.Save()
}

func (s *PersistenceStore) DeleteUser(username string) {
	s.mu.Lock()
	delete(s.data.Users, username)
	delete(s.data.UserPolicies, username)
	delete(s.data.AIConfigs, username)
	s.mu.Unlock()
	s.Save()
}

func (s *PersistenceStore) GetUser(username string) *User {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.data.Users[username]
}

func (s *PersistenceStore) GetAllUsers() []*User {
	s.mu.RLock()
	defer s.mu.RUnlock()
	users := make([]*User, 0, len(s.data.Users))
	for _, u := range s.data.Users {
		users = append(users, u)
	}
	return users
}

func (s *PersistenceStore) SaveShare(sr *ShareRecord) {
	s.mu.Lock()
	s.data.Shares[sr.Token] = sr
	s.mu.Unlock()
	s.Save()
}

func (s *PersistenceStore) DeleteShare(token string) {
	s.mu.Lock()
	delete(s.data.Shares, token)
	s.mu.Unlock()
	s.Save()
}

func (s *PersistenceStore) GetShare(token string) *ShareRecord {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.data.Shares[token]
}

func (s *PersistenceStore) GetAllShares() []*ShareRecord {
	s.mu.RLock()
	defer s.mu.RUnlock()
	shares := make([]*ShareRecord, 0, len(s.data.Shares))
	for _, sr := range s.data.Shares {
		shares = append(shares, sr)
	}
	return shares
}

func (s *PersistenceStore) SaveTask(task *BatchTask) {
	s.mu.Lock()
	s.data.Tasks[task.TaskID] = task
	s.mu.Unlock()
	s.Save()
}

func (s *PersistenceStore) GetTask(taskID string) *BatchTask {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.data.Tasks[taskID]
}

func (s *PersistenceStore) SaveAIConfig(username string, cfg *AIConfig) {
	s.mu.Lock()
	s.data.AIConfigs[username] = cfg
	s.mu.Unlock()
	s.Save()
}

func (s *PersistenceStore) GetAIConfig(username string) *AIConfig {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.data.AIConfigs[username]
}

func (s *PersistenceStore) SaveUserPolicy(username string, policy *UserPolicy) {
	s.mu.Lock()
	s.data.UserPolicies[username] = policy
	s.mu.Unlock()
	s.Save()
}

func (s *PersistenceStore) GetUserPolicy(username string) *UserPolicy {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.data.UserPolicies[username]
}

func (s *PersistenceStore) SaveOfflineDevice(d *Device) {
	s.mu.Lock()
	now := time.Now()
	d.LastOffline = &now
	d.Online = false
	d.Status = "offline"
	s.data.OfflineDevices[d.ID] = d
	s.mu.Unlock()
	s.Save()
}

func (s *PersistenceStore) RemoveOfflineDevice(deviceID string) bool {
	s.mu.Lock()
	deleted := false
	if _, exists := s.data.OfflineDevices[deviceID]; exists {
		delete(s.data.OfflineDevices, deviceID)
		deleted = true
	}
	s.mu.Unlock()

	if deleted {
		s.Save()
	}
	return deleted
}

func (s *PersistenceStore) GetOfflineDevices() []*Device {
	s.mu.RLock()
	defer s.mu.RUnlock()
	list := make([]*Device, 0, len(s.data.OfflineDevices))
	for _, d := range s.data.OfflineDevices {
		list = append(list, d)
	}
	return list
}
