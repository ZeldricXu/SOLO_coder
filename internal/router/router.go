package router

import (
	"context"
	"encoding/json"
	"fmt"
	"hash/fnv"
	"model-inference-platform/internal/orchestrator"
	"model-inference-platform/internal/pkg/config"
	"model-inference-platform/internal/pkg/redis"
	"sync"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"
)

type LoadBalanceStrategy string

const (
	StrategyRoundRobin        LoadBalanceStrategy = "round_robin"
	StrategyLeastRequests     LoadBalanceStrategy = "least_requests"
	StrategyConsistentHash    LoadBalanceStrategy = "consistent_hash"
)

type RouteTableEntry struct {
	InstanceID   string `json:"instance_id"`
	Address      string `json:"address"`
	GRPCPort     int    `json:"grpc_port"`
	HTTPPort     int    `json:"http_port"`
	GPUDeviceID  int    `json:"gpu_device_id"`
	CurrentLoad  int    `json:"current_load"`
	ActiveRequests int64 `json:"active_requests"`
	LastHeartbeat int64 `json:"last_heartbeat"`
}

type Router struct {
	cfg          config.Config
	orchestrator *orchestrator.Orchestrator
	redisClient  redis.RedisClient
	logger       *zap.Logger

	abTestRouter *ABTestRouter

	stopCh       chan struct{}
	wg           sync.WaitGroup
}

func New(cfg config.Config, orch *orchestrator.Orchestrator, redisClient redis.RedisClient,
	logger *zap.Logger) *Router {
	return &Router{
		cfg:          cfg,
		orchestrator: orch,
		redisClient:  redisClient,
		logger:       logger,
		abTestRouter: NewABTestRouter(redisClient, logger),
		stopCh:       make(chan struct{}),
	}
}

func (r *Router) Start(ctx context.Context) error {
	r.wg.Add(1)
	go r.syncRouteTableLoop(ctx)
	r.logger.Info("Router (route table manager) started")
	return nil
}

func (r *Router) Stop() {
	close(r.stopCh)
	r.wg.Wait()
	r.logger.Info("Router stopped")
}

func (r *Router) syncRouteTableLoop(ctx context.Context) {
	defer r.wg.Done()

	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-r.stopCh:
			return
		case <-ticker.C:
			r.syncRouteTable(ctx)
		}
	}
}

func (r *Router) syncRouteTable(ctx context.Context) {
	instances := r.orchestrator.GetAllInstances()

	routeTable := make(map[string][]*RouteTableEntry)
	activeInstanceIDs := make(map[string]map[string]bool)
	for _, inst := range instances {
		if inst.Status == orchestrator.InstanceReady {
			key := fmt.Sprintf("%s:%s", inst.ModelName, inst.Version)
			routeTable[key] = append(routeTable[key], &RouteTableEntry{
				InstanceID:    inst.ID,
				Address:       inst.Address,
				GRPCPort:      inst.GRPCPort,
				HTTPPort:      inst.HTTPPort,
				GPUDeviceID:   inst.GPUDeviceID,
				CurrentLoad:   inst.CurrentLoad,
				ActiveRequests: inst.ActiveRequests,
				LastHeartbeat: inst.LastHeartbeat.Unix(),
			})
			if _, ok := activeInstanceIDs[key]; !ok {
				activeInstanceIDs[key] = make(map[string]bool)
			}
			activeInstanceIDs[key][inst.ID] = true
		}
	}

	for key, entries := range routeTable {
		redisKey := fmt.Sprintf("route:%s", key)
		existing, err := r.redisClient.HGetAll(ctx, redisKey)
		if err == nil {
			for id := range existing {
				if !activeInstanceIDs[key][id] {
					r.redisClient.HDel(ctx, redisKey, id)
				}
			}
		}
		for _, entry := range entries {
			data, _ := json.Marshal(entry)
			r.redisClient.HSet(ctx, redisKey, entry.InstanceID, string(data))
		}
		r.redisClient.Expire(ctx, redisKey, 30*time.Second)
	}
}

func (r *Router) GetRouteTable(ctx context.Context, modelName, version string) ([]*RouteTableEntry, error) {
	key := fmt.Sprintf("route:%s:%s", modelName, version)
	fields, err := r.redisClient.HGetAll(ctx, key)
	if err != nil {
		return nil, err
	}

	var entries []*RouteTableEntry
	for _, data := range fields {
		var entry RouteTableEntry
		if err := json.Unmarshal([]byte(data), &entry); err != nil {
			continue
		}
		if time.Since(time.Unix(entry.LastHeartbeat, 0)) < 60*time.Second {
			entries = append(entries, &entry)
		}
	}
	return entries, nil
}

func (r *Router) SelectABTestVersion(ctx context.Context, namespace, modelName, defaultVersion string) (string, error) {
	return r.abTestRouter.SelectVersion(ctx, namespace, modelName, defaultVersion)
}

func (r *Router) AddABTestConfig(cfg *ABTestConfig) {
	r.abTestRouter.AddConfig(cfg)
}

func (r *Router) RemoveABTestConfig(namespace, modelName string) {
	r.abTestRouter.RemoveConfig(namespace, modelName)
}

type ABTestRouter struct {
	redisClient redis.RedisClient
	logger      *zap.Logger
	configs     map[string]*ABTestConfig
	mu          sync.RWMutex
}

type ABTestConfig struct {
	ID            string
	ModelName     string
	Namespace     string
	VersionA      string
	VersionB      string
	TrafficSplitA int
	TrafficSplitB int
	Active        bool
}

func NewABTestRouter(redisClient redis.RedisClient, logger *zap.Logger) *ABTestRouter {
	return &ABTestRouter{
		redisClient: redisClient,
		logger:      logger,
		configs:     make(map[string]*ABTestConfig),
	}
}

func (a *ABTestRouter) SelectVersion(ctx context.Context, namespace, modelName, defaultVersion string) (string, error) {
	key := fmt.Sprintf("%s:%s", namespace, modelName)

	a.mu.RLock()
	cfg, ok := a.configs[key]
	a.mu.RUnlock()

	if !ok || !cfg.Active {
		return defaultVersion, nil
	}

	requestID := uuid.New().String()
	h := fnv.New32a()
	h.Write([]byte(requestID))
	hash := int(h.Sum32() % 100)

	if hash < cfg.TrafficSplitA {
		return cfg.VersionA, nil
	}
	return cfg.VersionB, nil
}

func (a *ABTestRouter) AddConfig(cfg *ABTestConfig) {
	key := fmt.Sprintf("%s:%s", cfg.Namespace, cfg.ModelName)
	a.mu.Lock()
	a.configs[key] = cfg
	a.mu.Unlock()
}

func (a *ABTestRouter) RemoveConfig(namespace, modelName string) {
	key := fmt.Sprintf("%s:%s", namespace, modelName)
	a.mu.Lock()
	delete(a.configs, key)
	a.mu.Unlock()
}
