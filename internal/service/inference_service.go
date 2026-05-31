package service

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/edgevision/edgevision/internal/domain/model"
	"github.com/edgevision/edgevision/internal/infrastructure/cache"
	"github.com/edgevision/edgevision/internal/infrastructure/database"
	"github.com/edgevision/edgevision/internal/infrastructure/logger"
	"github.com/edgevision/edgevision/pkg/utils"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type InferenceService struct {
	db          *gorm.DB
	taskQueue   chan string
	concurrency int
}

func NewInferenceService(concurrency int) *InferenceService {
	service := &InferenceService{
		db:          database.GetDB(),
		taskQueue:   make(chan string, 1000),
		concurrency: concurrency,
	}

	for i := 0; i < concurrency; i++ {
		go service.taskWorker(i)
	}

	return service
}

type RegisterModelRequest struct {
	Name                  string                 `json:"name"`
	Version               string                 `json:"version"`
	Framework             string                 `json:"framework"`
	ModelType             string                 `json:"model_type"`
	FileSize              int64                  `json:"file_size"`
	FileURL               string                 `json:"file_url"`
	Checksum              string                 `json:"checksum"`
	InputFormat           string                 `json:"input_format"`
	OutputFormat          string                 `json:"output_format"`
	InputShape            []int                  `json:"input_shape"`
	Labels                []string               `json:"labels"`
	Threshold             float64                `json:"threshold"`
	Accuracy              float64                `json:"accuracy"`
	HardwareRequirements  map[string]interface{} `json:"hardware_requirements"`
	Metadata              map[string]interface{} `json:"metadata"`
}

func (s *InferenceService) RegisterModel(ctx context.Context, req *RegisterModelRequest) (*model.AIModel, error) {
	model := &model.AIModel{
		ID:                     utils.GenerateID("ai"),
		Name:                   req.Name,
		Version:                req.Version,
		Framework:              req.Framework,
		ModelType:              req.ModelType,
		FileSize:               req.FileSize,
		FileURL:                req.FileURL,
		Checksum:               req.Checksum,
		InputFormat:            req.InputFormat,
		OutputFormat:           req.OutputFormat,
		InputShape:             req.InputShape,
		Labels:                 req.Labels,
		Threshold:              req.Threshold,
		Accuracy:               req.Accuracy,
		HardwareRequirements:   req.HardwareRequirements,
		Metadata:               req.Metadata,
		IsActive:               true,
		CreatedAt:              utils.Now(),
		UpdatedAt:              utils.Now(),
	}

	if err := s.db.Create(model).Error; err != nil {
		logger.Get().Error("failed to register model", zap.Error(err))
		return nil, err
	}

	return model, nil
}

func (s *InferenceService) GetModel(ctx context.Context, modelID string) (*model.AIModel, error) {
	var model model.AIModel
	if err := s.db.First(&model, "id = ?", modelID).Error; err != nil {
		return nil, err
	}
	return &model, nil
}

func (s *InferenceService) ListModels(ctx context.Context, page, pageSize int, modelType string) ([]model.AIModel, int64, error) {
	var models []model.AIModel
	var total int64

	query := s.db.Model(&model.AIModel{})
	if modelType != "" {
		query = query.Where("model_type = ?", modelType)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&models).Error; err != nil {
		return nil, 0, err
	}

	return models, total, nil
}

type DeployModelRequest struct {
	ModelID         string                 `json:"model_id"`
	DeviceID        string                 `json:"device_id"`
	DeploymentType  string                 `json:"deployment_type"`
	TargetFramework string                 `json:"target_framework"`
	Accelerator     string                 `json:"accelerator"`
	InstanceCount   int                    `json:"instance_count"`
	BatchSize       int                    `json:"batch_size"`
	Parameters      map[string]interface{} `json:"parameters"`
}

