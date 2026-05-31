package cdc

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"session154/internal/logger"
	"sync"
	"time"

	"go.uber.org/zap"
)

type EventType string

const (
	EventTypeInsert EventType = "insert"
	EventTypeUpdate EventType = "update"
	EventTypeDelete EventType = "delete"
	EventTypeDDL    EventType = "ddl"
)

type SourceType string

const (
	SourceMySQLBinlog SourceType = "mysql_binlog"
	SourcePostgresWAL SourceType = "postgres_wal"
	SourceMongoOplog  SourceType = "mongo_oplog"
)

type ChangeEvent struct {
	ID            string                 `json:"id"`
	EventType     EventType              `json:"event_type"`
	SourceType    SourceType             `json:"source_type"`
	Database      string                 `json:"database"`
	Table         string                 `json:"table"`
	PrimaryKey    map[string]interface{} `json:"primary_key"`
	Before        map[string]interface{} `json:"before,omitempty"`
	After         map[string]interface{} `json:"after,omitempty"`
	Timestamp     time.Time              `json:"timestamp"`
	LSN           string                 `json:"lsn"`
	BinlogFile    string                 `json:"binlog_file,omitempty"`
	BinlogPos     uint32                 `json:"binlog_pos,omitempty"`
	SchemaVersion int                    `json:"schema_version,omitempty"`
	Metadata      map[string]interface{} `json:"metadata,omitempty"`
}

type Offset struct {
	SourceType SourceType `json:"source_type"`
	LSN        string     `json:"lsn"`
	BinlogFile string     `json:"binlog_file,omitempty"`
	BinlogPos  uint32     `json:"binlog_pos,omitempty"`
	Timestamp  time.Time  `json:"timestamp"`
}

type EventSerializer interface {
	Serialize(event *ChangeEvent) ([]byte, error)
	Deserialize(data []byte) (*ChangeEvent, error)
	ContentType() string
}

type JSONSerializer struct{}

func NewJSONSerializer() *JSONSerializer {
	return &JSONSerializer{}
}

func (s *JSONSerializer) Serialize(event *ChangeEvent) ([]byte, error) {
	return json.Marshal(event)
}

func (s *JSONSerializer) Deserialize(data []byte) (*ChangeEvent, error) {
	var event ChangeEvent
	if err := json.Unmarshal(data, &event); err != nil {
		return nil, err
	}
	return &event, nil
}

func (s *JSONSerializer) ContentType() string {
	return "application/json"
}

type AvroSerializer struct {
	schema string
}

func NewAvroSerializer(schema string) *AvroSerializer {
	return &AvroSerializer{schema: schema}
}

func (s *AvroSerializer) Serialize(event *ChangeEvent) ([]byte, error) {
	return json.Marshal(event)
}

func (s *AvroSerializer) Deserialize(data []byte) (*ChangeEvent, error) {
	var event ChangeEvent
	if err := json.Unmarshal(data, &event); err != nil {
		return nil, err
	}
	return &event, nil
}

func (s *AvroSerializer) ContentType() string {
	return "application/avro"
}

type OutputAdapter interface {
	Start(ctx context.Context) error
	Stop() error
	Send(ctx context.Context, event *ChangeEvent) error
	SendBatch(ctx context.Context, events []*ChangeEvent) error
}

type ConsoleAdapter struct {
	serializer EventSerializer
}

func NewConsoleAdapter(serializer EventSerializer) *ConsoleAdapter {
	if serializer == nil {
		serializer = NewJSONSerializer()
	}
	return &ConsoleAdapter{serializer: serializer}
}

func (a *ConsoleAdapter) Start(ctx context.Context) error { return nil }
func (a *ConsoleAdapter) Stop() error                   { return nil }

func (a *ConsoleAdapter) Send(ctx context.Context, event *ChangeEvent) error {
	data, err := a.serializer.Serialize(event)
	if err != nil {
		return err
	}
	fmt.Println(string(data))
	return nil
}

