package testutils

import (
	"context"
	"sync"

	"edgescheduler/internal/common/eventbus"
)

type MockEventBus struct {
	publishedEvents []eventbus.Event
	subscribers     map[eventbus.EventType]map[string]eventbus.EventHandler
	mu              sync.RWMutex
	shouldFail      bool
	failOnEventType eventbus.EventType
}

func NewMockEventBus() *MockEventBus {
	return &MockEventBus{
		publishedEvents: make([]eventbus.Event, 0),
		subscribers:     make(map[eventbus.EventType]map[string]eventbus.EventHandler),
	}
}

func (m *MockEventBus) SetShouldFail(shouldFail bool, eventType eventbus.EventType) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.shouldFail = shouldFail
	m.failOnEventType = eventType
}

func (m *MockEventBus) Publish(ctx context.Context, eventType eventbus.EventType, payload map[string]interface{}, source string) (eventbus.Event, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.shouldFail && eventType == m.failOnEventType {
		return eventbus.Event{}, context.DeadlineExceeded
	}

	event := eventbus.Event{
		Type:      eventType,
		Payload:   payload,
		Source:    source,
		Timestamp: GetTestTime(),
	}
	m.publishedEvents = append(m.publishedEvents, event)

	if handlers, exists := m.subscribers[eventType]; exists {
		for _, handler := range handlers {
			_ = handler(ctx, event)
		}
	}

	return event, nil
}

func (m *MockEventBus) Subscribe(ctx context.Context, eventType eventbus.EventType, handler eventbus.EventHandler) string {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.subscribers[eventType]; !exists {
		m.subscribers[eventType] = make(map[string]eventbus.EventHandler)
	}

	subscriberID := GenerateTestID("sub")
	m.subscribers[eventType][subscriberID] = handler
	return subscriberID
}

func (m *MockEventBus) Unsubscribe(eventType eventbus.EventType, subscriberID string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if handlers, exists := m.subscribers[eventType]; exists {
		delete(handlers, subscriberID)
	}
}

func (m *MockEventBus) PublishEvent(ctx context.Context, event eventbus.Event) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.publishedEvents = append(m.publishedEvents, event)
	return nil
}

func (m *MockEventBus) GetPublishedEvents() []eventbus.Event {
	m.mu.RLock()
	defer m.mu.RUnlock()
	events := make([]eventbus.Event, len(m.publishedEvents))
	copy(events, m.publishedEvents)
	return events
}

func (m *MockEventBus) GetPublishedEventsByType(eventType eventbus.EventType) []eventbus.Event {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var filtered []eventbus.Event
	for _, e := range m.publishedEvents {
		if e.Type == eventType {
			filtered = append(filtered, e)
		}
	}
	return filtered
}

func (m *MockEventBus) GetPublishedEventCount() int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return len(m.publishedEvents)
}

func (m *MockEventBus) ClearEvents() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.publishedEvents = make([]eventbus.Event, 0)
}

func (m *MockEventBus) Reset() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.publishedEvents = make([]eventbus.Event, 0)
	m.subscribers = make(map[eventbus.EventType]map[string]eventbus.EventHandler)
	m.shouldFail = false
}
