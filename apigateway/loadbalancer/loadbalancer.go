package loadbalancer

import (
	"apigateway/models"
	"fmt"
	"math/rand"
	"sync"
	"sync/atomic"
	"time"
)

type LoadBalancer struct {
	services       map[string]*models.LoadBalancerConfig
	roundRobinIdx  map[string]*int64
	weightedIdx    map[string]*int
	mu             sync.RWMutex
}

func NewLoadBalancer() *LoadBalancer {
	rand.Seed(time.Now().UnixNano())
	return &LoadBalancer{
		services:      make(map[string]*models.LoadBalancerConfig),
		roundRobinIdx: make(map[string]*int64),
		weightedIdx:   make(map[string]*int),
	}
}

func (lb *LoadBalancer) RegisterService(config *models.LoadBalancerConfig) error {
	if config == nil || config.ServiceName == "" {
		return fmt.Errorf("invalid load balancer config")
	}

	lb.mu.Lock()
	defer lb.mu.Unlock()

	lb.services[config.ServiceName] = config

	if _, exists := lb.roundRobinIdx[config.ServiceName]; !exists {
		var idx int64 = 0
		lb.roundRobinIdx[config.ServiceName] = &idx
	}

	if _, exists := lb.weightedIdx[config.ServiceName]; !exists {
		idx := 0
		lb.weightedIdx[config.ServiceName] = &idx
	}

	return nil
}

func (lb *LoadBalancer) UpdateService(config *models.LoadBalancerConfig) error {
	return lb.RegisterService(config)
}

func (lb *LoadBalancer) UnregisterService(serviceName string) {
	lb.mu.Lock()
	defer lb.mu.Unlock()

	delete(lb.services, serviceName)
	delete(lb.roundRobinIdx, serviceName)
	delete(lb.weightedIdx, serviceName)
}

func (lb *LoadBalancer) GetServiceConfig(serviceName string) (*models.LoadBalancerConfig, bool) {
	lb.mu.RLock()
	defer lb.mu.RUnlock()

	config, exists := lb.services[serviceName]
	return config, exists
}

func (lb *LoadBalancer) SelectInstance(serviceName string, algorithm string) (*models.ServiceInstance, error) {
	lb.mu.RLock()
	config, exists := lb.services[serviceName]
	lb.mu.RUnlock()

	if !exists {
		return nil, fmt.Errorf("service not registered: %s", serviceName)
	}

	healthyInstances := make([]models.ServiceInstance, 0)
	for _, inst := range config.Instances {
		if inst.Healthy {
			healthyInstances = append(healthyInstances, inst)
		}
	}

	if len(healthyInstances) == 0 {
		return nil, fmt.Errorf("no healthy instances available for service: %s", serviceName)
	}

	useAlgorithm := algorithm
	if useAlgorithm == "" {
		useAlgorithm = config.BalanceAlgorithm
	}
	if useAlgorithm == "" {
		useAlgorithm = "round_robin"
	}

	switch useAlgorithm {
	case "round_robin":
		return lb.selectRoundRobin(serviceName, healthyInstances)
	case "weighted_round_robin":
		return lb.selectWeightedRoundRobin(serviceName, healthyInstances)
	case "random":
		return lb.selectRandom(healthyInstances)
	case "least_connections":
		return lb.selectLeastConnections(healthyInstances)
	default:
		return lb.selectRoundRobin(serviceName, healthyInstances)
	}
}

func (lb *LoadBalancer) selectRoundRobin(serviceName string, instances []models.ServiceInstance) (*models.ServiceInstance, error) {
	lb.mu.RLock()
	idxPtr, exists := lb.roundRobinIdx[serviceName]
	lb.mu.RUnlock()

	if !exists {
		var idx int64 = 0
		lb.mu.Lock()
		lb.roundRobinIdx[serviceName] = &idx
		lb.mu.Unlock()
		idxPtr = &idx
	}

	idx := atomic.AddInt64(idxPtr, 1) - 1
	selectedIdx := int(idx) % len(instances)
	return &instances[selectedIdx], nil
}

