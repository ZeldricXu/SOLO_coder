package pool

import (
	"errors"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"netproxy/internal/config"
	"netproxy/internal/logger"
)

type PooledConnection struct {
	conn          net.Conn
	host          string
	port          int
	createdAt     time.Time
	lastUsed      time.Time
	acquiredAt    time.Time
	inUse         bool
	invalid       bool
}

type ReconnectLimiter struct {
	mu           sync.Mutex
	windowStart  time.Time
	count        int
	maxRate      int
	window       time.Duration
}

type ConnectionPool struct {
	host              string
	port              int
	config            *config.PoolConfig
	idleConnections   []*PooledConnection
	activeConnections map[*PooledConnection]struct{}
	idleCount         int32
	activeCount       int32
	mu                sync.RWMutex
	cond              *sync.Cond
	lastHealthCheck   time.Time
	healthStatus      string
	stopChan          chan struct{}
	wg                sync.WaitGroup
	reclaimRunning    bool
	reconnectLimiter  *ReconnectLimiter
	configMgr         *config.ConfigManager
}

type PoolManager struct {
	config          *config.PoolConfig
	pools           map[string]*ConnectionPool
	mu              sync.RWMutex
	globalStopChan  chan struct{}
	globalWg        sync.WaitGroup
	configMgr       *config.ConfigManager
}

var (
	instance *PoolManager
	once     sync.Once
)

var (
	ErrPoolClosed         = errors.New("connection pool is closed")
	ErrPoolExhausted      = errors.New("connection pool exhausted")
	ErrConnectionTimeout  = errors.New("connection timeout")
	ErrConnectionInvalid  = errors.New("connection is invalid")
	ErrConnectionReclaimed = errors.New("connection was reclaimed due to timeout")
	ErrReconnectRateLimit = errors.New("reconnect rate limit exceeded")
)

func NewPooledConnection(conn net.Conn, host string, port int) *PooledConnection {
	return &PooledConnection{
		conn:      conn,
		host:      host,
		port:      port,
		createdAt: time.Now(),
		lastUsed:  time.Now(),
	}
}

func (pc *PooledConnection) Read(b []byte) (n int, err error) {
	pc.lastUsed = time.Now()
	return pc.conn.Read(b)
}

func (pc *PooledConnection) Write(b []byte) (n int, err error) {
	pc.lastUsed = time.Now()
	return pc.conn.Write(b)
}

func (pc *PooledConnection) Close() error {
	return pc.conn.Close()
}

func (pc *PooledConnection) LocalAddr() net.Addr {
	return pc.conn.LocalAddr()
}

func (pc *PooledConnection) RemoteAddr() net.Addr {
	return pc.conn.RemoteAddr()
}

func (pc *PooledConnection) SetDeadline(t time.Time) error {
	return pc.conn.SetDeadline(t)
}

func (pc *PooledConnection) SetReadDeadline(t time.Time) error {
	return pc.conn.SetReadDeadline(t)
}

func (pc *PooledConnection) SetWriteDeadline(t time.Time) error {
	return pc.conn.SetWriteDeadline(t)
}

func (pc *PooledConnection) IsValid() bool {
	if pc.invalid {
		return false
	}

	oneByte := make([]byte, 1)
	pc.conn.SetReadDeadline(time.Now().Add(1 * time.Millisecond))
	_, err := pc.conn.Read(oneByte)
	if err != nil {
		if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
			return true
		}
		return false
	}
	return true
}

func (pc *PooledConnection) MarkInvalid() {
	pc.invalid = true
}

func (pc *PooledConnection) GetHoldDuration() time.Duration {
	if !pc.inUse {
		return 0
	}
	return time.Since(pc.acquiredAt)
}

func NewReconnectLimiter(maxRate int, window time.Duration) *ReconnectLimiter {
	if maxRate <= 0 {
		maxRate = 10
	}
	if window <= 0 {
		window = 1 * time.Second
	}
	return &ReconnectLimiter{
		windowStart: time.Now(),
		count:       0,
		maxRate:     maxRate,
		window:      window,
	}
}

func (rl *ReconnectLimiter) Allow() bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	now := time.Now()
	if now.Sub(rl.windowStart) >= rl.window {
		rl.windowStart = now
		rl.count = 0
	}

	if rl.count >= rl.maxRate {
		return false
	}

	rl.count++
	return true
}

