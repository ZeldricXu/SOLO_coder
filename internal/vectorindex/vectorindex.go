package vectorindex

import (
	"math"
	"sync"
	"sync/atomic"
	"time"

	"github.com/datatransform/platform/pkg/logger"
	"github.com/datatransform/platform/pkg/service"
	"github.com/prometheus/client_golang/prometheus"
	"go.uber.org/zap"
)

const (
	ServiceName = "vector_index"

	defaultNodeCapacity   = 32
	defaultApproxThreshold = 0.7
	defaultSearchTopK      = 10

	namespace     = "vector_index"
	operationAdd  = "add"
	operationSearch = "search"
	operationBuild  = "build"
)

var (
	ErrDimensionMismatch = service.NewErrorDetail("VEC_001", "dimension mismatch", nil)
	ErrIndexNotBuilt     = service.NewErrorDetail("VEC_002", "index not built", nil)
	ErrEmptyQuery        = service.NewErrorDetail("VEC_003", "empty query vector", nil)
	ErrInvalidTopK       = service.NewErrorDetail("VEC_004", "invalid topK value", nil)
	ErrMonitoringDisabled = service.NewErrorDetail("VEC_005", "monitoring is disabled", nil)
)

type Vector []float64

type DistanceFunction func(a, b Vector) float64

const (
	DistanceCosine    = "cosine"
	DistanceEuclidean = "euclidean"
)

type VectorItem struct {
	ID     string
	Vector Vector
	Data   interface{}
}

type IndexNode struct {
	ID       string
	Vector   Vector
	Children []*IndexNode
	Level    int
}

type SearchResult struct {
	ID       string
	Distance float64
	Data     interface{}
}

type SearchOptions struct {
	TopK        int
	Approximate bool
	DistanceFn  string
	Threshold   float64
}

type IndexStatus string

const (
	StatusIdle       IndexStatus = "idle"
	StatusBuilding   IndexStatus = "building"
	StatusReady      IndexStatus = "ready"
	StatusDegraded   IndexStatus = "degraded"
)

type MonitoringConfig struct {
	Enabled    bool
	Registry   *prometheus.Registry
	MetricPath string
}

type IndexMetrics struct {
	ItemsCount        prometheus.Gauge
	IndexBuilt        prometheus.Gauge
	BuildDuration     prometheus.Histogram
	SearchDuration    *prometheus.HistogramVec
	AddDuration       prometheus.Histogram
	SearchCount       *prometheus.CounterVec
	SearchFailedCount *prometheus.CounterVec
	AddCount          prometheus.Counter
	AddFailedCount    prometheus.Counter
	QueriesPerSecond  prometheus.Gauge
}

type OperationTiming struct {
	AddDurationNS    int64
	BuildDurationNS  int64
	SearchDurationNS int64
	LastOperationTS  int64
}

type VectorIndex struct {
	*service.BaseService

	items        map[string]*VectorItem
	indexTree    []*IndexNode
	dimension    int
	distanceFn   DistanceFunction
	nodeCapacity int
	mu           sync.RWMutex
	built        bool
	status       IndexStatus

	monitoringEnabled bool
	registry          *prometheus.Registry
	metrics           *IndexMetrics

	timing OperationTiming

	searchQueries        uint64
	searchWindowStart    int64
}

func NewVectorIndex(dimension int) *VectorIndex {
	return NewVectorIndexWithConfig(dimension, DistanceCosine, MonitoringConfig{Enabled: false})
}

func NewVectorIndexWithConfig(dimension int, distanceFn string, monitoringConfig MonitoringConfig) *VectorIndex {
	vi := &VectorIndex{
		BaseService:       service.NewBaseService(ServiceName),
		items:             make(map[string]*VectorItem),
		indexTree:         make([]*IndexNode, 0),
		dimension:         dimension,
		distanceFn:        CosineDistance,
		nodeCapacity:      defaultNodeCapacity,
		built:             false,
		status:            StatusIdle,
		monitoringEnabled: monitoringConfig.Enabled,
		registry:          monitoringConfig.Registry,
	}

	vi.SetDistanceFunction(distanceFn)

	if monitoringConfig.Enabled {
		vi.initMetrics(monitoringConfig.Registry)
	}

	return vi
}

