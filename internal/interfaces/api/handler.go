package api

import (
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"session189/internal/domain"
	"session189/internal/modules/alerter"
	"session189/internal/modules/anomaly"
	"session189/internal/modules/core"
	"session189/internal/modules/notifier"
	"session189/internal/modules/profiling"
	"session189/internal/modules/scheduler"
	"session189/internal/modules/slo"
	"session189/internal/modules/storage"
	"session189/internal/modules/tracing"
)

type Handler struct {
	profiler        *profiling.Profiler
	flameGenerator  *profiling.FlameGraphGenerator
	alertEngine     *alerter.AlertEngine
	notifier        *notifier.Notifier
	backupManager   *storage.BackupManager
	restoreManager  *storage.RestoreManager
	anomalyDetector *anomaly.AnomalyDetector
	taskExecutor    *core.TaskExecutor
	scheduler       *scheduler.Scheduler
	sloMonitor      *slo.SLOMonitor
	budgetManager   *slo.ErrorBudgetManager
	spanCollector   *tracing.SpanCollector
	sampler         *tracing.Sampler
}

func NewHandler(
	profiler *profiling.Profiler,
	flameGenerator *profiling.FlameGraphGenerator,
	alertEngine *alerter.AlertEngine,
	notifier *notifier.Notifier,
	backupManager *storage.BackupManager,
	restoreManager *storage.RestoreManager,
	anomalyDetector *anomaly.AnomalyDetector,
	taskExecutor *core.TaskExecutor,
	scheduler *scheduler.Scheduler,
	sloMonitor *slo.SLOMonitor,
	budgetManager *slo.ErrorBudgetManager,
	spanCollector *tracing.SpanCollector,
	sampler *tracing.Sampler,
) *Handler {
	return &Handler{
		profiler:        profiler,
		flameGenerator:  flameGenerator,
		alertEngine:     alertEngine,
		notifier:        notifier,
		backupManager:   backupManager,
		restoreManager:  restoreManager,
		anomalyDetector: anomalyDetector,
		taskExecutor:    taskExecutor,
		scheduler:       scheduler,
		sloMonitor:      sloMonitor,
		budgetManager:   budgetManager,
		spanCollector:   spanCollector,
		sampler:         sampler,
	}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	api := r.Group("/api/v1")
	{
		h.registerProfilingRoutes(api)
		h.registerAlertRoutes(api)
		h.registerNotificationRoutes(api)
		h.registerStorageRoutes(api)
		h.registerAnomalyRoutes(api)
		h.registerTaskRoutes(api)
		h.registerSchedulerRoutes(api)
		h.registerSLORoutes(api)
		h.registerTracingRoutes(api)
		h.registerHealthRoutes(api)
	}
}

func (h *Handler) registerProfilingRoutes(api *gin.RouterGroup) {
	profiling := api.Group("/profiling")
	{
		profiling.POST("/cpu", h.StartCPUProfile)
		profiling.POST("/memory", h.StartMemoryProfile)
		profiling.POST("/goroutine", h.StartGoroutineProfile)
		profiling.GET("/samples", h.ListProfileSamples)
		profiling.GET("/samples/:id", h.GetProfileSample)
		profiling.DELETE("/samples/:id", h.DeleteProfileSample)
		profiling.POST("/flamegraph", h.GenerateFlameGraph)
		profiling.GET("/flamegraphs", h.ListFlameGraphs)
		profiling.GET("/flamegraphs/:id", h.GetFlameGraph)
		profiling.DELETE("/flamegraphs/:id", h.DeleteFlameGraph)
	}
}

func (h *Handler) registerAlertRoutes(api *gin.RouterGroup) {
	alerts := api.Group("/alerts")
	{
		alerts.POST("/rules", h.CreateAlertRule)
		alerts.GET("/rules", h.ListAlertRules)
		alerts.GET("/rules/:id", h.GetAlertRule)
		alerts.PUT("/rules/:id", h.UpdateAlertRule)
		alerts.DELETE("/rules/:id", h.DeleteAlertRule)
		alerts.GET("/events", h.ListAlertEvents)
		alerts.GET("/events/:id", h.GetAlertEvent)
		alerts.POST("/events/:id/resolve", h.ResolveAlert)
	}
}

