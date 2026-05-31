package main

import (
	"context"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/datatransform/platform/internal/apigateway"
	"github.com/datatransform/platform/internal/cdc"
	"github.com/datatransform/platform/internal/core"
	"github.com/datatransform/platform/internal/lifecycle"
	"github.com/datatransform/platform/internal/lineage"
	"github.com/datatransform/platform/internal/metadata"
	"github.com/datatransform/platform/internal/scheduler"
	"github.com/datatransform/platform/internal/streaming"
	"github.com/datatransform/platform/internal/vectorindex"
	"github.com/datatransform/platform/pkg/cache"
	"github.com/datatransform/platform/pkg/config"
	"github.com/datatransform/platform/pkg/database"
	"github.com/datatransform/platform/pkg/logger"
	"github.com/datatransform/platform/pkg/models"
	"github.com/datatransform/platform/pkg/utils"
	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.uber.org/zap"
)

type Application struct {
	config          *config.Config
	coreProcessor   *core.CoreProcessor
	scheduler       *scheduler.Scheduler
	vectorIndex     *vectorindex.VectorIndex
	apiGateway      *apigateway.APIGateway
	cdcCapture      *cdc.CDCCapture
	lifecycleMgr    *lifecycle.DataLifecycleManager
	queryEngine     *streaming.StreamingQueryEngine
	lineageParser   *lineage.LineageParser
	metadataCrawler *metadata.MetadataCrawler
	router          *gin.Engine
	promRegistry    *prometheus.Registry
	httpServer      *http.Server
}

func main() {
	if err := logger.Init(); err != nil {
		panic("failed to initialize logger: " + err.Error())
	}
	defer logger.Sync()

	cfg := config.Load()

	app := &Application{
		config:       cfg,
		promRegistry: prometheus.NewRegistry(),
	}

	if err := app.initialize(); err != nil {
		logger.Error("failed to initialize application", zap.Error(err))
		os.Exit(1)
	}

	app.setupDefaultConfig()

	if err := app.startComponents(); err != nil {
		logger.Error("failed to start components", zap.Error(err))
		os.Exit(1)
	}

	go app.runHTTP()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("shutting down application")
	app.shutdown()
}

func (app *Application) initialize() error {
	logger.Info("initializing application with enhanced features")

	if err := database.Init(&app.config.Database); err != nil {
		logger.Warn("failed to initialize database, continuing without persistent storage", zap.Error(err))
	}

	if err := cache.Init(&app.config.Redis); err != nil {
		logger.Warn("failed to initialize cache, continuing without cache", zap.Error(err))
	}

	app.coreProcessor = core.NewCoreProcessorWithCache(100, core.CacheConfig{
		L1Enabled:  true,
		L1Capacity: 10000,
		L1TTL:      10 * time.Minute,
		L2Enabled:  false,
		L2TTL:      30 * time.Minute,
	})

	app.scheduler = scheduler.NewSchedulerWithBatching(scheduler.BatchingConfig{
		MaxBatchSize:  50,
		BatchTimeout:  100 * time.Millisecond,
		MaxBatchWait:  500 * time.Millisecond,
		AutoFlush:     true,
	})

	app.vectorIndex = vectorindex.NewVectorIndexWithConfig(
		128,
		vectorindex.DistanceCosine,
		vectorindex.MonitoringConfig{
			Enabled:  true,
			Registry: app.promRegistry,
		},
	)

	app.apiGateway = apigateway.NewAPIGateway(
		apigateway.AuthConfig{JWTSecret: app.config.Auth.JWTSecret},
		apigateway.RateLimiterConfig{
			RequestsPerSecond: app.config.RateLimit.RequestsPerSecond,
			BurstSize:         app.config.RateLimit.BurstSize,
		},
	)

	app.cdcCapture = cdc.NewCDCCapture(cdc.CDCConfig{
		DatabaseType: "postgres",
		Connection:   app.config.Database.Host,
		Tables:       []string{"users", "orders", "products"},
	})
	app.cdcCapture.AddOutput(&cdc.ConsoleOutput{})

	app.lifecycleMgr = lifecycle.NewDataLifecycleManager()
	app.lifecycleMgr.AddMigrationPolicy(&lifecycle.MigrationPolicy{
		SourceTier:      lifecycle.TierHot,
		TargetTier:      lifecycle.TierWarm,
		AgeThreshold:    7 * 24 * time.Hour,
		AccessThreshold: 3 * 24 * time.Hour,
	})
	app.lifecycleMgr.AddMigrationPolicy(&lifecycle.MigrationPolicy{
		SourceTier:      lifecycle.TierWarm,
		TargetTier:      lifecycle.TierCold,
		AgeThreshold:    30 * 24 * time.Hour,
		AccessThreshold: 14 * 24 * time.Hour,
	})

	app.queryEngine = streaming.NewStreamingQueryEngine()
	app.lineageParser = lineage.NewLineageParser()

	app.metadataCrawler = metadata.NewMetadataCrawler(metadata.ScanConfig{
		SampleRowCount:    10,
		IncludeStatistics: true,
		IncludeSampleData: true,
		MaxTableSize:      1024 * 1024 * 1024,
	})
	app.metadataCrawler.AddDataSource(
		"primary",
		app.config.Database.Host,
		5432,
		app.config.Database.DBName,
		"postgres",
	)

	app.router = gin.New()
	app.apiGateway.SetupRoutes(app.router)

	return nil
}

