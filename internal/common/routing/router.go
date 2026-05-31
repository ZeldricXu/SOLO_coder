package routing

import (
	"context"
	"errors"
	"sync"
	"time"
)

type ReadWriteMode int

const (
	ModeAuto      ReadWriteMode = iota
	ModeReadOnly
	ModeWriteOnly
	ModeReadWrite
)

var ErrInvalidMode = errors.New("invalid read-write mode")

type RouteType int

const (
	RouteRead  RouteType = iota
	RouteWrite
)

type RoutingStrategyType string

const (
	StrategyRoundRobin      RoutingStrategyType = "round_robin"
	StrategyLeastConnection RoutingStrategyType = "least_connection"
	StrategyWeightedRandom  RoutingStrategyType = "weighted_random"
)

type DatabaseRole string

const (
	RolePrimary DatabaseRole = "primary"
	RoleReplica DatabaseRole = "replica"
)

type DatabaseNode struct {
	ID       string
	Addr     string
	Role     string
	Weight   int
	IsHealthy bool
	LastCheck time.Time
}

type RoutingStrategy interface {
	Select(ctx context.Context, nodes []DatabaseNode, routeType RouteType) (*DatabaseNode, error)
}

type RoundRobinStrategy struct {
	mu       sync.Mutex
	counters map[RouteType]int
}

func NewRoundRobinStrategy() *RoundRobinStrategy {
	return &RoundRobinStrategy{
		counters: make(map[RouteType]int),
	}
}

func (s *RoundRobinStrategy) Select(ctx context.Context, nodes []DatabaseNode, routeType RouteType) (*DatabaseNode, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	healthyNodes := make([]DatabaseNode, 0)
	for _, node := range nodes {
		if node.IsHealthy {
			if routeType == RouteWrite && node.Role != "primary" {
				continue
			}
			healthyNodes = append(healthyNodes, node)
		}
	}

	if len(healthyNodes) == 0 {
		return nil, errors.New("no healthy nodes available")
	}

	counter := s.counters[routeType]
	node := healthyNodes[counter%len(healthyNodes)]
	s.counters[routeType] = counter + 1

	return &node, nil
}

type LeastConnectionStrategy struct {
	connections map[string]int
	mu          sync.Mutex
}

func NewLeastConnectionStrategy() *LeastConnectionStrategy {
	return &LeastConnectionStrategy{
		connections: make(map[string]int),
	}
}

func (s *LeastConnectionStrategy) Select(ctx context.Context, nodes []DatabaseNode, routeType RouteType) (*DatabaseNode, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	var selected *DatabaseNode
	minConns := -1

	for i := range nodes {
		node := nodes[i]
		if !node.IsHealthy {
			continue
		}
		if routeType == RouteWrite && node.Role != "primary" {
			continue
		}

		conns := s.connections[node.ID]
		if minConns == -1 || conns < minConns {
			minConns = conns
			selected = &node
		}
	}

	if selected == nil {
		return nil, errors.New("no healthy nodes available")
	}

	s.connections[selected.ID]++
	return selected, nil
}

type WeightedRandomStrategy struct{}

func NewWeightedRandomStrategy() *WeightedRandomStrategy {
	return &WeightedRandomStrategy{}
}

func (s *WeightedRandomStrategy) Select(ctx context.Context, nodes []DatabaseNode, routeType RouteType) (*DatabaseNode, error) {
	healthyNodes := make([]DatabaseNode, 0)
	totalWeight := 0

	for _, node := range nodes {
		if !node.IsHealthy {
			continue
		}
		if routeType == RouteWrite && node.Role != "primary" {
			continue
		}
		healthyNodes = append(healthyNodes, node)
		totalWeight += node.Weight
	}

	if len(healthyNodes) == 0 {
		return nil, errors.New("no healthy nodes available")
	}

	randomValue := time.Now().UnixNano() % int64(totalWeight)
	cumulative := 0

	for _, node := range healthyNodes {
		cumulative += node.Weight
		if int64(cumulative) > randomValue {
			return &node, nil
		}
	}

	return &healthyNodes[0], nil
}

type ReadWriteRouter struct {
	nodes        []DatabaseNode
	strategy     RoutingStrategy
	mode         ReadWriteMode
	mu           sync.RWMutex
	healthChecks map[string]*HealthCheckConfig
}

type HealthCheckConfig struct {
	Enabled  bool
	Interval time.Duration
	Timeout  time.Duration
	MaxFails int
}

type RouterConfig struct {
	PrimaryNode     DatabaseNode
	ReplicaNodes    []DatabaseNode
	Strategy        RoutingStrategy
	HealthCheck     HealthCheckConfig
	ForceReadFromPrimary bool
}

