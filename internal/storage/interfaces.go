package storage

import (
	"context"
	"database/sql"
	"time"

	"log-pipeline/pkg/models"
)

type LogStore interface {
	InsertLog(ctx context.Context, log *models.LogEntry) error
	InsertLogs(ctx context.Context, logs []*models.LogEntry) error
	QueryLogs(ctx context.Context, startTime, endTime time.Time, query string, limit int) ([]*models.LogEntry, error)
	Close() error
}

type AggregateStore interface {
	InsertAggregate(ctx context.Context, agg *models.WindowAggregate) error
	QueryAggregate(ctx context.Context, sql string, args ...interface{}) (*sql.Rows, error)
}

type AnomalyStore interface {
	InsertAnomaly(ctx context.Context, anomaly *models.AnomalyResult) error
}

type AlertStore interface {
	InsertAlert(ctx context.Context, alert *models.AlertEvent) error
}

type WindowStore interface {
	SetWindowState(key string, state interface{}, ttl time.Duration) error
	GetWindowState(key string, result interface{}) error
	DeleteWindowState(key string) error
}

type DedupStore interface {
	Deduplicate(key string, value string, ttl time.Duration) (bool, error)
	IsDuplicate(key string) (bool, error)
}

type CacheStore interface {
	CacheLog(log *models.LogEntry, ttl time.Duration) error
	GetLog(id string) (*models.LogEntry, error)
}

type CounterStore interface {
	IncrementCounter(key string) (int64, error)
	GetCounter(key string) (int64, error)
	SetCounter(key string, value int64, ttl time.Duration) error
}

type SetStore interface {
	AddToSet(key string, members ...string) error
	IsMember(key string, member string) (bool, error)
}

type PubSubStore interface {
	Publish(channel string, message interface{}) error
	Subscribe(channel string) interface{}
}

type QueueStore interface {
	LPush(key string, values ...interface{}) error
	RPop(key string) (string, error)
	LLen(key string) (int64, error)
}

type KVStore interface {
	SetWithTTL(key string, value interface{}, ttl time.Duration) error
	Get(key string) (string, error)
	Delete(key string) error
	Keys(pattern string) ([]string, error)
}
