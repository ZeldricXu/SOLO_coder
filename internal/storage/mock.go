package storage

import (
	"context"
	"database/sql"
	"encoding/json"
	"sync"
	"time"

	"log-pipeline/pkg/models"
)

type MockClickHouseStore struct {
	mu        sync.RWMutex
	logs      []*models.LogEntry
	aggregates []*models.WindowAggregate
	anomalies []*models.AnomalyResult
	alerts    []*models.AlertEvent
	queryLogsFunc func(ctx context.Context, startTime, endTime time.Time, query string, limit int) ([]*models.LogEntry, error)
}

func NewMockClickHouseStore() *MockClickHouseStore {
	return &MockClickHouseStore{
		logs:       make([]*models.LogEntry, 0),
		aggregates: make([]*models.WindowAggregate, 0),
		anomalies:  make([]*models.AnomalyResult, 0),
		alerts:     make([]*models.AlertEvent, 0),
	}
}

func (m *MockClickHouseStore) InsertLog(ctx context.Context, log *models.LogEntry) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.logs = append(m.logs, log)
	return nil
}

func (m *MockClickHouseStore) InsertLogs(ctx context.Context, logs []*models.LogEntry) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.logs = append(m.logs, logs...)
	return nil
}

func (m *MockClickHouseStore) InsertAggregate(ctx context.Context, agg *models.WindowAggregate) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.aggregates = append(m.aggregates, agg)
	return nil
}

func (m *MockClickHouseStore) InsertAnomaly(ctx context.Context, anomaly *models.AnomalyResult) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.anomalies = append(m.anomalies, anomaly)
	return nil
}

func (m *MockClickHouseStore) InsertAlert(ctx context.Context, alert *models.AlertEvent) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.alerts = append(m.alerts, alert)
	return nil
}

func (m *MockClickHouseStore) QueryLogs(ctx context.Context, startTime, endTime time.Time, query string, limit int) ([]*models.LogEntry, error) {
	if m.queryLogsFunc != nil {
		return m.queryLogsFunc(ctx, startTime, endTime, query, limit)
	}
	m.mu.RLock()
	defer m.mu.RUnlock()
	var result []*models.LogEntry
	for _, log := range m.logs {
		if log.Timestamp.After(startTime) && log.Timestamp.Before(endTime) {
			result = append(result, log)
			if len(result) >= limit {
				break
			}
		}
	}
	return result, nil
}

func (m *MockClickHouseStore) QueryAggregate(ctx context.Context, sql string, args ...interface{}) (*sql.Rows, error) {
	return nil, nil
}

func (m *MockClickHouseStore) Close() error {
	return nil
}

func (m *MockClickHouseStore) GetLogs() []*models.LogEntry {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.logs
}

func (m *MockClickHouseStore) GetAggregates() []*models.WindowAggregate {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.aggregates
}

func (m *MockClickHouseStore) GetAnomalies() []*models.AnomalyResult {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.anomalies
}

func (m *MockClickHouseStore) GetAlerts() []*models.AlertEvent {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.alerts
}

func (m *MockClickHouseStore) Clear() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.logs = make([]*models.LogEntry, 0)
	m.aggregates = make([]*models.WindowAggregate, 0)
	m.anomalies = make([]*models.AnomalyResult, 0)
	m.alerts = make([]*models.AlertEvent, 0)
}

type MockRedisStore struct {
	mu           sync.RWMutex
	data         map[string]string
	windowStates map[string]string
	dedupKeys    map[string]string
	counters     map[string]int64
	sets         map[string]map[string]bool
	lists        map[string][]string
}

func NewMockRedisStore() *MockRedisStore {
	return &MockRedisStore{
		data:         make(map[string]string),
		windowStates: make(map[string]string),
		dedupKeys:    make(map[string]string),
		counters:     make(map[string]int64),
		sets:         make(map[string]map[string]bool),
		lists:        make(map[string][]string),
	}
}

