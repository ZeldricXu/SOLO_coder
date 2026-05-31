package cdc

import (
	"encoding/json"
	"sync"
	"time"

	"github.com/datatransform/platform/pkg/logger"
	"go.uber.org/zap"
)

type EventType string

const (
	EventTypeInsert EventType = "INSERT"
	EventTypeUpdate EventType = "UPDATE"
	EventTypeDelete EventType = "DELETE"
)

type CDCRecord struct {
	Database   string                 `json:"database"`
	Table      string                 `json:"table"`
	EventType  EventType              `json:"event_type"`
	PrimaryKey map[string]interface{} `json:"primary_key"`
	Before     map[string]interface{} `json:"before,omitempty"`
	After      map[string]interface{} `json:"after,omitempty"`
	Timestamp  time.Time              `json:"timestamp"`
	LSN        string                 `json:"lsn"`
}

type EventOutput interface {
	Publish(record CDCRecord) error
}

type ConsoleOutput struct{}

func (c *ConsoleOutput) Publish(record CDCRecord) error {
	data, _ := json.Marshal(record)
	logger.Info("CDC event", zap.String("record", string(data)))
	return nil
}

type KafkaOutput struct {
	brokers []string
	topic   string
}

func NewKafkaOutput(brokers []string, topic string) *KafkaOutput {
	return &KafkaOutput{
		brokers: brokers,
		topic:   topic,
	}
}

func (k *KafkaOutput) Publish(record CDCRecord) error {
	data, err := json.Marshal(record)
	if err != nil {
		return err
	}
	logger.Info("Kafka publish",
		zap.Strings("brokers", k.brokers),
		zap.String("topic", k.topic),
		zap.String("message", string(data)),
	)
	return nil
}

type CDCConfig struct {
	DatabaseType string
	Connection   string
	Tables       []string
	StartLSN     string
}

type CDCCapture struct {
	config   CDCConfig
	outputs  []EventOutput
	running  bool
	stopChan chan struct{}
	mu       sync.RWMutex
	lsn      string
}

func NewCDCCapture(config CDCConfig) *CDCCapture {
	return &CDCCapture{
		config:   config,
		outputs:  make([]EventOutput, 0),
		stopChan: make(chan struct{}),
		lsn:      config.StartLSN,
	}
}

func (c *CDCCapture) AddOutput(output EventOutput) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.outputs = append(c.outputs, output)
}

func (c *CDCCapture) Start() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.running {
		return nil
	}

	c.running = true
	logger.Info("starting CDC capture",
		zap.String("database_type", c.config.DatabaseType),
		zap.Strings("tables", c.config.Tables),
	)

	go c.captureLoop()

	return nil
}

func (c *CDCCapture) Stop() {
	c.mu.Lock()
	defer c.mu.Unlock()

	if !c.running {
		return
	}

	c.running = false
	close(c.stopChan)
	logger.Info("CDC capture stopped")
}

func (c *CDCCapture) captureLoop() {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-c.stopChan:
			return
		case <-ticker.C:
			c.processEvents()
		}
	}
}

func (c *CDCCapture) processEvents() {
	records := c.simulateBinlogParsing()

	for _, record := range records {
		c.publishToOutputs(record)
		c.mu.Lock()
		c.lsn = record.LSN
		c.mu.Unlock()
	}
}

func (c *CDCCapture) simulateBinlogParsing() []CDCRecord {
	records := make([]CDCRecord, 0)

	for _, table := range c.config.Tables {
		eventTypes := []EventType{EventTypeInsert, EventTypeUpdate, EventTypeDelete}
		for _, eventType := range eventTypes {
			record := CDCRecord{
				Database:  c.config.DatabaseType,
				Table:     table,
				EventType: eventType,
				PrimaryKey: map[string]interface{}{
					"id": time.Now().UnixNano(),
				},
				Timestamp: time.Now(),
				LSN:       c.generateLSN(),
			}

			switch eventType {
			case EventTypeInsert:
				record.After = map[string]interface{}{
					"id":         time.Now().UnixNano(),
					"name":       "test",
					"created_at": time.Now().Format(time.RFC3339),
				}
			case EventTypeUpdate:
				record.Before = map[string]interface{}{
					"id":         time.Now().UnixNano(),
					"name":       "old_value",
					"updated_at": time.Now().Add(-1 * time.Hour).Format(time.RFC3339),
				}
				record.After = map[string]interface{}{
					"id":         time.Now().UnixNano(),
					"name":       "new_value",
					"updated_at": time.Now().Format(time.RFC3339),
				}
			case EventTypeDelete:
				record.Before = map[string]interface{}{
					"id":         time.Now().UnixNano(),
					"name":       "deleted",
					"deleted_at": time.Now().Format(time.RFC3339),
				}
			}

			records = append(records, record)
		}
	}

	return records
}

func (c *CDCCapture) publishToOutputs(record CDCRecord) {
	c.mu.RLock()
	outputs := make([]EventOutput, len(c.outputs))
	copy(outputs, c.outputs)
	c.mu.RUnlock()

	for _, output := range outputs {
		if err := output.Publish(record); err != nil {
			logger.Error("failed to publish CDC record",
				zap.Error(err),
				zap.String("table", record.Table),
				zap.String("event_type", string(record.EventType)),
			)
		}
	}
}

func (c *CDCCapture) generateLSN() string {
	return time.Now().Format("20060102150405.000000")
}

func (c *CDCCapture) GetCurrentLSN() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.lsn
}

func (c *CDCCapture) IsRunning() bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.running
}

func SerializeRecord(record CDCRecord) ([]byte, error) {
	return json.Marshal(record)
}

func DeserializeRecord(data []byte) (CDCRecord, error) {
	var record CDCRecord
	err := json.Unmarshal(data, &record)
	return record, err
}
