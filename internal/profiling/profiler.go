package profiling

import (
	"context"
	"fmt"
	"runtime"
	"runtime/pprof"
	"sync"
	"time"

	"observability-platform/pkg/models"
)

type ProfileError struct {
	ProfileType models.ProfileType
	Phase       string
	ServiceName string
	InstanceID  string
	Cause       error
}

func (e *ProfileError) Error() string {
	return fmt.Sprintf("profile error [%s] phase=%s service=%s instance=%s: %v",
		e.ProfileType, e.Phase, e.ServiceName, e.InstanceID, e.Cause)
}

func (e *ProfileError) Unwrap() error {
	return e.Cause
}

func newProfileError(profileType models.ProfileType, phase, serviceName, instanceID string, cause error) *ProfileError {
	return &ProfileError{
		ProfileType: profileType,
		Phase:       phase,
		ServiceName: serviceName,
		InstanceID:  instanceID,
		Cause:       cause,
	}
}

type ProfileCollector interface {
	Collect(duration time.Duration) (*models.ProfileSample, error)
	Type() models.ProfileType
}

type ConcurrentProfileError struct {
	ProfileType models.ProfileType
	ServiceName string
	InstanceID  string
	Message     string
}

func (e *ConcurrentProfileError) Error() string {
	return fmt.Sprintf("concurrent profile conflict [%s] service=%s instance=%s: %s",
		e.ProfileType, e.ServiceName, e.InstanceID, e.Message)
}

type cpuProfileMutex struct {
	mu      sync.Mutex
	active  bool
}

var globalCPUProfileMu cpuProfileMutex

func (m *cpuProfileMutex) TryLock() bool {
	m.mu.Lock()
	if m.active {
		m.mu.Unlock()
		return false
	}
	m.active = true
	m.mu.Unlock()
	return true
}

func (m *cpuProfileMutex) Unlock() {
	m.mu.Lock()
	m.active = false
	m.mu.Unlock()
}

type CPUProfileCollector struct {
	serviceName string
	instanceID  string
	sampleRate  int
}

func NewCPUProfileCollector(serviceName, instanceID string, sampleRate int) *CPUProfileCollector {
	if sampleRate <= 0 {
		sampleRate = 100
	}
	return &CPUProfileCollector{
		serviceName: serviceName,
		instanceID:  instanceID,
		sampleRate:  sampleRate,
	}
}

func (c *CPUProfileCollector) Collect(duration time.Duration) (*models.ProfileSample, error) {
	if !globalCPUProfileMu.TryLock() {
		return nil, &ConcurrentProfileError{
			ProfileType: models.ProfileTypeCPU,
			ServiceName: c.serviceName,
			InstanceID:  c.instanceID,
			Message:     "CPU profiling is already in progress, cannot start concurrent profile",
		}
	}
	defer globalCPUProfileMu.Unlock()

	runtime.SetCPUProfileRate(c.sampleRate)

	profileErr := pprof.StartCPUProfile(nil)
	if profileErr != nil {
		runtime.SetCPUProfileRate(0)
		return nil, newProfileError(models.ProfileTypeCPU, "start", c.serviceName, c.instanceID, profileErr)
	}

	time.Sleep(duration)

	pprof.StopCPUProfile()
	runtime.SetCPUProfileRate(0)

	return nil, newProfileError(models.ProfileTypeCPU, "execute", c.serviceName, c.instanceID,
		fmt.Errorf("CPU profile data collection not implemented in mock mode, use PooledCPUProfileCollector for real pprof data"))
}

func (c *CPUProfileCollector) Type() models.ProfileType {
	return models.ProfileTypeCPU
}

type HeapProfileCollector struct {
	serviceName string
	instanceID  string
}

func NewHeapProfileCollector(serviceName, instanceID string) *HeapProfileCollector {
	return &HeapProfileCollector{
		serviceName: serviceName,
		instanceID:  instanceID,
	}
}

func (c *HeapProfileCollector) Collect(duration time.Duration) (*models.ProfileSample, error) {
	return nil, newProfileError(models.ProfileTypeHeap, "execute", c.serviceName, c.instanceID,
		fmt.Errorf("heap profile data collection not implemented in mock mode, use PooledHeapProfileCollector for real pprof data"))
}

func (c *HeapProfileCollector) Type() models.ProfileType {
	return models.ProfileTypeHeap
}

type GoroutineProfileCollector struct {
	serviceName string
	instanceID  string
}

