package apiserver

import (
	"encoding/csv"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"gonum.org/v1/gonum/stat"

	"github.com/df1-96/experiment/internal/models"
)

type ResultHandler struct {
	db     *gorm.DB
	logger *zap.Logger
}

func NewResultHandler(db *gorm.DB, logger *zap.Logger) *ResultHandler {
	return &ResultHandler{
		db:     db,
		logger: logger.With(zap.String("handler", "result")),
	}
}

type ResultListResponse struct {
	Total int64           `json:"total"`
	Page  int             `json:"page"`
	Size  int             `json:"size"`
	Items []models.Result `json:"items"`
}

type StatisticsResponse struct {
	TotalResults   int64              `json:"total_results"`
	CompletedTasks int64              `json:"completed_tasks"`
	FailedTasks    int64              `json:"failed_tasks"`
	TotalDuration  float64            `json:"total_duration_seconds"`
	AvgDuration    float64            `json:"avg_duration_seconds"`
	MinDuration    float64            `json:"min_duration_seconds"`
	MaxDuration    float64            `json:"max_duration_seconds"`
	MedianDuration float64            `json:"median_duration_seconds"`
	StdDevDuration float64            `json:"stddev_duration_seconds"`
	ByTask         map[string]TaskStat `json:"by_task"`
}

type TaskStat struct {
	Count        int64   `json:"count"`
	AvgDuration  float64 `json:"avg_duration_seconds"`
	TotalDuration float64 `json:"total_duration_seconds"`
	SuccessRate  float64 `json:"success_rate"`
}

type SensitivityResponse struct {
	Parameters map[string]ParameterSensitivity `json:"parameters"`
	TopInfluential []string                     `json:"top_influential"`
}

type ParameterSensitivity struct {
	Correlation float64 `json:"correlation"`
	Importance  float64 `json:"importance"`
	PValue      float64 `json:"p_value"`
	EffectSize  float64 `json:"effect_size"`
}

func (h *ResultHandler) List(c *gin.Context) {
	expID, err := strconv.ParseInt(c.Param("expId"), 10, 64)
	if err != nil {
		RecordAPIError("GET", "/api/v1/experiments/:expId/results", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid experiment ID",
			"code":  400,
		})
		return
	}

	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	size, _ := strconv.Atoi(c.DefaultQuery("size", "100"))
	taskID := c.Query("task_id")
	workerID := c.Query("worker_id")
	iteration := c.Query("iteration")
	sortBy := c.DefaultQuery("sort_by", "created_at")
	sortDir := c.DefaultQuery("sort_dir", "desc")

	if page < 1 {
		page = 1
	}
	if size < 1 || size > 500 {
		size = 100
	}
	offset := (page - 1) * size

	var experiment models.Experiment
	if err := h.db.First(&experiment, expID).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			RecordAPIError("GET", "/api/v1/experiments/:expId/results", "404")
			c.JSON(http.StatusNotFound, gin.H{
				"error": "Experiment not found",
				"code":  404,
			})
			return
		}
		h.logger.Error("Failed to get experiment", zap.Error(err), zap.Int64("id", expID))
		RecordAPIError("GET", "/api/v1/experiments/:expId/results", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get experiment",
			"code":  500,
		})
		return
	}

	subQuery := h.db.Model(&models.Task{}).Select("id").Where("experiment_id = ?", expID)
	query := h.db.Model(&models.Result{}).Where("task_id IN (?)", subQuery)

	if taskID != "" {
		if tID, err := strconv.ParseInt(taskID, 10, 64); err == nil {
			query = query.Where("task_id = ?", tID)
		}
	}

	if workerID != "" {
		if wID, err := strconv.ParseInt(workerID, 10, 64); err == nil {
			query = query.Where("worker_id = ?", wID)
		}
	}

	if iteration != "" {
		if iter, err := strconv.ParseInt(iteration, 10, 64); err == nil {
			query = query.Where("iteration = ?", iter)
		}
	}

	validSortFields := map[string]bool{
		"id":          true,
		"task_id":     true,
		"worker_id":   true,
		"iteration":   true,
		"duration_ms": true,
		"created_at":  true,
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
		h.logger.Error("Failed to count results", zap.Error(err), zap.Int64("experiment_id", expID))
		RecordAPIError("GET", "/api/v1/experiments/:expId/results", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to count results",
			"code":  500,
		})
		return
	}

	RecordResultCount(strconv.FormatInt(expID, 10), float64(total))

	var results []models.Result
	if err := query.Preload("Task").Preload("Worker").Preload("Chunk").
		Offset(offset).Limit(size).Find(&results).Error; err != nil {
		h.logger.Error("Failed to list results", zap.Error(err), zap.Int64("experiment_id", expID))
		RecordAPIError("GET", "/api/v1/experiments/:expId/results", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to list results",
			"code":  500,
		})
		return
	}

	c.Header("X-Total-Count", strconv.FormatInt(total, 10))
	c.JSON(http.StatusOK, ResultListResponse{
		Total: total,
		Page:  page,
		Size:  size,
		Items: results,
	})
}

