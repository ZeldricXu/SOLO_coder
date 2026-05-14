package health

import (
	"fmt"
	"net"
	"sync"
	"time"

	"netproxy/internal/config"
	"netproxy/internal/logger"
	"netproxy/internal/pool"
)

type HealthStatus string

const (
	StatusHealthy   HealthStatus = "healthy"
	StatusDegraded  HealthStatus = "degraded"
	StatusUnhealthy HealthStatus = "unhealthy"
	StatusUnknown   HealthStatus = "unknown"
)

type TargetHealth struct {
	Host             string       `json:"host"`
	Port             int          `json:"port"`
	Status           HealthStatus `json:"status"`
	LastCheck        time.Time    `json:"last_check"`
	LastSuccess      time.Time    `json:"last_success"`
	LastFailure      time.Time    `json:"last_failure"`
	FailureCount     int          `json:"failure_count"`
	SuccessCount     int          `json:"success_count"`
	ConsecutiveFails int          `json:"consecutive_fails"`
	ResponseTime     int64        `json:"response_time_ms"`
	Error            string       `json:"error,omitempty"`
	InvalidConnCount int          `json:"invalid_connection_count"`
}

type HealthChecker struct {
	config     *config.HealthConfig
	poolMgr    *pool.PoolManager
	targets    map[string]*TargetHealth
	mu         sync.RWMutex
	ticker     *time.Ticker
	done       chan struct{}
	wg         sync.WaitGroup
	running    bool
	autoClean  bool
}

var (
	instance *HealthChecker
	once     sync.Once
)

func NewHealthChecker(cfg *config.HealthConfig, poolMgr *pool.PoolManager) *HealthChecker {
	return &HealthChecker{
		config:    cfg,
		poolMgr:   poolMgr,
		targets:   make(map[string]*TargetHealth),
		done:      make(chan struct{}),
		autoClean: true,
	}
}

func InitHealthChecker(cfg *config.HealthConfig, poolMgr *pool.PoolManager) {
	once.Do(func() {
		instance = NewHealthChecker(cfg, poolMgr)
	})
}

func GetHealthChecker() *HealthChecker {
	return instance
}

func (hc *HealthChecker) SetAutoClean(enabled bool) {
	hc.mu.Lock()
	defer hc.mu.Unlock()
	hc.autoClean = enabled
}

func (hc *HealthChecker) getTargetKey(host string, port int) string {
	return fmt.Sprintf("%s:%d", host, port)
}

func (hc *HealthChecker) getOrCreateTarget(host string, port int) *TargetHealth {
	key := hc.getTargetKey(host, port)

	hc.mu.RLock()
	target, exists := hc.targets[key]
	hc.mu.RUnlock()

	if exists {
		return target
	}

	hc.mu.Lock()
	defer hc.mu.Unlock()

	if target, exists := hc.targets[key]; exists {
		return target
	}

	target = &TargetHealth{
		Host:   host,
		Port:   port,
		Status: StatusUnknown,
	}
	hc.targets[key] = target
	return target
}

func (hc *HealthChecker) checkTarget(host string, port int) error {
	address := fmt.Sprintf("%s:%d", host, port)
	timeout := time.Duration(hc.config.Timeout) * time.Second

	start := time.Now()
	conn, err := net.DialTimeout("tcp", address, timeout)
	if err != nil {
		return err
	}
	defer conn.Close()

	conn.SetReadDeadline(time.Now().Add(timeout))
	oneByte := make([]byte, 1)
	conn.Read(oneByte)

	elapsed := time.Since(start)

	target := hc.getOrCreateTarget(host, port)
	hc.mu.Lock()
	defer hc.mu.Unlock()

	target.LastCheck = time.Now()
	target.LastSuccess = time.Now()
	target.SuccessCount++
	target.ConsecutiveFails = 0
	target.ResponseTime = elapsed.Milliseconds()
	target.Error = ""
	target.InvalidConnCount = 0

	if target.ConsecutiveFails >= hc.config.FailureThreshold {
		target.Status = StatusDegraded
	} else {
		target.Status = StatusHealthy
	}

	return nil
}

func (hc *HealthChecker) CheckTargetWithRetry(host string, port int) error {
	var lastErr error

	for i := 0; i < hc.config.RetryCount; i++ {
		err := hc.checkTarget(host, port)
		if err == nil {
			return nil
		}
		lastErr = err

		target := hc.getOrCreateTarget(host, port)
		hc.mu.Lock()
		target.LastCheck = time.Now()
		target.LastFailure = time.Now()
		target.FailureCount++
		target.ConsecutiveFails++
		target.Error = err.Error()

		if target.ConsecutiveFails >= hc.config.FailureThreshold {
			target.Status = StatusUnhealthy
		} else {
			target.Status = StatusDegraded
		}
		hc.mu.Unlock()

		if i < hc.config.RetryCount-1 {
			time.Sleep(1 * time.Second)
		}
	}

	if hc.autoClean {
		hc.cleanInvalidConnections(host, port)
	}

	return lastErr
}

func (hc *HealthChecker) cleanInvalidConnections(host string, port int) {
	if hc.poolMgr == nil {
		return
	}

	poolStats := hc.poolMgr.GetPoolStats(host, port)
	if poolStats == nil {
		return
	}

	target := hc.getOrCreateTarget(host, port)
	removedCount := 0

	if connPool := hc.poolMgr.GetPool(host, port); connPool != nil {
		removedCount = connPool.ForceRemoveInvalidConnections()
	}

	hc.mu.Lock()
	target.InvalidConnCount = removedCount
	hc.mu.Unlock()

	if removedCount > 0 {
		logger.Warn("Health check: cleaned %d invalid connections for %s:%d", removedCount, host, port)
	}
}

