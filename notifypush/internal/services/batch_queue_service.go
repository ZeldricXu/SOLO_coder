package services

import (
	"context"
	"encoding/json"
	"fmt"
	"notifypush/internal/channels"
	"notifypush/internal/config"
	"notifypush/internal/models"
	"notifypush/internal/storage"
	"sync"
	"time"
)

type BatchTaskItem struct {
	BatchID    string              `json:"batch_id"`
	TemplateID string              `json:"template_id"`
	Channel    string              `json:"channel"`
	Receivers  []string            `json:"receivers"`
	Variables  []map[string]string `json:"variables"`
	BatchSize  int                 `json:"batch_size"`
	Priority   int                 `json:"priority"`
	CreatedAt  time.Time           `json:"created_at"`
}

type BatchQueueService struct {
	redisClient     storage.RedisClient
	channelRegistry *channels.ChannelRegistry
	storage         *storage.MemoryStorage
	templateSvc     *TemplateService
	statusTracker   *StatusTracker
	statisticsSvc   *StatisticsService
	retrySvc        *RetryService
	cfg             *config.BatchQueueConfig
	workers         []*BatchWorker
	workerWG        sync.WaitGroup
	stopChan        chan struct{}
	running         bool
	mu              sync.Mutex
}

type BatchWorker struct {
	id          int
	queueSvc    *BatchQueueService
	stopChan    chan struct{}
	stopped     bool
}

func NewBatchQueueService(
	redisClient storage.RedisClient,
	channelRegistry *channels.ChannelRegistry,
	storage *storage.MemoryStorage,
	templateSvc *TemplateService,
	statusTracker *StatusTracker,
	statisticsSvc *StatisticsService,
	retrySvc *RetryService,
	cfg *config.BatchQueueConfig,
) *BatchQueueService {
	return &BatchQueueService{
		redisClient:     redisClient,
		channelRegistry: channelRegistry,
		storage:         storage,
		templateSvc:     templateSvc,
		statusTracker:   statusTracker,
		statisticsSvc:   statisticsSvc,
		retrySvc:        retrySvc,
		cfg:             cfg,
		stopChan:        make(chan struct{}),
	}
}

func (s *BatchQueueService) Start() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running {
		return nil
	}
	s.running = true
	s.stopChan = make(chan struct{})
	s.workers = make([]*BatchWorker, s.cfg.WorkerCount)
	for i := 0; i < s.cfg.WorkerCount; i++ {
		worker := &BatchWorker{
			id:       i,
			queueSvc: s,
			stopChan: make(chan struct{}),
			stopped:  false,
		}
		s.workers[i] = worker
		s.workerWG.Add(1)
		go worker.run()
	}
	fmt.Printf("Batch Queue Service started with %d workers\n", s.cfg.WorkerCount)
	return nil
}

func (s *BatchQueueService) Stop() {
	s.mu.Lock()
	if !s.running {
		s.mu.Unlock()
		return
	}
	s.running = false
	close(s.stopChan)
	for _, worker := range s.workers {
		close(worker.stopChan)
	}
	s.mu.Unlock()
	s.workerWG.Wait()
	fmt.Println("Batch Queue Service stopped")
}

func (s *BatchQueueService) Enqueue(task *BatchTaskItem) error {
	taskJSON, err := json.Marshal(task)
	if err != nil {
		return err
	}
	ctx := context.Background()
	_, err = s.redisClient.LPush(ctx, s.cfg.QueueName, string(taskJSON))
	if err != nil {
		return err
	}
	return nil
}

func (w *BatchWorker) run() {
	defer w.queueSvc.workerWG.Done()
	for {
		select {
		case <-w.stopChan:
			return
		case <-w.queueSvc.stopChan:
			return
		default:
			ctx := context.Background()
			result, err := w.queueSvc.redisClient.BRPop(
				ctx,
				time.Duration(w.queueSvc.cfg.PollIntervalMs)*time.Millisecond,
				w.queueSvc.cfg.QueueName,
			)
			if err != nil {
				time.Sleep(time.Duration(w.queueSvc.cfg.PollIntervalMs) * time.Millisecond)
				continue
			}
			if result == nil || len(result) < 2 {
				continue
			}
			taskJSON := result[1]
			var task BatchTaskItem
			err = json.Unmarshal([]byte(taskJSON), &task)
			if err != nil {
				continue
			}
			w.processBatch(&task)
		}
	}
}

