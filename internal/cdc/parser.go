package cdc

import (
	"encoding/json"
	"time"
)

type EventType string

const (
	EventTypeInsert  EventType = "INSERT"
	EventTypeUpdate  EventType = "UPDATE"
	EventTypeDelete  EventType = "DELETE"
	EventTypeDDL     EventType = "DDL"
	EventTypeHeartbeat EventType = "HEARTBEAT"
)

type ChangeEvent struct {
	ID            string                 `json:"id"`
	EventType     EventType              `json:"event_type"`
	Database      string                 `json:"database"`
	Schema        string                 `json:"schema"`
	TableName     string                 `json:"table_name"`
	PrimaryKey    map[string]interface{} `json:"primary_key"`
	Before        map[string]interface{} `json:"before,omitempty"`
	After         map[string]interface{} `json:"after,omitempty"`
	Timestamp     time.Time              `json:"timestamp"`
	SourceLogName string                 `json:"source_log_name"`
	LogPosition   int64                  `json:"log_position"`
	TransactionID string                 `json:"transaction_id,omitempty"`
	Metadata      map[string]interface{} `json:"metadata,omitempty"`
}

type BinlogParser interface {
	Parse(rawData []byte) ([]ChangeEvent, error)
	Supports(sourceType string) bool
}

type MySQLBinlogParser struct{}

func NewMySQLBinlogParser() *MySQLBinlogParser {
	return &MySQLBinlogParser{}
}

func (p *MySQLBinlogParser) Supports(sourceType string) bool {
	return sourceType == "mysql" || sourceType == "mariadb"
}

func (p *MySQLBinlogParser) Parse(rawData []byte) ([]ChangeEvent, error) {
	var events []ChangeEvent

	var rawEvent map[string]interface{}
	if err := json.Unmarshal(rawData, &rawEvent); err != nil {
		return nil, err
	}

	event := ChangeEvent{
		ID:            p.getString(rawEvent, "id"),
		EventType:     EventType(p.getString(rawEvent, "event_type")),
		Database:      p.getString(rawEvent, "database"),
		Schema:        p.getString(rawEvent, "schema"),
		TableName:     p.getString(rawEvent, "table_name"),
		PrimaryKey:    p.getMap(rawEvent, "primary_key"),
		Before:        p.getMap(rawEvent, "before"),
		After:         p.getMap(rawEvent, "after"),
		Timestamp:     time.Now().UTC(),
		SourceLogName: p.getString(rawEvent, "source_log_name"),
		LogPosition:   p.getInt64(rawEvent, "log_position"),
		TransactionID: p.getString(rawEvent, "transaction_id"),
		Metadata:      p.getMap(rawEvent, "metadata"),
	}

	events = append(events, event)
	return events, nil
}

func (p *MySQLBinlogParser) getString(m map[string]interface{}, key string) string {
	if v, ok := m[key].(string); ok {
		return v
	}
	return ""
}

func (p *MySQLBinlogParser) getInt64(m map[string]interface{}, key string) int64 {
	if v, ok := m[key].(float64); ok {
		return int64(v)
	}
	if v, ok := m[key].(int64); ok {
		return v
	}
	return 0
}

func (p *MySQLBinlogParser) getMap(m map[string]interface{}, key string) map[string]interface{} {
	if v, ok := m[key].(map[string]interface{}); ok {
		return v
	}
	return make(map[string]interface{})
}

type PostgreSQLWALParser struct{}

func NewPostgreSQLWALParser() *PostgreSQLWALParser {
	return &PostgreSQLWALParser{}
}

func (p *PostgreSQLWALParser) Supports(sourceType string) bool {
	return sourceType == "postgres" || sourceType == "postgresql"
}

func (p *PostgreSQLWALParser) Parse(rawData []byte) ([]ChangeEvent, error) {
	var events []ChangeEvent

	var walEntry struct {
		Change []struct {
			Kind         string                 `json:"kind"`
			Schema       string                 `json:"schema"`
			Table        string                 `json:"table"`
			ColumnNames  []string               `json:"columnnames"`
			ColumnValues []interface{}          `json:"columnvalues"`
			OldKeys      *struct {
				KeyNames  []string      `json:"keynames"`
				KeyValues []interface{} `json:"keyvalues"`
			} `json:"oldkeys,omitempty"`
		} `json:"change"`
	}

	if err := json.Unmarshal(rawData, &walEntry); err != nil {
		return nil, err
	}

	for _, change := range walEntry.Change {
		event := ChangeEvent{
			EventType: p.mapEventType(change.Kind),
			Schema:    change.Schema,
			TableName: change.Table,
			Timestamp: time.Now().UTC(),
			After:     make(map[string]interface{}),
		}

		for i, name := range change.ColumnNames {
			if i < len(change.ColumnValues) {
				event.After[name] = change.ColumnValues[i]
			}
		}

		if change.OldKeys != nil {
			event.PrimaryKey = make(map[string]interface{})
			event.Before = make(map[string]interface{})
			for i, name := range change.OldKeys.KeyNames {
				if i < len(change.OldKeys.KeyValues) {
					event.PrimaryKey[name] = change.OldKeys.KeyValues[i]
					event.Before[name] = change.OldKeys.KeyValues[i]
				}
			}
		}

		events = append(events, event)
	}

	return events, nil
}

func (p *PostgreSQLWALParser) mapEventType(kind string) EventType {
	switch kind {
	case "insert":
		return EventTypeInsert
	case "update":
		return EventTypeUpdate
	case "delete":
		return EventTypeDelete
	default:
		return EventTypeDDL
	}
}

type MongoDBOplogParser struct{}

func NewMongoDBOplogParser() *MongoDBOplogParser {
	return &MongoDBOplogParser{}
}

func (p *MongoDBOplogParser) Supports(sourceType string) bool {
	return sourceType == "mongodb" || sourceType == "mongo"
}

func (p *MongoDBOplogParser) Parse(rawData []byte) ([]ChangeEvent, error) {
	var oplog struct {
		Op        string                 `json:"op"`
		NS        string                 `json:"ns"`
		O         map[string]interface{} `json:"o"`
		O2        map[string]interface{} `json:"o2,omitempty"`
		Timestamp int64                  `json:"ts"`
		WallTime  int64                  `json:"wall,omitempty"`
	}

	if err := json.Unmarshal(rawData, &oplog); err != nil {
		return nil, err
	}

	event := ChangeEvent{
		EventType:   p.mapEventType(oplog.Op),
		TableName:   oplog.NS,
		Timestamp:   time.Unix(oplog.Timestamp, 0).UTC(),
		PrimaryKey:  oplog.O2,
		After:       oplog.O,
		LogPosition: oplog.Timestamp,
	}

	return []ChangeEvent{event}, nil
}

func (p *MongoDBOplogParser) mapEventType(op string) EventType {
	switch op {
	case "i":
		return EventTypeInsert
	case "u":
		return EventTypeUpdate
	case "d":
		return EventTypeDelete
	default:
		return EventTypeDDL
	}
}
