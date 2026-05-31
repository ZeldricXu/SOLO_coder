package topology

import (
	"encoding/json"
	"fmt"
	"math"
	"sort"
	"sync"
	"time"

	"session130/internal/logger"
	"session130/pkg/models"
)

type EdgeStats struct {
	Count     int64
	TotalLat  float64
	Latencies []float64
	Errors    int64
}

type Builder struct {
	mu        sync.RWMutex
	nodes     map[string]*models.TopologyNode
	edges     map[string]*EdgeStats
	nodeIndex map[string]bool
}

var (
	instance *Builder
	once     sync.Once
)

func NewBuilder() *Builder {
	return &Builder{
		nodes:     make(map[string]*models.TopologyNode),
		edges:     make(map[string]*EdgeStats),
		nodeIndex: make(map[string]bool),
	}
}

func GetBuilder() *Builder {
	once.Do(func() {
		instance = NewBuilder()
	})
	return instance
}

func edgeKey(from, to string) string {
	return fmt.Sprintf("%s->%s", from, to)
}

func (b *Builder) RecordSpan(span *models.Span) {
	b.mu.Lock()
	defer b.mu.Unlock()

	if span.Service != "" {
		if !b.nodeIndex[span.Service] {
			b.nodes[span.Service] = &models.TopologyNode{
				Service: span.Service,
				Metadata: map[string]string{
					"first_seen": time.Now().Format(time.RFC3339),
				},
			}
			b.nodeIndex[span.Service] = true
		}
	}

	if span.ParentID != "" && span.Service != "" {
		b.edges[span.Service] = &EdgeStats{}
	}

	if span.ParentID != "" && span.Service != "" {
		parentService := b.findParentService(span)
		if parentService != "" && parentService != span.Service {
			key := edgeKey(parentService, span.Service)
			stats, exists := b.edges[key]
			if !exists {
				stats = &EdgeStats{
					Latencies: make([]float64, 0, 1000),
				}
				b.edges[key] = stats
			}
			stats.Count++
			latency := float64(span.EndTime.Sub(span.StartTime).Milliseconds())
			stats.TotalLat += latency
			stats.Latencies = append(stats.Latencies, latency)
			if len(stats.Latencies) > 10000 {
				stats.Latencies = stats.Latencies[len(stats.Latencies)-10000:]
			}
			if span.Status == "error" {
				stats.Errors++
			}
		}
	}

	logger.Debug("", "topology span recorded", map[string]interface{}{
		"service": span.Service,
		"operation": span.Operation,
	})
}

func (b *Builder) findParentService(span *models.Span) string {
	return "gateway"
}

func (b *Builder) GetTopology() *models.ServiceTopology {
	b.mu.RLock()
	defer b.mu.RUnlock()

	nodes := make([]models.TopologyNode, 0, len(b.nodes))
	for _, node := range b.nodes {
		nodes = append(nodes, *node)
	}

	edges := make([]models.TopologyEdge, 0, len(b.edges))
	for key, stats := range b.edges {
		from, to := parseEdgeKey(key)
		edge := models.TopologyEdge{
			From:  from,
			To:    to,
			Count: stats.Count,
		}

		if len(stats.Latencies) > 0 {
			edge.LatencyP50 = calculatePercentile(stats.Latencies, 50)
			edge.LatencyP99 = calculatePercentile(stats.Latencies, 99)
		}

		if stats.Count > 0 {
			edge.ErrorRate = float64(stats.Errors) / float64(stats.Count)
		}

		edges = append(edges, edge)
	}

	sort.Slice(nodes, func(i, j int) bool {
		return nodes[i].Service < nodes[j].Service
	})

	sort.Slice(edges, func(i, j int) bool {
		if edges[i].From == edges[j].From {
			return edges[i].To < edges[j].To
		}
		return edges[i].From < edges[j].From
	})

	return &models.ServiceTopology{
		Nodes: nodes,
		Edges: edges,
	}
}

func (b *Builder) AddNode(service string, instance string, metadata map[string]string) {
	b.mu.Lock()
	defer b.mu.Unlock()

	if !b.nodeIndex[service] {
		nodeMeta := map[string]string{
			"first_seen": time.Now().Format(time.RFC3339),
		}
		for k, v := range metadata {
			nodeMeta[k] = v
		}
		b.nodes[service] = &models.TopologyNode{
			Service:  service,
			Instance: instance,
			Metadata: nodeMeta,
		}
		b.nodeIndex[service] = true
	}
}

func (b *Builder) RecordCall(from, to string, latencyMs float64, isError bool) {
	b.mu.Lock()
	defer b.mu.Unlock()

	key := edgeKey(from, to)
	stats, exists := b.edges[key]
	if !exists {
		stats = &EdgeStats{
			Latencies: make([]float64, 0, 1000),
		}
		b.edges[key] = stats
	}

	stats.Count++
	stats.TotalLat += latencyMs
	stats.Latencies = append(stats.Latencies, latencyMs)
	if len(stats.Latencies) > 10000 {
		stats.Latencies = stats.Latencies[len(stats.Latencies)-10000:]
	}
	if isError {
		stats.Errors++
	}
}

func (b *Builder) Reset() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.nodes = make(map[string]*models.TopologyNode)
	b.edges = make(map[string]*EdgeStats)
	b.nodeIndex = make(map[string]bool)
}

func (b *Builder) MarshalJSON() ([]byte, error) {
	return json.Marshal(b.GetTopology())
}

func parseEdgeKey(key string) (string, string) {
	for i := 0; i < len(key); i++ {
		if key[i] == '-' && i+1 < len(key) && key[i+1] == '>' {
			return key[:i], key[i+2:]
		}
	}
	return key, ""
}

func calculatePercentile(values []float64, percentile float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sorted := make([]float64, len(values))
	copy(sorted, values)
	sort.Float64s(sorted)
	index := int(math.Ceil((percentile / 100.0) * float64(len(sorted))))
	if index >= len(sorted) {
		index = len(sorted) - 1
	}
	return sorted[index]
}

func RecordSpan(span *models.Span) {
	GetBuilder().RecordSpan(span)
}

func GetTopology() *models.ServiceTopology {
	return GetBuilder().GetTopology()
}
