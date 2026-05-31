package main

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"taskmanager/internal/alerter"
	"taskmanager/internal/logger"
	"taskmanager/internal/logpipeline"
	"taskmanager/internal/notifier"
	"taskmanager/internal/scheduler"
	"taskmanager/internal/slomonitor"
	"taskmanager/internal/storage"
	"taskmanager/internal/topology"
	"taskmanager/internal/tracing"
	"taskmanager/pkg/models"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

type App struct {
	db              *gorm.DB
	scheduler       *scheduler.Scheduler
	sloMonitor      *slomonitor.SLOMonitor
	storageManager  *storage.StorageManager
	alertEngine     *alerter.AlertEngine
	logPipeline     *logpipeline.LogPipeline
	logAggregator   *logpipeline.LogAggregator
	traceCollector  *tracing.TraceCollector
	spanStorage     *tracing.InMemorySpanStorage
	topologyBuilder *topology.TopologyBuilder
	topologyAnalyzer *topology.TopologyAnalyzer
	snapshotGen     *topology.SnapshotGenerator
	notifier        *notifier.Notifier
	router          *gin.Engine
}

func NewApp() *App {
	db, err := gorm.Open(sqlite.Open("taskmanager.db"), &gorm.Config{})
	if err != nil {
		panic(err)
	}
	db.AutoMigrate(
		&models.Task{},
		&models.RunInstance{},
		&models.SLO{},
		&models.AlertRule{},
		&models.Alert{},
		&models.Notification{},
		&models.StoredFile{},
		&models.Snapshot{},
	)

	notifierInstance := notifier.NewNotifier(db)
	notifierInstance.RegisterSender("email", &notifier.EmailSender{})
	notifierInstance.RegisterSender("sms", &notifier.SMSSender{})
	notifierInstance.RegisterSender("webhook", &notifier.WebhookSender{URL: "http://localhost:8080/webhook"})

	alertEngine := alerter.NewAlertEngine(db, notifierInstance)
	sloMonitor := slomonitor.NewSLOMonitor(db, alertEngine)

	app := &App{
		db:               db,
		scheduler:        scheduler.NewScheduler(db),
		sloMonitor:       sloMonitor,
		storageManager:   storage.NewStorageManager(db, "./data/storage"),
		alertEngine:      alertEngine,
		logPipeline:      logpipeline.NewLogPipeline(),
		logAggregator:    logpipeline.NewLogAggregator(),
		spanStorage:      tracing.NewInMemorySpanStorage(),
		topologyBuilder:  topology.NewTopologyBuilder(),
		notifier:         notifierInstance,
	}

	tailSampler := tracing.NewTailSampler(10*time.Second, 1000)
	app.traceCollector = tracing.NewTraceCollector(nil, tailSampler, app.spanStorage)
	app.topologyAnalyzer = topology.NewTopologyAnalyzer(app.topologyBuilder)
	app.snapshotGen = topology.NewSnapshotGenerator(app.topologyBuilder)

	app.logPipeline.AddFilter(&logpipeline.LevelFilter{MinLevel: "info"})
	app.logPipeline.AddOutput("default", &logpipeline.ConsoleOutput{})

	app.setupRouter()
	return app
}

