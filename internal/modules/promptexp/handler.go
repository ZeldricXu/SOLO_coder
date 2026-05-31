package promptexp

import (
	"net/http"
	"strconv"

	"notificationplatform/internal/common/errors"
	"notificationplatform/internal/common/logger"
	"notificationplatform/internal/common/models"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

type Handler struct {
	service *Service
}

func NewHandler() *Handler {
	return &Handler{
		service: NewService(10),
	}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	prompts := r.Group("/prompts")
	{
		prompts.POST("", h.CreatePrompt)
		prompts.GET("", h.ListPrompts)
		prompts.GET("/:id", h.GetPrompt)
		prompts.POST("/:id/versions", h.CreateVersion)
		prompts.GET("/:id/versions", h.ListVersions)
		prompts.POST("/:id/versions/:version_id/promote", h.PromoteVersion)
	}

	experiments := r.Group("/experiments")
	{
		experiments.POST("", h.CreateExperiment)
		experiments.GET("", h.ListExperiments)
		experiments.GET("/:id", h.GetExperiment)
		experiments.POST("/:id/start", h.StartExperiment)
		experiments.POST("/:id/batch-test", h.RunBatchTest)
		experiments.GET("/runs/:run_id", h.GetRunStatus)
		experiments.POST("/runs/:run_id/cancel", h.CancelRun)
		experiments.GET("/runs/:run_id/evaluation", h.GetEvaluation)
	}
}

type CreatePromptRequest struct {
	Name        string   `json:"name" binding:"required"`
	Description string   `json:"description"`
	TaskType    string   `json:"task_type" binding:"required"`
	Tags        []string `json:"tags"`
	CreatedBy   string   `json:"created_by" binding:"required"`
}

func (h *Handler) CreatePrompt(c *gin.Context) {
	var req CreatePromptRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		logger.FromContext(c.Request.Context()).Warn("invalid create prompt request", zap.Error(err))
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	ctx := c.Request.Context()
	prompt, err := h.service.CreatePrompt(ctx, req.Name, req.Description, req.TaskType, req.CreatedBy, req.Tags)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to create prompt",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusCreated, models.APIResponse{
		Code:    http.StatusCreated,
		Message: "Prompt created successfully",
		Data:    prompt,
	})
}

func (h *Handler) ListPrompts(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	filters := make(map[string]interface{})
	if taskType := c.Query("task_type"); taskType != "" {
		filters["task_type"] = taskType
	}
	if status := c.Query("status"); status != "" {
		filters["status"] = status
	}
	if owner := c.Query("owner"); owner != "" {
		filters["owner"] = owner
	}

	ctx := c.Request.Context()
	prompts, total, err := h.service.ListPrompts(ctx, page, pageSize, filters)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to list prompts",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data: gin.H{
			"items":     prompts,
			"total":     total,
			"page":      page,
			"page_size": pageSize,
		},
	})
}

func (h *Handler) GetPrompt(c *gin.Context) {
	id := c.Param("id")

	ctx := c.Request.Context()
	prompt, err := h.service.GetPrompt(ctx, id)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to get prompt",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    prompt,
	})
}

type CreateVersionRequest struct {
	Name          string                 `json:"name" binding:"required"`
	Description   string                 `json:"description"`
	SystemPrompt  string                 `json:"system_prompt"`
	UserPrompt    string                 `json:"user_prompt" binding:"required"`
	Variables     map[string]string      `json:"variables"`
	ModelConfig   map[string]interface{} `json:"model_config"`
	ChangeLog     string                 `json:"change_log"`
	CreatedBy     string                 `json:"created_by" binding:"required"`
	ParentVersion int                    `json:"parent_version"`
}

func (h *Handler) CreateVersion(c *gin.Context) {
	promptID := c.Param("id")

	var req CreateVersionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	ctx := c.Request.Context()
	version := &models.PromptVersion{
		Name:          req.Name,
		Description:   req.Description,
		SystemPrompt:  req.SystemPrompt,
		UserPrompt:    req.UserPrompt,
		Variables:     req.Variables,
		ModelConfig:   req.ModelConfig,
		ChangeLog:     req.ChangeLog,
		CreatedBy:     req.CreatedBy,
		ParentVersion: req.ParentVersion,
	}

	version, err := h.service.CreateVersion(ctx, promptID, version)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to create version",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusCreated, models.APIResponse{
		Code:    http.StatusCreated,
		Message: "Version created successfully",
		Data:    version,
	})
}

func (h *Handler) ListVersions(c *gin.Context) {
	promptID := c.Param("id")

	ctx := c.Request.Context()
	versions, err := h.service.GetVersions(ctx, promptID)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to list versions",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    versions,
	})
}

