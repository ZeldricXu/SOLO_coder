package events

import (
	"context"
	"sync"
	"time"

	"github.com/solocoder/task-scheduler/internal/models"
)

type EventType string

const (
	EventTaskCreated    EventType = "task.created"
	EventTaskStarted    EventType = "task.started"
	EventTaskCompleted  EventType = "task.completed"
	EventTaskFailed     EventType = "task.failed"
	EventTaskCancelled  EventType = "task.cancelled"
	EventProgressUpdate EventType = "task.progress"
	EventResourceChange EventType = "resource.changed"
	EventConfigChange   EventType = "config.changed"
	EventBackupStarted  EventType = "backup.started"
	EventBackupComplete EventType = "backup.completed"
	EventBackupFailed   EventType = "backup.failed"
	EventRestoreComplete EventType = "restore.completed"
	EventRestoreFailed  EventType = "restore.failed"
)

type Event struct {
	ID         string                 `json:"id"`
	Type       EventType              `json:"type"`
	EntityID   string                 `json:"entity_id,omitempty"`
	Payload    map[string]interface{} `json:"payload"`
	Timestamp  time.Time              `json:"timestamp"`
	TraceCtx   *models.TraceContext   `json:"-"`
}

type EventHandler func(ctx context.Context, event Event) error

type EventBus interface {
	Publish(ctx context.Context, event Event) error
	Subscribe(eventType EventType, handler EventHandler)
	Unsubscribe(eventType EventType, handler EventHandler) error
	Close()
}

type InMemoryEventBus struct {
	mu           sync.RWMutex
	subscribers  map[EventType][]EventHandler
	eventQueue   chan Event
	stopCh       chan struct{}
	workerCount  int
}

func NewInMemoryEventBus(bufferSize int, workerCount int) *InMemoryEventBus {
	bus := &InMemoryEventBus{
		subscribers: make(map[EventType][]EventHandler),
		eventQueue:  make(chan Event, bufferSize),
		stopCh:      make(chan struct{}),
		workerCount: workerCount,
	}

	for i := 0; i < workerCount; i++ {
		go bus.worker()
	}

	return bus
}

func (b *InMemoryEventBus) worker() {
	for {
		select {
		case event := <-b.eventQueue:
			b.dispatch(event)
		case <-b.stopCh:
			return
		}
	}
}

func (b *InMemoryEventBus) dispatch(event Event) {
	b.mu.RLock()
	handlers, exists := b.subscribers[event.Type]
	b.mu.RUnlock()

	if !exists {
		return
	}

	ctx := context.Background()
	if event.TraceCtx != nil {
		ctx = context.WithValue(ctx, "traceID", event.TraceCtx.TraceID)
	}

	for _, handler := range handlers {
		func(h EventHandler) {
			defer func() {
				if r := recover(); r != nil {
				}
			}()
			_ = h(ctx, event)
		}(handler)
	}
}

func (b *InMemoryEventBus) Publish(ctx context.Context, event Event) error {
	select {
	case b.eventQueue <- event:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (b *InMemoryEventBus) Subscribe(eventType EventType, handler EventHandler) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.subscribers[eventType] = append(b.subscribers[eventType], handler)
}

func (b *InMemoryEventBus) Unsubscribe(eventType EventType, handler EventHandler) error {
	b.mu.Lock()
	defer b.mu.Unlock()

	handlers, exists := b.subscribers[eventType]
	if !exists {
		return nil
	}

	for i, h := range handlers {
		if &h == &handler {
			b.subscribers[eventType] = append(handlers[:i], handlers[i+1:]...)
			break
		}
	}

	return nil
}

func (b *InMemoryEventBus) Close() {
	close(b.stopCh)
}

func NewEvent(eventType EventType, entityID string, payload map[string]interface{}, traceCtx *models.TraceContext) Event {
	return Event{
		ID:        generateEventID(),
		Type:      eventType,
		EntityID:  entityID,
		Payload:   payload,
		Timestamp: time.Now(),
		TraceCtx:  traceCtx,
	}
}

func generateEventID() string {
	return "evt_" + time.Now().Format("20060102150405") + "_" + randomString(8)
}

func randomString(n int) string {
	const letters = "abcdefghijklmnopqrstuvwxyz0123456789"
	b := make([]byte, n)
	for i := range b {
		b[i] = letters[time.Now().UnixNano()%int64(len(letters))]
	}
	return string(b)
}