func (app *App) setupRouter() {
	r := gin.Default()

	r.Use(func(c *gin.Context) {
		c.Next()
	})

	api := r.Group("/api/v1")

	api.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok"})
	})

	tasks := api.Group("/tasks")
	{
		tasks.POST("", app.createTask)
		tasks.GET("", app.listTasks)
		tasks.GET("/:id", app.getTask)
		tasks.PUT("/:id", app.updateTask)
		tasks.DELETE("/:id", app.deleteTask)
		tasks.POST("/:id/trigger", app.triggerTask)
		tasks.GET("/:id/runs", app.getTaskRuns)
	}

	slos := api.Group("/slos")
	{
		slos.POST("", app.createSLO)
		slos.GET("", app.listSLOs)
		slos.GET("/:id", app.getSLO)
		slos.GET("/:id/status", app.getSLOStatus)
		slos.PUT("/:id", app.updateSLO)
		slos.DELETE("/:id", app.deleteSLO)
		slos.POST("/:id/reset", app.resetSLOBudget)
	}

	alerts := api.Group("/alerts")
	{
		alerts.GET("", app.listAlerts)
		alerts.POST("/:id/resolve", app.resolveAlert)
		rules := alerts.Group("/rules")
		{
			rules.POST("", app.createAlertRule)
			rules.GET("", app.listAlertRules)
			rules.GET("/:id", app.getAlertRule)
			rules.PUT("/:id", app.updateAlertRule)
			rules.DELETE("/:id", app.deleteAlertRule)
		}
	}

	files := api.Group("/files")
	{
		files.POST("", app.uploadFile)
		files.GET("", app.listFiles)
		files.GET("/:id", app.downloadFile)
		files.DELETE("/:id", app.deleteFile)
		files.POST("/:id/ttl", app.updateFileTTL)
		files.GET("/stats", app.getStorageStats)
	}

	logs := api.Group("/logs")
	{
		logs.POST("", app.ingestLog)
		logs.GET("/stats", app.getLogStats)
	}

	traces := api.Group("/traces")
	{
		traces.POST("/spans", app.receiveSpan)
		traces.GET("/:id", app.getTrace)
		traces.GET("", app.listTraces)
	}

	topologyGroup := api.Group("/topology")
	{
		topologyGroup.GET("", app.getTopology)
		topologyGroup.GET("/services/:name/dependencies", app.getServiceDependencies)
		topologyGroup.GET("/services/:name/dependents", app.getServiceDependents)
		topologyGroup.GET("/services/:name/metrics", app.getServiceMetrics)
		topologyGroup.GET("/analysis/critical-path", app.getCriticalPath)
		topologyGroup.GET("/analysis/high-error", app.getHighErrorServices)
		topologyGroup.GET("/analysis/cycles", app.getCycles)
		topologyGroup.POST("/snapshot", app.generateSnapshot)
	}

	notifications := api.Group("/notifications")
	{
		notifications.POST("", app.sendNotification)
		notifications.GET("", app.listNotifications)
		notifications.GET("/:id", app.getNotification)
		notifications.GET("/stats", app.getNotificationStats)
	}

	loggerAPI := api.Group("/logger")
	{
		loggerAPI.GET("/level", app.getLogLevel)
		loggerAPI.POST("/level", app.setLogLevel)
	}

	app.router = r
}

func (app *App) Start() {
	app.scheduler.Start()
	app.sloMonitor.Start()
	app.storageManager.Start()
	app.alertEngine.Start()
	app.logPipeline.Start()
	app.traceCollector.Start()
	app.topologyBuilder.Start()
	app.notifier.Start()
	logger.Info("all modules started")
}

func (app *App) Stop() {
	app.scheduler.Stop()
	app.sloMonitor.Stop()
	app.storageManager.Stop()
	app.alertEngine.Stop()
	app.logPipeline.Stop()
	app.traceCollector.Stop()
	app.topologyBuilder.Stop()
	app.notifier.Stop()
	logger.Sync()
	logger.Info("all modules stopped")
}

func (app *App) createTask(c *gin.Context) {
	var task models.Task
	if err := c.ShouldBindJSON(&task); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if err := app.scheduler.CreateTask(c.Request.Context(), &task); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, gin.H{"code": 201, "data": task})
}

func (app *App) listTasks(c *gin.Context) {
	tasks, err := app.scheduler.ListTasks(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": tasks})
}

func (app *App) getTask(c *gin.Context) {
	task, err := app.scheduler.GetTask(c.Request.Context(), c.Param("id"))
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": task})
}

func (app *App) updateTask(c *gin.Context) {
	var task models.Task
	if err := c.ShouldBindJSON(&task); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	task.ID = c.Param("id")
	if err := app.scheduler.UpdateTask(c.Request.Context(), &task); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": task})
}

func (app *App) deleteTask(c *gin.Context) {
	if err := app.scheduler.DeleteTask(c.Request.Context(), c.Param("id")); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "deleted"})
}

func (app *App) triggerTask(c *gin.Context) {
	if err := app.scheduler.TriggerTask(c.Request.Context(), c.Param("id")); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "triggered"})
}

func (app *App) getTaskRuns(c *gin.Context) {
	runs, err := app.scheduler.GetRunHistory(c.Request.Context(), c.Param("id"), 10)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": runs})
}

