package tracing

import (
	"context"
	"go.uber.org/zap"
	"math/rand"
	"metricplatform/internal/models"
	"metricplatform/pkg/dataaccess"
	"sync"
	"time"

	"github.com/google/uuid"
)

type TraceBuffer struct {
	spans     []*models.Span
	createdAt time.Time
}

type Collector struct {
	repo           *dataaccess.Repository
	spanChan       chan *models.Span
	samplingConfig map[string]*models.SamplingConfig
	tailBuffer     map[string]*TraceBuffer
	tailWait       time.Duration
	tailSampling   bool
	logger         *zap.Logger
	mu             sync.RWMutex
	wg             sync.WaitGroup
	ctx            context.Context
	cancel         context.CancelFunc
	workers        int
}

func NewCollector(bufferSize int, workers int, repo *dataaccess.Repository, logger *zap.Logger) *Collector {
	ctx, cancel := context.WithCancel(context.Background())
	return &Collector{
		repo:           repo,
		spanChan:       make(chan *models.Span, bufferSize),
		samplingConfig: make(map[string]*models.SamplingConfig),
		tailBuffer:     make(map[string]*TraceBuffer),
		tailWait:       30 * time.Second,
		tailSampling:   true,
		logger:         logger,
		ctx:            ctx,
		cancel:         cancel,
		workers:        workers,
	}
}

func (c *Collector) SetSamplingConfig(config *models.SamplingConfig) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if config.ID == "" {
		config.ID = uuid.New().String()
	}
	config.CreatedAt = time.Now()
	config.UpdatedAt = time.Now()

	c.samplingConfig[config.Service] = config
	c.tailSampling = config.TailSampling
	if config.TailWaitDuration > 0 {
		c.tailWait = config.TailWaitDuration
	}

	c.logger.Info("Sampling config updated", zap.String("service", config.Service))
}

func (c *Collector) GetSamplingConfig(service string) (*models.SamplingConfig, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	cfg, ok := c.samplingConfig[service]
	return cfg, ok
}

func (c *Collector) ReceiveSpan(span *models.Span) error {
	if span.SpanID == "" {
		span.SpanID = uuid.New().String()
	}
	if span.StartTime.IsZero() {
		span.StartTime = time.Now()
	}
	if span.EndTime.IsZero() {
		span.EndTime = time.Now()
	}
	if span.Duration == 0 {
		span.Duration = span.EndTime.Sub(span.StartTime)
	}

	select {
	case c.spanChan <- span:
		return nil
	default:
		return &CollectorError{Msg: "span buffer full"}
	}
}

func (c *Collector) Start() {
	c.wg.Add(c.workers)
	for i := 0; i < c.workers; i++ {
		go c.worker(i)
	}

	if c.tailSampling {
		c.wg.Add(1)
		go c.tailSamplingWorker()
	}

	c.logger.Info("Trace collector started", zap.Int("workers", c.workers))
}

func (c *Collector) Stop() {
	c.cancel()
	close(c.spanChan)
	c.wg.Wait()
	c.logger.Info("Trace collector stopped")
}

func (c *Collector) worker(id int) {
	defer c.wg.Done()

	for {
		select {
		case <-c.ctx.Done():
			return
		case span, ok := <-c.spanChan:
			if !ok {
				return
			}
			c.processSpan(span)
		}
	}
}

func (c *Collector) processSpan(span *models.Span) {
	c.mu.RLock()
	config, hasConfig := c.samplingConfig[span.Service]
	c.mu.RUnlock()

	sampleRate := 1.0
	if hasConfig {
		sampleRate = config.DefaultSampleRate

		for _, rule := range config.Rules {
			if matchRule(span, rule) {
				sampleRate = rule.SampleRate
				break
			}
		}
	}

	shouldSample := rand.Float64() < sampleRate
	span.Sampled = shouldSample

	if c.tailSampling && span.TraceID != "" {
		c.bufferForTailSampling(span)
	}

	if shouldSample {
		if err := c.repo.SaveSpan(span); err != nil {
			c.logger.Error("Failed to save span", zap.Error(err), zap.String("trace_id", span.TraceID))
		}
	}
}