func (h *Handler) PromoteVersion(c *gin.Context) {
	promptID := c.Param("id")
	versionID := c.Param("version_id")

	ctx := c.Request.Context()
	if err := h.service.PromoteVersion(ctx, promptID, versionID); err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to promote version",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Version promoted successfully",
	})
}

type CreateExperimentRequest struct {
	Name             string                   `json:"name" binding:"required"`
	Description      string                   `json:"description"`
	Type             string                   `json:"type" binding:"required"`
	ControlPromptID  string                   `json:"control_prompt_id" binding:"required"`
	ControlVersionID string                   `json:"control_version_id" binding:"required"`
	TrafficPercentage float64                 `json:"traffic_percentage" binding:"required,min=1,max=100"`
	DurationHours    int                      `json:"duration_hours"`
	Variants         []*models.ExperimentVariant `json:"variants" binding:"required,min=1"`
	TargetMetrics    []string                 `json:"target_metrics"`
	SegmentFilter    map[string]interface{}   `json:"segment_filter"`
	CreatedBy        string                   `json:"created_by" binding:"required"`
}

func (h *Handler) CreateExperiment(c *gin.Context) {
	var req CreateExperimentRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		logger.FromContext(c.Request.Context()).Warn("invalid create experiment request", zap.Error(err))
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	ctx := c.Request.Context()
	exp := &models.ABExperiment{
		Name:              req.Name,
		Description:       req.Description,
		Type:              req.Type,
		ControlPromptID:   req.ControlPromptID,
		ControlVersionID:  req.ControlVersionID,
		TrafficPercentage: req.TrafficPercentage,
		DurationHours:     req.DurationHours,
		Variants:          req.Variants,
		TargetMetrics:     req.TargetMetrics,
		SegmentFilter:     req.SegmentFilter,
		CreatedBy:         req.CreatedBy,
	}

	exp, err := h.service.CreateExperiment(ctx, exp)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to create experiment",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusCreated, models.APIResponse{
		Code:    http.StatusCreated,
		Message: "Experiment created successfully",
		Data:    exp,
	})
}

func (h *Handler) ListExperiments(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	filters := make(map[string]interface{})
	if status := c.Query("status"); status != "" {
		filters["status"] = status
	}
	if expType := c.Query("type"); expType != "" {
		filters["type"] = expType
	}

	ctx := c.Request.Context()
	exps, total, err := h.service.ListExperiments(ctx, page, pageSize, filters)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to list experiments",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data: gin.H{
			"items":     exps,
			"total":     total,
			"page":      page,
			"page_size": pageSize,
		},
	})
}

func (h *Handler) GetExperiment(c *gin.Context) {
	id := c.Param("id")

	ctx := c.Request.Context()
	exp, err := h.service.GetExperiment(ctx, id)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to get experiment",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    exp,
	})
}

func (h *Handler) StartExperiment(c *gin.Context) {
	id := c.Param("id")

	ctx := c.Request.Context()
	exp, err := h.service.StartExperiment(ctx, id)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to start experiment",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Experiment started successfully",
		Data:    exp,
	})
}

func (h *Handler) RunBatchTest(c *gin.Context) {
	experimentID := c.Param("id")

	var req struct {
		TestCases   []*models.TestCase `json:"test_cases" binding:"required"`
		Parallel    bool               `json:"parallel"`
		Concurrency int                `json:"concurrency"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	batchReq := &models.BatchTestRequest{
		ExperimentID: experimentID,
		TestCases:    req.TestCases,
		Parallel:     req.Parallel,
		Concurrency:  req.Concurrency,
	}

	ctx := c.Request.Context()
	runID, err := h.service.RunBatchTest(ctx, batchReq)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to run batch test",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusAccepted, models.APIResponse{
		Code:    http.StatusAccepted,
		Message: "Batch test started successfully",
		Data: gin.H{
			"run_id": runID,
			"status": "running",
		},
	})
}

func (h *Handler) GetRunStatus(c *gin.Context) {
	runID := c.Param("run_id")

	ctx := c.Request.Context()
	run, err := h.service.GetRunStatus(ctx, runID)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to get run status",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    run,
	})
}

func (h *Handler) CancelRun(c *gin.Context) {
	runID := c.Param("run_id")

	ctx := c.Request.Context()
	if err := h.service.CancelRun(ctx, runID); err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to cancel run",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Run cancellation initiated",
	})
}

func (h *Handler) GetEvaluation(c *gin.Context) {
	runID := c.Param("run_id")

	ctx := c.Request.Context()
	eval, err := h.service.GetEvaluation(ctx, runID)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to get evaluation",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    eval,
	})
}
