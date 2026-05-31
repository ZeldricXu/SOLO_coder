package middleware

import (
	"bytes"
	"io"
	"time"

	"depguard/internal/common/response"
	"depguard/internal/logger"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

func CORSMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}

		c.Next()
	}
}

func LoggerMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path
		method := c.Request.Method

		var bodyBytes []byte
		if c.Request.Body != nil {
			bodyBytes, _ = io.ReadAll(c.Request.Body)
			c.Request.Body = io.NopCloser(bytes.NewBuffer(bodyBytes))
		}

		c.Next()

		latency := time.Since(start)
		statusCode := c.Writer.Status()
		clientIP := c.ClientIP()

		logger.Log.Info("HTTP Request",
			zap.String("method", method),
			zap.String("path", path),
			zap.Int("status", statusCode),
			zap.String("client_ip", clientIP),
			zap.Duration("latency", latency),
			zap.Int("body_size", len(bodyBytes)),
		)
	}
}

func RecoveryMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if err := recover(); err != nil {
				logger.Log.Error("Panic recovered",
					zap.Any("error", err),
					zap.String("path", c.Request.URL.Path),
				)
				response.InternalServerError(c, "Internal server error")
				c.Abort()
			}
		}()
		c.Next()
	}
}

func ConcurrencyLimit(maxConcurrent int) gin.HandlerFunc {
	sem := make(chan struct{}, maxConcurrent)
	return func(c *gin.Context) {
		select {
		case sem <- struct{}{}:
			defer func() { <-sem }()
			c.Next()
		default:
			response.Error(c, 503, "Service busy, please try again later")
			c.Abort()
		}
	}
}
