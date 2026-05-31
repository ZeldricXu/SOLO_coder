package inference

import (
	"context"
	"sync"
	"time"

	"github.com/edgeplatform/session306/internal/data"
	"github.com/edgeplatform/session306/internal/model"
	"github.com/edgeplatform/session306/pkg/errors"
	"github.com/edgeplatform/session306/pkg/events"
	"github.com/edgeplatform/session306/pkg/utils"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

type InferenceExecutor interface {
	Execute(ctx context.Context, task *model.InferenceTask, model *model.AIModel) (string, error)
}

type LocalInferenceExecutor struct {
	logger *zap.Logger
}

func (e *LocalInferenceExecutor) Execute(ctx context.Context, task *model.InferenceTask, model *model.AIModel) (string, error) {
	e.logger.Debug("Executing inference locally",
		zap.String("task_id", task.TaskID),
		zap.String("model_id", model.ModelID),
	)
	return `{"result": "mock_inference_result", "confidence": 0.95}`, nil
}

type InferenceScheduler struct {
	da             *data.DataAccess
	eventBus       events.EventBus
	logger         *zap.Logger
	executor       InferenceExecutor
	taskQueue      chan *model.InferenceTask
	workerCount    int
	deviceModels   map[string]map[string]*model.AIModel
	mu             sync.RWMutex
	pollingInterval time.Duration
}

func NewInferenceScheduler(da *data.DataAccess, eb events.EventBus, log *zap.Logger, workerCount int) *InferenceScheduler {
	if workerCount <= 0 {
		workerCount = 3
	}
	return &InferenceScheduler{
		da:              da,
		eventBus:        eb,
		logger:          log,
		executor:        &LocalInferenceExecutor{logger: log},
		taskQueue:       make(chan *model.InferenceTask, 1000),
		workerCount:     workerCount,
		deviceModels:    make(map[string]map[string]*model.AIModel),
		pollingInterval: 5 * time.Second,
	}
}

func (s *InferenceScheduler) Start(ctx context.Context) error {
	if err := s.loadModels(ctx); err != nil {
		return err
	}

	for i := 0; i < s.workerCount; i++ {
		go s.worker(ctx, i)
	}

	go s.pollPendingTasks(ctx)

	s.logger.Info("Inference scheduler started", zap.Int("workers", s.workerCount))
	return nil
}

func (s *InferenceScheduler) loadModels(ctx context.Context) error {
	var models []model.AIModel
	if err := s.da.DB().WithContext(ctx).Where("status = ?", model.ModelStatusReady).Find(&models).Error; err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal, "failed to load models")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	s.deviceModels = make(map[string]map[string]*model.AIModel)
	for i := range models {
		m := &models[i]
		for _, deviceID := range m.DeviceIDs {
			if _, ok := s.deviceModels[deviceID]; !ok {
				s.deviceModels[deviceID] = make(map[string]*model.AIModel)
			}
			s.deviceModels[deviceID][m.ModelID] = m
		}
	}

	s.logger.Info("AI models loaded", zap.Int("count", len(models)))
	return nil
}

func (s *InferenceScheduler) RegisterModel(ctx context.Context, aiModel *model.AIModel) (*model.AIModel, error) {
	aiModel.ModelID = utils.GenerateID("model")
	aiModel.Status = model.ModelStatusPending
	aiModel.CreatedAt = utils.NowUTC()
	aiModel.UpdatedAt = utils.NowUTC()

	if err := s.da.DB().WithContext(ctx).Create(aiModel).Error; err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to register model")
	}

	s.logger.Info("AI model registered",
		zap.String("model_id", aiModel.ModelID),
		zap.String("name", aiModel.Name),
		zap.String("version", aiModel.Version),
	)
	return aiModel, nil
}

func (s *InferenceScheduler) GetModel(ctx context.Context, modelID string) (*model.AIModel, error) {
	var aiModel model.AIModel
	err := s.da.DB().WithContext(ctx).Where("model_id = ?", modelID).First(&aiModel).Error
	if err == gorm.ErrRecordNotFound {
		return nil, errors.NewNotFoundError("model not found")
	}
	return &aiModel, err
}

func (s *InferenceScheduler) ListModels(ctx context.Context, status model.ModelStatus, offset, limit int) ([]model.AIModel, int64, error) {
	var models []model.AIModel
	var total int64

	query := s.da.DB().WithContext(ctx).Model(&model.AIModel{})
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if limit > 0 {
		query = query.Offset(offset).Limit(limit)
	}
	err := query.Order("created_at DESC").Find(&models).Error
	return models, total, err
}

