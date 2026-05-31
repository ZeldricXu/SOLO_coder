package api

import (
	"context"
	"go.uber.org/zap"
	"metricplatform/internal/models"
	"metricplatform/pkg/alertengine"
	"metricplatform/pkg/anomaly"
	"metricplatform/pkg/dataaccess"
	"metricplatform/pkg/logpipeline"
	"metricplatform/pkg/metrics"
	"metricplatform/pkg/scheduler"
	"metricplatform/pkg/slo"
	"metricplatform/pkg/storage"
	"metricplatform/pkg/tracing"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type Handler struct {
	repo           *dataaccess.Repository
	alertEngine    *alertengine.RuleEvaluator
	sloMonitor     *slo.Monitor
	anomalyDetect  *anomaly.Detector
	metricsCol     *metrics.Collector
	logPipeline    *logpipeline.Pipeline
	storageMgr     *storage.Manager
	traceCollector *tracing.Collector
	scheduler      *scheduler.Scheduler
	logger         *zap.Logger
}

func NewHandler(
	repo *dataaccess.Repository,
	alertEngine *alertengine.RuleEvaluator,
	sloMonitor *slo.Monitor,
	anomalyDetect *anomaly.Detector,
	metricsCol *metrics.Collector,
	logPipeline *logpipeline.Pipeline,
	storageMgr *storage.Manager,
	traceCollector *tracing.Collector,
	scheduler *scheduler.Scheduler,
	logger *zap.Logger,
) *Handler {
	return &Handler{
		repo:           repo,
		alertEngine:    alertEngine,
		sloMonitor:     sloMonitor,
		anomalyDetect:  anomalyDetect,
		metricsCol:     metricsCol,
		logPipeline:    logPipeline,
		storageMgr:     storageMgr,
		traceCollector: traceCollector,
		scheduler:      scheduler,
		logger:         logger,
	}
}

func (h *Handler) successResponse(c *gin.Context, data interface{}) {
	c.JSON(http.StatusOK, models.APIResponse{
		Code: 200,
		Data: data,
	})
}

func (h *Handler) errorResponse(c *gin.Context, code int, msg string) {
	c.JSON(code, models.APIResponse{
		Code: code,
		Msg:  msg,
	})
}

func (h *Handler) CreateResource(c *gin.Context) {
	var req struct {
		Type   string                 `json:"type"`
		Config map[string]interface{} `json:"config"`
		Labels map[string]string      `json:"labels"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		h.errorResponse(c, 400, "invalid request: "+err.Error())
		return
	}

	entity := &models.Entity{
		Type:       req.Type,
		Status:     "provisioning",
		Attributes: req.Config,
	}

	if err := h.repo.SaveEntity(entity); err != nil {
		h.errorResponse(c, 500, "failed to create resource: "+err.Error())
		return
	}

	c.JSON(http.StatusCreated, models.APIResponse{
		Code: 201,
		Data: gin.H{
			"id":     entity.ID,
			"status": entity.Status,
		},
	})
}

func (h *Handler) GetResourceStatus(c *gin.Context) {
	id := c.Param("id")

	entity, err := h.repo.GetEntity(id)
	if err != nil {
		h.errorResponse(c, 404, "resource not found")
		return
	}

	h.successResponse(c, gin.H{
		"id":       entity.ID,
		"status":   entity.Status,
		"progress": 0.8,
		"type":     entity.Type,
	})
}

func (h *Handler) BatchOperation(c *gin.Context) {
	var req struct {
		Operations []struct {
			Action string `json:"action"`
			ID     string `json:"id"`
		} `json:"operations"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		h.errorResponse(c, 400, "invalid request: "+err.Error())
		return
	}

	batchID := uuid.New().String()
	results := make([]gin.H, 0, len(req.Operations))

	for _, op := range req.Operations {
		result := gin.H{
			"id":     op.ID,
			"action": op.Action,
			"status": "success",
		}

		entity, err := h.repo.GetEntity(op.ID)
		if err != nil {
			result["status"] = "failed"
			result["error"] = "resource not found"
		} else {
			switch op.Action {
			case "stop":
				entity.Status = "stopped"
			case "start":
				entity.Status = "running"
			case "restart":
				entity.Status = "restarting"
			}
			if err := h.repo.UpdateEntity(entity); err != nil {
				result["status"] = "failed"
				result["error"] = err.Error()
			}
		}

		results = append(results, result)
	}

	h.successResponse(c, gin.H{
		"batch_id": batchID,
		"results":  results,
	})
}

func (h *Handler) ListAlertRules(c *gin.Context) {
	rules := h.alertEngine.GetRules()
	h.successResponse(c, rules)
}

func (h *Handler) CreateAlertRule(c *gin.Context) {
	var rule models.AlertRule
	if err := c.ShouldBindJSON(&rule); err != nil {
		h.errorResponse(c, 400, "invalid request: "+err.Error())
		return
	}

	if err := h.alertEngine.AddRule(&rule); err != nil {
		h.errorResponse(c, 500, "failed to add rule: "+err.Error())
		return
	}

	c.JSON(http.StatusCreated, models.APIResponse{
		Code: 201,
		Data: rule,
	})
}

func (h *Handler) ListActiveAlerts(c *gin.Context) {
	alerts := h.alertEngine.GetActiveAlerts()
	h.successResponse(c, alerts)
}

func (h *Handler) ListSLOs(c *gin.Context) {
	slos, statuses := h.sloMonitor.GetAllSLOs()
	h.successResponse(c, gin.H{
		"slos":     slos,
		"statuses": statuses,
	})
}

func (h *Handler) CreateSLO(c *gin.Context) {
	var slo models.SLO
	if err := c.ShouldBindJSON(&slo); err != nil {
		h.errorResponse(c, 400, "invalid request: "+err.Error())
		return
	}

	if err := h.sloMonitor.AddSLO(&slo); err != nil {
		h.errorResponse(c, 500, "failed to add SLO: "+err.Error())
		return
	}

	c.JSON(http.StatusCreated, models.APIResponse{
		Code: 201,
		Data: slo,
	})
}

func (h *Handler) CollectMetric(c *gin.Context) {
	var point models.MetricDataPoint
	if err := c.ShouldBindJSON(&point); err != nil {
		h.errorResponse(c, 400, "invalid request: "+err.Error())
		return
	}

	if err := h.metricsCol.Collect(point); err != nil {
		h.errorResponse(c, 500, "failed to collect metric: "+err.Error())
		return
	}

	h.successResponse(c, gin.H{"status": "ok"})
}

func (h *Handler) GetMetrics(c *gin.Context) {
	aggValues := h.metricsCol.GetAggregatedValues()
	h.successResponse(c, aggValues)
}

func (h *Handler) DetectAnomaly(c *gin.Context) {
	var req struct {
		MetricName string  `json:"metric_name"`
		Value      float64 `json:"value"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		h.errorResponse(c, 400, "invalid request: "+err.Error())
		return
	}

	results, err := h.anomalyDetect.Detect(context.Background(), req.MetricName, req.Value)
	if err != nil {
		h.errorResponse(c, 500, "anomaly detection failed: "+err.Error())
		return
	}

	h.successResponse(c, results)
}

func (h *Handler) ProcessLog(c *gin.Context) {
	var entry models.LogEntry
	if err := c.ShouldBindJSON(&entry); err != nil {
		h.errorResponse(c, 400, "invalid request: "+err.Error())
		return
	}

	if err := h.logPipeline.Process(&entry); err != nil {
		h.errorResponse(c, 500, "failed to process log: "+err.Error())
		return
	}

	h.successResponse(c, gin.H{"status": "ok", "id": entry.ID})
}

func (h *Handler) ReceiveSpan(c *gin.Context) {
	var span models.Span
	if err := c.ShouldBindJSON(&span); err != nil {
		h.errorResponse(c, 400, "invalid request: "+err.Error())
		return
	}

	if err := h.traceCollector.ReceiveSpan(&span); err != nil {
		h.errorResponse(c, 500, "failed to receive span: "+err.Error())
		return
	}

	h.successResponse(c, gin.H{"status": "ok", "sampled": span.Sampled})
}

func (h *Handler) GetTrace(c *gin.Context) {
	traceID := c.Param("trace_id")
	spans, err := h.traceCollector.GetTrace(traceID)
	if err != nil {
		h.errorResponse(c, 500, "failed to get trace: "+err.Error())
		return
	}
	h.successResponse(c, spans)
}

func (h *Handler) SetSamplingConfig(c *gin.Context) {
	var config models.SamplingConfig
	if err := c.ShouldBindJSON(&config); err != nil {
		h.errorResponse(c, 400, "invalid request: "+err.Error())
		return
	}

	h.traceCollector.SetSamplingConfig(&config)
	h.successResponse(c, gin.H{"status": "ok"})
}

func (h *Handler) ListTasks(c *gin.Context) {
	tasks, err := h.scheduler.GetAllTasks()
	if err != nil {
		h.errorResponse(c, 500, "failed to list tasks: "+err.Error())
		return
	}
	h.successResponse(c, tasks)
}

func (h *Handler) CreateTask(c *gin.Context) {
	var task models.Task
	if err := c.ShouldBindJSON(&task); err != nil {
		h.errorResponse(c, 400, "invalid request: "+err.Error())
		return
	}

	if err := h.scheduler.AddTask(&task); err != nil {
		h.errorResponse(c, 500, "failed to add task: "+err.Error())
		return
	}

	c.JSON(http.StatusCreated, models.APIResponse{
		Code: 201,
		Data: task,
	})
}

func (h *Handler) GetTaskStatus(c *gin.Context) {
	taskID := c.Param("id")
	task, runs, err := h.scheduler.GetTaskStatus(taskID)
	if err != nil {
		h.errorResponse(c, 500, "failed to get task status: "+err.Error())
		return
	}
	if task == nil {
		h.errorResponse(c, 404, "task not found")
		return
	}
	h.successResponse(c, gin.H{
		"task": task,
		"runs": runs,
	})
}

func (h *Handler) PauseTask(c *gin.Context) {
	taskID := c.Param("id")
	if err := h.scheduler.PauseTask(taskID); err != nil {
		h.errorResponse(c, 500, "failed to pause task: "+err.Error())
		return
	}
	h.successResponse(c, gin.H{"status": "paused"})
}

func (h *Handler) ResumeTask(c *gin.Context) {
	taskID := c.Param("id")
	if err := h.scheduler.ResumeTask(taskID); err != nil {
		h.errorResponse(c, 500, "failed to resume task: "+err.Error())
		return
	}
	h.successResponse(c, gin.H{"status": "resumed"})
}

func (h *Handler) CreateBackup(c *gin.Context) {
	var req struct {
		BackupType string `json:"backup_type"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		req.BackupType = "full"
	}

	record, err := h.storageMgr.CreateBackup(context.Background(), storage.BackupType(req.BackupType))
	if err != nil {
		h.errorResponse(c, 500, "backup failed: "+err.Error())
		return
	}

	c.JSON(http.StatusCreated, models.APIResponse{
		Code: 201,
		Data: record,
	})
}

func (h *Handler) ListBackups(c *gin.Context) {
	backups, err := h.storageMgr.ListBackups()
	if err != nil {
		h.errorResponse(c, 500, "failed to list backups: "+err.Error())
		return
	}
	h.successResponse(c, backups)
}

func (h *Handler) RestoreBackup(c *gin.Context) {
	backupID := c.Param("id")
	if err := h.storageMgr.Restore(context.Background(), backupID); err != nil {
		h.errorResponse(c, 500, "restore failed: "+err.Error())
		return
	}
	h.successResponse(c, gin.H{"status": "restored"})
}

func (h *Handler) GetMigrationHistory(c *gin.Context) {
	history, err := h.repo.GetMigrationHistory()
	if err != nil {
		h.errorResponse(c, 500, "failed to get migration history: "+err.Error())
		return
	}
	h.successResponse(c, history)
}

func (h *Handler) RunMigrations(c *gin.Context) {
	if err := h.repo.Migrate(); err != nil {
		h.errorResponse(c, 500, "migration failed: "+err.Error())
		return
	}
	h.successResponse(c, gin.H{"status": "migrated"})
}

func (h *Handler) RollbackMigrations(c *gin.Context) {
	versionStr := c.Query("version")
	version, err := strconv.Atoi(versionStr)
	if err != nil {
		h.errorResponse(c, 400, "invalid version")
		return
	}

	if err := h.repo.Rollback(version); err != nil {
		h.errorResponse(c, 500, "rollback failed: "+err.Error())
		return
	}
	h.successResponse(c, gin.H{"status": "rolled back", "target_version": version})
}

func (h *Handler) GetAlertEngineStats(c *gin.Context) {
	stats := h.alertEngine.GetStats()
	h.successResponse(c, stats)
}

func (h *Handler) GetSLOCacheStats(c *gin.Context) {
	stats := h.sloMonitor.GetCacheStats()
	h.successResponse(c, stats)
}

func (h *Handler) InvalidateSLOCache(c *gin.Context) {
	sloID := c.Param("id")
	if err := h.sloMonitor.InvalidateCache(sloID); err != nil {
		h.errorResponse(c, 500, "failed to invalidate cache: "+err.Error())
		return
	}
	h.successResponse(c, gin.H{"status": "cache invalidated"})
}

func (h *Handler) GetAnomalyStats(c *gin.Context) {
	stats := h.anomalyDetect.GetStats()
	h.successResponse(c, stats)
}

func (h *Handler) GetHealth(c *gin.Context) {
	h.successResponse(c, gin.H{
		"status":    "healthy",
		"timestamp": time.Now().Format(time.RFC3339),
	})
}

func (h *Handler) RegisterRoutes(r *gin.Engine) {
	api := r.Group("/api/v1")
	{
		api.POST("/resources", h.CreateResource)
		api.GET("/resources/:id/status", h.GetResourceStatus)
		api.POST("/resources/batch", h.BatchOperation)

		alerts := api.Group("/alerts")
		{
			alerts.GET("/rules", h.ListAlertRules)
			alerts.POST("/rules", h.CreateAlertRule)
			alerts.GET("/active", h.ListActiveAlerts)
			alerts.GET("/stats", h.GetAlertEngineStats)
		}

		slos := api.Group("/slos")
		{
			slos.GET("", h.ListSLOs)
			slos.POST("", h.CreateSLO)
			slos.GET("/cache-stats", h.GetSLOCacheStats)
			slos.POST("/:id/invalidate-cache", h.InvalidateSLOCache)
		}

		metrics := api.Group("/metrics")
		{
			metrics.POST("", h.CollectMetric)
			metrics.GET("", h.GetMetrics)
			metrics.POST("/detect", h.DetectAnomaly)
			metrics.GET("/anomaly-stats", h.GetAnomalyStats)
		}

		logs := api.Group("/logs")
		{
			logs.POST("", h.ProcessLog)
		}

		traces := api.Group("/traces")
		{
			traces.POST("/spans", h.ReceiveSpan)
			traces.GET("/:trace_id", h.GetTrace)
			traces.POST("/sampling-config", h.SetSamplingConfig)
		}

		tasks := api.Group("/tasks")
		{
			tasks.GET("", h.ListTasks)
			tasks.POST("", h.CreateTask)
			tasks.GET("/:id/status", h.GetTaskStatus)
			tasks.POST("/:id/pause", h.PauseTask)
			tasks.POST("/:id/resume", h.ResumeTask)
		}

		storage := api.Group("/storage")
		{
			storage.POST("/backup", h.CreateBackup)
			storage.GET("/backups", h.ListBackups)
			storage.POST("/restore/:id", h.RestoreBackup)
		}

		migrations := api.Group("/migrations")
		{
			migrations.GET("", h.GetMigrationHistory)
			migrations.POST("", h.RunMigrations)
			migrations.POST("/rollback", h.RollbackMigrations)
		}

		api.GET("/health", h.GetHealth)
	}
}
