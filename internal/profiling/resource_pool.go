package profiling

import (
	"bytes"
	"fmt"
	"runtime"
	"runtime/pprof"
	"sync"
	"sync/atomic"
	"time"

	"observability-platform/pkg/models"
)

type PoolStats struct {
	Gets        int64
	Puts        int64
	Hits        int64
	Misses      int64
	InUse       int64
	MaxInUse    int64
	TotalCreated int64
	TotalDestroyed int64
}

func (ps *PoolStats) recordGet(hit bool) {
	atomic.AddInt64(&ps.Gets, 1)
	if hit {
		atomic.AddInt64(&ps.Hits, 1)
	} else {
		atomic.AddInt64(&ps.Misses, 1)
		atomic.AddInt64(&ps.TotalCreated, 1)
	}
	inUse := atomic.AddInt64(&ps.InUse, 1)
	if max := atomic.LoadInt64(&ps.MaxInUse); inUse > max {
		atomic.CompareAndSwapInt64(&ps.MaxInUse, max, inUse)
	}
}

func (ps *PoolStats) recordPut() {
	atomic.AddInt64(&ps.Puts, 1)
	atomic.AddInt64(&ps.InUse, -1)
}

func (ps *PoolStats) recordDestroy() {
	atomic.AddInt64(&ps.TotalDestroyed, 1)
}

func (ps *PoolStats) Snapshot() map[string]interface{} {
	return map[string]interface{}{
		"gets":           atomic.LoadInt64(&ps.Gets),
		"puts":           atomic.LoadInt64(&ps.Puts),
		"hits":           atomic.LoadInt64(&ps.Hits),
		"misses":         atomic.LoadInt64(&ps.Misses),
		"hit_rate":       float64(atomic.LoadInt64(&ps.Hits)) / float64(max(1, atomic.LoadInt64(&ps.Gets))) * 100,
		"in_use":         atomic.LoadInt64(&ps.InUse),
		"max_in_use":     atomic.LoadInt64(&ps.MaxInUse),
		"total_created":  atomic.LoadInt64(&ps.TotalCreated),
		"total_destroyed": atomic.LoadInt64(&ps.TotalDestroyed),
	}
}

func max(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}

type BufferPool struct {
	pool     sync.Pool
	stats    PoolStats
	minSize  int
	maxSize  int
	maxCount int
	count    int64
}

func NewBufferPool(minSize, maxSize int, maxCount int) *BufferPool {
	if minSize <= 0 {
		minSize = 1024
	}
	if maxSize <= 0 {
		maxSize = 10 * 1024 * 1024
	}
	if maxCount <= 0 {
		maxCount = 100
	}

	return &BufferPool{
		pool: sync.Pool{
			New: func() interface{} {
				return new(bytes.Buffer)
			},
		},
		minSize:  minSize,
		maxSize:  maxSize,
		maxCount: maxCount,
	}
}

func (bp *BufferPool) Get() *bytes.Buffer {
	buf := bp.pool.Get().(*bytes.Buffer)
	buf.Reset()
	bp.stats.recordGet(true)
	return buf
}

func (bp *BufferPool) Put(buf *bytes.Buffer) {
	if buf == nil {
		return
	}

	if atomic.LoadInt64(&bp.count) >= int64(bp.maxCount) {
		bp.stats.recordDestroy()
		return
	}

	if buf.Cap() > bp.maxSize || buf.Cap() < bp.minSize {
		bp.stats.recordDestroy()
		return
	}

	bp.pool.Put(buf)
	atomic.AddInt64(&bp.count, 1)
	bp.stats.recordPut()
}

func (bp *BufferPool) Stats() map[string]interface{} {
	stats := bp.stats.Snapshot()
	stats["min_size"] = bp.minSize
	stats["max_size"] = bp.maxSize
	stats["max_count"] = bp.maxCount
	stats["current_count"] = atomic.LoadInt64(&bp.count)
	return stats
}

type FlameGraphNodePool struct {
	pool     sync.Pool
	stats    PoolStats
	maxCount int
	count    int64
}