func (rl *ReconnectLimiter) Wait() {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	now := time.Now()
	if now.Sub(rl.windowStart) < rl.window && rl.count >= rl.maxRate {
		waitTime := rl.window - now.Sub(rl.windowStart)
		time.Sleep(waitTime)
		rl.windowStart = time.Now()
		rl.count = 0
	}

	rl.count++
}

func (rl *ReconnectLimiter) SetRate(maxRate int, window time.Duration) {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	if maxRate > 0 {
		rl.maxRate = maxRate
	}
	if window > 0 {
		rl.window = window
	}
}

func NewConnectionPool(host string, port int, cfg *config.PoolConfig, configMgr *config.ConfigManager) *ConnectionPool {
	var effectiveConfig *config.PoolConfig
	if configMgr != nil {
		effectiveConfig = configMgr.GetEffectivePoolConfig(host)
	}
	if effectiveConfig == nil {
		effectiveConfig = cfg
	}

	pool := &ConnectionPool{
		host:              host,
		port:              port,
		config:            effectiveConfig,
		idleConnections:   make([]*PooledConnection, 0, effectiveConfig.MaxConnections),
		activeConnections: make(map[*PooledConnection]struct{}, effectiveConfig.MaxConnections),
		lastHealthCheck:   time.Now(),
		healthStatus:      "healthy",
		stopChan:          make(chan struct{}),
		reconnectLimiter: NewReconnectLimiter(
			cfg.MaxReconnectRate,
			time.Duration(cfg.ReconnectWindow)*time.Second,
		),
		configMgr: configMgr,
	}
	pool.cond = sync.NewCond(&pool.mu)
	return pool
}

func (cp *ConnectionPool) StartReclaimer() {
	cp.mu.Lock()
	if cp.reclaimRunning {
		cp.mu.Unlock()
		return
	}
	cp.reclaimRunning = true
	cp.mu.Unlock()

	cp.wg.Add(1)
	go cp.reclaimLoop()
}

func (cp *ConnectionPool) StopReclaimer() {
	cp.mu.Lock()
	if !cp.reclaimRunning {
		cp.mu.Unlock()
		return
	}
	cp.reclaimRunning = false
	close(cp.stopChan)
	cp.mu.Unlock()
	cp.wg.Wait()
}

func (cp *ConnectionPool) reclaimLoop() {
	defer cp.wg.Done()

	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-cp.stopChan:
			return
		case <-ticker.C:
			holdTimeout := time.Duration(cp.getHoldTimeout()) * time.Second
			if holdTimeout <= 0 {
				holdTimeout = 300 * time.Second
			}
			cp.reclaimExpiredConnections(holdTimeout)
		}
	}
}

func (cp *ConnectionPool) getHoldTimeout() int {
	if cp.configMgr != nil {
		timeoutCfg := cp.configMgr.GetTimeoutConfig(cp.host)
		if timeoutCfg != nil && timeoutCfg.HoldTimeout > 0 {
			return timeoutCfg.HoldTimeout
		}
	}
	if cp.config != nil {
		return cp.config.HoldTimeout
	}
	return 600
}

func (cp *ConnectionPool) getConnectionTimeout() int {
	if cp.configMgr != nil {
		timeoutCfg := cp.configMgr.GetTimeoutConfig(cp.host)
		if timeoutCfg != nil && timeoutCfg.ConnectTimeout > 0 {
			return timeoutCfg.ConnectTimeout
		}
	}
	if cp.config != nil {
		return cp.config.ConnectionTimeout
	}
	return 30
}

func (cp *ConnectionPool) getIdleTimeout() int {
	if cp.configMgr != nil {
		timeoutCfg := cp.configMgr.GetTimeoutConfig(cp.host)
		if timeoutCfg != nil && timeoutCfg.IdleTimeout > 0 {
			return timeoutCfg.IdleTimeout
		}
	}
	if cp.config != nil {
		return cp.config.IdleTimeout
	}
	return 300
}

func (cp *ConnectionPool) getMaxConnections() int {
	if cp.configMgr != nil {
		timeoutCfg := cp.configMgr.GetTimeoutConfig(cp.host)
		if timeoutCfg != nil && timeoutCfg.MaxConnections > 0 {
			return timeoutCfg.MaxConnections
		}
	}
	if cp.config != nil {
		return cp.config.MaxConnections
	}
	return 50
}

