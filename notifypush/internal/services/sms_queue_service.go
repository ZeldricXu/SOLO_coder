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

type SMSTask struct {
	NotifyID   string            `json:"notify_id"`
	Receiver   string            `json:"receiver"`
	Content    string            `json:"content"`
	Subject    string            `json:"subject"`
	TemplateID string            `json:"template_id"`
	Priority   int               `json:"priority"`
	Variables  map[string]string `json:"variables"`
	CreatedAt  time.Time         `json:"created_at"`
}

type SMSQueueService struct {
	redisClient     storage.RedisClient
	channelRegistry *channels.ChannelRegistry
	storage         *storage.MemoryStorage
	statusTracker   *StatusTracker
	statisticsSvc   *StatisticsService
	cfg             *config.SMSQueueConfig
	workers         []*SMSWorker
	workerWG        sync.WaitGroup
	stopChan        chan struct{}
	running         bool
	mu              sync.Mutex
}

type SMSWorker struct {
	id          int
	queueSvc    *SMSQueueService
	stopChan    chan struct{}
	stopped     bool
}

func NewSMSQueueService(
	redisClient storage.RedisClient,
	channelRegistry *channels.ChannelRegistry,
	storage *storage.MemoryStorage,
	statusTracker *StatusTracker,
	statisticsSvc *StatisticsService,
	cfg *config.SMSQueueConfig,
) *SMSQueueService {
	return &SMSQueueService{
		redisClient:     redisClient,
		channelRegistry: channelRegistry,
		storage:         storage,
		statusTracker:   statusTracker,
		statisticsSvc:   statisticsSvc,
		cfg:             cfg,
		stopChan:        make(chan struct{}),
	}
}

func (s *SMSQueueService) Start() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running {
		return nil
	}
	s.running = true
	s.stopChan = make(chan struct{})
	s.workers = make([]*SMSWorker, s.cfg.WorkerCount)
	for i := 0; i < s.cfg.WorkerCount; i++ {
		worker := &SMSWorker{
			id:       i,
			queueSvc: s,
			stopChan: make(chan struct{}),
			stopped:  false,
		}
		s.workers[i] = worker
		s.workerWG.Add(1)
		go worker.run()
	}
	fmt.Printf("SMS Queue Service started with %d workers\n", s.cfg.WorkerCount)
	return nil
}

func (s *SMSQueueService) Stop() {
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
	fmt.Println("SMS Queue Service stopped")
}

func (s *SMSQueueService) Enqueue(task *SMSTask) error {
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

func (w *SMSWorker) run() {
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
			var task SMSTask
			err = json.Unmarshal([]byte(taskJSON), &task)
			if err != nil {
				continue
			}
			w.processTask(&task)
		}
	}
}

func (w *SMSWorker) processTask(task *SMSTask) {
	channel, exists := w.queueSvc.channelRegistry.Get(models.ChannelTypeSMS)
	if !exists {
		w.markTaskFailed(task, "SMS channel not found")
		return
	}
	notification, _ := w.queueSvc.storage.GetNotification(task.NotifyID)
	if notification != nil {
		notification.Status = models.NotifyStatusSending
		w.queueSvc.storage.UpdateNotificationStatus(
			task.NotifyID,
			models.NotifyStatusSending,
			notification.RetryCount,
		)
	}
	w.queueSvc.statusTracker.UpdateSendStatus(
		task.NotifyID,
		models.SendStatusPending,
		"",
		0,
	)
	result, err := channel.Send(task.Receiver, task.Content, task.Subject)
	if err != nil || !result.Success {
		errorMsg := ""
		if result != nil && result.Message != "" {
			errorMsg = result.Message
		} else if err != nil {
			errorMsg = err.Error()
		}
		w.markTaskFailed(task, errorMsg)
		return
	}
	w.markTaskSuccess(task)
}

func (w *SMSWorker) markTaskSuccess(task *SMSTask) {
	w.queueSvc.statusTracker.UpdateSendStatus(
		task.NotifyID,
		models.SendStatusSuccess,
		"",
		0,
	)
	w.queueSvc.statusTracker.UpdateDeliveryStatus(
		task.NotifyID,
		models.DeliveryStatusDelivered,
	)
	notification, _ := w.queueSvc.storage.GetNotification(task.NotifyID)
	if notification != nil {
		notification.Status = models.NotifyStatusSent
		w.queueSvc.storage.UpdateNotificationStatus(
			task.NotifyID,
			models.NotifyStatusSent,
			notification.RetryCount,
		)
	}
	w.queueSvc.statisticsSvc.RecordSend("sms", true)
}

func (w *SMSWorker) markTaskFailed(task *SMSTask, errorMsg string) {
	w.queueSvc.statusTracker.UpdateSendStatus(
		task.NotifyID,
		models.SendStatusFailed,
		errorMsg,
		0,
	)
	notification, _ := w.queueSvc.storage.GetNotification(task.NotifyID)
	if notification != nil {
		notification.Status = models.NotifyStatusFailed
		w.queueSvc.storage.UpdateNotificationStatus(
			task.NotifyID,
			models.NotifyStatusFailed,
			notification.RetryCount,
		)
	}
	w.queueSvc.statisticsSvc.RecordSend("sms", false)
}

func (s *SMSQueueService) GetQueueLength() (int64, error) {
	ctx := context.Background()
	items, err := s.redisClient.LRange(ctx, s.cfg.QueueName, 0, -1)
	if err != nil {
		return 0, err
	}
	return int64(len(items)), nil
}

func (s *SMSQueueService) IsRunning() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.running
}
