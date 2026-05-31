package api

import (
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/dataplatform/engine/internal/common/errors"
	"github.com/dataplatform/engine/internal/domain"
	"github.com/dataplatform/engine/internal/domain/adversarial"
	"github.com/dataplatform/engine/internal/domain/gateway"
	"github.com/dataplatform/engine/internal/domain/gpu"
	"github.com/dataplatform/engine/internal/domain/notification"
	"github.com/dataplatform/engine/internal/domain/processing"
	"github.com/dataplatform/engine/internal/domain/prompt"
	"github.com/dataplatform/engine/internal/domain/scheduler"
	"github.com/dataplatform/engine/internal/domain/storage"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type CreateResourceRequest struct {
	Type   string                 `json:"type"`
	Config map[string]interface{} `json:"config"`
	Labels map[string]string      `json:"labels"`
}

func (h *APIHandler) CreateResource(c *gin.Context) {
	var req CreateResourceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	entity := &domain.Entity{
		ID:         uuid.New().String(),
		Type:       req.Type,
		Status:     domain.StatusProvisioning,
		Attributes: req.Config,
		CreatedAt:  time.Now(),
		UpdatedAt:  time.Now(),
	}

	h.logger.Info("Resource created",
		domain.String("resource_id", entity.ID),
		domain.String("type", entity.Type),
	)

	createdResponse(c, gin.H{
		"id":     entity.ID,
		"status": entity.Status,
	})
}

func (h *APIHandler) GetResourceStatus(c *gin.Context) {
	id := c.Param("id")

	entity := &domain.Entity{
		ID:        id,
		Status:    domain.StatusCompleted,
		UpdatedAt: time.Now(),
	}

	successResponse(c, gin.H{
		"id":       entity.ID,
		"status":   entity.Status,
		"progress": 0.8,
	})
}

type BatchOperation struct {
	Action string `json:"action"`
	ID     string `json:"id"`
}

type BatchRequest struct {
	Operations []BatchOperation `json:"operations"`
}

func (h *APIHandler) BatchOperation(c *gin.Context) {
	var req BatchRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	results := make([]gin.H, 0, len(req.Operations))
	for _, op := range req.Operations {
		results = append(results, gin.H{
			"id":      op.ID,
			"action":  op.Action,
			"success": true,
		})
	}

	successResponse(c, gin.H{
		"batch_id": uuid.New().String(),
		"results":  results,
	})
}

func (h *APIHandler) SubmitGPUTask(c *gin.Context) {
	var task gpu.GPUTask
	if err := c.ShouldBindJSON(&task); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	created, err := h.gpuScheduler.SubmitTask(c.Request.Context(), &task)
	if err != nil {
		errorResponse(c, err)
		return
	}

	createdResponse(c, created)
}

func (h *APIHandler) GetGPUTask(c *gin.Context) {
	id := c.Param("id")

	task, err := h.gpuScheduler.GetTaskStatus(c.Request.Context(), id)
	if err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, task)
}

func (h *APIHandler) CancelGPUTask(c *gin.Context) {
	id := c.Param("id")

	if err := h.gpuScheduler.CancelTask(c.Request.Context(), id); err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, gin.H{"message": "task cancelled"})
}

func (h *APIHandler) GetGPUResources(c *gin.Context) {
	resources, err := h.gpuScheduler.GetAvailableResources(c.Request.Context())
	if err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, resources)
}

func (h *APIHandler) ProcessData(c *gin.Context) {
	var req processing.ProcessRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	processorImpl, ok := h.processor.(*processing.DataProcessorImpl)
	if !ok {
		errorResponse(c, errors.New(errors.ErrCodeInternal, "invalid processor type"))
		return
	}

	result, err := processorImpl.ExecuteHandler(c.Request.Context(), &req)
	if err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, result)
}

