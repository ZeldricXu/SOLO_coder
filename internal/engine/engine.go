package engine

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
	"streamsql/internal/cdc"
	"streamsql/internal/common/logger"
	"streamsql/internal/compression"
	"streamsql/internal/lifecycle"
	"streamsql/internal/lineage"
	"streamsql/internal/metacrawler"
	"streamsql/internal/quality"
	"streamsql/internal/streamparser"
	"streamsql/internal/vectorindex"
)

type EventType string

const (
	EventQuerySubmitted  EventType = "query.submitted"
	EventQueryCompleted EventType = "query.completed"
	EventQueryFailed EventType = "query.failed"
	EventDataIngested EventType = "data.ingested"
	EventDataCompressed EventType = "data.compressed"
	EventQualityChecked EventType = "quality.checked"
	EventLineageUpdated EventType = "lineage.updated"
	EventStateChanged EventType = "state.changed"
)

type Event struct {
	ID        string                 `json:"id"`
	Type      EventType              `json:"type"`
	Source    string                 `json:"source"`
	Timestamp time.Time              `json:"timestamp"`
	Payload   map[string]interface{} `json:"payload"`
}

type EventHandler func(event Event)

type StateStore interface {
	Save(key string, value interface{}) error
	Get(key string) (interface{}, error)
	Delete(key string) error
	List(prefix string) ([]interface{}, error)
}

type InMemoryStateStore struct {
	data map[string]interface{}
	mu   sync.RWMutex
}

func NewInMemoryStateStore() *InMemoryStateStore {
	return &InMemoryStateStore{
		data: make(map[string]interface{}),
	}
}

func (s *InMemoryStateStore) Save(key string, value interface{}) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.data[key] = value
	return nil
}

func (s *InMemoryStateStore) Get(key string) (interface{}, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	value, exists := s.data[key]
	if !exists {
		return nil, fmt.Errorf("key not found: %s", key)
	}
	return value, nil
}

func (s *InMemoryStateStore) Delete(key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.data, key)
	return nil
}

func (s *InMemoryStateStore) List(prefix string) ([]interface{}, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var results []interface{}
	for key, value := range s.data {
		if len(key) >= len(prefix) && key[:len(prefix)] == prefix {
			results = append(results, value)
		}
	}
	return results, nil
}

type CoreEngine struct {
	compressionService *compression.CompressionService
	qualityService     *quality.QualityService
	lifecycleService   *lifecycle.LifecycleService
	cdcService         *cdc.CDCService
	vectorService      *vectorindex.VectorIndexService
	lineageService      *lineage.LineageService
	parserService      *streamparser.StreamParserService
	crawlerService     *metacrawler.MetaCrawlerService
	stateStore         StateStore
	eventHandlers    map[EventType][]EventHandler
	mu               sync.RWMutex
}

func NewCoreEngine() *CoreEngine {
	engine := &CoreEngine{
		compressionService: compression.NewCompressionService(),
		qualityService:     quality.NewQualityService(),
		lifecycleService:   lifecycle.NewLifecycleService(),
		cdcService:         cdc.NewCDCService(),
		vectorService:      vectorindex.NewVectorIndexService(),
		lineageService:      lineage.NewLineageService(),
		parserService:      streamparser.NewStreamParserService(4),
		crawlerService:     metacrawler.NewMetaCrawlerService(),
		stateStore:         NewInMemoryStateStore(),
		eventHandlers:    make(map[EventType][]EventHandler),
	}

	engine.registerDefaultHandlers()

	logger.Sugar().Info("Core Engine initialized")
	return engine
}

func (e *CoreEngine) registerDefaultHandlers() {
	e.Subscribe(EventQueryCompleted, func(event Event) {
		logger.Sugar().Infof("Query completed: %v", event.Payload)
	})

	e.Subscribe(EventDataCompressed, func(event Event) {
		logger.Sugar().Infof("Data compressed: %v", event.Payload)
	})

	e.Subscribe(EventLineageUpdated, func(event Event) {
		logger.Sugar().Infof("Lineage updated: %v", event.Payload)
	})
}