func (a *ConsoleAdapter) SendBatch(ctx context.Context, events []*ChangeEvent) error {
	for _, e := range events {
		if err := a.Send(ctx, e); err != nil {
			return err
		}
	}
	return nil
}

type InMemoryAdapter struct {
	events     []*ChangeEvent
	serializer EventSerializer
	mu         sync.RWMutex
}

func NewInMemoryAdapter(serializer EventSerializer) *InMemoryAdapter {
	if serializer == nil {
		serializer = NewJSONSerializer()
	}
	return &InMemoryAdapter{
		events:     make([]*ChangeEvent, 0),
		serializer: serializer,
	}
}

func (a *InMemoryAdapter) Start(ctx context.Context) error { return nil }
func (a *InMemoryAdapter) Stop() error                   { return nil }

func (a *InMemoryAdapter) Send(ctx context.Context, event *ChangeEvent) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.events = append(a.events, event)
	return nil
}

func (a *InMemoryAdapter) SendBatch(ctx context.Context, events []*ChangeEvent) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.events = append(a.events, events...)
	return nil
}

func (a *InMemoryAdapter) GetEvents() []*ChangeEvent {
	a.mu.RLock()
	defer a.mu.RUnlock()
	result := make([]*ChangeEvent, len(a.events))
	copy(result, a.events)
	return result
}

func (a *InMemoryAdapter) Clear() {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.events = a.events[:0]
}

type SourceConfig struct {
	SourceType SourceType `json:"source_type"`
	Host       string     `json:"host"`
	Port       int        `json:"port"`
	Database   string     `json:"database"`
	User       string     `json:"user"`
	Password   string     `json:"password"`
	Tables     []string   `json:"tables,omitempty"`
	StartLSN   string     `json:"start_lsn,omitempty"`
	ServerID   uint32     `json:"server_id,omitempty"`
}

type BinlogPosition struct {
	File string
	Pos  uint32
}

type WalPosition struct {
	LSN string
}

type ChangeEventSource interface {
	Start(ctx context.Context, position interface{}) error
	Stop() error
	Events() <-chan *ChangeEvent
	Errors() <-chan error
	GetOffset() *Offset
}

type MockSource struct {
	config    SourceConfig
	eventChan chan *ChangeEvent
	errorChan chan error
	offset    *Offset
	running   bool
	mu        sync.RWMutex
}

func NewMockSource(config SourceConfig) *MockSource {
	return &MockSource{
		config:    config,
		eventChan: make(chan *ChangeEvent, 1000),
		errorChan: make(chan error, 100),
	}
}

func (s *MockSource) Start(ctx context.Context, position interface{}) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.running {
		return errors.New("source already running")
	}

	s.running = true
	s.offset = &Offset{
		SourceType: s.config.SourceType,
		Timestamp:  time.Now(),
	}

	switch s.config.SourceType {
	case SourceMySQLBinlog:
		if pos, ok := position.(BinlogPosition); ok {
			s.offset.BinlogFile = pos.File
			s.offset.BinlogPos = pos.Pos
		}
	case SourcePostgresWAL:
		if pos, ok := position.(WalPosition); ok {
			s.offset.LSN = pos.LSN
		}
	}

	logger.Info("mock cdc source started", zap.String("source_type", string(s.config.SourceType)))
	return nil
}

func (s *MockSource) Stop() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.running {
		return nil
	}

	s.running = false
	close(s.eventChan)
	close(s.errorChan)
	logger.Info("mock cdc source stopped")
	return nil
}

func (s *MockSource) Events() <-chan *ChangeEvent {
	return s.eventChan
}

func (s *MockSource) Errors() <-chan error {
	return s.errorChan
}

func (s *MockSource) GetOffset() *Offset {
	s.mu.RLock()
	defer s.mu.RUnlock()
	offset := *s.offset
	return &offset
}

