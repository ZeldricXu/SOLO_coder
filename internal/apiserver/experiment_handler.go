package apiserver

import (
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"gorm.io/gorm"

	"github.com/df1-96/experiment/internal/models"
	"github.com/df1-96/experiment/pkg/util"
)

type ExperimentHandler struct {
	db     *gorm.DB
	logger *zap.Logger
}

func NewExperimentHandler(db *gorm.DB, logger *zap.Logger) *ExperimentHandler {
	return &ExperimentHandler{
		db:     db,
		logger: logger.With(zap.String("handler", "experiment")),
	}
}

type CreateExperimentRequest struct {
	Name        string                 `json:"name" binding:"required,max=255"`
	Description string                 `json:"description"`
	Params      map[string]interface{} `json:"params" binding:"required"`
	Config      map[string]interface{} `json:"config"`
}

type UpdateExperimentRequest struct {
	Name        string                 `json:"name" binding:"max=255"`
	Description string                 `json:"description"`
	Params      map[string]interface{} `json:"params"`
	Config      map[string]interface{} `json:"config"`
	Status      string                 `json:"status"`
}

type ExperimentListResponse struct {
	Total int64                `json:"total"`
	Page  int                  `json:"page"`
	Size  int                  `json:"size"`
	Items []models.Experiment  `json:"items"`
}

func (h *ExperimentHandler) List(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	size, _ := strconv.Atoi(c.DefaultQuery("size", "20"))
	status := c.Query("status")
	search := c.Query("search")
	createdBy := c.Query("created_by")
	sortBy := c.DefaultQuery("sort_by", "created_at")
	sortDir := c.DefaultQuery("sort_dir", "desc")

	if page < 1 {
		page = 1
	}
	if size < 1 || size > 100 {
		size = 20
	}
	offset := (page - 1) * size

	query := h.db.Model(&models.Experiment{})

	if status != "" {
		statuses := strings.Split(status, ",")
		query = query.Where("status IN ?", statuses)
	}

	if search != "" {
		query = query.Where("name ILIKE ? OR description ILIKE ?", "%"+search+"%", "%"+search+"%")
	}

	if createdBy != "" {
		if userID, err := strconv.ParseInt(createdBy, 10, 64); err == nil {
			query = query.Where("created_by = ?", userID)
		}
	}

	validSortFields := map[string]bool{
		"id":         true,
		"name":       true,
		"status":     true,
		"created_at": true,
		"updated_at": true,
		"start_time": true,
		"end_time":   true,
	}
	if !validSortFields[sortBy] {
		sortBy = "created_at"
	}
	if sortDir != "asc" && sortDir != "desc" {
		sortDir = "desc"
	}
	query = query.Order(sortBy + " " + sortDir)

	var total int64
	if err := query.Count(&total).Error; err != nil {
		h.logger.Error("Failed to count experiments", zap.Error(err))
		RecordAPIError("GET", "/api/v1/experiments", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to count experiments",
			"code":  500,
		})
		return
	}

	var experiments []models.Experiment
	if err := query.Preload("Tasks").Offset(offset).Limit(size).Find(&experiments).Error; err != nil {
		h.logger.Error("Failed to list experiments", zap.Error(err))
		RecordAPIError("GET", "/api/v1/experiments", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to list experiments",
			"code":  500,
		})
		return
	}

	c.Header("X-Total-Count", strconv.FormatInt(total, 10))
	c.JSON(http.StatusOK, ExperimentListResponse{
		Total: total,
		Page:  page,
		Size:  size,
		Items: experiments,
	})
}

func (h *ExperimentHandler) Create(c *gin.Context) {
	var req CreateExperimentRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.logger.Warn("Invalid create experiment request", zap.Error(err))
		RecordAPIError("POST", "/api/v1/experiments", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": err.Error(),
			"code":  400,
		})
		return
	}

	userID, _ := c.Get("user_id")

	experiment := &models.Experiment{
		ID:          util.GenerateID(),
		Name:        req.Name,
		Description: req.Description,
		Status:      models.ExperimentStatusPending,
		Params:      req.Params,
		Config:      req.Config,
		CreatedBy:   userID.(int64),
	}

	if err := h.db.Create(experiment).Error; err != nil {
		h.logger.Error("Failed to create experiment", zap.Error(err))
		RecordAPIError("POST", "/api/v1/experiments", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to create experiment",
			"code":  500,
		})
		return
	}

	RecordExperimentStatus(string(experiment.Status), 1)

	h.logger.Info("Experiment created", zap.Int64("experiment_id", experiment.ID), zap.String("name", experiment.Name))
	c.JSON(http.StatusCreated, experiment)
}

func (h *ExperimentHandler) Get(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		RecordAPIError("GET", "/api/v1/experiments/:id", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid experiment ID",
			"code":  400,
		})
		return
	}

	var experiment models.Experiment
	if err := h.db.Preload("Tasks").First(&experiment, id).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			RecordAPIError("GET", "/api/v1/experiments/:id", "404")
			c.JSON(http.StatusNotFound, gin.H{
				"error": "Experiment not found",
				"code":  404,
			})
			return
		}
		h.logger.Error("Failed to get experiment", zap.Error(err), zap.Int64("id", id))
		RecordAPIError("GET", "/api/v1/experiments/:id", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get experiment",
			"code":  500,
		})
		return
	}

	c.JSON(http.StatusOK, experiment)
}

