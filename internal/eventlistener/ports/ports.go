package ports

import (
	"context"
	"github.com/solocoder/session147/internal/eventlistener/domain"
)

type EventRepository interface {
	CreateSubscription(ctx context.Context, sub *domain.EventSubscription) error
	GetSubscription(ctx context.Context, id string) (*domain.EventSubscription, error)
	ListSubscriptions(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.EventSubscription, int64, error)
	UpdateSubscription(ctx context.Context, sub *domain.EventSubscription) error
	DeleteSubscription(ctx context.Context, id string) error
	GetActiveSubscriptions(ctx context.Context, chainID int64) ([]domain.EventSubscription, error)

	StoreEventLog(ctx context.Context, log *domain.EventLogEntry) error
	GetEventLog(ctx context.Context, id string) (*domain.EventLogEntry, error)
	ListEventLogs(ctx context.Context, subscriptionID string, page, pageSize int) ([]domain.EventLogEntry, int64, error)
	UpdateEventLogCallback(ctx context.Context, id string, status string, err string) error
}

type EventListenerService interface {
	CreateSubscription(ctx context.Context, req *domain.CreateSubscriptionRequest, createdBy string) (*domain.EventSubscription, error)
	GetSubscription(ctx context.Context, id string) (*domain.EventSubscription, error)
	ListSubscriptions(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.EventSubscription, int64, error)
	UpdateSubscription(ctx context.Context, sub *domain.EventSubscription) (*domain.EventSubscription, error)
	PauseSubscription(ctx context.Context, id string) error
	ResumeSubscription(ctx context.Context, id string) error
	DeleteSubscription(ctx context.Context, id string) error

	GetEventLog(ctx context.Context, id string) (*domain.EventLogEntry, error)
	ListEventLogs(ctx context.Context, subscriptionID string, page, pageSize int) ([]domain.EventLogEntry, int64, error)
	RetryCallback(ctx context.Context, logID string) error
}

type EventFetcher interface {
	GetLogs(ctx context.Context, fromBlock, toBlock uint64, addresses []string, topics []string) ([]interface{}, error)
	SubscribeNewHeads(ctx context.Context) (<-chan interface{}, error)
	SubscribeLogs(ctx context.Context, addresses []string, topics []string) (<-chan interface{}, error)
}

type CallbackExecutor interface {
	Execute(ctx context.Context, url string, data interface{}) error
}