func (vi *VectorIndex) initMetrics(reg *prometheus.Registry) {
	if reg == nil {
		reg = prometheus.NewRegistry()
	}
	vi.registry = reg

	vi.metrics = &IndexMetrics{
		ItemsCount: prometheus.NewGauge(prometheus.GaugeOpts{
			Namespace: namespace,
			Name:      "items_total",
			Help:      "Total number of items in the vector index",
		}),
		IndexBuilt: prometheus.NewGauge(prometheus.GaugeOpts{
			Namespace: namespace,
			Name:      "index_built",
			Help:      "Whether the index has been built (1=built, 0=not built)",
		}),
		BuildDuration: prometheus.NewHistogram(prometheus.HistogramOpts{
			Namespace: namespace,
			Name:      "build_duration_seconds",
			Help:      "Time taken to build the vector index tree",
			Buckets:   prometheus.ExponentialBuckets(0.001, 2, 15),
		}),
		SearchDuration: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Namespace: namespace,
				Name:      "search_duration_seconds",
				Help:      "Time taken to perform a vector search",
				Buckets:   prometheus.ExponentialBuckets(0.0001, 2, 15),
			},
			[]string{"type", "approximate"},
		),
		AddDuration: prometheus.NewHistogram(prometheus.HistogramOpts{
			Namespace: namespace,
			Name:      "add_duration_seconds",
			Help:      "Time taken to add a vector to the index",
			Buckets:   prometheus.ExponentialBuckets(0.0001, 2, 15),
		}),
		SearchCount: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Namespace: namespace,
				Name:      "search_queries_total",
				Help:      "Total number of search queries executed",
			},
			[]string{"type", "approximate"},
		),
		SearchFailedCount: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Namespace: namespace,
				Name:      "search_errors_total",
				Help:      "Total number of failed search queries",
			},
			[]string{"type"},
		),
		AddCount: prometheus.NewCounter(prometheus.CounterOpts{
			Namespace: namespace,
			Name:      "add_operations_total",
			Help:      "Total number of add operations",
		}),
		AddFailedCount: prometheus.NewCounter(prometheus.CounterOpts{
			Namespace: namespace,
			Name:      "add_errors_total",
			Help:      "Total number of failed add operations",
		}),
		QueriesPerSecond: prometheus.NewGauge(prometheus.GaugeOpts{
			Namespace: namespace,
			Name:      "queries_per_second",
			Help:      "Current queries per second (QPS)",
		}),
	}

	reg.MustRegister(
		vi.metrics.ItemsCount,
		vi.metrics.IndexBuilt,
		vi.metrics.BuildDuration,
		vi.metrics.SearchDuration,
		vi.metrics.AddDuration,
		vi.metrics.SearchCount,
		vi.metrics.SearchFailedCount,
		vi.metrics.AddCount,
		vi.metrics.AddFailedCount,
		vi.metrics.QueriesPerSecond,
	)
}

func (vi *VectorIndex) EnableMonitoring(reg *prometheus.Registry) {
	if vi.monitoringEnabled {
		logger.Warn("monitoring already enabled")
		return
	}

	vi.initMetrics(reg)
	vi.monitoringEnabled = true
	logger.Info("monitoring enabled for vector index")
}

func (vi *VectorIndex) DisableMonitoring() {
	if !vi.monitoringEnabled {
		return
	}

	vi.monitoringEnabled = false
	logger.Info("monitoring disabled for vector index")
}

func (vi *VectorIndex) Registry() *prometheus.Registry {
	return vi.registry
}

func (vi *VectorIndex) Start() error {
	if err := vi.ValidateStart(); err != nil {
		return err
	}

	logger.Info("starting vector index",
		zap.String("service", ServiceName),
		zap.Int("dimension", vi.dimension),
		zap.Bool("monitoring_enabled", vi.monitoringEnabled),
	)

	if vi.monitoringEnabled {
		vi.metrics.ItemsCount.Set(float64(len(vi.items)))
		if vi.built {
			vi.metrics.IndexBuilt.Set(1)
		} else {
			vi.metrics.IndexBuilt.Set(0)
		}
	}

	vi.SetRunning(true)
	return nil
}

