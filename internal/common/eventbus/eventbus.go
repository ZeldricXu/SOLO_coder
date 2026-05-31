package eventbus

import (
	"context"
	"sync"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"edgescheduler/internal/common/logger"
)

type EventType string

const (
	EventDeviceRegistered     EventType = "device.registered"
	EventDeviceActivated      EventType = "device.activated"
	EventDeviceStatusChanged  EventType = "device.status.changed"
	EventDeviceDeactivated    EventType = "device.deactivated"

	EventInferenceTaskCreated   EventType = "inference.task.created"
	EventInferenceTaskCompleted EventType = "inference.task.completed"
	EventInferenceTaskFailed    EventType = "inference.task.failed"
	EventModelDeployed          EventType = "model.deployed"

	EventDataCached      EventType = "data.cached"
	EventDataSynced      EventType = "data.synced"
	EventNetworkRestored EventType = "network.restored"
	EventSyncJobCreated  EventType = "sync.job.created"
	EventSyncCompleted   EventType = "sync.completed"
	EventSyncFailed      EventType = "sync.failed"

	EventDataAggregated    EventType = "data.aggregated"
	EventDataIngested      EventType = "data.ingested"
	EventAggregationResult EventType = "aggregation.result"

	EventProtocolDataReceived EventType = "protocol.data.received"
	EventProtocolDataSent     EventType = "protocol.data.sent"

	EventFirmwareGenerated      EventType = "firmware.generated"
	EventFirmwarePublished      EventType = "firmware.published"
	EventUpgradeBatchCreated    EventType = "upgrade.batch.created"
	EventUpgradeStarted         EventType = "upgrade.started"
	EventUpgradeProgress        EventType = "upgrade.progress"
	EventUpgradeSuccess         EventType = "upgrade.success"
	EventUpgradeFailed          EventType = "upgrade.failed"
	EventUpgradeRollback        EventType = "upgrade.rollback"
	EventUpgradeBatchCompleted  EventType = "upgrade.batch.completed"
	EventUpgradeBatchRollback   EventType = "upgrade.batch.rollback"

	EventShadowCreated           EventType = "shadow.created"
	EventShadowDesiredUpdated    EventType = "shadow.desired.updated"
	EventShadowReportedUpdated   EventType = "shadow.reported.updated"
	EventShadowDelta             EventType = "shadow.delta"
	EventShadowSynced            EventType = "shadow.synced"
	EventShadowConflict          EventType = "shadow.conflict"
	EventShadowConflictResolved  EventType = "shadow.conflict.resolved"
	EventShadowRollback          EventType = "shadow.rollback"

	EventRuleCreated    EventType = "rule.created"
	EventRuleTriggered  EventType = "rule.triggered"
	EventRuleExecuted   EventType = "rule.executed"

	EventMQTTMessagePublished EventType = "mqtt.message.published"
	EventAlertTriggered       EventType = "alert.triggered"
)

type Event struct {
	ID        string                 `json:"id"`
	Type      EventType              `json:"type"`
	Timestamp time.Time              `json:"timestamp"`
	Source    string                 `json:"source"`
	TraceID   string                 `json:"trace_id"`
	Payload   map[string]interface{} `json:"payload"`
}

type EventHandler func(ctx context.Context, event Event) error

type EventBus interface {
	Publish(ctx context.Context, eventType EventType, payload map[string]interface{}, source string) (Event, error)
	Subscribe(ctx context.Context, eventType EventType, handler EventHandler) string
	Unsubscribe(eventType EventType, subscriberID string)
	PublishEvent(ctx context.Context, event Event) error
}

type InMemoryEventBus struct {
	subscribers map[EventType]map[string]EventHandler
	mu          sync.RWMutex
}

var (
	instance *InMemoryEventBus
	once     sync.Once
)

func GetEventBus() EventBus {
	once.Do(func() {
		instance = &InMemoryEventBus{
			subscribers: make(map[EventType]map[string]EventHandler),
		}
	})
	return instance
}

func (eb *InMemoryEventBus) Publish(ctx context.Context, eventType EventType, payload map[string]interface{}, source string) (Event, error) {
	traceID := ctx.Value("trace_id")
	if traceID == nil {
		traceID = uuid.New().String()
	}

	event := Event{
		ID:        uuid.New().String(),
		Type:      eventType,
		Timestamp: time.Now().UTC(),
		Source:    source,
		TraceID:   traceID.(string),
		Payload:   payload,
	}

	return event, eb.PublishEvent(ctx, event)
}

func (eb *InMemoryEventBus) PublishEvent(ctx context.Context, event Event) error {
	eb.mu.RLock()
	defer eb.mu.RUnlock()

	handlers, exists := eb.subscribers[event.Type]
	if !exists {
		return nil
	}

	for id, handler := range handlers {
		go func(handlerID string, h EventHandler, e Event) {
			logger.Info("Dispatching event",
				zap.String("event_id", e.ID),
				zap.String("event_type", string(e.Type)),
				zap.String("handler_id", handlerID),
				zap.String("trace_id", e.TraceID),
			)

			if err := h(ctx, e); err != nil {
				logger.Error("Event handler failed",
					zap.String("event_id", e.ID),
					zap.String("event_type", string(e.Type)),
					zap.String("handler_id", handlerID),
					zap.Error(err),
				)
			}
		}(id, handler, event)
	}

	return nil
}

func (eb *InMemoryEventBus) Subscribe(ctx context.Context, eventType EventType, handler EventHandler) string {
	eb.mu.Lock()
	defer eb.mu.Unlock()

	subscriberID := uuid.New().String()

	if _, exists := eb.subscribers[eventType]; !exists {
		eb.subscribers[eventType] = make(map[string]EventHandler)
	}

	eb.subscribers[eventType][subscriberID] = handler

	logger.Info("Subscribed to event",
		zap.String("event_type", string(eventType)),
		zap.String("subscriber_id", subscriberID),
	)

	return subscriberID
}

func (eb *InMemoryEventBus) Unsubscribe(eventType EventType, subscriberID string) {
	eb.mu.Lock()
	defer eb.mu.Unlock()

	if handlers, exists := eb.subscribers[eventType]; exists {
		delete(handlers, subscriberID)
		logger.Info("Unsubscribed from event",
			zap.String("event_type", string(eventType)),
			zap.String("subscriber_id", subscriberID),
		)
	}
}

func BuildEvent(eventType EventType, payload map[string]interface{}) map[string]interface{} {
	return map[string]interface{}{
		"type":    eventType,
		"payload": payload,
		"time":    time.Now().UTC(),
	}
}
