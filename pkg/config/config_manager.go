package config

import (
	"context"
	"fmt"
	"sync"
	"time"

	"session189/pkg/eventbus"
)

type ConfigSource string

const (
	ConfigSourceMemory ConfigSource = "memory"
	ConfigSourceFile   ConfigSource = "file"
	ConfigSourceDB     ConfigSource = "db"
)

type ConfigEntry struct {
	Key       string      `json:"key"`
	Value     interface{} `json:"value"`
	Version   int64       `json:"version"`
	Source    ConfigSource `json:"source"`
	UpdatedAt time.Time   `json:"updated_at"`
}

type ConfigChangeEvent struct {
	Key     string      `json:"key"`
	OldValue interface{} `json:"old_value"`
	NewValue interface{} `json:"new_value"`
	Version int64       `json:"version"`
}

type ChangeHandler func(ctx context.Context, change ConfigChangeEvent) error

type ConfigManager interface {
	Get(ctx context.Context, key string) (interface{}, bool)
	GetString(ctx context.Context, key string, defaultValue string) string
	GetInt(ctx context.Context, key string, defaultValue int) int
	GetBool(ctx context.Context, key string, defaultValue bool) bool
	GetFloat64(ctx context.Context, key string, defaultValue float64) float64
	Set(ctx context.Context, key string, value interface{}) error
	Subscribe(key string, handler ChangeHandler) Subscription
	Unsubscribe(sub Subscription)
	Load(ctx context.Context, source ConfigSource, uri string) error
	Watch(ctx context.Context, source ConfigSource, uri string, interval time.Duration)
	Close()
}

type Subscription interface {
	ID() string
	Key() string
	Unsubscribe()
}

type configSubscription struct {
	id      string
	key     string
	handler ChangeHandler
	manager *configManager
}

func (s *configSubscription) ID() string { return s.id }
func (s *configSubscription) Key() string { return s.key }
func (s *configSubscription) Unsubscribe() { s.manager.Unsubscribe(s) }

type configManager struct {
	mu           sync.RWMutex
	configs      map[string]*ConfigEntry
	handlers     map[string]map[string]ChangeHandler
	bus          eventbus.EventBus
	watchCancel  context.CancelFunc
}

func NewManager(bus eventbus.EventBus) ConfigManager {
	return &configManager{
		configs:  make(map[string]*ConfigEntry),
		handlers: make(map[string]map[string]ChangeHandler),
		bus:      bus,
	}
}

func (m *configManager) Get(ctx context.Context, key string) (interface{}, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	entry, exists := m.configs[key]
	if !exists {
		return nil, false
	}
	return entry.Value, true
}

func (m *configManager) GetString(ctx context.Context, key string, defaultValue string) string {
	if v, ok := m.Get(ctx, key); ok {
		if s, ok := v.(string); ok {
			return s
		}
	}
	return defaultValue
}

func (m *configManager) GetInt(ctx context.Context, key string, defaultValue int) int {
	if v, ok := m.Get(ctx, key); ok {
		switch val := v.(type) {
		case int:
			return val
		case float64:
			return int(val)
		}
	}
	return defaultValue
}

func (m *configManager) GetBool(ctx context.Context, key string, defaultValue bool) bool {
	if v, ok := m.Get(ctx, key); ok {
		if b, ok := v.(bool); ok {
			return b
		}
	}
	return defaultValue
}

func (m *configManager) GetFloat64(ctx context.Context, key string, defaultValue float64) float64 {
	if v, ok := m.Get(ctx, key); ok {
		switch val := v.(type) {
		case float64:
			return val
		case int:
			return float64(val)
		}
	}
	return defaultValue
}

func (m *configManager) Set(ctx context.Context, key string, value interface{}) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	oldEntry, exists := m.configs[key]
	var oldValue interface{}
	var newVersion int64 = 1
	if exists {
		oldValue = oldEntry.Value
		newVersion = oldEntry.Version + 1
	}

	m.configs[key] = &ConfigEntry{
		Key:       key,
		Value:     value,
		Version:   newVersion,
		Source:    ConfigSourceMemory,
		UpdatedAt: time.Now(),
	}

	change := ConfigChangeEvent{
		Key:     key,
		OldValue: oldValue,
		NewValue: value,
		Version: newVersion,
	}

	m.notifyHandlers(ctx, change)
	return nil
}

func (m *configManager) Subscribe(key string, handler ChangeHandler) Subscription {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.handlers[key]; !exists {
		m.handlers[key] = make(map[string]ChangeHandler)
	}

	id := fmt.Sprintf("cfg-sub-%d", len(m.handlers[key])+1)
	m.handlers[key][id] = handler

	return &configSubscription{
		id:      id,
		key:     key,
		handler: handler,
		manager: m,
	}
}

func (m *configManager) Unsubscribe(sub Subscription) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if handlers, exists := m.handlers[sub.Key()]; exists {
		delete(handlers, sub.ID())
	}
}

func (m *configManager) notifyHandlers(ctx context.Context, change ConfigChangeEvent) {
	m.bus.Publish(ctx, eventbus.Event{
		Type:      eventbus.EventTypeConfigUpdated,
		Source:    "config-manager",
		Timestamp: time.Now().UnixNano(),
		Data:      change,
	})

	if handlers, exists := m.handlers[change.Key]; exists {
		for _, handler := range handlers {
			go func(h ChangeHandler) {
				_ = h(ctx, change)
			}(handler)
		}
	}
}

func (m *configManager) Load(ctx context.Context, source ConfigSource, uri string) error {
	return nil
}

func (m *configManager) Watch(ctx context.Context, source ConfigSource, uri string, interval time.Duration) {
	watchCtx, cancel := context.WithCancel(ctx)
	m.watchCancel = cancel

	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-watchCtx.Done():
				return
			case <-ticker.C:
				_ = m.Load(watchCtx, source, uri)
			}
		}
	}()
}

func (m *configManager) Close() {
	if m.watchCancel != nil {
		m.watchCancel()
	}
}
