package webhook

import (
	"context"
	"fmt"
	"io"
	"model-inference-platform/internal/model"
	"model-inference-platform/internal/orchestrator"
	"model-inference-platform/internal/pkg/config"
	"model-inference-platform/internal/pkg/database"
	"net/http"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"
)

type TrainingPlatformPayload struct {
	TaskID          string                 `json:"task_id" binding:"required"`
	ModelName       string                 `json:"model_name" binding:"required"`
	Version         string                 `json:"version" binding:"required"`
	Namespace       string                 `json:"namespace" binding:"required"`
	ModelFormat     model.ModelFormat      `json:"model_format" binding:"required"`
	ModelURL        string                 `json:"model_url" binding:"required"`
	Checksum        string                 `json:"checksum"`
	TrainingMetrics map[string]interface{} `json:"training_metrics"`
	Dataset         string                 `json:"dataset"`
	TrainingUser    string                 `json:"training_user"`
	AutoDeploy      bool                   `json:"auto_deploy"`
	DeployEnv       string                 `json:"deploy_env"`
	CreatedAt       time.Time              `json:"created_at"`
}

type WebhookResponse struct {
	Success   bool      `json:"success"`
	Message   string    `json:"message"`
	ModelID   string    `json:"model_id,omitempty"`
	VersionID string    `json:"version_id,omitempty"`
	TaskID    string    `json:"task_id"`
	CreatedAt time.Time `json:"created_at"`
}

type WebhookTaskStatus string

const (
	TaskStatusPending   WebhookTaskStatus = "pending"
	TaskStatusRunning   WebhookTaskStatus = "running"
	TaskStatusSuccess   WebhookTaskStatus = "success"
	TaskStatusFailed    WebhookTaskStatus = "failed"
)

type WebhookTask struct {
	ID          string                 `json:"id"`
	TaskID      string                 `json:"task_id"`
	Payload     TrainingPlatformPayload `json:"payload"`
	Status      WebhookTaskStatus      `json:"status"`
	ErrorMessage string                `json:"error_message,omitempty"`
	ModelID     string                 `json:"model_id,omitempty"`
	VersionID   string                 `json:"version_id,omitempty"`
	CreatedAt   time.Time              `json:"created_at"`
	StartedAt   *time.Time             `json:"started_at,omitempty"`
	CompletedAt *time.Time             `json:"completed_at,omitempty"`
}

type WebhookConfig struct {
	Enabled           bool          `yaml:"enabled"`
	AuthToken         string        `yaml:"auth_token"`
	AutoDeployDefault bool          `yaml:"auto_deploy_default"`
	DefaultDeployEnv  string        `yaml:"default_deploy_env"`
	DownloadTimeout   time.Duration `yaml:"download_timeout"`
	MaxModelSizeMB    int64         `yaml:"max_model_size_mb"`
}

type WebhookManager struct {
	cfg          config.WebhookConfig
	db           *database.Database
	modelRepo    *model.Repository
	orchestrator *orchestrator.Orchestrator
	logger       *zap.Logger

	tasks   map[string]*WebhookTask
	tasksMu chan struct{}

	httpClient *http.Client
}

func NewWebhookManager(cfg config.WebhookConfig, db *database.Database,
	modelRepo *model.Repository, orch *orchestrator.Orchestrator,
	logger *zap.Logger) *WebhookManager {

	return &WebhookManager{
		cfg:          cfg,
		db:           db,
		modelRepo:    modelRepo,
		orchestrator: orch,
		logger:       logger,
		tasks:        make(map[string]*WebhookTask),
		tasksMu:      make(chan struct{}, 1),
		httpClient: &http.Client{
			Timeout: cfg.DownloadTimeout,
		},
	}
}

