package notification

import (
	"context"
	"fmt"
	"sync"
	"time"

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/internal/contracts"
	"github.com/solocoder/task-scheduler/internal/events"
	"github.com/solocoder/task-scheduler/internal/logging"
)

type Notifier struct {
	channelRegistry     contracts.ChannelRegistry
	templateRepository  contracts.TemplateRepository
	strategyManager     *StrategyManager
	notificationQueue   chan *contracts.Notification
	stopCh              chan struct{}
	wg                  sync.WaitGroup
	workerCount         int
}

func NewNotifier(
	channelRegistry contracts.ChannelRegistry,
	templateRepository contracts.TemplateRepository,
	strategyManager *StrategyManager,
	eventBus events.EventBus,
	workerCount int,
	queueSize int,
) *Notifier {
	n := &Notifier{
		channelRegistry:     channelRegistry,
		templateRepository:  templateRepository,
		strategyManager:     strategyManager,
		notificationQueue:   make(chan *contracts.Notification, queueSize),
		stopCh:              make(chan struct{}),
		workerCount:         workerCount,
	}

	n.registerDefaultTemplates()

	eventHandler := NewEventHandler(n)
	eventHandler.Subscribe(eventBus)

	return n
}

func NewNotifierWithDefaults(
	eventBus events.EventBus,
	workerCount int,
	queueSize int,
) *Notifier {
	return NewNotifier(
		NewChannelRegistry(),
		NewTemplateManager(),
		NewStrategyManager(),
		eventBus,
		workerCount,
		queueSize,
	)
}

func (n *Notifier) registerDefaultTemplates() {
	defaultTemplates := []*contracts.NotificationTemplate{
		{
			ID:       "task.completed",
			Name:     "Task Completed",
			Channels: []contracts.ChannelType{contracts.ChannelEmail, contracts.ChannelSlack},
			Subject:  "Task Completed: {{.TaskName}}",
			Content:  `<p>Task <strong>{{.TaskName}}</strong> has been completed successfully.</p><p>Task ID: {{.TaskID}}</p><p>Duration: {{.Duration}}</p>`,
		},
		{
			ID:       "task.failed",
			Name:     "Task Failed",
			Channels: []contracts.ChannelType{contracts.ChannelEmail, contracts.ChannelSMS, contracts.ChannelSlack},
			Subject:  "ALERT: Task Failed - {{.TaskName}}",
			Content:  `<p>Task <strong>{{.TaskName}}</strong> has failed.</p><p>Task ID: {{.TaskID}}</p><p>Error: {{.Error}}</p><p>Retry Count: {{.RetryCount}}</p>`,
		},
		{
			ID:       "backup.completed",
			Name:     "Backup Completed",
			Channels: []contracts.ChannelType{contracts.ChannelEmail},
			Subject:  "Backup Completed Successfully",
			Content:  `<p>Backup has been completed successfully.</p><p>Backup ID: {{.BackupID}}</p><p>Size: {{.Size}}</p><p>Duration: {{.Duration}}</p>`,
		},
		{
			ID:       "system.alert",
			Name:     "System Alert",
			Channels: []contracts.ChannelType{contracts.ChannelSMS, contracts.ChannelSlack, contracts.ChannelDingTalk},
			Subject:  "SYSTEM ALERT: {{.Title}}",
			Content:  `<p><strong>Alert:</strong> {{.Title}}</p><p>Severity: {{.Severity}}</p><p>Message: {{.Message}}</p><p>Timestamp: {{.Timestamp}}</p>`,
		},
	}

	for _, tpl := range defaultTemplates {
		_ = n.templateRepository.Register(tpl)
	}
}

func (n *Notifier) RegisterChannel(channel contracts.NotificationChannel) {
	n.channelRegistry.Register(channel)
}

func (n *Notifier) RegisterTemplate(template *contracts.NotificationTemplate) error {
	return n.templateRepository.Register(template)
}

func (n *Notifier) Start() {
	logging.Info(context.Background(), "Starting notification service", zap.Int("worker_count", n.workerCount))

	for i := 0; i < n.workerCount; i++ {
		n.wg.Add(1)
		go n.worker(i)
	}
}

func (n *Notifier) Stop() {
	logging.Info(context.Background(), "Stopping notification service")
	close(n.stopCh)
	n.wg.Wait()
	close(n.notificationQueue)
	logging.Info(context.Background(), "Notification service stopped")
}

func (n *Notifier) worker(id int) {
	defer n.wg.Done()
	logger := logging.GetDefaultLogger().With(zap.Int("notif_worker_id", id))

	for {
		select {
		case notification := <-n.notificationQueue:
			if notification == nil {
				continue
			}
			ctx := context.WithValue(context.Background(), "traceID", "notif_"+notification.ID)
			result := n.Send(ctx, notification)
			logger.Info(ctx, "Notification processed",
				zap.String("notification_id", notification.ID),
				zap.Bool("success", result.Success))
		case <-n.stopCh:
			return
		}
	}
}