func (h *ExperimentHandler) Update(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		RecordAPIError("PUT", "/api/v1/experiments/:id", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid experiment ID",
			"code":  400,
		})
		return
	}

	var experiment models.Experiment
	if err := h.db.First(&experiment, id).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			RecordAPIError("PUT", "/api/v1/experiments/:id", "404")
			c.JSON(http.StatusNotFound, gin.H{
				"error": "Experiment not found",
				"code":  404,
			})
			return
		}
		h.logger.Error("Failed to get experiment", zap.Error(err), zap.Int64("id", id))
		RecordAPIError("PUT", "/api/v1/experiments/:id", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get experiment",
			"code":  500,
		})
		return
	}

	var req UpdateExperimentRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.logger.Warn("Invalid update experiment request", zap.Error(err))
		RecordAPIError("PUT", "/api/v1/experiments/:id", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": err.Error(),
			"code":  400,
		})
		return
	}

	if req.Name != "" {
		experiment.Name = req.Name
	}
	if req.Description != "" {
		experiment.Description = req.Description
	}
	if req.Params != nil {
		experiment.Params = req.Params
	}
	if req.Config != nil {
		experiment.Config = req.Config
	}
	if req.Status != "" {
		experiment.Status = models.ExperimentStatus(req.Status)
	}

	if err := h.db.Save(&experiment).Error; err != nil {
		h.logger.Error("Failed to update experiment", zap.Error(err), zap.Int64("id", id))
		RecordAPIError("PUT", "/api/v1/experiments/:id", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to update experiment",
			"code":  500,
		})
		return
	}

	h.logger.Info("Experiment updated", zap.Int64("experiment_id", id))
	c.JSON(http.StatusOK, experiment)
}

func (h *ExperimentHandler) Delete(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		RecordAPIError("DELETE", "/api/v1/experiments/:id", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid experiment ID",
			"code":  400,
		})
		return
	}

	result := h.db.Delete(&models.Experiment{}, id)
	if result.Error != nil {
		h.logger.Error("Failed to delete experiment", zap.Error(result.Error), zap.Int64("id", id))
		RecordAPIError("DELETE", "/api/v1/experiments/:id", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to delete experiment",
			"code":  500,
		})
		return
	}

	if result.RowsAffected == 0 {
		RecordAPIError("DELETE", "/api/v1/experiments/:id", "404")
		c.JSON(http.StatusNotFound, gin.H{
			"error": "Experiment not found",
			"code":  404,
		})
		return
	}

	h.logger.Info("Experiment deleted", zap.Int64("experiment_id", id))
	c.JSON(http.StatusOK, gin.H{
		"message": "Experiment deleted successfully",
		"code":    200,
	})
}

func (h *ExperimentHandler) Start(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		RecordAPIError("POST", "/api/v1/experiments/:id/start", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid experiment ID",
			"code":  400,
		})
		return
	}

	var experiment models.Experiment
	if err := h.db.First(&experiment, id).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			RecordAPIError("POST", "/api/v1/experiments/:id/start", "404")
			c.JSON(http.StatusNotFound, gin.H{
				"error": "Experiment not found",
				"code":  404,
			})
			return
		}
		h.logger.Error("Failed to get experiment", zap.Error(err), zap.Int64("id", id))
		RecordAPIError("POST", "/api/v1/experiments/:id/start", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get experiment",
			"code":  500,
		})
		return
	}

	if experiment.Status != models.ExperimentStatusPending {
		RecordAPIError("POST", "/api/v1/experiments/:id/start", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Experiment can only be started from pending status",
			"code":  400,
		})
		return
	}

	now := time.Now()
	experiment.Status = models.ExperimentStatusRunning
	experiment.StartTime = &now

	if err := h.db.Save(&experiment).Error; err != nil {
		h.logger.Error("Failed to start experiment", zap.Error(err), zap.Int64("id", id))
		RecordAPIError("POST", "/api/v1/experiments/:id/start", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to start experiment",
			"code":  500,
		})
		return
	}

	RecordExperimentStatus(string(models.ExperimentStatusPending), -1)
	RecordExperimentStatus(string(models.ExperimentStatusRunning), 1)

	h.logger.Info("Experiment started", zap.Int64("experiment_id", id))
	c.JSON(http.StatusOK, gin.H{
		"message": "Experiment started successfully",
		"code":    200,
		"data":    experiment,
	})
}