func (h *Handler) registerNotificationRoutes(api *gin.RouterGroup) {
	notifications := api.Group("/notifications")
	{
		notifications.POST("", h.CreateNotification)
		notifications.GET("", h.ListNotifications)
		notifications.GET("/:id", h.GetNotification)
		notifications.PUT("/:id", h.UpdateNotification)
		notifications.DELETE("/:id", h.DeleteNotification)
	}
}

func (h *Handler) registerStorageRoutes(api *gin.RouterGroup) {
	storage := api.Group("/storage")
	{
		storage.POST("/backups", h.CreateBackup)
		storage.GET("/backups", h.ListBackups)
		storage.GET("/backups/:id", h.GetBackup)
		storage.DELETE("/backups/:id", h.DeleteBackup)
		storage.GET("/backups/:id/download", h.DownloadBackup)
		storage.POST("/backups/upload", h.UploadBackup)
		storage.POST("/restores", h.CreateRestore)
		storage.GET("/restores", h.ListRestores)
		storage.GET("/restores/:id", h.GetRestore)
		storage.POST("/restores/:id/cancel", h.CancelRestore)
	}
}

func (h *Handler) registerAnomalyRoutes(api *gin.RouterGroup) {
	anomaly := api.Group("/anomaly")
	{
		anomaly.POST("/detect", h.DetectAnomaly)
		anomaly.GET("/results", h.ListAnomalyResults)
		anomaly.GET("/results/:id", h.GetAnomalyResult)
		anomaly.POST("/baselines", h.UpdateBaseline)
		anomaly.GET("/baselines/:metric", h.GetBaseline)
	}
}

func (h *Handler) registerTaskRoutes(api *gin.RouterGroup) {
	tasks := api.Group("/tasks")
	{
		tasks.POST("", h.CreateTask)
		tasks.GET("", h.ListTasks)
		tasks.GET("/:id", h.GetTask)
		tasks.POST("/:id/cancel", h.CancelTask)
		tasks.GET("/:id/logs", h.GetTaskLogs)
	}
}

func (h *Handler) registerSchedulerRoutes(api *gin.RouterGroup) {
	scheduler := api.Group("/scheduler")
	{
		scheduler.POST("/jobs", h.CreateScheduledJob)
		scheduler.GET("/jobs", h.ListScheduledJobs)
		scheduler.GET("/jobs/:id", h.GetScheduledJob)
		scheduler.PUT("/jobs/:id", h.UpdateScheduledJob)
		scheduler.DELETE("/jobs/:id", h.DeleteScheduledJob)
		scheduler.POST("/jobs/:id/trigger", h.TriggerScheduledJob)
	}
}

func (h *Handler) registerSLORoutes(api *gin.RouterGroup) {
	slo := api.Group("/slo")
	{
		slo.POST("/slis", h.CreateSLI)
		slo.GET("/slis", h.ListSLIs)
		slo.GET("/slis/:id", h.GetSLI)
		slo.PUT("/slis/:id", h.UpdateSLI)
		slo.DELETE("/slis/:id", h.DeleteSLI)
		slo.POST("/slos", h.CreateSLO)
		slo.GET("/slos", h.ListSLOs)
		slo.GET("/slos/:id", h.GetSLO)
		slo.PUT("/slos/:id", h.UpdateSLO)
		slo.DELETE("/slos/:id", h.DeleteSLO)
		slo.GET("/slos/:id/budget", h.GetErrorBudget)
		slo.GET("/slos/:id/report", h.GetBudgetReport)
		slo.POST("/slos/:id/reset", h.ResetBudget)
	}
}

func (h *Handler) registerTracingRoutes(api *gin.RouterGroup) {
	tracing := api.Group("/tracing")
	{
		tracing.POST("/spans", h.CollectSpan)
		tracing.GET("/spans", h.ListSpans)
		tracing.GET("/spans/:id", h.GetSpan)
		tracing.GET("/traces", h.ListTraces)
		tracing.GET("/traces/:id", h.GetTrace)
		tracing.POST("/policies", h.CreateSamplingPolicy)
		tracing.GET("/policies", h.ListSamplingPolicies)
		tracing.GET("/policies/:id", h.GetSamplingPolicy)
		tracing.PUT("/policies/:id", h.UpdateSamplingPolicy)
		tracing.DELETE("/policies/:id", h.DeleteSamplingPolicy)
	}
}

