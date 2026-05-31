package adversarial

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
	jobs := r.Group("/attack-jobs")
	{
		jobs.POST("", h.CreateJob)
		jobs.GET("", h.ListJobs)
		jobs.GET("/:id", h.GetJob)
		jobs.POST("/:id/start", h.StartJob)
		jobs.POST("/batch-attack", h.StartBatchAttack)
		jobs.GET("/runs/:run_id", h.GetRunStatus)
		jobs.POST("/runs/:run_id/cancel", h.CancelRun)
		jobs.GET("/:id/results", h.ListResults)
		jobs.GET("/:id/assessment", h.GetAssessment)
	}

	r.POST("/merge-jobs", h.MergeJobs)
	r.GET("/attack-strategies", h.ListStrategies)
	r.GET("/attack-templates", h.ListTemplates)
	r.POST("/attack-templates", h.CreateTemplate)
}

type CreateJobRequest struct {
	Name            string                   `json:"name" binding:"required"`
	Description     string                   `json:"description"`
	TargetModel     string                   `json:"target_model" binding:"required"`
	Strategies      []string                 `json:"strategies"`
	SeverityFilter  []string                 `json:"severity_filter"`
	BasePrompts     []string                 `json:"base_prompts" binding:"required"`
	TargetBehaviors []string                 `json:"target_behaviors"`
	Concurrency     int                      `json:"concurrency"`
	BatchSize       int                      `json:"batch_size"`
	MergeStrategy   string                   `json:"merge_strategy"`
	CreatedBy       string                   `json:"created_by" binding:"required"`
}

func (h *Handler) CreateJob(c *gin.Context) {
	var req CreateJobRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		logger.FromContext(c.Request.Context()).Warn("invalid create attack job request", zap.Error(err))
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	ctx := c.Request.Context()
	job := &models.AttackJob{
		Name:            req.Name,
		Description:     req.Description,
		TargetModel:     req.TargetModel,
		Strategies:      req.Strategies,
		SeverityFilter:  req.SeverityFilter,
		BasePrompts:     req.BasePrompts,
		TargetBehaviors: req.TargetBehaviors,
		Concurrency:     req.Concurrency,
		BatchSize:       req.BatchSize,
		MergeStrategy:   req.MergeStrategy,
		CreatedBy:       req.CreatedBy,
	}

	job, err := h.service.CreateJob(ctx, job)
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
			Message: "Failed to create attack job",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusCreated, models.APIResponse{
		Code:    http.StatusCreated,
		Message: "Attack job created successfully",
		Data:    job,
	})
}

func (h *Handler) ListJobs(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	filters := make(map[string]interface{})
	if status := c.Query("status"); status != "" {
		filters["status"] = status
	}
	if targetModel := c.Query("target_model"); targetModel != "" {
		filters["target_model"] = targetModel
	}
	if createdBy := c.Query("created_by"); createdBy != "" {
		filters["created_by"] = createdBy
	}

	ctx := c.Request.Context()
	jobs, total, err := h.service.ListJobs(ctx, page, pageSize, filters)
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
			Message: "Failed to list attack jobs",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data: gin.H{
			"items":     jobs,
			"total":     total,
			"page":      page,
			"page_size": pageSize,
		},
	})
}

func (h *Handler) GetJob(c *gin.Context) {
	id := c.Param("id")

	ctx := c.Request.Context()
	job, err := h.service.GetJob(ctx, id)
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
			Message: "Failed to get attack job",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    job,
	})
}

func (h *Handler) StartJob(c *gin.Context) {
	id := c.Param("id")

	job, err := h.service.GetJob(c.Request.Context(), id)
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
			Message: "Failed to get attack job",
			Data:    err.Error(),
		})
		return
	}

	batchReq := &models.BatchAttackRequest{
		JobID:           id,
		BasePrompts:     job.BasePrompts,
		Strategies:      job.Strategies,
		Severities:      job.SeverityFilter,
		TargetBehaviors: job.TargetBehaviors,
		Concurrency:     job.Concurrency,
		MergeStrategy:   job.MergeStrategy,
	}

	h.StartBatchAttackWithRequest(c, batchReq)
}

func (h *Handler) StartBatchAttack(c *gin.Context) {
	var req models.BatchAttackRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	h.StartBatchAttackWithRequest(c, &req)
}

func (h *Handler) StartBatchAttackWithRequest(c *gin.Context, req *models.BatchAttackRequest) {
	ctx := c.Request.Context()
	runID, err := h.service.StartBatchAttack(ctx, req)
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
			Message: "Failed to start batch attack",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusAccepted, models.APIResponse{
		Code:    http.StatusAccepted,
		Message: "Batch attack started successfully",
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

func (h *Handler) MergeJobs(c *gin.Context) {
	var req models.MergeRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	ctx := c.Request.Context()
	mergedJob, err := h.service.MergeJobs(ctx, &req)
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
			Message: "Failed to merge jobs",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Jobs merged successfully",
		Data: gin.H{
			"merged_job_id": mergedJob.ID,
			"job":           mergedJob,
		},
	})
}

func (h *Handler) ListResults(c *gin.Context) {
	jobID := c.Param("id")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	ctx := c.Request.Context()
	results, total, err := h.service.ListAttackResults(ctx, jobID, page, pageSize)
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
			Message: "Failed to list attack results",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data: gin.H{
			"items":     results,
			"total":     total,
			"page":      page,
			"page_size": pageSize,
		},
	})
}

func (h *Handler) GetAssessment(c *gin.Context) {
	jobID := c.Param("id")

	ctx := c.Request.Context()
	assessment, err := h.service.GetSecurityAssessment(ctx, jobID)
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
			Message: "Failed to get security assessment",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    assessment,
	})
}

func (h *Handler) ListStrategies(c *gin.Context) {
	ctx := c.Request.Context()
	strategies := h.service.GetStrategies(ctx)

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    strategies,
	})
}

func (h *Handler) ListTemplates(c *gin.Context) {
	ctx := c.Request.Context()
	templates := h.service.GetTemplates(ctx)

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    templates,
	})
}

type CreateTemplateRequest struct {
	Name        string            `json:"name" binding:"required"`
	Strategy    string            `json:"strategy" binding:"required"`
	Severity    string            `json:"severity" binding:"required"`
	Template    string            `json:"template" binding:"required"`
	Description string            `json:"description"`
	Variables   map[string]string `json:"variables"`
	Enabled     bool              `json:"enabled"`
}

func (h *Handler) CreateTemplate(c *gin.Context) {
	var req CreateTemplateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	ctx := c.Request.Context()
	tmpl := &models.AttackTemplate{
		Name:        req.Name,
		Strategy:    req.Strategy,
		Severity:    req.Severity,
		Template:    req.Template,
		Description: req.Description,
		Variables:   req.Variables,
		Enabled:     req.Enabled,
	}

	tmpl, err := h.service.CreateTemplate(ctx, tmpl)
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
			Message: "Failed to create attack template",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusCreated, models.APIResponse{
		Code:    http.StatusCreated,
		Message: "Attack template created successfully",
		Data:    tmpl,
	})
}