func (app *Application) setupDefaultConfig() {
	defaultConfig := &models.Config{
		ConfigID:  "cfg_default",
		Namespace: "development",
		Version:   1,
		Parameters: map[string]interface{}{
			"timeout": 30.0,
			"retries": 3.0,
			"rules": map[string]interface{}{
				"name":        map[string]interface{}{"type": "uppercase"},
				"description": map[string]interface{}{"type": "string"},
			},
		},
		Enabled:   true,
		AppliedAt: utils.CurrentTime(),
	}

	app.coreProcessor.SaveConfig(defaultConfig)
	logger.Info("default configuration loaded", zap.String("namespace", defaultConfig.Namespace))

	app.coreProcessor.WarmupCache([]*core.HandlerRequest{
		{
			Namespace: "development",
			Params:    map[string]interface{}{"type": "test"},
			Payload:   map[string]interface{}{"name": "warmup"},
			UseCache:  true,
		},
	})
}

func (app *Application) startComponents() error {
	logger.Info("starting application components with enhanced features")

	if err := app.coreProcessor.Start(); err != nil {
		logger.Warn("core processor start returned error", zap.Error(err))
	}

	if err := app.scheduler.Start(); err != nil {
		logger.Warn("scheduler start returned error", zap.Error(err))
	}
	go app.scheduler.AutoFlushLoop()

	if err := app.vectorIndex.Start(); err != nil {
		logger.Warn("vector index start returned error", zap.Error(err))
	}

	app.cdcCapture.Start()
	app.lifecycleMgr.Start()
	app.metadataCrawler.Start()

	app.scheduler.Listen(app.coreProcessor.EventChannel())

	_, err := app.scheduler.AddTask(&scheduler.TaskDefinition{
		Name:     "vector_index_build",
		CronExpr: "0 */5 * * * *",
		Handler: func() error {
			if !app.vectorIndex.IsBuilt() && app.vectorIndex.Size() > 0 {
				app.vectorIndex.BuildIndex()
			}
			return nil
		},
		Enabled: true,
	})
	if err != nil {
		logger.Error("failed to add vector_index_build task", zap.Error(err))
	}

	_, err = app.scheduler.AddTask(&scheduler.TaskDefinition{
		Name:     "metadata_scan",
		CronExpr: "0 0 * * * *",
		Handler: func() error {
			return app.metadataCrawler.TriggerScan("primary")
		},
		Enabled: true,
	})
	if err != nil {
		logger.Error("failed to add metadata_scan task", zap.Error(err))
	}

	app.router.GET("/health", app.healthHandler)
	app.router.GET("/api/v1/status", app.statusHandler)

	app.router.GET("/metrics", gin.WrapH(promhttp.HandlerFor(app.promRegistry, promhttp.HandlerOpts{})))

	app.router.POST("/api/v1/process", app.processHandler)
	app.router.GET("/api/v1/vector/search", app.vectorSearchHandler)
	app.router.POST("/api/v1/vector/batch-add", app.vectorBatchAddHandler)

	app.router.POST("/api/v1/query/parse", app.queryParseHandler)
	app.router.POST("/api/v1/lineage/parse", app.lineageParseHandler)

	app.router.POST("/api/v1/test/core", app.testCoreHandler)
	app.router.POST("/api/v1/cache/invalidate", app.cacheInvalidateHandler)

	app.router.POST("/api/v1/scheduler/batch", app.schedulerBatchHandler)

	return nil
}

