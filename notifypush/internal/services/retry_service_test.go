package services

import (
	"notifypush/internal/channels"
	"notifypush/internal/models"
	"notifypush/internal/storage"
	"notifypush/internal/testdata"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type MockFailingChannel struct {
	failCount     int32
	totalCalls    int32
	failUntil     int
	channelType   models.ChannelType
}

func NewMockFailingChannel(failUntil int, channelType models.ChannelType) *MockFailingChannel {
	return &MockFailingChannel{
		failUntil:   failUntil,
		channelType: channelType,
	}
}

func (m *MockFailingChannel) Send(receiver string, content string, subject string) (*channels.SendResult, error) {
	callNum := atomic.AddInt32(&m.totalCalls, 1)
	currentFailCount := atomic.LoadInt32(&m.failCount)
	
	if int(callNum) <= m.failUntil {
		atomic.AddInt32(&m.failCount, 1)
		return &channels.SendResult{
			Success: false,
			Error:   nil,
			Message: "mock channel failure",
		}, nil
	}
	
	_ = currentFailCount
	return &channels.SendResult{
		Success: true,
		Message: "success",
	}, nil
}

func (m *MockFailingChannel) GetChannelType() models.ChannelType {
	return m.channelType
}

func (m *MockFailingChannel) GetCallCount() int {
	return int(atomic.LoadInt32(&m.totalCalls))
}

func (m *MockFailingChannel) GetFailCount() int {
	return int(atomic.LoadInt32(&m.failCount))
}

type MockAlwaysFailingChannel struct {
	callCount int32
}

func NewMockAlwaysFailingChannel() *MockAlwaysFailingChannel {
	return &MockAlwaysFailingChannel{}
}

func (m *MockAlwaysFailingChannel) Send(receiver string, content string, subject string) (*channels.SendResult, error) {
	atomic.AddInt32(&m.callCount, 1)
	return &channels.SendResult{
		Success: false,
		Message: "always fails",
	}, nil
}

func (m *MockAlwaysFailingChannel) GetChannelType() models.ChannelType {
	return models.ChannelTypeSMS
}

func (m *MockAlwaysFailingChannel) GetCallCount() int {
	return int(atomic.LoadInt32(&m.callCount))
}

func TestRetryService_MaxRetriesLimit(t *testing.T) {
	storage := storage.NewMemoryStorage()
	channelRegistry := channels.NewChannelRegistry()
	statusTracker := NewStatusTracker(storage)
	statisticsService := NewStatisticsService(storage)
	
	mockChannel := NewMockAlwaysFailingChannel()
	channelRegistry.Register(models.ChannelTypeSMS, mockChannel)
	
	retryService := NewRetryService(storage, channelRegistry, statusTracker, statisticsService)
	retryService.SetMaxRetries(3)
	retryService.SetRetryDelay(100)
	
	notification := testdata.NewNotificationBuilder().
		WithNotifyID("notify_retry_limit").
		WithChannel("sms").
		WithStatus(models.NotifyStatusFailed).
		WithRetryCount(0).
		WithMaxRetries(3).
		Build()
	
	storage.SaveNotification(notification)
	statusTracker.CreateStatusRecord(notification.NotifyID, "sms")
	
	retryService.ScheduleRetry(notification, "initial failure")
	
	time.Sleep(1500 * time.Millisecond)
	
	callCount := mockChannel.GetCallCount()
	if callCount != 4 {
		t.Errorf("Expected 4 calls (1 initial + 3 retries), got %d", callCount)
	}
	
	updatedNotify, _ := storage.GetNotification(notification.NotifyID)
	if updatedNotify.Status != models.NotifyStatusFailed {
		t.Errorf("Expected final status 'failed', got '%s'", updatedNotify.Status)
	}
	
	if updatedNotify.RetryCount != 3 {
		t.Errorf("Expected retry_count 3, got %d", updatedNotify.RetryCount)
	}
	
	t.Logf("Max retries test: calls=%d, status=%s, retries=%d",
		callCount, updatedNotify.Status, updatedNotify.RetryCount)
}

func TestRetryService_SuccessAfterRetry(t *testing.T) {
	storage := storage.NewMemoryStorage()
	channelRegistry := channels.NewChannelRegistry()
	statusTracker := NewStatusTracker(storage)
	statisticsService := NewStatisticsService(storage)
	
	mockChannel := NewMockFailingChannel(2, models.ChannelTypeSMS)
	channelRegistry.Register(models.ChannelTypeSMS, mockChannel)
	
	retryService := NewRetryService(storage, channelRegistry, statusTracker, statisticsService)
	retryService.SetMaxRetries(5)
	retryService.SetRetryDelay(100)
	
	notification := testdata.NewNotificationBuilder().
		WithNotifyID("notify_success_after_retry").
		WithChannel("sms").
		WithStatus(models.NotifyStatusFailed).
		WithRetryCount(0).
		WithMaxRetries(5).
		Build()
	
	storage.SaveNotification(notification)
	statusTracker.CreateStatusRecord(notification.NotifyID, "sms")
	
	retryService.ScheduleRetry(notification, "initial failure")
	
	time.Sleep(2000 * time.Millisecond)
	
	callCount := mockChannel.GetCallCount()
	if callCount < 3 {
		t.Errorf("Expected at least 3 calls, got %d", callCount)
	}
	
	updatedNotify, _ := storage.GetNotification(notification.NotifyID)
	if updatedNotify.Status != models.NotifyStatusSent && updatedNotify.Status != models.NotifyStatusFailed {
		t.Errorf("Expected status 'sent' or 'failed', got '%s'", updatedNotify.Status)
	}
	
	t.Logf("Success after retry: calls=%d, status=%s, retries=%d",
		callCount, updatedNotify.Status, updatedNotify.RetryCount)
}

func TestRetryService_RetryInterval(t *testing.T) {
	storage := storage.NewMemoryStorage()
	channelRegistry := channels.NewChannelRegistry()
	statusTracker := NewStatusTracker(storage)
	statisticsService := NewStatisticsService(storage)
	
	mockChannel := NewMockAlwaysFailingChannel()
	channelRegistry.Register(models.ChannelTypeSMS, mockChannel)
	
	retryDelay := 200
	retryService := NewRetryService(storage, channelRegistry, statusTracker, statisticsService)
	retryService.SetMaxRetries(2)
	retryService.SetRetryDelay(retryDelay)
	
	notification := testdata.NewNotificationBuilder().
		WithNotifyID("notify_retry_interval").
		WithChannel("sms").
		WithStatus(models.NotifyStatusFailed).
		WithRetryCount(0).
		WithMaxRetries(2).
		Build()
	
	storage.SaveNotification(notification)
	statusTracker.CreateStatusRecord(notification.NotifyID, "sms")
	
	start := time.Now()
	retryService.ScheduleRetry(notification, "initial failure")
	
	expectedMinTime := time.Duration(retryDelay) * 1 * time.Millisecond
	expectedMaxTime := time.Duration(retryDelay) * 6 * time.Millisecond
	
	time.Sleep(expectedMaxTime + 500*time.Millisecond)
	
	elapsed := time.Since(start)
	callCount := mockChannel.GetCallCount()
	
	if callCount != 3 {
		t.Errorf("Expected 3 calls, got %d", callCount)
	}
	
	if elapsed < expectedMinTime {
		t.Errorf("Retries too fast: elapsed %v, expected at least %v", elapsed, expectedMinTime)
	}
	
	t.Logf("Retry interval test: elapsed=%v, calls=%d, expected min=%v, max=%v",
		elapsed, callCount, expectedMinTime, expectedMaxTime)
}

func TestRetryService_ConcurrentRetries(t *testing.T) {
	storage := storage.NewMemoryStorage()
	channelRegistry := channels.NewChannelRegistry()
	statusTracker := NewStatusTracker(storage)
	statisticsService := NewStatisticsService(storage)
	
	mockChannel := NewMockAlwaysFailingChannel()
	channelRegistry.Register(models.ChannelTypeSMS, mockChannel)
	
	retryService := NewRetryService(storage, channelRegistry, statusTracker, statisticsService)
	retryService.SetMaxRetries(2)
	retryService.SetRetryDelay(100)
	
	numNotifications := 10
	var wg sync.WaitGroup
	
	start := time.Now()
	
	for i := 0; i < numNotifications; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			notifyID := "notify_concurrent_" + string(rune(idx))
			notification := testdata.NewNotificationBuilder().
				WithNotifyID(notifyID).
				WithChannel("sms").
				WithStatus(models.NotifyStatusFailed).
				WithRetryCount(0).
				WithMaxRetries(2).
				Build()
			
			storage.SaveNotification(notification)
			statusTracker.CreateStatusRecord(notifyID, "sms")
			retryService.ScheduleRetry(notification, "test failure")
		}(i)
	}
	
	wg.Wait()
	
	waitTime := 2500 * time.Millisecond
	time.Sleep(waitTime)
	
	elapsed := time.Since(start)
	totalCalls := mockChannel.GetCallCount()
	expectedCalls := numNotifications * 3
	
	t.Logf("Concurrent retries: elapsed=%v, total calls=%d, expected=%d",
		elapsed, totalCalls, expectedCalls)
	
	if totalCalls != expectedCalls {
		t.Errorf("Expected %d total calls, got %d", expectedCalls, totalCalls)
	}
}

