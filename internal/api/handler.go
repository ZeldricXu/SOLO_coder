package api

import (
	"net/http"

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
)

type APIResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message,omitempty"`
	Data    interface{} `json:"data,omitempty"`
}

type APIHandler struct {
	gpuScheduler       domain.GPUScheduler
	persistenceScheduler *gpu.PersistenceScheduler
	processor          processing.DataProcessor
	cachedProcessor    *processing.CachedProcessor
	gateway            domain.InferenceGateway
	dynamicGateway     *gateway.DynamicGateway
	configManager      *gateway.DynamicConfigManager
	adversarial        adversarial.AdversarialGenerator
	scheduler          scheduler.TaskScheduler
	logger             domain.Logger
	notifier           notification.Notifier
	storage            storage.StorageManager
	promptManager      prompt.PromptExperimentManager
}

func NewAPIHandler(
	gpuScheduler domain.GPUScheduler,
	persistenceScheduler *gpu.PersistenceScheduler,
	processor processing.DataProcessor,
	cachedProcessor *processing.CachedProcessor,
	gateway domain.InferenceGateway,
	dynamicGateway *gateway.DynamicGateway,
	configManager *gateway.DynamicConfigManager,
	adversarial adversarial.AdversarialGenerator,
	scheduler scheduler.TaskScheduler,
	logger domain.Logger,
	notifier notification.Notifier,
	storage storage.StorageManager,
	promptManager prompt.PromptExperimentManager,
) *APIHandler {
	return &APIHandler{
		gpuScheduler:       gpuScheduler,
		persistenceScheduler: persistenceScheduler,
		processor:          processor,
		cachedProcessor:    cachedProcessor,
		gateway:            gateway,
		dynamicGateway:     dynamicGateway,
		configManager:      configManager,
		adversarial:        adversarial,
		scheduler:          scheduler,
		logger:             logger,
		notifier:           notifier,
		storage:            storage,
		promptManager:      promptManager,
	}
}

func (h *APIHandler) RegisterRoutes(r *gin.Engine) {
	api := r.Group("/api/v1")
	{
		resources := api.Group("/resources")
		{
			resources.POST("", h.CreateResource)
			resources.GET("/:id/status", h.GetResourceStatus)
			resources.POST("/batch", h.BatchOperation)
		}

		gpu := api.Group("/gpu")
		{
			gpu.POST("/tasks", h.SubmitGPUTask)
			gpu.GET("/tasks/:id", h.GetGPUTask)
			gpu.DELETE("/tasks/:id", h.CancelGPUTask)
			gpu.GET("/resources", h.GetGPUResources)
			gpu.POST("/recovery", h.RecoverTasks)
			gpu.GET("/persistence/stats", h.GetPersistenceStats)
		}

		process := api.Group("/process")
		{
			process.POST("", h.ProcessData)
			process.GET("/cache/stats", h.GetCacheStats)
			process.POST("/cache/invalidate", h.InvalidateCache)
			process.POST("/cache/warmup", h.WarmUpCache)
		}

		inference := api.Group("/inference")
		{
			inference.POST("", h.RouteInference)
			inference.GET("/providers", h.ListProviders)
			inference.GET("/config", h.GetGatewayConfig)
			inference.PUT("/config", h.UpdateGatewayConfig)
			inference.POST("/config/providers", h.AddGatewayProvider)
			inference.DELETE("/config/providers/:name", h.RemoveGatewayProvider)
			inference.PUT("/config/providers", h.UpdateGatewayProvider)
			inference.GET("/config/versions", h.GetConfigVersions)
			inference.POST("/config/rollback", h.RollbackConfig)
		}

		adversarial := api.Group("/adversarial")
		{
			adversarial.POST("/generate", h.GenerateAdversarial)
			adversarial.POST("/evaluate", h.EvaluateAdversarial)
			adversarial.GET("/strategies", h.ListStrategies)
		}

		jobs := api.Group("/jobs")
		{
			jobs.POST("", h.ScheduleJob)
			jobs.GET("", h.ListJobs)
			jobs.DELETE("/:id", h.UnscheduleJob)
			jobs.POST("/:id/trigger", h.TriggerJob)
		}

		logs := api.Group("/logs")
		{
			logs.POST("/level", h.SetLogLevel)
			logs.GET("/level", h.GetLogLevel)
		}

		notify := api.Group("/notifications")
		{
			notify.POST("", h.SendNotification)
			notify.GET("/channels", h.ListChannels)
		}

		storage := api.Group("/storage")
		{
			storage.POST("/upload", h.UploadFile)
			storage.GET("/download/:key", h.DownloadFile)
			storage.DELETE("/:key", h.DeleteFile)
			storage.GET("/list", h.ListFiles)
			storage.POST("/lifecycle", h.SetLifecycle)
		}

		prompt := api.Group("/prompt")
		{
			prompt.POST("/experiments", h.CreateExperiment)
			prompt.POST("/experiments/:id/versions", h.CreateVersion)
			prompt.GET("/experiments/:id/versions", h.ListVersions)
			prompt.POST("/abtests", h.StartABTest)
			prompt.POST("/abtests/:id/stop", h.StopABTest)
			prompt.POST("/abtests/:id/evaluate", h.EvaluateABTest)
		}
	}
}

func successResponse(c *gin.Context, data interface{}) {
	c.JSON(http.StatusOK, APIResponse{
		Code: http.StatusOK,
		Data: data,
	})
}

func createdResponse(c *gin.Context, data interface{}) {
	c.JSON(http.StatusCreated, APIResponse{
		Code: http.StatusCreated,
		Data: data,
	})
}

func errorResponse(c *gin.Context, err error) {
	code := http.StatusInternalServerError
	message := err.Error()

	if appErr, ok := err.(*errors.AppError); ok {
		switch appErr.Code {
		case errors.ErrCodeValidation:
			code = http.StatusBadRequest
		case errors.ErrCodeNotFound:
			code = http.StatusNotFound
		case errors.ErrCodeConflict:
			code = http.StatusConflict
		case errors.ErrCodeUnauthorized:
			code = http.StatusUnauthorized
		case errors.ErrCodeForbidden:
			code = http.StatusForbidden
		case errors.ErrCodeTimeout:
			code = http.StatusGatewayTimeout
		case errors.ErrCodeUnavailable:
			code = http.StatusServiceUnavailable
		case errors.ErrCodeResourceExhausted:
			code = http.StatusTooManyRequests
		}
		message = appErr.Message
	}

	c.JSON(code, APIResponse{
		Code:    code,
		Message: message,
	})
}