func (app *Application) runHTTP() {
	addr := ":" + app.config.Server.Port
	logger.Info("HTTP server starting", zap.String("address", addr))

	app.httpServer = &http.Server{
		Addr:    addr,
		Handler: app.router,
	}

	if err := app.httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		logger.Error("HTTP server failed", zap.Error(err))
	}
}

func (app *Application) shutdown() {
	logger.Info("shutting down components")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if app.httpServer != nil {
		if err := app.httpServer.Shutdown(ctx); err != nil {
			logger.Error("HTTP server shutdown failed", zap.Error(err))
		}
	}

	if err := app.vectorIndex.Stop(); err != nil {
		logger.Warn("vector index stop returned error", zap.Error(err))
	}

	if err := app.scheduler.Stop(); err != nil {
		logger.Warn("scheduler stop returned error", zap.Error(err))
	}

	if err := app.coreProcessor.Stop(); err != nil {
		logger.Warn("core processor stop returned error", zap.Error(err))
	}

	app.cdcCapture.Stop()
	app.lifecycleMgr.Stop()
	app.metadataCrawler.Stop()

	if err := database.Close(); err != nil {
		logger.Error("error closing database connection", zap.Error(err))
	}

	if err := cache.Close(); err != nil {
		logger.Error("error closing cache connection", zap.Error(err))
	}

	logger.Info("application shutdown complete")
}

func (app *Application) healthHandler(c *gin.Context) {
	c.JSON(200, gin.H{
		"code":    200,
		"status":  "healthy",
		"version": "1.1.0",
		"features": gin.H{
			"multi_level_cache": true,
			"batch_operations":  true,
			"prometheus_metrics": true,
		},
	})
}

func (app *Application) statusHandler(c *gin.Context) {
	c.JSON(200, gin.H{
		"code": 200,
		"data": gin.H{
			"core":         app.coreProcessor.Stats(),
			"scheduler":    app.scheduler.Stats(),
			"vector_index": app.vectorIndex.Stats(),
			"cdc":          app.cdcCapture.IsRunning(),
			"lifecycle":    app.lifecycleMgr.IsRunning(),
			"metadata":     app.metadataCrawler.IsRunning(),
		},
	})
}

func (app *Application) processHandler(c *gin.Context) {
	var request struct {
		Namespace string                 `json:"namespace" binding:"required"`
		Params    map[string]interface{} `json:"params" binding:"required"`
		Payload   map[string]interface{} `json:"payload"`
		UseCache  bool                   `json:"use_cache"`
	}

	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "invalid request"})
		return
	}

	if request.Payload == nil {
		request.Payload = make(map[string]interface{})
	}

	result := app.coreProcessor.ExecuteHandler(&core.HandlerRequest{
		TraceID:   utils.GenerateTraceID(),
		Params:    request.Params,
		Namespace: request.Namespace,
		Payload:   request.Payload,
		UseCache:  request.UseCache,
	})

	if !result.Success {
		c.JSON(500, gin.H{"code": 500, "message": result.Error})
		return
	}

	c.JSON(200, gin.H{
		"code":   200,
		"data":   result.Data,
		"source": result.Source,
	})
}

func (app *Application) vectorSearchHandler(c *gin.Context) {
	query := make([]float64, 128)
	for i := range query {
		query[i] = 0.5
	}

	results := app.vectorIndex.Search(query, 10, true)
	timing := app.vectorIndex.GetTimingStats()

	c.JSON(200, gin.H{
		"code":         200,
		"data":         results,
		"count":        len(results),
		"last_search_ms": float64(timing.SearchDurationNS) / 1e6,
	})
}

