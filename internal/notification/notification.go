package notification

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/datatrace/datatrace/internal/common"
	"github.com/datatrace/datatrace/internal/models"
)

type Status string

const (
	StatusPending   Status = "pending"
	StatusSent      Status = "sent"
	StatusDelivered Status = "delivered"
	StatusFailed    Status = "failed"
	StatusRetrying  Status = "retrying"

	DefaultMaxRetries    = 3
	DefaultRetryInterval = 5 * time.Second
	DefaultSendTimeout   = 30 * time.Second
	DefaultRetryTick     = 1 * time.Second
)

type Notification struct {
	ID            string                 `json:"id"`
	Type          string                 `json:"type"`
	Recipient     string                 `json:"recipient"`
	Payload       map[string]interface{} `json:"payload"`
	Status        Status                 `json:"status"`
	RetryCount    int                    `json:"retry_count"`
	MaxRetries    int                    `json:"max_retries"`
	RetryInterval time.Duration          `json:"retry_interval"`
	CreatedAt     time.Time              `json:"created_at"`
	SentAt        *time.Time             `json:"sent_at,omitempty"`
	DeliveredAt   *time.Time             `json:"delivered_at,omitempty"`
	Error         string                 `json:"error,omitempty"`
	NextRetryAt   *time.Time             `json:"next_retry_at,omitempty"`
}

type Sender interface {
	Send(ctx context.Context, notification *Notification) error
}

type ConsoleSender struct{}

func NewConsoleSender() *ConsoleSender {
	return &ConsoleSender{}
}

func (s *ConsoleSender) Send(ctx context.Context, notification *Notification) error {
	return nil
}

type Service struct {
	common.BaseService
	senders  map[string]Sender
	queue    chan *Notification
	inFlight map[string]*Notification
	mu       sync.RWMutex
}

type NotificationService = Service

func NewService(bufferSize int) *Service {
	ns := &Service{
		BaseService: common.NewBaseService(),
		senders:     make(map[string]Sender),
		queue:       make(chan *Notification, bufferSize),
		inFlight:    make(map[string]*Notification),
	}
	return ns
}

func NewNotificationService(bufferSize int) *NotificationService {
	return NewService(bufferSize)
}

func (s *Service) RegisterSender(notificationType string, sender Sender) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.senders[notificationType] = sender
}

func (s *Service) Start() {
	_ = s.BaseService.Start()

	s.AddWorker(2)
	go s.processQueue()
	go s.processRetries()
}

func (s *Service) Stop() {
	_ = s.BaseService.Stop()
}

func (s *Service) Send(ctx context.Context, notificationType, recipient string, payload map[string]interface{}) (*Notification, error) {
	if !s.IsRunning() {
		return nil, common.WrapError(common.CodeUnavailable, "notification service is not running", nil)
	}

	s.mu.RLock()
	_, senderExists := s.senders[notificationType]
	s.mu.RUnlock()

	if !senderExists {
		return nil, common.WrapError(common.CodeInvalidInput,
			fmt.Sprintf("no sender registered for type: %s", notificationType), nil)
	}

	notif := &Notification{
		ID:            common.NewID(),
		Type:          notificationType,
		Recipient:     recipient,
		Payload:       payload,
		Status:        StatusPending,
		RetryCount:    0,
		MaxRetries:    DefaultMaxRetries,
		RetryInterval: DefaultRetryInterval,
		CreatedAt:     time.Now(),
	}

	select {
	case s.queue <- notif:
		return notif, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
		return nil, common.WrapError(common.CodeQueueFull, "notification queue is full", nil)
	}
}

func (s *Service) processQueue() {
	defer s.WorkerDone()

	for {
		select {
		case <-s.StopChan():
			close(s.queue)
			return
		case notif := <-s.queue:
			if notif != nil {
				s.processNotification(notif)
			}
		}
	}
}

func (s *Service) processNotification(notif *Notification) {
	sender, err := s.prepareForSend(notif)
	if err != nil {
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), DefaultSendTimeout)
	defer cancel()

	if err := sender.Send(ctx, notif); err != nil {
		s.handleSendFailure(notif, err)
		return
	}

	s.markDelivered(notif)
}

func (s *Service) prepareForSend(notif *Notification) (Sender, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	sender, ok := s.senders[notif.Type]
	if !ok {
		s.markFailedLocked(notif, fmt.Sprintf("no sender available for type: %s", notif.Type))
		return nil, fmt.Errorf("no sender available")
	}

	notif.Status = StatusSent
	notif.SentAt = common.NowPtr()
	s.inFlight[notif.ID] = notif

	return sender, nil
}

func (s *Service) handleSendFailure(notif *Notification, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	notif.RetryCount++
	notif.Error = err.Error()

	if notif.RetryCount >= notif.MaxRetries {
		notif.Status = StatusFailed
		delete(s.inFlight, notif.ID)
		return
	}

	notif.Status = StatusRetrying
	nextRetry := time.Now().Add(notif.RetryInterval * time.Duration(notif.RetryCount))
	notif.NextRetryAt = &nextRetry
}

func (s *Service) processRetries() {
	defer s.WorkerDone()

	ticker := time.NewTicker(DefaultRetryTick)
	defer ticker.Stop()

	for {
		select {
		case <-s.StopChan():
			return
		case <-ticker.C:
			s.retryDueNotifications()
		}
	}
}

func (s *Service) retryDueNotifications() {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now()
	for _, notif := range s.inFlight {
		if notif.Status == StatusRetrying && notif.NextRetryAt != nil && now.After(*notif.NextRetryAt) {
			notif.Status = StatusPending
			notif.NextRetryAt = nil
			go s.processNotification(notif)
		}
	}
}

func (s *Service) markDelivered(notif *Notification) {
	s.mu.Lock()
	defer s.mu.Unlock()

	notif.Status = StatusDelivered
	notif.DeliveredAt = common.NowPtr()
	delete(s.inFlight, notif.ID)
}

func (s *Service) markFailedLocked(notif *Notification, errMsg string) {
	notif.Status = StatusFailed
	notif.Error = errMsg
	delete(s.inFlight, notif.ID)
}

func (s *Service) GetStatus(notificationID string) (*Notification, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	notif, ok := s.inFlight[notificationID]
	if !ok {
		return nil, common.WrapError(common.CodeNotFound,
			fmt.Sprintf("notification not found: %s", notificationID), nil)
	}
	return notif, nil
}

func (s *Service) QueueStatus() common.QueueStatus {
	s.mu.RLock()
	defer s.mu.RUnlock()

	return common.QueueStatus{
		Queued:   len(s.queue),
		InFlight: len(s.inFlight),
		Capacity: cap(s.queue),
	}
}

func (s *Service) ToEntity() *models.Entity {
	return common.NewEntity("notification_service")
}

func (s *Service) GetMetrics() map[string]interface{} {
	qs := s.QueueStatus()
	return map[string]interface{}{
		"queued":     qs.Queued,
		"in_flight":  qs.InFlight,
		"capacity":   qs.Capacity,
		"sender_count": len(s.senders),
		"uptime":     s.Uptime().String(),
	}
}
