package windowing

import (
	"context"
	"fmt"
	"regexp"
	"strings"
	"sync"
	"time"

	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
	"log-pipeline/pkg/utils"
)

type WindowEngine struct {
	config       *config.WindowingConfig
	strategy     WindowStrategy
	trigger      TriggerPolicy
	stateStore   *WindowStateStore
	aggChan      chan *models.WindowAggregate
	alertChan    chan *models.AlertEvent
	mu           sync.RWMutex
	ctx          context.Context
	cancel       context.CancelFunc
	wg           sync.WaitGroup
	ip401Pattern *regexp.Regexp
}

type SlidingWindow struct {
	ID         string
	Start      time.Time
	End        time.Time
	Key        string
	Logs       []*models.LogEntry
	Count      int64
	LevelCount map[string]int64
}

type SessionWindow struct {
	ID         string
	SessionKey string
	LastActive time.Time
	Logs       []*models.LogEntry
	Count      int64
}

func NewWindowEngine(cfg *config.WindowingConfig) *WindowEngine {
	ctx, cancel := context.WithCancel(context.Background())
	strategy := NewFixedWindowStrategy(cfg.SlidingWindowSize, cfg.SlidingStep)
	return &WindowEngine{
		config:       cfg,
		strategy:     strategy,
		trigger:      NewEventTimeTrigger(cfg.SlidingWindowSize),
		stateStore:   NewWindowStateStore(),
		aggChan:      make(chan *models.WindowAggregate, 1000),
		alertChan:    make(chan *models.AlertEvent, 100),
		ctx:          ctx,
		cancel:       cancel,
		ip401Pattern: regexp.MustCompile(`(\d+\.\d+\.\d+\.\d+).*?401|401.*?(\d+\.\d+\.\d+\.\d+)`),
	}
}

func NewWindowEngineWithStrategy(cfg *config.WindowingConfig, strategy WindowStrategy) *WindowEngine {
	ctx, cancel := context.WithCancel(context.Background())
	return &WindowEngine{
		config:       cfg,
		strategy:     strategy,
		trigger:      NewEventTimeTrigger(strategy.WindowSize()),
		stateStore:   NewWindowStateStore(),
		aggChan:      make(chan *models.WindowAggregate, 1000),
		alertChan:    make(chan *models.AlertEvent, 100),
		ctx:          ctx,
		cancel:       cancel,
		ip401Pattern: regexp.MustCompile(`(\d+\.\d+\.\d+\.\d+).*?401|401.*?(\d+\.\d+\.\d+\.\d+)`),
	}
}

func NewWindowEngineWithTrigger(cfg *config.WindowingConfig, strategy WindowStrategy, trigger TriggerPolicy) *WindowEngine {
	ctx, cancel := context.WithCancel(context.Background())
	return &WindowEngine{
		config:       cfg,
		strategy:     strategy,
		trigger:      trigger,
		stateStore:   NewWindowStateStore(),
		aggChan:      make(chan *models.WindowAggregate, 1000),
		alertChan:    make(chan *models.AlertEvent, 100),
		ctx:          ctx,
		cancel:       cancel,
		ip401Pattern: regexp.MustCompile(`(\d+\.\d+\.\d+\.\d+).*?401|401.*?(\d+\.\d+\.\d+\.\d+)`),
	}
}

func (we *WindowEngine) SetStrategy(strategy WindowStrategy) {
	we.mu.Lock()
	defer we.mu.Unlock()
	we.strategy = strategy
}

func (we *WindowEngine) GetStrategy() WindowStrategy {
	we.mu.RLock()
	defer we.mu.RUnlock()
	return we.strategy
}

func (we *WindowEngine) SetTriggerPolicy(trigger TriggerPolicy) {
	we.mu.Lock()
	defer we.mu.Unlock()
	we.trigger = trigger
}

func (we *WindowEngine) GetTriggerPolicy() TriggerPolicy {
	we.mu.RLock()
	defer we.mu.RUnlock()
	return we.trigger
}

func (we *WindowEngine) GetStateStore() *WindowStateStore {
	return we.stateStore
}

func (we *WindowEngine) Start(logChan <-chan *models.LogEntry) {
	we.wg.Add(2)
	go we.processLogs(logChan)
	go we.tickerLoop()
}

func (we *WindowEngine) Stop() {
	we.cancel()
	we.wg.Wait()
	close(we.aggChan)
	close(we.alertChan)
}

func (we *WindowEngine) Aggregates() <-chan *models.WindowAggregate {
	return we.aggChan
}

func (we *WindowEngine) Alerts() <-chan *models.AlertEvent {
	return we.alertChan
}

func (we *WindowEngine) processLogs(logChan <-chan *models.LogEntry) {
	defer we.wg.Done()

	for {
		select {
		case <-we.ctx.Done():
			return
		case log, ok := <-logChan:
			if !ok {
				return
			}
			we.processLog(log)
		}
	}
}

func (we *WindowEngine) processLog(log *models.LogEntry) {
	we.mu.RLock()
	defer we.mu.RUnlock()

	key := we.ExtractKey(log)

	we.addToSlidingWindow(log, key)
	we.addToSession(log, key)
	we.check401Error(log)

	we.strategy.OnLogProcessed(1)
}