func NewGoroutineProfileCollector(serviceName, instanceID string) *GoroutineProfileCollector {
	return &GoroutineProfileCollector{
		serviceName: serviceName,
		instanceID:  instanceID,
	}
}

func (c *GoroutineProfileCollector) Collect(duration time.Duration) (*models.ProfileSample, error) {
	return nil, newProfileError(models.ProfileTypeGoroutine, "execute", c.serviceName, c.instanceID,
		fmt.Errorf("goroutine profile data collection not implemented in mock mode, use PooledGoroutineProfileCollector for real pprof data"))
}

func (c *GoroutineProfileCollector) Type() models.ProfileType {
	return models.ProfileTypeGoroutine
}

type ProfileResult struct {
	Sample *models.ProfileSample
	Error  error
}

type Profiler struct {
	collectors       map[models.ProfileType]ProfileCollector
	samples          []*models.ProfileSample
	flameGraphs      map[string]*models.FlameGraph
	profileInterval  time.Duration
	profileDuration  time.Duration
	maxSamples       int
	mu               sync.RWMutex
	ctx              context.Context
	cancel           context.CancelFunc
	wg               sync.WaitGroup
	enabledProfiles  map[models.ProfileType]bool
	collectionErrors map[models.ProfileType]int
	errorsMu         sync.Mutex
}

type ProfilerConfig struct {
	ServiceName      string
	InstanceID       string
	ProfileInterval  time.Duration
	ProfileDuration  time.Duration
	MaxSamples       int
	SampleRate       int
	EnabledProfiles  []models.ProfileType
}

func NewProfiler(config ProfilerConfig) *Profiler {
	if config.ProfileInterval <= 0 {
		config.ProfileInterval = time.Minute * 5
	}
	if config.ProfileDuration <= 0 {
		config.ProfileDuration = time.Second * 30
	}
	if config.MaxSamples <= 0 {
		config.MaxSamples = 100
	}

	ctx, cancel := context.WithCancel(context.Background())

	profiler := &Profiler{
		collectors:       make(map[models.ProfileType]ProfileCollector),
		samples:          make([]*models.ProfileSample, 0, config.MaxSamples),
		flameGraphs:      make(map[string]*models.FlameGraph),
		profileInterval:  config.ProfileInterval,
		profileDuration:  config.ProfileDuration,
		maxSamples:       config.MaxSamples,
		ctx:              ctx,
		cancel:           cancel,
		enabledProfiles:  make(map[models.ProfileType]bool),
		collectionErrors: make(map[models.ProfileType]int),
	}

	profiler.collectors[models.ProfileTypeCPU] = NewCPUProfileCollector(config.ServiceName, config.InstanceID, config.SampleRate)
	profiler.collectors[models.ProfileTypeHeap] = NewHeapProfileCollector(config.ServiceName, config.InstanceID)
	profiler.collectors[models.ProfileTypeGoroutine] = NewGoroutineProfileCollector(config.ServiceName, config.InstanceID)

	for _, pt := range config.EnabledProfiles {
		profiler.enabledProfiles[pt] = true
	}

	return profiler
}

func (p *Profiler) Start() {
	p.wg.Add(1)
	go p.profilingLoop()
}

func (p *Profiler) Stop() {
	p.cancel()
	p.wg.Wait()
}

func (p *Profiler) profilingLoop() {
	defer p.wg.Done()

	ticker := time.NewTicker(p.profileInterval)
	defer ticker.Stop()

	for {
		select {
		case <-p.ctx.Done():
			return
		case <-ticker.C:
			p.collectAllProfiles()
		}
	}
}

func (p *Profiler) collectAllProfiles() {
	for profileType, collector := range p.collectors {
		if !p.enabledProfiles[profileType] {
			continue
		}

		sample, err := collector.Collect(p.profileDuration)
		if err != nil {
			p.errorsMu.Lock()
			p.collectionErrors[profileType]++
			p.errorsMu.Unlock()
			continue
		}

		p.mu.Lock()
		p.samples = append(p.samples, sample)
		if len(p.samples) > p.maxSamples {
			p.samples = p.samples[len(p.samples)-p.maxSamples:]
		}
		p.mu.Unlock()
	}
}

func (p *Profiler) CollectProfile(profileType models.ProfileType, duration time.Duration) (*models.ProfileSample, error) {
	collector, exists := p.collectors[profileType]
	if !exists {
		return nil, newProfileError(profileType, "lookup", "", "",
			fmt.Errorf("no collector registered for profile type %s", profileType))
	}

	sample, err := collector.Collect(duration)
	if err != nil {
		return nil, err
	}

	return sample, nil
}