func (vi *VectorIndex) Stop() error {
	if err := vi.ValidateStop(); err != nil {
		return err
	}

	logger.Info("stopping vector index",
		zap.String("service", ServiceName),
		zap.Int("item_count", len(vi.items)),
	)

	vi.SetRunning(false)
	return nil
}

func (vi *VectorIndex) SetDistanceFunction(fn string) {
	switch fn {
	case DistanceEuclidean:
		vi.distanceFn = EuclideanDistance
	default:
		vi.distanceFn = CosineDistance
	}
}

func (vi *VectorIndex) Add(item *VectorItem) error {
	if item == nil {
		return wrapError(ErrDimensionMismatch, "nil item")
	}

	return vi.AddVector(item.ID, item.Vector, item.Data)
}

func (vi *VectorIndex) AddVector(id string, vector Vector, data interface{}) error {
	startTime := time.Now()

	if len(vector) != vi.dimension {
		if vi.monitoringEnabled {
			vi.metrics.AddFailedCount.Inc()
		}
		return wrapError(ErrDimensionMismatch,
			"expected: "+itoa(vi.dimension)+", actual: "+itoa(len(vector)))
	}

	vi.mu.Lock()
	defer vi.mu.Unlock()

	vi.items[id] = &VectorItem{
		ID:     id,
		Vector: vector,
		Data:   data,
	}

	vi.built = false
	vi.status = StatusIdle

	duration := time.Since(startTime)
	atomic.StoreInt64(&vi.timing.AddDurationNS, duration.Nanoseconds())
	atomic.StoreInt64(&vi.timing.LastOperationTS, time.Now().UnixNano())

	if vi.monitoringEnabled {
		vi.metrics.AddDuration.Observe(duration.Seconds())
		vi.metrics.AddCount.Inc()
		vi.metrics.ItemsCount.Set(float64(len(vi.items)))
		vi.metrics.IndexBuilt.Set(0)
	}

	logger.Debug("vector added",
		zap.String("id", id),
		zap.Int("total_items", len(vi.items)),
		zap.Duration("duration", duration),
	)

	return nil
}

func (vi *VectorIndex) BatchAdd(items []*VectorItem) (int, error) {
	if len(items) == 0 {
		return 0, nil
	}

	startTime := time.Now()

	vi.mu.Lock()
	defer vi.mu.Unlock()

	count := 0
	for _, item := range items {
		if item == nil {
			continue
		}

		if len(item.Vector) != vi.dimension {
			continue
		}

		vi.items[item.ID] = item
		count++
	}

	vi.built = false
	vi.status = StatusIdle

	duration := time.Since(startTime)
	atomic.StoreInt64(&vi.timing.AddDurationNS, duration.Nanoseconds())

	if vi.monitoringEnabled {
		vi.metrics.AddDuration.Observe(duration.Seconds())
		vi.metrics.AddCount.Add(float64(count))
		vi.metrics.ItemsCount.Set(float64(len(vi.items)))
		vi.metrics.IndexBuilt.Set(0)
	}

	logger.Debug("batch add completed",
		zap.Int("added", count),
		zap.Int("total", len(items)),
		zap.Duration("duration", duration),
	)

	return count, nil
}

func (vi *VectorIndex) Remove(id string) {
	vi.RemoveVector(id)
}

func (vi *VectorIndex) RemoveVector(id string) {
	vi.mu.Lock()
	defer vi.mu.Unlock()

	if _, exists := vi.items[id]; exists {
		delete(vi.items, id)
		vi.built = false
		vi.status = StatusIdle

		if vi.monitoringEnabled {
			vi.metrics.ItemsCount.Set(float64(len(vi.items)))
			vi.metrics.IndexBuilt.Set(0)
		}

		logger.Debug("vector removed", zap.String("id", id))
	}
}

func (vi *VectorIndex) Get(id string) (*VectorItem, bool) {
	return vi.GetVector(id)
}

func (vi *VectorIndex) GetVector(id string) (*VectorItem, bool) {
	vi.mu.RLock()
	defer vi.mu.RUnlock()

	item, exists := vi.items[id]
	return item, exists
}

