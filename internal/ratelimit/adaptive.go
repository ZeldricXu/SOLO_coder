package ratelimit

import (
	"context"
	"math"
	"sort"
	"sync"
	"time"

	"DF1-56/internal/models"
)

type latencySample struct {
	duration  time.Duration
	timestamp time.Time
	isError   bool
}

type AdaptiveManager struct {
	enabled           bool
	policyID          string
	config            *models.AdaptiveRateLimit
	originalLimit     int64
	currentLimit      int64
	minLimit          int64
	samples           []latencySample
	samplesMu         sync.RWMutex
	metrics           *models.UpstreamMetrics
	metricsMu         sync.RWMutex
	lastAdjustTime    time.Time
	adjustInterval    time.Duration
	windowSize        time.Duration
	rtScaleDownFactor float64
	errorScaleDownFactor float64
	scaleUpFactor     float64
	rtThreshold       float64
	errorRateThreshold float64
	baselineRTp99     time.Duration
	stopCh            chan struct{}
	running           bool
	mu                sync.Mutex
}

func NewAdaptiveManager(policyID string, adaptiveConfig *models.AdaptiveRateLimit, originalLimit int64) *AdaptiveManager {
	enabled := true
	if adaptiveConfig == nil || !adaptiveConfig.Enabled {
		enabled = false
		if adaptiveConfig == nil {
			adaptiveConfig = &models.AdaptiveRateLimit{}
		}
	}

	am := &AdaptiveManager{
		enabled:            enabled,
		policyID:           policyID,
		config:             adaptiveConfig,
		originalLimit:      originalLimit,
		currentLimit:       originalLimit,
		minLimit:           adaptiveConfig.MinLimit,
		adjustInterval:     adaptiveConfig.AdjustInterval,
		windowSize:         adaptiveConfig.WindowSize,
		rtScaleDownFactor:  adaptiveConfig.RTScaleDownFactor,
		errorScaleDownFactor: adaptiveConfig.ErrorScaleDownFactor,
		scaleUpFactor:      adaptiveConfig.ScaleUpFactor,
		rtThreshold:        adaptiveConfig.RTThreshold,
		errorRateThreshold: adaptiveConfig.ErrorRateThreshold,
		baselineRTp99:      adaptiveConfig.BaselineRTp99,
		stopCh:             make(chan struct{}),
		samples:            make([]latencySample, 0, 10000),
	}

	if am.adjustInterval == 0 {
		am.adjustInterval = 10 * time.Second
	}
	if am.windowSize == 0 {
		am.windowSize = 1 * time.Minute
	}
	if am.rtScaleDownFactor == 0 {
		am.rtScaleDownFactor = 0.7
	}
	if am.errorScaleDownFactor == 0 {
		am.errorScaleDownFactor = 0.5
	}
	if am.scaleUpFactor == 0 {
		am.scaleUpFactor = 1.1
	}
	if am.rtThreshold == 0 {
		am.rtThreshold = 2.0
	}
	if am.errorRateThreshold == 0 {
		am.errorRateThreshold = 0.05
	}
	if am.minLimit == 0 {
		am.minLimit = int64(math.Max(10, float64(originalLimit)*0.2))
	}

	return am
}

func (am *AdaptiveManager) Start() {
	if !am.enabled {
		return
	}

	am.mu.Lock()
	if am.running {
		am.mu.Unlock()
		return
	}
	am.running = true
	am.lastAdjustTime = time.Now()
	am.mu.Unlock()

	go am.adjustLoop()
}

func (am *AdaptiveManager) Stop() {
	am.mu.Lock()
	defer am.mu.Unlock()

	if !am.running {
		return
	}

	am.running = false
	close(am.stopCh)
}

func (am *AdaptiveManager) adjustLoop() {
	ticker := time.NewTicker(am.adjustInterval)
	defer ticker.Stop()

	for {
		select {
		case <-am.stopCh:
			return
		case <-ticker.C:
			am.adjustLimit()
		}
	}
}

func (am *AdaptiveManager) RecordLatency(ctx context.Context, routeID string, duration time.Duration, isError bool) {
	if !am.enabled {
		return
	}

	am.samplesMu.Lock()
	am.samples = append(am.samples, latencySample{
		duration:  duration,
		timestamp: time.Now(),
		isError:   isError,
	})
	am.samplesMu.Unlock()
}

