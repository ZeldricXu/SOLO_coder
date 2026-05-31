package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/solocoder/backup-engine/internal/alerting"
	"github.com/solocoder/backup-engine/internal/anomaly"
	"github.com/solocoder/backup-engine/internal/core"
	"github.com/solocoder/backup-engine/internal/gateway"
	"github.com/solocoder/backup-engine/internal/logger"
	"github.com/solocoder/backup-engine/internal/logpipeline"
	"github.com/solocoder/backup-engine/internal/scheduler"
	"github.com/solocoder/backup-engine/internal/slo"
	"github.com/solocoder/backup-engine/internal/storage"
	"github.com/solocoder/backup-engine/pkg/common"
)

type App struct {
	storage      *storage.StorageManager
	processor    *core.Processor
	anomaly      *anomaly.DetectorEngine
	alertEngine  *alerting.AlertEngine
	sloMonitor   *slo.SLOMonitor
	gateway      *gateway.APIGateway
	scheduler    *scheduler.TaskScheduler
	logPipeline  *logpipeline.Pipeline
}

func main() {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	app := &App{}

	if err := app.init(ctx); err != nil {
		logger.Fatal("Failed to initialize application", map[string]interface{}{"error": err.Error()})
	}

	go func() {
		sig := <-sigCh
		logger.Info("Received shutdown signal", map[string]interface{}{"signal": sig.String()})
		app.shutdown(ctx)
		cancel()
		os.Exit(0)
	}()

	if err := app.run(ctx); err != nil {
		logger.Error("Application error", map[string]interface{}{"error": err.Error()})
		app.shutdown(ctx)
		os.Exit(1)
	}

	<-ctx.Done()
}

func (app *App) init(ctx context.Context) error {
	logger.Info("Initializing Backup Engine...")

	backupConfig := common.BackupConfig{
		Source:        "primary",
		Destination:   "./backups",
		Compression:   "none",
		EncryptionKey: "your-32-byte-encryption-key-here!",
		RetentionDays: 30,
		MaxParallel:   5,
	}

	sm, err := storage.NewStorageManager("./backups", backupConfig)
	if err != nil {
		return fmt.Errorf("failed to create storage manager: %w", err)
	}
	if err := sm.LoadFromDisk(); err != nil {
		logger.Warn("Failed to load backups from disk", map[string]interface{}{"error": err.Error()})
	}
	app.storage = sm

	app.processor = core.NewProcessor()
	app.processor.SetTimeout(30 * time.Second)
	app.processor.SetMaxRetries(3)

	app.registerHandlers()

	app.anomaly = anomaly.NewDetectorEngine(1000, anomaly.AlgorithmZScore)

	app.alertEngine = alerting.NewAlertEngine()
	app.alertEngine.AddNotifier(alerting.NewConsoleNotifier())
	app.alertEngine.AddNotifier(alerting.NewWebhookNotifier("http://localhost:8080/webhook"))

	app.sloMonitor = slo.NewSLOMonitor(10000)
	app.setupSLOs()

	app.gateway = gateway.NewAPIGateway(app.processor)
	app.setupRoutes()

	app.scheduler = scheduler.NewTaskScheduler(5)
	app.setupTasks()

	app.logPipeline = logpipeline.NewPipelineWithHotReload("./config/pipeline.json", 5*time.Second)
	app.setupLogPipeline()

	logger.Info("Application initialized successfully")
	return nil
}

