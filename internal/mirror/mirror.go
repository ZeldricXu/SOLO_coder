package mirror

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"

	"DF1-56/internal/models"
)

const (
	DefaultMirrorTimeout = 5 * time.Second
	HeaderMirrored       = "X-Mirrored"
)

type MirrorManager struct {
	mu         sync.RWMutex
	policies   map[string]*models.MirrorPolicy
	httpClient *http.Client
}

func NewMirrorManager(httpClient *http.Client) *MirrorManager {
	if httpClient == nil {
		httpClient = &http.Client{
			Timeout: DefaultMirrorTimeout,
			Transport: &http.Transport{
				MaxIdleConns:        100,
				IdleConnTimeout:     90 * time.Second,
				TLSHandshakeTimeout: 10 * time.Second,
			},
		}
	}

	return &MirrorManager{
		policies:   make(map[string]*models.MirrorPolicy),
		httpClient: httpClient,
	}
}

func (mm *MirrorManager) AddPolicy(policy *models.MirrorPolicy) {
	if policy == nil {
		return
	}
	mm.mu.Lock()
	defer mm.mu.Unlock()
	mm.policies[policy.ID] = policy
}

func (mm *MirrorManager) UpdatePolicy(policy *models.MirrorPolicy) {
	if policy == nil {
		return
	}
	mm.mu.Lock()
	defer mm.mu.Unlock()
	mm.policies[policy.ID] = policy
}

func (mm *MirrorManager) RemovePolicy(policyID string) {
	mm.mu.Lock()
	defer mm.mu.Unlock()
	delete(mm.policies, policyID)
}

func (mm *MirrorManager) MirrorRequest(ctx *models.GatewayContext, policyID string, targetCluster *models.UpstreamCluster) error {
	if ctx == nil || ctx.Request == nil {
		return fmt.Errorf("gateway context or request is nil")
	}

	if targetCluster == nil || len(targetCluster.Nodes) == 0 {
		return fmt.Errorf("target cluster has no healthy nodes")
	}

	mm.mu.RLock()
	policy, exists := mm.policies[policyID]
	mm.mu.RUnlock()

	if !exists {
		return fmt.Errorf("mirror policy %s not found", policyID)
	}

	if !policy.Enabled {
		return nil
	}

	if !mm.shouldMirror(policy, ctx) {
		return nil
	}

	targetNode := selectNode(targetCluster, ctx.RequestID)
	if targetNode == nil {
		return fmt.Errorf("no available node in target cluster")
	}

	targetURL := buildTargetURL(targetNode.Address, ctx.Request.URL.Path, ctx.Request.URL.RawQuery)

	go func() {
		err := mm.sendMirroredRequest(ctx, policy, targetURL)
		if err != nil {
			log.Printf("mirror request failed: %v", err)
		}
	}()

	return nil
}

func (mm *MirrorManager) shouldMirror(policy *models.MirrorPolicy, ctx *models.GatewayContext) bool {
	if policy.Percent <= 0 {
		return false
	}
	if policy.Percent >= 100 {
		return true
	}

	hashKey := ctx.RequestID
	if hashKey == "" {
		hashKey = ctx.TraceID
	}
	if hashKey == "" {
		hashKey = ctx.ClientIP + ctx.Request.URL.Path
	}

	hashValue := fnvHash32(hashKey)
	percent := hashValue % 100

	return percent < uint32(policy.Percent)
}

func (mm *MirrorManager) sendMirroredRequest(ctx *models.GatewayContext, policy *models.MirrorPolicy, targetURL string) error {
	timeout := policy.Timeout
	if timeout <= 0 {
		timeout = DefaultMirrorTimeout
	}

	mirrorCtx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	req, err := cloneRequestWithFilter(ctx, targetURL, policy.IncludeHeaders, policy.ExcludeHeaders)
	if err != nil {
		return fmt.Errorf("clone request failed: %w", err)
	}

	req = req.WithContext(mirrorCtx)
	req.Header.Set(HeaderMirrored, "true")
	if ctx.RequestID != "" {
		req.Header.Set("X-Original-Request-ID", ctx.RequestID)
	}

	client := mm.httpClient
	if policy.Timeout > 0 && policy.Timeout != client.Timeout {
		client = &http.Client{
			Timeout:   policy.Timeout,
			Transport: client.Transport,
		}
	}

	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("send mirror request failed: %w", err)
	}
	defer resp.Body.Close()

	return nil
}

