package slo

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"session130/internal/logger"
	"session130/internal/metrics"
)

const (
	defaultReadTimeout  = 5 * time.Second
	defaultWriteTimeout = 10 * time.Second
	circuitBreakerThreshold = 5
	circuitBreakerTimeout   = 30 * time.Second
)

var (
	ErrNoReplicasAvailable = errors.New("no replicas available")
	ErrNoHealthyReplicas      = errors.New("no healthy replicas available")
	ErrNoPrimaryAvailable   = errors.New("no primary available")
	ErrReplicaNotFound       = errors.New("replica not found")
	ErrInsufficientReplicas      = errors.New("need at least 2 replicas for failover")
)

type OperationType string

const (
	OpRead  OperationType = "read"
	OpWrite OperationType = "write"
)

type ReplicaInfo struct {
	ID        string            `json:"id"`
	Host      string            `json:"host"`
	Port      int               `json:"port"`
	Priority  int               `json:"priority"`
	Healthy   bool              `json:"healthy"`
	Tags      map[string]string `json:"tags,omitempty"`
	Latency   time.Duration     `json:"latency,omitempty"`
	LastCheck time.Time         `json:"last_check,omitempty"`
}

type ReadOptions struct {
	Timeout       time.Duration
	AllowFallback bool
	PreferredTags map[string]string
}

type WriteOptions struct {
	Timeout      time.Duration
	SyncReplicas bool
}

type OperationResult[T any] struct {
	Data      T
	Source    string
	Latency   time.Duration
	FromCache bool
	Error     error
}

type RoutingStats struct {
	TotalReads      int64         `json:"total_reads"`
	TotalWrites     int64         `json:"total_writes"`
	PrimaryReads   int64         `json:"primary_reads"`
	ReplicaReads   int64         `json:"replica_reads"`
	FailedReads    int64         `json:"failed_reads"`
	FailedWrites   int64         `json:"failed_writes"`
	FailoverCount  int64         `json:"failover_count"`
	AvgReadLatency  time.Duration `json:"avg_read_latency"`
	AvgWriteLatency time.Duration `json:"avg_write_latency"`
}

type RoutingEvents interface {
	OnFailover(oldPrimary string, newPrimary string)
	OnReplicaHealthChange(replicaID string, healthy bool)
	OnOperationComplete(opType OperationType, duration time.Duration, success bool)
}

type ReadWriteRouter struct {
	mu             sync.RWMutex
	primary          string
	replicas         map[string]*ReplicaInfo
	operationMap     map[string]OperationType
	stats            atomicStats
	eventBus         RoutingEvents
	circuitBreaker   map[string]*CircuitBreaker
}

type atomicStats struct {
	totalReads    atomic.Int64
	totalWrites   atomic.Int64
	primaryReads  atomic.Int64
	replicaReads  atomic.Int64
	failedReads   atomic.Int64
	failedWrites  atomic.Int64
	failoverCount atomic.Int64
}

type CircuitBreaker struct {
	failureCount int
	lastFailure  time.Time
	threshold    int
	timeout      time.Duration
	open         bool
}

var (
	routerInstance *ReadWriteRouter
	routerOnce     sync.Once
)

func NewReadWriteRouter() *ReadWriteRouter {
	return &ReadWriteRouter{
		replicas:        make(map[string]*ReplicaInfo),
		operationMap:    make(map[string]OperationType),
		circuitBreaker:  make(map[string]*CircuitBreaker),
	}
}

func GetReadWriteRouter() *ReadWriteRouter {
	routerOnce.Do(func() {
		routerInstance = NewReadWriteRouter()
	})
	return routerInstance
}

func (r *ReadWriteRouter) SetPrimary(replicaID string) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if _, exists := r.replicas[replicaID]; !exists {
		return fmt.Errorf("%w: %s", ErrReplicaNotFound, replicaID)
	}

	oldPrimary := r.primary
	r.primary = replicaID

	if r.eventBus != nil && oldPrimary != replicaID {
		r.eventBus.OnFailover(oldPrimary, replicaID)
	}

	metrics.Inc("slo_routing_primary_set_total", nil)
	return nil
}

func (r *ReadWriteRouter) AddReplica(info ReplicaInfo) {
	r.mu.Lock()
	defer r.mu.Unlock()

	r.replicas[info.ID] = &info
	r.circuitBreaker[info.ID] = &CircuitBreaker{
		threshold: circuitBreakerThreshold,
		timeout:   circuitBreakerTimeout,
	}

	if r.primary == "" {
		r.primary = info.ID
	}

	metrics.Inc("slo_routing_replica_add_total", nil)
	logger.Info("", "replica added to SLO router", map[string]interface{}{
		"replica_id": info.ID,
		"host":       info.Host,
		"port":       info.Port,
	})
}