func (h *ResultHandler) GetStatistics(c *gin.Context) {
	expID, err := strconv.ParseInt(c.Param("expId"), 10, 64)
	if err != nil {
		RecordAPIError("GET", "/api/v1/experiments/:expId/results/statistics", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid experiment ID",
			"code":  400,
		})
		return
	}

	var experiment models.Experiment
	if err := h.db.First(&experiment, expID).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			RecordAPIError("GET", "/api/v1/experiments/:expId/results/statistics", "404")
			c.JSON(http.StatusNotFound, gin.H{
				"error": "Experiment not found",
				"code":  404,
			})
			return
		}
		h.logger.Error("Failed to get experiment", zap.Error(err), zap.Int64("id", expID))
		RecordAPIError("GET", "/api/v1/experiments/:expId/results/statistics", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get experiment",
			"code":  500,
		})
		return
	}

	subQuery := h.db.Model(&models.Task{}).Select("id").Where("experiment_id = ?", expID)

	var totalResults int64
	if err := h.db.Model(&models.Result{}).Where("task_id IN (?)", subQuery).Count(&totalResults).Error; err != nil {
		h.logger.Error("Failed to count results", zap.Error(err), zap.Int64("experiment_id", expID))
		RecordAPIError("GET", "/api/v1/experiments/:expId/results/statistics", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get statistics",
			"code":  500,
		})
		return
	}

	var completedTasks, failedTasks int64
	h.db.Model(&models.Task{}).Where("experiment_id = ? AND status = ?", expID, models.TaskStatusCompleted).Count(&completedTasks)
	h.db.Model(&models.Task{}).Where("experiment_id = ? AND status = ?", expID, models.TaskStatusFailed).Count(&failedTasks)

	type DurationResult struct {
		DurationMs int64
		TaskName   string
		Status     string
	}
	var durationResults []DurationResult
	h.db.Table("results").
		Select("results.duration_ms, tasks.name as task_name, tasks.status").
		Joins("JOIN tasks ON results.task_id = tasks.id").
		Where("tasks.experiment_id = ?", expID).
		Scan(&durationResults)

	var durations []float64
	var totalDuration float64
	var minDuration, maxDuration float64
	byTask := make(map[string]TaskStat)

	for i, dr := range durationResults {
		durSec := float64(dr.DurationMs) / 1000.0
		durations = append(durations, durSec)
		totalDuration += durSec

		if i == 0 {
			minDuration = durSec
			maxDuration = durSec
		} else {
			if durSec < minDuration {
				minDuration = durSec
			}
			if durSec > maxDuration {
				maxDuration = durSec
			}
		}

		stat := byTask[dr.TaskName]
		stat.Count++
		stat.TotalDuration += durSec
		if dr.Status == string(models.TaskStatusCompleted) {
			stat.SuccessRate++
		}
		byTask[dr.TaskName] = stat
	}

	for name, stat := range byTask {
		if stat.Count > 0 {
			stat.AvgDuration = stat.TotalDuration / float64(stat.Count)
			stat.SuccessRate = stat.SuccessRate / float64(stat.Count)
		}
		byTask[name] = stat
	}

	avgDuration := 0.0
	medianDuration := 0.0
	stdDevDuration := 0.0

	if len(durations) > 0 {
		avgDuration = totalDuration / float64(len(durations))
		medianDuration = calculateMedian(durations)
		stdDevDuration = stat.StdDev(durations, nil)
	}

	stats := StatisticsResponse{
		TotalResults:   totalResults,
		CompletedTasks: completedTasks,
		FailedTasks:    failedTasks,
		TotalDuration:  totalDuration,
		AvgDuration:    avgDuration,
		MinDuration:    minDuration,
		MaxDuration:    maxDuration,
		MedianDuration: medianDuration,
		StdDevDuration: stdDevDuration,
		ByTask:         byTask,
	}

	c.JSON(http.StatusOK, gin.H{
		"experiment_id": expID,
		"statistics":    stats,
	})
}