func (vi *VectorIndex) Exists(id string) bool {
	vi.mu.RLock()
	defer vi.mu.RUnlock()

	_, exists := vi.items[id]
	return exists
}

func (vi *VectorIndex) Build() {
	vi.BuildIndex()
}

func (vi *VectorIndex) BuildIndex() {
	startTime := time.Now()

	vi.mu.Lock()
	vi.status = StatusBuilding
	vi.mu.Unlock()

	vi.mu.Lock()
	defer vi.mu.Unlock()

	if len(vi.items) == 0 {
		logger.Warn("no items to build index from")
		vi.status = StatusIdle
		return
	}

	logger.Info("building vector index",
		zap.Int("item_count", len(vi.items)),
		zap.Int("node_capacity", vi.nodeCapacity),
	)

	vi.indexTree = vi.buildTree(vi.getItemsAsSlice(), 0)
	vi.built = true
	vi.status = StatusReady

	duration := time.Since(startTime)
	atomic.StoreInt64(&vi.timing.BuildDurationNS, duration.Nanoseconds())

	if vi.monitoringEnabled {
		vi.metrics.BuildDuration.Observe(duration.Seconds())
		vi.metrics.IndexBuilt.Set(1)
	}

	logger.Info("vector index built",
		zap.Duration("build_time", duration),
		zap.Int("tree_nodes", len(vi.indexTree)),
	)
}

func (vi *VectorIndex) getItemsAsSlice() []*VectorItem {
	items := make([]*VectorItem, 0, len(vi.items))
	for _, item := range vi.items {
		items = append(items, item)
	}
	return items
}

func (vi *VectorIndex) buildTree(items []*VectorItem, level int) []*IndexNode {
	if len(items) == 0 {
		return nil
	}

	if len(items) <= vi.nodeCapacity {
		nodes := make([]*IndexNode, 0, len(items))
		for _, item := range items {
			nodes = append(nodes, &IndexNode{
				ID:     item.ID,
				Vector: item.Vector,
				Level:  level,
			})
		}
		return nodes
	}

	pivot := items[len(items)/2]
	node := &IndexNode{
		ID:     pivot.ID,
		Vector: pivot.Vector,
		Level:  level,
	}

	var left, right []*VectorItem
	for _, item := range items {
		if item.ID == pivot.ID {
			continue
		}

		dist := vi.distanceFn(item.Vector, pivot.Vector)
		if dist < 0.5 {
			left = append(left, item)
		} else {
			right = append(right, item)
		}
	}

	if len(left) > 0 {
		node.Children = append(node.Children, vi.buildTree(left, level+1)...)
	}
	if len(right) > 0 {
		node.Children = append(node.Children, vi.buildTree(right, level+1)...)
	}

	return []*IndexNode{node}
}

func (vi *VectorIndex) Search(query Vector, topK int, approximate bool) []SearchResult {
	if len(query) == 0 {
		return []SearchResult{}
	}

	opts := SearchOptions{
		TopK:        topK,
		Approximate: approximate,
	}

	results, _ := vi.SearchWithOptions(query, opts)
	return results
}

func (vi *VectorIndex) SearchWithOptions(query Vector, opts SearchOptions) ([]SearchResult, error) {
	startTime := time.Now()

	vi.updateQPS()

	if len(query) == 0 {
		if vi.monitoringEnabled {
			vi.metrics.SearchFailedCount.WithLabelValues("empty").Inc()
		}
		return nil, ErrEmptyQuery
	}

	if opts.TopK <= 0 {
		opts.TopK = defaultSearchTopK
	}

	if len(query) != vi.dimension {
		if vi.monitoringEnabled {
			vi.metrics.SearchFailedCount.WithLabelValues("dimension").Inc()
		}
		return nil, wrapError(ErrDimensionMismatch,
			"query: "+itoa(len(query))+", expected: "+itoa(vi.dimension))
	}

	vi.mu.RLock()
	defer vi.mu.RUnlock()

	var results []SearchResult
	var searchType string

	if !vi.built || len(vi.indexTree) == 0 {
		results = vi.bruteForceSearch(query, opts.TopK)
		searchType = "bruteforce"
	} else if opts.Approximate {
		threshold := opts.Threshold
		if threshold <= 0 {
			threshold = defaultApproxThreshold
		}
		results = vi.approximateSearch(query, opts.TopK, threshold)
		searchType = "approximate"
	} else {
		results = vi.exactSearch(query, opts.TopK)
		searchType = "exact"
	}

	duration := time.Since(startTime)
	atomic.StoreInt64(&vi.timing.SearchDurationNS, duration.Nanoseconds())

	if vi.monitoringEnabled {
		approxLabel := "false"
		if opts.Approximate {
			approxLabel = "true"
		}
		vi.metrics.SearchDuration.WithLabelValues(searchType, approxLabel).Observe(duration.Seconds())
		vi.metrics.SearchCount.WithLabelValues(searchType, approxLabel).Inc()
	}

	return results, nil
}

