package eventbus

import (
	"sync"
)

type Event struct {
	Type    string
	Payload interface{}
}

type Handler func(Event)

type EventBus struct {
	subscribers map[string][]Handler
	mu          sync.RWMutex
}

var (
	instance *EventBus
	once     sync.Once
)

func GetBus() *EventBus {
	once.Do(func() {
		instance = &EventBus{
			subscribers: make(map[string][]Handler),
		}
	})
	return instance
}

func (eb *EventBus) Subscribe(eventType string, handler Handler) {
	eb.mu.Lock()
	defer eb.mu.Unlock()
	eb.subscribers[eventType] = append(eb.subscribers[eventType], handler)
}

func (eb *EventBus) Publish(event Event) {
	eb.mu.RLock()
	defer eb.mu.RUnlock()
	handlers, exists := eb.subscribers[event.Type]
	if !exists {
		return
	}
	for _, h := range handlers {
		go h(event)
	}
}