func (s *InferenceService) DeployModel(ctx context.Context, req *DeployModelRequest) (*model.ModelDeployment, error) {
	var aiModel model.AIModel
	if err := s.db.First(&aiModel, "id = ?", req.ModelID).Error; err != nil {
		return nil, errors.New("model not found")
	}

	deployment := &model.ModelDeployment{
		ID:              utils.GenerateID("dep"),
		ModelID:         req.ModelID,
		DeviceID:        req.DeviceID,
		Status:          model.ModelStatusDeploying,
		DeploymentType:  req.DeploymentType,
		TargetFramework: req.TargetFramework,
		Accelerator:     req.Accelerator,
		InstanceCount:   req.InstanceCount,
		BatchSize:       req.BatchSize,
		Parameters:      req.Parameters,
		CreatedAt:       utils.Now(),
		UpdatedAt:       utils.Now(),
	}

	if err := s.db.Create(deployment).Error; err != nil {
		return nil, err
	}

	go s.processDeployment(deployment.ID)

	return deployment, nil
}

func (s *InferenceService) processDeployment(deploymentID string) {
	ctx := context.Background()

	var deployment model.ModelDeployment
	if err := s.db.First(&deployment, "id = ?", deploymentID).Error; err != nil {
		return
	}

	time.Sleep(2 * time.Second)

	now := utils.Now()
	deployment.Status = model.ModelStatusDeployed
	deployment.DeployedAt = &now
	deployment.LastHeartbeat = &now
	deployment.UpdatedAt = now

	_ = s.db.Save(&deployment)

	logger.Get().Info("model deployed successfully",
		zap.String("deployment_id", deployment.ID),
		zap.String("model_id", deployment.ModelID))

	cacheKey := fmt.Sprintf("inference:deployment:%s", deployment.DeviceID)
	_ = cache.Publish(ctx, cacheKey, utils.ToJSON(deployment))
}

type CreateInferenceTaskRequest struct {
	DeploymentID string                 `json:"deployment_id"`
	TaskType     string                 `json:"task_type"`
	InputData    string                 `json:"input_data"`
	InputSource  string                 `json:"input_source"`
	Priority     int                    `json:"priority"`
	CallbackURL  string                 `json:"callback_url"`
}

func (s *InferenceService) CreateTask(ctx context.Context, req *CreateInferenceTaskRequest) (*model.InferenceTask, error) {
	var deployment model.ModelDeployment
	if err := s.db.First(&deployment, "id = ?", req.DeploymentID).Error; err != nil {
		return nil, errors.New("deployment not found")
	}

	task := &model.InferenceTask{
		ID:            utils.GenerateID("task"),
		DeploymentID:  req.DeploymentID,
		ModelID:       deployment.ModelID,
		DeviceID:      deployment.DeviceID,
		TaskType:      req.TaskType,
		InputData:     req.InputData,
		InputSource:   req.InputSource,
		Status:        model.TaskStatusPending,
		Priority:      req.Priority,
		CallbackURL:   req.CallbackURL,
		CreatedAt:     utils.Now(),
		UpdatedAt:     utils.Now(),
	}

	if err := s.db.Create(task).Error; err != nil {
		return nil, err
	}

	s.taskQueue <- task.ID

	return task, nil
}

func (s *InferenceService) taskWorker(workerID int) {
	ctx := context.Background()
	for taskID := range s.taskQueue {
		s.executeTask(ctx, taskID, workerID)
	}
}

func (s *InferenceService) executeTask(ctx context.Context, taskID string, workerID int) {
	var task model.InferenceTask
	if err := s.db.First(&task, "id = ?", taskID).Error; err != nil {
		return
	}

	if task.Status != model.TaskStatusPending {
		return
	}

	task.Status = model.TaskStatusRunning
	now := utils.Now()
	task.StartedAt = &now
	task.UpdatedAt = now
	_ = s.db.Save(&task)

	logger.Get().Info("executing inference task",
		zap.String("task_id", task.ID),
		zap.Int("worker_id", workerID))

	startTime := time.Now()

	result, confidence := s.runInference(task)

	latencyMs := time.Since(startTime).Milliseconds()

	task.Status = model.TaskStatusCompleted
	task.Result = result
	task.Confidence = confidence
	task.LatencyMs = latencyMs
	completedAt := utils.Now()
	task.CompletedAt = &completedAt
	task.UpdatedAt = completedAt
	_ = s.db.Save(&task)

	if task.CallbackURL != "" {
		go s.sendCallback(task)
	}

	cacheKey := fmt.Sprintf("inference:task:%s", task.DeviceID)
	_ = cache.Publish(ctx, cacheKey, utils.ToJSON(task))

	logger.Get().Info("inference task completed",
		zap.String("task_id", task.ID),
		zap.Int64("latency_ms", latencyMs),
		zap.Float64("confidence", confidence))
}

