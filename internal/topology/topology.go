package topology

import (
	"sort"
	"sync"
	"time"

	"observability-platform/pkg/models"
)

type TopologyBuilder struct {
	serviceCalls      map[string]*ServiceCallStats
	serviceInstances  map[string]map[string]struct{}
	nodes             map[string]*models.ServiceNode
	edges             map[string]*models.ServiceEdge
	mu                sync.RWMutex
	timeWindow        time.Duration
	windowStartTime   time.Time
}

type ServiceCallStats struct {
	FromService string
	ToService   string
	CallCount   int64
	ErrorCount  int64
	Latencies   []time.Duration
	TotalLatency time.Duration
}

type TopologyConfig struct {
	TimeWindow time.Duration
}

func NewTopologyBuilder(config TopologyConfig) *TopologyBuilder {
	if config.TimeWindow <= 0 {
		config.TimeWindow = time.Hour
	}

	return &TopologyBuilder{
		serviceCalls:     make(map[string]*ServiceCallStats),
		serviceInstances: make(map[string]map[string]struct{}),
		nodes:            make(map[string]*models.ServiceNode),
		edges:            make(map[string]*models.ServiceEdge),
		timeWindow:       config.TimeWindow,
		windowStartTime:  time.Now(),
	}
}

func (b *TopologyBuilder) ProcessTrace(trace *models.Trace) {
	if trace == nil || len(trace.Spans) == 0 {
		return
	}

	b.mu.Lock()
	defer b.mu.Unlock()

	spanMap := make(map[string]*models.Span)
	for i := range trace.Spans {
		spanMap[trace.Spans[i].SpanID] = &trace.Spans[i]
	}

	for _, span := range trace.Spans {
		b.registerService(span.ServiceName)

		if span.ParentSpanID != "" {
			if parentSpan, exists := spanMap[span.ParentSpanID]; exists {
				if parentSpan.ServiceName != "" && span.ServiceName != "" && parentSpan.ServiceName != span.ServiceName {
					b.recordServiceCall(parentSpan.ServiceName, span.ServiceName, &span)
				}
			}
		}
	}
}

func (b *TopologyBuilder) registerService(serviceName string) {
	if serviceName == "" {
		return
	}

	if _, exists := b.nodes[serviceName]; !exists {
		b.nodes[serviceName] = &models.ServiceNode{
			ServiceName:   serviceName,
			InstanceCount: 0,
			Attributes:    make(map[string]interface{}),
		}
		b.serviceInstances[serviceName] = make(map[string]struct{})
	}
}

func (b *TopologyBuilder) recordServiceCall(fromService, toService string, span *models.Span) {
	key := fromService + "->" + toService

	stats, exists := b.serviceCalls[key]
	if !exists {
		stats = &ServiceCallStats{
			FromService: fromService,
			ToService:   toService,
			Latencies:   make([]time.Duration, 0, 100),
		}
		b.serviceCalls[key] = stats
	}

	stats.CallCount++
	if span.Status.Code != 0 {
		stats.ErrorCount++
	}
	stats.Latencies = append(stats.Latencies, span.Duration)
	stats.TotalLatency += span.Duration
}

func (b *TopologyBuilder) BuildTopology() *models.ServiceTopology {
	b.mu.RLock()
	defer b.mu.RUnlock()

	topology := &models.ServiceTopology{
		Nodes:       make([]models.ServiceNode, 0, len(b.nodes)),
		Edges:       make([]models.ServiceEdge, 0, len(b.edges)),
		GeneratedAt: time.Now(),
		TimeWindow:  b.timeWindow,
		Metadata:    make(map[string]interface{}),
	}

	for _, node := range b.nodes {
		topology.Nodes = append(topology.Nodes, *node)
	}

	for _, callStats := range b.serviceCalls {
		edge := b.calculateEdgeMetrics(callStats)
		topology.Edges = append(topology.Edges, edge)
	}

	sort.Slice(topology.Nodes, func(i, j int) bool {
		return topology.Nodes[i].ServiceName < topology.Nodes[j].ServiceName
	})

	sort.Slice(topology.Edges, func(i, j int) bool {
		if topology.Edges[i].FromService == topology.Edges[j].FromService {
			return topology.Edges[i].ToService < topology.Edges[j].ToService
		}
		return topology.Edges[i].FromService < topology.Edges[j].FromService
	})

	return topology
}

func (b *TopologyBuilder) calculateEdgeMetrics(stats *ServiceCallStats) models.ServiceEdge {
	edge := models.ServiceEdge{
		FromService: stats.FromService,
		ToService:   stats.ToService,
		CallCount:   stats.CallCount,
		ErrorCount:  stats.ErrorCount,
	}

	if stats.CallCount > 0 {
		edge.AvgLatency = stats.TotalLatency / time.Duration(stats.CallCount)
	}

	if len(stats.Latencies) > 0 {
		sorted := make([]time.Duration, len(stats.Latencies))
		copy(sorted, stats.Latencies)
		sort.Slice(sorted, func(i, j int) bool {
			return sorted[i] < sorted[j]
		})

		edge.P50Latency = b.percentile(sorted, 0.50)
		edge.P95Latency = b.percentile(sorted, 0.95)
		edge.P99Latency = b.percentile(sorted, 0.99)
	}

	return edge
}

