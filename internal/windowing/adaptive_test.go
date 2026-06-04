package windowing

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"

	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
)

func TestNewFixedWindowStrategy(t *testing.T) {
	s := NewFixedWindowStrategy(time.Minute, time.Second*10)
	assert.Equal(t, time.Minute, s.WindowSize())
	assert.Equal(t, time.Second*10, s.WindowStep())
}

func TestFixedWindowStrategy_OnLogProcessed_NoOp(t *testing.T) {
	s := NewFixedWindowStrategy(time.Minute, time.Second*10)
	s.OnLogProcessed(100)
	assert.Equal(t, float64(0), s.CurrentRate())
}

func TestNewAdaptiveWindowStrategy(t *testing.T) {
	cfg := AdaptiveWindowConfig{
		MinWindowSize:     time.Second * 15,
		MaxWindowSize:     time.Minute * 2,
		MinStep:           time.Second * 5,
		MaxStep:           time.Second * 30,
		LowRateThreshold:  10.0,
		HighRateThreshold: 1000.0,
		RateWindow:        time.Minute,
	}
	s := NewAdaptiveWindowStrategy(cfg)

	assert.Equal(t, time.Minute*2, s.WindowSize())
	assert.Equal(t, time.Second*30, s.WindowStep())
}

func TestAdaptiveWindowStrategy_DefaultsToMaxWhenNoTraffic(t *testing.T) {
	cfg := AdaptiveWindowConfig{
		MinWindowSize:     time.Second * 15,
		MaxWindowSize:     time.Minute * 2,
		MinStep:           time.Second * 5,
		MaxStep:           time.Second * 30,
		LowRateThreshold:  10.0,
		HighRateThreshold: 1000.0,
	}
	s := NewAdaptiveWindowStrategy(cfg)

	assert.Equal(t, time.Minute*2, s.WindowSize())
	assert.Equal(t, time.Second*30, s.WindowStep())
}

func TestAdaptiveWindowStrategy_HighRateShrinksWindow(t *testing.T) {
	cfg := AdaptiveWindowConfig{
		MinWindowSize:     time.Second * 15,
		MaxWindowSize:     time.Minute * 2,
		MinStep:           time.Second * 5,
		MaxStep:           time.Second * 30,
		LowRateThreshold:  10.0,
		HighRateThreshold: 100.0,
		RateWindow:        time.Second * 10,
	}
	s := NewAdaptiveWindowStrategy(cfg)

	now := time.Now()
	for i := 0; i < 200; i++ {
		s.rateCounter.counts = append(s.rateCounter.counts, rateSample{
			timestamp: now.Add(time.Duration(i) * time.Millisecond * 50),
			count:     1,
		})
	}
	s.rateCounter.lastRate = 200.0
	s.adjust(200.0)

	assert.Equal(t, time.Second*15, s.WindowSize())
	assert.Equal(t, time.Second*5, s.WindowStep())
}

func TestAdaptiveWindowStrategy_LowRateExpandsWindow(t *testing.T) {
	cfg := AdaptiveWindowConfig{
		MinWindowSize:     time.Second * 15,
		MaxWindowSize:     time.Minute * 2,
		MinStep:           time.Second * 5,
		MaxStep:           time.Second * 30,
		LowRateThreshold:  10.0,
		HighRateThreshold: 100.0,
		RateWindow:        time.Second * 10,
	}
	s := NewAdaptiveWindowStrategy(cfg)

	s.rateCounter.lastRate = 5.0
	s.adjust(5.0)

	assert.Equal(t, time.Minute*2, s.WindowSize())
	assert.Equal(t, time.Second*30, s.WindowStep())
}

