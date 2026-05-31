package executor

import (
	"context"

	"github.com/solocoder/task-scheduler/v2/internal/core/ports"
)

type EventEmitter struct {
	eventBus ports.EventPublisher
}

func NewEventEmitter(eventBus ports.EventPublisher) *EventEmitter {
	return &EventEmitter{
		eventBus: eventBus,
	}
}

func (e *EventEmitter) EmitTaskStarted(
	ctx context.Context,
	entityID string,
	runID string,
	traceID string,
) error {
	return e.eventBus.Publish(ctx, EventTaskStarted, entityID, map[string]interface{}{
		"run_id": runID,
	}, ports.NewTraceContext(traceID))
}

func (e *EventEmitter) EmitTaskCompleted(
	ctx context.Context,
	entityID string,
	runID string,
	result map[string]interface{},
	duration float64,
	traceID string,
) error {
	return e.eventBus.Publish(ctx, EventTaskCompleted, entityID, map[string]interface{}{
		"result":   result,
		"run_id":   runID,
		"duration": duration,
	}, ports.NewTraceContext(traceID))
}

func (e *EventEmitter) EmitTaskFailed(
	ctx context.Context,
	entityID string,
	runID string,
	errorMsg string,
	traceID string,
) error {
	return e.eventBus.Publish(ctx, EventTaskFailed, entityID, map[string]interface{}{
		"run_id": runID,
		"error":  errorMsg,
	}, ports.NewTraceContext(traceID))
}

func (e *EventEmitter) EmitProgressUpdate(
	ctx context.Context,
	runID string,
	progress float64,
) error {
	return e.eventBus.Publish(ctx, EventProgressUpdate, "", map[string]interface{}{
		"run_id":   runID,
		"progress": progress,
	}, nil)
}
