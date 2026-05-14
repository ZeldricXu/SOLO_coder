package services

import (
	"errors"
	"notifypush/internal/channels"
	"notifypush/internal/config"
	"notifypush/internal/models"
	"notifypush/internal/storage"
	"time"
)

type NotificationService struct {
	storage           *storage.MemoryStorage
	channelRegistry   *channels.ChannelRegistry
	templateService   *TemplateService
	statusTracker     *StatusTracker
	retryService      *RetryService
	statisticsService *StatisticsService
	smsQueueService   *SMSQueueService
	statusQuerySvc    *StatusQueryService
	useSMSQueue       bool
}

func NewNotificationService(
	storage *storage.MemoryStorage,
	channelRegistry *channels.ChannelRegistry,
	templateService *TemplateService,
	statusTracker *StatusTracker,
	retryService *RetryService,
	statisticsService *StatisticsService,
) *NotificationService {
	return &NotificationService{
		storage:           storage,
		channelRegistry:   channelRegistry,
		templateService:   templateService,
		statusTracker:     statusTracker,
		retryService:      retryService,
		statisticsService: statisticsService,
		useSMSQueue:       false,
	}
}

func (s *NotificationService) WithSMSQueue(smsQueueService *SMSQueueService, statusQuerySvc *StatusQueryService) *NotificationService {
	s.smsQueueService = smsQueueService
	s.statusQuerySvc = statusQuerySvc
	s.useSMSQueue = true
	return s
}

func (s *NotificationService) SendNotification(req *models.SendRequest) (*models.SendResponse, error) {
	if req.TemplateID == "" {
		return nil, errors.New("template_id is required")
	}
	if req.Channel == "" {
		return nil, errors.New("channel is required")
	}
	if req.Receiver == "" {
		return nil, errors.New("receiver is required")
	}

	channelType := models.ChannelType(req.Channel)
	channel, exists := s.channelRegistry.Get(channelType)
	if !exists {
		return nil, errors.New("unsupported channel: " + req.Channel)
	}

	content, subject, err := s.templateService.ParseTemplate(req.TemplateID, req.Variables)
	if err != nil {
		return nil, err
	}

	notifyID := generateNotifyID()
	now := time.Now()
	var scheduledAt time.Time
	if req.ScheduleAt != "" {
		parsed, err := time.Parse(time.RFC3339, req.ScheduleAt)
		if err != nil {
			scheduledAt = now
		} else {
			scheduledAt = parsed
		}
	} else {
		scheduledAt = now
	}

	priority := req.Priority
	if priority <= 0 {
		priority = 5
	}

	notification := &models.Notification{
		NotifyID:    notifyID,
		NotifyType:  "standard",
		TemplateID:  req.TemplateID,
		Channel:     req.Channel,
		Receiver:    req.Receiver,
		Content:     content,
		Priority:    priority,
		CreatedAt:   now,
		ScheduledAt: scheduledAt,
		Status:      models.NotifyStatusQueued,
		RetryCount:  0,
		MaxRetries:  3,
		Variables:   req.Variables,
	}

	err = s.storage.SaveNotification(notification)
	if err != nil {
		return nil, err
	}

	s.statusTracker.CreateStatusRecord(notifyID, req.Channel)

	if s.useSMSQueue && req.Channel == "sms" {
		task := &SMSTask{
			NotifyID:   notifyID,
			Receiver:   req.Receiver,
			Content:    content,
			Subject:    subject,
			TemplateID: req.TemplateID,
			Priority:   priority,
			Variables:  req.Variables,
			CreatedAt:  now,
		}
		err = s.smsQueueService.Enqueue(task)
		if err != nil {
			notification.Status = models.NotifyStatusFailed
			s.storage.UpdateNotificationStatus(notifyID, notification.Status, 0)
			s.statusTracker.UpdateSendStatus(notifyID, models.SendStatusFailed, err.Error(), 0)
			return nil, err
		}
		if s.statusQuerySvc != nil {
			s.statusQuerySvc.RegisterForQuery(notifyID, priority)
		}
		return &models.SendResponse{
			NotifyID: notifyID,
			Status:   string(models.NotifyStatusQueued),
		}, nil
	}

	go s.executeSend(notification, channel, subject)

	return &models.SendResponse{
		NotifyID: notifyID,
		Status:   string(models.NotifyStatusQueued),
	}, nil
}

func (s *NotificationService) executeSend(notification *models.Notification, channel channels.Channel, subject string) {
	notification.Status = models.NotifyStatusSending
	s.storage.UpdateNotificationStatus(notification.NotifyID, notification.Status, notification.RetryCount)
	s.statusTracker.UpdateSendStatus(notification.NotifyID, models.SendStatusPending, "", 0)

	result, err := channel.Send(notification.Receiver, notification.Content, subject)
	if err != nil || !result.Success {
		errorMsg := ""
		if result != nil && result.Message != "" {
			errorMsg = result.Message
		} else if err != nil {
			errorMsg = err.Error()
		}
		s.statusTracker.UpdateSendStatus(notification.NotifyID, models.SendStatusFailed, errorMsg, 0)
		s.retryService.ScheduleRetry(notification, errorMsg)
		return
	}

	notification.Status = models.NotifyStatusSent
	s.storage.UpdateNotificationStatus(notification.NotifyID, notification.Status, notification.RetryCount)
	s.statusTracker.UpdateSendStatus(notification.NotifyID, models.SendStatusSuccess, "", 0)
	s.statusTracker.UpdateDeliveryStatus(notification.NotifyID, models.DeliveryStatusDelivered)
	s.statisticsService.RecordSend(notification.Channel, true)
}

func (s *NotificationService) GetNotificationStatus(notifyID string) (*models.StatusQueryResponse, error) {
	notification, err := s.storage.GetNotification(notifyID)
	if err != nil {
		return nil, err
	}
	if notification == nil {
		return nil, errors.New("notification not found")
	}

	statusRecord, err := s.statusTracker.GetStatus(notifyID)
	if err != nil {
		return nil, err
	}

	response := &models.StatusQueryResponse{
		NotifyID:   notifyID,
		Channel:    notification.Channel,
		RetryCount: notification.RetryCount,
	}

	if statusRecord != nil {
		response.SendStatus = string(statusRecord.SendStatus)
		response.DeliveryStatus = string(statusRecord.DeliveryStatus)
		response.ErrorMessage = statusRecord.ErrorMessage
	}

	return response, nil
}

func generateNotifyID() string {
	return "notify_" + time.Now().Format("20060102150405") + "_" + randomString(6)
}

func generateBatchID() string {
	return "batch_" + time.Now().Format("20060102150405") + "_" + randomString(6)
}

func randomString(n int) string {
	const letters = "abcdefghijklmnopqrstuvwxyz0123456789"
	now := time.Now().UnixNano()
	result := make([]byte, n)
	for i := range result {
		result[i] = letters[int(now)%len(letters)]
		now = now / 2
	}
	return string(result)
}
