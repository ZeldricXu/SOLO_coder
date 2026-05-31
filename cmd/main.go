package main

import (
	"context"
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/solocoder/tasktracker/internal/apicontract"
	"github.com/solocoder/tasktracker/internal/config"
	"github.com/solocoder/tasktracker/internal/environment"
	"github.com/solocoder/tasktracker/internal/featureflag"
	"github.com/solocoder/tasktracker/internal/gateway"
	"github.com/solocoder/tasktracker/internal/logger"
	"github.com/solocoder/tasktracker/internal/models"
	"github.com/solocoder/tasktracker/internal/qualitygate"
	"github.com/solocoder/tasktracker/internal/scaffold"
	"github.com/solocoder/tasktracker/internal/scheduler"
	"github.com/solocoder/tasktracker/internal/storage"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

type App struct {
	cfgManager   *config.Manager
	scheduler    *scheduler.Scheduler
	qualityGate  *qualitygate.QualityGate
	storage      *storage.StorageManager
	contractMgr  *apicontract.ContractManager
	envMgr       *environment.EnvironmentManager
	scaffoldGen  *scaffold.ScaffoldGenerator
	flagMgr      *featureflag.FeatureFlagManager
	apiGateway   *gateway.APIGateway
	router       *gin.Engine
	httpServer   *http.Server
}

func main() {
	logger.Init(logger.Config{
		Level:      "info",
		Encoding:   "json",
		OutputPath: "",
	})
	defer logger.Sync()

	app := NewApp()

	if err := app.Setup(); err != nil {
		logger.Fatal("Failed to setup application", logger.ErrorField(err))
	}

	app.Start()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	app.Stop()
}

func NewApp() *App {
	return &App{}
}

func (a *App) Setup() error {
	logger.Info("Setting up application...")

	a.cfgManager = config.NewManager("production")
	a.registerConfigDefaults()

	a.scheduler = scheduler.NewScheduler(scheduler.Config{
		WorkerCount: 5,
		QueueSize:   100,
		MaxRetries:  3,
	}, a.cfgManager)

	a.qualityGate = qualitygate.NewQualityGate(qualitygate.Config{
		MaxCritical: 0,
		MaxMajor:    5,
		MaxMinor:    20,
		MinScore:    70.0,
	}, a.cfgManager)

	a.storage = storage.NewStorageManager(storage.Config{
		BaseDir:    "./data",
		EncryptKey: "",
	})

	a.contractMgr = apicontract.NewContractManager()

	a.envMgr = environment.NewEnvironmentManager(environment.Config{
		MaxEnvironments: 10,
		DefaultTTL:      24 * time.Hour,
		ReclaimInterval: 1 * time.Hour,
	})

	a.scaffoldGen = scaffold.NewScaffoldGenerator(scaffold.Config{
		TemplatesDir: "./templates",
	})

	a.flagMgr = featureflag.NewFeatureFlagManager()

	a.apiGateway = gateway.NewAPIGateway(gateway.Config{
		MaxLogs: 10000,
	})

	a.registerTaskHandlers()
	a.registerSampleFeatureFlags()
	a.registerSampleRoutes()

	a.setupRouter()

	return nil
}

func (a *App) registerConfigDefaults() {
	a.cfgManager.SetDefault("task_timeout", "30s")
	a.cfgManager.SetDefault("max_concurrent_tasks", 10)
	a.cfgManager.SetDefault("retry_delay", "1s")
	a.cfgManager.SetDefault("environment", "production")

	a.cfgManager.RegisterValidator("max_concurrent_tasks", config.ValidateRange(1, 100))
	a.cfgManager.RegisterValidator("task_timeout", config.ValidateRequired())
}

func (a *App) registerTaskHandlers() {
	a.scheduler.RegisterHandler("quality_analysis", func(ctx context.Context, task *models.Task) error {
		logger.Info("Processing quality analysis task", logger.String("task_id", task.ID))
		time.Sleep(100 * time.Millisecond)
		return nil
	})

	a.scheduler.RegisterHandler("backup", func(ctx context.Context, task *models.Task) error {
		logger.Info("Processing backup task", logger.String("task_id", task.ID))
		key, _ := task.Payload["key"].(string)
		if key != "" {
			a.storage.Backup(key)
		}
		return nil
	})

	a.scheduler.RegisterHandler("environment_provision", func(ctx context.Context, task *models.Task) error {
		logger.Info("Processing environment provision task", logger.String("task_id", task.ID))
		time.Sleep(500 * time.Millisecond)
		return nil
	})
}

func (a *App) registerSampleFeatureFlags() {
	a.flagMgr.CreateFlag(&featureflag.FeatureFlag{
		ID:          "new_ui",
		Name:        "New UI Interface",
		Description: "Enable the new user interface",
		Status:      featureflag.StatusRollout,
		DefaultValue: false,
		ValueType:   "boolean",
		Tags:        []string{"frontend", "experimental"},
	})

	a.flagMgr.CreateFlag(&featureflag.FeatureFlag{
		ID:          "fast_mode",
		Name:        "Fast Processing Mode",
		Description: "Enable faster processing at the cost of accuracy",
		Status:      featureflag.StatusDisabled,
		DefaultValue: false,
		ValueType:   "boolean",
		Tags:        []string{"performance"},
	})

	a.flagMgr.AddSegment(&featureflag.UserSegment{
		ID:   "beta_testers",
		Name: "Beta Testers",
		Conditions: map[string]interface{}{
			"role": "beta",
		},
	})
}

func (a *App) registerSampleRoutes() {
	a.apiGateway.AddRoute(&gateway.Route{
		Path:        "/api/v1/users",
		Method:      "GET",
		TargetURL:   "http://localhost:8081",
		TimeoutMs:   5000,
		RateLimit:   100,
		AuthRequired: true,
	})
}

func (a *App) setupRouter() {
	gin.SetMode(gin.ReleaseMode)
	a.router = gin.New()
	a.router.Use(gin.Recovery())
	a.router.Use(a.apiGateway.Middleware())

	a.router.GET("/health", a.healthCheck)
	a.router.GET("/api/v1/status", a.systemStatus)

	api := a.router.Group("/api/v1")
	{
		a.setupSchedulerRoutes(api)
		a.setupQualityGateRoutes(api)
		a.setupStorageRoutes(api)
		a.setupContractRoutes(api)
		a.setupEnvironmentRoutes(api)
		a.setupScaffoldRoutes(api)
		a.setupFeatureFlagRoutes(api)
		a.setupGatewayRoutes(api)
	}

	a.router.NoRoute(func(c *gin.Context) {
		c.JSON(404, gin.H{"code": 404, "message": "Not found"})
	})
}

func (a *App) setupSchedulerRoutes(api *gin.RouterGroup) {
	schedulerAPI := api.Group("/tasks")
	{
		schedulerAPI.POST("", a.createTask)
		schedulerAPI.GET("", a.listTasks)
		schedulerAPI.GET("/:id/status", a.getTaskStatus)
		schedulerAPI.POST("/batch", a.batchOperations)
		schedulerAPI.POST("/:id/cancel", a.cancelTask)
		schedulerAPI.GET("/stats", a.schedulerStats)
	}
}

func (a *App) setupQualityGateRoutes(api *gin.RouterGroup) {
	qaAPI := api.Group("/quality")
	{
		qaAPI.GET("/rules", a.listRules)
		qaAPI.POST("/analyze", a.analyzeCode)
		qaAPI.GET("/thresholds", a.getThresholds)
		qaAPI.POST("/thresholds", a.setThreshold)
	}
}

func (a *App) setupStorageRoutes(api *gin.RouterGroup) {
	storageAPI := api.Group("/storage")
	{
		storageAPI.POST("/:key", a.saveData)
		storageAPI.GET("/:key", a.loadData)
		storageAPI.DELETE("/:key", a.deleteData)
		storageAPI.POST("/:key/backup", a.backupData)
		storageAPI.POST("/backups/:backupId/restore", a.restoreBackup)
		storageAPI.GET("/backups", a.listBackups)
	}
}

func (a *App) setupContractRoutes(api *gin.RouterGroup) {
	contractAPI := api.Group("/contracts")
	{
		contractAPI.POST("/schemas", a.loadSchema)
		contractAPI.GET("/schemas", a.listSchemas)
		contractAPI.POST("/validate/:schemaId", a.validatePayload)
		contractAPI.POST("/mock/endpoints", a.addMockEndpoint)
		contractAPI.GET("/mock/endpoints", a.listMockEndpoints)
		contractAPI.POST("/mock/start", a.startMockServer)
		contractAPI.POST("/mock/stop", a.stopMockServer)
	}
}

func (a *App) setupEnvironmentRoutes(api *gin.RouterGroup) {
	envAPI := api.Group("/environments")
	{
		envAPI.POST("", a.createEnvironment)
		envAPI.GET("", a.listEnvironments)
		envAPI.GET("/:id", a.getEnvironment)
		envAPI.POST("/:id/start", a.startEnvironment)
		envAPI.POST("/:id/stop", a.stopEnvironment)
		envAPI.DELETE("/:id", a.destroyEnvironment)
		envAPI.POST("/:id/extend", a.extendEnvironmentTTL)
		envAPI.GET("/usage/stats", a.getEnvironmentStats)
		envAPI.GET("/usage/quota", a.getEnvironmentQuota)
	}
}

func (a *App) setupScaffoldRoutes(api *gin.RouterGroup) {
	scaffoldAPI := api.Group("/scaffold")
	{
		scaffoldAPI.GET("/templates", a.listTemplates)
		scaffoldAPI.GET("/templates/:name/questions", a.getTemplateQuestions)
		scaffoldAPI.POST("/generate", a.generateProject)
	}
}

func (a *App) setupFeatureFlagRoutes(api *gin.RouterGroup) {
	flagAPI := api.Group("/features")
	{
		flagAPI.POST("", a.createFeatureFlag)
		flagAPI.GET("", a.listFeatureFlags)
		flagAPI.GET("/:id", a.getFeatureFlag)
		flagAPI.PUT("/:id", a.updateFeatureFlag)
		flagAPI.DELETE("/:id", a.deleteFeatureFlag)
		flagAPI.POST("/:id/evaluate", a.evaluateFeatureFlag)
		flagAPI.POST("/segments", a.createSegment)
		flagAPI.GET("/segments", a.listSegments)
		flagAPI.GET("/stats", a.getFlagStats)
	}
}

func (a *App) setupGatewayRoutes(api *gin.RouterGroup) {
	gatewayAPI := api.Group("/gateway")
	{
		gatewayAPI.GET("/routes", a.listRoutes)
		gatewayAPI.POST("/routes", a.addRoute)
		gatewayAPI.DELETE("/routes", a.removeRoute)
		gatewayAPI.GET("/logs", a.getRequestLogs)
		gatewayAPI.GET("/traces/:traceId", a.getTrace)
		gatewayAPI.GET("/stats", a.getGatewayStats)
	}
}

func (a *App) Start() {
	logger.Info("Starting application...")

	a.scheduler.Start()

	a.httpServer = &http.Server{
		Addr:    ":8080",
		Handler: a.router,
	}

	go func() {
		logger.Info("HTTP server starting on :8080")
		if err := a.httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("HTTP server failed", logger.ErrorField(err))
		}
	}()

	logger.Info("Application started successfully")
}

