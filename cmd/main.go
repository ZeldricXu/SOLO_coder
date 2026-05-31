package main

import (
	"context"
	"net/http"
	"os"
	"os/signal"
	"session154/internal/core"
	"session154/internal/dataaccess"
	"session154/internal/gateway"
	"session154/internal/logger"
	"session154/pkg/models"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

func main() {
	if err := logger.Init(logger.Config{
		LogDir:       "./logs",
		MaxSizeMB:    100,
		MaxBackups:   10,
		MaxAgeDays:   30,
		Compress:     true,
		Level:        "info",
		EnableStdout: true,
	}); err != nil {
		panic(err)
	}
	defer logger.Sync()

	logger.Info("starting session154 service")

	gw := gateway.NewAPIGateway(gateway.Config{
		Port:            8080,
		JWTSecret:       "your-secret-key-change-in-production",
		TokenExpiration: 24 * time.Hour,
		RateLimit:       100,
		RateLimitWindow: time.Minute,
	})

	gw.Auth().AddUser("admin", "admin123", []string{"admin", "user"})
	gw.Auth().AddUser("user", "user123", []string{"user"})

	scheduler := core.NewScheduler(10, 1000)
	scheduler.Start(context.Background())
	defer scheduler.Stop()

	scheduler.Register("metadata_crawl", func(ctx context.Context, task *core.Task) (interface{}, error) {
		logger.Info("processing metadata crawl task", zap.String("task_id", task.ID))
		task.SetProgress(0.5)
		time.Sleep(100 * time.Millisecond)
		task.SetProgress(1.0)
		return map[string]interface{}{"status": "completed"}, nil
	})

	scheduler.Register("vector_index_build", func(ctx context.Context, task *core.Task) (interface{}, error) {
		logger.Info("processing vector index build task", zap.String("task_id", task.ID))
		task.SetProgress(0.3)
		time.Sleep(100 * time.Millisecond)
		task.SetProgress(0.7)
		time.Sleep(100 * time.Millisecond)
		task.SetProgress(1.0)
		return map[string]interface{}{"index_built": true}, nil
	})

	cacheConfig := dataaccess.CacheConfig{
		DefaultTTL:     5 * time.Minute,
		MaxSize:        10000,
		Strategy:       dataaccess.CacheStrategyCacheAside,
		EvictionPolicy: dataaccess.EvictionLRU,
		EnableMetrics:  true,
	}
	cache := dataaccess.NewInMemoryCache(cacheConfig)
	cacheManager := dataaccess.NewCacheManager(cache, cacheConfig)

	setupAPI(gw, scheduler, cacheManager)

	if err := gw.Start(); err != nil {
		logger.Fatal("failed to start api gateway", zap.Error(err))
	}

	logger.Info("service started successfully", zap.Int("port", 8080))

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("shutting down service")

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := gw.Stop(ctx); err != nil {
		logger.Error("gateway shutdown error", zap.Error(err))
	}

	logger.Info("service stopped")
}

func setupAPI(gw *gateway.APIGateway, scheduler *core.Scheduler, cacheManager *dataaccess.CacheManager) {
	router := gw.Router()

	api := router.Group("/api/v1")
	api.Use(gw.Auth().Authenticate)

	resources := api.Group("/resources")
	{
		resources.POST("", gw.Authorizer().RequireRole("admin"), func(c *gin.Context) {
			var req models.ResourceRequest
			if err := c.ShouldBindJSON(&req); err != nil {
				c.JSON(http.StatusBadRequest, models.APIResponse{Code: 400, Msg: "invalid request"})
				return
			}

			task := core.NewTask(req.Type, req.Config, core.WithLabels(req.Labels))
			if err := scheduler.Submit(task); err != nil {
				c.JSON(http.StatusInternalServerError, models.APIResponse{Code: 500, Msg: "failed to submit task"})
				return
			}

			c.JSON(http.StatusCreated, models.APIResponse{
				Code: 201,
				Data: models.ResourceResponse{ID: task.ID, Status: string(task.Status)},
			})
		})

		resources.GET("/:id/status", func(c *gin.Context) {
			id := c.Param("id")
			task, exists := scheduler.GetTask(id)
			if !exists {
				c.JSON(http.StatusNotFound, models.APIResponse{Code: 404, Msg: "task not found"})
				return
			}

			c.JSON(http.StatusOK, models.APIResponse{Code: 200, Data: task.ToStatusResponse()})
		})

		resources.POST("/batch", func(c *gin.Context) {
			var req models.BatchRequest
			if err := c.ShouldBindJSON(&req); err != nil {
				c.JSON(http.StatusBadRequest, models.APIResponse{Code: 400, Msg: "invalid request"})
				return
			}

			operations := make([]core.BatchOperation, len(req.Operations))

			for i, op := range req.Operations {
				operations[i] = core.BatchOperation{
					Action: op.Action,
					ID:     op.ID,
					Task:   core.NewTask("batch_operation", map[string]interface{}{"action": op.Action}),
				}
			}

			batchProcessor := core.NewBatchProcessor(scheduler)
			batchID, results := batchProcessor.Process(c.Request.Context(), operations)

			batchResults := make([]models.BatchOperationResult, len(results))
			for i, r := range results {
				batchResults[i] = models.BatchOperationResult{
					ID:      r.ID,
					Success: r.Success,
					Message: r.Message,
				}
			}

			c.JSON(http.StatusOK, models.APIResponse{
				Code: 200,
				Data: models.BatchResponse{BatchID: batchID, Results: batchResults},
			})
		})
	}

	cacheAPI := api.Group("/cache")
	{
		cacheAPI.GET("/:key", func(c *gin.Context) {
			key := c.Param("key")
			value, err := cacheManager.Get(c.Request.Context(), key)
			if err != nil {
				c.JSON(http.StatusNotFound, models.APIResponse{Code: 404, Msg: "key not found"})
				return
			}
			c.JSON(http.StatusOK, models.APIResponse{Code: 200, Data: value})
		})

		cacheAPI.POST("/:key", func(c *gin.Context) {
			key := c.Param("key")
			var body struct {
				Value interface{} `json:"value"`
				TTL   int         `json:"ttl_seconds"`
			}
			if err := c.ShouldBindJSON(&body); err != nil {
				c.JSON(http.StatusBadRequest, models.APIResponse{Code: 400, Msg: "invalid request"})
				return
			}

			ttl := time.Duration(body.TTL) * time.Second
			if err := cacheManager.Set(c.Request.Context(), key, body.Value, ttl); err != nil {
				c.JSON(http.StatusInternalServerError, models.APIResponse{Code: 500, Msg: "failed to set cache"})
				return
			}
			c.JSON(http.StatusOK, models.APIResponse{Code: 200, Msg: "ok"})
		})

		cacheAPI.DELETE("/:key", func(c *gin.Context) {
			key := c.Param("key")
			cacheManager.Delete(c.Request.Context(), key)
			c.JSON(http.StatusOK, models.APIResponse{Code: 200, Msg: "ok"})
		})
	}
}