func (h *Handler) registerHealthRoutes(api *gin.RouterGroup) {
	health := api.Group("/health")
	{
		health.GET("", h.HealthCheck)
		health.GET("/live", h.LivenessCheck)
		health.GET("/ready", h.ReadinessCheck)
	}
}

func getPagination(c *gin.Context) (int, int) {
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	if limit > 100 {
		limit = 100
	}
	return offset, limit
}

func getUserID(c *gin.Context) string {
	userID, _ := c.Get("user_id")
	if id, ok := userID.(string); ok {
		return id
	}
	return "anonymous"
}

func (h *Handler) StartCPUProfile(c *gin.Context) {
	var req struct {
		DurationSeconds int `json:"duration_seconds" binding:"min=1,max=300"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if req.DurationSeconds == 0 {
		req.DurationSeconds = 30
	}

	sample, err := h.profiler.StartCPUProfile(time.Duration(req.DurationSeconds) * time.Second)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, sample)
}

func (h *Handler) StartMemoryProfile(c *gin.Context) {
	var req struct {
		DurationSeconds int `json:"duration_seconds" binding:"min=1,max=300"`
		Rate            int `json:"rate"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if req.DurationSeconds == 0 {
		req.DurationSeconds = 30
	}

	sample, err := h.profiler.StartMemoryProfile(time.Duration(req.DurationSeconds)*time.Second, req.Rate)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, sample)
}

func (h *Handler) StartGoroutineProfile(c *gin.Context) {
	sample, err := h.profiler.StartGoroutineProfile()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, sample)
}

