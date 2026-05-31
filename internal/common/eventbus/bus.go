package eventbus

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/solocoder/session147/internal/common/logger"
	"go.uber.org/zap"
)

type EventType string

const (
	EventTypeGasPriceUpdated     EventType = "gas.price.updated"
	EventTypeGasPriceAlert       EventType = "gas.price.alert"
	EventTypeProposalCreated     EventType = "proposal.created"
	EventTypeProposalSigned      EventType = "proposal.signed"
	EventTypeProposalExecuted    EventType = "proposal.executed"
	EventTypeTxCreated           EventType = "tx.created"
	EventTypeTxSigned            EventType = "tx.signed"
	EventTypeTxBroadcast         EventType = "tx.broadcast"
	EventTypeTxConfirmed         EventType = "tx.confirmed"
	EventTypeTxFailed            EventType = "tx.failed"
	EventTypeBlockIndexed        EventType = "block.indexed"
	EventTypeBridgeInitiated     EventType = "bridge.initiated"
	EventTypeBridgeCompleted     EventType = "bridge.completed"
)

type Event struct {
	ID        string                 `json:"id"`
	Type      EventType              `json:"type"`
	Source    string                 `json:"source"`
	Timestamp time.Time              `json:"timestamp"`
	Data      map[string]interface{} `json:"data"`
	Metadata  map[string]string      `json:"metadata,omitempty"`
}

type EventHandler func(ctx context.Context, event Event) error

type EventSubscription struct {
	ID       string
	EventType EventType
	Handler  EventHandler
	Async    bool
}

type NotificationChannel struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	Channel    string `json:"channel"`
	WebhookURL string `json:"webhook_url"`
	Enabled    bool   `json:"enabled"`
}

type EventBus struct {
	subscribers map[EventType][]*EventSubscription
	mu          sync.RWMutex
	channels    map[string]*NotificationChannel
}

var (
	instance *EventBus
	once     sync.Once
)

func NewEventBus() *EventBus {
	return &EventBus{
		subscribers: make(map[EventType][]*EventSubscription),
		channels:    make(map[string]*NotificationChannel),
	}
}

func GetEventBus() *EventBus {
	once.Do(func() {
		instance = NewEventBus()
	})
	return instance
}

func (bus *EventBus) Publish(ctx context.Context, event Event) error {
	bus.mu.RLock()
	subs, exists := bus.subscribers[event.Type]
	bus.mu.RUnlock()

	if !exists {
		return nil
	}

	for _, sub := range subs {
		if sub.Async {
			go bus.invokeHandler(ctx, sub, event)
		} else {
			if err := bus.invokeHandler(ctx, sub, event); err != nil {
				logger.Error("event handler failed",
					zap.String("event_type", string(event.Type)),
					zap.String("sub_id", sub.ID),
					zap.Error(err))
			}
		}
	}

	bus.notifyChannels(ctx, event)
	return nil
}

func (bus *EventBus) invokeHandler(ctx context.Context, sub *EventSubscription, event Event) error {
	handlerCtx, cancel := context.WithTimeout(ctx, time.Second*30)
	defer cancel()

	done := make(chan error, 1)
	go func() {
		done <- sub.Handler(handlerCtx, event)
	}()

	select {
	case err := <-done:
		return err
	case <-handlerCtx.Done():
		return fmt.Errorf("event handler timeout: %s", sub.ID)
	}
}

func (bus *EventBus) Subscribe(eventType EventType, handler EventHandler, async bool) string {
	bus.mu.Lock()
	defer bus.mu.Unlock()

	subID := fmt.Sprintf("sub_%d", time.Now().UnixNano())
	sub := &EventSubscription{
		ID:       subID,
		EventType: eventType,
		Handler:  handler,
		Async:    async,
	}

	bus.subscribers[eventType] = append(bus.subscribers[eventType], sub)
	return subID
}

func (bus *EventBus) Unsubscribe(subID string) {
	bus.mu.Lock()
	defer bus.mu.Unlock()

	for eventType, subs := range bus.subscribers {
		newSubs := make([]*EventSubscription, 0)
		for _, sub := range subs {
			if sub.ID != subID {
				newSubs = append(newSubs, sub)
			}
		}
		bus.subscribers[eventType] = newSubs
	}
}

func (bus *EventBus) RegisterChannel(ch *NotificationChannel) {
	bus.mu.Lock()
	defer bus.mu.Unlock()
	bus.channels[ch.ID] = ch
}

func (bus *EventBus) UnregisterChannel(channelID string) {
	bus.mu.Lock()
	defer bus.mu.Unlock()
	delete(bus.channels, channelID)
}

func (bus *EventBus) notifyChannels(ctx context.Context, event Event) {
	bus.mu.RLock()
	channels := make([]*NotificationChannel, 0, len(bus.channels))
	for _, ch := range bus.channels {
		if ch.Enabled {
			channels = append(channels, ch)
		}
	}
	bus.mu.RUnlock()

	for _, ch := range channels {
		go bus.sendToChannel(ctx, ch, event)
	}
}

func (bus *EventBus) sendToChannel(ctx context.Context, ch *NotificationChannel, event Event) {
	logger.Info("sending event notification",
		zap.String("channel", ch.Name),
		zap.String("event_type", string(event.Type)),
		zap.String("event_id", event.ID))
}

func (bus *EventBus) ListChannels() []*NotificationChannel {
	bus.mu.RLock()
	defer bus.mu.RUnlock()

	channels := make([]*NotificationChannel, 0, len(bus.channels))
	for _, ch := range bus.channels {
		channels = append(channels, ch)
	}
	return channels
}

func NewEvent(eventType EventType, source string, data map[string]interface{}) Event {
	return Event{
		ID:        fmt.Sprintf("evt_%d", time.Now().UnixNano()),
		Type:      eventType,
		Source:    source,
		Timestamp: time.Now(),
		Data:      data,
		Metadata:  make(map[string]string),
	}
}

type EventListener interface {
	OnEvent(ctx context.Context, event Event) error
	GetEventTypes() []EventType
}

func (bus *EventBus) RegisterListener(listener EventListener) []string {
	subIDs := make([]string, 0)
	for _, eventType := range listener.GetEventTypes() {
		subID := bus.Subscribe(eventType, listener.OnEvent, false)
		subIDs = append(subIDs, subID)
	}
	return subIDs
}
