package cdc

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/datatrace/datatrace/internal/common"
	"github.com/datatrace/datatrace/internal/models"
)

type OperationType string

const (
	OpInsert OperationType = "INSERT"
	OpUpdate OperationType = "UPDATE"
	OpDelete OperationType = "DELETE"
	OpCreate OperationType = "CREATE"
	OpAlter  OperationType = "ALTER"
	OpDrop   OperationType = "DROP"

	DefaultEmitTimeout    = 10 * time.Second
	SourceTypeMySQLBinlog = "mysql_binlog"
	SourceTypePostgresWAL = "postgres_wal"
)

type RowData struct {
	Before map[string]interface{} `json:"before,omitempty"`
	After  map[string]interface{} `json:"after,omitempty"`
}

type ChangeEvent struct {
	ID         string                 `json:"id"`
	Database   string                 `json:"database"`
	Schema     string                 `json:"schema"`
	Table      string                 `json:"table"`
	Operation  OperationType          `json:"operation"`
	Data       RowData                `json:"data"`
	Timestamp  time.Time              `json:"timestamp"`
	LSN        int64                  `json:"lsn"`
	SourceType string                 `json:"source_type"`
	Metadata   map[string]interface{} `json:"metadata,omitempty"`
}

type Serializer interface {
	Serialize(event *ChangeEvent) ([]byte, error)
}

type JSONSerializer struct{}

func NewJSONSerializer() *JSONSerializer {
	return &JSONSerializer{}
}

func (s *JSONSerializer) Serialize(event *ChangeEvent) ([]byte, error) {
	return json.Marshal(event)
}

type OutputAdapter interface {
	Emit(ctx context.Context, data []byte) error
	Close() error
}

type MemoryAdapter struct {
	events [][]byte
	mu     sync.Mutex
}

func NewMemoryAdapter() *MemoryAdapter {
	return &MemoryAdapter{
		events: make([][]byte, 0),
	}
}

func (a *MemoryAdapter) Emit(ctx context.Context, data []byte) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.events = append(a.events, data)
	return nil
}

func (a *MemoryAdapter) Close() error {
	return nil
}

func (a *MemoryAdapter) GetEvents() [][]byte {
	a.mu.Lock()
	defer a.mu.Unlock()
	result := make([][]byte, len(a.events))
	copy(result, a.events)
	return result
}

type BinlogParser interface {
	Parse(ctx context.Context, data []byte) (*ChangeEvent, error)
	SourceType() string
}

type BaseBinlogParser struct {
	sourceType string
}

func (p *BaseBinlogParser) newEvent() *ChangeEvent {
	return &ChangeEvent{
		ID:         common.NewID(),
		Timestamp:  time.Now(),
		SourceType: p.sourceType,
	}
}

type MySQLBinlogParser struct {
	BaseBinlogParser
}

func NewMySQLBinlogParser() *MySQLBinlogParser {
	return &MySQLBinlogParser{
		BaseBinlogParser: BaseBinlogParser{sourceType: SourceTypeMySQLBinlog},
	}
}

func (p *MySQLBinlogParser) SourceType() string {
	return p.sourceType
}

func (p *MySQLBinlogParser) Parse(ctx context.Context, data []byte) (*ChangeEvent, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
	}

	event := p.newEvent()
	return event, nil
}

type PostgresWALParser struct {
	BaseBinlogParser
}

func NewPostgresWALParser() *PostgresWALParser {
	return &PostgresWALParser{
		BaseBinlogParser: BaseBinlogParser{sourceType: SourceTypePostgresWAL},
	}
}

func (p *PostgresWALParser) SourceType() string {
	return p.sourceType
}

func (p *PostgresWALParser) Parse(ctx context.Context, data []byte) (*ChangeEvent, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
	}

	event := p.newEvent()
	return event, nil
}

type Metrics struct {
	EventsProcessed int64
	BytesProcessed  int64
	LastLSN         int64
	LastEventTime   time.Time
}

type Service struct {
	common.BaseService
	parsers    map[string]BinlogParser
	serializer Serializer
	output     OutputAdapter
	eventChan  chan *ChangeEvent
	mu         sync.RWMutex
	metrics    *Metrics
}

