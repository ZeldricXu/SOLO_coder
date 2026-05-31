package notification

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/dataplatform/engine/internal/common/errors"
	"github.com/dataplatform/engine/internal/domain"
	"github.com/google/uuid"
)

type suppressionState struct {
	count     int
	lastSent  time.Time
}

type NotifierImpl struct {
	channels      map[string]NotificationChannel
	suppressions  map[string]*suppressionState
	mu            sync.RWMutex
	logger        domain.Logger
}

func NewNotifierImpl(logger domain.Logger) *NotifierImpl {
	return &NotifierImpl{
		channels:     make(map[string]NotificationChannel),
		suppressions: make(map[string]*suppressionState),
		logger:       logger,
	}
}

func (n *NotifierImpl) AddChannel(channel NotificationChannel) {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.channels[channel.Name()] = channel
	n.logger.Info("Notification channel added", domain.String("channel", channel.Name()))
}

func (n *NotifierImpl) RemoveChannel(name string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	delete(n.channels, name)
	n.logger.Info("Notification channel removed", domain.String("channel", name))
}

func (n *NotifierImpl) ListChannels() []string {
	n.mu.RLock()
	defer n.mu.RUnlock()

	names := make([]string, 0, len(n.channels))
	for name := range n.channels {
		names = append(names, name)
	}
	return names
}

func (n *NotifierImpl) Send(ctx context.Context, notification *Notification) error {
	if notification == nil {
		return errors.New(errors.ErrCodeValidation, "notification cannot be nil")
	}

	notification.ID = uuid.New().String()
	notification.CreatedAt = time.Now()

	if notification.Suppress != nil && notification.Suppress.Key != "" {
		if n.shouldSuppress(notification) {
			n.logger.Info("Notification suppressed",
				domain.String("key", notification.Suppress.Key),
			)
			return nil
		}
	}

	n.mu.RLock()
	channels := make([]NotificationChannel, 0, len(n.channels))
	for _, ch := range n.channels {
		channels = append(channels, ch)
	}
	n.mu.RUnlock()

	if len(channels) == 0 {
		return errors.New(errors.ErrCodeUnavailable, "no notification channels configured")
	}

	targetChannels := channels
	if len(notification.Channels) > 0 {
		targetChannels = make([]NotificationChannel, 0)
		n.mu.RLock()
		for _, name := range notification.Channels {
			if ch, exists := n.channels[name]; exists {
				targetChannels = append(targetChannels, ch)
			}
		}
		n.mu.RUnlock()
	}

	var lastErr error
	for _, ch := range targetChannels {
		if err := ch.Send(ctx, notification); err != nil {
			n.logger.Error("Failed to send notification",
				domain.String("channel", ch.Name()),
				domain.Error(err),
			)
			lastErr = err
		}
	}

	return lastErr
}

func (n *NotifierImpl) shouldSuppress(notification *Notification) bool {
	n.mu.Lock()
	defer n.mu.Unlock()

	key := notification.Suppress.Key
	state, exists := n.suppressions[key]
	now := time.Now()

	if !exists {
		n.suppressions[key] = &suppressionState{
			count:    1,
			lastSent: now,
		}
		return false
	}

	if now.Sub(state.lastSent) > notification.Suppress.Duration {
		state.count = 1
		state.lastSent = now
		return false
	}

	state.count++

	if state.count > notification.Suppress.Threshold {
		return true
	}

	return false
}

type ConsoleChannel struct{}

func NewConsoleChannel() *ConsoleChannel {
	return &ConsoleChannel{}
}

func (c *ConsoleChannel) Name() string {
	return "console"
}

func (c *ConsoleChannel) Send(ctx context.Context, notification *Notification) error {
	fmt.Printf("[NOTIFICATION] %s: %s\n", notification.Title, notification.Message)
	return nil
}

type WebhookChannel struct {
	name    string
	url     string
	headers map[string]string
}

func NewWebhookChannel(name, url string, headers map[string]string) *WebhookChannel {
	return &WebhookChannel{
		name:    name,
		url:     url,
		headers: headers,
	}
}

func (c *WebhookChannel) Name() string {
	return c.name
}

func (c *WebhookChannel) Send(ctx context.Context, notification *Notification) error {
	return nil
}

type EmailChannel struct {
	from    string
	to      []string
	smtpHost string
	smtpPort int
}

func NewEmailChannel(from string, to []string, smtpHost string, smtpPort int) *EmailChannel {
	return &EmailChannel{
		from:     from,
		to:       to,
		smtpHost: smtpHost,
		smtpPort: smtpPort,
	}
}

func (c *EmailChannel) Name() string {
	return "email"
}

func (c *EmailChannel) Send(ctx context.Context, notification *Notification) error {
	return nil
}