func NewReadWriteRouter(cfg RouterConfig) *ReadWriteRouter {
	nodes := make([]DatabaseNode, 0)
	if cfg.PrimaryNode.ID != "" {
		cfg.PrimaryNode.Role = "primary"
		cfg.PrimaryNode.IsHealthy = true
		nodes = append(nodes, cfg.PrimaryNode)
	}
	for i := range cfg.ReplicaNodes {
		cfg.ReplicaNodes[i].Role = "replica"
		cfg.ReplicaNodes[i].IsHealthy = true
		nodes = append(nodes, cfg.ReplicaNodes[i])
	}

	strategy := cfg.Strategy
	if strategy == nil {
		strategy = NewRoundRobinStrategy()
	}

	return &ReadWriteRouter{
		nodes:    nodes,
		strategy: strategy,
		mode:     ModeAuto,
	}
}

func (r *ReadWriteRouter) Route(ctx context.Context, routeType RouteType) (*DatabaseNode, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	if r.mode == ModeReadonly && routeType == RouteWrite {
		return nil, errors.New("router is in readonly mode")
	}
	if r.mode == ModeWriteonly && routeType == RouteRead {
		return nil, errors.New("router is in writeonly mode")
	}

	return r.strategy.Select(ctx, r.nodes, routeType)
}

func (r *ReadWriteRouter) SetMode(mode ReadWriteMode) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.mode = mode
}

func (r *ReadWriteRouter) GetMode() ReadWriteMode {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.mode
}

func (r *ReadWriteRouter) UpdateNodeHealth(nodeID string, healthy bool) {
	r.mu.Lock()
	defer r.mu.Unlock()

	for i := range r.nodes {
		if r.nodes[i].ID == nodeID {
			r.nodes[i].IsHealthy = healthy
			r.nodes[i].LastCheck = time.Now()
			break
		}
	}
}

func (r *ReadWriteRouter) AddNode(node DatabaseNode) {
	r.mu.Lock()
	defer r.mu.Unlock()

	node.IsHealthy = true
	node.LastCheck = time.Now()
	r.nodes = append(r.nodes, node)
}

func (r *ReadWriteRouter) RemoveNode(nodeID string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	newNodes := make([]DatabaseNode, 0)
	for _, node := range r.nodes {
		if node.ID != nodeID {
			newNodes = append(newNodes, node)
		}
	}
	r.nodes = newNodes
}

func (r *ReadWriteRouter) GetNodes() []DatabaseNode {
	r.mu.RLock()
	defer r.mu.RUnlock()

	nodes := make([]DatabaseNode, len(r.nodes))
	copy(nodes, r.nodes)
	return nodes
}

func (r *ReadWriteRouter) SetStrategy(strategy RoutingStrategy) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.strategy = strategy
}

func (r *ReadWriteRouter) GetStrategy() RoutingStrategyType {
	r.mu.RLock()
	defer r.mu.RUnlock()

	switch r.strategy.(type) {
	case *RoundRobinStrategy:
		return StrategyRoundRobin
	case *LeastConnectionStrategy:
		return StrategyLeastConnection
	case *WeightedRandomStrategy:
		return StrategyWeightedRandom
	default:
		return StrategyRoundRobin
	}
}

func (r *ReadWriteRouter) GetStats() map[string]interface{} {
	r.mu.RLock()
	defer r.mu.RUnlock()

	primaryCount := 0
	replicaCount := 0
	healthyCount := 0

	for _, node := range r.nodes {
		if node.Role == string(RolePrimary) {
			primaryCount++
		} else {
			replicaCount++
		}
		if node.IsHealthy {
			healthyCount++
		}
	}

	return map[string]interface{}{
		"total_nodes":   len(r.nodes),
		"primary_count": primaryCount,
		"replica_count": replicaCount,
		"healthy_count": healthyCount,
		"mode":          r.mode,
	}
}

func (r *ReadWriteRouter) StartHealthCheck(ctx context.Context) {
	ticker := time.NewTicker(time.Second * 10)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			r.performHealthCheck(ctx)
		}
	}
}

func (r *ReadWriteRouter) performHealthCheck(ctx context.Context) {
	r.mu.Lock()
	nodes := make([]DatabaseNode, len(r.nodes))
	copy(nodes, r.nodes)
	r.mu.Unlock()

	for _, node := range nodes {
		healthy := true
		if node.Role == "replica" {
			healthy = r.checkReplicaHealth(ctx, node)
		} else {
			healthy = r.checkPrimaryHealth(ctx, node)
		}
		r.UpdateNodeHealth(node.ID, healthy)
	}
}

func (r *ReadWriteRouter) checkReplicaHealth(ctx context.Context, node DatabaseNode) bool {
	return true
}

func (r *ReadWriteRouter) checkPrimaryHealth(ctx context.Context, node DatabaseNode) bool {
	return true
}

type ReadReplicaBalancer struct {
	router *ReadWriteRouter
}

func NewReadReplicaBalancer(router *ReadWriteRouter) *ReadReplicaBalancer {
	return &ReadReplicaBalancer{router: router}
}

func (b *ReadReplicaBalancer) GetReadNode(ctx context.Context) (*DatabaseNode, error) {
	return b.router.Route(ctx, RouteRead)
}

func (b *ReadReplicaBalancer) GetWriteNode(ctx context.Context) (*DatabaseNode, error) {
	return b.router.Route(ctx, RouteWrite)
}