func NewFlameGraphNodePool(maxCount int) *FlameGraphNodePool {
	if maxCount <= 0 {
		maxCount = 1000
	}

	return &FlameGraphNodePool{
		pool: sync.Pool{
			New: func() interface{} {
				return &models.FlameGraphNode{}
			},
		},
		maxCount: maxCount,
	}
}

func (fp *FlameGraphNodePool) Get() *models.FlameGraphNode {
	node := fp.pool.Get().(*models.FlameGraphNode)
	fp.resetNode(node)
	fp.stats.recordGet(true)
	return node
}

func (fp *FlameGraphNodePool) resetNode(node *models.FlameGraphNode) {
	node.Name = ""
	node.Value = 0
	node.Package = ""
	node.File = ""
	node.Line = 0
	if node.Children != nil {
		fp.PutChildren(node.Children)
	}
	node.Children = nil
}

func (fp *FlameGraphNodePool) Put(node *models.FlameGraphNode) {
	if node == nil {
		return
	}

	if atomic.LoadInt64(&fp.count) >= int64(fp.maxCount) {
		fp.stats.recordDestroy()
		return
	}

	fp.pool.Put(node)
	atomic.AddInt64(&fp.count, 1)
	fp.stats.recordPut()
}

func (fp *FlameGraphNodePool) PutChildren(children []models.FlameGraphNode) {
	for i := range children {
		fp.PutChild(&children[i])
	}
}

func (fp *FlameGraphNodePool) PutChild(child *models.FlameGraphNode) {
	if child == nil {
		return
	}

	if child.Children != nil {
		fp.PutChildren(child.Children)
	}

	if atomic.LoadInt64(&fp.count) >= int64(fp.maxCount) {
		fp.stats.recordDestroy()
		return
	}

	fp.pool.Put(child)
	atomic.AddInt64(&fp.count, 1)
	fp.stats.recordPut()
}

func (fp *FlameGraphNodePool) Stats() map[string]interface{} {
	stats := fp.stats.Snapshot()
	stats["max_count"] = fp.maxCount
	stats["current_count"] = atomic.LoadInt64(&fp.count)
	return stats
}

type ProfileSamplePool struct {
	pool     sync.Pool
	stats    PoolStats
	maxCount int
	count    int64
}

func NewProfileSamplePool(maxCount int) *ProfileSamplePool {
	if maxCount <= 0 {
		maxCount = 100
	}

	return &ProfileSamplePool{
		pool: sync.Pool{
			New: func() interface{} {
				return &models.ProfileSample{}
			},
		},
		maxCount: maxCount,
	}
}

func (pp *ProfileSamplePool) Get() *models.ProfileSample {
	sample := pp.pool.Get().(*models.ProfileSample)
	pp.resetSample(sample)
	pp.stats.recordGet(true)
	return sample
}

func (pp *ProfileSamplePool) resetSample(sample *models.ProfileSample) {
	sample.Timestamp = time.Time{}
	sample.ProfileType = ""
	sample.Duration = 0
	sample.SampleRate = 0
	sample.Data = nil
	sample.ServiceName = ""
	sample.InstanceID = ""
	sample.Labels = nil
}

func (pp *ProfileSamplePool) Put(sample *models.ProfileSample) {
	if sample == nil {
		return
	}

	if atomic.LoadInt64(&pp.count) >= int64(pp.maxCount) {
		pp.stats.recordDestroy()
		return
	}

	if sample.Data != nil && cap(sample.Data) > 10*1024*1024 {
		pp.stats.recordDestroy()
		return
	}

	pp.pool.Put(sample)
	atomic.AddInt64(&pp.count, 1)
	pp.stats.recordPut()
}

func (pp *ProfileSamplePool) Stats() map[string]interface{} {
	stats := pp.stats.Snapshot()
	stats["max_count"] = pp.maxCount
	stats["current_count"] = atomic.LoadInt64(&pp.count)
	return stats
}

type Int64SlicePool struct {
	pool     sync.Pool
	stats    PoolStats
	maxSize  int
	maxCount int
	count    int64
}

