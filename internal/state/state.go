package state

import (
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/multicloud/cli/internal/common"
)

type StateVersion struct {
	Version   int       `json:"version"`
	Timestamp time.Time `json:"timestamp"`
	MD5Sum    string    `json:"md5sum"`
	Message   string    `json:"message,omitempty"`
}

type State struct {
	Version   int                         `json:"version"`
	Serial    int                         `json:"serial"`
	Lineage   string                      `json:"lineage"`
	Resources map[string]*common.Resource `json:"resources"`
	Outputs   map[string]interface{}      `json:"outputs,omitempty"`
	Metadata  map[string]interface{}      `json:"metadata,omitempty"`
	CreatedAt time.Time                   `json:"created_at"`
	UpdatedAt time.Time                   `json:"updated_at"`
	LockInfo  *StateLock                  `json:"lock_info,omitempty"`
	Versions  []StateVersion              `json:"versions,omitempty"`
}

type StateLock struct {
	ID        string    `json:"id"`
	Operation string    `json:"operation"`
	Who       string    `json:"who"`
	Version   string    `json:"version"`
	CreatedAt time.Time `json:"created_at"`
	Path      string    `json:"path,omitempty"`
	ExpiresAt time.Time `json:"expires_at,omitempty"`
}

type StateBackend interface {
	Load() (*State, error)
	Save(state *State) error
	Lock(lock *StateLock) error
	Unlock(id string) error
	Exists() bool
	Path() string
}

type StateManager struct {
	backend StateBackend
	mu      sync.RWMutex
	state   *State
}

func NewState() *State {
	return &State{
		Version:   1,
		Serial:    0,
		Lineage:   common.GenerateID("state"),
		Resources: make(map[string]*common.Resource),
		Outputs:   make(map[string]interface{}),
		Metadata:  make(map[string]interface{}),
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
}

func (s *State) Hash() string {
	data, err := json.Marshal(s)
	if err != nil {
		return ""
	}
	hash := md5.Sum(data)
	return hex.EncodeToString(hash[:])
}

func (s *State) GetResource(name string) (*common.Resource, bool) {
	r, exists := s.Resources[name]
	return r, exists
}

func (s *State) SetResource(name string, resource *common.Resource) {
	if s.Resources == nil {
		s.Resources = make(map[string]*common.Resource)
	}
	s.Resources[name] = resource
}

func (s *State) RemoveResource(name string) {
	delete(s.Resources, name)
}

func (s *State) GetResourcesMap() map[string]*common.Resource {
	return s.Resources
}

func (s *State) IncrementSerial(message string) {
	s.Serial++
	s.UpdatedAt = time.Now()
	s.Versions = append(s.Versions, StateVersion{
		Version:   s.Serial,
		Timestamp: time.Now(),
		MD5Sum:    s.Hash(),
		Message:   message,
	})
}

func NewStateManager(backend StateBackend) *StateManager {
	return &StateManager{
		backend: backend,
	}
}

func (sm *StateManager) Load() (*State, error) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	if !sm.backend.Exists() {
		sm.state = NewState()
		return sm.state, nil
	}

	state, err := sm.backend.Load()
	if err != nil {
		return nil, err
	}

	sm.state = state
	return state, nil
}

func (sm *StateManager) Save(message string) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	if sm.state == nil {
		sm.state = NewState()
	}

	sm.state.IncrementSerial(message)

	if err := sm.backend.Save(sm.state); err != nil {
		sm.state.Serial--
		return err
	}

	return nil
}

func (sm *StateManager) Lock(operation string) (*StateLock, error) {
	who, _ := os.Hostname()
	lock := &StateLock{
		ID:        common.GenerateID("lock"),
		Operation: operation,
		Who:       who,
		Version:   "1.0.0",
		CreatedAt: time.Now(),
		ExpiresAt: time.Now().Add(1 * time.Hour),
	}

	if err := sm.backend.Lock(lock); err != nil {
		return nil, err
	}

	sm.mu.Lock()
	if sm.state != nil {
		sm.state.LockInfo = lock
	}
	sm.mu.Unlock()

	return lock, nil
}

func (sm *StateManager) Unlock(id string) error {
	if err := sm.backend.Unlock(id); err != nil {
		return err
	}

	sm.mu.Lock()
	if sm.state != nil {
		sm.state.LockInfo = nil
	}
	sm.mu.Unlock()

	return nil
}

func (sm *StateManager) GetState() *State {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return sm.state
}

func (sm *StateManager) GetResource(name string) (*common.Resource, bool) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	if sm.state == nil {
		return nil, false
	}
	return sm.state.GetResource(name)
}

func (sm *StateManager) SetResource(name string, resource *common.Resource) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	if sm.state == nil {
		sm.state = NewState()
	}
	sm.state.SetResource(name, resource)
}

func (sm *StateManager) RemoveResource(name string) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	if sm.state != nil {
		sm.state.RemoveResource(name)
	}
}