func (c *Collector) bufferForTailSampling(span *models.Span) {
	c.mu.Lock()
	defer c.mu.Unlock()

	buffer, exists := c.tailBuffer[span.TraceID]
	if !exists {
		buffer = &TraceBuffer{
			spans:     make([]*models.Span, 0),
			createdAt: time.Now(),
		}
		c.tailBuffer[span.TraceID] = buffer
	}

	buffer.spans = append(buffer.spans, span)
}

func (c *Collector) tailSamplingWorker() {
	defer c.wg.Done()

	ticker := time.NewTicker(c.tailWait / 2)
	defer ticker.Stop()

	for {
		select {
		case <-c.ctx.Done():
			return
		case <-ticker.C:
			c.processTailBuffer()
		}
	}
}

func (c *Collector) processTailBuffer() {
	c.mu.Lock()
	defer c.mu.Unlock()

	now := time.Now()
	eligibleTraces := make([]string, 0)

	for traceID, buffer := range c.tailBuffer {
		if now.Sub(buffer.createdAt) >= c.tailWait {
			eligibleTraces = append(eligibleTraces, traceID)
		}
	}

	for _, traceID := range eligibleTraces {
		buffer := c.tailBuffer[traceID]
		shouldKeep := c.evaluateTrace(buffer.spans)

		if shouldKeep {
			for _, span := range buffer.spans {
				if !span.Sampled {
					span.Sampled = true
					if err := c.repo.SaveSpan(span); err != nil {
						c.logger.Error("Failed to save span during tail sampling", zap.Error(err))
					}
				}
			}
			c.logger.Debug("Trace kept via tail sampling", zap.String("trace_id", traceID))
		}

		delete(c.tailBuffer, traceID)
	}

	if len(eligibleTraces) > 0 {
		c.logger.Debug("Tail sampling processed", zap.Int("traces", len(eligibleTraces)))
	}
}

func (c *Collector) evaluateTrace(spans []*models.Span) bool {
	hasError := false
	longDuration := false
	totalDuration := time.Duration(0)

	for _, span := range spans {
		if span.Status == "error" {
			hasError = true
		}
		if span.Duration > 5*time.Second {
			longDuration = true
		}
		totalDuration += span.Duration
	}

	if hasError {
		return true
	}

	if longDuration {
		return true
	}

	if totalDuration > 10*time.Second {
		return true
	}

	return false
}

func matchRule(span *models.Span, rule models.SamplingRule) bool {
	if span.Attributes == nil {
		return false
	}

	value, exists := span.Attributes[rule.AttributeKey]
	if !exists {
		return false
	}

	valueStr, ok := value.(string)
	if !ok {
		return false
	}

	switch rule.Operator {
	case "equals":
		return valueStr == rule.AttributeValue
	case "contains":
		return contains(valueStr, rule.AttributeValue)
	case "regex":
		matched, _ := MatchRegex(valueStr, rule.AttributeValue)
		return matched
	default:
		return valueStr == rule.AttributeValue
	}
}

func contains(s, substr string) bool {
	return len(s) >= len(substr) && (s == substr || (len(s) > 0 && containsAt(s, substr, 0)))
}

func containsAt(s, substr string, start int) bool {
	if start+len(substr) > len(s) {
		return false
	}
	return s[start:start+len(substr)] == substr || containsAt(s, substr, start+1)
}

func MatchRegex(s, pattern string) (bool, error) {
	return len(s) > 0 && len(pattern) > 0, nil
}

type CollectorError struct {
	Msg string
}

func (e *CollectorError) Error() string {
	return e.Msg
}

func (c *Collector) GetTrace(traceID string) ([]models.Span, error) {
	return c.repo.GetSpansByTrace(traceID)
}

func (c *Collector) GetBufferedTraceCount() int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return len(c.tailBuffer)
}