func NewInt64SlicePool(maxSize, maxCount int) *Int64SlicePool {
	if maxSize <= 0 {
		maxSize = 10000
	}
	if maxCount <= 0 {
		maxCount = 100
	}

	return &Int64SlicePool{
		pool: sync.Pool{
			New: func() interface{} {
				return make([]int64, 0, 1024)
			},
		},
		maxSize:  maxSize,
		maxCount: maxCount,
	}
}

func (ip *Int64SlicePool) Get(size int) []int64 {
	slice := ip.pool.Get().([]int64)
	if cap(slice) < size {
		ip.stats.recordDestroy()
		slice = make([]int64, 0, size)
		ip.stats.recordGet(false)
	} else {
		slice = slice[:0]
		ip.stats.recordGet(true)
	}
	return slice
}

func (ip *Int64SlicePool) Put(slice []int64) {
	if slice == nil {
		return
	}

	if atomic.LoadInt64(&ip.count) >= int64(ip.maxCount) {
		ip.stats.recordDestroy()
		return
	}

	if cap(slice) > ip.maxSize {
		ip.stats.recordDestroy()
		return
	}

	ip.pool.Put(slice[:0])
	atomic.AddInt64(&ip.count, 1)
	ip.stats.recordPut()
}

func (ip *Int64SlicePool) Stats() map[string]interface{} {
	stats := ip.stats.Snapshot()
	stats["max_size"] = ip.maxSize
	stats["max_count"] = ip.maxCount
	stats["current_count"] = atomic.LoadInt64(&ip.count)
	return stats
}

type StringSlicePool struct {
	pool     sync.Pool
	stats    PoolStats
	maxSize  int
	maxCount int
	count    int64
}

func NewStringSlicePool(maxSize, maxCount int) *StringSlicePool {
	if maxSize <= 0 {
		maxSize = 10000
	}
	if maxCount <= 0 {
		maxCount = 100
	}

	return &StringSlicePool{
		pool: sync.Pool{
			New: func() interface{} {
				return make([]string, 0, 1024)
			},
		},
		maxSize:  maxSize,
		maxCount: maxCount,
	}
}

func (sp *StringSlicePool) Get(size int) []string {
	slice := sp.pool.Get().([]string)
	if cap(slice) < size {
		sp.stats.recordDestroy()
		slice = make([]string, 0, size)
		sp.stats.recordGet(false)
	} else {
		slice = slice[:0]
		sp.stats.recordGet(true)
	}
	return slice
}

func (sp *StringSlicePool) Put(slice []string) {
	if slice == nil {
		return
	}

	if atomic.LoadInt64(&sp.count) >= int64(sp.maxCount) {
		sp.stats.recordDestroy()
		return
	}

	if cap(slice) > sp.maxSize {
		sp.stats.recordDestroy()
		return
	}

	sp.pool.Put(slice[:0])
	atomic.AddInt64(&sp.count, 1)
	sp.stats.recordPut()
}

func (sp *StringSlicePool) Stats() map[string]interface{} {
	stats := sp.stats.Snapshot()
	stats["max_size"] = sp.maxSize
	stats["max_count"] = sp.maxCount
	stats["current_count"] = atomic.LoadInt64(&sp.count)
	return stats
}

type ResourcePoolManager struct {
	bufferPool      *BufferPool
	nodePool        *FlameGraphNodePool
	samplePool      *ProfileSamplePool
	int64SlicePool  *Int64SlicePool
	stringSlicePool *StringSlicePool
}

type PoolConfig struct {
	BufferMinSize     int
	BufferMaxSize     int
	BufferMaxCount    int
	NodeMaxCount      int
	SampleMaxCount    int
	Int64SliceMaxSize int
	Int64SliceMaxCount int
	StringSliceMaxSize int
	StringSliceMaxCount int
}

func DefaultPoolConfig() PoolConfig {
	return PoolConfig{
		BufferMinSize:      1024,
		BufferMaxSize:      10 * 1024 * 1024,
		BufferMaxCount:     50,
		NodeMaxCount:       1000,
		SampleMaxCount:     50,
		Int64SliceMaxSize:  10000,
		Int64SliceMaxCount: 50,
		StringSliceMaxSize: 10000,
		StringSliceMaxCount: 50,
	}
}

