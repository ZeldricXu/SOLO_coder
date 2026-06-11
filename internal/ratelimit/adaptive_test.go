package ratelimit

import (
	"context"
	"testing"
	"time"

	"DF1-56/internal/models"
	"github.com/stretchr/testify/assert"
)

func TestAdaptiveManager_CalculateNewLimit_RTExceedThreshold(t *testing.T) {
	am := &AdaptiveManager{
		originalLimit:     100,
		currentLimit:      100,
		minLimit:          20,
		baselineRTp99:     100 * time.Millisecond,
		rtThreshold:       2.0,
		rtScaleDownFactor: 0.7,
		errorRateThreshold: 0.05,
		errorScaleDownFactor: 0.5,
		scaleUpFactor:     1.1,
	}

	metrics := &models.UpstreamMetrics{
		RTp99:     250 * time.Millisecond,
		ErrorRate: 0.01,
	}

	newLimit := am.calculateNewLimit(metrics)

	assert.Equal(t, int64(70), newLimit)
}

func TestAdaptiveManager_CalculateNewLimit_ErrorRateExceedThreshold(t *testing.T) {
	am := &AdaptiveManager{
		originalLimit:     100,
		currentLimit:      100,
		minLimit:          20,
		baselineRTp99:     100 * time.Millisecond,
		rtThreshold:       2.0,
		rtScaleDownFactor: 0.7,
		errorRateThreshold: 0.05,
		errorScaleDownFactor: 0.5,
		scaleUpFactor:     1.1,
	}

	metrics := &models.UpstreamMetrics{
		RTp99:     150 * time.Millisecond,
		ErrorRate: 0.10,
	}

	newLimit := am.calculateNewLimit(metrics)

	assert.Equal(t, int64(50), newLimit)
}

func TestAdaptiveManager_CalculateNewLimit_BothExceedThreshold(t *testing.T) {
	am := &AdaptiveManager{
		originalLimit:     100,
		currentLimit:      100,
		minLimit:          20,
		baselineRTp99:     100 * time.Millisecond,
		rtThreshold:       2.0,
		rtScaleDownFactor: 0.7,
		errorRateThreshold: 0.05,
		errorScaleDownFactor: 0.5,
		scaleUpFactor:     1.1,
	}

	metrics := &models.UpstreamMetrics{
		RTp99:     300 * time.Millisecond,
		ErrorRate: 0.10,
	}

	newLimit := am.calculateNewLimit(metrics)

	assert.Equal(t, int64(35), newLimit)
}

func TestAdaptiveManager_CalculateNewLimit_RecoverAndScaleUp(t *testing.T) {
	am := &AdaptiveManager{
		originalLimit:     100,
		currentLimit:      50,
		minLimit:          20,
		baselineRTp99:     100 * time.Millisecond,
		rtThreshold:       2.0,
		rtScaleDownFactor: 0.7,
		errorRateThreshold: 0.05,
		errorScaleDownFactor: 0.5,
		scaleUpFactor:     1.1,
	}

	metrics := &models.UpstreamMetrics{
		RTp99:     80 * time.Millisecond,
		ErrorRate: 0.01,
	}

	newLimit := am.calculateNewLimit(metrics)

	assert.Equal(t, int64(55), newLimit)
}

func TestAdaptiveManager_CalculateNewLimit_MinLimitProtection(t *testing.T) {
	am := &AdaptiveManager{
		originalLimit:     100,
		currentLimit:      25,
		minLimit:          20,
		baselineRTp99:     100 * time.Millisecond,
		rtThreshold:       2.0,
		rtScaleDownFactor: 0.7,
		errorRateThreshold: 0.05,
		errorScaleDownFactor: 0.5,
		scaleUpFactor:     1.1,
	}

	metrics := &models.UpstreamMetrics{
		RTp99:     300 * time.Millisecond,
		ErrorRate: 0.10,
	}

	newLimit := am.calculateNewLimit(metrics)

	assert.Equal(t, int64(20), newLimit)
}