func TestAdaptiveWindowStrategy_MediumRateInterpolates(t *testing.T) {
	cfg := AdaptiveWindowConfig{
		MinWindowSize:     time.Second * 15,
		MaxWindowSize:     time.Minute,
		MinStep:           time.Second * 5,
		MaxStep:           time.Second * 30,
		LowRateThreshold:  10.0,
		HighRateThreshold: 100.0,
		RateWindow:        time.Second * 10,
	}
	s := NewAdaptiveWindowStrategy(cfg)

	s.rateCounter.lastRate = 55.0
	s.adjust(55.0)

	windowSize := s.WindowSize()
	windowStep := s.WindowStep()

	assert.True(t, windowSize > time.Second*15, "window should be larger than min at medium rate")
	assert.True(t, windowSize < time.Minute, "window should be smaller than max at medium rate")
	assert.True(t, windowStep > time.Second*5, "step should be larger than min at medium rate")
	assert.True(t, windowStep < time.Second*30, "step should be smaller than max at medium rate")
}

func TestAdaptiveWindowStrategy_WindowBounds(t *testing.T) {
	cfg := AdaptiveWindowConfig{
		MinWindowSize:     time.Second * 15,
		MaxWindowSize:     time.Minute,
		MinStep:           time.Second * 5,
		MaxStep:           time.Second * 30,
		LowRateThreshold:  10.0,
		HighRateThreshold: 100.0,
		RateWindow:        time.Second * 10,
	}
	s := NewAdaptiveWindowStrategy(cfg)

	s.adjust(1e6)
	windowSize := s.WindowSize()
	windowStep := s.WindowStep()
	assert.Equal(t, time.Second*15, windowSize, "extreme high rate should clamp to min")
	assert.Equal(t, time.Second*5, windowStep, "extreme high rate should clamp to min step")

	s.adjust(0)
	windowSize = s.WindowSize()
	windowStep = s.WindowStep()
	assert.Equal(t, time.Minute, windowSize, "zero rate should clamp to max")
	assert.Equal(t, time.Second*30, windowStep, "zero rate should clamp to max step")
}

func TestAdaptiveWindowStrategy_OnLogProcessedUpdatesRate(t *testing.T) {
	cfg := AdaptiveWindowConfig{
		MinWindowSize:     time.Second * 15,
		MaxWindowSize:     time.Minute * 2,
		MinStep:           time.Second * 5,
		MaxStep:           time.Second * 30,
		LowRateThreshold:  10.0,
		HighRateThreshold: 100.0,
		RateWindow:        time.Minute,
	}
	s := NewAdaptiveWindowStrategy(cfg)

	for i := 0; i < 50; i++ {
		s.OnLogProcessed(1)
	}

	rate := s.CurrentRate()
	assert.True(t, rate > 0, "rate should be positive after processing logs")
}

func TestNewWindowEngineWithStrategy(t *testing.T) {
	cfg := testWindowingConfig()
	strategy := NewAdaptiveWindowStrategy(AdaptiveWindowConfig{
		MinWindowSize:     time.Second * 15,
		MaxWindowSize:     time.Minute * 2,
		MinStep:           time.Second * 5,
		MaxStep:           time.Second * 30,
		LowRateThreshold:  10.0,
		HighRateThreshold: 1000.0,
	})
	we := NewWindowEngineWithStrategy(cfg, strategy)

	assert.NotNil(t, we)
	assert.Equal(t, we.strategy, strategy)
}

func TestWindowEngine_SetGetStrategy(t *testing.T) {
	cfg := testWindowingConfig()
	we := NewWindowEngine(cfg)

	fixed := we.GetStrategy()
	_, ok := fixed.(*FixedWindowStrategy)
	assert.True(t, ok, "default strategy should be FixedWindowStrategy")

	adaptive := NewAdaptiveWindowStrategy(AdaptiveWindowConfig{
		MinWindowSize:     time.Second * 15,
		MaxWindowSize:     time.Minute,
		MinStep:           time.Second * 5,
		MaxStep:           time.Second * 30,
		LowRateThreshold:  10.0,
		HighRateThreshold: 100.0,
	})
	we.SetStrategy(adaptive)

	current := we.GetStrategy()
	_, ok = current.(*AdaptiveWindowStrategy)
	assert.True(t, ok, "strategy should be AdaptiveWindowStrategy after SetStrategy")
}

