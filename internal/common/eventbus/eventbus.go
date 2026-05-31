package eventbus

import (
	"context"
	"sync"
)

type Event struct {
	Type      string
	Payload   interface{}
	Timestamp int64
	TraceID   string
}

type Handler func(ctx context.Context, event Event) error

type EventBus struct {
	handlers map[string][]Handler
	mu       sync.RWMutex
}

var (
	instance *EventBus
	once     sync.Once
)

func GetInstance() *EventBus {
	once.Do(func() {
		instance = &EventBus{
			handlers: make(map[string][]Handler),
		}
	})
	return instance
}

func (eb *EventBus) Subscribe(eventType string, handler Handler) {
	eb.mu.Lock()
	defer eb.mu.Unlock()
	eb.handlers[eventType] = append(eb.handlers[eventType], handler)
}

func (eb *EventBus) Publish(ctx context.Context, event Event) {
	eb.mu.RLock()
	defer eb.mu.RUnlock()
	if handlers, exists := eb.handlers[event.Type]; exists {
		for _, handler := range handlers {
			h := handler
			go func() {
				_ = h(ctx, event)
			}()
		}
	}
}

func (eb *EventBus) PublishSync(ctx context.Context, event Event) error {
	eb.mu.RLock()
	defer eb.mu.RUnlock()
	if handlers, exists := eb.handlers[event.Type]; exists {
		for _, handler := range handlers {
			if err := handler(ctx, event); err != nil {
				return err
			}
		}
	}
	return nil
}

func (eb *EventBus) Unsubscribe(eventType string, handler Handler) {
	eb.mu.Lock()
	defer eb.mu.Unlock()
	if handlers, exists := eb.handlers[event.Type]; exists {
		for i, h := range handlers {
			if &h == &handler {
				eb.handlers[eventType] = append(handlers[:i], handlers[i+1:]...)
				break
			}
		}
	}
}