func TestAdaptiveManager_CalculateNewLimit_ScaleUpToOriginalLimit(t *testing.T) {
	am := &AdaptiveManager{
		originalLimit:     100,
		currentLimit:      95,
		minLimit:          20,
		baselineRTp99:     100 * time.Millisecond,
		rtThreshold:       2.0,
		rtScaleDownFactor: 0.7,
		errorRateThreshold: 0.05,
		errorScaleDownFactor: 0.5,
		scaleUpFactor:     1.1,
	}

	metrics := &models.UpstreamMetrics{
		RTp99:     80 * time.Millisecond,
		ErrorRate: 0.01,
	}

	newLimit := am.calculateNewLimit(metrics)

	assert.Equal(t, int64(100), newLimit)
}

func TestAdaptiveManager_CalculateMetrics(t *testing.T) {
	am := NewAdaptiveManager("test", &models.AdaptiveRateLimit{
		Enabled:             true,
		MinLimit:            20,
		BaselineRTp99:       100 * time.Millisecond,
		RTThreshold:         2.0,
		ErrorRateThreshold:  0.05,
		RTScaleDownFactor:   0.7,
		ErrorScaleDownFactor: 0.5,
		ScaleUpFactor:       1.1,
		AdjustInterval:      10 * time.Second,
		WindowSize:          1 * time.Minute,
	}, 100)

	now := time.Now()
	am.samples = []latencySample{
		{timestamp: now.Add(-500 * time.Millisecond), duration: 50 * time.Millisecond, isError: false},
		{timestamp: now.Add(-400 * time.Millisecond), duration: 100 * time.Millisecond, isError: false},
		{timestamp: now.Add(-300 * time.Millisecond), duration: 150 * time.Millisecond, isError: false},
		{timestamp: now.Add(-200 * time.Millisecond), duration: 200 * time.Millisecond, isError: true},
		{timestamp: now.Add(-100 * time.Millisecond), duration: 250 * time.Millisecond, isError: false},
	}

	metrics := am.calculateMetrics(am.samples, now.Add(-1*time.Second), now)

	assert.Equal(t, int64(5), metrics.TotalCount)
	assert.Equal(t, int64(1), metrics.ErrorCount)
	assert.InDelta(t, 0.2, metrics.ErrorRate, 0.001)
	assert.Equal(t, 250*time.Millisecond, metrics.RTp99)
}

func TestAdaptiveManager_RecordLatency(t *testing.T) {
	am := NewAdaptiveManager("test", &models.AdaptiveRateLimit{
		Enabled:    true,
		MinLimit:   20,
		WindowSize: 1 * time.Minute,
	}, 100)

	am.RecordLatency(context.Background(), "test-route", 100*time.Millisecond, false)
	am.RecordLatency(context.Background(), "test-route", 200*time.Millisecond, true)

	assert.Equal(t, 2, len(am.samples))
	assert.Equal(t, 100*time.Millisecond, am.samples[0].duration)
	assert.False(t, am.samples[0].isError)
	assert.Equal(t, 200*time.Millisecond, am.samples[1].duration)
	assert.True(t, am.samples[1].isError)
}

func TestAdaptiveManager_StartStop(t *testing.T) {
	am := NewAdaptiveManager("test", &models.AdaptiveRateLimit{
		Enabled:        true,
		MinLimit:       20,
		AdjustInterval: 100 * time.Millisecond,
		WindowSize:     1 * time.Minute,
	}, 100)

	am.Start()
	time.Sleep(150 * time.Millisecond)

	assert.True(t, am.running)

	am.Stop()
	assert.False(t, am.running)
}

func TestNewAdaptiveManager_DefaultValues(t *testing.T) {
	am := NewAdaptiveManager("test", nil, 100)

	assert.Equal(t, "test", am.policyID)
	assert.Equal(t, int64(100), am.originalLimit)
	assert.Equal(t, int64(100), am.currentLimit)
	assert.Equal(t, int64(20), am.minLimit)
	assert.Equal(t, 2.0, am.rtThreshold)
	assert.InDelta(t, 0.05, am.errorRateThreshold, 0.001)
	assert.InDelta(t, 0.7, am.rtScaleDownFactor, 0.001)
	assert.InDelta(t, 0.5, am.errorScaleDownFactor, 0.001)
	assert.InDelta(t, 1.1, am.scaleUpFactor, 0.001)
}
