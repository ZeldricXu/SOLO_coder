package pool

import (
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/crypto/ssh"

	"configsync/internal/models"
)

type MockSSHClient struct {
	alive     bool
	closed    bool
	mu        sync.Mutex
	sessionID int
}

var (
	mockClientIDCounter int64
)

func NewMockSSHClient(alive bool) *MockSSHClient {
	id := atomic.AddInt64(&mockClientIDCounter, 1)
	return &MockSSHClient{
		alive:     alive,
		closed:    false,
		sessionID: int(id),
	}
}

func (m *MockSSHClient) Close() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.closed = true
	m.alive = false
	return nil
}

func (m *MockSSHClient) IsAlive() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.alive && !m.closed
}

func (m *MockSSHClient) SetAlive(alive bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.alive = alive
}

func (m *MockSSHClient) SessionID() int {
	return m.sessionID
}

func (m *MockSSHClient) Dial(network, addr string, config *ssh.ClientConfig) (*ssh.Client, error) {
	return nil, fmt.Errorf("not implemented")
}

type MockSSHFactory struct {
	clients     map[string]*MockSSHClient
	createError error
	alwaysError bool
	mu          sync.Mutex
	createCount int
}

func NewMockSSHFactory() *MockSSHFactory {
	return &MockSSHFactory{
		clients: make(map[string]*MockSSHClient),
	}
}

func (f *MockSSHFactory) SetAlwaysError(err error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.alwaysError = true
	f.createError = err
}

func (f *MockSSHFactory) ClearAlwaysError() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.alwaysError = false
	f.createError = nil
}

func (f *MockSSHFactory) GetCreateCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.createCount
}

func (f *MockSSHFactory) ResetCreateCount() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.createCount = 0
}

func (f *MockSSHFactory) Create(server *models.Server) (*ssh.Client, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.createCount++

	if f.alwaysError {
		return nil, f.createError
	}

	key := fmt.Sprintf("%s:%d:%s", server.Host, server.Port, server.User)
	mockClient := NewMockSSHClient(true)
	f.clients[key] = mockClient

	return &ssh.Client{}, nil
}

type ControllableMockFactory struct {
	clients        map[string]*MockSSHClient
	nextAlive      bool
	connectionDelay time.Duration
	mu             sync.Mutex
	createCount    int
}

func NewControllableMockFactory() *ControllableMockFactory {
	return &ControllableMockFactory{
		clients:       make(map[string]*MockSSHClient),
		nextAlive:     true,
		connectionDelay: 0,
	}
}

func (f *ControllableMockFactory) SetNextAlive(alive bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.nextAlive = alive
}

func (f *ControllableMockFactory) SetConnectionDelay(delay time.Duration) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.connectionDelay = delay
}

func (f *ControllableMockFactory) GetCreateCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.createCount
}

func (f *ControllableMockFactory) Create(server *models.Server) (*ssh.Client, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	if f.connectionDelay > 0 {
		time.Sleep(f.connectionDelay)
	}

	f.createCount++
	key := fmt.Sprintf("%s:%d:%s", server.Host, server.Port, server.User)
	mockClient := NewMockSSHClient(f.nextAlive)
	f.clients[key] = mockClient

	return &ssh.Client{}, nil
}

func (f *ControllableMockFactory) GetClient(server *models.Server) *MockSSHClient {
	f.mu.Lock()
	defer f.mu.Unlock()
	key := fmt.Sprintf("%s:%d:%s", server.Host, server.Port, server.User)
	return f.clients[key]
}

func (f *ControllableMockFactory) SetClientDead(server *models.Server) {
	f.mu.Lock()
	defer f.mu.Unlock()
	key := fmt.Sprintf("%s:%d:%s", server.Host, server.Port, server.User)
	if client, exists := f.clients[key]; exists {
		client.SetAlive(false)
	}
}

type TestPoolConfig struct {
	MaxIdleTime time.Duration
	MaxPoolSize int
}

func NewTestPool(config TestPoolConfig, factory SSHClientFactory) *ConnectionPool {
	if factory == nil {
		factory = &MockSSHFactory{}
	}
	maxIdleTime := config.MaxIdleTime
	if maxIdleTime <= 0 {
		maxIdleTime = 5 * time.Minute
	}
	maxPoolSize := config.MaxPoolSize
	if maxPoolSize <= 0 {
		maxPoolSize = 20
	}
	return NewConnectionPoolWithOptionsAndFactory(maxIdleTime, maxPoolSize, factory)
}