func (h *Handler) ListProfileSamples(c *gin.Context) {
	profileType := c.Query("type")
	offset, limit := getPagination(c)

	samples, total, err := profiling.ListProfileSamples(c.Request.Context(), domain.ProfileType(profileType), offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": samples, "total": total, "offset": offset, "limit": limit})
}

func (h *Handler) GetProfileSample(c *gin.Context) {
	sampleID := c.Param("id")
	sample, err := profiling.GetProfileSample(c.Request.Context(), sampleID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Sample not found"})
		return
	}

	c.JSON(http.StatusOK, sample)
}

func (h *Handler) DeleteProfileSample(c *gin.Context) {
	sampleID := c.Param("id")
	if err := profiling.DeleteProfileSample(c.Request.Context(), sampleID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Sample deleted"})
}

func (h *Handler) GenerateFlameGraph(c *gin.Context) {
	var req struct {
		ProfileID string `json:"profile_id" binding:"required"`
		Title     string `json:"title"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	sample, err := profiling.GetProfileSample(c.Request.Context(), req.ProfileID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Profile sample not found"})
		return
	}

	graph, err := h.flameGenerator.GenerateFromProfile(c.Request.Context(), sample, req.Title)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, graph)
}

func (h *Handler) ListFlameGraphs(c *gin.Context) {
	profileID := c.Query("profile_id")
	offset, limit := getPagination(c)

	graphs, total, err := profiling.ListFlameGraphs(c.Request.Context(), profileID, offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": graphs, "total": total, "offset": offset, "limit": limit})
}

func (h *Handler) GetFlameGraph(c *gin.Context) {
	graphID := c.Param("id")
	graph, err := profiling.GetFlameGraph(c.Request.Context(), graphID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Flame graph not found"})
		return
	}

	c.JSON(http.StatusOK, graph)
}

func (h *Handler) DeleteFlameGraph(c *gin.Context) {
	graphID := c.Param("id")
	if err := profiling.DeleteFlameGraph(c.Request.Context(), graphID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Flame graph deleted"})
}

func (h *Handler) CreateAlertRule(c *gin.Context) {
	var rule domain.AlertRule
	if err := c.ShouldBindJSON(&rule); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	created, err := h.alertEngine.CreateRule(c.Request.Context(), &rule)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, created)
}

func (h *Handler) ListAlertRules(c *gin.Context) {
	offset, limit := getPagination(c)

	rules, total, err := h.alertEngine.ListRules(c.Request.Context(), offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": rules, "total": total, "offset": offset, "limit": limit})
}

func (h *Handler) GetAlertRule(c *gin.Context) {
	ruleID := c.Param("id")
	rule, err := h.alertEngine.GetRule(c.Request.Context(), ruleID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Rule not found"})
		return
	}

	c.JSON(http.StatusOK, rule)
}

func (h *Handler) UpdateAlertRule(c *gin.Context) {
	ruleID := c.Param("id")
	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	rule, err := h.alertEngine.UpdateRule(c.Request.Context(), ruleID, updates)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, rule)
}

func (h *Handler) DeleteAlertRule(c *gin.Context) {
	ruleID := c.Param("id")
	if err := h.alertEngine.DeleteRule(c.Request.Context(), ruleID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Rule deleted"})
}

func (h *Handler) ListAlertEvents(c *gin.Context) {
	ruleID := c.Query("rule_id")
	resolvedStr := c.Query("resolved")
	var resolved *bool
	if resolvedStr != "" {
		r := resolvedStr == "true"
		resolved = &r
	}
	offset, limit := getPagination(c)

	events, total, err := h.alertEngine.ListAlertEvents(c.Request.Context(), ruleID, resolved, offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": events, "total": total, "offset": offset, "limit": limit})
}

func (h *Handler) GetAlertEvent(c *gin.Context) {
	eventID := c.Param("id")
	event, err := h.alertEngine.GetAlertEvent(c.Request.Context(), eventID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Event not found"})
		return
	}

	c.JSON(http.StatusOK, event)
}

func (h *Handler) ResolveAlert(c *gin.Context) {
	eventID := c.Param("id")
	if err := h.alertEngine.ResolveAlert(c.Request.Context(), eventID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Alert resolved"})
}

func (h *Handler) CreateNotification(c *gin.Context) {
	var notification notifier.Notification
	if err := c.ShouldBindJSON(&notification); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	created, err := h.notifier.CreateNotification(c.Request.Context(), &notification)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, created)
}

func (h *Handler) ListNotifications(c *gin.Context) {
	offset, limit := getPagination(c)

	notifications, total, err := h.notifier.ListNotifications(c.Request.Context(), offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": notifications, "total": total, "offset": offset, "limit": limit})
}

func (h *Handler) GetNotification(c *gin.Context) {
	notificationID := c.Param("id")
	notification, err := h.notifier.GetNotification(c.Request.Context(), notificationID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Notification not found"})
		return
	}

	c.JSON(http.StatusOK, notification)
}

func (h *Handler) UpdateNotification(c *gin.Context) {
	notificationID := c.Param("id")
	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	notification, err := h.notifier.UpdateNotification(c.Request.Context(), notificationID, updates)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, notification)
}

func (h *Handler) DeleteNotification(c *gin.Context) {
	notificationID := c.Param("id")
	if err := h.notifier.DeleteNotification(c.Request.Context(), notificationID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Notification deleted"})
}

func (h *Handler) CreateBackup(c *gin.Context) {
	var req struct {
		Type   storage.BackupType `json:"type"`
		Tables []string           `json:"tables"`
		Name   string             `json:"name"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if req.Type == "" {
		req.Type = storage.BackupTypeFull
	}

	backup, err := h.backupManager.CreateBackup(c.Request.Context(), req.Type, req.Tables, req.Name, getUserID(c))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, backup)
}

func (h *Handler) ListBackups(c *gin.Context) {
	status := storage.BackupStatus(c.Query("status"))
	offset, limit := getPagination(c)

	backups, total, err := h.backupManager.ListBackups(c.Request.Context(), status, offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": backups, "total": total, "offset": offset, "limit": limit})
}

func (h *Handler) GetBackup(c *gin.Context) {
	backupID := c.Param("id")
	backup, err := h.backupManager.GetBackup(c.Request.Context(), backupID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Backup not found"})
		return
	}

	c.JSON(http.StatusOK, backup)
}

func (h *Handler) DeleteBackup(c *gin.Context) {
	backupID := c.Param("id")
	if err := h.backupManager.DeleteBackup(c.Request.Context(), backupID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Backup deleted"})
}

func (h *Handler) DownloadBackup(c *gin.Context) {
	backupID := c.Param("id")
	filename, file, err := h.backupManager.DownloadBackup(c.Request.Context(), backupID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	defer file.Close()

	c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=%s", filename))
	c.Header("Content-Type", "application/octet-stream")
	c.DataFromReader(http.StatusOK, -1, "application/octet-stream", file, nil)
}

func (h *Handler) UploadBackup(c *gin.Context) {
	file, header, err := c.Request.FormFile("file")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "No file uploaded"})
		return
	}
	defer file.Close()

	backup, err := h.restoreManager.UploadBackup(c.Request.Context(), file, header.Filename)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, backup)
}

func (h *Handler) CreateRestore(c *gin.Context) {
	var req struct {
		BackupID   string `json:"backup_id" binding:"required"`
		TargetDB   string `json:"target_db"`
		Name       string `json:"name"`
		DropTables bool   `json:"drop_tables"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	restore, err := h.restoreManager.CreateRestore(c.Request.Context(), req.BackupID, req.TargetDB, req.Name, getUserID(c), req.DropTables)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, restore)
}

func (h *Handler) ListRestores(c *gin.Context) {
	status := storage.RestoreStatus(c.Query("status"))
	offset, limit := getPagination(c)

	restores, total, err := h.restoreManager.ListRestores(c.Request.Context(), status, offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": restores, "total": total, "offset": offset, "limit": limit})
}

func (h *Handler) GetRestore(c *gin.Context) {
	restoreID := c.Param("id")
	restore, err := h.restoreManager.GetRestore(c.Request.Context(), restoreID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Restore not found"})
		return
	}

	c.JSON(http.StatusOK, restore)
}

func (h *Handler) CancelRestore(c *gin.Context) {
	restoreID := c.Param("id")
	if err := h.restoreManager.CancelRestore(c.Request.Context(), restoreID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Restore cancelled"})
}

func (h *Handler) DetectAnomaly(c *gin.Context) {
	var req struct {
		MetricName string                  `json:"metric_name" binding:"required"`
		Values     []float64               `json:"values" binding:"required,min=1"`
		Algorithm  domain.AnomalyAlgorithm `json:"algorithm"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if req.Algorithm == "" {
		results, err := h.anomalyDetector.DetectAll(c.Request.Context(), req.MetricName, req.Values)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusOK, gin.H{"results": results})
		return
	}

	result, err := h.anomalyDetector.DetectWithAlgorithm(c.Request.Context(), req.MetricName, req.Values, req.Algorithm)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, result)
}