func (vi *VectorIndex) updateQPS() {
	now := time.Now().UnixNano()
	windowStart := atomic.LoadInt64(&vi.searchWindowStart)

	if windowStart == 0 {
		atomic.StoreInt64(&vi.searchWindowStart, now)
		atomic.StoreUint64(&vi.searchQueries, 1)
		return
	}

	elapsed := float64(now-windowStart) / 1e9
	queries := atomic.AddUint64(&vi.searchQueries, 1)

	if elapsed >= 1.0 {
		qps := float64(queries) / elapsed
		if vi.monitoringEnabled {
			vi.metrics.QueriesPerSecond.Set(qps)
		}
		atomic.StoreInt64(&vi.searchWindowStart, now)
		atomic.StoreUint64(&vi.searchQueries, 1)
	}
}

func (vi *VectorIndex) bruteForceSearch(query Vector, topK int) []SearchResult {
	results := make([]SearchResult, 0, topK)

	for id, item := range vi.items {
		distance := vi.distanceFn(query, item.Vector)
		results = append(results, SearchResult{
			ID:       id,
			Distance: distance,
			Data:     item.Data,
		})
	}

	return sortResults(results, topK)
}

func (vi *VectorIndex) exactSearch(query Vector, topK int) []SearchResult {
	results := make([]SearchResult, 0, topK)

	for _, node := range vi.indexTree {
		vi.searchNode(node, query, &results, topK)
	}

	return sortResults(results, topK)
}

func (vi *VectorIndex) searchNode(node *IndexNode, query Vector, results *[]SearchResult, topK int) {
	if node == nil {
		return
	}

	distance := vi.distanceFn(query, node.Vector)
	*results = append(*results, SearchResult{
		ID:       node.ID,
		Distance: distance,
		Data:     vi.items[node.ID].Data,
	})

	for _, child := range node.Children {
		vi.searchNode(child, query, results, topK)
	}
}

func (vi *VectorIndex) approximateSearch(query Vector, topK int, threshold float64) []SearchResult {
	results := make([]SearchResult, 0, topK)

	for _, node := range vi.indexTree {
		vi.approximateSearchNode(node, query, &results, topK, threshold)
	}

	return sortResults(results, topK)
}

func (vi *VectorIndex) approximateSearchNode(node *IndexNode, query Vector, results *[]SearchResult, topK int, threshold float64) {
	if node == nil {
		return
	}

	distance := vi.distanceFn(query, node.Vector)
	*results = append(*results, SearchResult{
		ID:       node.ID,
		Distance: distance,
		Data:     vi.items[node.ID].Data,
	})

	for _, child := range node.Children {
		childDist := vi.distanceFn(query, child.Vector)
		if childDist < threshold {
			vi.approximateSearchNode(child, query, results, topK, threshold)
		}
	}
}

func CosineDistance(a, b Vector) float64 {
	if len(a) != len(b) {
		return math.Inf(1)
	}

	dotProduct := 0.0
	normA := 0.0
	normB := 0.0

	for i := range a {
		dotProduct += a[i] * b[i]
		normA += a[i] * a[i]
		normB += b[i] * b[i]
	}

	if normA == 0 || normB == 0 {
		return 1.0
	}

	similarity := dotProduct / (math.Sqrt(normA) * math.Sqrt(normB))
	return 1.0 - similarity
}

