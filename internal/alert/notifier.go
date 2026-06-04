package alert

import (
	"fmt"
	"sync"
	"time"

	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
)

type Notifier interface {
	Send(alert *models.AlertEvent) error
	Name() string
}

type NotifierRegistry struct {
	notifiers map[string]Notifier
	mu        sync.RWMutex
}

var globalRegistry = &NotifierRegistry{
	notifiers: make(map[string]Notifier),
}

func GlobalNotifierRegistry() *NotifierRegistry {
	return globalRegistry
}

func (r *NotifierRegistry) Register(notifier Notifier) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.notifiers[notifier.Name()] = notifier
}

func (r *NotifierRegistry) Unregister(name string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.notifiers, name)
}

func (r *NotifierRegistry) Get(name string) (Notifier, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	n, ok := r.notifiers[name]
	return n, ok
}

func (r *NotifierRegistry) All() []Notifier {
	r.mu.RLock()
	defer r.mu.RUnlock()

	result := make([]Notifier, 0, len(r.notifiers))
	for _, n := range r.notifiers {
		result = append(result, n)
	}
	return result
}

func (r *NotifierRegistry) Names() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()

	result := make([]string, 0, len(r.notifiers))
	for name := range r.notifiers {
		result = append(result, name)
	}
	return result
}

func (r *NotifierRegistry) Clear() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.notifiers = make(map[string]Notifier)
}

func RegisterNotifier(notifier Notifier) {
	globalRegistry.Register(notifier)
}

type DingTalkNotifier struct {
	Webhook string
	Secret  string
}

func NewDingTalkNotifier(webhook, secret string) *DingTalkNotifier {
	return &DingTalkNotifier{Webhook: webhook, Secret: secret}
}

func (n *DingTalkNotifier) Name() string { return "dingtalk" }

func (n *DingTalkNotifier) Send(alert *models.AlertEvent) error {
	channel := &DingTalkChannel{Webhook: n.Webhook, Secret: n.Secret}
	return channel.Send(alert)
}

func init() {
	RegisterNotifier(&DingTalkNotifier{})
}

type FeishuNotifier struct {
	Webhook string
}

func NewFeishuNotifier(webhook string) *FeishuNotifier {
	return &FeishuNotifier{Webhook: webhook}
}

func (n *FeishuNotifier) Name() string { return "feishu" }

func (n *FeishuNotifier) Send(alert *models.AlertEvent) error {
	channel := &FeishuChannel{Webhook: n.Webhook}
	return channel.Send(alert)
}

func init() {
	RegisterNotifier(&FeishuNotifier{})
}

type PagerDutyNotifier struct {
	Token string
}

func NewPagerDutyNotifier(token string) *PagerDutyNotifier {
	return &PagerDutyNotifier{Token: token}
}

func (n *PagerDutyNotifier) Name() string { return "pagerduty" }

func (n *PagerDutyNotifier) Send(alert *models.AlertEvent) error {
	channel := &PagerDutyChannel{Token: n.Token}
	return channel.Send(alert)
}

func init() {
	RegisterNotifier(&PagerDutyNotifier{})
}

func CreateNotifierFromConfig(chCfg config.AlertChannelConfig) Notifier {
	switch chCfg.Type {
	case "dingtalk":
		return NewDingTalkNotifier(chCfg.Webhook, chCfg.Secret)
	case "feishu", "lark":
		return NewFeishuNotifier(chCfg.Webhook)
	case "pagerduty":
		return NewPagerDutyNotifier(chCfg.Token)
	default:
		return nil
	}
}

type NotifierAdapter struct {
	notifier Notifier
}

func NewNotifierAdapter(notifier Notifier) *NotifierAdapter {
	return &NotifierAdapter{notifier: notifier}
}

func (a *NotifierAdapter) Send(alert *models.AlertEvent) error {
	return a.notifier.Send(alert)
}

func (a *NotifierAdapter) SendWithRetry(alert *models.AlertEvent, maxRetries int) error {
	return retrySendNotifier(a.notifier, alert, maxRetries)
}

func retrySendNotifier(n Notifier, alert *models.AlertEvent, maxRetries int) error {
	var lastErr error
	backoff := 100 * time.Duration(1)

	for attempt := 0; attempt <= maxRetries; attempt++ {
		err := n.Send(alert)
		if err == nil {
			return nil
		}

		lastErr = err

		if !isRetryableError(err) {
			return err
		}

		if attempt < maxRetries {
			time.Sleep(backoff)
			backoff *= 2
		}
	}

	return fmt.Errorf("max retries (%d) exceeded: %w", maxRetries, lastErr)
}
