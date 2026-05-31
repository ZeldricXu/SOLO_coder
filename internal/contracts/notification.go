package contracts

import (
	"context"
	"time"
)

type ChannelType string

const (
	ChannelEmail    ChannelType = "email"
	ChannelSMS      ChannelType = "sms"
	ChannelWebhook  ChannelType = "webhook"
	ChannelSlack    ChannelType = "slack"
	ChannelDingTalk ChannelType = "dingtalk"
)

type NotificationSeverity string

const (
	SeverityInfo     NotificationSeverity = "info"
	SeverityWarning  NotificationSeverity = "warning"
	SeverityError    NotificationSeverity = "error"
	SeverityCritical NotificationSeverity = "critical"
)

type RoutingStrategyType string

const (
	RoutingStrategySingle     RoutingStrategyType = "single"
	RoutingStrategyFailover   RoutingStrategyType = "failover"
	RoutingStrategyBroadcast  RoutingStrategyType = "broadcast"
	RoutingStrategyPriority   RoutingStrategyType = "priority"
	RoutingStrategyRoundRobin RoutingStrategyType = "round_robin"
)

type RetryStrategyType string

const (
	RetryStrategyFixed    RetryStrategyType = "fixed"
	RetryStrategyExponential RetryStrategyType = "exponential"
	RetryStrategyLinear   RetryStrategyType = "linear"
)

type FallbackStrategyType string

const (
	FallbackStrategyNone       FallbackStrategyType = "none"
	FallbackStrategyDowngrade  FallbackStrategyType = "downgrade"
	FallbackStrategyAlternative FallbackStrategyType = "alternative"
	FallbackStrategyQueue      FallbackStrategyType = "queue"
)

type Notification struct {
	ID         string                 `json:"id"`
	TemplateID string                 `json:"template_id"`
	Channel    ChannelType            `json:"channel"`
	Severity   NotificationSeverity   `json:"severity"`
	Recipients []string               `json:"recipients"`
	Data       map[string]interface{} `json:"data"`
	Subject    string                 `json:"subject,omitempty"`
	CreatedAt  time.Time              `json:"created_at"`
}

type NotificationResult struct {
	NotificationID string        `json:"notification_id"`
	Channel        ChannelType   `json:"channel"`
	Success        bool          `json:"success"`
	Error          string        `json:"error,omitempty"`
	SentAt         time.Time     `json:"sent_at"`
}

type NotificationTemplate struct {
	ID        string            `json:"id"`
	Name      string            `json:"name"`
	Channels  []ChannelType     `json:"channels"`
	Subject   string            `json:"subject,omitempty"`
	Content   string            `json:"content"`
	Variables map[string]string `json:"variables,omitempty"`
}

type NotificationChannel interface {
	Send(ctx context.Context, notification *Notification, template *NotificationTemplate) error
	GetType() ChannelType
	HealthCheck(ctx context.Context) bool
}

type RoutingStrategy interface {
	SelectChannel(ctx context.Context, notification *Notification, channels []NotificationChannel) ([]NotificationChannel, error)
	GetType() RoutingStrategyType
}

type RetryStrategy interface {
	ShouldRetry(attempt int, err error) bool
	GetDelay(attempt int) time.Duration
	GetType() RetryStrategyType
	GetMaxRetries() int
}

type FallbackStrategy interface {
	HandleFallback(ctx context.Context, notification *Notification, err error) (*NotificationResult, error)
	GetType() FallbackStrategyType
}

type RoutingStrategyRegistry interface {
	Register(strategy RoutingStrategy)
	Get(strategyType RoutingStrategyType) (RoutingStrategy, error)
	List() []RoutingStrategyType
	SetDefault(strategyType RoutingStrategyType) error
	GetDefault() RoutingStrategy
}

type RetryStrategyRegistry interface {
	Register(strategy RetryStrategy)
	Get(strategyType RetryStrategyType) (RetryStrategy, error)
	List() []RetryStrategyType
	SetDefault(strategyType RetryStrategyType) error
	GetDefault() RetryStrategy
}

type FallbackStrategyRegistry interface {
	Register(strategy FallbackStrategy)
	Get(strategyType FallbackStrategyType) (FallbackStrategy, error)
	List() []FallbackStrategyType
	SetDefault(strategyType FallbackStrategyType) error
	GetDefault() FallbackStrategy
}

type TemplateRepository interface {
	Register(template *NotificationTemplate) error
	Get(templateID string) (*NotificationTemplate, error)
	List() []*NotificationTemplate
}

type NotificationSender interface {
	Send(ctx context.Context, notification *Notification) *NotificationResult
	SendAsync(notification *Notification) error
	Broadcast(ctx context.Context, templateID string, severity NotificationSeverity, data map[string]interface{}, recipients []string) []*NotificationResult
	SendWithStrategy(ctx context.Context, notification *Notification, routingType RoutingStrategyType, retryType RetryStrategyType, fallbackType FallbackStrategyType) *NotificationResult
}

type ChannelRegistry interface {
	Register(channel NotificationChannel)
	Get(channelType ChannelType) (NotificationChannel, error)
	List() []ChannelType
}

type StrategyManager interface {
	RoutingStrategyRegistry
	RetryStrategyRegistry
	FallbackStrategyRegistry
}

type NotificationService interface {
	NotificationSender
	ChannelRegistry
	TemplateRepository
	StrategyManager
	Start()
	Stop()
	GetTemplates() []*NotificationTemplate
	GetRegisteredChannels() []ChannelType
}
