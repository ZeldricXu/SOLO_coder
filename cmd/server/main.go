package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/solocoder/logrotate/internal/domain"
	"github.com/solocoder/logrotate/pkg/config"
	"github.com/solocoder/logrotate/pkg/core"
	"github.com/solocoder/logrotate/pkg/document"
	"github.com/solocoder/logrotate/pkg/gateway"
	"github.com/solocoder/logrotate/pkg/gpu"
	"github.com/solocoder/logrotate/pkg/logger"
	"github.com/solocoder/logrotate/pkg/monitoring"
	"github.com/solocoder/logrotate/pkg/prompt"
	"github.com/solocoder/logrotate/pkg/scheduler"
)

type Application struct {
	ConfigManager     *config.Manager
	Logger            *logger.Manager
	TaskScheduler     *scheduler.Scheduler
	Metrics           *monitoring.Metrics
	DataProcessor     *core.Processor
	APIGateway        *gateway.Gateway
	DocumentPipeline  *document.Pipeline
	PromptManager     *prompt.Manager
	GPUScheduler      *gpu.Scheduler
	HTTPServer        *http.Server
}

func main() {
	app := &Application{}

	if err := app.Init(); err != nil {
		log.Fatalf("Failed to initialize application: %v", err)
	}
	defer app.Cleanup()

	if err := app.Run(); err != nil {
		log.Fatalf("Application error: %v", err)
	}
}

func (app *Application) Init() error {
	var err error

	app.ConfigManager, err = config.New(
		config.WithConfigFile("./configs/config.yaml"),
		config.WithEnvPrefix("APP"),
	)
	if err != nil {
		return fmt.Errorf("init config: %w", err)
	}

	logCfg := logger.Config{
		LogPath:       app.ConfigManager.GetString("log.path"),
		MaxSize:       app.ConfigManager.GetInt("log.max_size"),
		MaxBackups:    app.ConfigManager.GetInt("log.max_backups"),
		MaxAge:        app.ConfigManager.GetInt("log.max_age"),
		Compress:      app.ConfigManager.GetBool("log.compress"),
		Level:         app.ConfigManager.GetString("log.level"),
		EnableConsole: app.ConfigManager.GetBool("log.enable_console"),
		EnableFile:    app.ConfigManager.GetBool("log.enable_file"),
	}
	app.Logger, err = logger.New(logCfg)
	if err != nil {
		return fmt.Errorf("init logger: %w", err)
	}
	logger.InitGlobal(logCfg)

	app.TaskScheduler = scheduler.New(scheduler.WithMaxWorkers(20))

	app.Metrics = monitoring.New(
		monitoring.WithCollectInterval(30 * time.Second),
		monitoring.WithFlushCallback(func(snapshot interface{}) {
			logger.Info("Metrics snapshot collected", logger.String("snapshot_id", fmt.Sprintf("%v", snapshot)))
		}),
	)

	app.DataProcessor = core.NewProcessor()
	if err := registerSchemas(app.DataProcessor); err != nil {
		return fmt.Errorf("register schemas: %w", err)
	}

	gatewayCfg := gateway.GatewayConfig{
		EnableRequestLog:  true,
		EnableTrace:       true,
		EnableMetrics:     true,
	}
	app.APIGateway = gateway.New(gatewayCfg)

	app.DocumentPipeline = document.NewPipeline()
	app.PromptManager = prompt.NewManager()

	gpuCfg := gpu.SchedulerConfig{
		EnablePreemption:    true,
		PreemptionThreshold: 0.85,
		MaxConcurrentTasks:  10,
	}
	app.GPUScheduler = gpu.NewScheduler(gpuCfg)
	registerMockGPUs(app.GPUScheduler)

	registerTaskHandlers(app.TaskScheduler, app.DataProcessor, app.Metrics)

	gin.SetMode(app.ConfigManager.GetString("server.mode"))
	r := gin.New()

	app.APIGateway.SetupRoutes(r)
	app.registerAdditionalRoutes(r)

	host := app.ConfigManager.GetString("server.host")
	port := app.ConfigManager.GetInt("server.port")
	addr := fmt.Sprintf("%s:%d", host, port)

	app.HTTPServer = &http.Server{
		Addr:    addr,
		Handler: r,
	}

	logger.Info("Application initialized successfully")
	return nil
}

func (app *Application) Run() error {
	errChan := make(chan error, 1)

	go func() {
		logger.Infof("Starting HTTP server on %s", app.HTTPServer.Addr)
		if err := app.HTTPServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			errChan <- fmt.Errorf("http server: %w", err)
		}
	}()

	go app.runDemoTasks()

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

	select {
	case err := <-errChan:
		return err
	case sig := <-sigChan:
		logger.Infof("Received signal: %v", sig)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := app.HTTPServer.Shutdown(ctx); err != nil {
		logger.Errorf("HTTP server shutdown error: %v", err)
	}

	return nil
}

func (app *Application) Cleanup() {
	logger.Info("Cleaning up resources...")

	if app.HTTPServer != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		app.HTTPServer.Shutdown(ctx)
		cancel()
	}

	if app.TaskScheduler != nil {
		app.TaskScheduler.Stop()
	}

	if app.Metrics != nil {
		app.Metrics.Close()
	}

	if app.GPUScheduler != nil {
		app.GPUScheduler.Stop()
	}

	if app.Logger != nil {
		app.Logger.Close()
	}

	if app.ConfigManager != nil {
		app.ConfigManager.Close()
	}

	logger.Info("Cleanup completed")
}