func (app *App) registerHandlers() {
	app.processor.RegisterHandler(core.OpBackup, func(ctx context.Context, req *common.Request) (*common.Response, error) {
		name, _ := req.Payload.(map[string]interface{})["name"].(string)
		dataStr, _ := req.Payload.(map[string]interface{})["data"].(string)
		data := []byte(dataStr)

		info, err := app.storage.Backup(ctx, name, data)
		if err != nil {
			return &common.Response{
				Success: false,
				Code:    500,
				Message: "backup failed",
				Error:   err.Error(),
			}, err
		}

		return &common.Response{
			Success: true,
			Code:    200,
			Message: "backup completed",
			Data:    info,
		}, nil
	})

	app.processor.RegisterHandler(core.OpRestore, func(ctx context.Context, req *common.Request) (*common.Response, error) {
		backupID, _ := req.Payload.(map[string]interface{})["backup_id"].(string)

		data, err := app.storage.Restore(ctx, backupID)
		if err != nil {
			return &common.Response{
				Success: false,
				Code:    500,
				Message: "restore failed",
				Error:   err.Error(),
			}, err
		}

		return &common.Response{
			Success: true,
			Code:    200,
			Message: "restore completed",
			Data:    string(data),
		}, nil
	})

	app.processor.RegisterHandler(core.OpList, func(ctx context.Context, req *common.Request) (*common.Response, error) {
		backups := app.storage.List()
		return &common.Response{
			Success: true,
			Code:    200,
			Message: "list retrieved",
			Data:    backups,
		}, nil
	})

	app.processor.RegisterHandler(core.OpDelete, func(ctx context.Context, req *common.Request) (*common.Response, error) {
		backupID, _ := req.Payload.(map[string]interface{})["backup_id"].(string)

		if err := app.storage.Delete(backupID); err != nil {
			return &common.Response{
				Success: false,
				Code:    500,
				Message: "delete failed",
				Error:   err.Error(),
			}, err
		}

		return &common.Response{
			Success: true,
			Code:    200,
			Message: "deleted successfully",
		}, nil
	})

	app.processor.RegisterHandler(core.OpCleanup, func(ctx context.Context, req *common.Request) (*common.Response, error) {
		if err := app.storage.Cleanup(); err != nil {
			return &common.Response{
				Success: false,
				Code:    500,
				Message: "cleanup failed",
				Error:   err.Error(),
			}, err
		}

		return &common.Response{
			Success: true,
			Code:    200,
			Message: "cleanup completed",
		}, nil
	})

	app.processor.RegisterHandler(core.OpDetectAnomaly, func(ctx context.Context, req *common.Request) (*common.Response, error) {
		payload, _ := req.Payload.(map[string]interface{})
		name, _ := payload["metric_name"].(string)
		value, _ := payload["value"].(float64)
		algoStr, _ := payload["algorithm"].(string)

		metric := common.Metric{
			Name:      name,
			Value:     value,
			Timestamp: time.Now(),
			Labels:    make(map[string]string),
		}

		result, err := app.anomaly.Detect(metric, anomaly.AlgorithmType(algoStr))
		if err != nil {
			return &common.Response{
				Success: false,
				Code:    500,
				Message: "anomaly detection failed",
				Error:   err.Error(),
			}, err
		}

		app.alertEngine.ReportMetric(metric)

		return &common.Response{
			Success: true,
			Code:    200,
			Message: "detection completed",
			Data:    result,
		}, nil
	})

	app.processor.RegisterHandler(core.OpHealthCheck, func(ctx context.Context, req *common.Request) (*common.Response, error) {
		return app.processor.HealthCheck(), nil
	})

	cb := core.NewCircuitBreaker("storage", 5, 30*time.Second)
	app.processor.RegisterCircuitBreaker("storage", cb)
}

func (app *App) setupSLOs() {
	app.sloMonitor.AddSLO(&slo.SLOConfig{
		SLO: common.SLO{
			Name:               "availability",
			Description:        "Service availability SLO",
			SLIType:            string(slo.SLIAvailability),
			TargetPercent:      99.9,
			Period:             30 * 24 * time.Hour,
			ErrorBudgetPercent: 0.1,
		},
		WindowType:      slo.WindowRolling,
		AlertThresholds: []float64{0.8, 0.5, 0.2},
	})

	app.sloMonitor.AddSLO(&slo.SLOConfig{
		SLO: common.SLO{
			Name:               "latency",
			Description:        "Request latency SLO",
			SLIType:            string(slo.SLILatency),
			TargetPercent:      95.0,
			Period:             7 * 24 * time.Hour,
			ErrorBudgetPercent: 5.0,
		},
		WindowType:      slo.WindowRolling,
		AlertThresholds: []float64{0.8, 0.5},
	})

	for i := 0; i < 100; i++ {
		app.sloMonitor.RecordEvent("availability", slo.SLIEvent{
			Timestamp: time.Now().Add(-time.Duration(i) * time.Minute),
			IsGood:    i%100 != 0,
			Value:     100.0,
			TraceID:   common.GenerateTraceID(),
		})
	}

	for i := 0; i < 100; i++ {
		app.sloMonitor.RecordEvent("latency", slo.SLIEvent{
			Timestamp: time.Now().Add(-time.Duration(i) * time.Minute),
			IsGood:    i%20 != 0,
			Value:     50.0,
			TraceID:   common.GenerateTraceID(),
		})
	}
}