func (lb *LoadBalancer) selectWeightedRoundRobin(serviceName string, instances []models.ServiceInstance) (*models.ServiceInstance, error) {
	lb.mu.Lock()
	defer lb.mu.Unlock()

	idxPtr, exists := lb.weightedIdx[serviceName]
	if !exists {
		idx := 0
		lb.weightedIdx[serviceName] = &idx
		idxPtr = &idx
	}

	totalWeight := 0
	for _, inst := range instances {
		if inst.Weight <= 0 {
			totalWeight += 1
		} else {
			totalWeight += inst.Weight
		}
	}

	currentIdx := *idxPtr % totalWeight
	*idxPtr = (*idxPtr + 1) % totalWeight

	weightSum := 0
	for _, inst := range instances {
		weight := inst.Weight
		if weight <= 0 {
			weight = 1
		}
		weightSum += weight
		if currentIdx < weightSum {
			return &inst, nil
		}
	}

	return &instances[0], nil
}

func (lb *LoadBalancer) selectRandom(instances []models.ServiceInstance) (*models.ServiceInstance, error) {
	idx := rand.Intn(len(instances))
	return &instances[idx], nil
}

func (lb *LoadBalancer) selectLeastConnections(instances []models.ServiceInstance) (*models.ServiceInstance, error) {
	if len(instances) == 0 {
		return nil, fmt.Errorf("no instances available")
	}
	return &instances[0], nil
}

func (lb *LoadBalancer) UpdateInstanceHealth(serviceName, instanceID string, healthy bool) error {
	lb.mu.Lock()
	defer lb.mu.Unlock()

	config, exists := lb.services[serviceName]
	if !exists {
		return fmt.Errorf("service not registered: %s", serviceName)
	}

	for i := range config.Instances {
		if config.Instances[i].InstanceID == instanceID {
			config.Instances[i].Healthy = healthy
			return nil
		}
	}

	return fmt.Errorf("instance not found: %s", instanceID)
}

func (lb *LoadBalancer) AddInstance(serviceName string, instance models.ServiceInstance) error {
	lb.mu.Lock()
	defer lb.mu.Unlock()

	config, exists := lb.services[serviceName]
	if !exists {
		return fmt.Errorf("service not registered: %s", serviceName)
	}

	for _, inst := range config.Instances {
		if inst.InstanceID == instance.InstanceID {
			return fmt.Errorf("instance already exists: %s", instance.InstanceID)
		}
	}

	config.Instances = append(config.Instances, instance)
	return nil
}

func (lb *LoadBalancer) RemoveInstance(serviceName, instanceID string) error {
	lb.mu.Lock()
	defer lb.mu.Unlock()

	config, exists := lb.services[serviceName]
	if !exists {
		return fmt.Errorf("service not registered: %s", serviceName)
	}

	for i, inst := range config.Instances {
		if inst.InstanceID == instanceID {
			config.Instances = append(config.Instances[:i], config.Instances[i+1:]...)
			return nil
		}
	}

	return fmt.Errorf("instance not found: %s", instanceID)
}

func (lb *LoadBalancer) ListServices() []string {
	lb.mu.RLock()
	defer lb.mu.RUnlock()

	services := make([]string, 0, len(lb.services))
	for name := range lb.services {
		services = append(services, name)
	}
	return services
}

func (lb *LoadBalancer) GetHealthyInstances(serviceName string) []models.ServiceInstance {
	lb.mu.RLock()
	defer lb.mu.RUnlock()

	config, exists := lb.services[serviceName]
	if !exists {
		return nil
	}

	healthy := make([]models.ServiceInstance, 0)
	for _, inst := range config.Instances {
		if inst.Healthy {
			healthy = append(healthy, inst)
		}
	}
	return healthy
}