func (app *App) createSLO(c *gin.Context) {
	var slo models.SLO
	if err := c.ShouldBindJSON(&slo); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if err := app.sloMonitor.CreateSLO(c.Request.Context(), &slo); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, gin.H{"code": 201, "data": slo})
}

func (app *App) listSLOs(c *gin.Context) {
	slos, err := app.sloMonitor.ListSLOs(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": slos})
}

func (app *App) getSLO(c *gin.Context) {
	slo, err := app.sloMonitor.GetSLO(c.Request.Context(), c.Param("id"))
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": slo})
}

func (app *App) getSLOStatus(c *gin.Context) {
	status, err := app.sloMonitor.GetSLOStatus(c.Request.Context(), c.Param("id"))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": status})
}

func (app *App) updateSLO(c *gin.Context) {
	var slo models.SLO
	if err := c.ShouldBindJSON(&slo); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	slo.ID = c.Param("id")
	if err := app.sloMonitor.UpdateSLO(c.Request.Context(), &slo); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": slo})
}

func (app *App) deleteSLO(c *gin.Context) {
	if err := app.sloMonitor.DeleteSLO(c.Request.Context(), c.Param("id")); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "deleted"})
}

func (app *App) resetSLOBudget(c *gin.Context) {
	if err := app.sloMonitor.ResetBudget(c.Request.Context(), c.Param("id")); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "budget reset"})
}

func (app *App) createAlertRule(c *gin.Context) {
	var rule models.AlertRule
	if err := c.ShouldBindJSON(&rule); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if err := app.alertEngine.CreateRule(c.Request.Context(), &rule); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, gin.H{"code": 201, "data": rule})
}

func (app *App) listAlertRules(c *gin.Context) {
	rules, err := app.alertEngine.ListRules(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": rules})
}

func (app *App) getAlertRule(c *gin.Context) {
	rule, err := app.alertEngine.GetRule(c.Request.Context(), c.Param("id"))
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": rule})
}

func (app *App) updateAlertRule(c *gin.Context) {
	var rule models.AlertRule
	if err := c.ShouldBindJSON(&rule); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	rule.ID = c.Param("id")
	if err := app.alertEngine.UpdateRule(c.Request.Context(), &rule); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": rule})
}

func (app *App) deleteAlertRule(c *gin.Context) {
	if err := app.alertEngine.DeleteRule(c.Request.Context(), c.Param("id")); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "deleted"})
}

func (app *App) listAlerts(c *gin.Context) {
	status := c.Query("status")
	alerts, err := app.alertEngine.ListAlerts(c.Request.Context(), status, 100)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": alerts})
}

func (app *App) resolveAlert(c *gin.Context) {
	if err := app.alertEngine.ResolveAlert(c.Request.Context(), c.Param("id")); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "resolved"})
}

func (app *App) uploadFile(c *gin.Context) {
	file, err := c.FormFile("file")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	f, err := file.Open()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	defer f.Close()
	content := make([]byte, file.Size)
	f.Read(content)
	ttlStr := c.PostForm("ttl")
	var ttl *time.Duration
	if ttlStr != "" {
		d, err := time.ParseDuration(ttlStr)
		if err == nil {
			ttl = &d
		}
	}
	storedFile, err := app.storageManager.StoreFile(c.Request.Context(), file.Filename, content, file.Header.Get("Content-Type"), ttl)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, gin.H{"code": 201, "data": storedFile})
}

func (app *App) listFiles(c *gin.Context) {
	prefix := c.Query("prefix")
	files, err := app.storageManager.ListFiles(c.Request.Context(), prefix, 100)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": files})
}

func (app *App) downloadFile(c *gin.Context) {
	storedFile, content, err := app.storageManager.GetFile(c.Request.Context(), c.Param("id"))
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}
	c.Data(http.StatusOK, storedFile.ContentType, content)
}

func (app *App) deleteFile(c *gin.Context) {
	if err := app.storageManager.DeleteFile(c.Request.Context(), c.Param("id")); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "deleted"})
}