func (hc *HealthChecker) CheckAllTargets() map[string]error {
	results := make(map[string]error)

	hc.mu.RLock()
	targets := make([]string, 0, len(hc.targets))
	for key := range hc.targets {
		targets = append(targets, key)
	}
	hc.mu.RUnlock()

	var wg sync.WaitGroup
	var mu sync.Mutex

	for _, key := range targets {
		wg.Add(1)
		go func(k string) {
			defer wg.Done()

			hc.mu.RLock()
			target := hc.targets[k]
			hc.mu.RUnlock()

			err := hc.CheckTargetWithRetry(target.Host, target.Port)
			mu.Lock()
			if err != nil {
				results[k] = err
			}
			mu.Unlock()
		}(key)
	}

	wg.Wait()
	return results
}

func (hc *HealthChecker) ForceCleanAllInvalidConnections() int {
	if hc.poolMgr == nil {
		return 0
	}

	return hc.poolMgr.ForceRemoveAllInvalidConnections()
}

func (hc *HealthChecker) ForceCleanTargetConnections(host string, port int) int {
	if hc.poolMgr == nil {
		return 0
	}

	if connPool := hc.poolMgr.GetPool(host, port); connPool != nil {
		return connPool.ForceRemoveInvalidConnections()
	}
	return 0
}

func (hc *HealthChecker) Start() {
	if !hc.config.Enabled {
		logger.Info("Health checker is disabled in configuration")
		return
	}

	hc.mu.Lock()
	if hc.running {
		hc.mu.Unlock()
		return
	}
	hc.running = true
	hc.ticker = time.NewTicker(time.Duration(hc.config.Interval) * time.Second)
	hc.mu.Unlock()

	hc.wg.Add(1)
	go func() {
		defer hc.wg.Done()

		for {
			select {
			case <-hc.ticker.C:
				results := hc.CheckAllTargets()
				if len(results) > 0 {
					for target, err := range results {
						logger.Warn("Health check failed for %s: %v", target, err)
					}
				}
			case <-hc.done:
				return
			}
		}
	}()

	logger.Info("Health checker started with interval %d seconds, auto clean: %v",
		hc.config.Interval, hc.autoClean)
}

func (hc *HealthChecker) Stop() {
	hc.mu.Lock()
	if !hc.running {
		hc.mu.Unlock()
		return
	}
	hc.running = false

	if hc.ticker != nil {
		hc.ticker.Stop()
	}
	close(hc.done)
	hc.mu.Unlock()

	hc.wg.Wait()
	logger.Info("Health checker stopped")
}

func (hc *HealthChecker) GetTargetHealth(host string, port int) *TargetHealth {
	hc.mu.RLock()
	defer hc.mu.RUnlock()

	key := hc.getTargetKey(host, port)
	if target, exists := hc.targets[key]; exists {
		result := *target
		return &result
	}
	return nil
}

func (hc *HealthChecker) GetAllHealth() []TargetHealth {
	hc.mu.RLock()
	defer hc.mu.RUnlock()

	healths := make([]TargetHealth, 0, len(hc.targets))
	for _, target := range hc.targets {
		healths = append(healths, *target)
	}
	return healths
}

func (hc *HealthChecker) RegisterTarget(host string, port int) {
	hc.getOrCreateTarget(host, port)
	logger.Debug("Registered health check target: %s:%d", host, port)
}

func (hc *HealthChecker) UnregisterTarget(host string, port int) {
	key := hc.getTargetKey(host, port)

	hc.mu.Lock()
	defer hc.mu.Unlock()

	delete(hc.targets, key)
	logger.Debug("Unregistered health check target: %s:%d", host, port)
}

func (hc *HealthChecker) IsTargetHealthy(host string, port int) bool {
	hc.mu.RLock()
	defer hc.mu.RUnlock()

	key := hc.getTargetKey(host, port)
	if target, exists := hc.targets[key]; exists {
		return target.Status == StatusHealthy || target.Status == StatusUnknown
	}
	return true
}

func CheckTarget(host string, port int) error {
	if instance != nil {
		return instance.CheckTargetWithRetry(host, port)
	}
	return nil
}

func GetTargetHealth(host string, port int) *TargetHealth {
	if instance != nil {
		return instance.GetTargetHealth(host, port)
	}
	return nil
}

func GetAllHealth() []TargetHealth {
	if instance != nil {
		return instance.GetAllHealth()
	}
	return []TargetHealth{}
}

func RegisterTarget(host string, port int) {
	if instance != nil {
		instance.RegisterTarget(host, port)
	}
}

func UnregisterTarget(host string, port int) {
	if instance != nil {
		instance.UnregisterTarget(host, port)
	}
}

func IsTargetHealthy(host string, port int) bool {
	if instance != nil {
		return instance.IsTargetHealthy(host, port)
	}
	return true
}

func Start() {
	if instance != nil {
		instance.Start()
	}
}

func Stop() {
	if instance != nil {
		instance.Stop()
	}
}

func ForceCleanAllInvalidConnections() int {
	if instance != nil {
		return instance.ForceCleanAllInvalidConnections()
	}
	return 0
}

func ForceCleanTargetConnections(host string, port int) int {
	if instance != nil {
		return instance.ForceCleanTargetConnections(host, port)
	}
	return 0
}

func SetAutoClean(enabled bool) {
	if instance != nil {
		instance.SetAutoClean(enabled)
	}
}