func (cp *ConnectionPool) reclaimExpiredConnections(holdTimeout time.Duration) {
	cp.mu.Lock()
	defer cp.mu.Unlock()

	now := time.Now()
	reclaimed := 0

	for conn := range cp.activeConnections {
		if conn.inUse && now.Sub(conn.acquiredAt) > holdTimeout {
			logger.Warn("Reclaiming connection held for too long: %s:%d (held for %v)",
				conn.host, conn.port, now.Sub(conn.acquiredAt))

			conn.MarkInvalid()
			conn.Close()
			delete(cp.activeConnections, conn)
			atomic.AddInt32(&cp.activeCount, -1)
			reclaimed++
		}
	}

	if reclaimed > 0 {
		logger.Info("Reclaimed %d expired connections from pool %s:%d", reclaimed, cp.host, cp.port)
		cp.cond.Broadcast()
	}
}

func (cp *ConnectionPool) createConnection() (*PooledConnection, error) {
	if !cp.reconnectLimiter.Allow() {
		logger.Warn("Reconnect rate limit exceeded for %s:%d, waiting...", cp.host, cp.port)
		cp.reconnectLimiter.Wait()
	}

	address := fmt.Sprintf("%s:%d", cp.host, cp.port)
	timeout := time.Duration(cp.getConnectionTimeout()) * time.Second

	conn, err := net.DialTimeout("tcp", address, timeout)
	if err != nil {
		return nil, err
	}

	pooledConn := NewPooledConnection(conn, cp.host, cp.port)
	return pooledConn, nil
}

func (cp *ConnectionPool) Get() (*PooledConnection, error) {
	cp.mu.Lock()
	defer cp.mu.Unlock()

	idleTimeout := time.Duration(cp.getIdleTimeout()) * time.Second
	connectTimeout := time.Duration(cp.getConnectionTimeout()) * time.Second
	maxConnections := cp.getMaxConnections()

	for {
		for len(cp.idleConnections) > 0 {
			conn := cp.idleConnections[len(cp.idleConnections)-1]
			cp.idleConnections = cp.idleConnections[:len(cp.idleConnections)-1]
			atomic.AddInt32(&cp.idleCount, -1)

			if time.Since(conn.lastUsed) > idleTimeout {
				conn.Close()
				continue
			}

			if !conn.IsValid() {
				conn.Close()
				continue
			}

			conn.inUse = true
			conn.acquiredAt = time.Now()
			conn.lastUsed = time.Now()
			cp.activeConnections[conn] = struct{}{}
			atomic.AddInt32(&cp.activeCount, 1)

			return conn, nil
		}

		if int(cp.activeCount) < maxConnections {
			conn, err := cp.createConnection()
			if err != nil {
				return nil, err
			}

			conn.inUse = true
			conn.acquiredAt = time.Now()
			cp.activeConnections[conn] = struct{}{}
			atomic.AddInt32(&cp.activeCount, 1)

			return conn, nil
		}

		timeout := time.After(connectTimeout)
		waitChan := make(chan struct{})

		go func() {
			cp.cond.Wait()
			close(waitChan)
		}()

		select {
		case <-waitChan:
		case <-timeout:
			return nil, ErrConnectionTimeout
		}
	}
}

func (cp *ConnectionPool) Put(conn *PooledConnection) {
	if conn == nil {
		return
	}

	cp.mu.Lock()
	defer cp.mu.Unlock()

	if _, exists := cp.activeConnections[conn]; !exists {
		return
	}

	delete(cp.activeConnections, conn)
	atomic.AddInt32(&cp.activeCount, -1)
	conn.inUse = false

	if conn.invalid || !conn.IsValid() {
		conn.Close()
		cp.cond.Signal()
		return
	}

	idleTimeout := time.Duration(cp.getIdleTimeout()) * time.Second
	if time.Since(conn.createdAt) > idleTimeout*2 {
		conn.Close()
		cp.cond.Signal()
		return
	}

	conn.lastUsed = time.Now()
	cp.idleConnections = append(cp.idleConnections, conn)
	atomic.AddInt32(&cp.idleCount, 1)

	cp.cond.Signal()
}