func (n *Notifier) SendAsync(notification *contracts.Notification) error {
	select {
	case n.notificationQueue <- notification:
		return nil
	default:
		return fmt.Errorf("notification queue is full")
	}
}

func (n *Notifier) Send(ctx context.Context, notification *contracts.Notification) *contracts.NotificationResult {
	return n.SendWithStrategy(
		ctx,
		notification,
		"",
		"",
		"",
	)
}

func (n *Notifier) SendWithStrategy(
	ctx context.Context,
	notification *contracts.Notification,
	routingType contracts.RoutingStrategyType,
	retryType contracts.RetryStrategyType,
	fallbackType contracts.FallbackStrategyType,
) *contracts.NotificationResult {
	result := &contracts.NotificationResult{
		NotificationID: notification.ID,
		SentAt:         time.Now(),
	}

	routingStrategy := n.strategyManager.GetDefaultRoutingStrategy()
	if routingType != "" {
		if strategy, err := n.strategyManager.GetRoutingStrategy(routingType); err == nil {
			routingStrategy = strategy
		}
	}

	retryStrategy := n.strategyManager.GetDefaultRetryStrategy()
	if retryType != "" {
		if strategy, err := n.strategyManager.GetRetryStrategy(retryType); err == nil {
			retryStrategy = strategy
		}
	}

	fallbackStrategy := n.strategyManager.GetDefaultFallbackStrategy()
	if fallbackType != "" {
		if strategy, err := n.strategyManager.GetFallbackStrategy(fallbackType); err == nil {
			fallbackStrategy = strategy
		}
	}

	template, err := n.templateRepository.Get(notification.TemplateID)
	if err != nil {
		result.Success = false
		result.Error = err.Error()
		return result
	}

	channels := n.getAllChannels()
	selectedChannels, err := routingStrategy.SelectChannel(ctx, notification, channels)
	if err != nil {
		result.Success = false
		result.Error = err.Error()
		return result
	}

	if len(selectedChannels) == 0 {
		result.Success = false
		result.Error = "no channels selected"
		return result
	}

	result.Channel = selectedChannels[0].GetType()

	ctxWithTemplate := context.WithValue(ctx, "templateRepo", n.templateRepository)

	var lastErr error
	for attempt := 0; attempt <= retryStrategy.GetMaxRetries(); attempt++ {
		for _, channel := range selectedChannels {
			err := channel.Send(ctxWithTemplate, notification, template)
			if err == nil {
				result.Success = true
				result.Channel = channel.GetType()
				return result
			}
			lastErr = err
			result.Channel = channel.GetType()
		}

		if attempt < retryStrategy.GetMaxRetries() && retryStrategy.ShouldRetry(attempt, lastErr) {
			delay := retryStrategy.GetDelay(attempt)
			time.Sleep(delay)
		}
	}

	fallbackResult, _ := fallbackStrategy.HandleFallback(ctxWithTemplate, notification, lastErr)
	if fallbackResult != nil {
		return fallbackResult
	}

	result.Success = false
	result.Error = lastErr.Error()
	return result
}

func (n *Notifier) getAllChannels() []contracts.NotificationChannel {
	channelTypes := n.channelRegistry.List()
	channels := make([]contracts.NotificationChannel, 0, len(channelTypes))
	for _, ct := range channelTypes {
		if ch, err := n.channelRegistry.Get(ct); err == nil {
			channels = append(channels, ch)
		}
	}
	return channels
}

func (n *Notifier) Broadcast(ctx context.Context, templateID string, severity contracts.NotificationSeverity, data map[string]interface{}, recipients []string) []*contracts.NotificationResult {
	var results []*contracts.NotificationResult

	tpl, err := n.templateRepository.Get(templateID)
	if err != nil {
		return []*contracts.NotificationResult{{
			NotificationID: "broadcast_" + time.Now().Format("20060102150405"),
			Success:        false,
			Error:          err.Error(),
			SentAt:         time.Now(),
		}}
	}

	for _, channelType := range tpl.Channels {
		notification := &contracts.Notification{
			ID:         "notif_" + time.Now().Format("20060102150405"),
			TemplateID: templateID,
			Channel:    channelType,
			Severity:   severity,
			Recipients: recipients,
			Data:       data,
			CreatedAt:  time.Now(),
		}
		result := n.Send(ctx, notification)
		results = append(results, result)
	}

	return results
}

func (n *Notifier) GetTemplates() []*contracts.NotificationTemplate {
	return n.templateRepository.List()
}

func (n *Notifier) GetRegisteredChannels() []contracts.ChannelType {
	return n.channelRegistry.List()
}
