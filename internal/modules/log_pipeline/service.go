package log_pipeline

import (
	"context"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
	"sync"
	"time"

	"loglevelplatform/internal/common/eventbus"
	"loglevelplatform/internal/common/logger"
	"loglevelplatform/pkg/utils"

	"go.uber.org/zap"
)

type LogEntry struct {
	ID        string                 `json:"id"`
	Timestamp int64                  `json:"timestamp"`
	Level     string                 `json:"level"`
	Message   string                 `json:"message"`
	Service   string                 `json:"service"`
	Host      string                 `json:"host"`
	TraceID   string                 `json:"trace_id,omitempty"`
	Tags      map[string]string      `json:"tags,omitempty"`
	Fields    map[string]interface{} `json:"fields,omitempty"`
	Raw       string                 `json:"raw,omitempty"`
}

type PipelineStage string

const (
	StageCollect  PipelineStage = "collect"
	StageParse    PipelineStage = "parse"
	StageFilter   PipelineStage = "filter"
	StageEnrich   PipelineStage = "enrich"
	StageRoute    PipelineStage = "route"
)

type FilterRule struct {
	Field    string        `json:"field"`
	Operator string        `json:"operator"`
	Value    interface{}   `json:"value"`
}

type RouterRule struct {
	Match   map[string]interface{} `json:"match"`
	Outputs []string               `json:"outputs"`
}

type LogProcessor func(ctx context.Context, entry *LogEntry) (*LogEntry, error)
type LogOutput func(ctx context.Context, entry *LogEntry) error

type Service struct {
	processors  map[PipelineStage][]LogProcessor
	filters     []FilterRule
	routers     []RouterRule
	outputs     map[string]LogOutput
	inputChan   chan *LogEntry
	batchChan   chan []*LogEntry
	running     bool
	stopChan    chan struct{}
	batchSize   int
	flushInt    time.Duration
	mu          sync.RWMutex
	eb          *eventbus.EventBus
}

var (
	instance *Service
	once     sync.Once
)

func NewService() *Service {
	once.Do(func() {
		instance = &Service{
			processors: make(map[PipelineStage][]LogProcessor),
			filters:    make([]FilterRule, 0),
			routers:    make([]RouterRule, 0),
			outputs:    make(map[string]LogOutput),
			inputChan:  make(chan *LogEntry, 10000),
			batchChan:  make(chan []*LogEntry, 100),
			stopChan:   make(chan struct{}),
			batchSize:  100,
			flushInt:   5 * time.Second,
			eb:         eventbus.GetInstance(),
		}
		instance.registerDefaultProcessors()
		instance.registerDefaultOutputs()
	})
	return instance
}

func (s *Service) registerDefaultProcessors() {
	s.processors[StageParse] = append(s.processors[StageParse], s.parseJSONProcessor)
	s.processors[StageParse] = append(s.processors[StageParse], s.parseRegexProcessor)
	s.processors[StageEnrich] = append(s.processors[StageEnrich], s.enrichDefaults)
}

func (s *Service) registerDefaultOutputs() {
	s.outputs["stdout"] = s.stdoutOutput
	s.outputs["eventbus"] = s.eventbusOutput
	s.outputs["metrics"] = s.metricsOutput
}

func (s *Service) parseJSONProcessor(ctx context.Context, entry *LogEntry) (*LogEntry, error) {
	if entry.Raw == "" {
		return entry, nil
	}

	var parsed map[string]interface{}
	if err := json.Unmarshal([]byte(entry.Raw), &parsed); err == nil {
		if level, ok := parsed["level"].(string); ok {
			entry.Level = level
		}
		if msg, ok := parsed["message"].(string); ok {
			entry.Message = msg
		}
		if service, ok := parsed["service"].(string); ok {
			entry.Service = service
		}
		if traceID, ok := parsed["trace_id"].(string); ok {
			entry.TraceID = traceID
		}
		entry.Fields = parsed
	}
	return entry, nil
}