func (we *WindowEngine) ProcessLog(log *models.LogEntry) {
	we.processLog(log)
}

func (we *WindowEngine) ExtractKey(log *models.LogEntry) string {
	if ip, ok := log.Fields["client_ip"]; ok {
		return ip
	}
	if ip, ok := log.Fields["remote_addr"]; ok {
		return ip
	}
	return log.Host
}

func (we *WindowEngine) addToSlidingWindow(log *models.LogEntry, key string) {
	windowStep := we.strategy.WindowStep()
	windowSize := we.strategy.WindowSize()

	windowStart := log.Timestamp.Truncate(windowStep)
	windowKey := makeSlidingWindowKey(key, windowStart.Unix(), windowSize.String())

	we.stateStore.GetOrCreateSlidingWindow(windowKey, windowStart, windowStart.Add(windowSize), key)

	level := strings.ToUpper(log.Level)
	if level == "" {
		level = "INFO"
	}
	we.stateStore.AddLogToSlidingWindow(windowKey, log, level)
}

func (we *WindowEngine) addToSession(log *models.LogEntry, key string) {
	sessionKey := makeSessionKey(key)

	we.stateStore.GetOrCreateSession(sessionKey, key, log.Timestamp)
	we.stateStore.AddLogToSession(sessionKey, log)
}

func (we *WindowEngine) check401Error(log *models.LogEntry) {
	if !strings.Contains(log.Message, "401") {
		return
	}

	matches := we.ip401Pattern.FindStringSubmatch(log.Message)
	var ip string
	if len(matches) > 1 {
		ip = matches[1]
		if ip == "" && len(matches) > 2 {
			ip = matches[2]
		}
	}
	if ip == "" {
		ip = we.ExtractKey(log)
	}

	now := time.Now()
	windowStart := now.Add(-we.config.SlidingWindowSize)

	count := we.stateStore.Count401InWindows(ip, windowStart, func(msg string) bool {
		return strings.Contains(msg, "401")
	})

	if count >= int64(we.config.Error401Threshold) {
		alert := &models.AlertEvent{
			ID:          utils.GenerateID(),
			Timestamp:   now,
			AlertType:   "auth_failure_401",
			Severity:    "warning",
			Title:       fmt.Sprintf("Multiple 401 errors from IP %s", ip),
			Description: fmt.Sprintf("Detected %d 401 errors within 1 minute from IP %s", count, ip),
			SourceIP:    ip,
			Count:       count,
			Details: map[string]interface{}{
				"threshold": we.config.Error401Threshold,
				"window":    we.config.SlidingWindowSize.String(),
			},
		}

		select {
		case we.alertChan <- alert:
		default:
		}
	}
}

func (we *WindowEngine) tickerLoop() {
	defer we.wg.Done()

	currentStep := we.strategy.WindowStep()
	ticker := time.NewTicker(currentStep)
	defer ticker.Stop()

	for {
		select {
		case <-we.ctx.Done():
			return
		case <-ticker.C:
			we.emitWindows()
			we.cleanupSessions()

			newStep := we.strategy.WindowStep()
			if newStep != currentStep {
				ticker.Stop()
				ticker = time.NewTicker(newStep)
				currentStep = newStep
			}
		}
	}
}

func (we *WindowEngine) emitWindows() {
	we.EmitWindows()
}

func (we *WindowEngine) EmitWindows() {
	now := time.Now()
	expired := we.stateStore.FlushExpiredSlidingWindows(now)

	for _, window := range expired {
		agg := &models.WindowAggregate{
			WindowID:    window.ID,
			WindowStart: window.Start,
			WindowEnd:   window.End,
			WindowType:  "sliding",
			Key:         window.Key,
			Count:       window.Count,
			LevelCounts: window.LevelCount,
			Fields:      make(map[string]interface{}),
			LogSamples:  we.sampleLogs(window.Logs),
		}

		select {
		case we.aggChan <- agg:
		default:
		}
	}
}

func (we *WindowEngine) cleanupSessions() {
	we.CleanupSessions()
}

func (we *WindowEngine) CleanupSessions() {
	now := time.Now()
	expired := we.stateStore.FlushExpiredSessions(we.config.SessionTimeout, now)

	for _, session := range expired {
		agg := &models.WindowAggregate{
			WindowID:    session.ID,
			WindowStart: session.Logs[0].Timestamp,
			WindowEnd:   session.LastActive,
			WindowType:  "session",
			Key:         session.SessionKey,
			Count:       session.Count,
			LevelCounts: make(map[string]int64),
			LogSamples:  we.sampleLogs(session.Logs),
		}

		for _, log := range session.Logs {
			level := strings.ToUpper(log.Level)
			if level == "" {
				level = "INFO"
			}
			agg.LevelCounts[level]++
		}

		select {
		case we.aggChan <- agg:
		default:
		}
	}
}

func (we *WindowEngine) sampleLogs(logs []*models.LogEntry) []models.LogEntry {
	sampleSize := minInt(len(logs), 10)
	samples := make([]models.LogEntry, sampleSize)
	for i := 0; i < sampleSize; i++ {
		samples[i] = *logs[i]
	}
	return samples
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
