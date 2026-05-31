package strategies

import (
	"context"
	"fmt"
	"sync/atomic"

	"github.com/solocoder/task-scheduler/internal/contracts"
)

type SingleChannelStrategy struct {
	preferredChannel contracts.ChannelType
}

func NewSingleChannelStrategy(preferred contracts.ChannelType) *SingleChannelStrategy {
	return &SingleChannelStrategy{preferredChannel: preferred}
}

func (s *SingleChannelStrategy) GetType() contracts.RoutingStrategyType {
	return contracts.RoutingStrategySingle
}

func (s *SingleChannelStrategy) SelectChannel(ctx context.Context, notification *contracts.Notification, channels []contracts.NotificationChannel) ([]contracts.NotificationChannel, error) {
	targetType := notification.Channel
	if targetType == "" {
		targetType = s.preferredChannel
	}

	for _, ch := range channels {
		if ch.GetType() == targetType {
			return []contracts.NotificationChannel{ch}, nil
		}
	}
	return nil, fmt.Errorf("no channel found for type: %s", targetType)
}

type BroadcastStrategy struct{}

func NewBroadcastStrategy() *BroadcastStrategy {
	return &BroadcastStrategy{}
}

func (s *BroadcastStrategy) GetType() contracts.RoutingStrategyType {
	return contracts.RoutingStrategyBroadcast
}

func (s *BroadcastStrategy) SelectChannel(ctx context.Context, notification *contracts.Notification, channels []contracts.NotificationChannel) ([]contracts.NotificationChannel, error) {
	if len(channels) == 0 {
		return nil, fmt.Errorf("no channels available")
	}
	return channels, nil
}

type FailoverStrategy struct {
	priorityOrder []contracts.ChannelType
}

func NewFailoverStrategy(priority []contracts.ChannelType) *FailoverStrategy {
	return &FailoverStrategy{priorityOrder: priority}
}

func (s *FailoverStrategy) GetType() contracts.RoutingStrategyType {
	return contracts.RoutingStrategyFailover
}

func (s *FailoverStrategy) SelectChannel(ctx context.Context, notification *contracts.Notification, channels []contracts.NotificationChannel) ([]contracts.NotificationChannel, error) {
	channelMap := make(map[contracts.ChannelType]contracts.NotificationChannel)
	for _, ch := range channels {
		channelMap[ch.GetType()] = ch
	}

	for _, chType := range s.priorityOrder {
		if ch, ok := channelMap[chType]; ok && ch.HealthCheck(ctx) {
			return []contracts.NotificationChannel{ch}, nil
		}
	}

	for _, ch := range channels {
		if ch.HealthCheck(ctx) {
			return []contracts.NotificationChannel{ch}, nil
		}
	}

	if len(channels) > 0 {
		return []contracts.NotificationChannel{channels[0]}, nil
	}

	return nil, fmt.Errorf("no healthy channels available")
}

type PriorityStrategy struct {
	severityMap map[contracts.NotificationSeverity][]contracts.ChannelType
}

func NewPriorityStrategy() *PriorityStrategy {
	return &PriorityStrategy{
		severityMap: map[contracts.NotificationSeverity][]contracts.ChannelType{
			contracts.SeverityInfo:     {contracts.ChannelEmail},
			contracts.SeverityWarning:  {contracts.ChannelEmail, contracts.ChannelSlack},
			contracts.SeverityError:    {contracts.ChannelSlack, contracts.ChannelSMS},
			contracts.SeverityCritical: {contracts.ChannelSMS, contracts.ChannelSlack, contracts.ChannelDingTalk},
		},
	}
}

func (s *PriorityStrategy) GetType() contracts.RoutingStrategyType {
	return contracts.RoutingStrategyPriority
}

func (s *PriorityStrategy) SelectChannel(ctx context.Context, notification *contracts.Notification, channels []contracts.NotificationChannel) ([]contracts.NotificationChannel, error) {
	channelMap := make(map[contracts.ChannelType]contracts.NotificationChannel)
	for _, ch := range channels {
		channelMap[ch.GetType()] = ch
	}

	targetTypes, ok := s.severityMap[notification.Severity]
	if !ok {
		targetTypes = s.severityMap[contracts.SeverityInfo]
	}

	var selected []contracts.NotificationChannel
	for _, chType := range targetTypes {
		if ch, ok := channelMap[chType]; ok {
			selected = append(selected, ch)
		}
	}

	if len(selected) == 0 {
		if len(channels) > 0 {
			return []contracts.NotificationChannel{channels[0]}, nil
		}
		return nil, fmt.Errorf("no channels available for severity: %s", notification.Severity)
	}

	return selected, nil
}

type RoundRobinStrategy struct {
	counter uint64
}

func NewRoundRobinStrategy() *RoundRobinStrategy {
	return &RoundRobinStrategy{}
}

func (s *RoundRobinStrategy) GetType() contracts.RoutingStrategyType {
	return contracts.RoutingStrategyRoundRobin
}

func (s *RoundRobinStrategy) SelectChannel(ctx context.Context, notification *contracts.Notification, channels []contracts.NotificationChannel) ([]contracts.NotificationChannel, error) {
	if len(channels) == 0 {
		return nil, fmt.Errorf("no channels available")
	}

	count := atomic.AddUint64(&s.counter, 1)
	idx := int(count % uint64(len(channels)))
	return []contracts.NotificationChannel{channels[idx]}, nil
}
