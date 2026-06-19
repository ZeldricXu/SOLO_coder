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
)

type WorkerHandler struct {
	db     *gorm.DB
	logger *zap.Logger
}

func NewWorkerHandler(db *gorm.DB, logger *zap.Logger) *WorkerHandler {
	return &WorkerHandler{
		db:     db,
		logger: logger.With(zap.String("handler", "worker")),
	}
}

type WorkerListResponse struct {
	Total int64            `json:"total"`
	Page  int              `json:"page"`
	Size  int              `json:"size"`
	Items []models.Worker  `json:"items"`
}

type WorkerStats struct {
	ID               int64   `json:"id"`
	Name             string  `json:"name"`
	Status           string  `json:"status"`
	CPUUsagePercent  float64 `json:"cpu_usage_percent"`
	MemoryUsageBytes int64   `json:"memory_usage_bytes"`
	MemoryTotalBytes int64   `json:"memory_total_bytes"`
	TasksCompleted   int64   `json:"tasks_completed"`
	TasksFailed      int64   `json:"tasks_failed"`
	CurrentTaskID    *int64  `json:"current_task_id"`
	UptimeSeconds    int64   `json:"uptime_seconds"`
}

func (h *WorkerHandler) List(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	size, _ := strconv.Atoi(c.DefaultQuery("size", "50"))
	status := c.Query("status")
	search := c.Query("search")
	sortBy := c.DefaultQuery("sort_by", "created_at")
	sortDir := c.DefaultQuery("sort_dir", "desc")

	if page < 1 {
		page = 1
	}
	if size < 1 || size > 200 {
		size = 50
	}
	offset := (page - 1) * size

	query := h.db.Model(&models.Worker{})

	if status != "" {
		statuses := strings.Split(status, ",")
		query = query.Where("status IN ?", statuses)
	}

	if search != "" {
		query = query.Where("name ILIKE ? OR host ILIKE ?", "%"+search+"%", "%"+search+"%")
	}

	validSortFields := map[string]bool{
		"id":               true,
		"name":             true,
		"status":           true,
		"host":             true,
		"cpu_cores":        true,
		"memory_gb":        true,
		"tasks_completed":  true,
		"tasks_failed":     true,
		"last_heartbeat_at": true,
		"created_at":       true,
		"updated_at":       true,
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
		h.logger.Error("Failed to count workers", zap.Error(err))
		RecordAPIError("GET", "/api/v1/workers", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to count workers",
			"code":  500,
		})
		return
	}

	var workers []models.Worker
	if err := query.Preload("CurrentTask").Offset(offset).Limit(size).Find(&workers).Error; err != nil {
		h.logger.Error("Failed to list workers", zap.Error(err))
		RecordAPIError("GET", "/api/v1/workers", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to list workers",
			"code":  500,
		})
		return
	}

	var onlineCount int64
	for _, w := range workers {
		if w.Status == models.WorkerStatusIdle || w.Status == models.WorkerStatusRunning {
			onlineCount++
		}
	}
	RecordWorkerOnline(float64(onlineCount))

	c.Header("X-Total-Count", strconv.FormatInt(total, 10))
	c.JSON(http.StatusOK, WorkerListResponse{
		Total: total,
		Page:  page,
		Size:  size,
		Items: workers,
	})
}

func (h *WorkerHandler) Get(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		RecordAPIError("GET", "/api/v1/workers/:id", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid worker ID",
			"code":  400,
		})
		return
	}

	var worker models.Worker
	if err := h.db.Preload("CurrentTask").First(&worker, id).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			RecordAPIError("GET", "/api/v1/workers/:id", "404")
			c.JSON(http.StatusNotFound, gin.H{
				"error": "Worker not found",
				"code":  404,
			})
			return
		}
		h.logger.Error("Failed to get worker", zap.Error(err), zap.Int64("id", id))
		RecordAPIError("GET", "/api/v1/workers/:id", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get worker",
			"code":  500,
		})
		return
	}

	stats := WorkerStats{
		ID:               worker.ID,
		Name:             worker.Name,
		Status:           string(worker.Status),
		CPUUsagePercent:  0,
		MemoryUsageBytes: 0,
		MemoryTotalBytes: int64(worker.MemoryGB) * 1024 * 1024 * 1024,
		TasksCompleted:   worker.TasksCompleted,
		TasksFailed:      worker.TasksFailed,
		CurrentTaskID:    worker.CurrentTaskID,
		UptimeSeconds:    int64(time.Since(worker.CreatedAt).Seconds()),
	}

	RecordResourceUsage(strconv.FormatInt(worker.ID, 10), worker.Name, stats.CPUUsagePercent, 0, float64(stats.MemoryUsageBytes))

	c.JSON(http.StatusOK, gin.H{
		"worker": worker,
		"stats":  stats,
	})
}

func (h *WorkerHandler) GetHistory(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		RecordAPIError("GET", "/api/v1/workers/:id/history", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid worker ID",
			"code":  400,
		})
		return
	}

	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	size, _ := strconv.Atoi(c.DefaultQuery("size", "50"))
	status := c.Query("status")
	sortBy := c.DefaultQuery("sort_by", "end_time")
	sortDir := c.DefaultQuery("sort_dir", "desc")

	if page < 1 {
		page = 1
	}
	if size < 1 || size > 200 {
		size = 50
	}
	offset := (page - 1) * size

	var worker models.Worker
	if err := h.db.First(&worker, id).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			RecordAPIError("GET", "/api/v1/workers/:id/history", "404")
			c.JSON(http.StatusNotFound, gin.H{
				"error": "Worker not found",
				"code":  404,
			})
			return
		}
		h.logger.Error("Failed to get worker", zap.Error(err), zap.Int64("id", id))
		RecordAPIError("GET", "/api/v1/workers/:id/history", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get worker",
			"code":  500,
		})
		return
	}

	query := h.db.Model(&models.Task{}).Where("worker_id = ?", id)

	if status != "" {
		statuses := strings.Split(status, ",")
		query = query.Where("status IN ?", statuses)
	}

	validSortFields := map[string]bool{
		"id":         true,
		"name":       true,
		"status":     true,
		"start_time": true,
		"end_time":   true,
		"created_at": true,
	}
	if !validSortFields[sortBy] {
		sortBy = "end_time"
	}
	if sortDir != "asc" && sortDir != "desc" {
		sortDir = "desc"
	}
	query = query.Order(sortBy + " " + sortDir)

	var total int64
	if err := query.Count(&total).Error; err != nil {
		h.logger.Error("Failed to count worker history", zap.Error(err), zap.Int64("worker_id", id))
		RecordAPIError("GET", "/api/v1/workers/:id/history", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to count worker history",
			"code":  500,
		})
		return
	}

	var tasks []models.Task
	if err := query.Preload("Experiment").Offset(offset).Limit(size).Find(&tasks).Error; err != nil {
		h.logger.Error("Failed to list worker history", zap.Error(err), zap.Int64("worker_id", id))
		RecordAPIError("GET", "/api/v1/workers/:id/history", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to list worker history",
			"code":  500,
		})
		return
	}

	c.Header("X-Total-Count", strconv.FormatInt(total, 10))
	c.JSON(http.StatusOK, gin.H{
		"total":   total,
		"page":    page,
		"size":    size,
		"worker":  worker,
		"history": tasks,
	})
}
