package cdc

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"session172/internal/logger"
	"session172/pkg/models"
	"session172/pkg/utils"
)

type CDCCapture struct {
	mu           sync.RWMutex
	parser       LogParser
	serializer   EventSerializer
	adapter      OutputAdapter
	events       []*models.CDCEvent
	handlers     []func(*models.CDCEvent)
	isRunning    bool
	lastLSN      string
}

type LogParser interface {
	Parse(ctx context.Context, data []byte) (*models.CDCEvent, error)
	ParseWalEntry(ctx context.Context, entry interface{}) (*models.CDCEvent, error)
}

type EventSerializer interface {
	Serialize(event *models.CDCEvent) ([]byte, error)
	Deserialize(data []byte) (*models.CDCEvent, error)
	ToJSON(event *models.CDCEvent) (string, error)
	FromJSON(data string) (*models.CDCEvent, error)
}

type OutputAdapter interface {
	Send(ctx context.Context, event *models.CDCEvent) error
	BatchSend(ctx context.Context, events []*models.CDCEvent) error
	Close() error
}

type BinlogParser struct {
	schema map[string]map[string]string
}

type JSONSerializer struct{}

type ConsoleAdapter struct{}
type KafkaAdapter struct {
	brokers []string
	topic   string
}
type RedisAdapter struct {
	addr     string
	password string
	db       int
	channel  string
}

var (
	cdcInstance *CDCCapture
	cdcOnce     sync.Once
)

func NewCDCCapture(parser LogParser, serializer EventSerializer, adapter OutputAdapter) *CDCCapture {
	cdcOnce.Do(func() {
		if parser == nil {
			parser = NewBinlogParser()
		}
		if serializer == nil {
			serializer = NewJSONSerializer()
		}
		if adapter == nil {
			adapter = NewConsoleAdapter()
		}

		cdcInstance = &CDCCapture{
			parser:     parser,
			serializer: serializer,
			adapter:    adapter,
			events:     make([]*models.CDCEvent, 0),
			handlers:   make([]func(*models.CDCEvent), 0),
		}
	})
	return cdcInstance
}

func GetCDCCapture() *CDCCapture {
	if cdcInstance == nil {
		return NewCDCCapture(nil, nil, nil)
	}
	return cdcInstance
}

func NewBinlogParser() *BinlogParser {
	return &BinlogParser{
		schema: make(map[string]map[string]string),
	}
}

func (p *BinlogParser) Parse(ctx context.Context, data []byte) (*models.CDCEvent, error) {
	var rawEvent map[string]interface{}
	if err := json.Unmarshal(data, &rawEvent); err != nil {
		return nil, fmt.Errorf("failed to parse binlog: %w", err)
	}

	return p.parseEvent(rawEvent)
}

func (p *BinlogParser) ParseWalEntry(ctx context.Context, entry interface{}) (*models.CDCEvent, error) {
	switch e := entry.(type) {
	case map[string]interface{}:
		return p.parseEvent(e)
	case []byte:
		return p.Parse(ctx, e)
	default:
		return nil, fmt.Errorf("unsupported entry type: %T", entry)
	}
}

func (p *BinlogParser) parseEvent(raw map[string]interface{}) (*models.CDCEvent, error) {
	event := &models.CDCEvent{
		ID:        utils.GenerateID("cdc"),
		Timestamp: time.Now(),
	}

	if eventType, ok := raw["type"].(string); ok {
		event.EventType = eventType
	}
	if database, ok := raw["database"].(string); ok {
		event.Database = database
	}
	if table, ok := raw["table"].(string); ok {
		event.Table = table
	}
	if lsn, ok := raw["lsn"].(string); ok {
		event.LSN = lsn
	}
	if oldData, ok := raw["old_data"].(map[string]interface{}); ok {
		event.OldData = oldData
	}
	if newData, ok := raw["new_data"].(map[string]interface{}); ok {
		event.NewData = newData
	}
	if pk, ok := raw["primary_key"].(map[string]interface{}); ok {
		event.PrimaryKey = pk
	}

	return event, nil
}

func (p *BinlogParser) RegisterSchema(table string, columns map[string]string) {
	p.schema[table] = columns
}

func NewJSONSerializer() *JSONSerializer {
	return &JSONSerializer{}
}

func (s *JSONSerializer) Serialize(event *models.CDCEvent) ([]byte, error) {
	return json.Marshal(event)
}

func (s *JSONSerializer) Deserialize(data []byte) (*models.CDCEvent, error) {
	var event models.CDCEvent
	if err := json.Unmarshal(data, &event); err != nil {
		return nil, err
	}
	return &event, nil
}

func (s *JSONSerializer) ToJSON(event *models.CDCEvent) (string, error) {
	data, err := s.Serialize(event)
	return string(data), err
}

func (s *JSONSerializer) FromJSON(data string) (*models.CDCEvent, error) {
	return s.Deserialize([]byte(data))
}

func NewConsoleAdapter() *ConsoleAdapter {
	return &ConsoleAdapter{}
}

func (a *ConsoleAdapter) Send(ctx context.Context, event *models.CDCEvent) error {
	jsonStr, _ := utils.ToJSON(event)
	fmt.Printf("[CDC] %s\n", jsonStr)
	return nil
}