func (app *Application) registerAdditionalRoutes(r *gin.Engine) {
	api := r.Group("/api/v1")

	api.GET("/metrics", func(c *gin.Context) {
		snapshot := app.Metrics.CollectSnapshot()
		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": snapshot,
		})
	})

	api.GET("/tasks", func(c *gin.Context) {
		tasks := app.TaskScheduler.ListTasks()
		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": tasks,
		})
	})

	api.GET("/gpu/stats", func(c *gin.Context) {
		stats := app.GPUScheduler.GetGPUStats()
		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": stats,
		})
	})

	api.POST("/documents/process", func(c *gin.Context) {
		var req struct {
			Content string `json:"content"`
			Format  string `json:"format"`
		}
		if err := c.ShouldBindJSON(&req); err != nil {
			app.APIGateway.BadRequest(c, err.Error())
			return
		}

		doc, err := app.DocumentPipeline.Process(c.Request.Context(), []byte(req.Content), req.Format)
		if err != nil {
			app.APIGateway.InternalError(c, err.Error())
			return
		}

		app.APIGateway.Success(c, doc)
	})

	api.POST("/prompt/render", func(c *gin.Context) {
		var req struct {
			PromptID  string                 `json:"prompt_id"`
			Variables map[string]interface{} `json:"variables"`
		}
		if err := c.ShouldBindJSON(&req); err != nil {
			app.APIGateway.BadRequest(c, err.Error())
			return
		}

		rendered, err := app.PromptManager.RenderPrompt(c.Request.Context(), req.PromptID, req.Variables)
		if err != nil {
			app.APIGateway.InternalError(c, err.Error())
			return
		}

		app.APIGateway.Success(c, gin.H{"rendered": rendered})
	})
}

func registerSchemas(processor *core.Processor) error {
	userSchema := &core.Schema{
		Name: "user",
		Transforms: []core.TransformRule{
			{SourceField: "name", TargetField: "name", Transform: "trim", Required: true},
			{SourceField: "email", TargetField: "email", Transform: "lower", Required: true},
			{SourceField: "age", TargetField: "age", Transform: "int"},
			{SourceField: "active", TargetField: "active", Transform: "bool", DefaultValue: true},
		},
		Validations: []core.ValidationRule{
			{Field: "email", Type: "string"},
			{Field: "age", Type: "int", Min: 0, Max: 150},
		},
	}

	return processor.RegisterSchema(userSchema)
}

func registerTaskHandlers(s *scheduler.Scheduler, processor *core.Processor, metrics *monitoring.Metrics) {
	s.RegisterHandler("data_process", func(ctx context.Context, t *domain.Task) error {
		data, ok := t.Parameters["data"].(map[string]interface{})
		if !ok {
			return fmt.Errorf("invalid data format")
		}

		schemaName, _ := t.Parameters["schema"].(string)
		if schemaName == "" {
			schemaName = "user"
		}

		timer := metrics.StartTimer("data_process_duration", map[string]string{"schema": schemaName})
		defer timer.Stop()

		result := processor.Process(ctx, data, schemaName)
		if !result.Success {
			metrics.CounterInc("data_process_errors", 1, map[string]string{"schema": schemaName})
			return fmt.Errorf("processing failed: %v", result.Errors)
		}

		metrics.CounterInc("data_process_success", 1, map[string]string{"schema": schemaName})
		return nil
	})

	s.RegisterHandler("gpu_task", func(ctx context.Context, t *domain.Task) error {
		duration := 2 * time.Second
		if d, ok := t.Parameters["duration"].(float64); ok {
			duration = time.Duration(d) * time.Second
		}

		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(duration):
		}

		return nil
	})
}

func registerMockGPUs(s *gpu.Scheduler) {
	for i := 0; i < 4; i++ {
		gpu := &gpu.GPU{
			NodeID:      "node-01",
			DeviceIndex: i,
			TotalMemory: 24 * 1024 * 1024 * 1024,
			UsedMemory:  0,
			Utilization: 0,
			Status:      "available",
			Labels: map[string]string{
				"type":   "A100",
				"memory": "24GB",
			},
		}
		s.AddGPU(gpu)
	}
}

func (app *Application) runDemoTasks() {
	time.Sleep(2 * time.Second)

	logger.Info("Running demo tasks...")

	for i := 0; i < 5; i++ {
		taskData := map[string]interface{}{
			"name":  fmt.Sprintf("User %d", i),
			"email": fmt.Sprintf("user%d@example.com", i),
			"age":   20 + i,
		}

		_, err := app.TaskScheduler.Submit(&scheduler.Task{
			Name:       fmt.Sprintf("process-user-%d", i),
			Type:       "data_process",
			Priority:   1,
			Parameters: map[string]interface{}{"data": taskData, "schema": "user"},
			MaxRetries: 2,
		})
		if err != nil {
			logger.Errorf("Failed to submit task: %v", err)
		}
	}

	for i := 0; i < 3; i++ {
		gpuTask := &gpu.Task{
			Name:           fmt.Sprintf("ml-training-%d", i),
			Priority:       gpu.PriorityMedium,
			RequiredMemory: 8 * 1024 * 1024 * 1024,
			RequiredGPU:    1,
			TimeoutSeconds: 30,
			Labels: map[string]string{
				"model": "gpt-2",
			},
			RunFunc: func(ctx context.Context, resources []*gpu.GPU) error {
				logger.Infof("Running GPU task on %d GPUs", len(resources))
				time.Sleep(3 * time.Second)
				return nil
			},
		}
		_, err := app.GPUScheduler.SubmitTask(gpuTask)
		if err != nil {
			logger.Errorf("Failed to submit GPU task: %v", err)
		}
	}
}