func (s *Service) parseRegexProcessor(ctx context.Context, entry *LogEntry) (*LogEntry, error) {
	if entry.Raw == "" || entry.Message != "" {
		return entry, nil
	}

	patterns := []string{
		`^(?P<time>\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2})\s+(?P<level>[A-Z]+)\s+(?P<message>.*)$`,
		`^\[(?P<level>[A-Z]+)\]\s+(?P<time>\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})\s+(?P<message>.*)$`,
	}

	for _, pattern := range patterns {
		re := regexp.MustCompile(pattern)
		matches := re.FindStringSubmatch(entry.Raw)
		if matches != nil {
			names := re.SubexpNames()
			for i, name := range names {
				if i == 0 || name == "" {
					continue
				}
				switch name {
				case "level":
					entry.Level = strings.ToLower(matches[i])
				case "message":
					entry.Message = matches[i]
				}
			}
			if entry.Message != "" {
				break
			}
		}
	}

	return entry, nil
}

func (s *Service) enrichDefaults(ctx context.Context, entry *LogEntry) (*LogEntry, error) {
	if entry.ID == "" {
		entry.ID = utils.NewID("log")
	}
	if entry.Timestamp == 0 {
		entry.Timestamp = time.Now().UnixNano() / 1e6
	}
	if entry.Level == "" {
		entry.Level = "info"
	}
	if entry.Host == "" {
		entry.Host = "localhost"
	}
	if entry.Tags == nil {
		entry.Tags = make(map[string]string)
	}
	entry.Tags["processed_at"] = time.Now().Format(time.RFC3339)

	return entry, nil
}

func (s *Service) stdoutOutput(ctx context.Context, entry *LogEntry) error {
	data, _ := json.Marshal(entry)
	fmt.Println(string(data))
	return nil
}

func (s *Service) eventbusOutput(ctx context.Context, entry *LogEntry) error {
	event := eventbus.Event{
		Type:      "log." + entry.Level,
		Payload:   entry,
		Timestamp: entry.Timestamp,
		TraceID:   entry.TraceID,
	}
	s.eb.Publish(ctx, event)
	return nil
}

func (s *Service) metricsOutput(ctx context.Context, entry *LogEntry) error {
	event := eventbus.Event{
		Type:      "metric.log_count",
		Payload:   map[string]interface{}{"level": entry.Level, "service": entry.Service},
		Timestamp: entry.Timestamp,
		TraceID:   entry.TraceID,
	}
	s.eb.Publish(ctx, event)
	return nil
}

func (s *Service) AddFilter(rule FilterRule) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.filters = append(s.filters, rule)
}

func (s *Service) AddRouter(rule RouterRule) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.routers = append(s.routers, rule)
}

func (s *Service) AddProcessor(stage PipelineStage, processor LogProcessor) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.processors[stage] = append(s.processors[stage], processor)
}

func (s *Service) AddOutput(name string, output LogOutput) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.outputs[name] = output
}

func (s *Service) Collect(ctx context.Context, entry *LogEntry) error {
	select {
	case s.inputChan <- entry:
		return nil
	default:
		logger.Warn("log pipeline input channel full, dropping log",
			zap.String("service", entry.Service),
		)
		return fmt.Errorf("pipeline full")
	}
}

func (s *Service) CollectRaw(ctx context.Context, raw string, service string) error {
	entry := &LogEntry{
		Raw:     raw,
		Service: service,
	}
	return s.Collect(ctx, entry)
}

func (s *Service) CollectBatch(ctx context.Context, entries []*LogEntry) error {
	for _, entry := range entries {
		if err := s.Collect(ctx, entry); err != nil {
			return err
		}
	}
	return nil
}

func (s *Service) Start() {
	s.mu.Lock()
	if s.running {
		s.mu.Unlock()
		return
	}
	s.running = true
	s.mu.Unlock()

	go s.processLoop()
	go s.batchLoop()

	logger.Info("log pipeline service started")
}

func (s *Service) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running {
		s.running = false
		close(s.stopChan)
		logger.Info("log pipeline service stopped")
	}
}

func (s *Service) processLoop() {
	batch := make([]*LogEntry, 0, s.batchSize)
	ticker := time.NewTicker(s.flushInt)
	defer ticker.Stop()

	for {
		select {
		case entry, ok := <-s.inputChan:
			if !ok {
				if len(batch) > 0 {
					s.batchChan <- batch
				}
				return
			}

			processed, err := s.ProcessEntry(context.Background(), entry)
			if err == nil && processed != nil {
				batch = append(batch, processed)
				if len(batch) >= s.batchSize {
					s.batchChan <- batch
					batch = batch[:0]
				}
			}

		case <-ticker.C:
			if len(batch) > 0 {
				s.batchChan <- batch
				batch = batch[:0]
			}

		case <-s.stopChan:
			if len(batch) > 0 {
				s.batchChan <- batch
			}
			return
		}
	}
}