func (b *TopologyBuilder) percentile(sorted []time.Duration, p float64) time.Duration {
	if len(sorted) == 0 {
		return 0
	}
	index := int(float64(len(sorted)-1) * p)
	return sorted[index]
}

func (b *TopologyBuilder) GetServiceDependencies(serviceName string) []string {
	b.mu.RLock()
	defer b.mu.RUnlock()

	dependencies := make(map[string]struct{})
	for _, edge := range b.serviceCalls {
		if edge.FromService == serviceName {
			dependencies[edge.ToService] = struct{}{}
		}
	}

	result := make([]string, 0, len(dependencies))
	for dep := range dependencies {
		result = append(result, dep)
	}
	sort.Strings(result)
	return result
}

func (b *TopologyBuilder) GetServiceDependents(serviceName string) []string {
	b.mu.RLock()
	defer b.mu.RUnlock()

	dependents := make(map[string]struct{})
	for _, edge := range b.serviceCalls {
		if edge.ToService == serviceName {
			dependents[edge.FromService] = struct{}{}
		}
	}

	result := make([]string, 0, len(dependents))
	for dep := range dependents {
		result = append(result, dep)
	}
	sort.Strings(result)
	return result
}

func (b *TopologyBuilder) GetCriticalPath() []string {
	b.mu.RLock()
	defer b.mu.RUnlock()

	type nodeScore struct {
		name  string
		score float64
	}

	scores := make(map[string]float64)
	for _, callStats := range b.serviceCalls {
		errorRate := 0.0
		if callStats.CallCount > 0 {
			errorRate = float64(callStats.ErrorCount) / float64(callStats.CallCount)
		}
		latencyScore := float64(callStats.TotalLatency) / float64(time.Millisecond)
		score := float64(callStats.CallCount)*0.3 + errorRate*1000*0.5 + latencyScore*0.2
		scores[callStats.FromService] += score
		scores[callStats.ToService] += score
	}

	var nodeList []nodeScore
	for name, score := range scores {
		nodeList = append(nodeList, nodeScore{name, score})
	}

	sort.Slice(nodeList, func(i, j int) bool {
		return nodeList[i].score > nodeList[j].score
	})

	result := make([]string, 0, len(nodeList))
	for _, ns := range nodeList {
		result = append(result, ns.name)
	}

	return result
}

func (b *TopologyBuilder) Reset() {
	b.mu.Lock()
	defer b.mu.Unlock()

	b.serviceCalls = make(map[string]*ServiceCallStats)
	b.nodes = make(map[string]*models.ServiceNode)
	b.edges = make(map[string]*models.ServiceEdge)
	b.windowStartTime = time.Now()
}

func (b *TopologyBuilder) Merge(other *TopologyBuilder) {
	other.mu.RLock()
	defer other.mu.RUnlock()

	b.mu.Lock()
	defer b.mu.Unlock()

	for name, node := range other.nodes {
		if _, exists := b.nodes[name]; !exists {
			b.nodes[name] = &models.ServiceNode{
				ServiceName:   node.ServiceName,
				InstanceCount: node.InstanceCount,
			}
		}
	}

	for key, callStats := range other.serviceCalls {
		if existing, exists := b.serviceCalls[key]; exists {
			existing.CallCount += callStats.CallCount
			existing.ErrorCount += callStats.ErrorCount
			existing.TotalLatency += callStats.TotalLatency
			existing.Latencies = append(existing.Latencies, callStats.Latencies...)
		} else {
			b.serviceCalls[key] = &ServiceCallStats{
				FromService:  callStats.FromService,
				ToService:    callStats.ToService,
				CallCount:    callStats.CallCount,
				ErrorCount:   callStats.ErrorCount,
				TotalLatency: callStats.TotalLatency,
				Latencies:    append([]time.Duration{}, callStats.Latencies...),
			}
		}
	}
}

func (b *TopologyBuilder) GetServiceMetrics(serviceName string) map[string]interface{} {
	b.mu.RLock()
	defer b.mu.RUnlock()

	metrics := make(map[string]interface{})
	var incomingCalls, outgoingCalls int64
	var incomingErrors, outgoingErrors int64

	for _, callStats := range b.serviceCalls {
		if callStats.ToService == serviceName {
			incomingCalls += callStats.CallCount
			incomingErrors += callStats.ErrorCount
		}
		if callStats.FromService == serviceName {
			outgoingCalls += callStats.CallCount
			outgoingErrors += callStats.ErrorCount
		}
	}

	metrics["incoming_calls"] = incomingCalls
	metrics["outgoing_calls"] = outgoingCalls
	metrics["total_calls"] = incomingCalls + outgoingCalls
	metrics["incoming_errors"] = incomingErrors
	metrics["outgoing_errors"] = outgoingErrors
	metrics["total_errors"] = incomingErrors + outgoingErrors

	if totalCalls := incomingCalls + outgoingCalls; totalCalls > 0 {
		metrics["error_rate"] = float64(incomingErrors+outgoingErrors) / float64(totalCalls)
	} else {
		metrics["error_rate"] = 0.0
	}

	return metrics
}