func (a *App) Stop() {
	logger.Info("Stopping application...")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if a.httpServer != nil {
		a.httpServer.Shutdown(ctx)
	}

	a.scheduler.Stop()
	a.contractMgr.StopMockServer()
	a.envMgr.StopAutoReclaim()

	logger.Info("Application stopped successfully")
}

func (a *App) healthCheck(c *gin.Context) {
	c.JSON(200, gin.H{"code": 200, "status": "ok", "timestamp": time.Now().UTC()})
}

func (a *App) systemStatus(c *gin.Context) {
	c.JSON(200, gin.H{
		"code": 200,
		"data": gin.H{
			"scheduler":    a.scheduler.GetStats(),
			"environments": a.envMgr.GetQuotaUsage(),
			"gateway":      a.apiGateway.GetStats(),
			"features":     a.flagMgr.GetEvaluationStats(),
		},
	})
}

func (a *App) createTask(c *gin.Context) {
	var task models.Task
	if err := c.ShouldBindJSON(&task); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	if err := a.scheduler.Submit(&task); err != nil {
		c.JSON(500, gin.H{"code": 500, "error": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "data": gin.H{"id": task.ID, "status": task.Status}})
}

func (a *App) listTasks(c *gin.Context) {
	status := c.Query("status")
	tasks := a.scheduler.ListTasks(status)
	c.JSON(200, gin.H{"code": 200, "data": tasks})
}

func (a *App) getTaskStatus(c *gin.Context) {
	id := c.Param("id")
	task, err := a.scheduler.GetTaskStatus(id)
	if err != nil {
		c.JSON(404, gin.H{"code": 404, "error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": task})
}

func (a *App) batchOperations(c *gin.Context) {
	var req models.BatchRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	results := make([]map[string]interface{}, 0)
	for _, op := range req.Operations {
		result := map[string]interface{}{
			"id":     op.ID,
			"action": op.Action,
			"success": true,
		}
		switch op.Action {
		case "stop", "cancel":
			if err := a.scheduler.CancelTask(op.ID); err != nil {
				result["success"] = false
				result["error"] = err.Error()
			}
		default:
			result["success"] = false
			result["error"] = "unknown action"
		}
		results = append(results, result)
	}

	batchID := fmt.Sprintf("batch_%d", time.Now().UnixNano())
	c.JSON(200, gin.H{"code": 200, "data": gin.H{"batch_id": batchID, "results": results}})
}

func (a *App) cancelTask(c *gin.Context) {
	id := c.Param("id")
	if err := a.scheduler.CancelTask(id); err != nil {
		c.JSON(404, gin.H{"code": 404, "error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "task cancelled"})
}

func (a *App) schedulerStats(c *gin.Context) {
	c.JSON(200, gin.H{"code": 200, "data": a.scheduler.GetStats()})
}

func (a *App) listRules(c *gin.Context) {
	language := qualitygate.Language(c.Query("language"))
	rules := a.qualityGate.GetRules(language)
	c.JSON(200, gin.H{"code": 200, "data": rules})
}

func (a *App) analyzeCode(c *gin.Context) {
	var req struct {
		ProjectID string                 `json:"project_id"`
		Language  qualitygate.Language   `json:"language"`
		Files     map[string]string      `json:"files"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	report, err := a.qualityGate.Analyze(req.ProjectID, req.Language, req.Files)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "error": err.Error()})
		return
	}

	c.JSON(200, gin.H{"code": 200, "data": report})
}

func (a *App) getThresholds(c *gin.Context) {
	c.JSON(200, gin.H{"code": 200, "data": a.qualityGate.GetThresholds()})
}

func (a *App) setThreshold(c *gin.Context) {
	var req struct {
		Severity qualitygate.Severity `json:"severity"`
		Max      int                  `json:"max"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	a.qualityGate.SetThreshold(req.Severity, req.Max)
	c.JSON(200, gin.H{"code": 200, "message": "threshold updated"})
}

func (a *App) saveData(c *gin.Context) {
	key := c.Param("key")
	var data interface{}
	if err := c.ShouldBindJSON(&data); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	if err := a.storage.Save(key, data); err != nil {
		c.JSON(500, gin.H{"code": 500, "error": err.Error()})
		return
	}

	c.JSON(200, gin.H{"code": 200, "message": "saved"})
}

func (a *App) loadData(c *gin.Context) {
	key := c.Param("key")
	var data interface{}
	if err := a.storage.Load(key, &data); err != nil {
		c.JSON(404, gin.H{"code": 404, "error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": data})
}

func (a *App) deleteData(c *gin.Context) {
	key := c.Param("key")
	if err := a.storage.Delete(key); err != nil {
		c.JSON(500, gin.H{"code": 500, "error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "deleted"})
}

func (a *App) backupData(c *gin.Context) {
	key := c.Param("key")
	info, err := a.storage.Backup(key)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": info})
}

func (a *App) restoreBackup(c *gin.Context) {
	backupID := c.Param("backupId")
	var req struct {
		Key string `json:"key"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	if err := a.storage.Restore(backupID, req.Key); err != nil {
		c.JSON(500, gin.H{"code": 500, "error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "restored"})
}

func (a *App) listBackups(c *gin.Context) {
	backups := a.storage.ListBackups()
	c.JSON(200, gin.H{"code": 200, "data": backups})
}

func (a *App) loadSchema(c *gin.Context) {
	var req struct {
		ID      string                 `json:"id"`
		Type    apicontract.SchemaType `json:"type"`
		Version string                 `json:"version"`
		Content map[string]interface{} `json:"content"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	if err := a.contractMgr.LoadSchema(req.ID, req.Type, req.Version, req.Content); err != nil {
		c.JSON(500, gin.H{"code": 500, "error": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "message": "schema loaded"})
}

func (a *App) listSchemas(c *gin.Context) {
	schemas := a.contractMgr.ListSchemas()
	c.JSON(200, gin.H{"code": 200, "data": schemas})
}

func (a *App) validatePayload(c *gin.Context) {
	schemaID := c.Param("schemaId")
	var payload map[string]interface{}
	if err := c.ShouldBindJSON(&payload); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	result, err := a.contractMgr.ValidatePayload(schemaID, payload)
	if err != nil {
		c.JSON(404, gin.H{"code": 404, "error": err.Error()})
		return
	}

	c.JSON(200, gin.H{"code": 200, "data": result})
}

func (a *App) addMockEndpoint(c *gin.Context) {
	var endpoint apicontract.MockEndpoint
	if err := c.ShouldBindJSON(&endpoint); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	if err := a.contractMgr.AddMockEndpoint(&endpoint); err != nil {
		c.JSON(500, gin.H{"code": 500, "error": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "message": "endpoint added"})
}

func (a *App) listMockEndpoints(c *gin.Context) {
	endpoints := a.contractMgr.ListMockEndpoints()
	c.JSON(200, gin.H{"code": 200, "data": endpoints})
}

func (a *App) startMockServer(c *gin.Context) {
	var req struct {
		Port int `json:"port"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		req.Port = 9090
	}

	if err := a.contractMgr.StartMockServer(req.Port); err != nil {
		c.JSON(500, gin.H{"code": 500, "error": err.Error()})
		return
	}

	c.JSON(200, gin.H{"code": 200, "data": gin.H{"url": a.contractMgr.GetMockServerURL()}})
}

func (a *App) stopMockServer(c *gin.Context) {
	a.contractMgr.StopMockServer()
	c.JSON(200, gin.H{"code": 200, "message": "mock server stopped"})
}

func (a *App) createEnvironment(c *gin.Context) {
	var env environment.Environment
	if err := c.ShouldBindJSON(&env); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	created, err := a.envMgr.Create(&env)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "error": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "data": created})
}

func (a *App) listEnvironments(c *gin.Context) {
	owner := c.Query("owner")
	envType := environment.EnvironmentType(c.Query("type"))
	envs := a.envMgr.List(owner, envType)
	c.JSON(200, gin.H{"code": 200, "data": envs})
}

func (a *App) getEnvironment(c *gin.Context) {
	id := c.Param("id")
	env, err := a.envMgr.Get(id)
	if err != nil {
		c.JSON(404, gin.H{"code": 404, "error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": env})
}

func (a *App) startEnvironment(c *gin.Context) {
	id := c.Param("id")
	if err := a.envMgr.Start(id); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "environment started"})
}

func (a *App) stopEnvironment(c *gin.Context) {
	id := c.Param("id")
	if err := a.envMgr.Stop(id); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "environment stopped"})
}

func (a *App) destroyEnvironment(c *gin.Context) {
	id := c.Param("id")
	if err := a.envMgr.Destroy(id); err != nil {
		c.JSON(404, gin.H{"code": 404, "error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "environment destroyed"})
}

func (a *App) extendEnvironmentTTL(c *gin.Context) {
	id := c.Param("id")
	var req struct {
		Duration string `json:"duration"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	duration, err := time.ParseDuration(req.Duration)
	if err != nil {
		c.JSON(400, gin.H{"code": 400, "error": "invalid duration"})
		return
	}

	if err := a.envMgr.ExtendTTL(id, duration); err != nil {
		c.JSON(404, gin.H{"code": 404, "error": err.Error()})
		return
	}

	c.JSON(200, gin.H{"code": 200, "message": "TTL extended"})
}

func (a *App) getEnvironmentStats(c *gin.Context) {
	stats := a.envMgr.GetUsageStats()
	c.JSON(200, gin.H{"code": 200, "data": stats})
}

func (a *App) getEnvironmentQuota(c *gin.Context) {
	quota := a.envMgr.GetQuotaUsage()
	c.JSON(200, gin.H{"code": 200, "data": quota})
}

func (a *App) listTemplates(c *gin.Context) {
	templates := a.scaffoldGen.ListTemplates()
	c.JSON(200, gin.H{"code": 200, "data": templates})
}

func (a *App) getTemplateQuestions(c *gin.Context) {
	name := c.Param("name")
	questions, err := a.scaffoldGen.GetInteractiveQuestions(name)
	if err != nil {
		c.JSON(404, gin.H{"code": 404, "error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": questions})
}

func (a *App) generateProject(c *gin.Context) {
	var cfg scaffold.ProjectConfig
	if err := c.ShouldBindJSON(&cfg); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	cfg.Params = a.scaffoldGen.ApplyDefaults(cfg.TemplateName, cfg.Params)

	files, err := a.scaffoldGen.Generate(&cfg)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "error": err.Error()})
		return
	}

	c.JSON(200, gin.H{"code": 200, "data": gin.H{"files": files, "count": len(files)}})
}

func (a *App) createFeatureFlag(c *gin.Context) {
	var flag featureflag.FeatureFlag
	if err := c.ShouldBindJSON(&flag); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	if err := a.flagMgr.CreateFlag(&flag); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "data": flag})
}

func (a *App) listFeatureFlags(c *gin.Context) {
	tags := c.QueryArray("tags")
	flags := a.flagMgr.ListFlags(tags)
	c.JSON(200, gin.H{"code": 200, "data": flags})
}

func (a *App) getFeatureFlag(c *gin.Context) {
	id := c.Param("id")
	flag, err := a.flagMgr.GetFlag(id)
	if err != nil {
		c.JSON(404, gin.H{"code": 404, "error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": flag})
}

func (a *App) updateFeatureFlag(c *gin.Context) {
	id := c.Param("id")
	var flag featureflag.FeatureFlag
	if err := c.ShouldBindJSON(&flag); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}
	flag.ID = id

	if err := a.flagMgr.UpdateFlag(&flag); err != nil {
		c.JSON(404, gin.H{"code": 404, "error": err.Error()})
		return
	}

	c.JSON(200, gin.H{"code": 200, "data": flag})
}

func (a *App) deleteFeatureFlag(c *gin.Context) {
	id := c.Param("id")
	if err := a.flagMgr.DeleteFlag(id); err != nil {
		c.JSON(404, gin.H{"code": 404, "error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "flag deleted"})
}

func (a *App) evaluateFeatureFlag(c *gin.Context) {
	id := c.Param("id")
	var user featureflag.UserContext
	if err := c.ShouldBindJSON(&user); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	result := a.flagMgr.Evaluate(id, &user)
	c.JSON(200, gin.H{"code": 200, "data": result})
}

func (a *App) createSegment(c *gin.Context) {
	var segment featureflag.UserSegment
	if err := c.ShouldBindJSON(&segment); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	a.flagMgr.AddSegment(&segment)
	c.JSON(201, gin.H{"code": 201, "data": segment})
}

func (a *App) listSegments(c *gin.Context) {
	segments := a.flagMgr.ListSegments()
	c.JSON(200, gin.H{"code": 200, "data": segments})
}

func (a *App) getFlagStats(c *gin.Context) {
	stats := a.flagMgr.GetEvaluationStats()
	c.JSON(200, gin.H{"code": 200, "data": stats})
}

func (a *App) listRoutes(c *gin.Context) {
	routes := a.apiGateway.ListRoutes()
	c.JSON(200, gin.H{"code": 200, "data": routes})
}

func (a *App) addRoute(c *gin.Context) {
	var route gateway.Route
	if err := c.ShouldBindJSON(&route); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	a.apiGateway.AddRoute(&route)
	c.JSON(201, gin.H{"code": 201, "message": "route added"})
}

func (a *App) removeRoute(c *gin.Context) {
	var req struct {
		Method string `json:"method"`
		Path   string `json:"path"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "error": err.Error()})
		return
	}

	a.apiGateway.RemoveRoute(req.Method, req.Path)
	c.JSON(200, gin.H{"code": 200, "message": "route removed"})
}

func (a *App) getRequestLogs(c *gin.Context) {
	traceID := c.Query("trace_id")
	limit := 100
	if l := c.Query("limit"); l != "" {
		fmt.Sscanf(l, "%d", &limit)
	}
	logs := a.apiGateway.GetRequestLogs(traceID, limit)
	c.JSON(200, gin.H{"code": 200, "data": logs})
}

func (a *App) getTrace(c *gin.Context) {
	traceID := c.Param("traceId")
	trace := a.apiGateway.GetTrace(traceID)
	c.JSON(200, gin.H{"code": 200, "data": trace})
}

func (a *App) getGatewayStats(c *gin.Context) {
	stats := a.apiGateway.GetStats()
	c.JSON(200, gin.H{"code": 200, "data": stats})
}