func NewLocalBackend(path string) (*LocalBackend, error) {
	absPath, err := filepath.Abs(path)
	if err != nil {
		return nil, common.NewError(common.ErrOperationFailed, "failed to resolve state path", err)
	}

	return &LocalBackend{
		path:     absPath,
		lockPath: absPath + ".lock",
	}, nil
}

type LocalBackend struct {
	path     string
	lockPath string
}

func (b *LocalBackend) Path() string {
	return b.path
}

func (b *LocalBackend) Exists() bool {
	_, err := os.Stat(b.path)
	return !os.IsNotExist(err)
}

func (b *LocalBackend) Load() (*State, error) {
	data, err := os.ReadFile(b.path)
	if err != nil {
		if os.IsNotExist(err) {
			return NewState(), nil
		}
		return nil, common.NewError(common.ErrStateCorrupted, "failed to read state file", err)
	}

	var state State
	if err := json.Unmarshal(data, &state); err != nil {
		return nil, common.NewError(common.ErrStateCorrupted, "failed to parse state file", err)
	}

	return &state, nil
}

func (b *LocalBackend) Save(state *State) error {
	if err := common.EnsureDir(b.path); err != nil {
		return err
	}

	data, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return common.NewError(common.ErrOperationFailed, "failed to marshal state", err)
	}

	tmpPath := b.path + ".tmp"
	if err := os.WriteFile(tmpPath, data, 0644); err != nil {
		return common.NewError(common.ErrOperationFailed, "failed to write temp state file", err)
	}

	if err := os.Rename(tmpPath, b.path); err != nil {
		os.Remove(tmpPath)
		return common.NewError(common.ErrOperationFailed, "failed to replace state file", err)
	}

	return nil
}

func (b *LocalBackend) Lock(lock *StateLock) error {
	if _, err := os.Stat(b.lockPath); err == nil {
		data, err := os.ReadFile(b.lockPath)
		if err == nil {
			var existingLock StateLock
			if json.Unmarshal(data, &existingLock) == nil {
				if existingLock.ExpiresAt.After(time.Now()) {
					return common.NewError(common.ErrStateLocked,
						fmt.Sprintf("state is locked by %s (operation: %s, expires: %s)",
							existingLock.Who, existingLock.Operation, existingLock.ExpiresAt.Format(time.RFC3339)))
				}
			}
		}
	}

	data, err := json.Marshal(lock)
	if err != nil {
		return common.NewError(common.ErrOperationFailed, "failed to marshal lock", err)
	}

	if err := os.WriteFile(b.lockPath, data, 0644); err != nil {
		return common.NewError(common.ErrStateLocked, "failed to acquire state lock", err)
	}

	return nil
}

func (b *LocalBackend) Unlock(id string) error {
	if _, err := os.Stat(b.lockPath); os.IsNotExist(err) {
		return nil
	}

	data, err := os.ReadFile(b.lockPath)
	if err != nil {
		return common.NewError(common.ErrOperationFailed, "failed to read lock file", err)
	}

	var lock StateLock
	if err := json.Unmarshal(data, &lock); err != nil {
		return common.NewError(common.ErrStateCorrupted, "failed to parse lock file", err)
	}

	if lock.ID != id {
		return common.NewError(common.ErrStateLocked, "invalid lock ID")
	}

	if err := os.Remove(b.lockPath); err != nil {
		return common.NewError(common.ErrOperationFailed, "failed to remove lock file", err)
	}

	return nil
}

func NewS3Backend(bucket, key, region string) (*S3Backend, error) {
	if bucket == "" {
		return nil, common.NewError(common.ErrInvalidConfig, "s3 bucket is required")
	}
	if key == "" {
		key = "multicloud/state.tfstate"
	}
	if region == "" {
		region = "us-east-1"
	}

	return &S3Backend{
		bucket:  bucket,
		key:     key,
		region:  region,
		lockKey: key + ".lock",
	}, nil
}

type S3Backend struct {
	bucket  string
	key     string
	region  string
	lockKey string
	state   *State
}

func (b *S3Backend) Path() string {
	return fmt.Sprintf("s3://%s/%s", b.bucket, b.key)
}

func (b *S3Backend) Exists() bool {
	return b.state != nil
}

func (b *S3Backend) Load() (*State, error) {
	if b.state == nil {
		b.state = NewState()
	}
	return b.state, nil
}

func (b *S3Backend) Save(state *State) error {
	b.state = state
	return nil
}

func (b *S3Backend) Lock(lock *StateLock) error {
	if b.state != nil && b.state.LockInfo != nil && b.state.LockInfo.ExpiresAt.After(time.Now()) {
		return common.NewError(common.ErrStateLocked,
			fmt.Sprintf("state is locked by %s (operation: %s)",
				b.state.LockInfo.Who, b.state.LockInfo.Operation))
	}
	return nil
}

func (b *S3Backend) Unlock(id string) error {
	if b.state != nil && b.state.LockInfo != nil && b.state.LockInfo.ID != id {
		return common.NewError(common.ErrStateLocked, "invalid lock ID")
	}
	return nil
}