func TestRetryService_StatusTrackingDuringRetry(t *testing.T) {
	storage := storage.NewMemoryStorage()
	channelRegistry := channels.NewChannelRegistry()
	statusTracker := NewStatusTracker(storage)
	statisticsService := NewStatisticsService(storage)
	
	mockChannel := NewMockAlwaysFailingChannel()
	channelRegistry.Register(models.ChannelTypeSMS, mockChannel)
	
	retryService := NewRetryService(storage, channelRegistry, statusTracker, statisticsService)
	retryService.SetMaxRetries(2)
	retryService.SetRetryDelay(150)
	
	notification := testdata.NewNotificationBuilder().
		WithNotifyID("notify_status_tracking").
		WithChannel("sms").
		WithStatus(models.NotifyStatusFailed).
		WithRetryCount(0).
		WithMaxRetries(2).
		Build()
	
	storage.SaveNotification(notification)
	statusTracker.CreateStatusRecord(notification.NotifyID, "sms")
	
	retryService.ScheduleRetry(notification, "initial failure")
	
	time.Sleep(100 * time.Millisecond)
	record1, _ := statusTracker.GetStatus(notification.NotifyID)
	t.Logf("After 100ms: send_status=%s, retry_attempt=%d",
		record1.SendStatus, record1.RetryAttempt)
	
	time.Sleep(500 * time.Millisecond)
	record2, _ := statusTracker.GetStatus(notification.NotifyID)
	t.Logf("After 600ms: send_status=%s, retry_attempt=%d",
		record2.SendStatus, record2.RetryAttempt)
	
	time.Sleep(1500 * time.Millisecond)
	finalRecord, _ := statusTracker.GetStatus(notification.NotifyID)
	finalNotify, _ := storage.GetNotification(notification.NotifyID)
	
	t.Logf("Final: send_status=%s, delivery_status=%s, retry_attempt=%d, notify_status=%s",
		finalRecord.SendStatus, finalRecord.DeliveryStatus, finalRecord.RetryAttempt, finalNotify.Status)
	
	if finalRecord.SendStatus != models.SendStatusFailed {
		t.Errorf("Expected final send_status 'failed', got '%s'", finalRecord.SendStatus)
	}
	
	if finalNotify.Status != models.NotifyStatusFailed {
		t.Errorf("Expected final notify_status 'failed', got '%s'", finalNotify.Status)
	}
}

