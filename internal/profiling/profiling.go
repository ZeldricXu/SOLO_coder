package profiling

import (
	"fmt"
	"math"
	"runtime"
	"sort"
	"sync"
	"time"

	"session130/internal/logger"
	"session130/pkg/models"
)

type ProfileType string

const (
	CPUProfile    ProfileType = "cpu"
	MemoryProfile ProfileType = "memory"
	GoroutineProfile ProfileType = "goroutine"
)

type StackSample struct {
	Stack    []uintptr
	Count    int64
	Duration time.Duration
}

type FlameNode struct {
	Name     string
	Value    float64
	Children map[string]*FlameNode
}

type Profiler struct {
	mu            sync.RWMutex
	cpuSamples    []StackSample
	memorySamples []runtime.MemStats
	goroutineSnap []int
	active        bool
	stopChan      chan struct{}
	interval      time.Duration
	duration      time.Duration
	profiles      map[string]*models.ProfileData
}

var (
	instance *Profiler
	once     sync.Once
)

func NewProfiler(interval time.Duration) *Profiler {
	return &Profiler{
		cpuSamples:    make([]StackSample, 0, 10000),
		memorySamples: make([]runtime.MemStats, 0, 1000),
		goroutineSnap: make([]int, 0, 1000),
		stopChan:      make(chan struct{}),
		interval:      interval,
		profiles:      make(map[string]*models.ProfileData),
	}
}

func GetProfiler() *Profiler {
	once.Do(func() {
		instance = NewProfiler(100 * time.Millisecond)
	})
	return instance
}

func (p *Profiler) Start(duration time.Duration) {
	p.mu.Lock()
	if p.active {
		p.mu.Unlock()
		return
	}
	p.active = true
	p.duration = duration
	p.stopChan = make(chan struct{})
	p.cpuSamples = p.cpuSamples[:0]
	p.memorySamples = p.memorySamples[:0]
	p.mu.Unlock()

	go p.run()
	logger.Info("", "profiler started", map[string]interface{}{
		"interval_ms": p.interval.Milliseconds(),
		"duration_s":  duration.Seconds(),
	})
}

func (p *Profiler) Stop() {
	p.mu.Lock()
	if !p.active {
		p.mu.Unlock()
		return
	}
	p.active = false
	close(p.stopChan)
	p.mu.Unlock()
	logger.Info("", "profiler stopped", nil)
}

func (p *Profiler) run() {
	ticker := time.NewTicker(p.interval)
	defer ticker.Stop()

	deadline := time.Now().Add(p.duration)
	for {
		select {
		case <-ticker.C:
			if p.duration > 0 && time.Now().After(deadline) {
				p.Stop()
				return
			}
			p.sampleCPU()
			p.sampleMemory()
			p.sampleGoroutine()
		case <-p.stopChan:
			return
		}
	}
}

func (p *Profiler) sampleCPU() {
	buf := make([]uintptr, 32)
	n := runtime.Callers(2, buf)
	if n > 0 {
		stack := make([]uintptr, n)
		copy(stack, buf[:n])

		p.mu.Lock()
		p.cpuSamples = append(p.cpuSamples, StackSample{
			Stack:    stack,
			Count:    1,
			Duration: p.interval,
		})
		if len(p.cpuSamples) > 100000 {
			p.cpuSamples = p.cpuSamples[len(p.cpuSamples)-100000:]
		}
		p.mu.Unlock()
	}
}

func (p *Profiler) sampleMemory() {
	var mem runtime.MemStats
	runtime.ReadMemStats(&mem)

	p.mu.Lock()
	p.memorySamples = append(p.memorySamples, mem)
	if len(p.memorySamples) > 10000 {
		p.memorySamples = p.memorySamples[len(p.memorySamples)-10000:]
	}
	p.mu.Unlock()
}

func (p *Profiler) sampleGoroutine() {
	p.mu.Lock()
	p.goroutineSnap = append(p.goroutineSnap, runtime.NumGoroutine())
	if len(p.goroutineSnap) > 10000 {
		p.goroutineSnap = p.goroutineSnap[len(p.goroutineSnap)-10000:]
	}
	p.mu.Unlock()
}

func (p *Profiler) GetCPUProfile() *models.ProfileData {
	p.mu.RLock()
	defer p.mu.RUnlock()

	profile := &models.ProfileData{
		ProfileID: fmt.Sprintf("cpu_%d", time.Now().UnixNano()),
		Type:      string(CPUProfile),
		StartTime: time.Now().Add(-time.Duration(len(p.cpuSamples)) * p.interval),
		EndTime:   time.Now(),
		Samples:   len(p.cpuSamples),
		Data:      make(map[string]interface{}),
	}

	stackCounts := make(map[string]int64)
	for _, s := range p.cpuSamples {
		key := stackToString(s.Stack)
		stackCounts[key] += s.Count
	}

	profile.Data["stack_counts"] = stackCounts
	profile.Data["total_samples"] = len(p.cpuSamples)
	profile.Data["unique_stacks"] = len(stackCounts)

	p.profiles[profile.ProfileID] = profile
	return profile
}

