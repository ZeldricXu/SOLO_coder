package circuitbreaker

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"sync"
	"time"

	"DF1-56/internal/models"
)

type HealthChecker struct {
	mu       sync.RWMutex
	cluster  *models.UpstreamCluster
	config   *models.HealthCheckConfig
	nodes    map[string]*models.UpstreamNode
	metrics  map[string]*Metrics
	ticker   *time.Ticker
	stopCh   chan struct{}
	running  bool
	httpCli  *http.Client
}

func NewHealthChecker(cluster *models.UpstreamCluster) *HealthChecker {
	if cluster == nil {
		return nil
	}

	config := cluster.HealthCheck
	if config == nil {
		config = &models.HealthCheckConfig{
			Type:             models.HealthCheckTCP,
			Interval:         10 * time.Second,
			Timeout:          5 * time.Second,
			FailureThreshold: 3,
			SuccessThreshold: 2,
		}
	}
	if config.Interval <= 0 {
		config.Interval = 10 * time.Second
	}
	if config.Timeout <= 0 {
		config.Timeout = 5 * time.Second
	}
	if config.FailureThreshold <= 0 {
		config.FailureThreshold = 3
	}
	if config.SuccessThreshold <= 0 {
		config.SuccessThreshold = 2
	}
	if config.Method == "" {
		config.Method = http.MethodGet
	}
	if len(config.ExpectedStatus) == 0 {
		config.ExpectedStatus = []int{http.StatusOK}
	}

	nodes := make(map[string]*models.UpstreamNode)
	metrics := make(map[string]*Metrics)
	for _, node := range cluster.Nodes {
		nodes[node.ID] = node
		metrics[node.ID] = NewMetrics(config.Interval*time.Duration(config.FailureThreshold*2), 10)
	}

	return &HealthChecker{
		cluster: cluster,
		config:  config,
		nodes:   nodes,
		metrics: metrics,
		stopCh:  make(chan struct{}),
		httpCli: &http.Client{
			Timeout: config.Timeout,
		},
	}
}

func (hc *HealthChecker) Start() {
	hc.mu.Lock()
	defer hc.mu.Unlock()

	if hc.running {
		return
	}

	hc.running = true
	hc.ticker = time.NewTicker(hc.config.Interval)

	go func() {
		for {
			select {
			case <-hc.ticker.C:
				hc.checkAllNodes()
			case <-hc.stopCh:
				return
			}
		}
	}()
}

func (hc *HealthChecker) Stop() {
	hc.mu.Lock()
	defer hc.mu.Unlock()

	if !hc.running {
		return
	}

	hc.running = false
	if hc.ticker != nil {
		hc.ticker.Stop()
	}
	close(hc.stopCh)
	hc.stopCh = make(chan struct{})
}

func (hc *HealthChecker) checkAllNodes() {
	hc.mu.RLock()
	nodes := make([]*models.UpstreamNode, 0, len(hc.nodes))
	for _, node := range hc.nodes {
		nodes = append(nodes, node)
	}
	hc.mu.RUnlock()

	var wg sync.WaitGroup
	for _, node := range nodes {
		wg.Add(1)
		go func(n *models.UpstreamNode) {
			defer wg.Done()
			_ = hc.CheckNode(n)
		}(node)
	}
	wg.Wait()
}

func (hc *HealthChecker) CheckNode(node *models.UpstreamNode) error {
	if node == nil {
		return errors.New("node is nil")
	}

	var err error
	switch hc.config.Type {
	case models.HealthCheckHTTP:
		err = hc.checkHTTP(node)
	case models.HealthCheckTCP:
		err = hc.checkTCP(node)
	case models.HealthCheckGRPC:
		err = hc.checkGRPC(node)
	default:
		err = fmt.Errorf("unsupported health check type: %s", hc.config.Type)
	}

	hc.mu.Lock()
	defer hc.mu.Unlock()

	node.LastCheck = time.Now()
	if err != nil {
		node.FailCount++
		node.SuccessCount = 0
		if node.FailCount >= hc.config.FailureThreshold {
			node.Healthy = false
		}
		if m, ok := hc.metrics[node.ID]; ok {
			m.RecordFailure()
		}
	} else {
		node.SuccessCount++
		node.FailCount = 0
		if node.SuccessCount >= hc.config.SuccessThreshold {
			node.Healthy = true
		}
		if m, ok := hc.metrics[node.ID]; ok {
			m.RecordSuccess()
		}
	}

	return err
}

