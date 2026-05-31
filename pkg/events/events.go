package events

import (
	"context"
	"sync"
	"time"
)

type EventType string

const (
	EventResourceCreated   EventType = "resource.created"
	EventResourceUpdated   EventType = "resource.updated"
	EventResourceDeleted   EventType = "resource.deleted"
	EventTaskCompleted     EventType = "task.completed"
	EventTaskFailed        EventType = "task.failed"
	EventDeviceRegistered  EventType = "device.registered"
	EventDeviceOnline      EventType = "device.online"
	EventDeviceOffline     EventType = "device.offline"
	EventConfigChanged     EventType = "config.changed"
	EventOTARollout        EventType = "ota.rollout"
	EventOTACompleted      EventType = "ota.completed"
	EventOTAFailed         EventType = "ota.failed"
	EventInferenceReady    EventType = "inference.ready"
	EventInferenceResult   EventType = "inference.result"
	EventRuleTriggered     EventType = "rule.triggered"
)

type Event struct {
	ID        string                 `json:"id"`
	Type      EventType              `json:"type"`
	Source    string                 `json:"source"`
	Timestamp time.Time              `json:"timestamp"`
	TraceID   string                 `json:"trace_id"`
	Payload   map[string]interface{} `json:"payload"`
}

type EventHandler func(ctx context.Context, event Event) error

type EventBus interface {
	Publish(ctx context.Context, event Event) error
	Subscribe(eventType EventType, handler EventHandler) string
	Unsubscribe(subscriptionID string)
	Close()
}

type InMemoryEventBus struct {
	mu          sync.RWMutex
	handlers    map[EventType]map[string]EventHandler
	subscribers map[string]EventType
}

func NewInMemoryEventBus() *InMemoryEventBus {
	return &InMemoryEventBus{
		handlers:    make(map[EventType]map[string]EventHandler),
		subscribers: make(map[string]EventType),
	}
}

func (eb *InMemoryEventBus) Publish(ctx context.Context, event Event) error {
	eb.mu.RLock()
	handlers, exists := eb.handlers[event.Type]
	eb.mu.RUnlock()

	if !exists {
		return nil
	}

	for _, handler := range handlers {
		if err := handler(ctx, event); err != nil {
			return err
		}
	}
	return nil
}

func (eb *InMemoryEventBus) Subscribe(eventType EventType, handler EventHandler) string {
	eb.mu.Lock()
	defer eb.mu.Unlock()

	subID := "sub_" + time.Now().Format("20060102150405") + "_" + string(eventType)

	if _, exists := eb.handlers[eventType]; !exists {
		eb.handlers[eventType] = make(map[string]EventHandler)
	}
	eb.handlers[eventType][subID] = handler
	eb.subscribers[subID] = eventType

	return subID
}

func (eb *InMemoryEventBus) Unsubscribe(subscriptionID string) {
	eb.mu.Lock()
	defer eb.mu.Unlock()

	eventType, exists := eb.subscribers[subscriptionID]
	if !exists {
		return
	}

	if handlers, ok := eb.handlers[eventType]; ok {
		delete(handlers, subscriptionID)
	}
	delete(eb.subscribers, subscriptionID)
}

func (eb *InMemoryEventBus) Close() {
	eb.mu.Lock()
	defer eb.mu.Unlock()

	eb.handlers = make(map[EventType]map[string]EventHandler)
	eb.subscribers = make(map[string]EventType)
}