func (s *InferenceScheduler) DeployModel(ctx context.Context, req *model.ModelDeployRequest) error {
	aiModel, err := s.GetModel(ctx, req.ModelID)
	if err != nil {
		return err
	}

	now := utils.NowUTC()
	err = s.da.DB().WithContext(ctx).Model(aiModel).
		Updates(map[string]interface{}{
			"status":      model.ModelStatusDeploying,
			"device_ids":  req.DeviceIDs,
			"deployed_at": now,
			"updated_at":  now,
		}).Error
	if err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal, "failed to deploy model")
	}

	go func() {
		time.Sleep(2 * time.Second)

		s.mu.Lock()
		for _, deviceID := range req.DeviceIDs {
			if _, ok := s.deviceModels[deviceID]; !ok {
				s.deviceModels[deviceID] = make(map[string]*model.AIModel)
			}
			aiModel.Status = model.ModelStatusReady
			s.deviceModels[deviceID][aiModel.ModelID] = aiModel
		}
		s.mu.Unlock()

		s.da.DB().WithContext(context.Background()).Model(aiModel).
			Update("status", model.ModelStatusReady)

		event := events.Event{
			ID:        utils.GenerateID("evt"),
			Type:      events.EventInferenceReady,
			Source:    "inference_scheduler",
			Timestamp: utils.NowUTC(),
			TraceID:   ctx.Value("trace_id").(string),
			Payload: map[string]interface{}{
				"model_id":   aiModel.ModelID,
				"device_ids": req.DeviceIDs,
			},
		}
		_ = s.eventBus.Publish(context.Background(), event)

		s.logger.Info("AI model deployed",
			zap.String("model_id", aiModel.ModelID),
			zap.Strings("device_ids", req.DeviceIDs),
		)
	}()

	return nil
}

func (s *InferenceScheduler) SubmitTask(ctx context.Context, req *model.InferenceRequest) (*model.InferenceTask, error) {
	s.mu.RLock()
	deviceModels, ok := s.deviceModels[req.DeviceID]
	s.mu.RUnlock()

	if !ok {
		return nil, errors.NewNotFoundError("no models deployed on device")
	}

	if _, ok := deviceModels[req.ModelID]; !ok {
		return nil, errors.NewNotFoundError("model not deployed on device")
	}

	task := &model.InferenceTask{
		TaskID:         utils.GenerateID("inf"),
		ModelID:        req.ModelID,
		DeviceID:       req.DeviceID,
		Status:         model.InferenceStatusQueued,
		InputData:      req.InputData,
		InputFormat:    req.InputFormat,
		OutputFormat:   req.OutputFormat,
		Priority:       req.Priority,
		TimeoutSeconds: req.TimeoutSeconds,
		CallbackURL:    req.CallbackURL,
		CreatedAt:      utils.NowUTC(),
		UpdatedAt:      utils.NowUTC(),
	}

	if task.InputFormat == "" {
		task.InputFormat = "json"
	}
	if task.OutputFormat == "" {
		task.OutputFormat = "json"
	}
	if task.TimeoutSeconds <= 0 {
		task.TimeoutSeconds = 300
	}

	if err := s.da.DB().WithContext(ctx).Create(task).Error; err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to create inference task")
	}

	select {
	case s.taskQueue <- task:
	default:
		s.logger.Warn("Inference task queue full, will poll later",
			zap.String("task_id", task.TaskID),
		)
	}

	s.logger.Info("Inference task submitted",
		zap.String("task_id", task.TaskID),
		zap.String("model_id", req.ModelID),
		zap.String("device_id", req.DeviceID),
	)
	return task, nil
}

func (s *InferenceScheduler) GetTask(ctx context.Context, taskID string) (*model.InferenceTask, error) {
	var task model.InferenceTask
	err := s.da.DB().WithContext(ctx).Where("task_id = ?", taskID).First(&task).Error
	if err == gorm.ErrRecordNotFound {
		return nil, errors.NewNotFoundError("inference task not found")
	}
	return &task, err
}