func NewResourcePoolManager(config PoolConfig) *ResourcePoolManager {
	return &ResourcePoolManager{
		bufferPool:      NewBufferPool(config.BufferMinSize, config.BufferMaxSize, config.BufferMaxCount),
		nodePool:        NewFlameGraphNodePool(config.NodeMaxCount),
		samplePool:      NewProfileSamplePool(config.SampleMaxCount),
		int64SlicePool:  NewInt64SlicePool(config.Int64SliceMaxSize, config.Int64SliceMaxCount),
		stringSlicePool: NewStringSlicePool(config.StringSliceMaxSize, config.StringSliceMaxCount),
	}
}

func (rpm *ResourcePoolManager) Buffer() *BufferPool         { return rpm.bufferPool }
func (rpm *ResourcePoolManager) Node() *FlameGraphNodePool  { return rpm.nodePool }
func (rpm *ResourcePoolManager) Sample() *ProfileSamplePool { return rpm.samplePool }
func (rpm *ResourcePoolManager) Int64Slice() *Int64SlicePool { return rpm.int64SlicePool }
func (rpm *ResourcePoolManager) StringSlice() *StringSlicePool { return rpm.stringSlicePool }

func (rpm *ResourcePoolManager) Stats() map[string]interface{} {
	return map[string]interface{}{
		"buffer_pool":       rpm.bufferPool.Stats(),
		"node_pool":         rpm.nodePool.Stats(),
		"sample_pool":       rpm.samplePool.Stats(),
		"int64_slice_pool":  rpm.int64SlicePool.Stats(),
		"string_slice_pool": rpm.stringSlicePool.Stats(),
	}
}

func (rpm *ResourcePoolManager) Summary() string {
	stats := rpm.Stats()
	bufferStats := stats["buffer_pool"].(map[string]interface{})
	nodeStats := stats["node_pool"].(map[string]interface{})
	sampleStats := stats["sample_pool"].(map[string]interface{})

	return fmt.Sprintf(
		"Pool Manager Summary:\n"+
			"  Buffer Pool: %.1f%% hit rate, %d in use, %d max in use\n"+
			"  Node Pool:   %.1f%% hit rate, %d in use, %d max in use\n"+
			"  Sample Pool: %.1f%% hit rate, %d in use, %d max in use",
		bufferStats["hit_rate"].(float64),
		bufferStats["in_use"].(int64),
		bufferStats["max_in_use"].(int64),
		nodeStats["hit_rate"].(float64),
		nodeStats["in_use"].(int64),
		nodeStats["max_in_use"].(int64),
		sampleStats["hit_rate"].(float64),
		sampleStats["in_use"].(int64),
		sampleStats["max_in_use"].(int64),
	)
}

type PooledCPUProfileCollector struct {
	*CPUProfileCollector
	poolManager *ResourcePoolManager
}

func NewPooledCPUProfileCollector(serviceName, instanceID string, sampleRate int, poolManager *ResourcePoolManager) *PooledCPUProfileCollector {
	return &PooledCPUProfileCollector{
		CPUProfileCollector: NewCPUProfileCollector(serviceName, instanceID, sampleRate),
		poolManager:         poolManager,
	}
}

func (c *PooledCPUProfileCollector) Collect(duration time.Duration) (*models.ProfileSample, error) {
	buf := c.poolManager.Buffer().Get()
	defer c.poolManager.Buffer().Put(buf)

	runtime.SetCPUProfileRate(c.sampleRate)
	if err := pprof.StartCPUProfile(buf); err != nil {
		return nil, err
	}

	time.Sleep(duration)

	pprof.StopCPUProfile()
	runtime.SetCPUProfileRate(0)

	sample := c.poolManager.Sample().Get()
	sample.Timestamp = time.Now()
	sample.ProfileType = models.ProfileTypeCPU
	sample.Duration = duration
	sample.SampleRate = c.sampleRate
	sample.Data = make([]byte, buf.Len())
	copy(sample.Data, buf.Bytes())
	sample.ServiceName = c.serviceName
	sample.InstanceID = c.instanceID

	return sample, nil
}

