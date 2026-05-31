package api

import (
	"net/http"
	"time"

	"github.com/gin-contrib/cors"
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/internal/core"
	"github.com/solocoder/task-scheduler/internal/database"
	"github.com/solocoder/task-scheduler/internal/logging"
	"github.com/solocoder/task-scheduler/internal/scheduler"
)

func SetupRouter(db *database.Database, sched *scheduler.Scheduler, executor *core.TaskExecutor) *gin.Engine {
	r := gin.New()

	r.Use(gin.Recovery())
	r.Use(RequestIDMiddleware())
	r.Use(LoggingMiddleware())
	r.Use(CORSMiddleware())

	handler := NewAPIHandler(db, sched, executor)

	apiV1 := r.Group("/api/v1")
	{
		resources := apiV1.Group("/resources")
		{
			resources.POST("", handler.CreateResource)
			resources.GET("", handler.ListResources)
			resources.GET("/:id/status", handler.GetResourceStatus)
		}

		apiV1.POST("/resources/batch", handler.BatchOperation)

		apiV1.POST("/admin/log-level", handler.UpdateLogLevel)
	}

	r.GET("/health", handler.HealthCheck)
	r.GET("/ready", handler.HealthCheck)

	return r
}

func RequestIDMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		requestID := c.GetHeader("X-Request-ID")
		if requestID == "" {
			requestID = "req_" + time.Now().Format("20060102150405")
		}
		c.Set("requestID", requestID)
		c.Header("X-Request-ID", requestID)
		c.Next()
	}
}

func LoggingMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path
		query := c.Request.URL.RawQuery

		c.Next()

		duration := time.Since(start)
		statusCode := c.Writer.Status()
		clientIP := c.ClientIP()
		method := c.Request.Method
		requestID := c.GetString("requestID")

		fields := []zap.Field{
			zap.Int("status", statusCode),
			zap.String("method", method),
			zap.String("path", path),
			zap.String("query", query),
			zap.String("ip", clientIP),
			zap.String("request_id", requestID),
			zap.Duration("duration", duration),
		}

		if len(c.Errors) > 0 {
			for _, e := range c.Errors.Errors() {
				logging.Error(c.Request.Context(), e, fields...)
			}
		} else if statusCode >= http.StatusInternalServerError {
			logging.Error(c.Request.Context(), "Request failed", fields...)
		} else if statusCode >= http.StatusBadRequest {
			logging.Warn(c.Request.Context(), "Request warning", fields...)
		} else {
			logging.Info(c.Request.Context(), "Request completed", fields...)
		}
	}
}

func CORSMiddleware() gin.HandlerFunc {
	return cors.New(cors.Config{
		AllowOrigins:     []string{"*"},
		AllowMethods:     []string{"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"},
		AllowHeaders:     []string{"Origin", "Content-Type", "Accept", "Authorization", "X-Request-ID", "X-Trace-ID"},
		ExposeHeaders:    []string{"Content-Length", "X-Request-ID"},
		AllowCredentials: true,
		MaxAge:           12 * time.Hour,
	})
}
