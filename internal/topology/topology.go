package topology

import (
	"go.uber.org/zap"
	"sync"
	"taskmanager/internal/logger"
	"taskmanager/pkg/models"
	"time"
)

type TopologyBuilder struct {
	nodes     map[string]*models.ServiceNode
	edges     map[string]*models.ServiceEdge
	edgeKey   func(from, to string) string
	mu        sync.RWMutex
	spanChan  chan *models.Span
	wg        sync.WaitGroup
	stopped   chan struct{}
}

func NewTopologyBuilder() *TopologyBuilder {
	return &TopologyBuilder{
		nodes:    make(map[string]*models.ServiceNode),
		edges:    make(map[string]*models.ServiceEdge),
		edgeKey:  func(from, to string) string { return from + "->" + to },
		spanChan: make(chan *models.Span, 10000),
		stopped:  make(chan struct{}),
	}
}

func (tb *TopologyBuilder) Start() {
	tb.wg.Add(1)
	go tb.processLoop()
	logger.Info("topology builder started")
}

func (tb *TopologyBuilder) Stop() {
	close(tb.stopped)
	tb.wg.Wait()
	close(tb.spanChan)
	logger.Info("topology builder stopped")
}

func (tb *TopologyBuilder) AddSpan(span *models.Span) {
	select {
	case tb.spanChan <- span:
	default:
		logger.Warn("topology builder channel full, dropping span")
	}
}

func (tb *TopologyBuilder) processLoop() {
	defer tb.wg.Done()
	for {
		select {
		case span := <-tb.spanChan:
			tb.processSpan(span)
		case <-tb.stopped:
			return
		}
	}
}

func (tb *TopologyBuilder) processSpan(span *models.Span) {
	tb.mu.Lock()
	defer tb.mu.Unlock()
	service := span.Service
	if service == "" {
		service = "unknown"
	}
	node, ok := tb.nodes[service]
	if !ok {
		node = &models.ServiceNode{ServiceName: service}
		tb.nodes[service] = node
	}
	node.CallCount++
	node.AvgLatency = (node.AvgLatency*(node.CallCount-1) + span.Duration) / node.CallCount
	if span.StatusCode >= 500 {
		errorCount := float64(node.ErrorRate) * float64(node.CallCount-1) / 100.0
		errorCount++
		node.ErrorRate = errorCount * 100.0 / float64(node.CallCount)
	}
	if span.ParentID != "" && span.ParentID != span.SpanID {
		parentService := tb.findParentService(span)
		if parentService != "" && parentService != service {
			key := tb.edgeKey(parentService, service)
			edge, ok := tb.edges[key]
			if !ok {
				edge = &models.ServiceEdge{
					From: parentService,
					To:   service,
				}
				tb.edges[key] = edge
			}
			edge.CallCount++
			edge.AvgLatency = (edge.AvgLatency*(edge.CallCount-1) + span.Duration) / edge.CallCount
		}
	}
}

func (tb *TopologyBuilder) findParentService(span *models.Span) string {
	return span.Tags["parent_service"]
}

func (tb *TopologyBuilder) GetTopology() *models.Topology {
	tb.mu.RLock()
	defer tb.mu.RUnlock()
	topology := &models.Topology{
		Nodes: make([]models.ServiceNode, 0, len(tb.nodes)),
		Edges: make([]models.ServiceEdge, 0, len(tb.edges)),
	}
	for _, node := range tb.nodes {
		topology.Nodes = append(topology.Nodes, *node)
	}
	for _, edge := range tb.edges {
		topology.Edges = append(topology.Edges, *edge)
	}
	return topology
}

func (tb *TopologyBuilder) GetServiceDependencies(serviceName string) []string {
	tb.mu.RLock()
	defer tb.mu.RUnlock()
	dependencies := make([]string, 0)
	for _, edge := range tb.edges {
		if edge.From == serviceName {
			dependencies = append(dependencies, edge.To)
		}
	}
	return dependencies
}

func (tb *TopologyBuilder) GetServiceDependents(serviceName string) []string {
	tb.mu.RLock()
	defer tb.mu.RUnlock()
	dependents := make([]string, 0)
	for _, edge := range tb.edges {
		if edge.To == serviceName {
			dependents = append(dependents, edge.From)
		}
	}
	return dependents
}

func (tb *TopologyBuilder) GetServiceMetrics(serviceName string) *models.ServiceNode {
	tb.mu.RLock()
	defer tb.mu.RUnlock()
	if node, ok := tb.nodes[serviceName]; ok {
		nodeCopy := *node
		return &nodeCopy
	}
	return nil
}

