package main

import (
	"crypto/rand"
	"encoding/hex"
	"sync"
	"time"

	"golang.org/x/crypto/bcrypt"
)

type AuthManager struct {
	users  map[string]*User
	tokens map[string]*User
	noAuth bool
	store  *PersistenceStore
	mu     sync.RWMutex
}

func NewAuthManager(noAuth bool, store *PersistenceStore) *AuthManager {
	mgr := &AuthManager{
		users:  make(map[string]*User),
		tokens: make(map[string]*User),
		noAuth: noAuth,
		store:  store,
	}

	// Load users from store
	if store != nil {
		for _, u := range store.GetAllUsers() {
			mgr.users[u.Username] = u
		}
	}

	// Ensure default admin user exists (admin / admin123)
	if _, exists := mgr.users["admin"]; !exists {
		hash, _ := bcrypt.GenerateFromPassword([]byte("admin123"), bcrypt.DefaultCost)
		adminUser := &User{
			ID:           "admin",
			Username:     "admin",
			PasswordHash: string(hash),
			Role:         "admin",
			CreatedAt:    time.Now(),
		}
		mgr.users["admin"] = adminUser
		if store != nil {
			store.SaveUser(adminUser)
		}
	}

	return mgr
}

func (a *AuthManager) Authenticate(username, password string) (string, *User, bool) {
	a.mu.RLock()
	user, exists := a.users[username]
	a.mu.RUnlock()

	if !exists {
		return "", nil, false
	}

	// Enforce user account expiration
	if user.ExpiresAt != "" {
		if expTime, err := time.Parse(time.RFC3339, user.ExpiresAt); err == nil {
			if time.Now().After(expTime) {
				return "", nil, false
			}
		}
	}

	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(password)); err != nil {
		return "", nil, false
	}

	token := a.generateToken()
	a.mu.Lock()
	a.tokens[token] = user
	a.mu.Unlock()

	return token, user, true
}

func (a *AuthManager) ValidateToken(token string) (*User, bool) {
	if a.noAuth {
		return &User{ID: "anonymous", Username: "anonymous", Role: "admin"}, true
	}

	if token == "" {
		return nil, false
	}

	a.mu.RLock()
	user, exists := a.tokens[token]
	a.mu.RUnlock()

	if !exists {
		return nil, false
	}

	// Enforce token validity against user expiration
	if user.ExpiresAt != "" {
		if expTime, err := time.Parse(time.RFC3339, user.ExpiresAt); err == nil {
			if time.Now().After(expTime) {
				a.InvalidateToken(token)
				return nil, false
			}
		}
	}

	return user, true
}

func (a *AuthManager) InvalidateToken(token string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	delete(a.tokens, token)
}

func (a *AuthManager) GenerateToken(username string) (string, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	user, exists := a.users[username]
	if !exists {
		user = &User{ID: username, Username: username, Role: "admin"}
		a.users[username] = user
	}
	token := a.generateToken()
	a.tokens[token] = user
	return token, nil
}

func (a *AuthManager) RegisterUser(username, password, role, note string) (*User, error) {
	a.mu.Lock()
	defer a.mu.Unlock()

	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return nil, err
	}

	user := &User{
		ID:              username,
		Username:        username,
		PasswordHash:    string(hash),
		Role:            role,
		Note:            note,
		CreatedAt:       time.Now(),
		AssignedDevices: make([]string, 0),
	}
	a.users[username] = user
	if a.store != nil {
		a.store.SaveUser(user)
	}
	return user, nil
}

func (a *AuthManager) RenameUser(oldUsername, newUsername string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()

	u, exists := a.users[oldUsername]
	if !exists {
		return false
	}

	delete(a.users, oldUsername)
	u.Username = newUsername
	u.ID = newUsername
	a.users[newUsername] = u

	if a.store != nil {
		a.store.DeleteUser(oldUsername)
		a.store.SaveUser(u)
	}
	return true
}

func (a *AuthManager) AssignDevice(username, deviceID string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()

	u, exists := a.users[username]
	if !exists {
		return false
	}
	for _, d := range u.AssignedDevices {
		if d == deviceID {
			return true
		}
	}
	u.AssignedDevices = append(u.AssignedDevices, deviceID)
	if a.store != nil {
		a.store.SaveUser(u)
	}
	return true
}

func (a *AuthManager) ListUsers() []*User {
	a.mu.RLock()
	defer a.mu.RUnlock()

	list := make([]*User, 0, len(a.users))
	for _, u := range a.users {
		// Clone without password hash
		list = append(list, &User{
			ID:              u.ID,
			Username:        u.Username,
			Role:            u.Role,
			Note:            u.Note,
			CreatedAt:       u.CreatedAt,
			AssignedDevices: u.AssignedDevices,
			ExpiresAt:       u.ExpiresAt,
		})
	}
	return list
}

func (a *AuthManager) DeleteUser(username string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	delete(a.users, username)
	if a.store != nil {
		a.store.DeleteUser(username)
	}
}

func (a *AuthManager) ResetPassword(username, newPassword string) error {
	a.mu.Lock()
	defer a.mu.Unlock()

	user, exists := a.users[username]
	if !exists {
		return nil
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(newPassword), bcrypt.DefaultCost)
	if err != nil {
		return err
	}
	user.PasswordHash = string(hash)
	if a.store != nil {
		a.store.SaveUser(user)
	}
	return nil
}

func (a *AuthManager) generateToken() string {
	b := make([]byte, 24)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