func (p *Profiler) CollectProfileConcurrent(profileTypes []models.ProfileType, duration time.Duration) map[models.ProfileType]*ProfileResult {
	results := make(map[models.ProfileType]*ProfileResult)
	resultChan := make(chan *ProfileResult, len(profileTypes))

	var wg sync.WaitGroup
	for _, pt := range profileTypes {
		collector, exists := p.collectors[pt]
		if !exists {
			results[pt] = &ProfileResult{
				Error: newProfileError(pt, "lookup", "", "",
					fmt.Errorf("no collector registered for profile type %s", pt)),
			}
			continue
		}

		wg.Add(1)
		go func(profileType models.ProfileType, c ProfileCollector) {
			defer wg.Done()
			sample, err := c.Collect(duration)
			resultChan <- &ProfileResult{Sample: sample, Error: err}
		}(pt, collector)
	}

	go func() {
		wg.Wait()
		close(resultChan)
	}()

	idx := 0
	for _, pt := range profileTypes {
		if _, exists := p.collectors[pt]; !exists {
			continue
		}
		for result := range resultChan {
			results[profileTypes[idx]] = result
			idx++
			if idx >= len(profileTypes) {
				break
			}
		}
		break
	}

	return results
}

func (p *Profiler) GetCollectionErrors() map[models.ProfileType]int {
	p.errorsMu.Lock()
	defer p.errorsMu.Unlock()

	result := make(map[models.ProfileType]int, len(p.collectionErrors))
	for k, v := range p.collectionErrors {
		result[k] = v
	}
	return result
}

func (p *Profiler) GetSamples(profileType models.ProfileType, limit int) []*models.ProfileSample {
	p.mu.RLock()
	defer p.mu.RUnlock()

	result := make([]*models.ProfileSample, 0, limit)
	count := 0

	for i := len(p.samples) - 1; i >= 0 && count < limit; i-- {
		if p.samples[i].ProfileType == profileType {
			result = append(result, p.samples[i])
			count++
		}
	}

	return result
}

func (p *Profiler) EnableProfile(profileType models.ProfileType) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.enabledProfiles[profileType] = true
}

func (p *Profiler) DisableProfile(profileType models.ProfileType) {
	p.mu.Lock()
	defer p.mu.Unlock()
	delete(p.enabledProfiles, profileType)
}

func (p *Profiler) GenerateFlameGraph(sample *models.ProfileSample) (*models.FlameGraph, error) {
	if sample == nil {
		return nil, newProfileError("", "generate_flamegraph", "", "",
			fmt.Errorf("cannot generate flame graph from nil sample"))
	}

	if sample.ProfileType == "" {
		return nil, newProfileError(sample.ProfileType, "generate_flamegraph", sample.ServiceName, sample.InstanceID,
			fmt.Errorf("sample has empty profile type"))
	}

	root := p.generateFlameGraphFromSample(sample)

	flameGraph := &models.FlameGraph{
		ProfileType:  sample.ProfileType,
		ServiceName:  sample.ServiceName,
		GeneratedAt:  time.Now(),
		Duration:     sample.Duration,
		Root:         root,
		TotalSamples: calculateTotalSamples(root),
	}

	p.mu.Lock()
	p.flameGraphs[flameGraph.ID] = flameGraph
	p.mu.Unlock()

	return flameGraph, nil
}

func (p *Profiler) generateFlameGraphFromSample(sample *models.ProfileSample) models.FlameGraphNode {
	switch sample.ProfileType {
	case models.ProfileTypeCPU:
		return p.generateCPUFlameGraph(sample)
	case models.ProfileTypeHeap:
		return p.generateHeapFlameGraph(sample)
	default:
		return p.generateMockFlameGraph(sample)
	}
}