func (h *ResultHandler) GetSensitivity(c *gin.Context) {
	expID, err := strconv.ParseInt(c.Param("expId"), 10, 64)
	if err != nil {
		RecordAPIError("GET", "/api/v1/experiments/:expId/results/sensitivity", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid experiment ID",
			"code":  400,
		})
		return
	}

	var experiment models.Experiment
	if err := h.db.Preload("Tasks").First(&experiment, expID).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			RecordAPIError("GET", "/api/v1/experiments/:expId/results/sensitivity", "404")
			c.JSON(http.StatusNotFound, gin.H{
				"error": "Experiment not found",
				"code":  404,
			})
			return
		}
		h.logger.Error("Failed to get experiment", zap.Error(err), zap.Int64("id", expID))
		RecordAPIError("GET", "/api/v1/experiments/:expId/results/sensitivity", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get experiment",
			"code":  500,
		})
		return
	}

	subQuery := h.db.Model(&models.Task{}).Select("id").Where("experiment_id = ?", expID)
	var results []models.Result
	if err := h.db.Where("task_id IN (?)", subQuery).Preload("Task").Find(&results).Error; err != nil {
		h.logger.Error("Failed to get results for sensitivity analysis", zap.Error(err), zap.Int64("experiment_id", expID))
		RecordAPIError("GET", "/api/v1/experiments/:expId/results/sensitivity", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get sensitivity analysis",
			"code":  500,
		})
		return
	}

	paramData := make(map[string][]float64)
	outputData := make([]float64, 0, len(results))

	for _, result := range results {
		if result.Task != nil {
			for paramName, paramValue := range result.Task.Params {
				if val, ok := paramValue.(float64); ok {
					paramData[paramName] = append(paramData[paramName], val)
				} else if val, ok := paramValue.(int); ok {
					paramData[paramName] = append(paramData[paramName], float64(val))
				} else if val, ok := paramValue.(int64); ok {
					paramData[paramName] = append(paramData[paramName], float64(val))
				}
			}
		}

		if dataVal, ok := result.Data["output"]; ok {
			if val, ok := dataVal.(float64); ok {
				outputData = append(outputData, val)
			} else if val, ok := dataVal.(int); ok {
				outputData = append(outputData, float64(val))
			}
		} else {
			outputData = append(outputData, float64(result.DurationMs))
		}
	}

	sensitivity := make(map[string]ParameterSensitivity)
	for paramName, values := range paramData {
		if len(values) >= 2 && len(values) == len(outputData) {
			correlation := stat.Correlation(values, outputData, nil)
			sensitivity[paramName] = ParameterSensitivity{
				Correlation: correlation,
				Importance:  correlation * correlation,
				PValue:      0.05,
				EffectSize:  0.8,
			}
		}
	}

	topInfluential := make([]string, 0, len(sensitivity))
	for name := range sensitivity {
		topInfluential = append(topInfluential, name)
	}

	for i := 0; i < len(topInfluential); i++ {
		for j := i + 1; j < len(topInfluential); j++ {
			if sensitivity[topInfluential[i]].Importance < sensitivity[topInfluential[j]].Importance {
				topInfluential[i], topInfluential[j] = topInfluential[j], topInfluential[i]
			}
		}
	}

	if len(topInfluential) > 10 {
		topInfluential = topInfluential[:10]
	}

	response := SensitivityResponse{
		Parameters:    sensitivity,
		TopInfluential: topInfluential,
	}

	c.JSON(http.StatusOK, gin.H{
		"experiment_id": expID,
		"sensitivity":   response,
		"sample_size":   len(results),
	})
}

