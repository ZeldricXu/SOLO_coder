package notification

import (
	"context"
	"fmt"
	"sync"
	"time"

	"loglevelplatform/internal/common/database"
	"loglevelplatform/internal/common/errors"
	"loglevelplatform/internal/common/logger"
	"loglevelplatform/internal/common/models"
	"loglevelplatform/pkg/utils"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

type Channel string

const (
	ChannelEmail   Channel = "email"
	ChannelSMS     Channel = "sms"
	ChannelWebhook Channel = "webhook"
	ChannelDingtalk Channel = "dingtalk"
	ChannelWechat  Channel = "wechat"
)

type NotificationStatus string

const (
	StatusPending   NotificationStatus = "pending"
	StatusSent      NotificationStatus = "sent"
	StatusDelivered NotificationStatus = "delivered"
	StatusFailed    NotificationStatus = "failed"
	StatusRetrying  NotificationStatus = "retrying"
)

type NotificationChannel interface {
	Send(ctx context.Context, recipient, content string, metadata map[string]string) error
	Name() Channel
}

type EmailChannel struct{}
type SMSChannel struct{}
type WebhookChannel struct{}

type Service struct {
	db            *gorm.DB
	channels      map[Channel]NotificationChannel
	retryQueue    chan *models.NotificationRecord
	running       bool
	stopChan      chan struct{}
	workers       int
	retryInterval time.Duration
	mu            sync.RWMutex
}

var (
	instance *Service
	once     sync.Once
)

func NewService() *Service {
	once.Do(func() {
		instance = &Service{
			db:            database.GetDB(),
			channels:      make(map[Channel]NotificationChannel),
			retryQueue:    make(chan *models.NotificationRecord, 1000),
			stopChan:      make(chan struct{}),
			workers:       3,
			retryInterval: 30 * time.Second,
		}
		instance.RegisterChannel(&EmailChannel{})
		instance.RegisterChannel(&SMSChannel{})
		instance.RegisterChannel(&WebhookChannel{})
	})
	return instance
}

func (e *EmailChannel) Send(ctx context.Context, recipient, content string, metadata map[string]string) error {
	log := logger.FromContext(ctx)
	log.Info("sending email",
		zap.String("to", recipient),
		zap.Int("content_length", len(content)),
	)
	return nil
}

func (e *EmailChannel) Name() Channel {
	return ChannelEmail
}

func (s *SMSChannel) Send(ctx context.Context, recipient, content string, metadata map[string]string) error {
	log := logger.FromContext(ctx)
	log.Info("sending SMS",
		zap.String("to", recipient),
		zap.Int("content_length", len(content)),
	)
	return nil
}

func (s *SMSChannel) Name() Channel {
	return ChannelSMS
}

func (w *WebhookChannel) Send(ctx context.Context, recipient, content string, metadata map[string]string) error {
	log := logger.FromContext(ctx)
	log.Info("sending webhook",
		zap.String("url", recipient),
		zap.Int("content_length", len(content)),
	)
	return nil
}

func (w *WebhookChannel) Name() Channel {
	return ChannelWebhook
}

func (s *Service) RegisterChannel(ch NotificationChannel) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.channels[ch.Name()] = ch
}

type SendRequest struct {
	Type        string            `json:"type" binding:"required"`
	Channel     string            `json:"channel" binding:"required"`
	Recipient   string            `json:"recipient" binding:"required"`
	Content     string            `json:"content" binding:"required"`
	MaxRetries  int               `json:"max_retries"`
	TraceID     string            `json:"trace_id"`
	Metadata    map[string]string `json:"metadata"`
}

func (s *Service) Send(ctx context.Context, req *SendRequest) (*models.NotificationRecord, error) {
	log := logger.FromContext(ctx)

	if req.TraceID == "" {
		req.TraceID = utils.NewTraceID()
	}
	if req.MaxRetries <= 0 {
		req.MaxRetries = 3
	}

	record := &models.NotificationRecord{
		ID:          utils.NewID("notif"),
		Type:        req.Type,
		Channel:     req.Channel,
		Recipient:   req.Recipient,
		Content:     req.Content,
		Status:      string(StatusPending),
		RetryCount:  0,
		MaxRetries:  req.MaxRetries,
		TraceID:     req.TraceID,
		Metadata:    req.Metadata,
		CreatedAt:   time.Now(),
	}

	if err := s.db.Create(record).Error; err != nil {
		log.Error("failed to create notification record", zap.Error(err))
		return nil, err
	}

	go s.processNotification(ctx, record)

	log.Info("notification queued",
		zap.String("id", record.ID),
		zap.String("channel", record.Channel),
		zap.String("trace_id", record.TraceID),
	)

	return record, nil
}

func (s *Service) processNotification(ctx context.Context, record *models.NotificationRecord) {
	log := logger.FromContext(ctx).With(zap.String("notification_id", record.ID))

	channel, exists := s.channels[Channel(record.Channel)]
	if !exists {
		s.markFailed(record, fmt.Sprintf("channel %s not found", record.Channel))
		return
	}

	now := time.Now()
	record.SentAt = &now
	record.Status = string(StatusSent)
	s.db.Save(record)

	err := channel.Send(ctx, record.Recipient, record.Content, record.Metadata)
	if err != nil {
		log.Warn("notification send failed", zap.Error(err), zap.Int("retry_count", record.RetryCount))

		if record.RetryCount < record.MaxRetries {
			record.RetryCount++
			record.Status = string(StatusRetrying)
			s.db.Save(record)

			go func() {
				time.Sleep(s.getBackoffDuration(record.RetryCount))
				s.retryQueue <- record
			}()
			return
		}

		s.markFailed(record, err.Error())
		return
	}

	now2 := time.Now()
	record.DeliveredAt = &now2
	record.Status = string(StatusDelivered)
	s.db.Save(record)

	log.Info("notification delivered successfully",
		zap.String("channel", record.Channel),
		zap.Duration("latency", now2.Sub(*record.SentAt)),
	)
}

