package tracing

import (
	"go.uber.org/zap"
	"sync"
	"taskmanager/internal/logger"
	"taskmanager/pkg/models"
	"time"
)

type Sampler interface {
	ShouldSample(span *models.Span) bool
}

type ProbabilisticSampler struct {
	SamplingRate float64
}

func (s *ProbabilisticSampler) ShouldSample(span *models.Span) bool {
	if s.SamplingRate >= 1.0 {
		return true
	}
	if s.SamplingRate <= 0 {
		return false
	}
	hash := fnv32(span.TraceID)
	return float64(hash%10000)/10000.0 < s.SamplingRate
}

func fnv32(s string) uint32 {
	hash := uint32(2166136261)
	for i := 0; i < len(s); i++ {
		hash ^= uint32(s[i])
		hash *= 16777619
	}
	return hash
}

type RateLimitingSampler struct {
	MaxTracesPerSecond int
	currentSecond      int64
	count              int
	mu                 sync.Mutex
}

func (s *RateLimitingSampler) ShouldSample(span *models.Span) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now().Unix()
	if now != s.currentSecond {
		s.currentSecond = now
		s.count = 0
	}
	if s.count < s.MaxTracesPerSecond {
		s.count++
		return true
	}
	return false
}

type ErrorSampler struct {
	BaseSampler Sampler
}

func (s *ErrorSampler) ShouldSample(span *models.Span) bool {
	if span.StatusCode >= 500 {
		return true
	}
	if s.BaseSampler != nil {
		return s.BaseSampler.ShouldSample(span)
	}
	return true
}

type TailSampler struct {
	traceBuffer map[string][]*models.Span
	traceStart  map[string]time.Time
	maxDuration time.Duration
	maxTraces   int
	mu          sync.Mutex
	processed   chan []*models.Span
	stopped     chan struct{}
	wg          sync.WaitGroup
}

func NewTailSampler(maxDuration time.Duration, maxTraces int) *TailSampler {
	return &TailSampler{
		traceBuffer: make(map[string][]*models.Span),
		traceStart:  make(map[string]time.Time),
		maxDuration: maxDuration,
		maxTraces:   maxTraces,
		processed:   make(chan []*models.Span, 1000),
		stopped:     make(chan struct{}),
	}
}

func (ts *TailSampler) Start() {
	ts.wg.Add(1)
	go ts.flushLoop()
	logger.Info("tail sampler started")
}

func (ts *TailSampler) Stop() {
	close(ts.stopped)
	ts.wg.Wait()
	close(ts.processed)
	logger.Info("tail sampler stopped")
}

func (ts *TailSampler) flushLoop() {
	defer ts.wg.Done()
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			ts.flushExpired()
		case <-ts.stopped:
			return
		}
	}
}

func (ts *TailSampler) flushExpired() {
	ts.mu.Lock()
	defer ts.mu.Unlock()
	now := time.Now()
	for traceID, startTime := range ts.traceStart {
		if now.Sub(startTime) > ts.maxDuration {
			if spans, ok := ts.traceBuffer[traceID]; ok {
				hasError := false
				for _, span := range spans {
					if span.StatusCode >= 500 {
						hasError = true
						break
					}
				}
				if hasError {
					select {
					case ts.processed <- spans:
					default:
						logger.Warn("tail sampler processed channel full")
					}
				}
			}
			delete(ts.traceBuffer, traceID)
			delete(ts.traceStart, traceID)
		}
	}
}

func (ts *TailSampler) AddSpan(span *models.Span) {
	ts.mu.Lock()
	defer ts.mu.Unlock()
	if len(ts.traceBuffer) >= ts.maxTraces {
		for traceID := range ts.traceBuffer {
			delete(ts.traceBuffer, traceID)
			delete(ts.traceStart, traceID)
			break
		}
	}
	if _, ok := ts.traceBuffer[span.TraceID]; !ok {
		ts.traceStart[span.TraceID] = time.Now()
	}
	ts.traceBuffer[span.TraceID] = append(ts.traceBuffer[span.TraceID], span)
}

func (ts *TailSampler) ProcessedTraces() <-chan []*models.Span {
	return ts.processed
}

type TraceCollector struct {
	sampler     Sampler
	tailSampler *TailSampler
	spanChan    chan *models.Span
	storage     SpanStorage
	wg          sync.WaitGroup
	stopped     chan struct{}
}

type SpanStorage interface {
	StoreSpan(ctx interface{}, span *models.Span) error
}

func NewTraceCollector(sampler Sampler, tailSampler *TailSampler, storage SpanStorage) *TraceCollector {
	return &TraceCollector{
		sampler:     sampler,
		tailSampler: tailSampler,
		spanChan:    make(chan *models.Span, 10000),
		storage:     storage,
		stopped:     make(chan struct{}),
	}
}

func (tc *TraceCollector) Start() {
	tc.wg.Add(1)
	go tc.processLoop()
	if tc.tailSampler != nil {
		tc.tailSampler.Start()
	}
	logger.Info("trace collector started")
}

func (tc *TraceCollector) Stop() {
	close(tc.stopped)
	tc.wg.Wait()
	close(tc.spanChan)
	if tc.tailSampler != nil {
		tc.tailSampler.Stop()
	}
	logger.Info("trace collector stopped")
}

func (tc *TraceCollector) ReceiveSpan(span *models.Span) {
	if span.Duration == 0 {
		span.Duration = span.EndTime.Sub(span.StartTime).Nanoseconds() / 1e6
	}
	select {
	case tc.spanChan <- span:
	default:
		logger.Warn("trace collector channel full, dropping span")
	}
}

func (tc *TraceCollector) processLoop() {
	defer tc.wg.Done()
	for {
		select {
		case span := <-tc.spanChan:
			tc.processSpan(span)
		case spans := <-tc.tailSampler.ProcessedTraces():
			for _, span := range spans {
				if tc.storage != nil {
					_ = tc.storage.StoreSpan(nil, span)
				}
			}
			logger.Info("tail sampled trace stored", zap.Int("span_count", len(spans)))
		case <-tc.stopped:
			return
		}
	}
}

func (tc *TraceCollector) processSpan(span *models.Span) {
	if tc.tailSampler != nil {
		tc.tailSampler.AddSpan(span)
		return
	}
	sampled := true
	if tc.sampler != nil {
		sampled = tc.sampler.ShouldSample(span)
	}
	if sampled && tc.storage != nil {
		if err := tc.storage.StoreSpan(nil, span); err != nil {
			logger.Error("store span failed", zap.Error(err))
		}
	}
}

type InMemorySpanStorage struct {
	spans []*models.Span
	mu    sync.RWMutex
}

func NewInMemorySpanStorage() *InMemorySpanStorage {
	return &InMemorySpanStorage{}
}

func (s *InMemorySpanStorage) StoreSpan(ctx interface{}, span *models.Span) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.spans = append(s.spans, span)
	return nil
}

func (s *InMemorySpanStorage) GetSpansByTrace(traceID string) []*models.Span {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var result []*models.Span
	for _, span := range s.spans {
		if span.TraceID == traceID {
			result = append(result, span)
		}
	}
	return result
}

func (s *InMemorySpanStorage) GetAllSpans(limit int) []*models.Span {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if limit <= 0 || limit > len(s.spans) {
		limit = len(s.spans)
	}
	return s.spans[len(s.spans)-limit:]
}
