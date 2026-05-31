package middleware

import (
	"context"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"session316/internal/logger"
	"session316/pkg/errors"
)

type RateLimiter struct {
	maxConcurrent int
	queueSize     int
	semaphore     chan struct{}
	queue         chan struct{}
	mu            sync.Mutex
	metrics       *RateLimitMetrics
}

type RateLimitMetrics struct {
	TotalRequests   int64
	AcceptedRequests int64
	RejectedRequests int64
	QueueWaitTime    time.Duration
}

func NewRateLimiter(maxConcurrent, queueSize int) *RateLimiter {
	return &RateLimiter{
		maxConcurrent: maxConcurrent,
		queueSize:     queueSize,
		semaphore:     make(chan struct{}, maxConcurrent),
		queue:         make(chan struct{}, queueSize),
		metrics:       &RateLimitMetrics{},
	}
}

func (rl *RateLimiter) Middleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		startTime := time.Now()
		rl.mu.Lock()
		rl.metrics.TotalRequests++
		rl.mu.Unlock()

		select {
		case rl.queue <- struct{}{}:
			defer func() { <-rl.queue }()
		default:
			rl.mu.Lock()
			rl.metrics.RejectedRequests++
			rl.mu.Unlock()
			logger.Warn("Rate limit queue full, rejecting request",
				zap.String("path", c.Request.URL.Path),
				zap.Int("max_concurrent", rl.maxConcurrent),
				zap.Int("queue_size", rl.queueSize),
			)
			appErr := errors.RateLimitError()
			c.JSON(appErr.HTTPStatus(), appErr)
			c.Abort()
			return
		}

		select {
		case rl.semaphore <- struct{}{}:
			rl.mu.Lock()
			rl.metrics.AcceptedRequests++
			rl.metrics.QueueWaitTime += time.Since(startTime)
			rl.mu.Unlock()
			defer func() { <-rl.semaphore }()
		case <-c.Request.Context().Done():
			return
		}

		c.Next()
	}
}

func (rl *RateLimiter) GetMetrics() RateLimitMetrics {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	return *rl.metrics
}

func (rl *RateLimiter) ResetMetrics() {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	rl.metrics = &RateLimitMetrics{}
}

type ContextKey string

const (
	TraceIDKey    ContextKey = "trace_id"
	UserIDKey     ContextKey = "user_id"
	RequestStartKey ContextKey = "request_start"
)

func ContextWithTraceID(ctx context.Context, traceID string) context.Context {
	return context.WithValue(ctx, TraceIDKey, traceID)
}

func GetTraceID(ctx context.Context) string {
	if traceID, ok := ctx.Value(TraceIDKey).(string); ok {
		return traceID
	}
	return ""
}

func RequestLogger() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path
		method := c.Request.Method

		traceID := c.GetHeader("X-Trace-ID")
		if traceID == "" {
			traceID = c.GetString("trace_id")
		}
		c.Set("trace_id", traceID)
		c.Header("X-Trace-ID", traceID)

		c.Next()

		latency := time.Since(start)
		statusCode := c.Writer.Status()
		clientIP := c.ClientIP()

		fields := []zap.Field{
			zap.String("trace_id", traceID),
			zap.String("method", method),
			zap.String("path", path),
			zap.Int("status", statusCode),
			zap.Duration("latency", latency),
			zap.String("client_ip", clientIP),
			zap.String("user_agent", c.Request.UserAgent()),
		}

		if len(c.Errors) > 0 {
			logger.Error("Request failed", append(fields, zap.String("error", c.Errors.String()))...)
		} else if statusCode >= 500 {
			logger.Error("Server error", fields...)
		} else if statusCode >= 400 {
			logger.Warn("Client error", fields...)
		} else {
			logger.Info("Request completed", fields...)
		}
	}
}

func CORSMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Trace-ID")
		c.Writer.Header().Set("Access-Control-Expose-Headers", "X-Trace-ID")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}

		c.Next()
	}
}

func TimeoutMiddleware(timeout time.Duration) gin.HandlerFunc {
	return func(c *gin.Context) {
		ctx, cancel := context.WithTimeout(c.Request.Context(), timeout)
		defer cancel()

		c.Request = c.Request.WithContext(ctx)
		done := make(chan struct{})

		go func() {
			c.Next()
			close(done)
		}()

		select {
		case <-done:
		case <-ctx.Done():
			logger.Warn("Request timeout",
				zap.String("path", c.Request.URL.Path),
				zap.Duration("timeout", timeout),
			)
			appErr := errors.TimeoutError(c.Request.URL.Path)
			c.JSON(appErr.HTTPStatus(), appErr)
			c.Abort()
		}
	}
}

func TraceIDMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		traceID := c.GetHeader("X-Trace-ID")
		if traceID == "" {
			traceID = c.GetString("trace_id")
		}
		c.Set("trace_id", traceID)
		c.Header("X-Trace-ID", traceID)
		c.Next()
	}
}
