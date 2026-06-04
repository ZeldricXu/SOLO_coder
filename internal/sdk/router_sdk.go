package sdk

import (
	"context"
	"encoding/json"
	"fmt"
	"hash/fnv"
	"model-inference-platform/internal/pkg/config"
	"model-inference-platform/internal/pkg/redis"
	"model-inference-platform/internal/pkg/triton"
	"sync"
	"sync/atomic"
	"time"

	"go.uber.org/zap"
)

type LoadBalanceStrategy string

const (
	StrategyRoundRobin     LoadBalanceStrategy = "round_robin"
	StrategyLeastRequests  LoadBalanceStrategy = "least_requests"
	StrategyConsistentHash LoadBalanceStrategy = "consistent_hash"
)

type InstanceInfo struct {
	ID             string `json:"id"`
	Address        string `json:"address"`
	GRPCPort       int    `json:"grpc_port"`
	HTTPPort       int    `json:"http_port"`
	GPUDeviceID    int    `json:"gpu_device_id"`
	CurrentLoad    int    `json:"current_load"`
	ActiveRequests int64  `json:"active_requests"`
	LastHeartbeat  int64  `json:"last_heartbeat"`
}

type SDKRequest struct {
	RequestID  string
	TraceID    string
	ModelName  string
	Version    string
	Namespace  string
	Inputs     []*triton.InferenceTensor
	InputHash  string
	Timeout    time.Duration
	RetryCount int
	MaxRetries int
}

type SDKResponse struct {
	RequestID  string
	Outputs    []*triton.InferenceTensor
	Latency    time.Duration
	Error      string
	InstanceID string
}

type ConnectionPool struct {
	clients    map[string]triton.TritonClient
	clientsMu  sync.RWMutex
	maxConns   int
	logger     *zap.Logger
}

func NewConnectionPool(maxConns int, logger *zap.Logger) *ConnectionPool {
	return &ConnectionPool{
		clients:   make(map[string]triton.TritonClient),
		maxConns:  maxConns,
		logger:    logger,
	}
}

func (p *ConnectionPool) Get(address string, grpcPort int) (triton.TritonClient, error) {
	key := fmt.Sprintf("%s:%d", address, grpcPort)

	p.clientsMu.RLock()
	client, ok := p.clients[key]
	p.clientsMu.RUnlock()

	if ok {
		return client, nil
	}

	p.clientsMu.Lock()
	defer p.clientsMu.Unlock()

	if client, ok := p.clients[key]; ok {
		return client, nil
	}

	if len(p.clients) >= p.maxConns {
		p.evictOldest()
	}

	client, err := triton.NewClient(config.TritonConfig{
		GRPCHost: address,
		GRPCPort: grpcPort,
	})
	if err != nil {
		return nil, fmt.Errorf("failed to create triton client: %w", err)
	}

	p.clients[key] = client
	return client, nil
}

func (p *ConnectionPool) evictOldest() {
	var oldestKey string
	for k := range p.clients {
		oldestKey = k
		break
	}
	if client, ok := p.clients[oldestKey]; ok {
		client.Close()
		delete(p.clients, oldestKey)
	}
}

func (p *ConnectionPool) Close() {
	p.clientsMu.Lock()
	defer p.clientsMu.Unlock()
	for _, client := range p.clients {
		client.Close()
	}
	p.clients = make(map[string]triton.TritonClient)
}

type RouterSDK struct {
	redisClient  *redis.Client
	connPool     *ConnectionPool
	logger       *zap.Logger
	strategy     LoadBalanceStrategy

	instancesCache   map[string][]*InstanceInfo
	instancesCacheMu sync.RWMutex
	cacheTTL         time.Duration
	lastRefresh      time.Time

	roundRobinC  map[string]*uint32
	rrMu         sync.Mutex

	refreshInterval time.Duration
	stopCh          chan struct{}
	wg              sync.WaitGroup
}