func (w *BatchWorker) processBatch(task *BatchTaskItem) {
	w.queueSvc.storage.UpdateBatchStatus(task.BatchID, models.BatchStatusProcessing)
	template, err := w.queueSvc.templateSvc.GetTemplate(task.TemplateID)
	if err != nil {
		w.markBatchFailed(task, "template not found: "+err.Error())
		return
	}
	channelType := models.ChannelType(task.Channel)
	channel, exists := w.queueSvc.channelRegistry.Get(channelType)
	if !exists {
		w.markBatchFailed(task, "channel not found: "+task.Channel)
		return
	}
	totalReceivers := len(task.Receivers)
	sentCount := 0
	successCount := 0
	failCount := 0
	batchSize := task.BatchSize
	if batchSize <= 0 {
		batchSize = 100
	}
	var mu sync.Mutex
	var wg sync.WaitGroup
	workerPool := make(chan struct{}, 10)
	for i := 0; i < totalReceivers; i += batchSize {
		end := i + batchSize
		if end > totalReceivers {
			end = totalReceivers
		}
		batchReceivers := task.Receivers[i:end]
		workerPool <- struct{}{}
		wg.Add(1)
		go func(startIdx int, receivers []string) {
			defer wg.Done()
			defer func() { <-workerPool }()
			for idx, receiver := range receivers {
				globalIdx := startIdx + idx
				var vars map[string]string
				if task.Variables != nil && globalIdx < len(task.Variables) {
					vars = task.Variables[globalIdx]
				} else {
					vars = make(map[string]string)
				}
				content := template.TemplateContent
				subject := template.Subject
				for _, varName := range template.Variables {
					placeholder := "{" + varName + "}"
					if value, exists := vars[varName]; exists {
						for {
							idx := -1
							for j := 0; j <= len(content)-len(placeholder); j++ {
								if content[j:j+len(placeholder)] == placeholder {
									idx = j
									break
								}
							}
							if idx == -1 {
								break
							}
							content = content[:idx] + value + content[idx+len(placeholder):]
						}
					}
				}
				notifyID := generateNotifyID()
				now := time.Now()
				notification := &models.Notification{
					NotifyID:    notifyID,
					NotifyType:  "batch",
					TemplateID:  task.TemplateID,
					Channel:     task.Channel,
					Receiver:    receiver,
					Content:     content,
					Priority:    task.Priority,
					CreatedAt:   now,
					ScheduledAt: now,
					Status:      models.NotifyStatusQueued,
					RetryCount:  0,
					MaxRetries:  3,
					Variables:   vars,
					BatchID:     task.BatchID,
				}
				w.queueSvc.storage.SaveNotification(notification)
				w.queueSvc.statusTracker.CreateStatusRecord(notifyID, task.Channel)
				result, sendErr := channel.Send(receiver, content, subject)
				mu.Lock()
				sentCount++
				if sendErr == nil && result.Success {
					successCount++
					notification.Status = models.NotifyStatusSent
					w.queueSvc.storage.UpdateNotificationStatus(notifyID, notification.Status, 0)
					w.queueSvc.statusTracker.UpdateSendStatus(notifyID, models.SendStatusSuccess, "", 0)
					w.queueSvc.statusTracker.UpdateDeliveryStatus(notifyID, models.DeliveryStatusDelivered)
					w.queueSvc.statisticsSvc.RecordSend(task.Channel, true)
				} else {
					failCount++
					errorMsg := ""
					if result != nil {
						errorMsg = result.Message
					} else if sendErr != nil {
						errorMsg = sendErr.Error()
					}
					notification.Status = models.NotifyStatusFailed
					w.queueSvc.storage.UpdateNotificationStatus(notifyID, notification.Status, 0)
					w.queueSvc.statusTracker.UpdateSendStatus(notifyID, models.SendStatusFailed, errorMsg, 0)
					w.queueSvc.statisticsSvc.RecordSend(task.Channel, false)
				}
				w.queueSvc.storage.UpdateBatchProgress(task.BatchID, sentCount, successCount, failCount)
				mu.Unlock()
			}
		}(i, batchReceivers)
	}
	wg.Wait()
	w.queueSvc.storage.UpdateBatchStatus(task.BatchID, models.BatchStatusCompleted)
}

func (w *BatchWorker) markBatchFailed(task *BatchTaskItem, errorMsg string) {
	w.queueSvc.storage.UpdateBatchStatus(task.BatchID, models.BatchStatusFailed)
	fmt.Printf("Batch %s failed: %s\n", task.BatchID, errorMsg)
}

func (s *BatchQueueService) GetQueueLength() (int64, error) {
	ctx := context.Background()
	items, err := s.redisClient.LRange(ctx, s.cfg.QueueName, 0, -1)
	if err != nil {
		return 0, err
	}
	return int64(len(items)), nil
}

func (s *BatchQueueService) IsRunning() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.running
}