func (tb *TopologyBuilder) Reset() {
	tb.mu.Lock()
	defer tb.mu.Unlock()
	tb.nodes = make(map[string]*models.ServiceNode)
	tb.edges = make(map[string]*models.ServiceEdge)
}

type TopologyAnalyzer struct {
	builder *TopologyBuilder
}

func NewTopologyAnalyzer(builder *TopologyBuilder) *TopologyAnalyzer {
	return &TopologyAnalyzer{builder: builder}
}

func (ta *TopologyAnalyzer) FindCriticalPath() []string {
	topology := ta.builder.GetTopology()
	visited := make(map[string]bool)
	path := make([]string, 0)
	maxCalls := int64(0)
	var dfs func(string, []string, int64)
	dfs = func(node string, currentPath []string, totalCalls int64) {
		visited[node] = true
		currentPath = append(currentPath, node)
		if nodeMetrics := ta.builder.GetServiceMetrics(node); nodeMetrics != nil {
			totalCalls += nodeMetrics.CallCount
		}
		if totalCalls > maxCalls {
			maxCalls = totalCalls
			path = make([]string, len(currentPath))
			copy(path, currentPath)
		}
		deps := ta.builder.GetServiceDependencies(node)
		for _, dep := range deps {
			if !visited[dep] {
				dfs(dep, currentPath, totalCalls)
			}
		}
		visited[node] = false
	}
	for _, node := range topology.Nodes {
		dfs(node.ServiceName, []string{}, 0)
	}
	return path
}

func (ta *TopologyAnalyzer) FindHighErrorServices(threshold float64) []string {
	topology := ta.builder.GetTopology()
	result := make([]string, 0)
	for _, node := range topology.Nodes {
		if node.ErrorRate > threshold {
			result = append(result, node.ServiceName)
		}
	}
	return result
}

func (ta *TopologyAnalyzer) DetectCircularDependencies() [][]string {
	topology := ta.builder.GetTopology()
	visited := make(map[string]bool)
	recStack := make(map[string]bool)
	var cycles [][]string
	var dfs func(string, []string) bool
	dfs = func(node string, path []string) bool {
		visited[node] = true
		recStack[node] = true
		path = append(path, node)
		deps := ta.builder.GetServiceDependencies(node)
		for _, dep := range deps {
			if !visited[dep] {
				if dfs(dep, path) {
					return true
				}
			} else if recStack[dep] {
				cycleStart := -1
				for i, n := range path {
					if n == dep {
						cycleStart = i
						break
					}
				}
				if cycleStart != -1 {
					cycle := append([]string{}, path[cycleStart:]...)
					cycle = append(cycle, dep)
					cycles = append(cycles, cycle)
				}
			}
		}
		recStack[node] = false
		return false
	}
	for _, node := range topology.Nodes {
		if !visited[node.ServiceName] {
			dfs(node.ServiceName, []string{})
		}
	}
	return cycles
}

type SnapshotGenerator struct {
	builder *TopologyBuilder
}

func NewSnapshotGenerator(builder *TopologyBuilder) *SnapshotGenerator {
	return &SnapshotGenerator{builder: builder}
}

func (sg *SnapshotGenerator) GenerateSnapshot(dimensions map[string]string) *models.Snapshot {
	topology := sg.builder.GetTopology()
	totalCalls := int64(0)
	totalLatency := int64(0)
	errorCount := 0
	for _, node := range topology.Nodes {
		totalCalls += node.CallCount
		totalLatency += node.AvgLatency * node.CallCount
		if node.ErrorRate > 0 {
			errorCount++
		}
	}
	avgLatency := int64(0)
	if totalCalls > 0 {
		avgLatency = totalLatency / totalCalls
	}
	errorRate := 0.0
	if len(topology.Nodes) > 0 {
		errorRate = float64(errorCount) / float64(len(topology.Nodes))
	}
	return &models.Snapshot{
		SnapshotID: generateSnapshotID(),
		Timestamp:  time.Now(),
		Metrics: map[string]float64{
			"throughput":   float64(totalCalls),
			"latency_p99":  float64(avgLatency) * 1.5,
			"latency_p50":  float64(avgLatency),
			"error_rate":   errorRate,
			"service_count": float64(len(topology.Nodes)),
			"edge_count":   float64(len(topology.Edges)),
		},
		Dimensions: dimensions,
	}
}

func generateSnapshotID() string {
	return "snap_" + time.Now().Format("20060102150405")
}

func (sg *SnapshotGenerator) GetTopology() *models.Topology {
	return sg.builder.GetTopology()
}
