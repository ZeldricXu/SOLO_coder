package logs

import (
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"observability-platform/pkg/models"
)

type LogParser interface {
	Parse(raw string) (*models.LogEntry, error)
	Name() string
}

type LogFilter interface {
	Filter(entry *models.LogEntry) bool
	Name() string
}

type LogProcessor interface {
	Process(entry *models.LogEntry) (*models.LogEntry, error)
	Name() string
}

type LogOutput interface {
	Write(entry *models.LogEntry) error
	WriteBatch(entries []*models.LogEntry) error
	Name() string
	Close() error
}

type Pipeline struct {
	parsers    []LogParser
	filters    []LogFilter
	processors []LogProcessor
	outputs    []LogOutput
	input      chan *models.LogEntry
	wg         sync.WaitGroup
	stopChan   chan struct{}
	config     PipelineConfig
}

type PipelineConfig struct {
	BufferSize  int
	Workers     int
	BatchSize   int
	BatchFlush  time.Duration
}

func NewPipeline(config PipelineConfig) *Pipeline {
	if config.BufferSize <= 0 {
		config.BufferSize = 10000
	}
	if config.Workers <= 0 {
		config.Workers = 4
	}
	if config.BatchSize <= 0 {
		config.BatchSize = 100
	}
	if config.BatchFlush <= 0 {
		config.BatchFlush = time.Second
	}

	return &Pipeline{
		parsers:    make([]LogParser, 0),
		filters:    make([]LogFilter, 0),
		processors: make([]LogProcessor, 0),
		outputs:    make([]LogOutput, 0),
		input:      make(chan *models.LogEntry, config.BufferSize),
		stopChan:   make(chan struct{}),
		config:     config,
	}
}

func (p *Pipeline) AddParser(parser LogParser) {
	p.parsers = append(p.parsers, parser)
}

func (p *Pipeline) AddFilter(filter LogFilter) {
	p.filters = append(p.filters, filter)
}

func (p *Pipeline) AddProcessor(processor LogProcessor) {
	p.processors = append(p.processors, processor)
}

func (p *Pipeline) AddOutput(output LogOutput) {
	p.outputs = append(p.outputs, output)
}

func (p *Pipeline) Start() {
	for i := 0; i < p.config.Workers; i++ {
		p.wg.Add(1)
		go p.worker()
	}
}

func (p *Pipeline) Stop() {
	close(p.stopChan)
	close(p.input)
	p.wg.Wait()

	for _, output := range p.outputs {
		output.Close()
	}
}

func (p *Pipeline) worker() {
	defer p.wg.Done()

	batch := make([]*models.LogEntry, 0, p.config.BatchSize)
	ticker := time.NewTicker(p.config.BatchFlush)
	defer ticker.Stop()

	for {
		select {
		case <-p.stopChan:
			if len(batch) > 0 {
				p.flushBatch(batch)
			}
			return
		case entry, ok := <-p.input:
			if !ok {
				if len(batch) > 0 {
					p.flushBatch(batch)
				}
				return
			}
			if processed := p.processEntry(entry); processed != nil {
				batch = append(batch, processed)
				if len(batch) >= p.config.BatchSize {
					p.flushBatch(batch)
					batch = make([]*models.LogEntry, 0, p.config.BatchSize)
				}
			}
		case <-ticker.C:
			if len(batch) > 0 {
				p.flushBatch(batch)
				batch = make([]*models.LogEntry, 0, p.config.BatchSize)
			}
		}
	}
}

func (p *Pipeline) processEntry(entry *models.LogEntry) *models.LogEntry {
	for _, filter := range p.filters {
		if !filter.Filter(entry) {
			return nil
		}
	}

	for _, processor := range p.processors {
		var err error
		entry, err = processor.Process(entry)
		if err != nil {
			return nil
		}
	}

	return entry
}

func (p *Pipeline) flushBatch(entries []*models.LogEntry) {
	for _, output := range p.outputs {
		output.WriteBatch(entries)
	}
}