type PooledHeapProfileCollector struct {
	*HeapProfileCollector
	poolManager *ResourcePoolManager
}

func NewPooledHeapProfileCollector(serviceName, instanceID string, poolManager *ResourcePoolManager) *PooledHeapProfileCollector {
	return &PooledHeapProfileCollector{
		HeapProfileCollector: NewHeapProfileCollector(serviceName, instanceID),
		poolManager:          poolManager,
	}
}

func (c *PooledHeapProfileCollector) Collect(duration time.Duration) (*models.ProfileSample, error) {
	buf := c.poolManager.Buffer().Get()
	defer c.poolManager.Buffer().Put(buf)

	if err := pprof.WriteHeapProfile(buf); err != nil {
		return nil, err
	}

	sample := c.poolManager.Sample().Get()
	sample.Timestamp = time.Now()
	sample.ProfileType = models.ProfileTypeHeap
	sample.Duration = duration
	sample.Data = make([]byte, buf.Len())
	copy(sample.Data, buf.Bytes())
	sample.ServiceName = c.serviceName
	sample.InstanceID = c.instanceID

	return sample, nil
}

type PooledGoroutineProfileCollector struct {
	*GoroutineProfileCollector
	poolManager *ResourcePoolManager
}

func NewPooledGoroutineProfileCollector(serviceName, instanceID string, poolManager *ResourcePoolManager) *PooledGoroutineProfileCollector {
	return &PooledGoroutineProfileCollector{
		GoroutineProfileCollector: NewGoroutineProfileCollector(serviceName, instanceID),
		poolManager:               poolManager,
	}
}

func (c *PooledGoroutineProfileCollector) Collect(duration time.Duration) (*models.ProfileSample, error) {
	buf := c.poolManager.Buffer().Get()
	defer c.poolManager.Buffer().Put(buf)

	profile := pprof.Lookup("goroutine")
	if profile == nil {
		return nil, fmt.Errorf("goroutine profile not found")
	}

	if err := profile.WriteTo(buf, 0); err != nil {
		return nil, err
	}

	sample := c.poolManager.Sample().Get()
	sample.Timestamp = time.Now()
	sample.ProfileType = models.ProfileTypeGoroutine
	sample.Duration = duration
	sample.Data = make([]byte, buf.Len())
	copy(sample.Data, buf.Bytes())
	sample.ServiceName = c.serviceName
	sample.InstanceID = c.instanceID

	return sample, nil
}

type ProfilerWithPool struct {
	*Profiler
	poolManager *ResourcePoolManager
}

func NewProfilerWithPool(config ProfilerConfig, poolConfig PoolConfig) *ProfilerWithPool {
	profiler := NewProfiler(config)
	poolManager := NewResourcePoolManager(poolConfig)

	profiler.collectors[models.ProfileTypeCPU] = NewPooledCPUProfileCollector(
		config.ServiceName, config.InstanceID, config.SampleRate, poolManager,
	)
	profiler.collectors[models.ProfileTypeHeap] = NewPooledHeapProfileCollector(
		config.ServiceName, config.InstanceID, poolManager,
	)
	profiler.collectors[models.ProfileTypeGoroutine] = NewPooledGoroutineProfileCollector(
		config.ServiceName, config.InstanceID, poolManager,
	)

	return &ProfilerWithPool{
		Profiler:    profiler,
		poolManager: poolManager,
	}
}

func (p *ProfilerWithPool) GetPoolManager() *ResourcePoolManager {
	return p.poolManager
}

func (p *ProfilerWithPool) GetPoolStats() map[string]interface{} {
	return p.poolManager.Stats()
}

func (p *ProfilerWithPool) ReleaseSample(sample *models.ProfileSample) {
	p.poolManager.Sample().Put(sample)
}

func (p *ProfilerWithPool) GetStats() map[string]interface{} {
	baseStats := p.Profiler.GetStats()
	poolStats := p.poolManager.Stats()
	baseStats["pool_stats"] = poolStats
	return baseStats
}