func (s *InferenceScheduler) ListTasks(ctx context.Context, deviceID string, status model.InferenceStatus, offset, limit int) ([]model.InferenceTask, int64, error) {
	var tasks []model.InferenceTask
	var total int64

	query := s.da.DB().WithContext(ctx).Model(&model.InferenceTask{})
	if deviceID != "" {
		query = query.Where("device_id = ?", deviceID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if limit > 0 {
		query = query.Offset(offset).Limit(limit)
	}
	err := query.Order("priority DESC, created_at ASC").Find(&tasks).Error
	return tasks, total, err
}

func (s *InferenceScheduler) CancelTask(ctx context.Context, taskID string) error {
	now := utils.NowUTC()
	err := s.da.DB().WithContext(ctx).Model(&model.InferenceTask{}).
		Where("task_id = ? AND status IN (?, ?)", taskID, model.InferenceStatusQueued, model.InferenceStatusRunning).
		Updates(map[string]interface{}{
			"status":     model.InferenceStatusCancelled,
			"end_time":   now,
			"updated_at": now,
		}).Error
	if err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal, "failed to cancel task")
	}

	s.logger.Info("Inference task cancelled", zap.String("task_id", taskID))
	return nil
}

func (s *InferenceScheduler) worker(ctx context.Context, workerID int) {
	s.logger.Debug("Inference worker started", zap.Int("worker_id", workerID))

	for {
		select {
		case <-ctx.Done():
			s.logger.Debug("Inference worker stopped", zap.Int("worker_id", workerID))
			return
		case task := <-s.taskQueue:
			s.processTask(ctx, task)
		}
	}
}

func (s *InferenceScheduler) processTask(ctx context.Context, task *model.InferenceTask) {
	if task.Status != model.InferenceStatusQueued {
		return
	}

	s.mu.RLock()
	aiModel := s.deviceModels[task.DeviceID][task.ModelID]
	s.mu.RUnlock()

	if aiModel == nil {
		s.completeTask(ctx, task, nil, errors.NewNotFoundError("model not found on device"))
		return
	}

	now := utils.NowUTC()
	s.da.DB().WithContext(ctx).Model(task).Updates(map[string]interface{}{
		"status":     model.InferenceStatusRunning,
		"start_time": now,
		"updated_at": now,
	})

	taskCtx, cancel := context.WithTimeout(ctx, time.Duration(task.TimeoutSeconds)*time.Second)
	defer cancel()

	start := time.Now()
	output, err := s.executor.Execute(taskCtx, task, aiModel)
	duration := time.Since(start).Milliseconds()

	s.completeTask(ctx, task, &output, err)

	eventPayload := map[string]interface{}{
		"task_id":     task.TaskID,
		"model_id":    task.ModelID,
		"device_id":   task.DeviceID,
		"duration_ms": duration,
		"success":     err == nil,
	}
	if err == nil {
		eventPayload["output"] = output
	} else {
		eventPayload["error"] = err.Error()
	}

	event := events.Event{
		ID:        utils.GenerateID("evt"),
		Type:      events.EventInferenceResult,
		Source:    "inference_scheduler",
		Timestamp: utils.NowUTC(),
		TraceID:   task.TraceID,
		Payload:   eventPayload,
	}
	_ = s.eventBus.Publish(ctx, event)

	s.logger.Info("Inference task completed",
		zap.String("task_id", task.TaskID),
		zap.Int64("duration_ms", duration),
		zap.Bool("success", err == nil),
	)
}

func (s *InferenceScheduler) completeTask(ctx context.Context, task *model.InferenceTask, output *string, err error) {
	now := utils.NowUTC()
	duration := int64(time.Since(task.CreatedAt).Milliseconds())

	updates := map[string]interface{}{
		"end_time":    now,
		"updated_at":  now,
		"duration_ms": duration,
		"result_synced": false,
	}

	if err != nil {
		updates["status"] = model.InferenceStatusFailed
		errStr := err.Error()
		updates["error"] = errStr
	} else {
		updates["status"] = model.InferenceStatusCompleted
		if output != nil {
			updates["output_data"] = *output
		}
	}

	s.da.DB().WithContext(ctx).Model(task).Updates(updates)
}

func (s *InferenceScheduler) pollPendingTasks(ctx context.Context) {
	ticker := time.NewTicker(s.pollingInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.fetchPendingTasks(ctx)
		}
	}
}

func (s *InferenceScheduler) fetchPendingTasks(ctx context.Context) {
	var tasks []model.InferenceTask
	err := s.da.DB().WithContext(ctx).
		Where("status = ?", model.InferenceStatusQueued).
		Order("priority DESC, created_at ASC").
		Limit(100).
		Find(&tasks).Error
	if err != nil {
		s.logger.Warn("Failed to fetch pending tasks", zap.Error(err))
		return
	}

	for i := range tasks {
		select {
		case s.taskQueue <- &tasks[i]:
		default:
			break
		}
	}
}

func (s *InferenceScheduler) Stop() {
	close(s.taskQueue)
	s.logger.Info("Inference scheduler stopped")
}