func (p *Pipeline) Ingest(entry *models.LogEntry) {
	if entry.ID == "" {
		entry.ID = uuid.New().String()
	}
	if entry.Timestamp.IsZero() {
		entry.Timestamp = time.Now()
	}

	select {
	case p.input <- entry:
	default:
	}
}

func (p *Pipeline) IngestRaw(raw string) {
	for _, parser := range p.parsers {
		if entry, err := parser.Parse(raw); err == nil {
			p.Ingest(entry)
			return
		}
	}

	entry := &models.LogEntry{
		ID:        uuid.New().String(),
		Timestamp: time.Now(),
		Severity:  models.SeverityInfo,
		Message:   raw,
		Parsed:    false,
	}
	p.Ingest(entry)
}

type JSONParser struct{}

func NewJSONParser() *JSONParser {
	return &JSONParser{}
}

func (p *JSONParser) Parse(raw string) (*models.LogEntry, error) {
	var entry models.LogEntry
	if err := json.Unmarshal([]byte(raw), &entry); err != nil {
		return nil, err
	}

	if entry.Timestamp.IsZero() {
		entry.Timestamp = time.Now()
	}
	if entry.ID == "" {
		entry.ID = uuid.New().String()
	}
	entry.Parsed = true

	return &entry, nil
}

func (p *JSONParser) Name() string {
	return "json"
}

type RegexParser struct {
	pattern     *regexp.Regexp
	fieldMap    map[string]int
	timeFormat  string
}

func NewRegexParser(pattern string, fieldMap map[string]int, timeFormat string) (*RegexParser, error) {
	re, err := regexp.Compile(pattern)
	if err != nil {
		return nil, err
	}

	return &RegexParser{
		pattern:    re,
		fieldMap:   fieldMap,
		timeFormat: timeFormat,
	}, nil
}

func (p *RegexParser) Parse(raw string) (*models.LogEntry, error) {
	matches := p.pattern.FindStringSubmatch(raw)
	if matches == nil {
		return nil, fmt.Errorf("pattern did not match")
	}

	entry := &models.LogEntry{
		ID:        uuid.New().String(),
		Timestamp: time.Now(),
		Severity:  models.SeverityInfo,
		Parsed:    true,
	}

	for field, index := range p.fieldMap {
		if index < len(matches) {
			value := matches[index]
			switch field {
			case "message":
				entry.Message = value
			case "severity", "level":
				entry.Severity = parseSeverity(value)
			case "timestamp", "time":
				if p.timeFormat != "" {
					if t, err := time.Parse(p.timeFormat, value); err == nil {
						entry.Timestamp = t
					}
				}
			case "service":
				entry.ServiceName = value
			case "trace_id":
				entry.TraceID = value
			case "span_id":
				entry.SpanID = value
			}
		}
	}

	return entry, nil
}

func (p *RegexParser) Name() string {
	return "regex"
}

func parseSeverity(level string) models.SeverityLevel {
	level = strings.ToUpper(level)
	switch level {
	case "DEBUG", "DBG":
		return models.SeverityDebug
	case "INFO", "INF":
		return models.SeverityInfo
	case "WARN", "WARNING":
		return models.SeverityWarning
	case "ERROR", "ERR":
		return models.SeverityError
	case "FATAL", "CRIT", "CRITICAL":
		return models.SeverityFatal
	default:
		return models.SeverityInfo
	}
}

type SeverityFilter struct {
	minLevel models.SeverityLevel
}

func NewSeverityFilter(minLevel models.SeverityLevel) *SeverityFilter {
	return &SeverityFilter{minLevel: minLevel}
}

func (f *SeverityFilter) Filter(entry *models.LogEntry) bool {
	return severityToInt(entry.Severity) >= severityToInt(f.minLevel)
}

func (f *SeverityFilter) Name() string {
	return "severity"
}

func severityToInt(level models.SeverityLevel) int {
	switch level {
	case models.SeverityDebug:
		return 0
	case models.SeverityInfo:
		return 1
	case models.SeverityWarning:
		return 2
	case models.SeverityError:
		return 3
	case models.SeverityFatal:
		return 4
	default:
		return 1
	}
}