func (hc *HealthChecker) checkHTTP(node *models.UpstreamNode) error {
	scheme := "http"
	if node.Protocol == models.ProtocolHTTP2 || node.Protocol == models.ProtocolGRPC {
		scheme = "https"
	}

	url := fmt.Sprintf("%s://%s%s", scheme, node.Address, hc.config.Path)

	req, err := http.NewRequest(hc.config.Method, url, nil)
	if err != nil {
		return err
	}

	ctx, cancel := context.WithTimeout(context.Background(), hc.config.Timeout)
	defer cancel()
	req = req.WithContext(ctx)

	resp, err := hc.httpCli.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	for _, expected := range hc.config.ExpectedStatus {
		if resp.StatusCode == expected {
			return nil
		}
	}

	return fmt.Errorf("unexpected status code: %d", resp.StatusCode)
}

func (hc *HealthChecker) checkTCP(node *models.UpstreamNode) error {
	conn, err := net.DialTimeout("tcp", node.Address, hc.config.Timeout)
	if err != nil {
		return err
	}
	defer conn.Close()
	return nil
}

func (hc *HealthChecker) checkGRPC(node *models.UpstreamNode) error {
	conn, err := net.DialTimeout("tcp", node.Address, hc.config.Timeout)
	if err != nil {
		return err
	}
	defer conn.Close()

	return nil
}

func (hc *HealthChecker) GetHealthyNodes() []*models.UpstreamNode {
	hc.mu.RLock()
	defer hc.mu.RUnlock()

	healthy := make([]*models.UpstreamNode, 0, len(hc.nodes))
	for _, node := range hc.nodes {
		if node.Healthy {
			healthy = append(healthy, node)
		}
	}
	return healthy
}

func (hc *HealthChecker) MarkSuccess(nodeID string) {
	hc.mu.Lock()
	defer hc.mu.Unlock()

	node, ok := hc.nodes[nodeID]
	if !ok {
		return
	}

	node.SuccessCount++
	node.FailCount = 0
	if node.SuccessCount >= hc.config.SuccessThreshold {
		node.Healthy = true
	}
	if m, ok := hc.metrics[nodeID]; ok {
		m.RecordSuccess()
	}
}

func (hc *HealthChecker) MarkFailure(nodeID string, err error) {
	hc.mu.Lock()
	defer hc.mu.Unlock()

	node, ok := hc.nodes[nodeID]
	if !ok {
		return
	}

	node.FailCount++
	node.SuccessCount = 0
	if node.FailCount >= hc.config.FailureThreshold {
		node.Healthy = false
	}
	if m, ok := hc.metrics[nodeID]; ok {
		m.RecordFailure()
	}
}

func (hc *HealthChecker) GetNodeMetrics(nodeID string) *Metrics {
	hc.mu.RLock()
	defer hc.mu.RUnlock()
	return hc.metrics[nodeID]
}

func (hc *HealthChecker) AddNode(node *models.UpstreamNode) {
	if node == nil {
		return
	}

	hc.mu.Lock()
	defer hc.mu.Unlock()

	hc.nodes[node.ID] = node
	hc.metrics[node.ID] = NewMetrics(hc.config.Interval*time.Duration(hc.config.FailureThreshold*2), 10)
}

func (hc *HealthChecker) RemoveNode(nodeID string) {
	hc.mu.Lock()
	defer hc.mu.Unlock()

	delete(hc.nodes, nodeID)
	delete(hc.metrics, nodeID)
}