func (s *MockSource) SimulateEvent(event *ChangeEvent) {
	s.mu.RLock()
	running := s.running
	s.mu.RUnlock()

	if running {
		s.eventChan <- event
	}
}

type CapturePipeline struct {
	source      ChangeEventSource
	serializer  EventSerializer
	adapters    []OutputAdapter
	filter      func(*ChangeEvent) bool
	transformer func(*ChangeEvent) *ChangeEvent
	batchSize   int
	batchTimeout time.Duration
	running     bool
	mu          sync.RWMutex
	wg          sync.WaitGroup
}

func NewCapturePipeline(source ChangeEventSource, serializer EventSerializer) *CapturePipeline {
	if serializer == nil {
		serializer = NewJSONSerializer()
	}
	return &CapturePipeline{
		source:      source,
		serializer:  serializer,
		adapters:    make([]OutputAdapter, 0),
		batchSize:   100,
		batchTimeout: time.Second,
	}
}

func (p *CapturePipeline) AddAdapter(adapter OutputAdapter) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.adapters = append(p.adapters, adapter)
}

func (p *CapturePipeline) SetFilter(filter func(*ChangeEvent) bool) {
	p.filter = filter
}

func (p *CapturePipeline) SetTransformer(transformer func(*ChangeEvent) *ChangeEvent) {
	p.transformer = transformer
}

func (p *CapturePipeline) SetBatchSize(size int) {
	if size > 0 {
		p.batchSize = size
	}
}

func (p *CapturePipeline) SetBatchTimeout(timeout time.Duration) {
	if timeout > 0 {
		p.batchTimeout = timeout
	}
}

func (p *CapturePipeline) Start(ctx context.Context, position interface{}) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.running {
		return errors.New("pipeline already running")
	}

	if err := p.source.Start(ctx, position); err != nil {
		return fmt.Errorf("failed to start source: %w", err)
	}

	for _, adapter := range p.adapters {
		if err := adapter.Start(ctx); err != nil {
			logger.Error("failed to start adapter", zap.Error(err))
		}
	}

	p.running = true
	p.wg.Add(1)
	go p.run(ctx)

	logger.Info("cdc capture pipeline started", zap.Int("adapters", len(p.adapters)))
	return nil
}

func (p *CapturePipeline) run(ctx context.Context) {
	defer p.wg.Done()

	var batch []*ChangeEvent
	batchTimer := time.NewTimer(p.batchTimeout)
	defer batchTimer.Stop()

	eventChan := p.source.Events()
	errorChan := p.source.Errors()

	for {
		select {
		case <-ctx.Done():
			if len(batch) > 0 {
				p.sendBatch(ctx, batch)
			}
			return

		case err, ok := <-errorChan:
			if !ok {
				continue
			}
			logger.Error("cdc source error", zap.Error(err))

		case event, ok := <-eventChan:
			if !ok {
				if len(batch) > 0 {
					p.sendBatch(ctx, batch)
				}
				return
			}

			if p.filter != nil && !p.filter(event) {
				continue
			}

			if p.transformer != nil {
				event = p.transformer(event)
			}

			batch = append(batch, event)

			if len(batch) >= p.batchSize {
				p.sendBatch(ctx, batch)
				batch = nil
				batchTimer.Reset(p.batchTimeout)
			}

		case <-batchTimer.C:
			if len(batch) > 0 {
				p.sendBatch(ctx, batch)
				batch = nil
			}
			batchTimer.Reset(p.batchTimeout)
		}
	}
}

func (p *CapturePipeline) sendBatch(ctx context.Context, events []*ChangeEvent) {
	p.mu.RLock()
	adapters := make([]OutputAdapter, len(p.adapters))
	copy(adapters, p.adapters)
	p.mu.RUnlock()

	for _, adapter := range adapters {
		if err := adapter.SendBatch(ctx, events); err != nil {
			logger.Error("failed to send batch to adapter", zap.Error(err))
		}
	}
}

