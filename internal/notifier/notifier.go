package notifier

import (
	"context"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"math"
	"sync"
	"taskmanager/internal/logger"
	"taskmanager/pkg/models"
	"time"
)

type NotificationSender interface {
	Send(ctx context.Context, recipient, subject, content string) error
}

type EmailSender struct{}

func (s *EmailSender) Send(ctx context.Context, recipient, subject, content string) error {
	logger.Info("sending email", zap.String("to", recipient), zap.String("subject", subject))
	time.Sleep(10 * time.Millisecond)
	return nil
}

type SMSSender struct{}

func (s *SMSSender) Send(ctx context.Context, recipient, subject, content string) error {
	logger.Info("sending sms", zap.String("to", recipient))
	time.Sleep(10 * time.Millisecond)
	return nil
}

type WebhookSender struct {
	URL string
}

func (s *WebhookSender) Send(ctx context.Context, recipient, subject, content string) error {
	logger.Info("sending webhook", zap.String("url", s.URL), zap.String("subject", subject))
	time.Sleep(10 * time.Millisecond)
	return nil
}

type Notifier struct {
	db          *gorm.DB
	senders     map[string]NotificationSender
	queue       chan *models.Notification
	wg          sync.WaitGroup
	stopped     chan struct{}
}

func NewNotifier(db *gorm.DB) *Notifier {
	return &Notifier{
		db:      db,
		senders: make(map[string]NotificationSender),
		queue:   make(chan *models.Notification, 10000),
		stopped: make(chan struct{}),
	}
}

func (n *Notifier) RegisterSender(channel string, sender NotificationSender) {
	n.senders[channel] = sender
}

func (n *Notifier) Start() {
	n.wg.Add(1)
	go n.processQueue()
	n.wg.Add(1)
	go n.retryFailed()
	logger.Info("notifier started")
}

func (n *Notifier) Stop() {
	close(n.stopped)
	n.wg.Wait()
	close(n.queue)
	logger.Info("notifier stopped")
}

func (n *Notifier) SendNotification(ctx context.Context, channel, recipient, subject, content string, labels map[string]string) error {
	notification := &models.Notification{
		ID:         uuid.New().String(),
		Channel:    channel,
		Recipient:  recipient,
		Subject:    subject,
		Content:    content,
		Status:     "pending",
		RetryCount: 0,
		MaxRetries: 3,
		Labels:     labels,
		CreatedAt:  time.Now(),
		UpdatedAt:  time.Now(),
	}
	if err := n.db.Create(notification).Error; err != nil {
		return err
	}
	select {
	case n.queue <- notification:
	default:
		logger.Warn("notification queue full, will retry later")
	}
	return nil
}

func (n *Notifier) processQueue() {
	defer n.wg.Done()
	for {
		select {
		case notification := <-n.queue:
			n.send(notification)
		case <-n.stopped:
			return
		}
	}
}

func (n *Notifier) send(notification *models.Notification) {
	sender, ok := n.senders[notification.Channel]
	if !ok {
		notification.Status = "failed"
		errMsg := "no sender registered for channel: " + notification.Channel
		notification.ErrorDetail = &errMsg
		n.updateNotification(notification)
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	err := sender.Send(ctx, notification.Recipient, notification.Subject, notification.Content)
	if err != nil {
		notification.RetryCount++
		if notification.RetryCount >= notification.MaxRetries {
			notification.Status = "failed"
			errMsg := err.Error()
			notification.ErrorDetail = &errMsg
			logger.Error("notification failed permanently",
				zap.String("notification_id", notification.ID),
				zap.String("channel", notification.Channel),
				zap.Error(err),
			)
		} else {
			notification.Status = "retrying"
			delay := n.calculateBackoff(notification.RetryCount)
			go n.scheduleRetry(notification, delay)
			logger.Warn("notification send failed, retrying",
				zap.String("notification_id", notification.ID),
				zap.Int("retry_count", notification.RetryCount),
				zap.Duration("delay", delay),
				zap.Error(err),
			)
		}
	} else {
		notification.Status = "sent"
		now := time.Now()
		notification.SentAt = &now
		logger.Info("notification sent",
			zap.String("notification_id", notification.ID),
			zap.String("channel", notification.Channel),
			zap.String("recipient", notification.Recipient),
		)
	}
	n.updateNotification(notification)
}

func (n *Notifier) scheduleRetry(notification *models.Notification, delay time.Duration) {
	time.Sleep(delay)
	select {
	case n.queue <- notification:
	case <-n.stopped:
	}
}

func (n *Notifier) calculateBackoff(retryCount int) time.Duration {
	baseDelay := 1 * time.Second
	maxDelay := 30 * time.Second
	delay := baseDelay * time.Duration(math.Pow(2, float64(retryCount-1)))
	if delay > maxDelay {
		delay = maxDelay
	}
	return delay
}

func (n *Notifier) retryFailed() {
	defer n.wg.Done()
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			n.retryStuckNotifications()
		case <-n.stopped:
			return
		}
	}
}

func (n *Notifier) retryStuckNotifications() {
	var notifications []models.Notification
	if err := n.db.Where("status = ? AND retry_count < max_retries", "retrying").
		Or("status = ? AND retry_count < max_retries", "pending").
		Find(&notifications).Error; err != nil {
		logger.Error("find stuck notifications failed", zap.Error(err))
		return
	}
	for i := range notifications {
		notification := &notifications[i]
		select {
		case n.queue <- notification:
		default:
			logger.Warn("queue full, skipping retry for notification", zap.String("id", notification.ID))
		}
	}
}

func (n *Notifier) updateNotification(notification *models.Notification) {
	notification.UpdatedAt = time.Now()
	if err := n.db.Save(notification).Error; err != nil {
		logger.Error("update notification failed", zap.Error(err))
	}
}

func (n *Notifier) GetNotification(ctx context.Context, id string) (*models.Notification, error) {
	var notification models.Notification
	if err := n.db.First(&notification, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &notification, nil
}

func (n *Notifier) ListNotifications(ctx context.Context, status string, limit int) ([]models.Notification, error) {
	var notifications []models.Notification
	query := n.db.Order("created_at desc")
	if status != "" {
		query = query.Where("status = ?", status)
	}
	if limit > 0 {
		query = query.Limit(limit)
	}
	if err := query.Find(&notifications).Error; err != nil {
		return nil, err
	}
	return notifications, nil
}

func (n *Notifier) GetStats(ctx context.Context) (map[string]interface{}, error) {
	type Result struct {
		Status string
		Count  int64
	}
	var results []Result
	if err := n.db.Model(&models.Notification{}).
		Select("status, count(*) as count").
		Group("status").Scan(&results).Error; err != nil {
		return nil, err
	}
	stats := make(map[string]interface{})
	for _, r := range results {
		stats[r.Status] = r.Count
	}
	return stats, nil
}