func TestWindowEngine_AdaptiveWindow_NoOverlapNoGap(t *testing.T) {
	cfg := testWindowingConfig()
	strategy := NewAdaptiveWindowStrategy(AdaptiveWindowConfig{
		MinWindowSize:     time.Second * 30,
		MaxWindowSize:     time.Minute,
		MinStep:           time.Second * 10,
		MaxStep:           time.Second * 30,
		LowRateThreshold:  10.0,
		HighRateThreshold: 100.0,
		RateWindow:        time.Second * 5,
	})
	we := NewWindowEngineWithStrategy(cfg, strategy)

	now := time.Now()
	for i := 0; i < 100; i++ {
		entry := &models.LogEntry{
			ID:        "test",
			Timestamp: now.Add(time.Duration(i) * time.Second),
			Source:    "test",
			Host:      "10.0.0.1",
			Level:     "INFO",
			Message:   "test log",
			Fields:    map[string]string{"client_ip": "10.0.0.1"},
		}
		we.ProcessLog(entry)
	}

	windows := we.stateStore.AllSlidingWindows()

	assert.Greater(t, len(windows), 0, "should have created windows")

	totalLogs := int64(0)
	for _, w := range windows {
		totalLogs += w.Count
	}
	assert.Equal(t, int64(100), totalLogs, "total logs across windows should equal 100, no overlap or gap")
}

func TestAdaptiveWindowConfigFromWindowingConfig(t *testing.T) {
	cfg := &config.WindowingConfig{
		SlidingWindowSize: time.Minute,
		SlidingStep:       time.Second * 10,
		SessionTimeout:    time.Minute * 5,
		Error401Threshold: 5,
		RedisTTL:          time.Hour,
	}

	adaptiveCfg := AdaptiveWindowConfigFromWindowingConfig(cfg)
	assert.Equal(t, time.Second*15, adaptiveCfg.MinWindowSize)
	assert.Equal(t, time.Minute*2, adaptiveCfg.MaxWindowSize)
	assert.Equal(t, time.Second*5, adaptiveCfg.MinStep)
	assert.Equal(t, time.Second*20, adaptiveCfg.MaxStep)
}

func TestRateCounter(t *testing.T) {
	rc := newRateCounter(time.Second * 10)

	now := time.Now()
	for i := 0; i < 5; i++ {
		rc.counts = append(rc.counts, rateSample{
			timestamp: now.Add(time.Duration(i) * time.Second),
			count:     10,
		})
	}
	rc.lastRate = 10.0

	rate := rc.rate()
	assert.True(t, rate > 0, "rate should be positive")
}

func TestAdaptiveWindowStrategy_WindowSizeClampBelowMin(t *testing.T) {
	cfg := AdaptiveWindowConfig{
		MinWindowSize:     time.Second * 30,
		MaxWindowSize:     time.Minute,
		MinStep:           time.Second * 10,
		MaxStep:           time.Second * 30,
		LowRateThreshold:  10.0,
		HighRateThreshold: 100.0,
		RateWindow:        time.Second,
	}
	s := NewAdaptiveWindowStrategy(cfg)

	s.adjust(1e9)
	assert.Equal(t, time.Second*30, s.WindowSize(), "should clamp to min window size")
	assert.Equal(t, time.Second*10, s.WindowStep(), "should clamp to min step")
}

func TestAdaptiveWindowStrategy_SameThresholds(t *testing.T) {
	cfg := AdaptiveWindowConfig{
		MinWindowSize:     time.Second * 30,
		MaxWindowSize:     time.Minute,
		MinStep:           time.Second * 10,
		MaxStep:           time.Second * 30,
		LowRateThreshold:  50.0,
		HighRateThreshold: 50.0,
		RateWindow:        time.Second,
	}
	s := NewAdaptiveWindowStrategy(cfg)

	s.adjust(50.0)
	assert.Equal(t, time.Minute, s.WindowSize(), "equal thresholds should default to max")
	assert.Equal(t, time.Second*30, s.WindowStep(), "equal thresholds should default to max step")
}