func (m *MockRedisStore) SetWindowState(key string, state interface{}, ttl time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	data, _ := json.Marshal(state)
	m.windowStates[key] = string(data)
	return nil
}

func (m *MockRedisStore) GetWindowState(key string, result interface{}) error {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if data, ok := m.windowStates[key]; ok {
		return json.Unmarshal([]byte(data), result)
	}
	return sql.ErrNoRows
}

func (m *MockRedisStore) DeleteWindowState(key string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.windowStates, key)
	return nil
}

func (m *MockRedisStore) Deduplicate(key string, value string, ttl time.Duration) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, exists := m.dedupKeys[key]; exists {
		return false, nil
	}
	m.dedupKeys[key] = value
	return true, nil
}

func (m *MockRedisStore) IsDuplicate(key string) (bool, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	_, exists := m.dedupKeys[key]
	return exists, nil
}

func (m *MockRedisStore) CacheLog(log *models.LogEntry, ttl time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	data, _ := json.Marshal(log)
	m.data["log:"+log.ID] = string(data)
	return nil
}

func (m *MockRedisStore) GetLog(id string) (*models.LogEntry, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if data, ok := m.data["log:"+id]; ok {
		var log models.LogEntry
		if err := json.Unmarshal([]byte(data), &log); err != nil {
			return nil, err
		}
		return &log, nil
	}
	return nil, sql.ErrNoRows
}

func (m *MockRedisStore) IncrementCounter(key string) (int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.counters[key]++
	return m.counters[key], nil
}

func (m *MockRedisStore) GetCounter(key string) (int64, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.counters[key], nil
}

func (m *MockRedisStore) SetCounter(key string, value int64, ttl time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.counters[key] = value
	return nil
}

func (m *MockRedisStore) AddToSet(key string, members ...string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.sets[key]; !ok {
		m.sets[key] = make(map[string]bool)
	}
	for _, member := range members {
		m.sets[key][member] = true
	}
	return nil
}

func (m *MockRedisStore) IsMember(key string, member string) (bool, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if set, ok := m.sets[key]; ok {
		return set[member], nil
	}
	return false, nil
}

func (m *MockRedisStore) LPush(key string, values ...interface{}) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, v := range values {
		m.lists[key] = append([]string{toString(v)}, m.lists[key]...)
	}
	return nil
}

func (m *MockRedisStore) RPop(key string) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.lists[key]) == 0 {
		return "", sql.ErrNoRows
	}
	last := m.lists[key][len(m.lists[key])-1]
	m.lists[key] = m.lists[key][:len(m.lists[key])-1]
	return last, nil
}

func (m *MockRedisStore) LLen(key string) (int64, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return int64(len(m.lists[key])), nil
}

func (m *MockRedisStore) SetWithTTL(key string, value interface{}, ttl time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.data[key] = toString(value)
	return nil
}

func (m *MockRedisStore) Get(key string) (string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if v, ok := m.data[key]; ok {
		return v, nil
	}
	return "", sql.ErrNoRows
}

func (m *MockRedisStore) Delete(key string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.data, key)
	return nil
}

func (m *MockRedisStore) Keys(pattern string) ([]string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	var keys []string
	for k := range m.data {
		keys = append(keys, k)
	}
	return keys, nil
}

func (m *MockRedisStore) Publish(channel string, message interface{}) error {
	return nil
}

func (m *MockRedisStore) Subscribe(channel string) interface{} {
	return nil
}

func (m *MockRedisStore) Close() error {
	return nil
}

func (m *MockRedisStore) Clear() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.data = make(map[string]string)
	m.windowStates = make(map[string]string)
	m.dedupKeys = make(map[string]string)
	m.counters = make(map[string]int64)
	m.sets = make(map[string]map[string]bool)
	m.lists = make(map[string][]string)
}

func toString(v interface{}) string {
	if s, ok := v.(string); ok {
		return s
	}
	data, _ := json.Marshal(v)
	return string(data)
}