func (cp *ConnectionPool) ForceRemoveInvalidConnections() int {
	cp.mu.Lock()
	defer cp.mu.Unlock()

	removed := 0
	idleToRemove := make([]*PooledConnection, 0)
	activeToRemove := make([]*PooledConnection, 0)

	for i := 0; i < len(cp.idleConnections); i++ {
		conn := cp.idleConnections[i]
		if conn.invalid || !conn.IsValid() {
			idleToRemove = append(idleToRemove, conn)
		}
	}

	for conn := range cp.activeConnections {
		if conn.invalid {
			activeToRemove = append(activeToRemove, conn)
		}
	}

	for _, conn := range idleToRemove {
		conn.Close()
		removed++
	}
	for _, conn := range activeToRemove {
		conn.Close()
		delete(cp.activeConnections, conn)
		removed++
	}

	atomic.AddInt32(&cp.idleCount, -int32(len(idleToRemove)))
	atomic.AddInt32(&cp.activeCount, -int32(len(activeToRemove)))

	if len(idleToRemove) > 0 {
		newIdle := make([]*PooledConnection, 0, len(cp.idleConnections)-len(idleToRemove))
		removeMap := make(map[*PooledConnection]bool)
		for _, conn := range idleToRemove {
			removeMap[conn] = true
		}
		for _, conn := range cp.idleConnections {
			if !removeMap[conn] {
				newIdle = append(newIdle, conn)
			}
		}
		cp.idleConnections = newIdle
	}

	if removed > 0 {
		logger.Debug("Batch removed %d invalid connections from pool %s:%d (idle: %d, active: %d)",
			removed, cp.host, cp.port, len(idleToRemove), len(activeToRemove))
		cp.cond.Broadcast()
	}

	return removed
}

func (cp *ConnectionPool) Close() {
	cp.StopReclaimer()

	cp.mu.Lock()
	defer cp.mu.Unlock()

	for _, conn := range cp.idleConnections {
		conn.Close()
	}
	cp.idleConnections = nil
	atomic.StoreInt32(&cp.idleCount, 0)

	for conn := range cp.activeConnections {
		conn.MarkInvalid()
		conn.Close()
	}
	cp.activeConnections = nil
	atomic.StoreInt32(&cp.activeCount, 0)

	cp.cond.Broadcast()
}

func (cp *ConnectionPool) Stats() PoolStats {
	cp.mu.RLock()
	defer cp.mu.RUnlock()

	var totalHoldTime time.Duration
	var activeWithHoldTime int
	for conn := range cp.activeConnections {
		if conn.inUse {
			totalHoldTime += conn.GetHoldDuration()
			activeWithHoldTime++
		}
	}

	var avgHoldTime int64
	if activeWithHoldTime > 0 {
		avgHoldTime = int64(totalHoldTime / time.Duration(activeWithHoldTime))
	}

	return PoolStats{
		Host:               cp.host,
		Port:               cp.port,
		ActiveConnections:  int(atomic.LoadInt32(&cp.activeCount)),
		IdleConnections:    int(atomic.LoadInt32(&cp.idleCount)),
		MaxConnections:     cp.getMaxConnections(),
		LastHealthCheck:    cp.lastHealthCheck,
		HealthStatus:       cp.healthStatus,
		AvgHoldTimeMs:      avgHoldTime,
		HoldTimeoutMs:      int64(cp.getHoldTimeout()) * 1000,
		ConnectionTimeoutMs: int64(cp.getConnectionTimeout()) * 1000,
		IdleTimeoutMs:      int64(cp.getIdleTimeout()) * 1000,
	}
}

func (cp *ConnectionPool) CheckHealth() error {
	cp.mu.Lock()
	defer cp.mu.Unlock()

	cp.lastHealthCheck = time.Now()

	invalidCount := 0
	for i := 0; i < len(cp.idleConnections); {
		conn := cp.idleConnections[i]
		if !conn.IsValid() {
			conn.Close()
			cp.idleConnections = append(cp.idleConnections[:i], cp.idleConnections[i+1:]...)
			atomic.AddInt32(&cp.idleCount, -1)
			invalidCount++
		} else {
			i++
		}
	}

	if invalidCount > 0 {
		logger.Debug("Health check: removed %d invalid idle connections for %s:%d",
			invalidCount, cp.host, cp.port)
	}

	testConn, err := cp.createConnection()
	if err != nil {
		cp.healthStatus = "unhealthy"
		return err
	}
	testConn.Close()
	cp.healthStatus = "healthy"

	return nil
}

func (cp *ConnectionPool) SetHealthStatus(status string) {
	cp.mu.Lock()
	defer cp.mu.Unlock()
	cp.healthStatus = status
}

func (cp *ConnectionPool) SetReconnectRate(maxRate int, window time.Duration) {
	cp.reconnectLimiter.SetRate(maxRate, window)
}

type PoolStats struct {
	Host                string    `json:"host"`
	Port                int       `json:"port"`
	ActiveConnections   int       `json:"active_connections"`
	IdleConnections     int       `json:"idle_connections"`
	MaxConnections      int       `json:"max_connections"`
	LastHealthCheck     time.Time `json:"last_health_check"`
	HealthStatus        string    `json:"health_status"`
	AvgHoldTimeMs       int64     `json:"avg_hold_time_ms"`
	HoldTimeoutMs       int64     `json:"hold_timeout_ms"`
	ConnectionTimeoutMs int64     `json:"connection_timeout_ms"`
	IdleTimeoutMs       int64     `json:"idle_timeout_ms"`
}

