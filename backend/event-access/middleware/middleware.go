package middleware

import (
	"context"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"
)

type ContextKey string

const (
	RequestIDKey ContextKey = "request_id"
)

func RequestID() gin.HandlerFunc {
	return func(c *gin.Context) {
		requestID := c.GetHeader("X-Request-ID")
		if requestID == "" {
			requestID = uuid.New().String()
		}

		c.Set(string(RequestIDKey), requestID)
		c.Header("X-Request-ID", requestID)

		ctx := context.WithValue(c.Request.Context(), RequestIDKey, requestID)
		c.Request = c.Request.WithContext(ctx)

		c.Next()
	}
}

func Logger(logger *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path
		query := c.Request.URL.RawQuery

		c.Next()

		end := time.Now()
		latency := end.Sub(start)

		requestID, _ := c.Get(string(RequestIDKey))

		fields := []zap.Field{
			zap.Int("status", c.Writer.Status()),
			zap.String("method", c.Request.Method),
			zap.String("path", path),
			zap.String("query", query),
			zap.String("ip", c.ClientIP()),
			zap.String("user_agent", c.Request.UserAgent()),
			zap.Duration("latency", latency),
		}

		if requestID != nil {
			fields = append(fields, zap.String("request_id", requestID.(string)))
		}

		if len(c.Errors) > 0 {
			for _, e := range c.Errors {
				logger.Error("Request error", append(fields, zap.Error(e.Err))...)
			}
			return
		}

		if c.Writer.Status() >= 400 {
			logger.Warn("Request completed with error", fields...)
		} else {
			logger.Info("Request completed", fields...)
		}
	}
}

func Recovery() gin.HandlerFunc {
	return gin.CustomRecovery(func(c *gin.Context, recovered interface{}) {
		logger := zap.L()
		logger.Error("Panic recovered", 
			zap.Any("error", recovered),
			zap.String("path", c.Request.URL.Path),
			zap.String("method", c.Request.Method),
		)

		c.JSON(500, gin.H{
			"code":    500,
			"message": "Internal server error",
		})
		c.Abort()
	})
}

func CORS() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization, X-Request-ID")
		c.Header("Access-Control-Expose-Headers", "X-Request-ID")
		c.Header("Access-Control-Allow-Credentials", "true")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}

		c.Next()
	}
}

func RateLimit(maxRequests int, window time.Duration) gin.HandlerFunc {
	type clientData struct {
		requests  int
		windowStart time.Time
	}

	clients := make(map[string]*clientData)

	return func(c *gin.Context) {
		clientIP := c.ClientIP()

		data, exists := clients[clientIP]
		if !exists || time.Since(data.windowStart) > window {
			clients[clientIP] = &clientData{
				requests:    1,
				windowStart: time.Now(),
			}
			c.Next()
			return
		}

		if data.requests >= maxRequests {
			c.JSON(429, gin.H{
				"code":    429,
				"message": "Too many requests",
			})
			c.Abort()
			return
		}

		data.requests++
		c.Next()
	}
}
