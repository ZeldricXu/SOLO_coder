package services

import (
	"notifypush/internal/channels"
	"notifypush/internal/models"
	"notifypush/internal/storage"
	"sync"
	"time"
)

const (
	DefaultMaxRetries = 3
	DefaultRetryDelay = 1000
)

type RetryService struct {
	storage         *storage.MemoryStorage
	channelRegistry *channels.ChannelRegistry
	statusTracker   *StatusTracker
	statisticsService *StatisticsService
	maxRetries      int
	retryDelayMs    int
	mu              sync.Mutex
}

func NewRetryService(
	storage *storage.MemoryStorage,
	channelRegistry *channels.ChannelRegistry,
	statusTracker *StatusTracker,
	statisticsService *StatisticsService,
) *RetryService {
	return &RetryService{
		storage:           storage,
		channelRegistry:   channelRegistry,
		statusTracker:     statusTracker,
		statisticsService: statisticsService,
		maxRetries:        DefaultMaxRetries,
		retryDelayMs:      DefaultRetryDelay,
	}
}

func (r *RetryService) SetMaxRetries(max int) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.maxRetries = max
}

func (r *RetryService) SetRetryDelay(delayMs int) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.retryDelayMs = delayMs
}

func (r *RetryService) ScheduleRetry(notification *models.Notification, errorMsg string) {
	r.mu.Lock()
	currentRetryCount := notification.RetryCount
	maxRetries := notification.MaxRetries
	if maxRetries <= 0 {
		maxRetries = r.maxRetries
	}
	r.mu.Unlock()

	if currentRetryCount >= maxRetries {
		r.markAsFailed(notification, "max retries exceeded: "+errorMsg)
		return
	}

	go func() {
		delay := r.retryDelayMs * (currentRetryCount + 1)
		time.Sleep(time.Duration(delay) * time.Millisecond)

		notification.Status = models.NotifyStatusRetrying
		notification.RetryCount++
		r.storage.UpdateNotificationStatus(notification.NotifyID, notification.Status, notification.RetryCount)
		r.statusTracker.UpdateSendStatus(notification.NotifyID, models.SendStatusRetrying, "retry attempt "+string(rune(notification.RetryCount)), notification.RetryCount)

		r.executeRetry(notification)
	}()
}

func (r *RetryService) executeRetry(notification *models.Notification) {
	channelType := models.ChannelType(notification.Channel)
	channel, exists := r.channelRegistry.Get(channelType)
	if !exists {
		r.markAsFailed(notification, "channel not found: "+notification.Channel)
		return
	}

	notification.Status = models.NotifyStatusSending
	r.storage.UpdateNotificationStatus(notification.NotifyID, notification.Status, notification.RetryCount)

	result, err := channel.Send(notification.Receiver, notification.Content, "")
	if err != nil || !result.Success {
		errorMsg := ""
		if result != nil && result.Message != "" {
			errorMsg = result.Message
		} else if err != nil {
			errorMsg = err.Error()
		}
		r.ScheduleRetry(notification, errorMsg)
		return
	}

	now := time.Now()
	notification.Status = models.NotifyStatusSent
	r.storage.UpdateNotificationStatus(notification.NotifyID, notification.Status, notification.RetryCount)
	r.statusTracker.UpdateSendStatus(notification.NotifyID, models.SendStatusSuccess, "", notification.RetryCount)
	r.statusTracker.UpdateDeliveryStatus(notification.NotifyID, models.DeliveryStatusDelivered)
	_ = now

	r.statisticsService.RecordSend(notification.Channel, true)
}

func (r *RetryService) markAsFailed(notification *models.Notification, errorMsg string) {
	notification.Status = models.NotifyStatusFailed
	r.storage.UpdateNotificationStatus(notification.NotifyID, notification.Status, notification.RetryCount)
	r.statusTracker.UpdateSendStatus(notification.NotifyID, models.SendStatusFailed, errorMsg, notification.RetryCount)
	r.statisticsService.RecordSend(notification.Channel, false)
}