func (s *InferenceService) runInference(task model.InferenceTask) (map[string]interface{}, float64) {
	time.Sleep(100 * time.Millisecond)

	result := map[string]interface{}{
		"detections": []map[string]interface{}{
			{
				"class":     "person",
				"confidence": 0.95,
				"bbox":      []float64{0.1, 0.2, 0.3, 0.4},
			},
		},
		"inference_time_ms": 45,
	}

	return result, 0.95
}

func (s *InferenceService) sendCallback(task model.InferenceTask) {
	logger.Get().Info("sending callback",
		zap.String("task_id", task.ID),
		zap.String("callback_url", task.CallbackURL))
}

func (s *InferenceService) GetTask(ctx context.Context, taskID string) (*model.InferenceTask, error) {
	var task model.InferenceTask
	if err := s.db.First(&task, "id = ?", taskID).Error; err != nil {
		return nil, err
	}
	return &task, nil
}

func (s *InferenceService) ListTasks(ctx context.Context, deploymentID, status string, page, pageSize int) ([]model.InferenceTask, int64, error) {
	var tasks []model.InferenceTask
	var total int64

	query := s.db.Model(&model.InferenceTask{})
	if deploymentID != "" {
		query = query.Where("deployment_id = ?", deploymentID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&tasks).Error; err != nil {
		return nil, 0, err
	}

	return tasks, total, nil
}

func (s *InferenceService) ListDeployments(ctx context.Context, deviceID, status string, page, pageSize int) ([]model.ModelDeployment, int64, error) {
	var deployments []model.ModelDeployment
	var total int64

	query := s.db.Model(&model.ModelDeployment{})
	if deviceID != "" {
		query = query.Where("device_id = ?", deviceID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&deployments).Error; err != nil {
		return nil, 0, err
	}

	return deployments, total, nil
}

func (s *InferenceService) UndeployModel(ctx context.Context, deploymentID string) error {
	var deployment model.ModelDeployment
	if err := s.db.First(&deployment, "id = ?", deploymentID).Error; err != nil {
		return err
	}

	deployment.Status = model.ModelStatusUndeployed
	deployment.UpdatedAt = utils.Now()

	return s.db.Save(&deployment).Error
}

func (s *InferenceService) Heartbeat(ctx context.Context, deploymentID string) error {
	var deployment model.ModelDeployment
	if err := s.db.First(&deployment, "id = ?", deploymentID).Error; err != nil {
		return err
	}

	now := utils.Now()
	deployment.LastHeartbeat = &now
	return s.db.Save(&deployment).Error
}

func (s *InferenceService) GetStats(ctx context.Context, deploymentID string) (map[string]interface{}, error) {
	var totalTasks, completedTasks, failedTasks int64
	var avgLatency float64

	query := s.db.Model(&model.InferenceTask{}).Where("deployment_id = ?", deploymentID)
	query.Count(&totalTasks)
	query.Where("status = ?", model.TaskStatusCompleted).Count(&completedTasks)
	query.Where("status = ?", model.TaskStatusFailed).Count(&failedTasks)

	rows, err := s.db.Model(&model.InferenceTask{}).
		Where("deployment_id = ? AND status = ?", deploymentID, model.TaskStatusCompleted).
		Select("AVG(latency_ms)").Rows()
	if err == nil && rows.Next() {
		rows.Scan(&avgLatency)
		rows.Close()
	}

	return map[string]interface{}{
		"total_tasks":     totalTasks,
		"completed_tasks": completedTasks,
		"failed_tasks":    failedTasks,
		"avg_latency_ms":  avgLatency,
	}, nil
}