func (app *App) updateFileTTL(c *gin.Context) {
	var req struct {
		TTL string `json:"ttl"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	ttl, err := time.ParseDuration(req.TTL)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid TTL"})
		return
	}
	if err := app.storageManager.UpdateTTL(c.Request.Context(), c.Param("id"), ttl); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "TTL updated"})
}

func (app *App) getStorageStats(c *gin.Context) {
	stats, err := app.storageManager.GetStorageStats(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": stats})
}

func (app *App) ingestLog(c *gin.Context) {
	var entry models.LogEntry
	if err := c.ShouldBindJSON(&entry); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	app.logPipeline.Process(entry)
	app.logAggregator.Process(&entry)
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "ingested"})
}

func (app *App) getLogStats(c *gin.Context) {
	stats := app.logAggregator.GetStats()
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": stats})
}

func (app *App) receiveSpan(c *gin.Context) {
	var span models.Span
	if err := c.ShouldBindJSON(&span); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	app.traceCollector.ReceiveSpan(&span)
	app.topologyBuilder.AddSpan(&span)
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "received"})
}

func (app *App) getTrace(c *gin.Context) {
	spans := app.spanStorage.GetSpansByTrace(c.Param("id"))
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": spans})
}

func (app *App) listTraces(c *gin.Context) {
	spans := app.spanStorage.GetAllSpans(100)
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": spans})
}

func (app *App) getTopology(c *gin.Context) {
	topology := app.topologyBuilder.GetTopology()
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": topology})
}

func (app *App) getServiceDependencies(c *gin.Context) {
	deps := app.topologyBuilder.GetServiceDependencies(c.Param("name"))
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": deps})
}

func (app *App) getServiceDependents(c *gin.Context) {
	deps := app.topologyBuilder.GetServiceDependents(c.Param("name"))
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": deps})
}

func (app *App) getServiceMetrics(c *gin.Context) {
	metrics := app.topologyBuilder.GetServiceMetrics(c.Param("name"))
	if metrics == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "service not found"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": metrics})
}

func (app *App) getCriticalPath(c *gin.Context) {
	path := app.topologyAnalyzer.FindCriticalPath()
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": path})
}

func (app *App) getHighErrorServices(c *gin.Context) {
	threshold := 5.0
	services := app.topologyAnalyzer.FindHighErrorServices(threshold)
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": services})
}

func (app *App) getCycles(c *gin.Context) {
	cycles := app.topologyAnalyzer.DetectCircularDependencies()
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": cycles})
}

func (app *App) generateSnapshot(c *gin.Context) {
	var dims map[string]string
	json.NewDecoder(c.Request.Body).Decode(&dims)
	snapshot := app.snapshotGen.GenerateSnapshot(dims)
	app.db.Create(snapshot)
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": snapshot})
}

func (app *App) sendNotification(c *gin.Context) {
	var req struct {
		Channel   string            `json:"channel"`
		Recipient string            `json:"recipient"`
		Subject   string            `json:"subject"`
		Content   string            `json:"content"`
		Labels    map[string]string `json:"labels"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	id := uuid.New().String()
	if err := app.notifier.SendNotification(c.Request.Context(), req.Channel, req.Recipient, req.Subject, req.Content, req.Labels); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": gin.H{"id": id, "status": "queued"}})
}

func (app *App) listNotifications(c *gin.Context) {
	status := c.Query("status")
	notifications, err := app.notifier.ListNotifications(c.Request.Context(), status, 100)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": notifications})
}

func (app *App) getNotification(c *gin.Context) {
	notification, err := app.notifier.GetNotification(c.Request.Context(), c.Param("id"))
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": notification})
}

func (app *App) getNotificationStats(c *gin.Context) {
	stats, err := app.notifier.GetStats(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": stats})
}

func (app *App) getLogLevel(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": gin.H{"level": logger.GetLevel()}})
}

func (app *App) setLogLevel(c *gin.Context) {
	var req struct {
		Level string `json:"level"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	logger.SetLevel(req.Level)
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": gin.H{"level": logger.GetLevel()}})
}

func main() {
	logger.Init()
	app := NewApp()
	app.Start()

	srv := &http.Server{
		Addr:    ":8080",
		Handler: app.router,
	}

	go func() {
		logger.Info("server starting on :8080")
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("server failed", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	logger.Info("shutting down server...")

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		logger.Fatal("server shutdown failed", zap.Error(err))
	}

	app.Stop()
	logger.Info("server exited")
}
