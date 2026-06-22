package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/featureflag/platform/internal/config"
	"github.com/featureflag/platform/internal/dao"
	"github.com/featureflag/platform/internal/model"
	"github.com/featureflag/platform/pkg/logger"
	"github.com/featureflag/platform/pkg/utils"
	"github.com/segmentio/kafka-go"
)

type KafkaProducer struct {
	writer *kafka.Writer
	topic  string
	mu     sync.Mutex
}

func NewKafkaProducer() *KafkaProducer {
	cfg := config.AppConfig.Kafka
	writer := &kafka.Writer{
		Addr:     kafka.TCP(cfg.Brokers...),
		Topic:    cfg.Topic,
		Balancer: &kafka.LeastBytes{},
		Async:    true,
		Completion: func(messages []kafka.Message, err error) {
			if err != nil {
				logger.Errorf("kafka send messages error: %v", err)
			}
		},
	}
	return &KafkaProducer{
		writer: writer,
		topic:  cfg.Topic,
	}
}

func (p *KafkaProducer) SendEvent(event *model.ChangeEvent) {
	if p == nil || p.writer == nil {
		return
	}

	data, err := json.Marshal(event)
	if err != nil {
		logger.Errorf("marshal event error: %v", err)
		return
	}

	msg := kafka.Message{
		Key:   []byte(event.SwitchID),
		Value: data,
		Headers: []kafka.Header{
			{Key: "event_type", Value: []byte(event.EventType)},
			{Key: "timestamp", Value: []byte(event.Timestamp.Format(time.RFC3339))},
		},
		Time: event.Timestamp,
	}

	p.mu.Lock()
	defer p.mu.Unlock()

	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := p.writer.WriteMessages(ctx, msg); err != nil {
			logger.Warnf("send kafka message error: %v", err)
		}
	}()
}

func (p *KafkaProducer) Close() {
	if p != nil && p.writer != nil {
		if err := p.writer.Close(); err != nil {
			logger.Errorf("close kafka writer error: %v", err)
		}
	}
}

type AutoRollbackService struct {
	switchDAO    *dao.SwitchDAO
	statsDAO     *dao.StatsDAO
	historyDAO   *dao.HistoryDAO
	switchService *SwitchService
	kafkaProducer *KafkaProducer
	stopCh       chan struct{}
	running      bool
	mu           sync.Mutex
}

func NewAutoRollbackService() *AutoRollbackService {
	return &AutoRollbackService{
		switchDAO:  dao.NewSwitchDAO(),
		statsDAO:   dao.NewStatsDAO(),
		historyDAO: dao.NewHistoryDAO(),
		stopCh:     make(chan struct{}),
	}
}

func (s *AutoRollbackService) SetSwitchService(ss *SwitchService) {
	s.switchService = ss
}

func (s *AutoRollbackService) SetKafkaProducer(p *KafkaProducer) {
	s.kafkaProducer = p
}

func (s *AutoRollbackService) Start() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.running {
		return
	}
	s.running = true

	interval := time.Duration(config.AppConfig.AutoRollback.CheckInterval) * time.Second
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		logger.Infof("auto rollback service started, check interval: %v", interval)

		for {
			select {
			case <-ticker.C:
				s.checkAndRollback()
			case <-s.stopCh:
				logger.Info("auto rollback service stopped")
				return
			}
		}
	}()
}

func (s *AutoRollbackService) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.running {
		return
	}
	s.running = false
	close(s.stopCh)
}

func (s *AutoRollbackService) checkAndRollback() {
	ctx := context.Background()
	windowMinutes := config.AppConfig.AutoRollback.WindowMinutes

	switches, err := s.switchDAO.GetAllEnabled(ctx)
	if err != nil {
		logger.Errorf("get all enabled switches error: %v", err)
		return
	}

	for _, sw := range switches {
		if !sw.AutoRollbackEnabled {
			continue
		}

		stats, err := s.statsDAO.GetRecentStats(ctx, sw.ID, windowMinutes)
		if err != nil {
			logger.Warnf("get recent stats for switch %s error: %v", sw.Key, err)
			continue
		}

		if stats.TotalEvaluations == 0 {
			continue
		}

		errorRate := float64(stats.ErrorCount) / float64(stats.TotalEvaluations) * 100
		threshold := sw.AutoRollbackThreshold
		if threshold <= 0 {
			threshold = config.AppConfig.AutoRollback.CheckInterval
		}

		if errorRate > threshold {
			logger.Warnf("auto rollback triggered for switch %s, error rate: %.2f%%, threshold: %.2f%%",
				sw.Key, errorRate, threshold)

			_, err := s.switchService.Disable(ctx, sw.ID, "auto-rollback")
			if err != nil {
				logger.Errorf("auto rollback switch %s error: %v", sw.Key, err)
				continue
			}

			if s.kafkaProducer != nil {
				event := &model.ChangeEvent{
					EventType: model.EventAutoRollback,
					SwitchID:  sw.ID,
					SwitchKey: sw.Key,
					Operator:  "auto-rollback",
					Timestamp: time.Now(),
					Data: map[string]interface{}{
						"error_rate":   errorRate,
						"threshold":    threshold,
						"total_count":  stats.TotalEvaluations,
						"error_count":  stats.ErrorCount,
					},
				}
				s.kafkaProducer.SendEvent(event)
			}
		}
	}
}

type ScheduleService struct {
	taskDAO       *dao.ScheduledTaskDAO
	switchService *SwitchService
	stopCh        chan struct{}
	running       bool
	mu            sync.Mutex
}