func (p *CapturePipeline) Stop() error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if !p.running {
		return nil
	}

	p.running = false

	if err := p.source.Stop(); err != nil {
		logger.Error("failed to stop source", zap.Error(err))
	}

	for _, adapter := range p.adapters {
		if err := adapter.Stop(); err != nil {
			logger.Error("failed to stop adapter", zap.Error(err))
		}
	}

	p.wg.Wait()
	logger.Info("cdc capture pipeline stopped")
	return nil
}

func (p *CapturePipeline) GetOffset() *Offset {
	return p.source.GetOffset()
}

type OffsetManager interface {
	Save(ctx context.Context, pipelineID string, offset *Offset) error
	Load(ctx context.Context, pipelineID string) (*Offset, error)
	Delete(ctx context.Context, pipelineID string) error
}

type MemoryOffsetManager struct {
	offsets map[string]*Offset
	mu      sync.RWMutex
}

func NewMemoryOffsetManager() *MemoryOffsetManager {
	return &MemoryOffsetManager{
		offsets: make(map[string]*Offset),
	}
}

func (m *MemoryOffsetManager) Save(ctx context.Context, pipelineID string, offset *Offset) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.offsets[pipelineID] = offset
	return nil
}

func (m *MemoryOffsetManager) Load(ctx context.Context, pipelineID string) (*Offset, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if offset, ok := m.offsets[pipelineID]; ok {
		return offset, nil
	}
	return nil, nil
}

func (m *MemoryOffsetManager) Delete(ctx context.Context, pipelineID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.offsets, pipelineID)
	return nil
}

type EventTransformer struct{}

func NewEventTransformer() *EventTransformer {
	return &EventTransformer{}
}

func (t *EventTransformer) Flatten(event *ChangeEvent) *ChangeEvent {
	if event.After != nil {
		flattened := make(map[string]interface{})
		for k, v := range event.After {
			flattened[k] = v
		}
		event.Metadata["flattened"] = flattened
	}
	return event
}

func (t *EventTransformer) AddTimestamp(event *ChangeEvent) *ChangeEvent {
	if event.Metadata == nil {
		event.Metadata = make(map[string]interface{})
	}
	event.Metadata["processed_at"] = time.Now().UTC()
	return event
}

func (t *EventTransformer) MaskSensitive(event *ChangeEvent, fields []string) *ChangeEvent {
	for _, field := range fields {
		if event.After != nil {
			if _, ok := event.After[field]; ok {
				event.After[field] = "***MASKED***"
			}
		}
		if event.Before != nil {
			if _, ok := event.Before[field]; ok {
				event.Before[field] = "***MASKED***"
			}
		}
	}
	return event
}

type SchemaRegistry struct {
	schemas map[string]map[int]map[string]interface{}
	mu      sync.RWMutex
}

func NewSchemaRegistry() *SchemaRegistry {
	return &SchemaRegistry{
		schemas: make(map[string]map[int]map[string]interface{}),
	}
}

func (r *SchemaRegistry) Register(table string, version int, schema map[string]interface{}) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if _, ok := r.schemas[table]; !ok {
		r.schemas[table] = make(map[int]map[string]interface{})
	}
	r.schemas[table][version] = schema
}

func (r *SchemaRegistry) Get(table string, version int) (map[string]interface{}, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	if versions, ok := r.schemas[table]; ok {
		if schema, ok := versions[version]; ok {
			return schema, true
		}
	}
	return nil, false
}

func (r *SchemaRegistry) GetLatest(table string) (map[string]interface{}, int, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	if versions, ok := r.schemas[table]; ok {
		maxVersion := 0
		for v := range versions {
			if v > maxVersion {
				maxVersion = v
			}
		}
		if maxVersion > 0 {
			return versions[maxVersion], maxVersion, true
		}
	}
	return nil, 0, false
}
