package services

import (
	"errors"
	"notifypush/internal/channels"
	"notifypush/internal/models"
	"notifypush/internal/storage"
	"sync"
	"time"
)

const (
	DefaultBatchSize = 100
	MaxWorkers       = 10
)

type BatchService struct {
	storage           *storage.MemoryStorage
	channelRegistry   *channels.ChannelRegistry
	templateService   *TemplateService
	statusTracker     *StatusTracker
	retryService      *RetryService
	statisticsService *StatisticsService
	workerPool        chan struct{}
	batchQueueSvc     *BatchQueueService
	useBatchQueue     bool
}

func NewBatchService(
	storage *storage.MemoryStorage,
	channelRegistry *channels.ChannelRegistry,
	templateService *TemplateService,
	statusTracker *StatusTracker,
	retryService *RetryService,
	statisticsService *StatisticsService,
) *BatchService {
	return &BatchService{
		storage:           storage,
		channelRegistry:   channelRegistry,
		templateService:   templateService,
		statusTracker:     statusTracker,
		retryService:      retryService,
		statisticsService: statisticsService,
		workerPool:        make(chan struct{}, MaxWorkers),
		useBatchQueue:     false,
	}
}

func (s *BatchService) WithBatchQueue(batchQueueSvc *BatchQueueService) *BatchService {
	s.batchQueueSvc = batchQueueSvc
	s.useBatchQueue = true
	return s
}

func (s *BatchService) CreateBatchTask(req *models.BatchSendRequest) (*models.BatchSendResponse, error) {
	if req.TemplateID == "" {
		return nil, errors.New("template_id is required")
	}
	if req.Channel == "" {
		return nil, errors.New("channel is required")
	}
	if len(req.Receivers) == 0 {
		return nil, errors.New("receivers is required")
	}

	channelType := models.ChannelType(req.Channel)
	_, exists := s.channelRegistry.Get(channelType)
	if !exists {
		return nil, errors.New("unsupported channel: " + req.Channel)
	}

	_, err := s.templateService.GetTemplate(req.TemplateID)
	if err != nil {
		return nil, err
	}

	batchSize := req.BatchSize
	if batchSize <= 0 {
		batchSize = DefaultBatchSize
	}

	priority := req.Priority
	if priority <= 0 {
		priority = 5
	}

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

	batchID := generateBatchID()
	batch := &models.BatchTask{
		BatchID:      batchID,
		NotifyType:   "batch",
		TemplateID:   req.TemplateID,
		Channel:      req.Channel,
		Receivers:    req.Receivers,
		Variables:    req.Variables,
		BatchSize:    batchSize,
		TotalCount:   len(req.Receivers),
		SentCount:    0,
		SuccessCount: 0,
		FailCount:    0,
		Status:       models.BatchStatusPending,
		Priority:     priority,
		ScheduledAt:  scheduledAt,
		CreatedAt:    now,
	}

	err = s.storage.SaveBatchTask(batch)
	if err != nil {
		return nil, err
	}

	if s.useBatchQueue {
		task := &BatchTaskItem{
			BatchID:    batchID,
			TemplateID: req.TemplateID,
			Channel:    req.Channel,
			Receivers:  req.Receivers,
			Variables:  req.Variables,
			BatchSize:  batchSize,
			Priority:   priority,
			CreatedAt:  now,
		}
		err = s.batchQueueSvc.Enqueue(task)
		if err != nil {
			batch.Status = models.BatchStatusFailed
			s.storage.UpdateBatchStatus(batchID, models.BatchStatusFailed)
			return nil, err
		}
		return &models.BatchSendResponse{
			BatchID: batchID,
			Status:  string(models.BatchStatusProcessing),
		}, nil
	}

	go s.executeBatch(batch)

	return &models.BatchSendResponse{
		BatchID: batchID,
		Status:  string(models.BatchStatusProcessing),
	}, nil
}