func (wm *WebhookManager) HandleTrainingPlatformWebhook(ctx context.Context,
	payload *TrainingPlatformPayload, authToken string) (*WebhookResponse, error) {

	if wm.cfg.AuthToken != "" && authToken != wm.cfg.AuthToken {
		return &WebhookResponse{
			Success:   false,
			Message:   "invalid authentication token",
			TaskID:    payload.TaskID,
			CreatedAt: time.Now(),
		}, fmt.Errorf("invalid auth token")
	}

	if err := wm.validatePayload(payload); err != nil {
		return &WebhookResponse{
			Success:   false,
			Message:   err.Error(),
			TaskID:    payload.TaskID,
			CreatedAt: time.Now(),
		}, err
	}

	task := &WebhookTask{
		ID:        uuid.New().String(),
		TaskID:    payload.TaskID,
		Payload:   *payload,
		Status:    TaskStatusPending,
		CreatedAt: time.Now(),
	}

	wm.tasksMu <- struct{}{}
	wm.tasks[task.ID] = task
	<-wm.tasksMu

	go wm.processTask(task)

	return &WebhookResponse{
		Success:   true,
		Message:   "webhook accepted, processing in background",
		TaskID:    payload.TaskID,
		CreatedAt: time.Now(),
	}, nil
}

func (wm *WebhookManager) validatePayload(payload *TrainingPlatformPayload) error {
	if payload.ModelName == "" {
		return fmt.Errorf("model_name is required")
	}
	if payload.Version == "" {
		return fmt.Errorf("version is required")
	}
	if payload.Namespace == "" {
		return fmt.Errorf("namespace is required")
	}
	if payload.ModelURL == "" {
		return fmt.Errorf("model_url is required")
	}

	validFormats := map[model.ModelFormat]bool{
		model.FormatTensorFlow: true,
		model.FormatPyTorch:    true,
		model.FormatONNX:       true,
	}
	if !validFormats[payload.ModelFormat] {
		return fmt.Errorf("unsupported model format: %s, supported: TensorFlow, PyTorch, ONNX",
			payload.ModelFormat)
	}

	return nil
}

func (wm *WebhookManager) processTask(task *WebhookTask) {
	ctx := context.Background()
	now := time.Now()
	task.StartedAt = &now
	task.Status = TaskStatusRunning

	wm.tasksMu <- struct{}{}
	wm.tasks[task.ID] = task
	<-wm.tasksMu

	wm.logger.Info("Starting webhook task processing",
		zap.String("task_id", task.TaskID),
		zap.String("model", task.Payload.ModelName),
		zap.String("version", task.Payload.Version))

	modelReader, err := wm.downloadModel(ctx, task.Payload.ModelURL)
	if err != nil {
		wm.markTaskFailed(task, fmt.Sprintf("failed to download model: %v", err))
		return
	}
	defer modelReader.Close()

	existingModel, err := wm.modelRepo.GetModel(ctx, task.Payload.Namespace, task.Payload.ModelName)
	if err != nil {
		wm.logger.Info("Model not found, creating new model",
			zap.String("model", task.Payload.ModelName))

		description := fmt.Sprintf("Auto-registered from training platform, dataset: %s",
			task.Payload.Dataset)
		existingModel, err = wm.modelRepo.CreateModel(ctx,
			task.Payload.Namespace,
			task.Payload.ModelName,
			description,
			map[string]string{
				"auto_registered": "true",
				"dataset":         task.Payload.Dataset,
				"training_user":   task.Payload.TrainingUser,
			})
		if err != nil {
			wm.markTaskFailed(task, fmt.Sprintf("failed to create model: %v", err))
			return
		}
	}

	metadata := task.Payload.TrainingMetrics
	if metadata == nil {
		metadata = make(map[string]interface{})
	}
	metadata["task_id"] = task.Payload.TaskID
	metadata["auto_deploy"] = task.Payload.AutoDeploy
	if task.Payload.Dataset != "" {
		metadata["dataset"] = task.Payload.Dataset
	}

	modelVersion, err := wm.modelRepo.CreateModelVersion(ctx,
		existingModel.ID,
		task.Payload.Version,
		task.Payload.ModelFormat,
		modelReader,
		task.Payload.TrainingUser,
		metadata)
	if err != nil {
		wm.markTaskFailed(task, fmt.Sprintf("failed to create model version: %v", err))
		return
	}

	task.ModelID = existingModel.ID
	task.VersionID = modelVersion.ID

	wm.logger.Info("Model version created successfully",
		zap.String("model_id", existingModel.ID),
		zap.String("version_id", modelVersion.ID))

	autoDeploy := task.Payload.AutoDeploy
	if !autoDeploy && wm.cfg.AutoDeployDefault {
		autoDeploy = true
	}

	if autoDeploy && wm.orchestrator != nil {
		deployEnv := task.Payload.DeployEnv
		if deployEnv == "" {
			deployEnv = wm.cfg.DefaultDeployEnv
		}

		wm.logger.Info("Auto-deploying model version",
			zap.String("model", task.Payload.ModelName),
			zap.String("version", task.Payload.Version),
			zap.String("env", deployEnv))

		gpuMemory := modelVersion.GPUMemoryMB
		if gpuMemory < 512 {
			gpuMemory = 512
		}

		_, err := wm.orchestrator.CreateInstance(ctx,
			task.Payload.ModelName,
			existingModel.ID,
			task.Payload.Version,
			task.Payload.Namespace,
			gpuMemory)
		if err != nil {
			wm.logger.Error("Failed to auto-deploy model",
				zap.Error(err),
				zap.String("model", task.Payload.ModelName))
		}
	}

	wm.markTaskSuccess(task)
}