func (p *Profiler) GetMemoryProfile() *models.ProfileData {
	p.mu.RLock()
	defer p.mu.RUnlock()

	profile := &models.ProfileData{
		ProfileID: fmt.Sprintf("mem_%d", time.Now().UnixNano()),
		Type:      string(MemoryProfile),
		StartTime: time.Now().Add(-time.Duration(len(p.memorySamples)) * p.interval),
		EndTime:   time.Now(),
		Samples:   len(p.memorySamples),
		Data:      make(map[string]interface{}),
	}

	if len(p.memorySamples) > 0 {
		latest := p.memorySamples[len(p.memorySamples)-1]
		profile.Data["alloc"] = latest.Alloc
		profile.Data["total_alloc"] = latest.TotalAlloc
		profile.Data["sys"] = latest.Sys
		profile.Data["heap_alloc"] = latest.HeapAlloc
		profile.Data["heap_sys"] = latest.HeapSys
		profile.Data["heap_inuse"] = latest.HeapInuse
		profile.Data["stack_inuse"] = latest.StackInuse
		profile.Data["num_gc"] = latest.NumGC

		if len(p.memorySamples) > 1 {
			first := p.memorySamples[0]
			profile.Data["alloc_rate_per_sec"] = float64(latest.TotalAlloc-first.TotalAlloc) /
				time.Duration(len(p.memorySamples)-1*int(p.interval)).Seconds()
		}
	}

	p.profiles[profile.ProfileID] = profile
	return profile
}

func (p *Profiler) GetGoroutineProfile() *models.ProfileData {
	p.mu.RLock()
	defer p.mu.RUnlock()

	profile := &models.ProfileData{
		ProfileID: fmt.Sprintf("goroutine_%d", time.Now().UnixNano()),
		Type:      string(GoroutineProfile),
		StartTime: time.Now().Add(-time.Duration(len(p.goroutineSnap)) * p.interval),
		EndTime:   time.Now(),
		Samples:   len(p.goroutineSnap),
		Data:      make(map[string]interface{}),
	}

	if len(p.goroutineSnap) > 0 {
		values := make([]float64, len(p.goroutineSnap))
		for i, v := range p.goroutineSnap {
			values[i] = float64(v)
		}

		profile.Data["current"] = p.goroutineSnap[len(p.goroutineSnap)-1]
		profile.Data["avg"] = average(values)
		profile.Data["p50"] = percentile(values, 50)
		profile.Data["p95"] = percentile(values, 95)
		profile.Data["max"] = maxFloat(values)
		profile.Data["min"] = minFloat(values)
	}

	p.profiles[profile.ProfileID] = profile
	return profile
}

func (p *Profiler) GenerateFlameGraph(profileType ProfileType) *FlameNode {
	p.mu.RLock()
	defer p.mu.RUnlock()

	root := &FlameNode{
		Name:     "root",
		Children: make(map[string]*FlameNode),
	}

	if profileType == CPUProfile {
		for _, sample := range p.cpuSamples {
			p.addStackToFlame(root, sample.Stack, float64(sample.Duration.Nanoseconds()))
		}
	}

	return root
}

func (p *Profiler) addStackToFlame(root *FlameNode, stack []uintptr, value float64) {
	current := root
	current.Value += value

	for i := len(stack) - 1; i >= 0; i-- {
		name := frameToString(stack[i])
		if _, exists := current.Children[name]; !exists {
			current.Children[name] = &FlameNode{
				Name:     name,
				Children: make(map[string]*FlameNode),
			}
		}
		current = current.Children[name]
		current.Value += value
	}
}

func (p *Profiler) CompareProfiles(id1, id2 string) map[string]interface{} {
	p.mu.RLock()
	defer p.mu.RUnlock()

	prof1, ok1 := p.profiles[id1]
	prof2, ok2 := p.profiles[id2]

	if !ok1 || !ok2 {
		return map[string]interface{}{
			"error": "profile not found",
		}
	}

	result := make(map[string]interface{})
	result["profile1"] = prof1.ProfileID
	result["profile2"] = prof2.ProfileID
	result["time_diff"] = prof2.EndTime.Sub(prof1.EndTime).Seconds()

	if prof1.Type == prof2.Type && prof1.Type == string(MemoryProfile) {
		allocDiff := float64(prof2.Data["heap_alloc"].(uint64)) - float64(prof1.Data["heap_alloc"].(uint64))
		result["heap_alloc_diff"] = allocDiff
		result["heap_alloc_diff_pct"] = (allocDiff / float64(prof1.Data["heap_alloc"].(uint64))) * 100
	}

	return result
}

func (p *Profiler) GetStats() map[string]interface{} {
	p.mu.RLock()
	defer p.mu.RUnlock()

	return map[string]interface{}{
		"active":          p.active,
		"cpu_samples":     len(p.cpuSamples),
		"memory_samples":  len(p.memorySamples),
		"goroutine_snaps": len(p.goroutineSnap),
		"interval_ms":     p.interval.Milliseconds(),
	}
}

func stackToString(stack []uintptr) string {
	result := ""
	for i := len(stack) - 1; i >= 0; i-- {
		result += frameToString(stack[i]) + ";"
	}
	return result
}

func frameToString(pc uintptr) string {
	fn := runtime.FuncForPC(pc)
	if fn == nil {
		return fmt.Sprintf("0x%x", pc)
	}
	file, line := fn.FileLine(pc)
	return fmt.Sprintf("%s (%s:%d)", fn.Name(), file, line)
}

func average(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sum := 0.0
	for _, v := range values {
		sum += v
	}
	return sum / float64(len(values))
}

func percentile(values []float64, p float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sorted := make([]float64, len(values))
	copy(sorted, values)
	sort.Float64s(sorted)
	index := int(math.Ceil((p / 100.0) * float64(len(sorted))))
	if index >= len(sorted) {
		index = len(sorted) - 1
	}
	return sorted[index]
}

func maxFloat(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	max := values[0]
	for _, v := range values {
		if v > max {
			max = v
		}
	}
	return max
}

func minFloat(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	min := values[0]
	for _, v := range values {
		if v < min {
			min = v
		}
	}
	return min
}