type FieldFilter struct {
	field    string
	contains string
}

func NewFieldFilter(field, contains string) *FieldFilter {
	return &FieldFilter{field: field, contains: contains}
}

func (f *FieldFilter) Filter(entry *models.LogEntry) bool {
	var value string
	switch f.field {
	case "message":
		value = entry.Message
	case "service":
		value = entry.ServiceName
	default:
		if attr, ok := entry.Attributes[f.field]; ok {
			value = fmt.Sprintf("%v", attr)
		}
	}
	return strings.Contains(value, f.contains)
}

func (f *FieldFilter) Name() string {
	return "field"
}

type AttributeEnricher struct {
	attributes map[string]interface{}
}

func NewAttributeEnricher(attrs map[string]interface{}) *AttributeEnricher {
	return &AttributeEnricher{attributes: attrs}
}

func (p *AttributeEnricher) Process(entry *models.LogEntry) (*models.LogEntry, error) {
	if entry.Attributes == nil {
		entry.Attributes = make(map[string]interface{})
	}
	for k, v := range p.attributes {
		entry.Attributes[k] = v
	}
	return entry, nil
}

func (p *AttributeEnricher) Name() string {
	return "attribute_enricher"
}

type ConsoleOutput struct {
	format string
}

func NewConsoleOutput(format string) *ConsoleOutput {
	if format == "" {
		format = "text"
	}
	return &ConsoleOutput{format: format}
}

func (o *ConsoleOutput) Write(entry *models.LogEntry) error {
	if o.format == "json" {
		data, _ := json.Marshal(entry)
		fmt.Println(string(data))
	} else {
		fmt.Printf("[%s] %s: %s\n", entry.Timestamp.Format(time.RFC3339), entry.Severity, entry.Message)
	}
	return nil
}

func (o *ConsoleOutput) WriteBatch(entries []*models.LogEntry) error {
	for _, entry := range entries {
		o.Write(entry)
	}
	return nil
}

func (o *ConsoleOutput) Name() string {
	return "console"
}

func (o *ConsoleOutput) Close() error {
	return nil
}

type InMemoryLogStore struct {
	logs     []*models.LogEntry
	maxSize  int
	mu       sync.RWMutex
}

func NewInMemoryLogStore(maxSize int) *InMemoryLogStore {
	if maxSize <= 0 {
		maxSize = 10000
	}
	return &InMemoryLogStore{
		logs:    make([]*models.LogEntry, 0, maxSize),
		maxSize: maxSize,
	}
}

func (s *InMemoryLogStore) Write(entry *models.LogEntry) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.logs = append(s.logs, entry)
	if len(s.logs) > s.maxSize {
		s.logs = s.logs[len(s.logs)-s.maxSize:]
	}
	return nil
}

func (s *InMemoryLogStore) WriteBatch(entries []*models.LogEntry) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.logs = append(s.logs, entries...)
	if len(s.logs) > s.maxSize {
		s.logs = s.logs[len(s.logs)-s.maxSize:]
	}
	return nil
}

func (s *InMemoryLogStore) Name() string {
	return "in_memory"
}

func (s *InMemoryLogStore) Close() error {
	return nil
}

func (s *InMemoryLogStore) Query(start, end time.Time, severity models.SeverityLevel, service string, limit int) []*models.LogEntry {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make([]*models.LogEntry, 0, limit)
	count := 0

	for i := len(s.logs) - 1; i >= 0 && count < limit; i-- {
		entry := s.logs[i]

		if !start.IsZero() && entry.Timestamp.Before(start) {
			continue
		}
		if !end.IsZero() && entry.Timestamp.After(end) {
			continue
		}
		if severity != "" && severityToInt(entry.Severity) < severityToInt(severity) {
			continue
		}
		if service != "" && entry.ServiceName != service {
			continue
		}

		result = append(result, entry)
		count++
	}

	return result
}
