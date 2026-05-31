package trace

import (
	"container/list"
	"context"
	"encoding/json"
	"sync"
	"time"

	"github.com/google/uuid"
	"observability-platform/pkg/models"
)

type CollectorConfig struct {
	BufferSize        int
	FlushInterval     time.Duration
	MaxTraceWaitTime  time.Duration
	EnableTailSampling bool
	Sampler           Sampler
	TailSampler       *TailSampler
}

type TraceBuffer struct {
	trace      *models.Trace
	lastUpdate time.Time
}

type Collector struct {
	config         CollectorConfig
	spans          chan *models.Span
	traceBuffer    map[string]*TraceBuffer
	bufferMutex    sync.RWMutex
	spanProcessors []SpanProcessor
	traceExporters []TraceExporter
	tailSampler    *TailSampler
	sampler        Sampler
	ctx            context.Context
	cancel         context.CancelFunc
	wg             sync.WaitGroup
	stats          CollectorStats
}

type CollectorStats struct {
	ReceivedSpans    int64
	SampledSpans     int64
	DroppedSpans     int64
	CompletedTraces  int64
	ExportedTraces   int64
	mu               sync.Mutex
}

type SpanProcessor interface {
	Process(span *models.Span)
}

type TraceExporter interface {
	Export(trace *models.Trace) error
}

func NewCollector(config CollectorConfig) *Collector {
	if config.BufferSize <= 0 {
		config.BufferSize = 10000
	}
	if config.FlushInterval <= 0 {
		config.FlushInterval = time.Second * 5
	}
	if config.MaxTraceWaitTime <= 0 {
		config.MaxTraceWaitTime = time.Minute * 2
	}

	sampler := config.Sampler
	if sampler == nil {
		sampler = NewProbabilisticSampler(1.0)
	}

	ctx, cancel := context.WithCancel(context.Background())

	return &Collector{
		config:         config,
		spans:          make(chan *models.Span, config.BufferSize),
		traceBuffer:    make(map[string]*TraceBuffer),
		spanProcessors: make([]SpanProcessor, 0),
		traceExporters: make([]TraceExporter, 0),
		tailSampler:    config.TailSampler,
		sampler:        sampler,
		ctx:            ctx,
		cancel:         cancel,
	}
}

func (c *Collector) AddSpanProcessor(processor SpanProcessor) {
	c.spanProcessors = append(c.spanProcessors, processor)
}

func (c *Collector) AddTraceExporter(exporter TraceExporter) {
	c.traceExporters = append(c.traceExporters, exporter)
}

func (c *Collector) ReceiveSpan(span *models.Span) {
	if span == nil {
		return
	}

	c.stats.mu.Lock()
	c.stats.ReceivedSpans++
	c.stats.mu.Unlock()

	if !span.Sampled {
		span.Sampled = c.sampler.ShouldSample(span)
	}

	if !span.Sampled {
		c.stats.mu.Lock()
		c.stats.DroppedSpans++
		c.stats.mu.Unlock()
		return
	}

	c.stats.mu.Lock()
	c.stats.SampledSpans++
	c.stats.mu.Unlock()

	select {
	case c.spans <- span:
	default:
	}
}

func (c *Collector) ReceiveSpansBatch(spans []*models.Span) {
	for _, span := range spans {
		c.ReceiveSpan(span)
	}
}

func (c *Collector) Start() {
	c.wg.Add(2)
	go c.processSpans()
	go c.flushLoop()
}

func (c *Collector) Stop() {
	c.cancel()
	c.wg.Wait()
	c.flushAllTraces()
}

func (c *Collector) processSpans() {
	defer c.wg.Done()

	for {
		select {
		case <-c.ctx.Done():
			return
		case span := <-c.spans:
			c.processSpan(span)
		}
	}
}

func (c *Collector) processSpan(span *models.Span) {
	for _, processor := range c.spanProcessors {
		processor.Process(span)
	}

	c.bufferMutex.Lock()
	defer c.bufferMutex.Unlock()

	buffer, exists := c.traceBuffer[span.TraceID]
	if !exists {
		buffer = &TraceBuffer{
			trace: &models.Trace{
				TraceID: span.TraceID,
				Spans:   make([]models.Span, 0),
			},
		}
		c.traceBuffer[span.TraceID] = buffer
	}

	buffer.trace.Spans = append(buffer.trace.Spans, *span)
	buffer.lastUpdate = time.Now()

	if span.StartTime.Before(buffer.trace.StartTime) || buffer.trace.StartTime.IsZero() {
		buffer.trace.StartTime = span.StartTime
	}
	if span.EndTime.After(buffer.trace.EndTime) {
		buffer.trace.EndTime = span.EndTime
	}
	buffer.trace.Duration = buffer.trace.EndTime.Sub(buffer.trace.StartTime)
}

