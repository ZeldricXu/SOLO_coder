package edge_inference

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	"edgescheduler/internal/common/database"
	"edgescheduler/internal/common/eventbus"
	"edgescheduler/internal/common/logger"
	"edgescheduler/pkg/utils"
)

type InferenceService interface {
	RegisterModel(ctx context.Context, model *AIModel) (*AIModel, error)
	DeployModel(ctx context.Context, req *ModelDeployRequest) (*ModelDeployment, error)
	CreateInferenceTask(ctx context.Context, req *InferenceRequest) (*InferenceTask, error)
	ProcessTaskResult(ctx context.Context, taskID string, result map[string]interface{}, err error) error
	GetTask(ctx context.Context, taskID string) (*InferenceTask, error)
	ListTasks(ctx context.Context, filters map[string]interface{}, offset, limit int) ([]InferenceTask, int64, error)
	GetModel(ctx context.Context, modelID string) (*AIModel, error)
	ListModels(ctx context.Context, offset, limit int) ([]AIModel, int64, error)
	StartTaskScheduler(ctx context.Context, maxConcurrent int)
}

type inferenceServiceImpl struct {
	db       *gorm.DB
	eventBus eventbus.EventBus
	taskCh   chan *InferenceTask
	wg       sync.WaitGroup
}

func NewInferenceService() InferenceService {
	return &inferenceServiceImpl{
		db:       database.GetDB(),
		eventBus: eventbus.GetEventBus(),
		taskCh:   make(chan *InferenceTask, 1000),
	}
}

func NewInferenceServiceWithDeps(db *gorm.DB, eb eventbus.EventBus) InferenceService {
	return &inferenceServiceImpl{
		db:       db,
		eventBus: eb,
		taskCh:   make(chan *InferenceTask, 1000),
	}
}

func (s *inferenceServiceImpl) RegisterModel(ctx context.Context, model *AIModel) (*AIModel, error) {
	logger.Info("Registering AI model", zap.String("model_id", model.ModelID))

	var existing AIModel
	result := s.db.Where("model_id = ?", model.ModelID).First(&existing)
	if result.Error == nil {
		return nil, errors.New("model already registered")
	}
	if !errors.Is(result.Error, gorm.ErrRecordNotFound) {
		return nil, result.Error
	}

	model.Status = ModelStatusPending
	model.ModelID = utils.GenerateID("model")

	if err := s.db.Create(model).Error; err != nil {
		return nil, fmt.Errorf("failed to register model: %w", err)
	}

	logger.Info("AI model registered successfully", zap.String("model_id", model.ModelID))
	return model, nil
}

func (s *inferenceServiceImpl) DeployModel(ctx context.Context, req *ModelDeployRequest) (*ModelDeployment, error) {
	logger.Info("Deploying model",
		zap.String("model_id", req.ModelID),
		zap.String("device_id", req.DeviceID),
	)

	var model AIModel
	if err := s.db.Where("model_id = ?", req.ModelID).First(&model).Error; err != nil {
		return nil, errors.New("model not found")
	}

	deployment := &ModelDeployment{
		DeploymentID: utils.GenerateID("deploy"),
		ModelID:      req.ModelID,
		DeviceID:     req.DeviceID,
		Status:       ModelStatusDeploying,
	}

	if err := s.db.Create(deployment).Error; err != nil {
		return nil, fmt.Errorf("failed to create deployment: %w", err)
	}

	go s.simulateDeployment(ctx, deployment, &model)

	return deployment, nil
}

func (s *inferenceServiceImpl) simulateDeployment(ctx context.Context, deployment *ModelDeployment, model *AIModel) {
	time.Sleep(2 * time.Second)

	now := time.Now().UTC()
	deployment.Status = ModelStatusDeployed
	deployment.DeployedAt = &now

	s.db.Save(deployment)

	s.eventBus.Publish(ctx, eventbus.EventInferenceTaskCreated, map[string]interface{}{
		"deployment_id": deployment.DeploymentID,
		"model_id":      deployment.ModelID,
		"device_id":     deployment.DeviceID,
		"status":        deployment.Status,
	}, "edge_inference")

	logger.Info("Model deployed successfully",
		zap.String("deployment_id", deployment.DeploymentID),
		zap.String("model_id", model.ModelID),
	)
}

