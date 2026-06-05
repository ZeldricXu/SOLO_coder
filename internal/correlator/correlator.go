package correlator

import (
	"context"
	"log"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/models"
	"github.com/datateam/loganalyzer/internal/storage"
)

type TraceBuffer struct {
	Events     []*models.LogEvent
	FirstSeen  time.Time
	LastSeen   time.Time
	HasError   bool
	ErrorCode  string
}

type Correlator struct {
	cfg          config.CorrelationConfig
	clickhouse   *storage.ClickHouseClient
	input        <-chan *models.LogEvent
	eventCh      chan *models.EventChain
	buffer       map[string]*TraceBuffer
	bufferMu     sync.RWMutex
	wg           sync.WaitGroup
	stopCh       chan struct{}
	traceFields  []string
}

func NewCorrelator(cfg config.CorrelationConfig, clickhouse *storage.ClickHouseClient, input <-chan *models.LogEvent) (*Correlator, error) {
	if cfg.Timeout == 0 {
		cfg.Timeout = 5 * time.Minute
	}
	if cfg.MaxEventChainSize == 0 {
		cfg.MaxEventChainSize = 1000
	}
	if cfg.BufferTTL == 0 {
		cfg.BufferTTL = 30 * time.Minute
	}

	traceFields := cfg.TraceIDFields
	if len(traceFields) == 0 {
		traceFields = []string{"trace_id", "traceId", "X-B3-TraceId", "request_id", "requestId"}
	}

	return &Correlator{
		cfg:         cfg,
		clickhouse:  clickhouse,
		input:       input,
		eventCh:     make(chan *models.EventChain, 100),
		buffer:      make(map[string]*TraceBuffer),
		stopCh:      make(chan struct{}),
		traceFields: traceFields,
	}, nil
}

func (c *Correlator) Start(ctx context.Context) error {
	c.wg.Add(2)
	go c.processEvents(ctx)
	go c.cleanupLoop(ctx)

	log.Printf("Correlator started, timeout: %s, buffer TTL: %s", c.cfg.Timeout, c.cfg.BufferTTL)
	return nil
}

func (c *Correlator) processEvents(ctx context.Context) {
	defer c.wg.Done()

	for {
		select {
		case <-ctx.Done():
			return
		case <-c.stopCh:
			return
		case event := <-c.input:
			if event == nil {
				continue
			}
			c.processEvent(event)
		}
	}
}

func (c *Correlator) processEvent(event *models.LogEvent) {
	traceID := c.extractTraceID(event)
	if traceID == "" {
		return
	}

	c.bufferMu.Lock()
	defer c.bufferMu.Unlock()

	buf, ok := c.buffer[traceID]
	if !ok {
		buf = &TraceBuffer{
			Events:    make([]*models.LogEvent, 0),
			FirstSeen: event.Timestamp,
			LastSeen:  event.Timestamp,
		}
		c.buffer[traceID] = buf
	}

	buf.Events = append(buf.Events, event)
	buf.LastSeen = event.Timestamp

	if event.Level == models.LevelError || event.Level == models.LevelFatal {
		buf.HasError = true
		if event.ErrorCode != "" {
			buf.ErrorCode = event.ErrorCode
		}
	}

	if len(buf.Events) >= c.cfg.MaxEventChainSize {
		c.flushTrace(traceID, buf)
	}

	if time.Since(buf.LastSeen) > c.cfg.Timeout {
		c.flushTrace(traceID, buf)
	}
}

func (c *Correlator) extractTraceID(event *models.LogEvent) string {
	if event.TraceID != "" {
		return event.TraceID
	}

	for _, field := range c.traceFields {
		if v, ok := event.ParsedFields[field]; ok {
			if s, ok := v.(string); ok && s != "" {
				return s
			}
		}
		if v, ok := event.Labels[field]; ok && v != "" {
			return v
		}
	}

	return ""
}

