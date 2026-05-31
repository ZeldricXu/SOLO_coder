package notification

import (
	"context"
	"time"

	"github.com/solocoder/task-scheduler/internal/contracts"
	"github.com/solocoder/task-scheduler/internal/events"
)

type EventHandler struct {
	sender contracts.NotificationSender
}

func NewEventHandler(sender contracts.NotificationSender) *EventHandler {
	return &EventHandler{sender: sender}
}

func (h *EventHandler) Subscribe(eventBus events.EventBus) {
	eventBus.Subscribe(events.EventTaskCompleted, func(ctx context.Context, event events.Event) error {
		return h.handleTaskCompleted(ctx, event)
	})

	eventBus.Subscribe(events.EventTaskFailed, func(ctx context.Context, event events.Event) error {
		return h.handleTaskFailed(ctx, event)
	})

	eventBus.Subscribe(events.EventBackupComplete, func(ctx context.Context, event events.Event) error {
		return h.handleBackupComplete(ctx, event)
	})
}

func (h *EventHandler) handleTaskCompleted(ctx context.Context, event events.Event) error {
	data := event.Payload

	notification := &contracts.Notification{
		ID:         "notif_" + event.ID,
		TemplateID: "task.completed",
		Channel:    contracts.ChannelEmail,
		Severity:   contracts.SeverityInfo,
		Recipients: []string{"admin@example.com"},
		Data: map[string]interface{}{
			"TaskName": data["task"],
			"TaskID":   event.EntityID,
			"Duration": data["duration"],
		},
		CreatedAt: time.Now(),
	}

	return h.sender.SendAsync(notification)
}

func (h *EventHandler) handleTaskFailed(ctx context.Context, event events.Event) error {
	data := event.Payload

	notification := &contracts.Notification{
		ID:         "notif_" + event.ID,
		TemplateID: "task.failed",
		Channel:    contracts.ChannelSlack,
		Severity:   contracts.SeverityError,
		Recipients: []string{"oncall@example.com", "+1234567890"},
		Data: map[string]interface{}{
			"TaskName":   data["task"],
			"TaskID":     event.EntityID,
			"Error":      data["error"],
			"RetryCount": 0,
		},
		CreatedAt: time.Now(),
	}

	return h.sender.SendAsync(notification)
}

func (h *EventHandler) handleBackupComplete(ctx context.Context, event events.Event) error {
	data := event.Payload

	notification := &contracts.Notification{
		ID:         "notif_" + event.ID,
		TemplateID: "backup.completed",
		Channel:    contracts.ChannelEmail,
		Severity:   contracts.SeverityInfo,
		Recipients: []string{"admin@example.com"},
		Data: map[string]interface{}{
			"BackupID": event.EntityID,
			"Size":     data["size"],
			"Duration": data["duration"],
		},
		CreatedAt: time.Now(),
	}

	return h.sender.SendAsync(notification)
}