type InferenceReq struct {
	TraceID    string                 `json:"trace_id"`
	Model      string                 `json:"model"`
	Prompt     string                 `json:"prompt"`
	Params     map[string]interface{} `json:"params"`
	TimeoutMs  int                    `json:"timeout_ms"`
	MaxRetries int                    `json:"max_retries"`
}

func (h *APIHandler) RouteInference(c *gin.Context) {
	var req InferenceReq
	if err := c.ShouldBindJSON(&req); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	gatewayReq := &gateway.InferenceRequest{
		TraceID:    req.TraceID,
		Model:      req.Model,
		Prompt:     req.Prompt,
		Params:     req.Params,
		TimeoutMs:  req.TimeoutMs,
		MaxRetries: req.MaxRetries,
	}

	if req.Prompt != "" {
		gatewayReq.Messages = []*gateway.Message{
			{Role: "user", Content: req.Prompt},
		}
	}

	resp, err := h.gateway.Route(c.Request.Context(), gatewayReq)
	if err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, resp)
}

func (h *APIHandler) ListProviders(c *gin.Context) {
	providers := h.gateway.ListProviders()
	successResponse(c, gin.H{"providers": providers})
}

type GenerateAdversarialReq struct {
	BasePrompt string                         `json:"base_prompt"`
	Strategies []adversarial.AttackStrategy `json:"strategies"`
}

func (h *APIHandler) GenerateAdversarial(c *gin.Context) {
	var req GenerateAdversarialReq
	if err := c.ShouldBindJSON(&req); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	var allSamples []*adversarial.AdversarialSample
	for _, strategy := range req.Strategies {
		samples, err := h.adversarial.Generate(c.Request.Context(), strategy, req.BasePrompt)
		if err != nil {
			errorResponse(c, err)
			return
		}
		allSamples = append(allSamples, samples...)
	}

	successResponse(c, gin.H{"samples": allSamples})
}

type EvaluateAdversarialReq struct {
	Samples []*adversarial.AdversarialSample `json:"samples"`
}

func (h *APIHandler) EvaluateAdversarial(c *gin.Context) {
	var req EvaluateAdversarialReq
	if err := c.ShouldBindJSON(&req); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	evaluation, err := h.adversarial.Evaluate(c.Request.Context(), req.Samples)
	if err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, evaluation)
}

func (h *APIHandler) ListStrategies(c *gin.Context) {
	strategies := h.adversarial.ListStrategies()
	successResponse(c, gin.H{"strategies": strategies})
}

type ScheduleJobReq struct {
	Name       string                 `json:"name"`
	Type       string                 `json:"type"`
	CronExpr   string                 `json:"cron_expr,omitempty"`
	IntervalMs int64                  `json:"interval_ms,omitempty"`
	Payload    map[string]interface{} `json:"payload"`
}

func (h *APIHandler) ScheduleJob(c *gin.Context) {
	var req ScheduleJobReq
	if err := c.ShouldBindJSON(&req); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	job := &scheduler.ScheduledJob{
		Name:       req.Name,
		Type:       scheduler.JobType(req.Type),
		CronExpr:   req.CronExpr,
		IntervalMs: req.IntervalMs,
		Payload:    req.Payload,
		Handler: func(ctx context.Context, j *scheduler.ScheduledJob) error {
			h.logger.Info("Job executed",
				domain.String("job_id", j.ID),
				domain.String("name", j.Name),
			)
			return nil
		},
	}

	jobID, err := h.scheduler.Schedule(c.Request.Context(), job)
	if err != nil {
		errorResponse(c, err)
		return
	}

	createdResponse(c, gin.H{"job_id": jobID})
}

func (h *APIHandler) ListJobs(c *gin.Context) {
	jobs, err := h.scheduler.List(c.Request.Context())
	if err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, jobs)
}

func (h *APIHandler) UnscheduleJob(c *gin.Context) {
	id := c.Param("id")

	if err := h.scheduler.Unschedule(c.Request.Context(), id); err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, gin.H{"message": "job unscheduled"})
}