func (c *Correlator) flushTrace(traceID string, buf *TraceBuffer) {
	chain := c.buildEventChain(traceID, buf)

	select {
	case c.eventCh <- chain:
	default:
		log.Printf("Event chain channel full, dropping chain: %s", traceID)
	}

	delete(c.buffer, traceID)
}

func (c *Correlator) buildEventChain(traceID string, buf *TraceBuffer) *models.EventChain {
	events := make([]*models.LogEvent, len(buf.Events))
	copy(events, buf.Events)

	sort.Slice(events, func(i, j int) bool {
		return events[i].Timestamp.Before(events[j].Timestamp)
	})

	serviceSet := make(map[string]bool)
	services := make([]string, 0)
	var startTime, endTime time.Time
	rootService := ""

	for i, event := range events {
		if !serviceSet[event.ServiceName] {
			serviceSet[event.ServiceName] = true
			services = append(services, event.ServiceName)
		}

		if i == 0 {
			startTime = event.Timestamp
			rootService = event.ServiceName
		}
		endTime = event.Timestamp
	}

	chain := &models.EventChain{
		TraceID:     traceID,
		StartTime:   startTime,
		EndTime:     endTime,
		Duration:    endTime.Sub(startTime).Milliseconds(),
		Services:    services,
		Events:      events,
		HasError:    buf.HasError,
		ErrorCode:   buf.ErrorCode,
		RootService: rootService,
	}

	return chain
}

func (c *Correlator) cleanupLoop(ctx context.Context) {
	defer c.wg.Done()

	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-c.stopCh:
			return
		case <-ticker.C:
			c.cleanupOldTraces()
		}
	}
}

func (c *Correlator) cleanupOldTraces() {
	c.bufferMu.Lock()
	defer c.bufferMu.Unlock()

	cutoff := time.Now().Add(-c.cfg.BufferTTL)

	for traceID, buf := range c.buffer {
		if buf.LastSeen.Before(cutoff) {
			c.flushTrace(traceID, buf)
		}
	}
}

func (c *Correlator) GetEventChain(ctx context.Context, traceID string) (*models.EventChain, error) {
	if c.clickhouse != nil {
		return c.clickhouse.GetEventChain(ctx, traceID)
	}

	c.bufferMu.RLock()
	defer c.bufferMu.RUnlock()

	if buf, ok := c.buffer[traceID]; ok {
		return c.buildEventChain(traceID, buf), nil
	}

	return nil, nil
}

func (c *Correlator) SearchTraces(ctx context.Context, serviceName string, hasError bool, startTime, endTime time.Time) ([]*models.EventChain, error) {
	c.bufferMu.RLock()
	defer c.bufferMu.RUnlock()

	chains := make([]*models.EventChain, 0)

	for traceID, buf := range c.buffer {
		if serviceName != "" && !c.containsService(buf.Events, serviceName) {
			continue
		}
		if hasError && !buf.HasError {
			continue
		}
		if buf.LastSeen.Before(startTime) || buf.FirstSeen.After(endTime) {
			continue
		}

		chains = append(chains, c.buildEventChain(traceID, buf))
	}

	sort.Slice(chains, func(i, j int) bool {
		return chains[i].StartTime.After(chains[j].StartTime)
	})

	return chains, nil
}

func (c *Correlator) containsService(events []*models.LogEvent, serviceName string) bool {
	for _, e := range events {
		if strings.EqualFold(e.ServiceName, serviceName) {
			return true
		}
	}
	return false
}

func (c *Correlator) EventChains() <-chan *models.EventChain {
	return c.eventCh
}

func (c *Correlator) Stop() {
	close(c.stopCh)
	c.wg.Wait()

	c.bufferMu.Lock()
	defer c.bufferMu.Unlock()

	for traceID, buf := range c.buffer {
		if len(buf.Events) > 0 {
			chain := c.buildEventChain(traceID, buf)
			select {
			case c.eventCh <- chain:
			default:
			}
		}
	}

	close(c.eventCh)
}