func (app *Application) vectorBatchAddHandler(c *gin.Context) {
	var request struct {
		Items []struct {
			ID     string    `json:"id" binding:"required"`
			Vector []float64 `json:"vector" binding:"required"`
			Data   interface{} `json:"data"`
		} `json:"items" binding:"required"`
	}

	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "invalid request"})
		return
	}

	items := make([]*vectorindex.VectorItem, 0, len(request.Items))
	for _, item := range request.Items {
		items = append(items, &vectorindex.VectorItem{
			ID:     item.ID,
			Vector: item.Vector,
			Data:   item.Data,
		})
	}

	count, err := app.vectorIndex.BatchAdd(items)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(200, gin.H{
		"code":          200,
		"added_count":   count,
		"total_items":   app.vectorIndex.Size(),
		"index_built":   app.vectorIndex.IsBuilt(),
	})
}

func (app *Application) queryParseHandler(c *gin.Context) {
	var request struct {
		SQL string `json:"sql" binding:"required"`
	}

	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "invalid request"})
		return
	}

	plan, err := app.queryEngine.Execute(request.SQL)
	if err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	c.JSON(200, gin.H{
		"code": 200,
		"data": plan,
	})
}

func (app *Application) lineageParseHandler(c *gin.Context) {
	var request struct {
		SQL string `json:"sql" binding:"required"`
	}

	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "invalid request"})
		return
	}

	result, err := app.lineageParser.ParseSQL(request.SQL)
	if err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	c.JSON(200, gin.H{
		"code": 200,
		"data": gin.H{
			"source_tables":    result.SourceTables,
			"target_table":     result.TargetTable,
			"column_lineage":   result.ColumnLineage,
			"tables":           result.Graph.GetAllTables(),
			"has_cycle":        result.Graph.HasCycle(),
			"topological_sort": result.Graph.TopologicalSort(),
		},
	})
}

func (app *Application) cacheInvalidateHandler(c *gin.Context) {
	var request struct {
		Key      string `json:"key"`
		Pattern  string `json:"pattern"`
	}

	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "invalid request"})
		return
	}

	var err error
	if request.Key != "" {
		err = app.coreProcessor.InvalidateCache(request.Key)
	} else if request.Pattern != "" {
		err = app.coreProcessor.InvalidateCacheByPattern(request.Pattern)
	}

	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(200, gin.H{
		"code": 200,
		"message": "cache invalidated",
	})
}

func (app *Application) schedulerBatchHandler(c *gin.Context) {
	var request struct {
		Operations []struct {
			ID     string `json:"id" binding:"required"`
			Action string `json:"action" binding:"required"`
		} `json:"operations" binding:"required"`
	}

	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "invalid request"})
		return
	}

	batchRequest := &scheduler.BatchRequest{}
	for _, op := range request.Operations {
		batchRequest.Operations = append(batchRequest.Operations, scheduler.BatchOperation{
			ID:     op.ID,
			Action: scheduler.BatchOperationType(op.Action),
		})
	}

	response := app.scheduler.ExecuteBatch(batchRequest)

	c.JSON(200, gin.H{
		"code":           200,
		"batch_id":       response.BatchID,
		"total_count":    response.TotalCount,
		"success_count":  response.SuccessCount,
		"failed_count":   response.FailedCount,
		"duration_ms":    response.DurationMS,
		"results":        response.Results,
	})
}

func (app *Application) testCoreHandler(c *gin.Context) {
	request := &core.HandlerRequest{
		TraceID:   utils.GenerateTraceID(),
		Params:    map[string]interface{}{"type": "test"},
		Namespace: "development",
		Payload: map[string]interface{}{
			"name":        "test user",
			"description": "TEST DESCRIPTION",
		},
		UseCache: true,
	}

	result := app.coreProcessor.ExecuteHandler(request)

	c.JSON(200, gin.H{
		"code":    200,
		"success": result.Success,
		"data":    result.Data,
		"error":   result.Error,
		"source":  result.Source,
	})
}