func (app *App) setupRoutes() {
	app.gateway.AddRoute("/api/backup", "POST", func(ctx context.Context, req *common.Request) (*common.Response, error) {
		req.Operation = string(core.OpBackup)
		req.Headers["circuit_breaker"] = "storage"
		return nil, nil
	})

	app.gateway.AddRoute("/api/restore", "POST", func(ctx context.Context, req *common.Request) (*common.Response, error) {
		req.Operation = string(core.OpRestore)
		req.Headers["circuit_breaker"] = "storage"
		return nil, nil
	})

	app.gateway.AddRoute("/api/backups", "GET", func(ctx context.Context, req *common.Request) (*common.Response, error) {
		req.Operation = string(core.OpList)
		return nil, nil
	})

	app.gateway.AddRoute("/api/backup/{id}", "DELETE", func(ctx context.Context, req *common.Request) (*common.Response, error) {
		req.Operation = string(core.OpDelete)
		return nil, nil
	})

	app.gateway.AddRoute("/api/anomaly/detect", "POST", func(ctx context.Context, req *common.Request) (*common.Response, error) {
		req.Operation = string(core.OpDetectAnomaly)
		return nil, nil
	})

	app.gateway.AddRoute("/api/slo", "GET", func(ctx context.Context, req *common.Request) (*common.Response, error) {
		sloName := req.Headers["slo_name"]
		if sloName == "" {
			sloName = "availability"
		}
		sli, err := app.sloMonitor.CalculateSLI(sloName, 24*time.Hour)
		if err != nil {
			return &common.Response{
				Success: false,
				Code:    500,
				Message: err.Error(),
			}, err
		}

		budget, _ := app.sloMonitor.GetErrorBudgetStatus(sloName)

		return &common.Response{
			Success: true,
			Code:    200,
			Data:    map[string]interface{}{"sli": sli, "budget": budget},
		}, nil
	})

	app.gateway.AddRoute("/api/alerts", "GET", func(ctx context.Context, req *common.Request) (*common.Response, error) {
		alerts := app.alertEngine.GetActiveAlerts()
		return &common.Response{
			Success: true,
			Code:    200,
			Data:    alerts,
		}, nil
	})
}

func (app *App) setupTasks() {
	task1 := &common.Task{
		Name:        "collect_metrics",
		Description: "Collect system metrics",
		Metadata:    map[string]string{"type": "metrics"},
	}

	task2 := &common.Task{
		Name:        "process_data",
		Description: "Process collected data",
		Metadata:    map[string]string{"type": "processing"},
	}

	task3 := &common.Task{
		Name:        "generate_report",
		Description: "Generate daily report",
		Metadata:    map[string]string{"type": "report"},
	}

	taskFunc1 := func(ctx context.Context, task *common.Task) error {
		logger.Info("Collecting metrics...", map[string]interface{}{"task_id": task.ID})
		time.Sleep(100 * time.Millisecond)
		task.Progress = 50
		time.Sleep(100 * time.Millisecond)
		task.Progress = 100
		return nil
	}

	taskFunc2 := func(ctx context.Context, task *common.Task) error {
		logger.Info("Processing data...", map[string]interface{}{"task_id": task.ID})
		time.Sleep(150 * time.Millisecond)
		task.Progress = 100
		return nil
	}

	taskFunc3 := func(ctx context.Context, task *common.Task) error {
		logger.Info("Generating report...", map[string]interface{}{"task_id": task.ID})
		time.Sleep(200 * time.Millisecond)
		task.Progress = 100
		return nil
	}

	id1 := app.scheduler.AddTask(task1, taskFunc1, scheduler.PriorityHigh, 3, 5*time.Minute)
	id2 := app.scheduler.AddTask(task2, taskFunc2, scheduler.PriorityNormal, 3, 5*time.Minute)
	id3 := app.scheduler.AddTask(task3, taskFunc3, scheduler.PriorityLow, 3, 5*time.Minute)

	app.scheduler.AddDependency(id2, id1)
	app.scheduler.AddDependency(id3, id2)
}