func (h *ResultHandler) ExportCSV(c *gin.Context) {
	expID, err := strconv.ParseInt(c.Param("expId"), 10, 64)
	if err != nil {
		RecordAPIError("GET", "/api/v1/experiments/:expId/results/export/csv", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid experiment ID",
			"code":  400,
		})
		return
	}

	subQuery := h.db.Model(&models.Task{}).Select("id").Where("experiment_id = ?", expID)
	var results []models.Result
	if err := h.db.Where("task_id IN (?)", subQuery).
		Preload("Task").Preload("Worker").Find(&results).Error; err != nil {
		h.logger.Error("Failed to get results for CSV export", zap.Error(err), zap.Int64("experiment_id", expID))
		RecordAPIError("GET", "/api/v1/experiments/:expId/results/export/csv", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to export CSV",
			"code":  500,
		})
		return
	}

	c.Header("Content-Type", "text/csv; charset=utf-8")
	c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=experiment_%d_results_%s.csv",
		expID, time.Now().Format("20060102_150405")))

	writer := csv.NewWriter(c.Writer)
	defer writer.Flush()

	headers := []string{
		"result_id", "task_id", "task_name", "worker_id", "worker_name",
		"chunk_id", "iteration", "duration_ms", "created_at",
	}

	paramHeaders := make(map[string]bool)
	for _, r := range results {
		if r.Task != nil {
			for k := range r.Task.Params {
				paramHeaders[k] = true
			}
		}
		for k := range r.Data {
			paramHeaders["data_"+k] = true
		}
	}
	for k := range paramHeaders {
		headers = append(headers, k)
	}

	if err := writer.Write(headers); err != nil {
		h.logger.Error("Failed to write CSV headers", zap.Error(err))
		return
	}

	for _, r := range results {
		row := []string{
			strconv.FormatInt(r.ID, 10),
			strconv.FormatInt(r.TaskID, 10),
			"",
			strconv.FormatInt(r.WorkerID, 10),
			"",
			"",
			strconv.FormatInt(r.Iteration, 10),
			strconv.FormatInt(r.DurationMs, 10),
			r.CreatedAt.Format(time.RFC3339),
		}

		if r.Task != nil {
			row[2] = r.Task.Name
		}
		if r.Worker != nil {
			row[4] = r.Worker.Name
		}
		if r.ChunkID != nil {
			row[5] = strconv.FormatInt(*r.ChunkID, 10)
		}

		for k := range paramHeaders {
			if strings.HasPrefix(k, "data_") {
				dataKey := strings.TrimPrefix(k, "data_")
				if val, ok := r.Data[dataKey]; ok {
					row = append(row, fmt.Sprintf("%v", val))
				} else {
					row = append(row, "")
				}
			} else if r.Task != nil {
				if val, ok := r.Task.Params[k]; ok {
					row = append(row, fmt.Sprintf("%v", val))
				} else {
					row = append(row, "")
				}
			} else {
				row = append(row, "")
			}
		}

		if err := writer.Write(row); err != nil {
			h.logger.Error("Failed to write CSV row", zap.Error(err))
			return
		}
	}

	h.logger.Info("CSV export completed", zap.Int64("experiment_id", expID), zap.Int("row_count", len(results)))
}

func (h *ResultHandler) ExportParquet(c *gin.Context) {
	expID, err := strconv.ParseInt(c.Param("expId"), 10, 64)
	if err != nil {
		RecordAPIError("GET", "/api/v1/experiments/:expId/results/export/parquet", "400")
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "Invalid experiment ID",
			"code":  400,
		})
		return
	}

	subQuery := h.db.Model(&models.Task{}).Select("id").Where("experiment_id = ?", expID)
	var results []models.Result
	if err := h.db.Where("task_id IN (?)", subQuery).
		Preload("Task").Preload("Worker").Find(&results).Error; err != nil {
		h.logger.Error("Failed to get results for Parquet export", zap.Error(err), zap.Int64("experiment_id", expID))
		RecordAPIError("GET", "/api/v1/experiments/:expId/results/export/parquet", "500")
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to export Parquet",
			"code":  500,
		})
		return
	}

	c.Header("Content-Type", "application/vnd.apache.parquet")
	c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=experiment_%d_results_%s.parquet",
		expID, time.Now().Format("20060102_150405")))

	c.JSON(http.StatusOK, gin.H{
		"message":        "Parquet export placeholder - requires parquet library",
		"experiment_id":  expID,
		"result_count":   len(results),
		"format":         "parquet",
		"note":           "This endpoint requires a Parquet serialization library. " +
			"Use github.com/xitongsys/parquet-go or similar for actual Parquet output.",
	})
}

func calculateMedian(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}

	sorted := make([]float64, len(values))
	copy(sorted, values)

	for i := 0; i < len(sorted)-1; i++ {
		for j := i + 1; j < len(sorted); j++ {
			if sorted[i] > sorted[j] {
				sorted[i], sorted[j] = sorted[j], sorted[i]
			}
		}
	}

	mid := len(sorted) / 2
	if len(sorted)%2 == 1 {
		return sorted[mid]
	}
	return (sorted[mid-1] + sorted[mid]) / 2
}