func (c *Collector) flushLoop() {
	defer c.wg.Done()

	ticker := time.NewTicker(c.config.FlushInterval)
	defer ticker.Stop()

	for {
		select {
		case <-c.ctx.Done():
			return
		case <-ticker.C:
			c.flushExpiredTraces()
		}
	}
}

func (c *Collector) flushExpiredTraces() {
	c.bufferMutex.Lock()
	defer c.bufferMutex.Unlock()

	now := time.Now()
	expiredTraces := make([]*models.Trace, 0)

	for traceID, buffer := range c.traceBuffer {
		if now.Sub(buffer.lastUpdate) > c.config.MaxTraceWaitTime {
			c.finalizeTrace(buffer.trace)
			expiredTraces = append(expiredTraces, buffer.trace)
			delete(c.traceBuffer, traceID)
		}
	}

	for _, trace := range expiredTraces {
		c.exportTrace(trace)
	}
}

func (c *Collector) flushAllTraces() {
	c.bufferMutex.Lock()
	defer c.bufferMutex.Unlock()

	for _, buffer := range c.traceBuffer {
		c.finalizeTrace(buffer.trace)
		c.exportTrace(buffer.trace)
	}
	c.traceBuffer = make(map[string]*TraceBuffer)
}

func (c *Collector) finalizeTrace(trace *models.Trace) {
	serviceSet := make(map[string]struct{})
	errorCount := 0
	var rootService string

	for _, span := range trace.Spans {
		if span.ServiceName != "" {
			serviceSet[span.ServiceName] = struct{}{}
		}
		if span.Status.Code != 0 {
			errorCount++
		}
		if span.ParentSpanID == "" {
			rootService = span.ServiceName
		}
	}

	trace.ServiceCount = len(serviceSet)
	trace.SpanCount = len(trace.Spans)
	trace.ErrorCount = errorCount
	trace.RootService = rootService
}

func (c *Collector) exportTrace(trace *models.Trace) {
	if c.config.EnableTailSampling && c.tailSampler != nil {
		decision := c.tailSampler.Evaluate(trace)
		if decision == TailDecisionDrop {
			return
		}
	}

	c.stats.mu.Lock()
	c.stats.CompletedTraces++
	c.stats.mu.Unlock()

	for _, exporter := range c.traceExporters {
		if err := exporter.Export(trace); err != nil {
			continue
		}
	}

	c.stats.mu.Lock()
	c.stats.ExportedTraces++
	c.stats.mu.Unlock()
}

func (c *Collector) GetStats() CollectorStats {
	c.stats.mu.Lock()
	defer c.stats.mu.Unlock()
	return c.stats
}

type InMemoryTraceExporter struct {
	traces *list.List
	mu     sync.Mutex
	maxSize int
}

func NewInMemoryTraceExporter(maxSize int) *InMemoryTraceExporter {
	if maxSize <= 0 {
		maxSize = 1000
	}
	return &InMemoryTraceExporter{
		traces:  list.New(),
		maxSize: maxSize,
	}
}

func (e *InMemoryTraceExporter) Export(trace *models.Trace) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	e.traces.PushBack(trace)
	if e.traces.Len() > e.maxSize {
		e.traces.Remove(e.traces.Front())
	}
	return nil
}

func (e *InMemoryTraceExporter) GetTraces() []*models.Trace {
	e.mu.Lock()
	defer e.mu.Unlock()

	traces := make([]*models.Trace, 0, e.traces.Len())
	for e := e.traces.Front(); e != nil; e = e.Next() {
		traces = append(traces, e.Value.(*models.Trace))
	}
	return traces
}

type ServiceStatsProcessor struct {
	serviceStats map[string]*ServiceStat
	mu           sync.Mutex
}

type ServiceStat struct {
	ServiceName   string
	SpanCount     int64
	ErrorCount    int64
	TotalDuration time.Duration
}

func NewServiceStatsProcessor() *ServiceStatsProcessor {
	return &ServiceStatsProcessor{
		serviceStats: make(map[string]*ServiceStat),
	}
}

func (p *ServiceStatsProcessor) Process(span *models.Span) {
	if span.ServiceName == "" {
		return
	}

	p.mu.Lock()
	defer p.mu.Unlock()

	stat, exists := p.serviceStats[span.ServiceName]
	if !exists {
		stat = &ServiceStat{ServiceName: span.ServiceName}
		p.serviceStats[span.ServiceName] = stat
	}

	stat.SpanCount++
	if span.Status.Code != 0 {
		stat.ErrorCount++
	}
	stat.TotalDuration += span.Duration
}

func (p *ServiceStatsProcessor) GetStats() map[string]*ServiceStat {
	p.mu.Lock()
	defer p.mu.Unlock()

	result := make(map[string]*ServiceStat)
	for k, v := range p.serviceStats {
		result[k] = v
	}
	return result
}

func GenerateSpanID() string {
	return uuid.New().String()
}

func GenerateTraceID() string {
	return uuid.New().String()
}

func SpanToJSON(span *models.Span) string {
	data, _ := json.Marshal(span)
	return string(data)
}
