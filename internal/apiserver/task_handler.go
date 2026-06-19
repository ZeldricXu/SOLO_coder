package apiserver

import (
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"gorm.io/gorm"

	"github.com/df1-96/experiment/internal/models"
)

type TaskHandler struct {
	db     *gorm.DB
	logger *zap.Logger
}

func NewTaskHandler(db *gorm.DB, logger *zap.Logger) *TaskHandler {
	return &TaskHandler{
		db:     db,
		logger: logger.With(zap.String("handler", "task")),
	}
}

type TaskListResponse struct {
	Total int64          `json:"total"`
	Page  int            `json:"page"`
	Size  int            `json:"size"`
	Items []models.Task  `json:"items"`
}

func (h *TaskHandler) ListByExperiment(c *gin.Context) {
	expID, err := strconv.ParseInt(c.Param("expId"), 10, 64)
	if err != nil {
		RecordAPIError("GET", "/api/v1/experiments/:expId/tasks", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid experiment ID",
			"code":  400,
		})
		return
	}

	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	size, _ := strconv.Atoi(c.DefaultQuery("size", "50"))
	status := c.Query("status")
	search := c.Query("search")
	workerID := c.Query("worker_id")
	sortBy := c.DefaultQuery("sort_by", "created_at")
	sortDir := c.DefaultQuery("sort_dir", "desc")

	if page < 1 {
		page = 1
	}
	if size < 1 || size > 200 {
		size = 50
	}
	offset := (page - 1) * size

	query := h.db.Model(&models.Task{}).Where("experiment_id = ?", expID)

	if status != "" {
		statuses := strings.Split(status, ",")
		query = query.Where("status IN ?", statuses)
	}

	if search != "" {
		query = query.Where("name ILIKE ?", "%"+search+"%")
	}

	if workerID != "" {
		if wID, err := strconv.ParseInt(workerID, 10, 64); err == nil {
			query = query.Where("worker_id = ?", wID)
		}
	}

	validSortFields := map[string]bool{
		"id":         true,
		"name":       true,
		"status":     true,
		"priority":   true,
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
		h.logger.Error("Failed to count tasks", zap.Error(err), zap.Int64("experiment_id", expID))
		RecordAPIError("GET", "/api/v1/experiments/:expId/tasks", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to count tasks",
			"code":  500,
		})
		return
	}

	var tasks []models.Task
	if err := query.Preload("Worker").Preload("Results").Preload("Checkpoints").
		Offset(offset).Limit(size).Find(&tasks).Error; err != nil {
		h.logger.Error("Failed to list tasks", zap.Error(err), zap.Int64("experiment_id", expID))
		RecordAPIError("GET", "/api/v1/experiments/:expId/tasks", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to list tasks",
			"code":  500,
		})
		return
	}

	c.Header("X-Total-Count", strconv.FormatInt(total, 10))
	c.JSON(http.StatusOK, TaskListResponse{
		Total: total,
		Page:  page,
		Size:  size,
		Items: tasks,
	})
}

func (h *TaskHandler) Get(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		RecordAPIError("GET", "/api/v1/tasks/:id", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid task ID",
			"code":  400,
		})
		return
	}

	var task models.Task
	if err := h.db.Preload("Experiment").Preload("Worker").Preload("Chunks").
		Preload("Results").Preload("Checkpoints").First(&task, id).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			RecordAPIError("GET", "/api/v1/tasks/:id", "404")
			c.JSON(http.StatusNotFound, gin.H{
				"error": "Task not found",
				"code":  404,
			})
			return
		}
		h.logger.Error("Failed to get task", zap.Error(err), zap.Int64("id", id))
		RecordAPIError("GET", "/api/v1/tasks/:id", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get task",
			"code":  500,
		})
		return
	}

	if task.Status == models.TaskStatusCompleted || task.Status == models.TaskStatusFailed {
		if task.StartTime != nil && task.EndTime != nil {
			duration := task.EndTime.Sub(*task.StartTime).Seconds()
			expID := strconv.FormatInt(task.ExperimentID, 10)
			RecordTaskExecution(expID, task.Name, string(task.Status), duration)
		}
	}

	c.JSON(http.StatusOK, task)
}