func NewPoolManager(cfg *config.PoolConfig, configMgr *config.ConfigManager) *PoolManager {
	return &PoolManager{
		config:         cfg,
		pools:          make(map[string]*ConnectionPool),
		globalStopChan: make(chan struct{}),
		configMgr:      configMgr,
	}
}

func InitPoolManager(cfg *config.PoolConfig) {
	once.Do(func() {
		instance = NewPoolManager(cfg, nil)
	})
}

func InitPoolManagerWithConfig(cfg *config.PoolConfig, configMgr *config.ConfigManager) {
	once.Do(func() {
		instance = NewPoolManager(cfg, configMgr)
	})
}

func GetPoolManager() *PoolManager {
	return instance
}

func (pm *PoolManager) getPoolKey(host string, port int) string {
	return fmt.Sprintf("%s:%d", host, port)
}

func (pm *PoolManager) GetPool(host string, port int) *ConnectionPool {
	key := pm.getPoolKey(host, port)

	pm.mu.RLock()
	pool, exists := pm.pools[key]
	pm.mu.RUnlock()

	if exists {
		return pool
	}

	pm.mu.Lock()
	defer pm.mu.Unlock()

	if pool, exists := pm.pools[key]; exists {
		return pool
	}

	pool = NewConnectionPool(host, port, pm.config, pm.configMgr)
	pool.StartReclaimer()
	pm.pools[key] = pool
	return pool
}

func (pm *PoolManager) GetConnection(host string, port int) (*PooledConnection, error) {
	pool := pm.GetPool(host, port)
	return pool.Get()
}

func (pm *PoolManager) ReleaseConnection(host string, port int, conn *PooledConnection) {
	pool := pm.GetPool(host, port)
	pool.Put(conn)
}

func (pm *PoolManager) ClosePool(host string, port int) {
	key := pm.getPoolKey(host, port)

	pm.mu.Lock()
	defer pm.mu.Unlock()

	if pool, exists := pm.pools[key]; exists {
		pool.Close()
		delete(pm.pools, key)
	}
}

func (pm *PoolManager) CloseAll() {
	pm.mu.Lock()
	pools := make(map[string]*ConnectionPool, len(pm.pools))
	for key, pool := range pm.pools {
		pools[key] = pool
	}
	pm.mu.Unlock()

	for key, pool := range pools {
		pool.Close()
		pm.mu.Lock()
		delete(pm.pools, key)
		pm.mu.Unlock()
	}
}

func (pm *PoolManager) GetAllPoolStats() []PoolStats {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	stats := make([]PoolStats, 0, len(pm.pools))
	for _, pool := range pm.pools {
		stats = append(stats, pool.Stats())
	}
	return stats
}

func (pm *PoolManager) GetPoolStats(host string, port int) *PoolStats {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	key := pm.getPoolKey(host, port)
	if pool, exists := pm.pools[key]; exists {
		stats := pool.Stats()
		return &stats
	}
	return nil
}

func (pm *PoolManager) CheckAllHealth() map[string]error {
	pm.mu.RLock()
	pools := make(map[string]*ConnectionPool, len(pm.pools))
	for key, pool := range pm.pools {
		pools[key] = pool
	}
	pm.mu.RUnlock()

	results := make(map[string]error)
	for key, pool := range pools {
		err := pool.CheckHealth()
		if err != nil {
			results[key] = err
			logger.Warn("Health check failed for %s: %v", key, err)
		} else {
			logger.Debug("Health check passed for %s", key)
		}
	}
	return results
}

func (pm *PoolManager) ForceRemoveAllInvalidConnections() int {
	pm.mu.RLock()
	pools := make(map[string]*ConnectionPool, len(pm.pools))
	for key, pool := range pm.pools {
		pools[key] = pool
	}
	pm.mu.RUnlock()

	totalRemoved := 0
	for _, pool := range pools {
		totalRemoved += pool.ForceRemoveInvalidConnections()
	}

	if totalRemoved > 0 {
		logger.Info("Batch removed %d invalid connections across all pools", totalRemoved)
	}

	return totalRemoved
}

func (pm *PoolManager) BatchCleanInvalidConnections() (int, error) {
	return pm.ForceRemoveAllInvalidConnections(), nil
}