func EuclideanDistance(a, b Vector) float64 {
	if len(a) != len(b) {
		return math.Inf(1)
	}

	sum := 0.0
	for i := range a {
		diff := a[i] - b[i]
		sum += diff * diff
	}

	return math.Sqrt(sum)
}

func sortResults(results []SearchResult, topK int) []SearchResult {
	for i := 0; i < len(results)-1; i++ {
		for j := 0; j < len(results)-i-1; j++ {
			if results[j].Distance > results[j+1].Distance {
				results[j], results[j+1] = results[j+1], results[j]
			}
		}
	}

	if topK > 0 && len(results) > topK {
		return results[:topK]
	}

	return results
}

func (vi *VectorIndex) Size() int {
	vi.mu.RLock()
	defer vi.mu.RUnlock()
	return len(vi.items)
}

func (vi *VectorIndex) Dimension() int {
	return vi.dimension
}

func (vi *VectorIndex) IsBuilt() bool {
	vi.mu.RLock()
	defer vi.mu.RUnlock()
	return vi.built
}

func (vi *VectorIndex) Status() IndexStatus {
	vi.mu.RLock()
	defer vi.mu.RUnlock()
	return vi.status
}

func (vi *VectorIndex) Clear() {
	vi.mu.Lock()
	defer vi.mu.Unlock()

	vi.items = make(map[string]*VectorItem)
	vi.indexTree = make([]*IndexNode, 0)
	vi.built = false
	vi.status = StatusIdle

	if vi.monitoringEnabled {
		vi.metrics.ItemsCount.Set(0)
		vi.metrics.IndexBuilt.Set(0)
	}

	logger.Info("vector index cleared")
}

func (vi *VectorIndex) Stats() map[string]interface{} {
	vi.mu.RLock()
	defer vi.mu.RUnlock()

	stats := map[string]interface{}{
		"running":          vi.IsRunning(),
		"dimension":        vi.dimension,
		"item_count":       len(vi.items),
		"index_built":      vi.built,
		"index_status":     vi.status,
		"tree_depth":       vi.calculateTreeDepth(),
		"monitoring":       vi.monitoringEnabled,
		"last_add_ms":      float64(atomic.LoadInt64(&vi.timing.AddDurationNS)) / 1e6,
		"last_build_ms":    float64(atomic.LoadInt64(&vi.timing.BuildDurationNS)) / 1e6,
		"last_search_ms":   float64(atomic.LoadInt64(&vi.timing.SearchDurationNS)) / 1e6,
		"last_operation_ts": atomic.LoadInt64(&vi.timing.LastOperationTS),
	}

	return stats
}

func (vi *VectorIndex) GetTimingStats() OperationTiming {
	return OperationTiming{
		AddDurationNS:    atomic.LoadInt64(&vi.timing.AddDurationNS),
		BuildDurationNS:  atomic.LoadInt64(&vi.timing.BuildDurationNS),
		SearchDurationNS: atomic.LoadInt64(&vi.timing.SearchDurationNS),
		LastOperationTS:  atomic.LoadInt64(&vi.timing.LastOperationTS),
	}
}

func (vi *VectorIndex) calculateTreeDepth() int {
	if len(vi.indexTree) == 0 {
		return 0
	}

	maxDepth := 0
	for _, root := range vi.indexTree {
		depth := vi.nodeDepth(root)
		if depth > maxDepth {
			maxDepth = depth
		}
	}
	return maxDepth
}

func (vi *VectorIndex) nodeDepth(node *IndexNode) int {
	if node == nil {
		return 0
	}

	maxDepth := 0
	for _, child := range node.Children {
		depth := vi.nodeDepth(child)
		if depth > maxDepth {
			maxDepth = depth
		}
	}

	return maxDepth + 1
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}

	negative := false
	if n < 0 {
		negative = true
		n = -n
	}

	var digits []byte
	for n > 0 {
		digits = append([]byte{byte('0' + n%10)}, digits...)
		n /= 10
	}

	if negative {
		digits = append([]byte{'-'}, digits...)
	}

	return string(digits)
}

func wrapError(base *service.ErrorDetail, detail string) *service.ErrorDetail {
	return service.NewErrorDetail(base.Code, base.Message+": "+detail, base.Cause)
}