func (am *AdaptiveManager) adjustLimit() {
	am.samplesMu.Lock()
	now := time.Now()
	windowStart := now.Add(-am.windowSize)

	var inWindow []latencySample
	for i := len(am.samples) - 1; i >= 0; i-- {
		if am.samples[i].timestamp.After(windowStart) {
			inWindow = append([]latencySample{am.samples[i]}, inWindow...)
		} else {
			break
		}
	}

	am.samples = inWindow
	am.samplesMu.Unlock()

	if len(inWindow) < 10 {
		return
	}

	metrics := am.calculateMetrics(inWindow, windowStart, now)

	am.metricsMu.Lock()
	am.metrics = metrics
	am.metricsMu.Unlock()

	newLimit := am.calculateNewLimit(metrics)

	am.mu.Lock()
	if newLimit != am.currentLimit {
		am.currentLimit = newLimit
		am.lastAdjustTime = now
	}
	am.mu.Unlock()
}

func (am *AdaptiveManager) calculateMetrics(samples []latencySample, windowStart, windowEnd time.Time) *models.UpstreamMetrics {
	count := len(samples)

	durations := make([]time.Duration, count)
	errorCount := 0

	for i, s := range samples {
		durations[i] = s.duration
		if s.isError {
			errorCount++
		}
	}

	sort.Slice(durations, func(i, j int) bool {
		return durations[i] < durations[j]
	})

	p50Index := int(float64(count) * 0.5)
	p90Index := int(float64(count) * 0.9)
	p99Index := int(float64(count) * 0.99)

	return &models.UpstreamMetrics{
		RTp99:       durations[p99Index],
		RTp90:       durations[p90Index],
		RTp50:       durations[p50Index],
		ErrorRate:   float64(errorCount) / float64(count),
		TotalCount:  int64(count),
		ErrorCount:  int64(errorCount),
		WindowStart: windowStart,
		WindowEnd:   windowEnd,
	}
}

func (am *AdaptiveManager) calculateNewLimit(metrics *models.UpstreamMetrics) int64 {
	am.mu.Lock()
	defer am.mu.Unlock()

	newLimit := am.currentLimit
	scaledDown := false

	if am.baselineRTp99 > 0 {
		rtRatio := float64(metrics.RTp99) / float64(am.baselineRTp99)
		if rtRatio >= am.rtThreshold {
			newLimit = int64(float64(newLimit) * am.rtScaleDownFactor)
			scaledDown = true
		}
	}

	if metrics.ErrorRate >= am.errorRateThreshold {
		newLimit = int64(float64(newLimit) * am.errorScaleDownFactor)
		scaledDown = true
	}

	if !scaledDown && metrics.RTp99 <= am.baselineRTp99 && metrics.ErrorRate < am.errorRateThreshold {
		if am.currentLimit < am.originalLimit {
			newLimit = int64(float64(newLimit) * am.scaleUpFactor)
			if newLimit > am.originalLimit {
				newLimit = am.originalLimit
			}
		}
	}

	if newLimit < am.minLimit {
		newLimit = am.minLimit
	}

	return newLimit
}

func (am *AdaptiveManager) GetCurrentLimit() int64 {
	am.mu.Lock()
	defer am.mu.Unlock()
	return am.currentLimit
}

func (am *AdaptiveManager) GetMetrics() *models.UpstreamMetrics {
	am.metricsMu.RLock()
	defer am.metricsMu.RUnlock()

	if am.metrics == nil {
		return &models.UpstreamMetrics{}
	}

	metricsCopy := *am.metrics
	return &metricsCopy
}

func (am *AdaptiveManager) IsEnabled() bool {
	return am.enabled
}

func (am *AdaptiveManager) Reset() {
	am.mu.Lock()
	defer am.mu.Unlock()

	am.currentLimit = am.originalLimit
	am.lastAdjustTime = time.Now()

	am.samplesMu.Lock()
	am.samples = make([]latencySample, 0, 10000)
	am.samplesMu.Unlock()

	am.metricsMu.Lock()
	am.metrics = nil
	am.metricsMu.Unlock()
}