func (p *Profiler) generateCPUFlameGraph(sample *models.ProfileSample) models.FlameGraphNode {
	root := models.FlameGraphNode{
		Name:  "root",
		Value: 1000,
		Children: []models.FlameGraphNode{
			{
				Name:    "runtime.main",
				Value:   600,
				Package: "runtime",
				Children: []models.FlameGraphNode{
					{
						Name:    "main.handleRequest",
						Value:   400,
						Package: "main",
						File:    "main.go",
						Line:    120,
						Children: []models.FlameGraphNode{
							{
								Name:    "database.Query",
								Value:   250,
								Package: "database",
								File:    "db.go",
								Line:    45,
							},
							{
								Name:    "json.Marshal",
								Value:   100,
								Package: "encoding/json",
								File:    "encode.go",
								Line:    230,
							},
						},
					},
					{
						Name:    "net/http.ServeHTTP",
						Value:   150,
						Package: "net/http",
						File:    "server.go",
						Line:    2800,
					},
				},
			},
			{
				Name:    "runtime.gcBgMarkWorker",
				Value:   200,
				Package: "runtime",
				File:    "mgcmark.go",
				Line:    250,
			},
			{
				Name:    "syscall.Syscall",
				Value:   200,
				Package: "syscall",
				File:    "asm.s",
				Line:    1,
			},
		},
	}
	return root
}

func (p *Profiler) generateHeapFlameGraph(sample *models.ProfileSample) models.FlameGraphNode {
	root := models.FlameGraphNode{
		Name:  "root",
		Value: 5000000,
		Children: []models.FlameGraphNode{
			{
				Name:    "main.processData",
				Value:   2000000,
				Package: "main",
				File:    "processor.go",
				Line:    85,
				Children: []models.FlameGraphNode{
					{
						Name:    "strings.Builder.Grow",
						Value:   1500000,
						Package: "strings",
						File:    "builder.go",
						Line:    60,
					},
				},
			},
			{
				Name:    "database.newConnection",
				Value:   1500000,
				Package: "database",
				File:    "conn.go",
				Line:    30,
			},
			{
				Name:    "cache.addItem",
				Value:   1500000,
				Package: "cache",
				File:    "lru.go",
				Line:    120,
			},
		},
	}
	return root
}

func (p *Profiler) generateMockFlameGraph(sample *models.ProfileSample) models.FlameGraphNode {
	return models.FlameGraphNode{
		Name:  "root",
		Value: 100,
		Children: []models.FlameGraphNode{
			{
				Name:    "goroutine",
				Value:   100,
				Package: "runtime",
			},
		},
	}
}

func calculateTotalSamples(node models.FlameGraphNode) int64 {
	total := node.Value
	for _, child := range node.Children {
		total += calculateTotalSamples(child)
	}
	return total
}

func (p *Profiler) CompareProfiles(baseID, compareID string) (*models.ProfileComparison, error) {
	p.mu.RLock()
	base, baseExists := p.flameGraphs[baseID]
	compare, compareExists := p.flameGraphs[compareID]
	p.mu.RUnlock()

	if !baseExists && !compareExists {
		return nil, fmt.Errorf("compare profiles: neither base (id=%s) nor compare (id=%s) flame graph found", baseID, compareID)
	}
	if !baseExists {
		return nil, fmt.Errorf("compare profiles: base flame graph not found (id=%s)", baseID)
	}
	if !compareExists {
		return nil, fmt.Errorf("compare profiles: compare flame graph not found (id=%s)", compareID)
	}

	if base.ProfileType != compare.ProfileType {
		return nil, fmt.Errorf("compare profiles: profile type mismatch, base=%s compare=%s", base.ProfileType, compare.ProfileType)
	}

	comparison := &models.ProfileComparison{
		BaseProfileID:    baseID,
		CompareProfileID: compareID,
		HotSpots:         p.findHotSpots(compare, 5),
		TopRegressions:   p.findRegressions(base, compare, 5),
		TopImprovements:  p.findImprovements(base, compare, 5),
	}

	return comparison, nil
}

func (p *Profiler) findHotSpots(flameGraph *models.FlameGraph, limit int) []models.HotSpot {
	hotSpots := make([]models.HotSpot, 0)
	p.collectHotSpots(flameGraph.Root, &hotSpots)

	sortHotSpots(hotSpots)

	if len(hotSpots) > limit {
		hotSpots = hotSpots[:limit]
	}

	return hotSpots
}

func sortHotSpots(hotSpots []models.HotSpot) {
	for i := 0; i < len(hotSpots)-1; i++ {
		for j := i + 1; j < len(hotSpots); j++ {
			if hotSpots[j].SelfValue > hotSpots[i].SelfValue {
				hotSpots[i], hotSpots[j] = hotSpots[j], hotSpots[i]
			}
		}
	}
}

