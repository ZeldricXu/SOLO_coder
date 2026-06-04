package windowing

import (
	"sync"
	"time"

	"log-pipeline/pkg/config"
)

type WindowStrategy interface {
	WindowSize() time.Duration
	WindowStep() time.Duration
	OnLogProcessed(count int64)
	CurrentRate() float64
}

type FixedWindowStrategy struct {
	windowSize time.Duration
	windowStep time.Duration
}

func NewFixedWindowStrategy(windowSize, windowStep time.Duration) *FixedWindowStrategy {
	return &FixedWindowStrategy{
		windowSize: windowSize,
		windowStep: windowStep,
	}
}

func (s *FixedWindowStrategy) WindowSize() time.Duration {
	return s.windowSize
}

func (s *FixedWindowStrategy) WindowStep() time.Duration {
	return s.windowStep
}

func (s *FixedWindowStrategy) OnLogProcessed(count int64) {}

func (s *FixedWindowStrategy) CurrentRate() float64 {
	return 0
}

type AdaptiveWindowStrategy struct {
	mu                sync.RWMutex
	minWindowSize     time.Duration
	maxWindowSize     time.Duration
	minStep           time.Duration
	maxStep           time.Duration
	lowRateThreshold  float64
	highRateThreshold float64
	currentWindowSize time.Duration
	currentStep       time.Duration
	rateWindow        time.Duration
	rateCounter       *rateCounter
}

type rateCounter struct {
	mu       sync.Mutex
	counts   []rateSample
	window   time.Duration
	lastRate float64
}

type rateSample struct {
	timestamp time.Time
	count     int64
}

func newRateCounter(window time.Duration) *rateCounter {
	return &rateCounter{
		counts: make([]rateSample, 0),
		window: window,
	}
}

func (rc *rateCounter) add(count int64) {
	rc.mu.Lock()
	defer rc.mu.Unlock()

	now := time.Now()
	rc.counts = append(rc.counts, rateSample{timestamp: now, count: count})

	cutoff := now.Add(-rc.window)
	i := 0
	for i < len(rc.counts) && rc.counts[i].timestamp.Before(cutoff) {
		i++
	}
	rc.counts = rc.counts[i:]

	if len(rc.counts) >= 2 {
		oldest := rc.counts[0]
		newest := rc.counts[len(rc.counts)-1]
		elapsed := newest.timestamp.Sub(oldest.timestamp).Seconds()
		if elapsed > 0 {
			totalCount := int64(0)
			for _, s := range rc.counts {
				totalCount += s.count
			}
			rc.lastRate = float64(totalCount) / elapsed
		}
	}
}

func (rc *rateCounter) rate() float64 {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	return rc.lastRate
}

type AdaptiveWindowConfig struct {
	MinWindowSize     time.Duration
	MaxWindowSize     time.Duration
	MinStep           time.Duration
	MaxStep           time.Duration
	LowRateThreshold  float64
	HighRateThreshold float64
	RateWindow        time.Duration
}

func NewAdaptiveWindowStrategy(cfg AdaptiveWindowConfig) *AdaptiveWindowStrategy {
	if cfg.RateWindow == 0 {
		cfg.RateWindow = time.Minute
	}
	return &AdaptiveWindowStrategy{
		minWindowSize:     cfg.MinWindowSize,
		maxWindowSize:     cfg.MaxWindowSize,
		minStep:           cfg.MinStep,
		maxStep:           cfg.MaxStep,
		lowRateThreshold:  cfg.LowRateThreshold,
		highRateThreshold: cfg.HighRateThreshold,
		currentWindowSize: cfg.MaxWindowSize,
		currentStep:       cfg.MaxStep,
		rateWindow:        cfg.RateWindow,
		rateCounter:       newRateCounter(cfg.RateWindow),
	}
}

func (s *AdaptiveWindowStrategy) WindowSize() time.Duration {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.currentWindowSize
}

func (s *AdaptiveWindowStrategy) WindowStep() time.Duration {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.currentStep
}

func (s *AdaptiveWindowStrategy) OnLogProcessed(count int64) {
	s.rateCounter.add(count)

	rate := s.rateCounter.rate()
	s.adjust(rate)
}

func (s *AdaptiveWindowStrategy) CurrentRate() float64 {
	return s.rateCounter.rate()
}

func (s *AdaptiveWindowStrategy) adjust(rate float64) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.highRateThreshold <= s.lowRateThreshold {
		s.currentWindowSize = s.maxWindowSize
		s.currentStep = s.maxStep
		return
	}

	var targetSize time.Duration
	var targetStep time.Duration

	if rate >= s.highRateThreshold {
		targetSize = s.minWindowSize
		targetStep = s.minStep
	} else if rate <= s.lowRateThreshold {
		targetSize = s.maxWindowSize
		targetStep = s.maxStep
	} else {
		ratio := (rate - s.lowRateThreshold) / (s.highRateThreshold - s.lowRateThreshold)
		sizeRange := float64(s.maxWindowSize - s.minWindowSize)
		stepRange := float64(s.maxStep - s.minStep)
		targetSize = s.minWindowSize + time.Duration(ratio*sizeRange)
		targetStep = s.minStep + time.Duration(ratio*stepRange)
	}

	alignTarget := func(target, granularity time.Duration) time.Duration {
		if granularity > 0 {
			return target.Round(granularity)
		}
		return target
	}

	if s.minStep > 0 {
		targetSize = alignTarget(targetSize, s.minStep)
		targetStep = alignTarget(targetStep, s.minStep)
	}

	if targetSize < s.minWindowSize {
		targetSize = s.minWindowSize
	}
	if targetSize > s.maxWindowSize {
		targetSize = s.maxWindowSize
	}
	if targetStep < s.minStep {
		targetStep = s.minStep
	}
	if targetStep > s.maxStep {
		targetStep = s.maxStep
	}

	s.currentWindowSize = targetSize
	s.currentStep = targetStep
}

func AdaptiveWindowConfigFromWindowingConfig(cfg *config.WindowingConfig) AdaptiveWindowConfig {
	return AdaptiveWindowConfig{
		MinWindowSize:     cfg.SlidingWindowSize / 4,
		MaxWindowSize:     cfg.SlidingWindowSize * 2,
		MinStep:           cfg.SlidingStep / 2,
		MaxStep:           cfg.SlidingStep * 2,
		LowRateThreshold:  10.0,
		HighRateThreshold: 1000.0,
		RateWindow:        time.Minute,
	}
}
