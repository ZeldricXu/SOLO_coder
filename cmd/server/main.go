package main

import (
	"context"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/datatrace/datatrace/internal/engine"
	"github.com/datatrace/datatrace/internal/gateway"
	"github.com/datatrace/datatrace/internal/logger"
	"github.com/datatrace/datatrace/internal/models"
	"github.com/gin-gonic/gin"
)

func main() {
	logConfig := logger.LogConfig{
		LogDir:         "./logs",
		MaxFileSize:    100 * 1024 * 1024,
		MaxFiles:       10,
		RotationPolicy: logger.RotationSize,
		Compress:       true,
		RetentionDays:  30,
		Level:          logger.LevelInfo,
	}
	log, err := logger.InitLogger(logConfig)
	if err != nil {
		panic("Failed to initialize logger: " + err.Error())
	}
	defer log.Close()

	log.Info("Starting DataTrace server", nil)

	engineConfig := engine.EngineConfig{
		MaxWorkers:      100,
		MaxQueueSize:    10000,
		RequestTimeout:  30 * time.Second,
		ShutdownTimeout: 10 * time.Second,
	}

	coreEngine := engine.NewCoreEngine(engineConfig)
	coreEngine.Start()
	defer coreEngine.Stop()

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()

	apiGateway := coreEngine.GetAPIGateway()
	r.Use(apiGateway.CORSMiddleware())
	r.Use(apiGateway.Middleware())
	r.Use(gin.Recovery())

	setupRoutes(r, coreEngine)

	server := &http.Server{
		Addr:         ":8080",
		Handler:      r,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	go func() {
		log.Info("Server listening on :8080", nil)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal("Server failed to start", map[string]interface{}{"error": err.Error()})
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Info("Shutting down server...", nil)

	ctx, cancel := context.WithTimeout(context.Background(), engineConfig.ShutdownTimeout)
	defer cancel()

	if err := server.Shutdown(ctx); err != nil {
		log.Error("Server forced to shutdown", map[string]interface{}{"error": err.Error()})
	}

	log.Info("Server exited", nil)
}

func setupRoutes(r *gin.Engine, e *engine.CoreEngine) {
	v1 := r.Group("/api/v1")
	{
		v1.POST("/resources", handleCreateResource(e))
		v1.GET("/resources/:id/status", handleGetResourceStatus(e))
		v1.POST("/resources/batch", handleBatchOperation(e))

		v1.POST("/execute", handleExecute(e))

		v1.GET("/stats", handleGetStats(e))
		v1.GET("/handlers", handleGetHandlers(e))

		v1.GET("/logs", handleGetLogs(e))
		v1.GET("/traces/:id", handleGetTrace(e))
	}

	r.GET("/health", handleHealth())
}

func handleCreateResource(e *engine.CoreEngine) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req models.ResourceCreateRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, models.APIResponse{
				Code: 400,
				Msg:  "Invalid request body: " + err.Error(),
			})
			return
		}

		request := &engine.Request{
			Action: "store_data",
			Payload: map[string]interface{}{
				"key":        req.Type + "_" + time.Now().String(),
				"data":       "resource_data",
				"tags":       req.Labels,
				"attributes": req.Config,
			},
		}

		resp, err := e.Execute(c.Request.Context(), request)
		if err != nil {
			c.JSON(http.StatusInternalServerError, models.APIResponse{
				Code: 500,
				Msg:  err.Error(),
			})
			return
		}

		c.JSON(http.StatusCreated, models.APIResponse{
			Code: 201,
			Data: models.ResourceCreateResponse{
				ID:     resp.RequestID,
				Status: "provisioning",
			},
		})
	}
}

func handleGetResourceStatus(e *engine.CoreEngine) gin.HandlerFunc {
	return func(c *gin.Context) {
		id := c.Param("id")

		request := &engine.Request{
			Action: "get_task_status",
			Payload: map[string]interface{}{
				"task_id": id,
			},
		}

		resp, err := e.Execute(c.Request.Context(), request)
		if err != nil {
			c.JSON(http.StatusInternalServerError, models.APIResponse{
				Code: 500,
				Msg:  err.Error(),
			})
			return
		}

		c.JSON(http.StatusOK, models.APIResponse{
			Code: 200,
			Data: resp.Data,
		})
	}
}

func handleBatchOperation(e *engine.CoreEngine) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req models.BatchRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, models.APIResponse{
				Code: 400,
				Msg:  "Invalid request body: " + err.Error(),
			})
			return
		}

		results := make([]map[string]interface{}, 0, len(req.Operations))
		for _, op := range req.Operations {
			request := &engine.Request{
				Action: op.Action,
				Payload: map[string]interface{}{
					"task_id": op.ID,
				},
			}

			resp, err := e.Execute(c.Request.Context(), request)
			result := map[string]interface{}{
				"id":     op.ID,
				"action": op.Action,
			}
			if err != nil {
				result["success"] = false
				result["error"] = err.Error()
			} else {
				result["success"] = true
				result["data"] = resp.Data
			}
			results = append(results, result)
		}

		c.JSON(http.StatusOK, models.APIResponse{
			Code: 200,
			Data: models.BatchResponse{
				BatchID: "batch_" + time.Now().Format("20060102150405"),
				Results: results,
			},
		})
	}
}

func handleExecute(e *engine.CoreEngine) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req engine.Request
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, models.APIResponse{
				Code: 400,
				Msg:  "Invalid request body: " + err.Error(),
			})
			return
		}

		traceID := c.GetHeader("X-Trace-ID")
		if traceID != "" {
			req.TraceID = traceID
		}

		resp, err := e.Execute(c.Request.Context(), &req)
		if err != nil {
			c.JSON(http.StatusInternalServerError, models.APIResponse{
				Code: 500,
				Msg:  err.Error(),
			})
			return
		}

		c.JSON(http.StatusOK, models.APIResponse{
			Code: 200,
			Data: resp,
		})
	}
}

func handleGetStats(e *engine.CoreEngine) gin.HandlerFunc {
	return func(c *gin.Context) {
		request := &engine.Request{
			Action: "get_stats",
		}

		resp, err := e.Execute(c.Request.Context(), request)
		if err != nil {
			c.JSON(http.StatusInternalServerError, models.APIResponse{
				Code: 500,
				Msg:  err.Error(),
			})
			return
		}

		c.JSON(http.StatusOK, models.APIResponse{
			Code: 200,
			Data: resp.Data,
		})
	}
}

func handleGetHandlers(e *engine.CoreEngine) gin.HandlerFunc {
	return func(c *gin.Context) {
		handlers := e.GetHandlers()
		c.JSON(http.StatusOK, models.APIResponse{
			Code: 200,
			Data: handlers,
		})
	}
}

func handleGetLogs(e *engine.CoreEngine) gin.HandlerFunc {
	return func(c *gin.Context) {
		gw := e.GetAPIGateway()
		logs := gw.GetRequestLogs()
		c.JSON(http.StatusOK, models.APIResponse{
			Code: 200,
			Data: logs,
		})
	}
}

func handleGetTrace(e *engine.CoreEngine) gin.HandlerFunc {
	return func(c *gin.Context) {
		id := c.Param("id")
		gw := e.GetAPIGateway()
		spans := gw.GetTraceSpans(id)
		c.JSON(http.StatusOK, models.APIResponse{
			Code: 200,
			Data: spans,
		})
	}
}

func handleHealth() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"status": "healthy",
			"time":   time.Now().Format(time.RFC3339),
		})
	}
}