type CDCService = Service

func NewService(bufferSize int) *Service {
	return &Service{
		BaseService: common.NewBaseService(),
		parsers:     make(map[string]BinlogParser),
		serializer:  NewJSONSerializer(),
		eventChan:   make(chan *ChangeEvent, bufferSize),
		metrics:     &Metrics{},
	}
}

func NewCDCService(bufferSize int) *CDCService {
	return NewService(bufferSize)
}

func (s *Service) RegisterParser(sourceType string, parser BinlogParser) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.parsers[sourceType] = parser
}

func (s *Service) SetSerializer(serializer Serializer) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.serializer = serializer
}

func (s *Service) SetOutputAdapter(adapter OutputAdapter) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.output = adapter
}

func (s *Service) Start() {
	_ = s.BaseService.Start()
	s.AddWorker(1)
	go s.processEvents()
}

func (s *Service) Stop() {
	_ = s.BaseService.Stop()

	s.mu.Lock()
	if s.output != nil {
		s.output.Close()
	}
	s.mu.Unlock()
}

func (s *Service) Ingest(ctx context.Context, sourceType string, data []byte) error {
	if !s.IsRunning() {
		return common.WrapError(common.CodeUnavailable, "cdc service is not running", nil)
	}

	s.mu.RLock()
	parser, ok := s.parsers[sourceType]
	s.mu.RUnlock()

	if !ok {
		return common.WrapError(common.CodeInvalidInput,
			fmt.Sprintf("no parser registered for source type: %s", sourceType), nil)
	}

	event, err := parser.Parse(ctx, data)
	if err != nil {
		return common.WrapError(common.CodeInternalError, "failed to parse binlog data", err)
	}

	select {
	case s.eventChan <- event:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	default:
		return common.WrapError(common.CodeQueueFull, "event channel is full", nil)
	}
}

func (s *Service) processEvents() {
	defer s.WorkerDone()

	for {
		select {
		case <-s.StopChan():
			close(s.eventChan)
			return
		case event := <-s.eventChan:
			if event != nil {
				_ = s.processEvent(event)
			}
		}
	}
}

func (s *Service) processEvent(event *ChangeEvent) error {
	s.mu.RLock()
	serializer := s.serializer
	output := s.output
	s.mu.RUnlock()

	data, err := serializer.Serialize(event)
	if err != nil {
		return common.WrapError(common.CodeInternalError, "failed to serialize event", err)
	}

	if output != nil {
		ctx, cancel := context.WithTimeout(context.Background(), DefaultEmitTimeout)
		defer cancel()

		if err := output.Emit(ctx, data); err != nil {
			return common.WrapError(common.CodeInternalError, "failed to emit event", err)
		}
	}

	s.mu.Lock()
	s.metrics.EventsProcessed++
	s.metrics.BytesProcessed += int64(len(data))
	s.metrics.LastLSN = event.LSN
	s.metrics.LastEventTime = event.Timestamp
	s.mu.Unlock()

	return nil
}

func (s *Service) GetMetrics() *Metrics {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return &Metrics{
		EventsProcessed: s.metrics.EventsProcessed,
		BytesProcessed:  s.metrics.BytesProcessed,
		LastLSN:         s.metrics.LastLSN,
		LastEventTime:   s.metrics.LastEventTime,
	}
}

func (s *Service) QueueStatus() common.QueueStatus {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return common.QueueStatus{
		Queued:   len(s.eventChan),
		Capacity: cap(s.eventChan),
	}
}

func (s *Service) ToEntity() *models.Entity {
	return common.NewEntity("cdc_service")
}

func (s *Service) GetMetricsMap() map[string]interface{} {
	m := s.GetMetrics()
	qs := s.QueueStatus()
	return map[string]interface{}{
		"events_processed": m.EventsProcessed,
		"bytes_processed":  m.BytesProcessed,
		"last_lsn":         m.LastLSN,
		"last_event_time":  m.LastEventTime,
		"queued":           qs.Queued,
		"capacity":         qs.Capacity,
		"parser_count":     len(s.parsers),
		"uptime":           s.Uptime().String(),
	}
}
