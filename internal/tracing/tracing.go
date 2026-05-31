package tracing

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"session130/internal/logger"
	"session130/pkg/models"
)

type SamplerType string

const (
	AlwaysSample    SamplerType = "always"
	Probabilistic SamplerType = "probabilistic"
	RateLimiting  SamplerType = "rate_limiting"
	TailBased     SamplerType = "tail_based"
)

type Sampler interface {
	ShouldSample(traceID string) bool
	Type() SamplerType
}

type AlwaysSampler struct{}

func (s *AlwaysSampler) ShouldSample(traceID string) bool { return true }
func (s *AlwaysSampler) Type() SamplerType             { return AlwaysSample }

type ProbabilisticSampler struct {
	rate float64
}

func NewProbabilisticSampler(rate float64) *ProbabilisticSampler {
	if rate <= 0 {
		rate = 0.01
	}
	if rate > 1 {
		rate = 1
	}
	return &ProbabilisticSampler{rate: rate}
}

func (s *ProbabilisticSampler) ShouldSample(traceID string) bool {
	hash := fnv32(traceID) % 10000
	return float64(hash%10000) < s.rate*10000
}

func (s *ProbabilisticSampler) Type() SamplerType {
	return Probabilistic
}

type RateLimitingSampler struct {
	tokensPerSecond int
	lastRefill     time.Time
	tokens         int
	mu             sync.Mutex
}

func NewRateLimitingSampler(tokensPerSecond int) *RateLimitingSampler {
	return &RateLimitingSampler{
		tokensPerSecond: tokensPerSecond,
		lastRefill:     time.Now(),
		tokens:         tokensPerSecond,
	}
}

func (s *RateLimitingSampler) ShouldSample(traceID string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now()
	elapsed := now.Sub(s.lastRefill)
	if elapsed >= time.Second {
		s.tokens = s.tokensPerSecond
		s.lastRefill = now
	}

	if s.tokens > 0 {
		s.tokens--
		return true
	}
	return false
}

func (s *RateLimitingSampler) Type() SamplerType {
	return RateLimiting
}

type TailSampler struct {
	decisionWindow   time.Duration
	traces     map[string][]*models.Span
	pending    map[string]chan bool
	mu         sync.Mutex
}

func NewTailSampler(window time.Duration) *TailSampler {
	ts := &TailSampler{
		decisionWindow: window,
		traces:         make(map[string][]*models.Span),
		pending:        make(map[string]chan bool),
	}
	go ts.cleanupLoop()
	return ts
}

func (s *TailSampler) ShouldSample(traceID string) bool {
	return true
}

func (s *TailSampler) RecordSpan(span *models.Span) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.traces[span.TraceID] = append(s.traces[span.TraceID], span)
}

func (s *TailSampler) Decide(traceID string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()

	spans, exists := s.traces[traceID]
	if !exists {
		return false
	}

	for _, span := range spans {
		if span.Status == "error" {
			return true
		}
		duration := span.EndTime.Sub(span.StartTime)
		if duration > 5*time.Second {
			return true
		}
	}

	return len(spans) > 10
}

func (s *TailSampler) cleanupLoop() {
	ticker := time.NewTicker(s.decisionWindow)
	for range ticker.C {
		s.mu.Lock()
		cutoff := time.Now().Add(-s.decisionWindow)
		for traceID, spans := range s.traces {
			if len(spans) > 0 && spans[0].StartTime.Before(cutoff) {
				delete(s.traces, traceID)
			}
		}
		s.mu.Unlock()
	}
}

func (s *TailSampler) Type() SamplerType {
	return TailBased
}

type Collector struct {
	mu         sync.RWMutex
	sampler    Sampler
	spans      map[string][]*models.Span
	traceIndex map[string]map[string]bool
	spanCount  int64
	sampledCount int64
	listeners  []func(*models.Span)
}

var (
	instance *Collector
	once     sync.Once
)

func NewCollector(sampler Sampler) *Collector {
	if sampler == nil {
		sampler = &AlwaysSampler{}
	}
	return &Collector{
		sampler:    sampler,
		spans:      make(map[string][]*models.Span),
		traceIndex: make(map[string]map[string]bool),
	}
}

func GetCollector() *Collector {
	once.Do(func() {
		instance = NewCollector(&AlwaysSampler{})
	})
	return instance
}

func (c *Collector) SetSampler(sampler Sampler) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.sampler = sampler
}

func (c *Collector) RecordSpan(span *models.Span) {
	atomic.AddInt64(&c.spanCount, 1)

	if !c.sampler.ShouldSample(span.TraceID) {
		return
	}

	atomic.AddInt64(&c.sampledCount, 1)

	c.mu.Lock()
	defer c.mu.Unlock()

	c.spans[span.TraceID] = append(c.spans[span.TraceID], span)

	if _, exists := c.traceIndex[span.TraceID]; !exists {
		c.traceIndex[span.TraceID] = make(map[string]bool)
	}
	c.traceIndex[span.TraceID][span.SpanID] = true

	logger.Debug(span.TraceID, "span recorded", map[string]interface{}{
		"service":   span.Service,
		"operation": span.Operation,
		"duration_ms": span.EndTime.Sub(span.StartTime).Milliseconds(),
	})

	for _, listener := range c.listeners {
		go listener(span)
	}
}

func (c *Collector) GetTrace(traceID string) ([]*models.Span, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	spans, exists := c.spans[traceID]
	if !exists {
		return nil, fmt.Errorf("trace %s not found", traceID)
	}
	result := make([]*models.Span, len(spans))
	copy(result, spans)
	return result, nil
}

func (c *Collector) GetAllTraces() []string {
	c.mu.RLock()
	defer c.mu.RUnlock()

	traceIDs := make([]string, 0, len(c.spans))
	for id := range c.spans {
		traceIDs = append(traceIDs, id)
	}
	return traceIDs
}

func (c *Collector) GetStats() map[string]interface{} {
	c.mu.RLock()
	defer c.mu.RUnlock()

	return map[string]interface{}{
		"total_spans":    atomic.LoadInt64(&c.spanCount),
		"sampled_spans": atomic.LoadInt64(&c.sampledCount),
		"trace_count":   len(c.spans),
		"sampler_type": c.sampler.Type(),
	}
}

func (c *Collector) Subscribe(listener func(*models.Span)) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.listeners = append(c.listeners, listener)
}

func (c *Collector) CleanupOld(maxAge time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()

	cutoff := time.Now().Add(-maxAge)
	for traceID, spans := range c.spans {
		if len(spans) > 0 && spans[0].StartTime.Before(cutoff) {
			delete(c.spans, traceID)
			delete(c.traceIndex, traceID)
		}
	}
}

func GenerateTraceID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func GenerateSpanID() string {
	b := make([]byte, 8)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func NewSpan(traceID, service, operation string) *models.Span {
	spanID := GenerateSpanID()
	return &models.Span{
		TraceID:    traceID,
		SpanID:     spanID,
		Service:    service,
		Operation:  operation,
		StartTime:  time.Now(),
		Status:     "ok",
		Attributes: make(map[string]interface{}),
	}
}

func fnv32(s string) uint32 {
	hash := uint32(2166136261)
	for i := 0; i < len(s); i++ {
		hash ^= uint32(s[i])
		hash *= 16777619
	}
	return hash
}

func RecordSpan(span *models.Span) {
	GetCollector().RecordSpan(span)
}

func GetTrace(traceID string) ([]*models.Span, error) {
	return GetCollector().GetTrace(traceID)
}