func (r *ReadWriteRouter) RemoveReplica(replicaID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()

	if _, exists := r.replicas[replicaID]; !exists {
		return false
	}

	delete(r.replicas, replicaID)
	delete(r.circuitBreaker, replicaID)

	if r.primary == replicaID {
		r.primary = ""
		for id := range r.replicas {
			r.primary = id
			break
		}
	}

	metrics.Inc("slo_routing_replica_remove_total", nil)
	return true
}

func (r *ReadWriteRouter) GetReplica(replicaID string) (*ReplicaInfo, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	replica, exists := r.replicas[replicaID]
	if !exists {
		return nil, false
	}

	return replica, true
}

func (r *ReadWriteRouter) GetAllReplicas() []ReplicaInfo {
	r.mu.RLock()
	defer r.mu.RUnlock()

	replicas := make([]ReplicaInfo, 0, len(r.replicas))
	for _, replica := range r.replicas {
		replicas = append(replicas, *replica)
	}
	return replicas
}

func (r *ReadWriteRouter) GetHealthyReplicas() []ReplicaInfo {
	r.mu.RLock()
	defer r.mu.RUnlock()

	replicas := make([]ReplicaInfo, 0, len(r.replicas))
	for _, replica := range r.replicas {
		if replica.Healthy {
			replicas = append(replicas, *replica)
		}
	}
	return replicas
}

func (r *ReadWriteRouter) selectReplica(options ReadOptions) (*ReplicaInfo, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	if len(r.replicas) == 0 {
		return nil, ErrNoReplicasAvailable
	}

	candidates := r.filterCandidates(options)
	if len(candidates) == 0 {
		if !options.AllowFallback {
			return nil, ErrNoHealthyReplicas
		}
		candidates = r.getAllReplicasLocked()
		if len(candidates) == 0 {
			return nil, ErrNoReplicasAvailable
		}
	}

	return r.selectBestCandidate(candidates), nil
}

func (r *ReadWriteRouter) filterCandidates(options ReadOptions) []*ReplicaInfo {
	candidates := make([]*ReplicaInfo, 0, len(r.replicas))
	for _, replica := range r.replicas {
		if !replica.Healthy {
			continue
		}

		cb := r.circuitBreaker[replica.ID]
		if cb != nil && cb.open {
			if time.Since(cb.lastFailure) > cb.timeout {
				cb.open = false
				cb.failureCount = 0
			} else {
				continue
			}
		}

		if !tagsMatch(replica.Tags, options.PreferredTags) {
			continue
		}

		candidates = append(candidates, replica)
	}
	return candidates
}

func (r *ReadWriteRouter) getAllReplicasLocked() []*ReplicaInfo {
	replicas := make([]*ReplicaInfo, 0, len(r.replicas))
	for _, replica := range r.replicas {
		replicas = append(replicas, replica)
	}
	return replicas
}

func (r *ReadWriteRouter) selectBestCandidate(candidates []*ReplicaInfo) *ReplicaInfo {
	var best *ReplicaInfo
	for _, replica := range candidates {
		if best == nil {
			best = replica
			continue
		}
		if replica.Priority > best.Priority {
			best = replica
		} else if replica.Priority == best.Priority && replica.Latency < best.Latency {
			best = replica
		}
	}
	return best
}

func tagsMatch(tags, preferred map[string]string) bool {
	if len(preferred) == 0 {
		return true
	}
	for k, v := range preferred {
		if tags[k] != v {
			return false
		}
	}
	return true
}

func (r *ReadWriteRouter) recordFailure(replicaID string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	cb := r.circuitBreaker[replicaID]
	if cb == nil {
		return
	}

	cb.failureCount++
	cb.lastFailure = time.Now()

	if cb.failureCount >= cb.threshold {
		cb.open = true
		replica, exists := r.replicas[replicaID]
		if exists {
			replica.Healthy = false
			if r.eventBus != nil {
				r.eventBus.OnReplicaHealthChange(replicaID, false)
			}
		}
		metrics.Inc("slo_routing_circuit_breaker_open_total", map[string]string{
			"replica_id": replicaID,
		})
	}
}

func (r *ReadWriteRouter) recordSuccess(replicaID string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	cb := r.circuitBreaker[replicaID]
	if cb == nil {
		return
	}

	cb.failureCount = 0
	if cb.open {
		cb.open = false
		replica, exists := r.replicas[replicaID]
		if exists {
			replica.Healthy = true
			if r.eventBus != nil {
				r.eventBus.OnReplicaHealthChange(replicaID, true)
			}
		}
	}
}