func (app *App) setupLogPipeline() {
	app.logPipeline.AddCollector(logpipeline.NewFileCollector("./logs/app.log"))

	app.logPipeline.AddProcessor(logpipeline.NewJSONParser())

	regexParser, err := logpipeline.NewRegexParser(`(?P<time>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}) (?P<level>\w+) (?P<msg>.+)`)
	if err == nil {
		app.logPipeline.AddProcessor(regexParser)
	}

	app.logPipeline.AddFilter(logpipeline.NewLevelFilter(logger.InfoLevel))

	patternRouter, _ := logpipeline.NewPatternRouter(map[string]string{
		"error":   `error|Error|ERROR`,
		"warning": `warn|Warn|WARN`,
		"security": `security|auth|login`,
	})
	app.logPipeline.AddRouter(patternRouter)

	app.logPipeline.AddRouter(logpipeline.NewLevelRouter())

	app.logPipeline.AddOutput("default", logpipeline.NewConsoleOutput())
	app.logPipeline.AddOutput("error", logpipeline.NewFileOutput("./logs/errors.log"))
	app.logPipeline.AddOutput("warning", logpipeline.NewFileOutput("./logs/warnings.log"))
}

func (app *App) run(ctx context.Context) error {
	logger.Info("Starting Backup Engine...")

	app.alertEngine.Start()

	alertRule := &common.AlertRule{
		Name:       "high_error_rate",
		Expression: "{error_rate} > 0.05",
		Severity:   "warning",
		For:        5 * time.Minute,
		Labels:     map[string]string{"service": "backup-engine"},
		Annotations: map[string]string{
			"description": "Error rate is above 5%",
			"runbook":     "https://wiki.example.com/runbooks/error_rate",
		},
		Enabled:  true,
		CronExpr: "*/5 * * * *",
	}
	app.alertEngine.AddRule(alertRule)

	for i := 0; i < 50; i++ {
		metric := common.Metric{
			Name:      "error_rate",
			Value:     float64(i%20) / 100,
			Timestamp: time.Now().Add(-time.Duration(50-i) * time.Second),
		}
		app.alertEngine.ReportMetric(metric)
	}

	app.alertEngine.EvaluateAll()

	preloadMetrics := make([]common.Metric, 0, 100)
	now := time.Now()
	for i := 0; i < 100; i++ {
		preloadMetrics = append(preloadMetrics, common.Metric{
			Name:      "request_latency",
			Value:     50.0 + float64(i%10),
			Timestamp: now.Add(-time.Duration(100-i) * time.Second),
		})
		preloadMetrics = append(preloadMetrics, common.Metric{
			Name:      "error_rate",
			Value:     float64(i%20) / 100,
			Timestamp: now.Add(-time.Duration(100-i) * time.Second),
		})
	}
	if err := app.alertEngine.PreloadMetrics(preloadMetrics); err != nil {
		logger.Warn("Failed to preload metrics", map[string]interface{}{"error": err.Error()})
	}

	go app.sloMonitor.Start(ctx, 10*time.Second)

	if err := app.logPipeline.Start(); err != nil {
		logger.Warn("Failed to start log pipeline", map[string]interface{}{"error": err.Error()})
	}

	go func() {
		for i := 0; i < 10; i++ {
			entry := &common.LogEntry{
				ID:        common.NewID(),
				Timestamp: time.Now(),
				Level:     "info",
				Message:   fmt.Sprintf("{\"level\": \"info\", \"msg\": \"Pipeline test message %d\", \"service\": \"backup-engine\"}", i),
				TraceID:   common.GenerateTraceID(),
				Service:   "backup-engine",
				Fields:    make(map[string]interface{}),
			}
			app.logPipeline.Ingest(entry)
			time.Sleep(100 * time.Millisecond)
		}
	}()

	go func() {
		logger.Info("Running scheduled tasks...")
		if err := app.scheduler.Run(ctx); err != nil {
			logger.Error("Scheduler error", map[string]interface{}{"error": err.Error()})
		}
	}()

	if err := app.gateway.Start(8080); err != nil {
		return fmt.Errorf("failed to start gateway: %w", err)
	}

	logger.Info("Backup Engine is running on port 8080")
	app.demo(ctx)

	return nil
}