type RouterSDKConfig struct {
	RedisConfig     config.RedisConfig
	Strategy        LoadBalanceStrategy
	RefreshInterval time.Duration
	MaxConnections  int
	CacheTTL        time.Duration
}

func NewRouterSDK(cfg RouterSDKConfig, logger *zap.Logger) (*RouterSDK, error) {
	redisClient, err := redis.New(cfg.RedisConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to create redis client: %w", err)
	}

	if cfg.RefreshInterval <= 0 {
		cfg.RefreshInterval = 5 * time.Second
	}
	if cfg.MaxConnections <= 0 {
		cfg.MaxConnections = 100
	}
	if cfg.CacheTTL <= 0 {
		cfg.CacheTTL = 3 * time.Second
	}
	if cfg.Strategy == "" {
		cfg.Strategy = StrategyLeastRequests
	}

	sdk := &RouterSDK{
		redisClient:     redisClient,
		connPool:        NewConnectionPool(cfg.MaxConnections, logger),
		logger:          logger,
		strategy:        cfg.Strategy,
		instancesCache:  make(map[string][]*InstanceInfo),
		roundRobinC:     make(map[string]*uint32),
		refreshInterval: cfg.RefreshInterval,
		cacheTTL:        cfg.CacheTTL,
		stopCh:          make(chan struct{}),
	}

	if err := sdk.refreshInstances(context.Background()); err != nil {
		logger.Warn("Failed to refresh instances on startup", zap.Error(err))
	}

	go sdk.refreshLoop()

	return sdk, nil
}

func (s *RouterSDK) SetStrategy(strategy LoadBalanceStrategy) {
	s.strategy = strategy
}

func (s *RouterSDK) refreshLoop() {
	s.wg.Add(1)
	defer s.wg.Done()

	ticker := time.NewTicker(s.refreshInterval)
	defer ticker.Stop()

	for {
		select {
		case <-s.stopCh:
			return
		case <-ticker.C:
			if err := s.refreshInstances(context.Background()); err != nil {
				s.logger.Warn("Failed to refresh instances", zap.Error(err))
			}
		}
	}
}

func (s *RouterSDK) refreshInstances(ctx context.Context) error {
	keys, err := s.redisClient.Keys(ctx, "instances:*")
	if err != nil {
		return fmt.Errorf("failed to get instance keys: %w", err)
	}

	newCache := make(map[string][]*InstanceInfo)

	for _, key := range keys {
		fields, err := s.redisClient.HGetAll(ctx, key)
		if err != nil {
			continue
		}

		var instances []*InstanceInfo
		for _, data := range fields {
			var info InstanceInfo
			if err := json.Unmarshal([]byte(data), &info); err != nil {
				continue
			}
			if time.Since(time.Unix(info.LastHeartbeat, 0)) < 60*time.Second {
				instances = append(instances, &info)
			}
		}

		if len(instances) > 0 {
			modelKey := key[len("instances:"):]
			newCache[modelKey] = instances
		}
	}

	s.instancesCacheMu.Lock()
	s.instancesCache = newCache
	s.lastRefresh = time.Now()
	s.instancesCacheMu.Unlock()

	return nil
}

func (s *RouterSDK) getInstances(modelName, version string) ([]*InstanceInfo, error) {
	key := fmt.Sprintf("%s:%s", modelName, version)

	s.instancesCacheMu.RLock()
	instances, ok := s.instancesCache[key]
	expired := time.Since(s.lastRefresh) > s.cacheTTL
	s.instancesCacheMu.RUnlock()

	if !ok || expired {
		if err := s.refreshInstances(context.Background()); err != nil {
			return nil, err
		}

		s.instancesCacheMu.RLock()
		instances, ok = s.instancesCache[key]
		s.instancesCacheMu.RUnlock()
	}

	if !ok || len(instances) == 0 {
		return nil, fmt.Errorf("no ready instances for %s:%s", modelName, version)
	}

	return instances, nil
}