func NewScheduleService() *ScheduleService {
	return &ScheduleService{
		taskDAO: dao.NewScheduledTaskDAO(),
		stopCh:  make(chan struct{}),
	}
}

func (s *ScheduleService) SetSwitchService(ss *SwitchService) {
	s.switchService = ss
}

func (s *ScheduleService) Start() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.running {
		return
	}
	s.running = true

	go func() {
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()

		logger.Info("schedule service started")

		for {
			select {
			case <-ticker.C:
				s.executeTasks()
			case <-s.stopCh:
				logger.Info("schedule service stopped")
				return
			}
		}
	}()
}

func (s *ScheduleService) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.running {
		return
	}
	s.running = false
	close(s.stopCh)
}

func (s *ScheduleService) CreateTask(ctx context.Context, req *model.ScheduleRequest, operator string) (*model.ScheduledTask, error) {
	if req.ExecuteAt.Before(time.Now()) {
		return nil, errors.New("execute time must be in the future")
	}

	task := &model.ScheduledTask{
		ID:            utils.GenerateUUID(),
		SwitchID:      req.SwitchID,
		TaskType:      req.TaskType,
		TargetEnabled: req.TargetEnabled,
		ExecuteAt:     req.ExecuteAt,
		Status:        "PENDING",
		CreatedBy:     operator,
		CreatedAt:     time.Now(),
	}

	err := s.taskDAO.Create(ctx, task)
	if err != nil {
		return nil, err
	}
	return task, nil
}

func (s *ScheduleService) executeTasks() {
	ctx := context.Background()

	tasks, err := s.taskDAO.GetPendingTasks(ctx, 100)
	if err != nil {
		logger.Errorf("get pending tasks error: %v", err)
		return
	}

	for _, task := range tasks {
		func(t *model.ScheduledTask) {
			defer func() {
				if r := recover(); r != nil {
					logger.Errorf("execute task panic: %v", r)
					_ = s.taskDAO.MarkExecuted(ctx, t.ID, false, fmt.Sprintf("panic: %v", r))
				}
			}()

			var err error
			if t.TargetEnabled {
				_, err = s.switchService.Enable(ctx, t.SwitchID, t.CreatedBy)
			} else {
				_, err = s.switchService.Disable(ctx, t.SwitchID, t.CreatedBy)
			}

			errMsg := ""
			if err != nil {
				errMsg = err.Error()
				logger.Errorf("execute scheduled task %s error: %v", t.ID, err)
			}

			markErr := s.taskDAO.MarkExecuted(ctx, t.ID, err == nil, errMsg)
			if markErr != nil {
				logger.Errorf("mark task executed error: %v", markErr)
			}
		}(task)
	}
}

func (s *ScheduleService) ListTasks(ctx context.Context, switchID string, page, pageSize int) (*model.ListResponse, error) {
	tasks, total, err := s.taskDAO.ListBySwitchID(ctx, switchID, page, pageSize)
	if err != nil {
		return nil, err
	}
	return &model.ListResponse{
		Data: tasks,
		Pagination: model.Pagination{
			Page:     page,
			PageSize: pageSize,
			Total:    total,
		},
	}, nil
}

type StatsService struct {
	statsDAO       *dao.StatsDAO
	switchDAO      *dao.SwitchDAO
	integrationDAO *dao.IntegrationDAO
}

func NewStatsService() *StatsService {
	return &StatsService{
		statsDAO:       dao.NewStatsDAO(),
		switchDAO:      dao.NewSwitchDAO(),
		integrationDAO: dao.NewIntegrationDAO(),
	}
}

func (s *StatsService) ReportStats(ctx context.Context, req *model.StatsReportRequest) error {
	sw, err := s.switchDAO.GetByKey(ctx, req.SwitchKey)
	if err != nil {
		logger.Warnf("report stats: switch %s not found", req.SwitchKey)
		return nil
	}

	stat := &model.SwitchStats{
		ID:              utils.GenerateUUID(),
		SwitchID:        sw.ID,
		Date:            time.Now().Format("2006-01-02"),
		TotalEvaluations: req.TotalCount,
		TrueCount:       req.TrueCount,
		FalseCount:      req.FalseCount,
		ErrorCount:      req.ErrorCount,
		AvgLatencyMs:    req.AvgLatencyMs,
		P99LatencyMs:    req.P99LatencyMs,
		CreatedAt:       time.Now(),
		UpdatedAt:       time.Now(),
	}

	err = s.statsDAO.Upsert(ctx, stat)
	if err != nil {
		return err
	}

	if req.ServiceName != "" {
		integration := &model.SwitchIntegration{
			ID:         utils.GenerateUUID(),
			SwitchID:   sw.ID,
			ServiceName: req.ServiceName,
			SDKVersion: req.SDKVersion,
			CreatedAt:  time.Now(),
			UpdatedAt:  time.Now(),
		}
		_ = s.integrationDAO.Upsert(ctx, integration)
	}

	return nil
}

func (s *StatsService) GetSwitchStats(ctx context.Context, switchID string, startDate, endDate string) ([]*model.SwitchStats, error) {
	return s.statsDAO.GetBySwitchID(ctx, switchID, startDate, endDate)
}

func (s *StatsService) GetStatsSummary(ctx context.Context, switchID string) (*model.StatsSummary, error) {
	return s.statsDAO.GetSummary(ctx, switchID)
}

func (s *StatsService) GetIntegrations(ctx context.Context, switchID string) ([]*model.SwitchIntegration, error) {
	return s.integrationDAO.GetBySwitchID(ctx, switchID)
}