func (app *App) demo(ctx context.Context) {
	time.Sleep(500 * time.Millisecond)

	testData := []byte("important data that needs backup " + time.Now().String())
	info, err := app.storage.Backup(ctx, "demo-backup", testData)
	if err != nil {
		logger.Error("Demo backup failed", map[string]interface{}{"error": err.Error()})
		return
	}

	logger.Info("Demo backup created", map[string]interface{}{
		"backup_id": info.ID,
		"size":      info.Size,
		"checksum":  info.Checksum,
	})

	restored, err := app.storage.Restore(ctx, info.ID)
	if err != nil {
		logger.Error("Demo restore failed", map[string]interface{}{"error": err.Error()})
		return
	}

	logger.Info("Demo restore completed", map[string]interface{}{
		"matches": string(restored) == string(testData),
	})

	snapshot, err := app.storage.CreateSnapshot()
	if err != nil {
		logger.Warn("Demo snapshot failed", map[string]interface{}{"error": err.Error()})
	} else {
		logger.Info("Demo snapshot created", map[string]interface{}{
			"snapshot_id": snapshot.ID,
			"entries":     len(snapshot.Data),
		})
	}

	cacheHits, cacheMisses, cacheSize := app.alertEngine.GetCacheStats()
	logger.Info("Alert engine cache stats", map[string]interface{}{
		"hits":   cacheHits,
		"misses": cacheMisses,
		"size":   cacheSize,
		"preloaded": app.alertEngine.IsPreloaded(),
	})

	pipelineConfig := app.logPipeline.GetConfig()
	if pipelineConfig != nil {
		logger.Info("Log pipeline config (hot reload)", map[string]interface{}{
			"processors": len(pipelineConfig.Processors),
			"filters":    len(pipelineConfig.Filters),
			"outputs":    len(pipelineConfig.Outputs),
		})
	}

	metric := common.Metric{
		Name:      "request_latency",
		Value:     150.0,
		Timestamp: time.Now(),
		Labels:    map[string]string{"endpoint": "/api/backup"},
	}

	results, err := app.anomaly.DetectAll(metric)
	if err != nil {
		logger.Error("Anomaly detection failed", map[string]interface{}{"error": err.Error()})
		return
	}

	for _, result := range results {
		logger.Info("Anomaly detection result", map[string]interface{}{
			"algorithm": result.Algorithm,
			"is_anomaly": result.IsAnomaly,
			"score":      result.Score,
			"expected":   result.Expected,
		})
	}

	sli, _ := app.sloMonitor.CalculateSLI("availability", 24*time.Hour)
	budget, _ := app.sloMonitor.GetErrorBudgetStatus("availability")

	logger.Info("SLO Status", map[string]interface{}{
		"sli_value":          sli.Value,
		"target":             99.9,
		"remaining_budget":   fmt.Sprintf("%.2f%%", budget.Remaining),
		"burn_rate":          fmt.Sprintf("%.2f", budget.BurnRate),
	})

	forecastSLO, forecastBudget, _ := app.sloMonitor.CalculateSLOForecast("availability", 7)
	logger.Info("SLO Forecast (7 days)", map[string]interface{}{
		"forecast_slo":       fmt.Sprintf("%.2f%%", forecastSLO),
		"forecast_budget":    fmt.Sprintf("%.2f%%", forecastBudget),
	})

	progress := app.scheduler.GetProgress()
	tasks := app.scheduler.GetAllTasks()
	logger.Info("Scheduler status", map[string]interface{}{
		"progress": fmt.Sprintf("%.1f%%", progress),
		"total_tasks": len(tasks),
	})

	for _, task := range tasks {
		logger.Info("Task status", map[string]interface{}{
			"task_id":   task.ID,
			"task_name": task.Name,
			"status":    task.Status,
			"progress":  task.Progress,
		})
	}

	processorMetrics := app.processor.GetMetrics()
	metricsJSON, _ := json.Marshal(processorMetrics)
	logger.Info("Processor metrics", map[string]interface{}{
		"metrics": string(metricsJSON),
	})

	alerts := app.alertEngine.GetActiveAlerts()
	logger.Info("Active alerts", map[string]interface{}{"count": len(alerts)})
	for _, alert := range alerts {
		logger.Warn("Active alert", map[string]interface{}{
			"alert_id": alert.ID,
			"rule_name": alert.RuleName,
			"severity":  alert.Severity,
			"message":   alert.Message,
		})
	}

	logger.Info("Demo completed successfully")
}

func (app *App) shutdown(ctx context.Context) {
	logger.Info("Shutting down Backup Engine...")

	if app.gateway != nil {
		shutdownCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		app.gateway.Stop(shutdownCtx)
	}

	if app.alertEngine != nil {
		app.alertEngine.Stop()
	}

	if app.logPipeline != nil {
		app.logPipeline.Stop()
	}

	if app.storage != nil {
		app.storage.Cleanup()
	}

	logger.Info("Backup Engine shutdown complete")
}

func init() {
	http.DefaultClient.Timeout = 30 * time.Second
}
