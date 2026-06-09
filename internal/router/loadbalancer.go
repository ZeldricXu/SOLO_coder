package router

import (
	"fmt"
	"hash/crc32"
	"math/rand"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"DF1-56/internal/models"
)

type LoadBalancer struct {
	roundRobinIndex map[string]uint64
	weightedIndex   map[string]int64
	connections     map[string]*int64
	mu              sync.RWMutex
	rand            *rand.Rand
}

func NewLoadBalancer() *LoadBalancer {
	return &LoadBalancer{
		roundRobinIndex: make(map[string]uint64),
		weightedIndex:   make(map[string]int64),
		connections:     make(map[string]*int64),
		rand:            rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

func (lb *LoadBalancer) SelectNode(cluster *models.UpstreamCluster, clientIP string) *models.UpstreamNode {
	if cluster == nil {
		return nil
	}

	healthyNodes := lb.getHealthyNodes(cluster)
	if len(healthyNodes) == 0 {
		return nil
	}

	switch cluster.LoadBalancer {
	case models.LoadBalancerRoundRobin:
		return lb.roundRobin(cluster.ID, healthyNodes)
	case models.LoadBalancerWeighted:
		return lb.weightedRoundRobin(cluster.ID, healthyNodes)
	case models.LoadBalancerIPHash:
		return lb.ipHash(healthyNodes, clientIP)
	case models.LoadBalancerRandom:
		return lb.random(healthyNodes)
	case models.LoadBalancerLeastConn:
		return lb.leastConn(cluster.ID, healthyNodes)
	default:
		return lb.roundRobin(cluster.ID, healthyNodes)
	}
}

func (lb *LoadBalancer) getHealthyNodes(cluster *models.UpstreamCluster) []*models.UpstreamNode {
	healthy := make([]*models.UpstreamNode, 0, len(cluster.Nodes))
	for _, node := range cluster.Nodes {
		if node != nil && node.Healthy {
			healthy = append(healthy, node)
		}
	}
	return healthy
}

func (lb *LoadBalancer) roundRobin(clusterID string, nodes []*models.UpstreamNode) *models.UpstreamNode {
	if len(nodes) == 0 {
		return nil
	}

	lb.mu.Lock()
	defer lb.mu.Unlock()

	idx, exists := lb.roundRobinIndex[clusterID]
	if !exists {
		idx = 0
	}

	node := nodes[idx%uint64(len(nodes))]
	lb.roundRobinIndex[clusterID] = idx + 1

	return node
}

func (lb *LoadBalancer) weightedRoundRobin(clusterID string, nodes []*models.UpstreamNode) *models.UpstreamNode {
	if len(nodes) == 0 {
		return nil
	}

	lb.mu.Lock()
	defer lb.mu.Unlock()

	totalWeight := 0
	for _, node := range nodes {
		if node.Weight <= 0 {
			node.Weight = 1
		}
		totalWeight += node.Weight
	}

	if totalWeight == 0 {
		return lb.random(nodes)
	}

	currentIdx, exists := lb.weightedIndex[clusterID]
	if !exists {
		currentIdx = -1
	}

	currentIdx = (currentIdx + 1) % int64(totalWeight)
	lb.weightedIndex[clusterID] = currentIdx

	accumulated := int64(0)
	for _, node := range nodes {
		accumulated += int64(node.Weight)
		if currentIdx < accumulated {
			return node
		}
	}

	return nodes[len(nodes)-1]
}

func (lb *LoadBalancer) ipHash(nodes []*models.UpstreamNode, clientIP string) *models.UpstreamNode {
	if len(nodes) == 0 {
		return nil
	}

	if clientIP == "" {
		return lb.random(nodes)
	}

	ip := net.ParseIP(clientIP)
	if ip == nil {
		return lb.random(nodes)
	}

	hash := crc32.ChecksumIEEE([]byte(clientIP))
	idx := int(hash) % len(nodes)

	return nodes[idx]
}

func (lb *LoadBalancer) random(nodes []*models.UpstreamNode) *models.UpstreamNode {
	if len(nodes) == 0 {
		return nil
	}

	lb.mu.Lock()
	defer lb.mu.Unlock()

	idx := lb.rand.Intn(len(nodes))
	return nodes[idx]
}

func (lb *LoadBalancer) leastConn(clusterID string, nodes []*models.UpstreamNode) *models.UpstreamNode {
	if len(nodes) == 0 {
		return nil
	}

	lb.mu.Lock()
	defer lb.mu.Unlock()

	var selected *models.UpstreamNode
	minConn := int64(1<<63 - 1)

	for _, node := range nodes {
		key := fmt.Sprintf("%s_%s", clusterID, node.ID)
		connPtr, exists := lb.connections[key]
		if !exists {
			conn := int64(0)
			connPtr = &conn
			lb.connections[key] = connPtr
		}

		currentConn := atomic.LoadInt64(connPtr)
		if currentConn < minConn {
			minConn = currentConn
			selected = node
		}
	}

	if selected != nil {
		key := fmt.Sprintf("%s_%s", clusterID, selected.ID)
		if connPtr, exists := lb.connections[key]; exists {
			atomic.AddInt64(connPtr, 1)
		}
	}

	return selected
}

func (lb *LoadBalancer) ReleaseConnection(clusterID string, nodeID string) {
	if clusterID == "" || nodeID == "" {
		return
	}

	lb.mu.RLock()
	defer lb.mu.RUnlock()

	key := fmt.Sprintf("%s_%s", clusterID, nodeID)
	if connPtr, exists := lb.connections[key]; exists {
		atomic.AddInt64(connPtr, -1)
		if atomic.LoadInt64(connPtr) < 0 {
			atomic.StoreInt64(connPtr, 0)
		}
	}
}

func (lb *LoadBalancer) GetConnectionCount(clusterID string, nodeID string) int64 {
	if clusterID == "" || nodeID == "" {
		return 0
	}

	lb.mu.RLock()
	defer lb.mu.RUnlock()

	key := fmt.Sprintf("%s_%s", clusterID, nodeID)
	if connPtr, exists := lb.connections[key]; exists {
		return atomic.LoadInt64(connPtr)
	}

	return 0
}

func (lb *LoadBalancer) ResetConnectionCount(clusterID string, nodeID string) {
	if clusterID == "" || nodeID == "" {
		return
	}

	lb.mu.Lock()
	defer lb.mu.Unlock()

	key := fmt.Sprintf("%s_%s", clusterID, nodeID)
	if connPtr, exists := lb.connections[key]; exists {
		atomic.StoreInt64(connPtr, 0)
	}
}

func (lb *LoadBalancer) ResetAllConnections() {
	lb.mu.Lock()
	defer lb.mu.Unlock()

	for key := range lb.connections {
		atomic.StoreInt64(lb.connections[key], 0)
	}
}