func (r *ReadWriteRouter) RouteRead(
	ctx context.Context,
	operation string,
	params []interface{},
	primaryFn func(ctx context.Context) (interface{}, error),
	replicaFn func(ctx context.Context, replica ReplicaInfo) (interface{}, error),
	options *ReadOptions,
) OperationResult[interface{}] {
	start := time.Now()
	r.stats.totalReads.Add(1)

	options = r.defaultReadOptions(options)

	if replicaFn != nil {
		if result, ok := r.tryReplicaRead(ctx, replicaFn, options); ok {
			r.stats.replicaReads.Add(1)
			result.Latency = time.Since(start)
			r.emitReadMetrics(result.Latency, "replica", true)
			r.notifyOperationComplete(OpRead, result.Latency, true)
			return result
		}
	}

	return r.doPrimaryRead(ctx, primaryFn, options, start)
}

func (r *ReadWriteRouter) defaultReadOptions(options *ReadOptions) *ReadOptions {
	if options == nil {
		return &ReadOptions{
			Timeout:       defaultReadTimeout,
			AllowFallback: true,
		}
	}
	if options.Timeout == 0 {
		options.Timeout = defaultReadTimeout
	}
	return options
}

func (r *ReadWriteRouter) tryReplicaRead(
	ctx context.Context,
	replicaFn func(ctx context.Context, replica ReplicaInfo) (interface{}, error),
	options *ReadOptions,
) (OperationResult[interface{}], bool) {
	replica, err := r.selectReplica(*options)
	if err != nil {
		return OperationResult[interface{}]{}, false
	}

	if replica.ID == r.primary {
		return OperationResult[interface{}]{}, false
	}

	replicaCtx, cancel := context.WithTimeout(ctx, options.Timeout)
	defer cancel()

	data, err := replicaFn(replicaCtx, *replica)
	if err != nil {
		r.recordFailure(replica.ID)
		logger.Warn("", "replica read failed, falling back to primary", map[string]interface{}{
			"replica_id": replica.ID,
			"error":      err.Error(),
		})
		return OperationResult[interface{}]{}, false
	}

	r.recordSuccess(replica.ID)
	return OperationResult[interface{}]{
		Data:   data,
		Source: replica.ID,
	}, true
}

func (r *ReadWriteRouter) doPrimaryRead(
	ctx context.Context,
	primaryFn func(ctx context.Context) (interface{}, error),
	options *ReadOptions,
	start time.Time,
) OperationResult[interface{}] {
	primaryCtx, cancel := context.WithTimeout(ctx, options.Timeout)
	defer cancel()

	data, err := primaryFn(primaryCtx)
	if err != nil {
		r.stats.failedReads.Add(1)
		r.emitReadMetrics(time.Since(start), "primary", false)
		r.notifyOperationComplete(OpRead, time.Since(start), false)
		return OperationResult[interface{}]{
			Error:   err,
			Latency: time.Since(start),
		}
	}

	r.stats.primaryReads.Add(1)
	r.emitReadMetrics(time.Since(start), "primary", true)
	r.notifyOperationComplete(OpRead, time.Since(start), true)
	return OperationResult[interface{}]{
		Data:    data,
		Source:  "primary",
		Latency: time.Since(start),
	}
}

func (r *ReadWriteRouter) emitReadMetrics(latency time.Duration, source string, success bool) {
	metrics.Observe("slo_routing_read_duration_seconds", latency.Seconds(), map[string]string{
		"source": source,
	})
	if !success {
		metrics.Inc("slo_routing_read_failed_total", nil)
	}
}

func (r *ReadWriteRouter) RouteWrite(
	ctx context.Context,
	operation string,
	params []interface{},
	primaryFn func(ctx context.Context) (interface{}, error),
	options *WriteOptions,
) OperationResult[interface{}] {
	start := time.Now()
	r.stats.totalWrites.Add(1)

	options = r.defaultWriteOptions(options)

	if r.primary == "" {
		r.stats.failedWrites.Add(1)
		r.emitWriteMetrics(time.Since(start), false)
		r.notifyOperationComplete(OpWrite, time.Since(start), false)
		return OperationResult[interface{}]{
			Error:   ErrNoPrimaryAvailable,
			Latency: time.Since(start),
		}
	}

	writeCtx, cancel := context.WithTimeout(ctx, options.Timeout)
	defer cancel()

	data, err := primaryFn(writeCtx)
	if err != nil {
		r.stats.failedWrites.Add(1)
		r.emitWriteMetrics(time.Since(start), false)
		r.notifyOperationComplete(OpWrite, time.Since(start), false)
		return OperationResult[interface{}]{
			Error:   err,
			Latency: time.Since(start),
		}
	}

	r.emitWriteMetrics(time.Since(start), true)
	r.notifyOperationComplete(OpWrite, time.Since(start), true)
	return OperationResult[interface{}]{
		Data:    data,
		Source:  "primary",
		Latency: time.Since(start),
	}
}

