package middleware

import (
	"depguard/logger"
	"depguard/models"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"net/http"
	"time"
)

func RequestLogger() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path
		query := c.Request.URL.RawQuery
		traceID := uuid.New().String()
		c.Set("trace_id", traceID)
		c.Header("X-Trace-ID", traceID)

		c.Next()

		end := time.Now()
		latency := end.Sub(start)

		logger.Get().Info("request",
			zap.String("trace_id", traceID),
			zap.String("path", path),
			zap.String("query", query),
			zap.String("method", c.Request.Method),
			zap.Int("status", c.Writer.Status()),
			zap.Duration("latency", latency),
			zap.String("client_ip", c.ClientIP()),
		)
	}
}

func Recovery() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if err := recover(); err != nil {
				traceID, _ := c.Get("trace_id")
				logger.Get().Error("panic recovered",
					zap.Any("error", err),
					zap.String("trace_id", traceID.(string)),
				)
				c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, "Internal Server Error"))
				c.Abort()
			}
		}()
		c.Next()
	}
}

func CORS() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
		c.Header("Access-Control-Allow-Headers", "Origin, Content-Type, Content-Length, Accept-Encoding, X-CSRF-Token, Authorization")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}

		c.Next()
	}
}