func (s *RouterSDK) Infer(ctx context.Context, req *SDKRequest) (*SDKResponse, error) {
	if req.Timeout <= 0 {
		req.Timeout = 30 * time.Second
	}

	instances, err := s.getInstances(req.ModelName, req.Version)
	if err != nil {
		return &SDKResponse{
			RequestID: req.RequestID,
			Error:     err.Error(),
		}, err
	}

	selected := s.selectInstance(req, instances)

	resp, err := s.sendToInstance(ctx, req, selected)
	if err != nil && req.RetryCount < req.MaxRetries {
		s.logger.Warn("Request failed, retrying",
			zap.String("request_id", req.RequestID),
			zap.Int("retry", req.RetryCount+1),
			zap.Error(err))

		req.RetryCount++
		return s.Infer(ctx, req)
	}

	if err != nil {
		return &SDKResponse{
			RequestID: req.RequestID,
			Error:     err.Error(),
		}, err
	}

	return resp, nil
}

func (s *RouterSDK) selectInstance(req *SDKRequest, instances []*InstanceInfo) *InstanceInfo {
	switch s.strategy {
	case StrategyRoundRobin:
		return s.roundRobin(req.ModelName, req.Version, instances)
	case StrategyLeastRequests:
		return s.leastRequests(instances)
	case StrategyConsistentHash:
		return s.consistentHash(req.InputHash, instances)
	default:
		return s.leastRequests(instances)
	}
}

func (s *RouterSDK) roundRobin(modelName, version string, instances []*InstanceInfo) *InstanceInfo {
	key := fmt.Sprintf("%s:%s", modelName, version)

	s.rrMu.Lock()
	counter, ok := s.roundRobinC[key]
	if !ok {
		c := uint32(0)
		counter = &c
		s.roundRobinC[key] = counter
	}
	idx := atomic.AddUint32(counter, 1) % uint32(len(instances))
	s.rrMu.Unlock()

	return instances[idx]
}

func (s *RouterSDK) leastRequests(instances []*InstanceInfo) *InstanceInfo {
	minIdx := 0
	minLoad := instances[0].ActiveRequests

	for i := 1; i < len(instances); i++ {
		if instances[i].ActiveRequests < minLoad {
			minLoad = instances[i].ActiveRequests
			minIdx = i
		}
	}

	return instances[minIdx]
}

func (s *RouterSDK) consistentHash(inputHash string, instances []*InstanceInfo) *InstanceInfo {
	h := fnv.New32a()
	h.Write([]byte(inputHash))
	hash := h.Sum32()
	idx := hash % uint32(len(instances))
	return instances[idx]
}

func (s *RouterSDK) sendToInstance(ctx context.Context, req *SDKRequest, instance *InstanceInfo) (*SDKResponse, error) {
	start := time.Now()

	client, err := s.connPool.Get("localhost", instance.GRPCPort)
	if err != nil {
		return nil, fmt.Errorf("failed to get connection: %w", err)
	}

	timeoutCtx, cancel := context.WithTimeout(ctx, req.Timeout)
	defer cancel()

	outputNames := make([]string, 0, len(req.Inputs))
	for range req.Inputs {
		outputNames = append(outputNames, "output")
	}
	if len(outputNames) == 0 {
		outputNames = []string{"output"}
	}

	result, err := client.Infer(timeoutCtx, req.ModelName, req.Version, req.Inputs, outputNames)
	if err != nil {
		s.logger.Error("Inference failed",
			zap.String("instance", instance.ID),
			zap.String("model", req.ModelName),
			zap.Error(err))
		return nil, err
	}

	latency := time.Since(start)

	return &SDKResponse{
		RequestID:  req.RequestID,
		Outputs:    result.Outputs,
		Latency:    latency,
		InstanceID: instance.ID,
	}, nil
}

func (s *RouterSDK) Close() {
	close(s.stopCh)
	s.wg.Wait()
	s.connPool.Close()
	s.redisClient.Close()
	s.logger.Info("RouterSDK stopped")
}