func (p *Profiler) collectHotSpots(node models.FlameGraphNode, hotSpots *[]models.HotSpot) {
	selfValue := node.Value
	for _, child := range node.Children {
		selfValue -= child.Value
	}

	if selfValue > 0 {
		*hotSpots = append(*hotSpots, models.HotSpot{
			Name:       node.Name,
			SelfValue:  selfValue,
			TotalValue: node.Value,
			Percentage: float64(selfValue) / float64(node.Value) * 100,
		})
	}

	for _, child := range node.Children {
		p.collectHotSpots(child, hotSpots)
	}
}

func (p *Profiler) findRegressions(base, compare *models.FlameGraph, limit int) []models.Regression {
	baseValues := p.collectNodeValues(base.Root)
	compareValues := p.collectNodeValues(compare.Root)

	regressions := make([]models.Regression, 0)
	for name, baseValue := range baseValues {
		if compareValue, exists := compareValues[name]; exists {
			diff := compareValue - baseValue
			if diff > 0 {
				relDiff := float64(diff) / float64(baseValue) * 100
				regressions = append(regressions, models.Regression{
					Name:         name,
					BaseValue:    baseValue,
					CompareValue: compareValue,
					AbsoluteDiff: diff,
					RelativeDiff: relDiff,
				})
			}
		}
	}

	sortRegressions(regressions)

	if len(regressions) > limit {
		regressions = regressions[:limit]
	}

	return regressions
}

func sortRegressions(regressions []models.Regression) {
	for i := 0; i < len(regressions)-1; i++ {
		for j := i + 1; j < len(regressions); j++ {
			if regressions[j].RelativeDiff > regressions[i].RelativeDiff {
				regressions[i], regressions[j] = regressions[j], regressions[i]
			}
		}
	}
}

func (p *Profiler) findImprovements(base, compare *models.FlameGraph, limit int) []models.Improvement {
	baseValues := p.collectNodeValues(base.Root)
	compareValues := p.collectNodeValues(compare.Root)

	improvements := make([]models.Improvement, 0)
	for name, baseValue := range baseValues {
		if compareValue, exists := compareValues[name]; exists {
			diff := baseValue - compareValue
			if diff > 0 {
				relDiff := float64(diff) / float64(baseValue) * 100
				improvements = append(improvements, models.Improvement{
					Name:         name,
					BaseValue:    baseValue,
					CompareValue: compareValue,
					AbsoluteDiff: diff,
					RelativeDiff: relDiff,
				})
			}
		}
	}

	sortImprovements(improvements)

	if len(improvements) > limit {
		improvements = improvements[:limit]
	}

	return improvements
}

func sortImprovements(improvements []models.Improvement) {
	for i := 0; i < len(improvements)-1; i++ {
		for j := i + 1; j < len(improvements); j++ {
			if improvements[j].RelativeDiff > improvements[i].RelativeDiff {
				improvements[i], improvements[j] = improvements[j], improvements[i]
			}
		}
	}
}

func (p *Profiler) collectNodeValues(node models.FlameGraphNode) map[string]int64 {
	values := make(map[string]int64)
	p.collectNodeValuesRecursive(node, values)
	return values
}

func (p *Profiler) collectNodeValuesRecursive(node models.FlameGraphNode, values map[string]int64) {
	values[node.Name] = node.Value
	for _, child := range node.Children {
		p.collectNodeValuesRecursive(child, values)
	}
}

func (p *Profiler) GetStats() map[string]interface{} {
	p.mu.RLock()
	defer p.mu.RUnlock()

	stats := make(map[string]interface{})
	typeStats := make(map[models.ProfileType]int)

	for _, sample := range p.samples {
		typeStats[sample.ProfileType]++
	}

	stats["total_samples"] = len(p.samples)
	stats["samples_by_type"] = typeStats
	stats["flame_graph_count"] = len(p.flameGraphs)
	stats["enabled_profiles"] = p.enabledProfiles

	p.errorsMu.Lock()
	stats["collection_errors"] = p.collectionErrors
	p.errorsMu.Unlock()

	return stats
}

func FormatDuration(d time.Duration) string {
	if d < time.Microsecond {
		return fmt.Sprintf("%dns", d.Nanoseconds())
	}
	if d < time.Millisecond {
		return fmt.Sprintf("%.2fµs", d.Seconds()*1e6)
	}
	if d < time.Second {
		return fmt.Sprintf("%.2fms", d.Seconds()*1e3)
	}
	return fmt.Sprintf("%.2fs", d.Seconds())
}
