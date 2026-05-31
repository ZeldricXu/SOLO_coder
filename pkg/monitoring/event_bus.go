package monitoring

import (
	"sync"

	"github.com/solocoder/session136/pkg/common/models"
)

type EventType string

const (
	EventMetricRecorded    EventType = "metric.recorded"
	EventAggregateComplete EventType = "aggregate.completed"
	EventFlushComplete    EventType = "flush.completed"
	EventExportComplete   EventType = "export.completed"
)

type MonitorEvent struct {
	Type      EventType
	Timestamp int64
	Data      interface{}
	Source    string
}

type EventHandler func(event *MonitorEvent)

type EventBus interface {
	Publish(event *MonitorEvent)
	Subscribe(eventType EventType, handler EventHandler)
	Unsubscribe(eventType EventType, handler EventHandler)
}

type DefaultEventBus struct {
	handlers map[EventType][]EventHandler
	mu       sync.RWMutex
}

func NewDefaultEventBus() *DefaultEventBus {
	return &DefaultEventBus{
		handlers: make(map[EventType][]EventHandler),
	}
}

func (e *DefaultEventBus) Publish(event *MonitorEvent) {
	e.mu.RLock()
	handlers, exists := e.handlers[event.Type]
	e.mu.RUnlock()

	if !exists {
		return
	}

	for _, handler := range handlers {
		go handler(event)
	}
}

func (e *DefaultEventBus) Subscribe(eventType EventType, handler EventHandler) {
	e.mu.Lock()
	defer e.mu.Unlock()

	e.handlers[eventType] = append(e.handlers[eventType], handler)
}

func (e *DefaultEventBus) Unsubscribe(eventType EventType, handler EventHandler) {
	e.mu.Lock()
	defer e.mu.Unlock()

	handlers, exists := e.handlers[eventType]
	if !exists {
		return
	}

	for i, h := range handlers {
		if &h == &handler {
			e.handlers[eventType] = append(handlers[:i], handlers[i+1:]...)
			return
		}
	}
}

func NewMetricRecordedEvent(metricName string, value float64, dimensions map[string]string) *MonitorEvent {
	return &MonitorEvent{
		Type:      EventMetricRecorded,
		Timestamp: getCurrentTimestamp(),
		Data: map[string]interface{}{
			"metric":     metricName,
			"value":      value,
			"dimensions": dimensions,
		},
		Source: "monitoring",
	}
}

func NewAggregateCompleteEvent(metricName string, aggType string, result float64, err error) *MonitorEvent {
	return &MonitorEvent{
		Type:      EventAggregateComplete,
		Timestamp: getCurrentTimestamp(),
		Data: map[string]interface{}{
			"metric": metricName,
			"aggType": aggType,
			"result": result,
			"error":  err,
		},
		Source: "monitoring",
	}
}

func NewFlushCompleteEvent(err error) *MonitorEvent {
	return &MonitorEvent{
		Type:      EventFlushComplete,
		Timestamp: getCurrentTimestamp(),
		Data: map[string]interface{}{
			"error": err,
		},
		Source: "monitoring",
	}
}

func NewExportCompleteEvent(snapshots []*models.MetricsSnapshot, err error) *MonitorEvent {
	return &MonitorEvent{
		Type:      EventExportComplete,
		Timestamp: getCurrentTimestamp(),
		Data: map[string]interface{}{
			"snapshots": snapshots,
			"count":     len(snapshots),
			"error":     err,
		},
		Source: "monitoring",
	}
}