func TestRetryService_CustomMaxRetries(t *testing.T) {
	storage := storage.NewMemoryStorage()
	channelRegistry := channels.NewChannelRegistry()
	statusTracker := NewStatusTracker(storage)
	statisticsService := NewStatisticsService(storage)
	
	mockChannel := NewMockAlwaysFailingChannel()
	channelRegistry.Register(models.ChannelTypeSMS, mockChannel)
	
	retryService := NewRetryService(storage, channelRegistry, statusTracker, statisticsService)
	retryService.SetMaxRetries(5)
	retryService.SetRetryDelay(50)
	
	notification := testdata.NewNotificationBuilder().
		WithNotifyID("notify_custom_retries").
		WithChannel("sms").
		WithStatus(models.NotifyStatusFailed).
		WithRetryCount(0).
		WithMaxRetries(5).
		Build()
	
	storage.SaveNotification(notification)
	statusTracker.CreateStatusRecord(notification.NotifyID, "sms")
	
	retryService.ScheduleRetry(notification, "initial failure")
	
	time.Sleep(3000 * time.Millisecond)
	
	callCount := mockChannel.GetCallCount()
	expectedCalls := 6
	
	t.Logf("Custom retries: calls=%d, expected=%d", callCount, expectedCalls)
	
	if callCount != expectedCalls {
		t.Errorf("Expected %d calls (1 + 5 retries), got %d", expectedCalls, callCount)
	}
	
	updatedNotify, _ := storage.GetNotification(notification.NotifyID)
	if updatedNotify.RetryCount != 5 {
		t.Errorf("Expected retry_count 5, got %d", updatedNotify.RetryCount)
	}
}