func (e *CoreEngine) Subscribe(eventType EventType, handler EventHandler) {
	e.mu.Lock()
	defer e.mu.Unlock()

	e.eventHandlers[eventType] = append(e.eventHandlers[eventType], handler)
	logger.Sugar().Infof("Subscribed handler for event: %s", eventType)
}

func (e *CoreEngine) Emit(event Event) {
	e.mu.RLock()
	handlers, exists := e.eventHandlers[event.Type]
	e.mu.RUnlock()

	if !exists {
		return
	}

	for _, handler := range handlers {
		go handler(event)
	}
}

func (e *CoreEngine) emitEvent(eventType EventType, source string, payload map[string]interface{}) {
	event := Event{
		ID:        uuid.New().String(),
		Type:      eventType,
		Source:    source,
		Timestamp: time.Now().UTC(),
		Payload:   payload,
	}
	e.Emit(event)
}

func (e *CoreEngine) ExecuteQuery(ctx context.Context, sql string) (*streamparser.QueryExecution, error) {
	logger.Sugar().Infof("Executing query: %s", sql)

	e.emitEvent(EventQuerySubmitted, "engine", map[string]interface{}{
		"sql": sql,
	})

	result, err := e.parserService.Execute(sql)
	if err != nil {
		e.emitEvent(EventQueryFailed, "engine", map[string]interface{}{
			"sql":   sql,
			"error": err.Error(),
		})
		return result, err
	}

	e.lineageService.ParseSQL(sql, "user")

	e.emitEvent(EventQueryCompleted, "engine", map[string]interface{}{
		"query_id": result.ID,
		"duration": result.DurationMs,
		"status":   result.Status,
	})

	e.stateStore.Save(fmt.Sprintf("query:%s", result.ID), result)

	return result, nil
}

func (e *CoreEngine) GetCompressionService() *compression.CompressionService {
	return e.compressionService
}

func (e *CoreEngine) GetQualityService() *quality.QualityService {
	return e.qualityService
}

func (e *CoreEngine) GetLifecycleService() *lifecycle.LifecycleService {
	return e.lifecycleService
}

func (e *CoreEngine) GetCDCService() *cdc.CDCService {
	return e.cdcService
}

func (e *CoreEngine) GetVectorService() *vectorindex.VectorIndexService {
	return e.vectorService
}

func (e *CoreEngine) GetLineageService() *lineage.LineageService {
	return e.lineageService
}

func (e *CoreEngine) GetParserService() *streamparser.StreamParserService {
	return e.parserService
}

func (e *CoreEngine) GetCrawlerService() *metacrawler.MetaCrawlerService {
	return e.crawlerService
}

func (e *CoreEngine) GetStateStore() StateStore {
	return e.stateStore
}

func (e *CoreEngine) GetStats() map[string]interface{} {
	compressionStats := e.compressionService.GetStats()
	qualityStats := e.qualityService.GetStats()
	lifecycleStats := e.lifecycleService.GetStats()
	parserStats := e.parserService.GetStats()
	crawlerStats := e.crawlerService.GetStats()

	return map[string]interface{}{
		"compression": compressionStats,
		"quality":     qualityStats,
		"lifecycle": lifecycleStats,
		"parser":    parserStats,
		"crawler":   crawlerStats,
	}
}

func (e *CoreEngine) Start() {
	logger.Sugar().Info("Core Engine starting...")
	e.qualityService.StartAllRules()
	logger.Sugar().Info("Core Engine started successfully")
}

func (e *CoreEngine) Stop() {
	logger.Sugar().Info("Core Engine stopping...")
	e.qualityService.StopAllRules()
	logger.Sugar().Info("Core Engine stopped")
}