func (s *Service) getBackoffDuration(retryCount int) time.Duration {
	base := s.retryInterval
	for i := 1; i < retryCount; i++ {
		base *= 2
	}
	jitter := time.Duration(utils.RandomInt(0, 1000)) * time.Millisecond
	return base + jitter
}

func (s *Service) markFailed(record *models.NotificationRecord, errMsg string) {
	now := time.Now()
	record.FailedAt = &now
	record.ErrorMsg = &errMsg
	record.Status = string(StatusFailed)
	s.db.Save(record)
}

func (s *Service) Start() {
	s.mu.Lock()
	if s.running {
		s.mu.Unlock()
		return
	}
	s.running = true
	s.mu.Unlock()

	for i := 0; i < s.workers; i++ {
		go s.retryWorker(i)
	}

	logger.Info("notification service started", zap.Int("workers", s.workers))
}

func (s *Service) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running {
		s.running = false
		close(s.stopChan)
		logger.Info("notification service stopped")
	}
}

func (s *Service) retryWorker(id int) {
	log := logger.GetLogger().With(zap.Int("worker_id", id))

	for {
		select {
		case record, ok := <-s.retryQueue:
			if !ok {
				return
			}
			ctx := context.Background()
			ctx = logger.WithContext(ctx, log)
			s.processNotification(ctx, record)

		case <-s.stopChan:
			return
		}
	}
}

func (s *Service) GetNotification(ctx context.Context, id string) (*models.NotificationRecord, error) {
	var record models.NotificationRecord
	if err := s.db.Where("id = ?", id).First(&record).Error; err != nil {
		return nil, errors.NewNotFoundError("notification not found")
	}
	return &record, nil
}

func (s *Service) ListNotifications(ctx context.Context, status, channel, traceID string, limit, offset int) ([]models.NotificationRecord, int64, error) {
	var records []models.NotificationRecord
	var total int64

	query := s.db.Model(&models.NotificationRecord{})
	if status != "" {
		query = query.Where("status = ?", status)
	}
	if channel != "" {
		query = query.Where("channel = ?", channel)
	}
	if traceID != "" {
		query = query.Where("trace_id = ?", traceID)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("created_at DESC").Limit(limit).Offset(offset).Find(&records).Error; err != nil {
		return nil, 0, err
	}

	return records, total, nil
}

func (s *Service) GetDeliveryStatus(ctx context.Context, id string) (map[string]interface{}, error) {
	record, err := s.GetNotification(ctx, id)
	if err != nil {
		return nil, err
	}

	status := map[string]interface{}{
		"id":           record.ID,
		"status":       record.Status,
		"retry_count":  record.RetryCount,
		"max_retries":  record.MaxRetries,
		"created_at":   record.CreatedAt,
		"sent_at":      record.SentAt,
		"delivered_at": record.DeliveredAt,
		"failed_at":    record.FailedAt,
		"error":        record.ErrorMsg,
	}

	if record.Status == string(StatusDelivered) && record.SentAt != nil && record.DeliveredAt != nil {
		status["delivery_latency_ms"] = record.DeliveredAt.Sub(*record.SentAt).Milliseconds()
	}

	return status, nil
}

func (s *Service) RetryNotification(ctx context.Context, id string) (*models.NotificationRecord, error) {
	log := logger.FromContext(ctx)

	record, err := s.GetNotification(ctx, id)
	if err != nil {
		return nil, err
	}

	if record.Status == string(StatusDelivered) {
		return nil, errors.NewValidationError("notification already delivered")
	}

	record.RetryCount = 0
	record.Status = string(StatusPending)
	record.ErrorMsg = nil
	record.FailedAt = nil
	record.SentAt = nil
	record.DeliveredAt = nil
	s.db.Save(record)

	go s.processNotification(ctx, record)

	log.Info("notification retry triggered", zap.String("id", id))
	return record, nil
}

func (s *Service) GetStatistics(ctx context.Context, startTime, endTime time.Time) (map[string]interface{}, error) {
	stats := make(map[string]interface{})

	query := s.db.Model(&models.NotificationRecord{})
	if !startTime.IsZero() {
		query = query.Where("created_at >= ?", startTime)
	}
	if !endTime.IsZero() {
		query = query.Where("created_at <= ?", endTime)
	}

	var total int64
	query.Count(&total)
	stats["total"] = total

	statuses := []string{string(StatusPending), string(StatusSent), string(StatusDelivered), string(StatusFailed), string(StatusRetrying)}
	for _, status := range statuses {
		var count int64
		query.Where("status = ?", status).Count(&count)
		stats[status+"_count"] = count
	}

	return stats, nil
}

func (s *Service) LoadFailedNotifications(ctx context.Context) error {
	var failedRecords []models.NotificationRecord
	err := s.db.Where("status = ? AND retry_count < max_retries", StatusFailed).
		Find(&failedRecords).Error
	if err != nil {
		return err
	}

	for i := range failedRecords {
		record := &failedRecords[i]
		go func(r *models.NotificationRecord) {
			s.retryQueue <- r
		}(record)
	}

	logger.Info("loaded failed notifications for retry", zap.Int("count", len(failedRecords)))
	return nil
}