func (s *inferenceServiceImpl) CreateInferenceTask(ctx context.Context, req *InferenceRequest) (*InferenceTask, error) {
	logger.Info("Creating inference task",
		zap.String("model_id", req.ModelID),
		zap.String("device_id", req.DeviceID),
	)

	task := &InferenceTask{
		TaskID:      utils.GenerateID("task"),
		ModelID:     req.ModelID,
		DeviceID:    req.DeviceID,
		InputData:   req.InputData,
		Status:      TaskStatusPending,
		Priority:    req.Priority,
		CallbackURL: req.CallbackURL,
	}

	if err := s.db.Create(task).Error; err != nil {
		return nil, fmt.Errorf("failed to create task: %w", err)
	}

	s.eventBus.Publish(ctx, eventbus.EventInferenceTaskCreated, map[string]interface{}{
		"task_id":   task.TaskID,
		"model_id":  task.ModelID,
		"device_id": task.DeviceID,
	}, "edge_inference")

	s.taskCh <- task

	logger.Info("Inference task created", zap.String("task_id", task.TaskID))
	return task, nil
}

func (s *inferenceServiceImpl) ProcessTaskResult(ctx context.Context, taskID string, result map[string]interface{}, taskErr error) error {
	var task InferenceTask
	if err := s.db.Where("task_id = ?", taskID).First(&task).Error; err != nil {
		return errors.New("task not found")
	}

	now := time.Now().UTC()
	task.CompletedAt = &now

	if taskErr != nil {
		task.Status = TaskStatusFailed
		task.ErrorDetail = taskErr.Error()

		s.eventBus.Publish(ctx, eventbus.EventInferenceTaskFailed, map[string]interface{}{
			"task_id":      task.TaskID,
			"error_detail": task.ErrorDetail,
		}, "edge_inference")
	} else {
		task.Status = TaskStatusCompleted
		task.OutputData = result

		s.eventBus.Publish(ctx, eventbus.EventInferenceTaskCompleted, map[string]interface{}{
			"task_id":  task.TaskID,
			"latency":  task.LatencyMs,
			"device_id": task.DeviceID,
		}, "edge_inference")
	}

	return s.db.Save(&task).Error
}

func (s *inferenceServiceImpl) GetTask(ctx context.Context, taskID string) (*InferenceTask, error) {
	var task InferenceTask
	if err := s.db.Where("task_id = ?", taskID).First(&task).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("task not found")
		}
		return nil, err
	}
	return &task, nil
}

func (s *inferenceServiceImpl) ListTasks(ctx context.Context, filters map[string]interface{}, offset, limit int) ([]InferenceTask, int64, error) {
	var tasks []InferenceTask
	var total int64

	query := s.db.Model(&InferenceTask{})

	if status, ok := filters["status"].(string); ok && status != "" {
		query = query.Where("status = ?", status)
	}
	if deviceID, ok := filters["device_id"].(string); ok && deviceID != "" {
		query = query.Where("device_id = ?", deviceID)
	}
	if modelID, ok := filters["model_id"].(string); ok && modelID != "" {
		query = query.Where("model_id = ?", modelID)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("created_at DESC").Offset(offset).Limit(limit).Find(&tasks).Error; err != nil {
		return nil, 0, err
	}

	return tasks, total, nil
}

func (s *inferenceServiceImpl) GetModel(ctx context.Context, modelID string) (*AIModel, error) {
	var model AIModel
	if err := s.db.Where("model_id = ?", modelID).First(&model).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("model not found")
		}
		return nil, err
	}
	return &model, nil
}

func (s *inferenceServiceImpl) ListModels(ctx context.Context, offset, limit int) ([]AIModel, int64, error) {
	var models []AIModel
	var total int64

	if err := s.db.Model(&AIModel{}).Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := s.db.Order("created_at DESC").Offset(offset).Limit(limit).Find(&models).Error; err != nil {
		return nil, 0, err
	}

	return models, total, nil
}

func (s *inferenceServiceImpl) StartTaskScheduler(ctx context.Context, maxConcurrent int) {
	logger.Info("Starting inference task scheduler", zap.Int("max_concurrent", maxConcurrent))

	sem := make(chan struct{}, maxConcurrent)

	for {
		select {
		case <-ctx.Done():
			logger.Info("Task scheduler stopped")
			return
		case task := <-s.taskCh:
			sem <- struct{}{}
			go func(t *InferenceTask) {
				defer func() { <-sem }()
				s.executeTask(ctx, t)
			}(task)
		}
	}
}

func (s *inferenceServiceImpl) executeTask(ctx context.Context, task *InferenceTask) {
	startTime := time.Now()

	task.Status = TaskStatusRunning
	task.StartedAt = &startTime
	s.db.Save(task)

	logger.Info("Executing inference task",
		zap.String("task_id", task.TaskID),
		zap.String("model_id", task.ModelID),
	)

	time.Sleep(500 * time.Millisecond)

	latency := time.Since(startTime).Milliseconds()
	task.LatencyMs = latency

	mockResult := map[string]interface{}{
		"predictions": []map[string]interface{}{
			{"class": "object_1", "confidence": 0.95},
			{"class": "object_2", "confidence": 0.87},
		},
		"inference_time_ms": latency,
		"model_version": "v1.0",
	}

	s.ProcessTaskResult(ctx, task.TaskID, mockResult, nil)
}