func (wm *WebhookManager) downloadModel(ctx context.Context, modelURL string) (io.ReadCloser, error) {
	wm.logger.Info("Downloading model from URL", zap.String("url", modelURL))

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, modelURL, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	resp, err := wm.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("failed to download model: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		return nil, fmt.Errorf("download failed with status: %d", resp.StatusCode)
	}

	if wm.cfg.MaxModelSizeMB > 0 {
		contentLength := resp.ContentLength
		maxBytes := wm.cfg.MaxModelSizeMB * 1024 * 1024
		if contentLength > maxBytes {
			resp.Body.Close()
			return nil, fmt.Errorf("model too large: %d bytes, max: %d bytes",
				contentLength, maxBytes)
		}
	}

	return resp.Body, nil
}

func (wm *WebhookManager) markTaskSuccess(task *WebhookTask) {
	now := time.Now()
	task.Status = TaskStatusSuccess
	task.CompletedAt = &now

	wm.tasksMu <- struct{}{}
	wm.tasks[task.ID] = task
	<-wm.tasksMu

	wm.logger.Info("Webhook task completed successfully",
		zap.String("task_id", task.TaskID),
		zap.String("model_id", task.ModelID),
		zap.String("version_id", task.VersionID))
}

func (wm *WebhookManager) markTaskFailed(task *WebhookTask, errorMsg string) {
	now := time.Now()
	task.Status = TaskStatusFailed
	task.ErrorMessage = errorMsg
	task.CompletedAt = &now

	wm.tasksMu <- struct{}{}
	wm.tasks[task.ID] = task
	<-wm.tasksMu

	wm.logger.Error("Webhook task failed",
		zap.String("task_id", task.TaskID),
		zap.String("error", errorMsg))
}

func (wm *WebhookManager) GetTaskStatus(ctx context.Context, taskID string) (*WebhookTask, error) {
	wm.tasksMu <- struct{}{}
	defer func() { <-wm.tasksMu }()

	for _, task := range wm.tasks {
		if task.TaskID == taskID {
			return task, nil
		}
	}

	return nil, fmt.Errorf("task not found: %s", taskID)
}

func (wm *WebhookManager) ListTasks(ctx context.Context, limit int) []*WebhookTask {
	wm.tasksMu <- struct{}{}
	defer func() { <-wm.tasksMu }()

	tasks := make([]*WebhookTask, 0, len(wm.tasks))
	for _, task := range wm.tasks {
		tasks = append(tasks, task)
	}

	if limit > 0 && len(tasks) > limit {
		tasks = tasks[:limit]
	}

	return tasks
}