func (h *APIHandler) TriggerJob(c *gin.Context) {
	id := c.Param("id")

	if err := h.scheduler.Trigger(c.Request.Context(), id); err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, gin.H{"message": "job triggered"})
}

type SetLogLevelReq struct {
	Level string `json:"level"`
}

func (h *APIHandler) SetLogLevel(c *gin.Context) {
	var req SetLogLevelReq
	if err := c.ShouldBindJSON(&req); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	var level domain.LogLevel
	switch strings.ToLower(req.Level) {
	case "debug":
		level = domain.LogLevelDebug
	case "info":
		level = domain.LogLevelInfo
	case "warn", "warning":
		level = domain.LogLevelWarn
	case "error":
		level = domain.LogLevelError
	case "fatal":
		level = domain.LogLevelFatal
	default:
		errorResponse(c, errors.New(errors.ErrCodeValidation, "invalid log level"))
		return
	}

	h.logger.SetLevel(level)
	successResponse(c, gin.H{"level": req.Level})
}

func (h *APIHandler) GetLogLevel(c *gin.Context) {
	level := h.logger.GetLevel()
	levelStr := "info"
	switch level {
	case domain.LogLevelDebug:
		levelStr = "debug"
	case domain.LogLevelInfo:
		levelStr = "info"
	case domain.LogLevelWarn:
		levelStr = "warn"
	case domain.LogLevelError:
		levelStr = "error"
	case domain.LogLevelFatal:
		levelStr = "fatal"
	}
	successResponse(c, gin.H{"level": levelStr})
}

type SendNotificationReq struct {
	Title    string                   `json:"title"`
	Message  string                   `json:"message"`
	Priority int                      `json:"priority"`
	Channels []string                 `json:"channels"`
	Data     map[string]interface{}   `json:"data"`
	Suppress *notification.SuppressRule `json:"suppress,omitempty"`
}

func (h *APIHandler) SendNotification(c *gin.Context) {
	var req SendNotificationReq
	if err := c.ShouldBindJSON(&req); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	notif := &notification.Notification{
		Title:    req.Title,
		Message:  req.Message,
		Priority: notification.NotificationPriority(req.Priority),
		Channels: req.Channels,
		Data:     req.Data,
		Suppress: req.Suppress,
	}

	if err := h.notifier.Send(c.Request.Context(), notif); err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, gin.H{"notification_id": notif.ID})
}

func (h *APIHandler) ListChannels(c *gin.Context) {
	channels := h.notifier.ListChannels()
	successResponse(c, gin.H{"channels": channels})
}

func (h *APIHandler) UploadFile(c *gin.Context) {
	key := c.PostForm("key")
	if key == "" {
		key = uuid.New().String()
	}

	file, _, err := c.Request.FormFile("file")
	if err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "failed to get file"))
		return
	}
	defer file.Close()

	data, err := io.ReadAll(file)
	if err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeInternal, "failed to read file"))
		return
	}

	metadata := make(map[string]string)
	for k, v := range c.Request.PostForm {
		if k != "key" && len(v) > 0 {
			metadata[k] = v[0]
		}
	}

	if err := h.storage.Upload(c.Request.Context(), key, data, metadata); err != nil {
		errorResponse(c, err)
		return
	}

	createdResponse(c, gin.H{"key": key, "size": len(data)})
}

func (h *APIHandler) DownloadFile(c *gin.Context) {
	key := c.Param("key")

	data, err := h.storage.Download(c.Request.Context(), key)
	if err != nil {
		errorResponse(c, err)
		return
	}

	c.Data(http.StatusOK, "application/octet-stream", data)
}

func (h *APIHandler) DeleteFile(c *gin.Context) {
	key := c.Param("key")

	if err := h.storage.Delete(c.Request.Context(), key); err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, gin.H{"message": "file deleted"})
}

func (h *APIHandler) ListFiles(c *gin.Context) {
	prefix := c.DefaultQuery("prefix", "")

	files, err := h.storage.List(c.Request.Context(), prefix)
	if err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, gin.H{"files": files})
}

