package adapters

import (
	"context"
	"sync"
	"time"

	"github.com/apishield/apishield/internal/core/ports"
	"github.com/google/uuid"
)

type eventHandlerEntry struct {
	id      string
	handler ports.EventHandler
}

type EventBusAdapter struct {
	mu         sync.RWMutex
	handlers   map[string][]eventHandlerEntry
	bufferSize int
	eventChan  chan ports.Event
	wg         sync.WaitGroup
	closeOnce  sync.Once
	done       chan struct{}
}

func NewEventBusAdapter(bufferSize int) *EventBusAdapter {
	if bufferSize <= 0 {
		bufferSize = 100
	}

	eb := &EventBusAdapter{
		handlers:   make(map[string][]eventHandlerEntry),
		bufferSize: bufferSize,
		eventChan:  make(chan ports.Event, bufferSize),
		done:       make(chan struct{}),
	}

	eb.wg.Add(1)
	go eb.dispatchLoop()

	return eb
}

func (eb *EventBusAdapter) dispatchLoop() {
	defer eb.wg.Done()

	for {
		select {
		case event := <-eb.eventChan:
			eb.dispatch(context.Background(), event)
		case <-eb.done:
			return
		}
	}
}

func (eb *EventBusAdapter) dispatch(ctx context.Context, event ports.Event) {
	eb.mu.RLock()
	handlers, ok := eb.handlers[event.Type]
	eb.mu.RUnlock()

	if !ok {
		return
	}

	for _, h := range handlers {
		go func(handler ports.EventHandler) {
			_ = handler(ctx, event)
		}(h.handler)
	}
}

func (eb *EventBusAdapter) Publish(ctx context.Context, event ports.Event) error {
	if event.ID == uuid.Nil {
		event.ID = uuid.New()
	}
	if event.Timestamp == 0 {
		event.Timestamp = time.Now().Unix()
	}

	select {
	case eb.eventChan <- event:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	case <-eb.done:
		return nil
	}
}

func (eb *EventBusAdapter) Subscribe(ctx context.Context, eventType string, handler ports.EventHandler) error {
	eb.mu.Lock()
	defer eb.mu.Unlock()

	entry := eventHandlerEntry{
		id:      uuid.New().String(),
		handler: handler,
	}

	eb.handlers[eventType] = append(eb.handlers[eventType], entry)
	return nil
}

func (eb *EventBusAdapter) Unsubscribe(ctx context.Context, eventType string, handlerID string) error {
	eb.mu.Lock()
	defer eb.mu.Unlock()

	handlers, ok := eb.handlers[eventType]
	if !ok {
		return nil
	}

	for i, h := range handlers {
		if h.id == handlerID {
			eb.handlers[eventType] = append(handlers[:i], handlers[i+1:]...)
			break
		}
	}

	return nil
}

func (eb *EventBusAdapter) Close(ctx context.Context) error {
	eb.closeOnce.Do(func() {
		close(eb.done)
	})

	eb.wg.Wait()
	close(eb.eventChan)

	return nil
}