func selectNode(cluster *models.UpstreamCluster, requestID string) *models.UpstreamNode {
	if cluster == nil || len(cluster.Nodes) == 0 {
		return nil
	}

	healthyNodes := make([]*models.UpstreamNode, 0, len(cluster.Nodes))
	for _, node := range cluster.Nodes {
		if node.Healthy {
			healthyNodes = append(healthyNodes, node)
		}
	}

	if len(healthyNodes) == 0 {
		healthyNodes = cluster.Nodes
	}

	if len(healthyNodes) == 1 {
		return healthyNodes[0]
	}

	switch cluster.LoadBalancer {
	case models.LoadBalancerRoundRobin:
		return roundRobinSelect(healthyNodes)
	case models.LoadBalancerRandom:
		return randomSelect(healthyNodes, requestID)
	case models.LoadBalancerIPHash:
		return ipHashSelect(healthyNodes, requestID)
	case models.LoadBalancerWeighted:
		return weightedSelect(healthyNodes)
	default:
		return roundRobinSelect(healthyNodes)
	}
}

var (
	rrIndex uint32
	rrMu    sync.Mutex
)

func roundRobinSelect(nodes []*models.UpstreamNode) *models.UpstreamNode {
	if len(nodes) == 0 {
		return nil
	}
	rrMu.Lock()
	defer rrMu.Unlock()
	node := nodes[rrIndex%uint32(len(nodes))]
	rrIndex++
	return node
}

func randomSelect(nodes []*models.UpstreamNode, requestID string) *models.UpstreamNode {
	if len(nodes) == 0 {
		return nil
	}
	hash := fnvHash32(requestID)
	return nodes[hash%uint32(len(nodes))]
}

func ipHashSelect(nodes []*models.UpstreamNode, requestID string) *models.UpstreamNode {
	if len(nodes) == 0 {
		return nil
	}
	hash := fnvHash32(requestID)
	return nodes[hash%uint32(len(nodes))]
}

func weightedSelect(nodes []*models.UpstreamNode) *models.UpstreamNode {
	if len(nodes) == 0 {
		return nil
	}

	totalWeight := 0
	for _, node := range nodes {
		totalWeight += node.Weight
	}

	if totalWeight <= 0 {
		return nodes[0]
	}

	rrMu.Lock()
	defer rrMu.Unlock()

	target := int(rrIndex % uint32(totalWeight))
	rrIndex++

	current := 0
	for _, node := range nodes {
		current += node.Weight
		if target < current {
			return node
		}
	}

	return nodes[len(nodes)-1]
}

func buildTargetURL(address, path, rawQuery string) string {
	scheme := "http"
	if len(address) >= 8 && (address[:7] == "http://" || address[:8] == "https://") {
		return address + path
	}

	url := scheme + "://" + address + path
	if rawQuery != "" {
		url += "?" + rawQuery
	}
	return url
}

func (mm *MirrorManager) GetPolicy(policyID string) (*models.MirrorPolicy, bool) {
	mm.mu.RLock()
	defer mm.mu.RUnlock()
	policy, exists := mm.policies[policyID]
	return policy, exists
}

func (mm *MirrorManager) ListPolicies() []*models.MirrorPolicy {
	mm.mu.RLock()
	defer mm.mu.RUnlock()
	policies := make([]*models.MirrorPolicy, 0, len(mm.policies))
	for _, policy := range mm.policies {
		policies = append(policies, policy)
	}
	return policies
}

func (mm *MirrorManager) GetHTTPClient() *http.Client {
	return mm.httpClient
}
