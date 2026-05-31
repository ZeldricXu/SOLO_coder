package strategies

import (
	"context"
	"fmt"
	"time"

	"github.com/solocoder/task-scheduler/internal/contracts"
)

type NoFallbackStrategy struct{}

func NewNoFallbackStrategy() *NoFallbackStrategy {
	return &NoFallbackStrategy{}
}

func (s *NoFallbackStrategy) GetType() contracts.FallbackStrategyType {
	return contracts.FallbackStrategyNone
}

func (s *NoFallbackStrategy) HandleFallback(ctx context.Context, notification *contracts.Notification, err error) (*contracts.NotificationResult, error) {
	return &contracts.NotificationResult{
		NotificationID: notification.ID,
		Channel:        notification.Channel,
		Success:        false,
		Error:          err.Error(),
		SentAt:         time.Now(),
	}, nil
}

type DowngradeFallbackStrategy struct {
	downgradeChannel contracts.ChannelType
}

func NewDowngradeFallbackStrategy(downgradeTo contracts.ChannelType) *DowngradeFallbackStrategy {
	return &DowngradeFallbackStrategy{
		downgradeChannel: downgradeTo,
	}
}

func (s *DowngradeFallbackStrategy) GetType() contracts.FallbackStrategyType {
	return contracts.FallbackStrategyDowngrade
}

func (s *DowngradeFallbackStrategy) HandleFallback(ctx context.Context, notification *contracts.Notification, err error) (*contracts.NotificationResult, error) {
	return &contracts.NotificationResult{
		NotificationID: notification.ID,
		Channel:        s.downgradeChannel,
		Success:        false,
		Error:          fmt.Sprintf("downgraded from %s, original error: %v", notification.Channel, err),
		SentAt:         time.Now(),
	}, nil
}

type AlternativeFallbackStrategy struct {
	alternativeChannel contracts.ChannelType
	channelRegistry    contracts.ChannelRegistry
}

func NewAlternativeFallbackStrategy(alternative contracts.ChannelType, registry contracts.ChannelRegistry) *AlternativeFallbackStrategy {
	return &AlternativeFallbackStrategy{
		alternativeChannel: alternative,
		channelRegistry:    registry,
	}
}

func (s *AlternativeFallbackStrategy) GetType() contracts.FallbackStrategyType {
	return contracts.FallbackStrategyAlternative
}

func (s *AlternativeFallbackStrategy) HandleFallback(ctx context.Context, notification *contracts.Notification, err error) (*contracts.NotificationResult, error) {
	alternativeChannel, err := s.channelRegistry.Get(s.alternativeChannel)
	if err != nil {
		return &contracts.NotificationResult{
			NotificationID: notification.ID,
			Channel:        notification.Channel,
			Success:        false,
			Error:          fmt.Sprintf("alternative channel %s not available: %v", s.alternativeChannel, err),
			SentAt:         time.Now(),
		}, nil
	}

	originalChannel := notification.Channel
	notification.Channel = s.alternativeChannel

	if templateRepo, ok := ctx.Value("templateRepo").(contracts.TemplateRepository); ok {
		tpl, err := templateRepo.Get(notification.TemplateID)
		if err != nil {
			return &contracts.NotificationResult{
				NotificationID: notification.ID,
				Channel:        s.alternativeChannel,
				Success:        false,
				Error:          fmt.Sprintf("failed to get template: %v", err),
				SentAt:         time.Now(),
			}, nil
		}

		err = alternativeChannel.Send(ctx, notification, tpl)
		if err != nil {
			return &contracts.NotificationResult{
				NotificationID: notification.ID,
				Channel:        s.alternativeChannel,
				Success:        false,
				Error:          fmt.Sprintf("alternative channel also failed: %v", err),
				SentAt:         time.Now(),
			}, nil
		}

		return &contracts.NotificationResult{
			NotificationID: notification.ID,
			Channel:        s.alternativeChannel,
			Success:        true,
			SentAt:         time.Now(),
		}, nil
	}

	return &contracts.NotificationResult{
		NotificationID: notification.ID,
		Channel:        originalChannel,
		Success:        false,
		Error:          "template repository not available in context",
		SentAt:         time.Now(),
	}, nil
}

type QueueFallbackStrategy struct {
	queue chan *contracts.Notification
}

func NewQueueFallbackStrategy(queueSize int) *QueueFallbackStrategy {
	return &QueueFallbackStrategy{
		queue: make(chan *contracts.Notification, queueSize),
	}
}

func (s *QueueFallbackStrategy) GetType() contracts.FallbackStrategyType {
	return contracts.FallbackStrategyQueue
}

func (s *QueueFallbackStrategy) HandleFallback(ctx context.Context, notification *contracts.Notification, err error) (*contracts.NotificationResult, error) {
	select {
	case s.queue <- notification:
		return &contracts.NotificationResult{
			NotificationID: notification.ID,
			Channel:        notification.Channel,
			Success:        false,
			Error:          fmt.Sprintf("queued for retry: %v", err),
			SentAt:         time.Now(),
		}, nil
	default:
		return &contracts.NotificationResult{
			NotificationID: notification.ID,
			Channel:        notification.Channel,
			Success:        false,
			Error:          fmt.Sprintf("queue full, dropped: %v", err),
			SentAt:         time.Now(),
		}, nil
	}
}

func (s *QueueFallbackStrategy) GetQueue() <-chan *contracts.Notification {
	return s.queue
}

func (s *QueueFallbackStrategy) Close() {
	close(s.queue)
}