type SetLifecycleReq struct {
	Rule *storage.LifecycleRule `json:"rule"`
}

func (h *APIHandler) SetLifecycle(c *gin.Context) {
	var req SetLifecycleReq
	if err := c.ShouldBindJSON(&req); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	if err := h.storage.SetLifecycle(c.Request.Context(), req.Rule); err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, gin.H{"message": "lifecycle rule set"})
}

type CreateExperimentReq struct {
	Name        string `json:"name"`
	Description string `json:"description"`
}

func (h *APIHandler) CreateExperiment(c *gin.Context) {
	var req CreateExperimentReq
	if err := c.ShouldBindJSON(&req); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	exp := &prompt.PromptExperiment{
		Name:        req.Name,
		Description: req.Description,
	}

	created, err := h.promptManager.CreateExperiment(c.Request.Context(), exp)
	if err != nil {
		errorResponse(c, err)
		return
	}

	createdResponse(c, created)
}

func (h *APIHandler) CreateVersion(c *gin.Context) {
	expID := c.Param("id")

	exp := &prompt.PromptExperiment{
		ID: expID,
	}

	version, err := h.promptManager.CreateVersion(c.Request.Context(), exp)
	if err != nil {
		errorResponse(c, err)
		return
	}

	createdResponse(c, version)
}

func (h *APIHandler) ListVersions(c *gin.Context) {
	expID := c.Param("id")

	versions, err := h.promptManager.ListVersions(c.Request.Context(), expID)
	if err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, versions)
}

type StartABTestReq struct {
	*prompt.ABTestConfig
}

func (h *APIHandler) StartABTest(c *gin.Context) {
	var req StartABTestReq
	if err := c.ShouldBindJSON(&req); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	test, err := h.promptManager.StartABTest(c.Request.Context(), req.ABTestConfig)
	if err != nil {
		errorResponse(c, err)
		return
	}

	createdResponse(c, test)
}

func (h *APIHandler) StopABTest(c *gin.Context) {
	testID := c.Param("id")

	if err := h.promptManager.StopABTest(c.Request.Context(), testID); err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, gin.H{"message": "A/B test stopped"})
}

type EvaluateABTestReq struct {
	Metrics map[string]float64 `json:"metrics"`
}

func (h *APIHandler) EvaluateABTest(c *gin.Context) {
	testID := c.Param("id")

	var req EvaluateABTestReq
	if err := c.ShouldBindJSON(&req); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	result, err := h.promptManager.Evaluate(c.Request.Context(), testID, req.Metrics)
	if err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, result)
}

func (h *APIHandler) RecoverTasks(c *gin.Context) {
	if h.persistenceScheduler == nil {
		errorResponse(c, errors.New(errors.ErrCodeUnavailable, "persistence not enabled"))
		return
	}

	count, err := h.persistenceScheduler.Recover(c.Request.Context())
	if err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, gin.H{"recovered_count": count})
}

func (h *APIHandler) GetPersistenceStats(c *gin.Context) {
	if h.persistenceScheduler == nil {
		errorResponse(c, errors.New(errors.ErrCodeUnavailable, "persistence not enabled"))
		return
	}

	successResponse(c, gin.H{"persistence_enabled": true})
}

func (h *APIHandler) GetCacheStats(c *gin.Context) {
	if h.cachedProcessor == nil {
		successResponse(c, gin.H{"cache_enabled": false})
		return
	}

	stats := h.cachedProcessor.GetCacheStats()
	successResponse(c, gin.H{
		"cache_enabled": true,
		"hit_rate":      h.cachedProcessor.GetHitRate(),
		"hits":          stats.Hits,
		"misses":        stats.Misses,
		"evictions":     stats.Evictions,
		"total_size":    stats.TotalSize,
		"entry_count":   stats.EntryCount,
	})
}