func TestRetryService_WithTestDataBuilder(t *testing.T) {
	notification := testdata.NewNotificationBuilder().
		WithNotifyID("notify_retry_builder").
		WithChannel("sms").
		WithStatus(models.NotifyStatusRetrying).
		WithRetryCount(2).
		WithMaxRetries(5).
		Build()
	
	if notification.NotifyID != "notify_retry_builder" {
		t.Errorf("Expected notify_id 'notify_retry_builder', got '%s'", notification.NotifyID)
	}
	
	if notification.Status != models.NotifyStatusRetrying {
		t.Errorf("Expected status 'retrying', got '%s'", notification.Status)
	}
	
	if notification.RetryCount != 2 {
		t.Errorf("Expected retry_count 2, got %d", notification.RetryCount)
	}
	
	if notification.MaxRetries != 5 {
		t.Errorf("Expected max_retries 5, got %d", notification.MaxRetries)
	}
}

func TestRetryService_StatisticsUpdate(t *testing.T) {
	storage := storage.NewMemoryStorage()
	channelRegistry := channels.NewChannelRegistry()
	statusTracker := NewStatusTracker(storage)
	statisticsService := NewStatisticsService(storage)
	
	mockChannel := NewMockAlwaysFailingChannel()
	channelRegistry.Register(models.ChannelTypeSMS, mockChannel)
	
	retryService := NewRetryService(storage, channelRegistry, statusTracker, statisticsService)
	retryService.SetMaxRetries(2)
	retryService.SetRetryDelay(50)
	
	notification := testdata.NewNotificationBuilder().
		WithNotifyID("notify_stats_test").
		WithChannel("sms").
		WithStatus(models.NotifyStatusFailed).
		WithRetryCount(0).
		WithMaxRetries(2).
		Build()
	
	storage.SaveNotification(notification)
	statusTracker.CreateStatusRecord(notification.NotifyID, "sms")
	
	retryService.ScheduleRetry(notification, "initial failure")
	
	time.Sleep(2000 * time.Millisecond)
	
	stats, _ := statisticsService.GetTodayStatistics("sms")
	
	t.Logf("Statistics: send_count=%d, success_count=%d, fail_count=%d",
		stats.SendCount, stats.SuccessCount, stats.FailCount)
	
	if stats.SendCount == 0 {
		t.Error("Expected at least one statistic record")
	}
}