func (h *TaskHandler) GetCheckpoints(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		RecordAPIError("GET", "/api/v1/tasks/:id/checkpoints", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid task ID",
			"code":  400,
		})
		return
	}

	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	size, _ := strconv.Atoi(c.DefaultQuery("size", "100"))
	sortBy := c.DefaultQuery("sort_by", "step")
	sortDir := c.DefaultQuery("sort_dir", "asc")

	if page < 1 {
		page = 1
	}
	if size < 1 || size > 500 {
		size = 100
	}
	offset := (page - 1) * size

	var task models.Task
	if err := h.db.First(&task, id).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			RecordAPIError("GET", "/api/v1/tasks/:id/checkpoints", "404")
			c.JSON(http.StatusNotFound, gin.H{
				"error": "Task not found",
				"code":  404,
			})
			return
		}
		h.logger.Error("Failed to get task", zap.Error(err), zap.Int64("id", id))
		RecordAPIError("GET", "/api/v1/tasks/:id/checkpoints", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get task",
			"code":  500,
		})
		return
	}

	query := h.db.Model(&models.Checkpoint{}).Where("task_id = ?", id)

	validSortFields := map[string]bool{
		"id":         true,
		"step":       true,
		"created_at": true,
	}
	if !validSortFields[sortBy] {
		sortBy = "step"
	}
	if sortDir != "asc" && sortDir != "desc" {
		sortDir = "asc"
	}
	query = query.Order(sortBy + " " + sortDir)

	var total int64
	if err := query.Count(&total).Error; err != nil {
		h.logger.Error("Failed to count checkpoints", zap.Error(err), zap.Int64("task_id", id))
		RecordAPIError("GET", "/api/v1/tasks/:id/checkpoints", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to count checkpoints",
			"code":  500,
		})
		return
	}

	var checkpoints []models.Checkpoint
	if err := query.Preload("Worker").Offset(offset).Limit(size).Find(&checkpoints).Error; err != nil {
		h.logger.Error("Failed to list checkpoints", zap.Error(err), zap.Int64("task_id", id))
		RecordAPIError("GET", "/api/v1/tasks/:id/checkpoints", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to list checkpoints",
			"code":  500,
		})
		return
	}

	c.Header("X-Total-Count", strconv.FormatInt(total, 10))
	c.JSON(http.StatusOK, gin.H{
		"total": total,
		"page":  page,
		"size":  size,
		"items": checkpoints,
	})
}

func (h *TaskHandler) GetResults(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		RecordAPIError("GET", "/api/v1/tasks/:id/results", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid task ID",
			"code":  400,
		})
		return
	}

	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	size, _ := strconv.Atoi(c.DefaultQuery("size", "100"))
	sortBy := c.DefaultQuery("sort_by", "created_at")
	sortDir := c.DefaultQuery("sort_dir", "desc")

	if page < 1 {
		page = 1
	}
	if size < 1 || size > 500 {
		size = 100
	}
	offset := (page - 1) * size

	var task models.Task
	if err := h.db.First(&task, id).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			RecordAPIError("GET", "/api/v1/tasks/:id/results", "404")
			c.JSON(http.StatusNotFound, gin.H{
				"error": "Task not found",
				"code":  404,
			})
			return
		}
		h.logger.Error("Failed to get task", zap.Error(err), zap.Int64("id", id))
		RecordAPIError("GET", "/api/v1/tasks/:id/results", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get task",
			"code":  500,
		})
		return
	}

	query := h.db.Model(&models.Result{}).Where("task_id = ?", id)

	validSortFields := map[string]bool{
		"id":         true,
		"iteration":  true,
		"duration_ms": true,
		"created_at": true,
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
		h.logger.Error("Failed to count results", zap.Error(err), zap.Int64("task_id", id))
		RecordAPIError("GET", "/api/v1/tasks/:id/results", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to count results",
			"code":  500,
		})
		return
	}

	var results []models.Result
	if err := query.Preload("Worker").Preload("Chunk").Offset(offset).Limit(size).Find(&results).Error; err != nil {
		h.logger.Error("Failed to list results", zap.Error(err), zap.Int64("task_id", id))
		RecordAPIError("GET", "/api/v1/tasks/:id/results", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to list results",
			"code":  500,
		})
		return
	}

	c.Header("X-Total-Count", strconv.FormatInt(total, 10))
	c.JSON(http.StatusOK, gin.H{
		"total": total,
		"page":  page,
		"size":  size,
		"items": results,
	})
}