func (a *ConsoleAdapter) BatchSend(ctx context.Context, events []*models.CDCEvent) error {
	for _, event := range events {
		a.Send(ctx, event)
	}
	return nil
}

func (a *ConsoleAdapter) Close() error {
	return nil
}

func NewKafkaAdapter(brokers []string, topic string) *KafkaAdapter {
	return &KafkaAdapter{
		brokers: brokers,
		topic:   topic,
	}
}

func (a *KafkaAdapter) Send(ctx context.Context, event *models.CDCEvent) error {
	logger.Infof("Kafka send: topic=%s, event=%s", a.topic, event.ID)
	return nil
}

func (a *KafkaAdapter) BatchSend(ctx context.Context, events []*models.CDCEvent) error {
	logger.Infof("Kafka batch send: topic=%s, count=%d", a.topic, len(events))
	return nil
}

func (a *KafkaAdapter) Close() error {
	return nil
}

func NewRedisAdapter(addr, password string, db int, channel string) *RedisAdapter {
	return &RedisAdapter{
		addr:     addr,
		password: password,
		db:       db,
		channel:  channel,
	}
}

func (a *RedisAdapter) Send(ctx context.Context, event *models.CDCEvent) error {
	logger.Infof("Redis publish: channel=%s, event=%s", a.channel, event.ID)
	return nil
}

func (a *RedisAdapter) BatchSend(ctx context.Context, events []*models.CDCEvent) error {
	logger.Infof("Redis batch publish: channel=%s, count=%d", a.channel, len(events))
	return nil
}

func (a *RedisAdapter) Close() error {
	return nil
}

func (c *CDCCapture) Start(ctx context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.isRunning {
		return fmt.Errorf("CDC capture already running")
	}

	c.isRunning = true
	logger.Info("CDC capture started")
	go c.run(ctx)
	return nil
}

func (c *CDCCapture) Stop() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if !c.isRunning {
		return fmt.Errorf("CDC capture not running")
	}

	c.isRunning = false
	logger.Info("CDC capture stopped")
	return nil
}

func (c *CDCCapture) run(ctx context.Context) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if !c.isRunning {
				return
			}
		}
	}
}

func (c *CDCCapture) Process(ctx context.Context, data []byte) (*models.CDCEvent, error) {
	event, err := c.parser.Parse(ctx, data)
	if err != nil {
		return nil, fmt.Errorf("parse failed: %w", err)
	}

	c.mu.Lock()
	c.events = append(c.events, event)
	if len(c.events) > 10000 {
		c.events = c.events[1000:]
	}
	if event.LSN != "" {
		c.lastLSN = event.LSN
	}
	c.mu.Unlock()

	for _, handler := range c.handlers {
		go handler(event)
	}

	if err := c.adapter.Send(ctx, event); err != nil {
		logger.Errorf("Failed to send event: %v", err)
	}

	return event, nil
}

func (c *CDCCapture) ProcessBatch(ctx context.Context, data [][]byte) ([]*models.CDCEvent, error) {
	events := make([]*models.CDCEvent, 0, len(data))

	for _, d := range data {
		event, err := c.Process(ctx, d)
		if err != nil {
			logger.Errorf("Failed to process entry: %v", err)
			continue
		}
		events = append(events, event)
	}

	if len(events) > 0 {
		if err := c.adapter.BatchSend(ctx, events); err != nil {
			logger.Errorf("Failed to batch send: %v", err)
		}
	}

	return events, nil
}

func (c *CDCCapture) AddHandler(handler func(*models.CDCEvent)) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.handlers = append(c.handlers, handler)
}

func (c *CDCCapture) GetEvents(limit int) []*models.CDCEvent {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if limit <= 0 || limit > len(c.events) {
		limit = len(c.events)
	}

	events := make([]*models.CDCEvent, limit)
	for i := 0; i < limit; i++ {
		events[i] = c.events[len(c.events)-limit+i]
	}
	return events
}

func (c *CDCCapture) GetLastLSN() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.lastLSN
}

func (c *CDCCapture) SetParser(parser LogParser) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.parser = parser
}

func (c *CDCCapture) SetSerializer(serializer EventSerializer) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.serializer = serializer
}

func (c *CDCCapture) SetAdapter(adapter OutputAdapter) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.adapter = adapter
}

func (c *CDCCapture) GetParser() LogParser {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.parser
}

func (c *CDCCapture) GetSerializer() EventSerializer {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.serializer
}

func (c *CDCCapture) GetAdapter() OutputAdapter {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.adapter
}

func (c *CDCCapture) CreateMockEvent(eventType, database, table string, pk, oldData, newData map[string]interface{}) *models.CDCEvent {
	return &models.CDCEvent{
		ID:         utils.GenerateID("cdc"),
		EventType:  eventType,
		Database:   database,
		Table:      table,
		PrimaryKey: pk,
		OldData:    oldData,
		NewData:    newData,
		Timestamp:  time.Now(),
		LSN:        fmt.Sprintf("0/%d", time.Now().UnixNano()),
	}
}