func (s *Service) batchLoop() {
	for {
		select {
		case batch, ok := <-s.batchChan:
			if !ok {
				return
			}
			for _, entry := range batch {
				s.routeEntry(context.Background(), entry)
			}
		case <-s.stopChan:
			return
		}
	}
}

func (s *Service) ProcessEntry(ctx context.Context, entry *LogEntry) (*LogEntry, error) {
	log := logger.FromContext(ctx)
	var err error

	stages := []PipelineStage{StageCollect, StageParse, StageFilter, StageEnrich, StageRoute}

	for _, stage := range stages {
		s.mu.RLock()
		processors := s.processors[stage]
		s.mu.RUnlock()

		for _, processor := range processors {
			entry, err = processor(ctx, entry)
			if err != nil {
				log.Warn("log processing failed at stage",
					zap.String("stage", string(stage)),
					zap.Error(err),
				)
				return nil, err
			}
			if entry == nil {
				return nil, nil
			}
		}

		if stage == StageFilter {
			if !s.applyFilters(entry) {
				return nil, nil
			}
		}
	}

	return entry, nil
}

func (s *Service) applyFilters(entry *LogEntry) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if len(s.filters) == 0 {
		return true
	}

	for _, rule := range s.filters {
		fieldValue := s.getFieldValue(entry, rule.Field)
		if !s.matchFilter(fieldValue, rule.Operator, rule.Value) {
			return false
		}
	}
	return true
}

func (s *Service) getFieldValue(entry *LogEntry, field string) interface{} {
	switch field {
	case "level":
		return entry.Level
	case "message":
		return entry.Message
	case "service":
		return entry.Service
	case "host":
		return entry.Host
	case "trace_id":
		return entry.TraceID
	default:
		if entry.Fields != nil {
			return entry.Fields[field]
		}
		if entry.Tags != nil {
			return entry.Tags[field]
		}
	}
	return nil
}

func (s *Service) matchFilter(value, operator string, expected interface{}) bool {
	expStr, ok := expected.(string)
	if !ok {
		return false
	}

	switch operator {
	case "equals":
		return value == expStr
	case "not_equals":
		return value != expStr
	case "contains":
		return strings.Contains(value, expStr)
	case "not_contains":
		return !strings.Contains(value, expStr)
	case "starts_with":
		return strings.HasPrefix(value, expStr)
	case "ends_with":
		return strings.HasSuffix(value, expStr)
	case "regex":
		matched, _ := regexp.MatchString(expStr, value)
		return matched
	default:
		return true
	}
}

func (s *Service) routeEntry(ctx context.Context, entry *LogEntry) {
	s.mu.RLock()
	routers := s.routers
	outputs := s.outputs
	s.mu.RUnlock()

	targetOutputs := make([]string, 0)

	if len(routers) == 0 {
		for name := range outputs {
			targetOutputs = append(targetOutputs, name)
		}
	} else {
		for _, router := range routers {
			if s.matchRouter(entry, router.Match) {
				targetOutputs = append(targetOutputs, router.Outputs...)
			}
		}
	}

	for _, outputName := range targetOutputs {
		if output, exists := outputs[outputName]; exists {
			go func(o LogOutput, e *LogEntry) {
				_ = o(ctx, e)
			}(output, entry)
		}
	}
}

func (s *Service) matchRouter(entry *LogEntry, match map[string]interface{}) bool {
	for field, expected := range match {
		value := s.getFieldValue(entry, field)
		if value != expected {
			return false
		}
	}
	return true
}

func (s *Service) GetStats(ctx context.Context) map[string]interface{} {
	stats := make(map[string]interface{})

	s.mu.RLock()
	stats["filter_count"] = len(s.filters)
	stats["router_count"] = len(s.routers)
	stats["output_count"] = len(s.outputs)
	stats["input_queue_size"] = len(s.inputChan)
	stats["batch_queue_size"] = len(s.batchChan)
	s.mu.RUnlock()

	return stats
}