func (h *APIHandler) InvalidateCache(c *gin.Context) {
	if h.cachedProcessor == nil {
		errorResponse(c, errors.New(errors.ErrCodeUnavailable, "cache not enabled"))
		return
	}

	h.cachedProcessor.InvalidateCache()
	successResponse(c, gin.H{"message": "cache invalidated"})
}

type WarmUpReq struct {
	Data []interface{} `json:"data"`
}

func (h *APIHandler) WarmUpCache(c *gin.Context) {
	if h.cachedProcessor == nil {
		errorResponse(c, errors.New(errors.ErrCodeUnavailable, "cache not enabled"))
		return
	}

	var req WarmUpReq
	if err := c.ShouldBindJSON(&req); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	go h.cachedProcessor.WarmUp(c.Request.Context(), req.Data)
	successResponse(c, gin.H{"message": "cache warm-up started in background", "data_count": len(req.Data)})
}

func (h *APIHandler) GetGatewayConfig(c *gin.Context) {
	if h.configManager == nil {
		errorResponse(c, errors.New(errors.ErrCodeUnavailable, "dynamic config not enabled"))
		return
	}

	config := h.configManager.GetConfig()
	successResponse(c, config)
}

func (h *APIHandler) UpdateGatewayConfig(c *gin.Context) {
	if h.configManager == nil {
		errorResponse(c, errors.New(errors.ErrCodeUnavailable, "dynamic config not enabled"))
		return
	}

	var config gateway.GatewayConfig
	if err := c.ShouldBindJSON(&config); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	if err := h.configManager.UpdateConfig(c.Request.Context(), &config); err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, gin.H{"message": "config updated", "version": config.Version})
}

func (h *APIHandler) AddGatewayProvider(c *gin.Context) {
	if h.configManager == nil {
		errorResponse(c, errors.New(errors.ErrCodeUnavailable, "dynamic config not enabled"))
		return
	}

	var provider gateway.ProviderConfig
	if err := c.ShouldBindJSON(&provider); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	if err := h.configManager.AddProvider(c.Request.Context(), &provider); err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, gin.H{"message": "provider added", "name": provider.Name})
}

func (h *APIHandler) RemoveGatewayProvider(c *gin.Context) {
	if h.configManager == nil {
		errorResponse(c, errors.New(errors.ErrCodeUnavailable, "dynamic config not enabled"))
		return
	}

	name := c.Param("name")

	if err := h.configManager.RemoveProvider(c.Request.Context(), name); err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, gin.H{"message": "provider removed", "name": name})
}

func (h *APIHandler) UpdateGatewayProvider(c *gin.Context) {
	if h.configManager == nil {
		errorResponse(c, errors.New(errors.ErrCodeUnavailable, "dynamic config not enabled"))
		return
	}

	var provider gateway.ProviderConfig
	if err := c.ShouldBindJSON(&provider); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	if err := h.configManager.UpdateProvider(c.Request.Context(), &provider); err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, gin.H{"message": "provider updated", "name": provider.Name})
}

func (h *APIHandler) GetConfigVersions(c *gin.Context) {
	if h.configManager == nil {
		errorResponse(c, errors.New(errors.ErrCodeUnavailable, "dynamic config not enabled"))
		return
	}

	versions := h.configManager.GetVersions()
	successResponse(c, gin.H{"versions": versions})
}

type RollbackReq struct {
	Version int64 `json:"version"`
}

func (h *APIHandler) RollbackConfig(c *gin.Context) {
	if h.configManager == nil {
		errorResponse(c, errors.New(errors.ErrCodeUnavailable, "dynamic config not enabled"))
		return
	}

	var req RollbackReq
	if err := c.ShouldBindJSON(&req); err != nil {
		errorResponse(c, errors.Wrap(err, errors.ErrCodeValidation, "invalid request body"))
		return
	}

	if err := h.configManager.RollbackToVersion(c.Request.Context(), req.Version); err != nil {
		errorResponse(c, err)
		return
	}

	successResponse(c, gin.H{"message": "config rolled back", "version": req.Version})
}