func (r *ReadWriteRouter) defaultWriteOptions(options *WriteOptions) *WriteOptions {
	if options == nil {
		return &WriteOptions{
			Timeout:      defaultWriteTimeout,
			SyncReplicas: false,
		}
	}
	if options.Timeout == 0 {
		options.Timeout = defaultWriteTimeout
	}
	return options
}

func (r *ReadWriteRouter) emitWriteMetrics(latency time.Duration, success bool) {
	metrics.Observe("slo_routing_write_duration_seconds", latency.Seconds(), nil)
	if !success {
		metrics.Inc("slo_routing_write_failed_total", nil)
	}
}

func (r *ReadWriteRouter) notifyOperationComplete(opType OperationType, duration time.Duration, success bool) {
	if r.eventBus != nil {
		r.eventBus.OnOperationComplete(opType, duration, success)
	}
}

func (r *ReadWriteRouter) TriggerFailover() (string, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if len(r.replicas) < 2 {
		return "", ErrInsufficientReplicas
	}

	oldPrimary := r.primary
	newPrimary, err := r.findNewPrimaryLocked()
	if err != nil {
		return "", err
	}

	r.primary = newPrimary
	r.stats.failoverCount.Add(1)

	metrics.Inc("slo_routing_failover_total", nil)
	logger.Info("", "SLO router failover completed", map[string]interface{}{
		"old_primary": oldPrimary,
		"new_primary": newPrimary,
	})

	if r.eventBus != nil {
		r.eventBus.OnFailover(oldPrimary, newPrimary)
	}

	return newPrimary, nil
}

func (r *ReadWriteRouter) findNewPrimaryLocked() (string, error) {
	for id, replica := range r.replicas {
		if id != r.primary && replica.Healthy {
			return id, nil
		}
	}
	return "", ErrNoHealthyReplicas
}

func (r *ReadWriteRouter) GetStats() RoutingStats {
	return RoutingStats{
		TotalReads:    r.stats.totalReads.Load(),
		TotalWrites:   r.stats.totalWrites.Load(),
		PrimaryReads:  r.stats.primaryReads.Load(),
		ReplicaReads:  r.stats.replicaReads.Load(),
		FailedReads:   r.stats.failedReads.Load(),
		FailedWrites:  r.stats.failedWrites.Load(),
		FailoverCount: r.stats.failoverCount.Load(),
	}
}

func (r *ReadWriteRouter) SetEventBus(bus RoutingEvents) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.eventBus = bus
}

func (r *ReadWriteRouter) UpdateReplicaHealth(replicaID string, healthy bool, latency time.Duration) {
	r.mu.Lock()
	defer r.mu.Unlock()

	replica, exists := r.replicas[replicaID]
	if !exists {
		return
	}

	oldHealthy := replica.Healthy
	replica.Healthy = healthy
	replica.Latency = latency
	replica.LastCheck = time.Now()

	if oldHealthy != healthy && r.eventBus != nil {
		r.eventBus.OnReplicaHealthChange(replicaID, healthy)
	}

	if healthy {
		r.resetCircuitBreakerLocked(replicaID)
	}

	metrics.Inc("slo_routing_replica_health_check_total", map[string]string{
		"replica_id": replicaID,
		"healthy":    fmt.Sprintf("%t", healthy),
	})
}

func (r *ReadWriteRouter) resetCircuitBreakerLocked(replicaID string) {
	cb := r.circuitBreaker[replicaID]
	if cb != nil {
		cb.failureCount = 0
		cb.open = false
	}
}

func (r *ReadWriteRouter) StartHealthCheck(interval time.Duration, checkFn func(replica ReplicaInfo) (bool, time.Duration)) {
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		for range ticker.C {
			replicas := r.GetAllReplicas()
			for _, replica := range replicas {
				healthy, latency := checkFn(replica)
				r.UpdateReplicaHealth(replica.ID, healthy, latency)
			}
		}
	}()
	logger.Info("", "SLO router health check started", map[string]interface{}{
		"interval_seconds": interval.Seconds(),
	})
}
