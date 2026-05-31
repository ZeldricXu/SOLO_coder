package events

import (
	"context"
	"encoding/json"
	"sync"
)

type Event struct {
	Type    string                 `json:"type"`
	Payload map[string]interface{} `json:"payload"`
	TraceID string                 `json:"trace_id"`
}

type Handler func(ctx context.Context, event Event) error

type EventBus struct {
	mu       sync.RWMutex
	handlers map[string][]Handler
}

var (
	bus  *EventBus
	once sync.Once
)

func Get() *EventBus {
	once.Do(func() {
		bus = &EventBus{
			handlers: make(map[string][]Handler),
		}
	})
	return bus
}

func (eb *EventBus) Subscribe(eventType string, handler Handler) {
	eb.mu.Lock()
	defer eb.mu.Unlock()
	eb.handlers[eventType] = append(eb.handlers[eventType], handler)
}

func (eb *EventBus) Publish(ctx context.Context, event Event) {
	eb.mu.RLock()
	handlers := eb.handlers[event.Type]
	eb.mu.RUnlock()

	for _, h := range handlers {
		go func(handler Handler) {
			_ = handler(ctx, event)
		}(h)
	}
}

func (e Event) Marshal() string {
	data, _ := json.Marshal(e)
	return string(data)
}