func (h *ExperimentHandler) Pause(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		RecordAPIError("POST", "/api/v1/experiments/:id/pause", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid experiment ID",
			"code":  400,
		})
		return
	}

	var experiment models.Experiment
	if err := h.db.First(&experiment, id).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			RecordAPIError("POST", "/api/v1/experiments/:id/pause", "404")
			c.JSON(http.StatusNotFound, gin.H{
				"error": "Experiment not found",
				"code":  404,
			})
			return
		}
		h.logger.Error("Failed to get experiment", zap.Error(err), zap.Int64("id", id))
		RecordAPIError("POST", "/api/v1/experiments/:id/pause", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get experiment",
			"code":  500,
		})
		return
	}

	if experiment.Status != models.ExperimentStatusRunning {
		RecordAPIError("POST", "/api/v1/experiments/:id/pause", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Only running experiments can be paused",
			"code":  400,
		})
		return
	}

	experiment.Status = "paused"

	if err := h.db.Save(&experiment).Error; err != nil {
		h.logger.Error("Failed to pause experiment", zap.Error(err), zap.Int64("id", id))
		RecordAPIError("POST", "/api/v1/experiments/:id/pause", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to pause experiment",
			"code":  500,
		})
		return
	}

	RecordExperimentStatus(string(models.ExperimentStatusRunning), -1)
	RecordExperimentStatus("paused", 1)

	h.logger.Info("Experiment paused", zap.Int64("experiment_id", id))
	c.JSON(http.StatusOK, gin.H{
		"message": "Experiment paused successfully",
		"code":    200,
		"data":    experiment,
	})
}

func (h *ExperimentHandler) Resume(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		RecordAPIError("POST", "/api/v1/experiments/:id/resume", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid experiment ID",
			"code":  400,
		})
		return
	}

	var experiment models.Experiment
	if err := h.db.First(&experiment, id).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			RecordAPIError("POST", "/api/v1/experiments/:id/resume", "404")
			c.JSON(http.StatusNotFound, gin.H{
				"error": "Experiment not found",
				"code":  404,
			})
			return
		}
		h.logger.Error("Failed to get experiment", zap.Error(err), zap.Int64("id", id))
		RecordAPIError("POST", "/api/v1/experiments/:id/resume", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get experiment",
			"code":  500,
		})
		return
	}

	if experiment.Status != "paused" {
		RecordAPIError("POST", "/api/v1/experiments/:id/resume", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Only paused experiments can be resumed",
			"code":  400,
		})
		return
	}

	experiment.Status = models.ExperimentStatusRunning

	if err := h.db.Save(&experiment).Error; err != nil {
		h.logger.Error("Failed to resume experiment", zap.Error(err), zap.Int64("id", id))
		RecordAPIError("POST", "/api/v1/experiments/:id/resume", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to resume experiment",
			"code":  500,
		})
		return
	}

	RecordExperimentStatus("paused", -1)
	RecordExperimentStatus(string(models.ExperimentStatusRunning), 1)

	h.logger.Info("Experiment resumed", zap.Int64("experiment_id", id))
	c.JSON(http.StatusOK, gin.H{
		"message": "Experiment resumed successfully",
		"code":    200,
		"data":    experiment,
	})
}

func (h *ExperimentHandler) Cancel(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		RecordAPIError("POST", "/api/v1/experiments/:id/cancel", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid experiment ID",
			"code":  400,
		})
		return
	}

	var experiment models.Experiment
	if err := h.db.First(&experiment, id).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			RecordAPIError("POST", "/api/v1/experiments/:id/cancel", "404")
			c.JSON(http.StatusNotFound, gin.H{
				"error": "Experiment not found",
				"code":  404,
			})
			return
		}
		h.logger.Error("Failed to get experiment", zap.Error(err), zap.Int64("id", id))
		RecordAPIError("POST", "/api/v1/experiments/:id/cancel", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get experiment",
			"code":  500,
		})
		return
	}

	if experiment.Status == models.ExperimentStatusCompleted ||
		experiment.Status == models.ExperimentStatusCanceled ||
		experiment.Status == models.ExperimentStatusFailed {
		RecordAPIError("POST", "/api/v1/experiments/:id/cancel", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Experiment is already in a terminal state",
			"code":  400,
		})
		return
	}

	now := time.Now()
	oldStatus := experiment.Status
	experiment.Status = models.ExperimentStatusCanceled
	experiment.EndTime = &now

	if err := h.db.Save(&experiment).Error; err != nil {
		h.logger.Error("Failed to cancel experiment", zap.Error(err), zap.Int64("id", id))
		RecordAPIError("POST", "/api/v1/experiments/:id/cancel", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to cancel experiment",
			"code":  500,
		})
		return
	}

	RecordExperimentStatus(string(oldStatus), -1)
	RecordExperimentStatus(string(models.ExperimentStatusCanceled), 1)

	if experiment.StartTime != nil {
		duration := now.Sub(*experiment.StartTime).Seconds()
		RecordExperimentDuration(string(models.ExperimentStatusCanceled), duration)
	}

	h.logger.Info("Experiment canceled", zap.Int64("experiment_id", id))
	c.JSON(http.StatusOK, gin.H{
		"message": "Experiment canceled successfully",
		"code":    200,
		"data":    experiment,
	})
}