func (h *Handler) ListAnomalyResults(c *gin.Context) {
	metricName := c.Query("metric_name")
	isAnomalyStr := c.Query("is_anomaly")
	var isAnomaly *bool
	if isAnomalyStr != "" {
		v := isAnomalyStr == "true"
		isAnomaly = &v
	}
	offset, limit := getPagination(c)

	results, total, err := h.anomalyDetector.ListResults(c.Request.Context(), metricName, isAnomaly, offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": results, "total": total, "offset": offset, "limit": limit})
}

func (h *Handler) GetAnomalyResult(c *gin.Context) {
	resultID := c.Param("id")
	result, err := h.anomalyDetector.GetResult(c.Request.Context(), resultID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Result not found"})
		return
	}

	c.JSON(http.StatusOK, result)
}

func (h *Handler) UpdateBaseline(c *gin.Context) {
	var req struct {
		MetricName string    `json:"metric_name" binding:"required"`
		Values     []float64 `json:"values" binding:"required,min=1"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	baseline, err := h.anomalyDetector.UpdateBaseline(c.Request.Context(), req.MetricName, req.Values)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, baseline)
}

func (h *Handler) GetBaseline(c *gin.Context) {
	metricName := c.Param("metric")
	baseline, err := h.anomalyDetector.GetBaseline(c.Request.Context(), metricName)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Baseline not found"})
		return
	}

	c.JSON(http.StatusOK, baseline)
}

func (h *Handler) CreateTask(c *gin.Context) {
	var task domain.Task
	if err := c.ShouldBindJSON(&task); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	task.CreatedBy = getUserID(c)
	created, err := h.taskExecutor.SubmitTask(c.Request.Context(), &task)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, created)
}

func (h *Handler) ListTasks(c *gin.Context) {
	status := domain.TaskStatus(c.Query("status"))
	createdBy := c.Query("created_by")
	offset, limit := getPagination(c)

	tasks, total, err := h.taskExecutor.ListTasks(c.Request.Context(), status, createdBy, offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": tasks, "total": total, "offset": offset, "limit": limit})
}

func (h *Handler) GetTask(c *gin.Context) {
	taskID := c.Param("id")
	task, err := h.taskExecutor.GetTask(c.Request.Context(), taskID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Task not found"})
		return
	}

	c.JSON(http.StatusOK, task)
}

func (h *Handler) CancelTask(c *gin.Context) {
	taskID := c.Param("id")
	if err := h.taskExecutor.CancelTask(c.Request.Context(), taskID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Task cancelled"})
}

func (h *Handler) GetTaskLogs(c *gin.Context) {
	taskID := c.Param("id")
	offset, limit := getPagination(c)

	logs, total, err := h.taskExecutor.GetTaskLogs(c.Request.Context(), taskID, offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": logs, "total": total, "offset": offset, "limit": limit})
}

func (h *Handler) CreateScheduledJob(c *gin.Context) {
	var job scheduler.ScheduledJob
	if err := c.ShouldBindJSON(&job); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	job.CreatedBy = getUserID(c)
	created, err := h.scheduler.CreateJob(c.Request.Context(), &job)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, created)
}

func (h *Handler) ListScheduledJobs(c *gin.Context) {
	offset, limit := getPagination(c)

	jobs, total, err := h.scheduler.ListJobs(c.Request.Context(), offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": jobs, "total": total, "offset": offset, "limit": limit})
}

func (h *Handler) GetScheduledJob(c *gin.Context) {
	jobID := c.Param("id")
	job, err := h.scheduler.GetJob(c.Request.Context(), jobID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Job not found"})
		return
	}

	c.JSON(http.StatusOK, job)
}

func (h *Handler) UpdateScheduledJob(c *gin.Context) {
	jobID := c.Param("id")
	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	job, err := h.scheduler.UpdateJob(c.Request.Context(), jobID, updates)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, job)
}

func (h *Handler) DeleteScheduledJob(c *gin.Context) {
	jobID := c.Param("id")
	if err := h.scheduler.DeleteJob(c.Request.Context(), jobID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Job deleted"})
}

func (h *Handler) TriggerScheduledJob(c *gin.Context) {
	jobID := c.Param("id")
	task, err := h.scheduler.TriggerJob(c.Request.Context(), jobID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, task)
}

func (h *Handler) CreateSLI(c *gin.Context) {
	var sli domain.SLI
	if err := c.ShouldBindJSON(&sli); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	created, err := h.sloMonitor.CreateSLI(c.Request.Context(), &sli)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, created)
}

func (h *Handler) ListSLIs(c *gin.Context) {
	offset, limit := getPagination(c)

	slis, total, err := h.sloMonitor.ListSLIs(c.Request.Context(), offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": slis, "total": total, "offset": offset, "limit": limit})
}

func (h *Handler) GetSLI(c *gin.Context) {
	sliID := c.Param("id")
	sli, err := h.sloMonitor.GetSLI(c.Request.Context(), sliID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "SLI not found"})
		return
	}

	c.JSON(http.StatusOK, sli)
}

func (h *Handler) UpdateSLI(c *gin.Context) {
	sliID := c.Param("id")
	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	sli, err := h.sloMonitor.UpdateSLI(c.Request.Context(), sliID, updates)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, sli)
}

func (h *Handler) DeleteSLI(c *gin.Context) {
	sliID := c.Param("id")
	if err := h.sloMonitor.DeleteSLI(c.Request.Context(), sliID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "SLI deleted"})
}

func (h *Handler) CreateSLO(c *gin.Context) {
	var slo domain.SLO
	if err := c.ShouldBindJSON(&slo); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if slo.StartTime.IsZero() {
		slo.StartTime = time.Now()
	}
	if slo.EndTime.IsZero() {
		slo.EndTime = time.Now().AddDate(0, 1, 0)
	}

	created, err := h.sloMonitor.CreateSLO(c.Request.Context(), &slo)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, created)
}

func (h *Handler) ListSLOs(c *gin.Context) {
	status := domain.SLOStatus(c.Query("status"))
	offset, limit := getPagination(c)

	slos, total, err := h.sloMonitor.ListSLOs(c.Request.Context(), status, offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": slos, "total": total, "offset": offset, "limit": limit})
}

func (h *Handler) GetSLO(c *gin.Context) {
	sloID := c.Param("id")
	slo, err := h.sloMonitor.GetSLO(c.Request.Context(), sloID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "SLO not found"})
		return
	}

	c.JSON(http.StatusOK, slo)
}

func (h *Handler) UpdateSLO(c *gin.Context) {
	sloID := c.Param("id")
	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	slo, err := h.sloMonitor.UpdateSLO(c.Request.Context(), sloID, updates)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, slo)
}

func (h *Handler) DeleteSLO(c *gin.Context) {
	sloID := c.Param("id")
	if err := h.sloMonitor.DeleteSLO(c.Request.Context(), sloID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "SLO deleted"})
}

func (h *Handler) GetErrorBudget(c *gin.Context) {
	sloID := c.Param("id")
	budget, err := h.budgetManager.GetBudget(c.Request.Context(), sloID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Budget not found"})
		return
	}

	c.JSON(http.StatusOK, budget)
}

func (h *Handler) GetBudgetReport(c *gin.Context) {
	sloID := c.Param("id")
	report, err := h.budgetManager.GenerateBudgetReport(c.Request.Context(), sloID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, report)
}

func (h *Handler) ResetBudget(c *gin.Context) {
	sloID := c.Param("id")
	if err := h.budgetManager.ResetBudget(c.Request.Context(), sloID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Budget reset"})
}

func (h *Handler) CollectSpan(c *gin.Context) {
	var span domain.TraceSpan
	if err := c.ShouldBindJSON(&span); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	sampled, err := h.spanCollector.Collect(c.Request.Context(), &span)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"sampled": sampled, "span_id": span.SpanID})
}

func (h *Handler) ListSpans(c *gin.Context) {
	serviceName := c.Query("service_name")
	status := domain.SpanStatus(c.Query("status"))
	offset, limit := getPagination(c)

	spans, total, err := h.spanCollector.ListSpans(c.Request.Context(), serviceName, status, offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": spans, "total": total, "offset": offset, "limit": limit})
}

func (h *Handler) GetSpan(c *gin.Context) {
	spanID := c.Param("id")
	span, err := h.spanCollector.GetSpan(c.Request.Context(), spanID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Span not found"})
		return
	}

	c.JSON(http.StatusOK, span)
}

func (h *Handler) ListTraces(c *gin.Context) {
	serviceName := c.Query("service_name")
	hasErrorStr := c.Query("has_error")
	var hasError *bool
	if hasErrorStr != "" {
		v := hasErrorStr == "true"
		hasError = &v
	}
	offset, limit := getPagination(c)

	traces, total, err := h.spanCollector.ListTraces(c.Request.Context(), serviceName, hasError, offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": traces, "total": total, "offset": offset, "limit": limit})
}

func (h *Handler) GetTrace(c *gin.Context) {
	traceID := c.Param("id")
	spans, err := h.spanCollector.GetTrace(c.Request.Context(), traceID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Trace not found"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"trace_id": traceID, "spans": spans})
}

func (h *Handler) CreateSamplingPolicy(c *gin.Context) {
	var policy domain.SamplingPolicy
	if err := c.ShouldBindJSON(&policy); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	created, err := h.sampler.CreatePolicy(c.Request.Context(), &policy)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, created)
}

func (h *Handler) ListSamplingPolicies(c *gin.Context) {
	offset, limit := getPagination(c)

	policies, total, err := h.sampler.ListPolicies(c.Request.Context(), offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": policies, "total": total, "offset": offset, "limit": limit})
}

func (h *Handler) GetSamplingPolicy(c *gin.Context) {
	policyID := c.Param("id")
	policy, err := h.sampler.GetPolicy(c.Request.Context(), policyID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Policy not found"})
		return
	}

	c.JSON(http.StatusOK, policy)
}

func (h *Handler) UpdateSamplingPolicy(c *gin.Context) {
	policyID := c.Param("id")
	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	policy, err := h.sampler.UpdatePolicy(c.Request.Context(), policyID, updates)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, policy)
}

func (h *Handler) DeleteSamplingPolicy(c *gin.Context) {
	policyID := c.Param("id")
	if err := h.sampler.DeletePolicy(c.Request.Context(), policyID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Policy deleted"})
}

func (h *Handler) HealthCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":  "ok",
		"time":    time.Now().UTC(),
		"version": "1.0.0",
	})
}

func (h *Handler) LivenessCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"status": "alive"})
}

func (h *Handler) ReadinessCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"status": "ready"})
}