func (s *BatchService) executeBatch(batch *models.BatchTask) {
	s.storage.UpdateBatchStatus(batch.BatchID, models.BatchStatusProcessing)

	channelType := models.ChannelType(batch.Channel)
	channel, _ := s.channelRegistry.Get(channelType)
	template, _ := s.templateService.GetTemplate(batch.TemplateID)

	totalReceivers := len(batch.Receivers)
	sentCount := 0
	successCount := 0
	failCount := 0

	var wg sync.WaitGroup
	var mu sync.Mutex

	for i := 0; i < totalReceivers; i += batch.BatchSize {
		end := i + batch.BatchSize
		if end > totalReceivers {
			end = totalReceivers
		}

		batchReceivers := batch.Receivers[i:end]

		s.workerPool <- struct{}{}
		wg.Add(1)

		go func(startIdx int, receivers []string) {
			defer wg.Done()
			defer func() { <-s.workerPool }()

			for idx, receiver := range receivers {
				globalIdx := startIdx + idx
				var vars map[string]string
				if batch.Variables != nil && globalIdx < len(batch.Variables) {
					vars = batch.Variables[globalIdx]
				} else {
					vars = make(map[string]string)
				}

				content := template.TemplateContent
				subject := template.Subject
				for _, varName := range template.Variables {
					placeholder := "{" + varName + "}"
					if value, exists := vars[varName]; exists {
						content = replacePlaceholder(content, placeholder, value)
					}
				}

				notifyID := generateNotifyID()
				now := time.Now()
				notification := &models.Notification{
					NotifyID:    notifyID,
					NotifyType:  "batch",
					TemplateID:  batch.TemplateID,
					Channel:     batch.Channel,
					Receiver:    receiver,
					Content:     content,
					Priority:    batch.Priority,
					CreatedAt:   now,
					ScheduledAt: now,
					Status:      models.NotifyStatusQueued,
					RetryCount:  0,
					MaxRetries:  3,
					Variables:   vars,
					BatchID:     batch.BatchID,
				}

				s.storage.SaveNotification(notification)
				s.statusTracker.CreateStatusRecord(notifyID, batch.Channel)

				result, err := channel.Send(receiver, content, subject)

				mu.Lock()
				sentCount++
				if err == nil && result.Success {
					successCount++
					notification.Status = models.NotifyStatusSent
					s.storage.UpdateNotificationStatus(notifyID, notification.Status, 0)
					s.statusTracker.UpdateSendStatus(notifyID, models.SendStatusSuccess, "", 0)
					s.statusTracker.UpdateDeliveryStatus(notifyID, models.DeliveryStatusDelivered)
					s.statisticsService.RecordSend(batch.Channel, true)
				} else {
					failCount++
					errorMsg := ""
					if result != nil {
						errorMsg = result.Message
					} else if err != nil {
						errorMsg = err.Error()
					}
					notification.Status = models.NotifyStatusFailed
					s.storage.UpdateNotificationStatus(notifyID, notification.Status, 0)
					s.statusTracker.UpdateSendStatus(notifyID, models.SendStatusFailed, errorMsg, 0)
					s.statisticsService.RecordSend(batch.Channel, false)
				}
				s.storage.UpdateBatchProgress(batch.BatchID, sentCount, successCount, failCount)
				mu.Unlock()
			}
		}(i, batchReceivers)
	}

	wg.Wait()

	s.storage.UpdateBatchStatus(batch.BatchID, models.BatchStatusCompleted)
}

func (s *BatchService) GetBatchStatus(batchID string) (*models.BatchStatistics, error) {
	batch, err := s.storage.GetBatchTask(batchID)
	if err != nil {
		return nil, err
	}
	if batch == nil {
		return nil, errors.New("batch not found")
	}

	stats := &models.BatchStatistics{
		BatchID:      batch.BatchID,
		TotalCount:   batch.TotalCount,
		SentCount:    batch.SentCount,
		SuccessCount: batch.SuccessCount,
		FailCount:    batch.FailCount,
	}

	if batch.SentCount > 0 {
		stats.SuccessRate = float64(batch.SuccessCount) / float64(batch.SentCount) * 100
	}

	return stats, nil
}

func replacePlaceholder(content, placeholder, value string) string {
	for {
		idx := indexOf(content, placeholder)
		if idx == -1 {
			break
		}
		content = content[:idx] + value + content[idx+len(placeholder):]
	}
	return content
}

func indexOf(s, substr string) int {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return i
		}
	}
	return -1
}
