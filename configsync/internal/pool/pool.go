package pool

import (
	"fmt"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"

	"configsync/internal/models"
)

type SSHClientFactory interface {
	Create(server *models.Server) (*ssh.Client, error)
}

type DefaultSSHFactory struct{}

func (f *DefaultSSHFactory) Create(server *models.Server) (*ssh.Client, error) {
	sshConfig, err := createSSHConfig(server)
	if err != nil {
		return nil, fmt.Errorf("failed to create SSH config: %w", err)
	}

	addr := fmt.Sprintf("%s:%d", server.Host, server.Port)
	client, err := ssh.Dial("tcp", addr, sshConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to %s: %w", addr, err)
	}

	return client, nil
}

type pooledConnection struct {
	client     *ssh.Client
	serverID   string
	lastUsed   time.Time
	createdAt  time.Time
}

type ConnectionPool struct {
	connections map[string]*pooledConnection
	maxIdleTime time.Duration
	maxPoolSize int
	factory     SSHClientFactory
	mu          sync.RWMutex
	stopCleaner chan struct{}
}

var (
	defaultMaxIdleTime = 5 * time.Minute
	defaultMaxPoolSize = 20
)

func NewConnectionPool() *ConnectionPool {
	return NewConnectionPoolWithFactory(&DefaultSSHFactory{})
}

func NewConnectionPoolWithFactory(factory SSHClientFactory) *ConnectionPool {
	return NewConnectionPoolWithOptionsAndFactory(defaultMaxIdleTime, defaultMaxPoolSize, factory)
}

func NewConnectionPoolWithOptions(maxIdleTime time.Duration, maxPoolSize int) *ConnectionPool {
	return NewConnectionPoolWithOptionsAndFactory(maxIdleTime, maxPoolSize, &DefaultSSHFactory{})
}

func NewConnectionPoolWithOptionsAndFactory(maxIdleTime time.Duration, maxPoolSize int, factory SSHClientFactory) *ConnectionPool {
	if factory == nil {
		factory = &DefaultSSHFactory{}
	}
	pool := &ConnectionPool{
		connections: make(map[string]*pooledConnection),
		maxIdleTime: maxIdleTime,
		maxPoolSize: maxPoolSize,
		factory:     factory,
		stopCleaner: make(chan struct{}),
	}

	go pool.startCleaner()

	return pool
}

func (p *ConnectionPool) startCleaner() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			p.cleanupIdleConnections()
		case <-p.stopCleaner:
			return
		}
	}
}

func (p *ConnectionPool) cleanupIdleConnections() {
	p.mu.Lock()
	defer p.mu.Unlock()

	now := time.Now()
	for serverID, conn := range p.connections {
		if now.Sub(conn.lastUsed) > p.maxIdleTime {
			conn.client.Close()
			delete(p.connections, serverID)
		}
	}
}

func (p *ConnectionPool) Close() {
	close(p.stopCleaner)

	p.mu.Lock()
	defer p.mu.Unlock()

	for _, conn := range p.connections {
		conn.client.Close()
	}
	p.connections = make(map[string]*pooledConnection)
}

func (p *ConnectionPool) getPoolKey(server *models.Server) string {
	return fmt.Sprintf("%s:%d:%s", server.Host, server.Port, server.User)
}

func (p *ConnectionPool) Get(server *models.Server) (*ssh.Client, error) {
	key := p.getPoolKey(server)

	p.mu.RLock()
	if pooled, exists := p.connections[key]; exists {
		if p.isConnectionAlive(pooled.client) {
			pooled.lastUsed = time.Now()
			p.mu.RUnlock()
			return pooled.client, nil
		}
		p.mu.RUnlock()

		p.mu.Lock()
		pooled.client.Close()
		delete(p.connections, key)
		p.mu.Unlock()
	} else {
		p.mu.RUnlock()
	}

	return p.createAndCache(server, key)
}

func (p *ConnectionPool) createAndCache(server *models.Server, key string) (*ssh.Client, error) {
	p.mu.Lock()
	defer p.mu.Unlock()

	if pooled, exists := p.connections[key]; exists {
		if p.isConnectionAlive(pooled.client) {
			pooled.lastUsed = time.Now()
			return pooled.client, nil
		}
		pooled.client.Close()
		delete(p.connections, key)
	}

	if len(p.connections) >= p.maxPoolSize {
		p.evictOldestConnection()
	}

	client, err := p.createSSHClient(server)
	if err != nil {
		return nil, err
	}

	p.connections[key] = &pooledConnection{
		client:    client,
		serverID:  server.ServerID,
		lastUsed:  time.Now(),
		createdAt: time.Now(),
	}

	return client, nil
}

func (p *ConnectionPool) evictOldestConnection() {
	var oldestKey string
	var oldestTime time.Time

	for key, conn := range p.connections {
		if oldestTime.IsZero() || conn.lastUsed.Before(oldestTime) {
			oldestKey = key
			oldestTime = conn.lastUsed
		}
	}

	if oldestKey != "" {
		p.connections[oldestKey].client.Close()
		delete(p.connections, oldestKey)
	}
}

func (p *ConnectionPool) isConnectionAlive(client *ssh.Client) bool {
	if client == nil {
		return false
	}

	_, _, err := client.SendRequest("keepalive@openssh.com", true, nil)
	return err == nil
}

func (p *ConnectionPool) createSSHClient(server *models.Server) (*ssh.Client, error) {
	return p.factory.Create(server)
}

func (p *ConnectionPool) Release(server *models.Server) {
	key := p.getPoolKey(server)

	p.mu.Lock()
	defer p.mu.Unlock()

	if conn, exists := p.connections[key]; exists {
		conn.lastUsed = time.Now()
	}
}

func (p *ConnectionPool) ForceClose(server *models.Server) {
	key := p.getPoolKey(server)

	p.mu.Lock()
	defer p.mu.Unlock()

	if conn, exists := p.connections[key]; exists {
		conn.client.Close()
		delete(p.connections, key)
	}
}

func (p *ConnectionPool) Stats() map[string]interface{} {
	p.mu.RLock()
	defer p.mu.RUnlock()

	return map[string]interface{}{
		"active_connections": len(p.connections),
		"max_pool_size":      p.maxPoolSize,
		"max_idle_time":      p.maxIdleTime.String(),
	}
}
