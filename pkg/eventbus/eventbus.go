package eventbus

import (
	"context"
	"fmt"
	"sync"
)

type EventType string

const (
	EventTypeConfigUpdated EventType = "config.updated"
	EventTypeTaskCreated  EventType = "task.created"
	EventTypeTaskUpdated  EventType = "task.updated"
	EventTypeTaskCompleted EventType = "task.completed"
	EventTypeTaskFailed   EventType = "task.failed"
	EventTypeAlertTriggered EventType = "alert.triggered"
	EventTypeProfilingStarted EventType = "profiling.started"
	EventTypeProfilingCompleted EventType = "profiling.completed"
)

type Event struct {
	Type      EventType
	Source    string
	Timestamp int64
	Data      interface{}
}

type Handler func(ctx context.Context, event Event) error

type EventBus interface {
	Publish(ctx context.Context, event Event) error
	Subscribe(eventType EventType, handler Handler) Subscription
	Unsubscribe(sub Subscription)
	Close()
}

type Subscription interface {
	ID() string
	EventType() EventType
	Unsubscribe()
}

type subscription struct {
	id        string
	eventType EventType
	handler   Handler
	bus       *eventBus
}

func (s *subscription) ID() string          { return s.id }
func (s *subscription) EventType() EventType { return s.eventType }
func (s *subscription) Unsubscribe()          { s.bus.Unsubscribe(s) }

type eventBus struct {
	mu          sync.RWMutex
	handlers    map[EventType]map[string]Handler
	eventChan   chan Event
	workerCount int
	wg          sync.WaitGroup
	ctx         context.Context
	cancel      context.CancelFunc
}

func New(workerCount int, bufferSize int) EventBus {
	ctx, cancel := context.WithCancel(context.Background())
	bus := &eventBus{
		handlers:    make(map[EventType]map[string]Handler),
		eventChan:   make(chan Event, bufferSize),
		workerCount: workerCount,
		ctx:         ctx,
		cancel:      cancel,
	}
	bus.startWorkers()
	return bus
}

func (b *eventBus) startWorkers() {
	for i := 0; i < b.workerCount; i++ {
		b.wg.Add(1)
		go b.worker(i)
	}
}

func (b *eventBus) worker(id int) {
	defer b.wg.Done()
	for {
		select {
		case <-b.ctx.Done():
			return
		case event := <-b.eventChan:
			b.dispatchEvent(event)
		}
	}
}

func (b *eventBus) dispatchEvent(event Event) {
	b.mu.RLock()
	handlers, exists := b.handlers[event.Type]
	b.mu.RUnlock()

	if !exists {
		return
	}

	for _, handler := range handlers {
		if err := handler(b.ctx, event); err != nil {
			fmt.Printf("event handler error: %v\n", err)
		}
	}
}

func (b *eventBus) Publish(ctx context.Context, event Event) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	case b.eventChan <- event:
		return nil
	default:
		return fmt.Errorf("event bus buffer full")
	}
}

func (b *eventBus) Subscribe(eventType EventType, handler Handler) Subscription {
	b.mu.Lock()
	defer b.mu.Unlock()

	if _, exists := b.handlers[eventType]; !exists {
		b.handlers[eventType] = make(map[string]Handler)
	}

	id := fmt.Sprintf("sub-%d", len(b.handlers[eventType])+1)
	b.handlers[eventType][id] = handler

	return &subscription{
		id:        id,
		eventType: eventType,
		handler:   handler,
		bus:       b,
	}
}

func (b *eventBus) Unsubscribe(sub Subscription) {
	b.mu.Lock()
	defer b.mu.Unlock()

	if handlers, exists := b.handlers[sub.EventType()]; exists {
		delete(handlers, sub.ID())
	}
}

func (b *eventBus) Close() {
	b.cancel()
	b.wg.Wait()
	close(b.eventChan)
}
