package middleware

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gasestimator/platform/internal/infrastructure/logger"
	"github.com/gasestimator/platform/pkg/common"
	"github.com/google/uuid"
	"go.uber.org/zap"
)

type contextKey string

const (
	TraceIDKey    contextKey = "trace_id"
	RequestIDKey  contextKey = "request_id"
)

func RequestID() gin.HandlerFunc {
	return func(c *gin.Context) {
		requestID := c.GetHeader("X-Request-ID")
		if requestID == "" {
			requestID = uuid.New().String()
		}
		c.Set("request_id", requestID)
		c.Header("X-Request-ID", requestID)
		c.Next()
	}
}

func TraceID() gin.HandlerFunc {
	return func(c *gin.Context) {
		traceID := c.GetHeader("X-Trace-ID")
		if traceID == "" {
			traceID = uuid.New().String()
		}
		c.Set("trace_id", traceID)
		c.Request = c.Request.WithContext(context.WithValue(c.Request.Context(), TraceIDKey, traceID))
		c.Next()
	}
}

func Logger() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path
		query := c.Request.URL.RawQuery

		var bodyBytes []byte
		if c.Request.Body != nil {
			bodyBytes, _ = io.ReadAll(c.Request.Body)
			c.Request.Body = io.NopCloser(bytes.NewBuffer(bodyBytes))
		}

		c.Next()

		cost := time.Since(start)
		status := c.Writer.Status()
		method := c.Request.Method
		clientIP := c.ClientIP()
		userAgent := c.Request.UserAgent()

		fields := []zap.Field{
			zap.Int("status", status),
			zap.String("method", method),
			zap.String("path", path),
			zap.String("query", query),
			zap.String("ip", clientIP),
			zap.String("user_agent", userAgent),
			zap.Duration("cost", cost),
		}

		if reqID, ok := c.Get("request_id"); ok {
			fields = append(fields, zap.String("request_id", reqID.(string)))
		}
		if traceID, ok := c.Get("trace_id"); ok {
			fields = append(fields, zap.String("trace_id", traceID.(string)))
		}

		if status >= 500 {
			logger.L().Error("http request error", fields...)
		} else if status >= 400 {
			logger.L().Warn("http request warning", fields...)
		} else {
			logger.L().Info("http request", fields...)
		}
	}
}

func CORS() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Request-ID, X-Trace-ID")
		c.Writer.Header().Set("Access-Control-Max-Age", "86400")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	}
}

func Recovery() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if err := recover(); err != nil {
				logger.L().Error("panic recovered",
					zap.Any("error", err),
					zap.String("path", c.Request.URL.Path),
				)
				c.JSON(http.StatusInternalServerError, gin.H{
					"code":    500,
					"message": "Internal Server Error",
				})
				c.Abort()
			}
		}()
		c.Next()
	}
}

func RateLimit(maxConcurrent int) gin.HandlerFunc {
	sem := make(chan struct{}, maxConcurrent)
	return func(c *gin.Context) {
		select {
		case sem <- struct{}{}:
			defer func() { <-sem }()
			c.Next()
		default:
			c.JSON(http.StatusTooManyRequests, gin.H{
				"code":    429,
				"message": "Too Many Requests",
			})
			c.Abort()
		}
	}
}

func Timeout(timeout time.Duration) gin.HandlerFunc {
	return func(c *gin.Context) {
		ctx, cancel := context.WithTimeout(c.Request.Context(), timeout)
		defer cancel()
		c.Request = c.Request.WithContext(ctx)
		c.Next()
		if ctx.Err() == context.DeadlineExceeded {
			c.AbortWithStatusJSON(http.StatusGatewayTimeout, gin.H{
				"code":    504,
				"message": "Request Timeout",
			})
		}
	}
}

func ErrorHandler() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Next()

		if len(c.Errors) > 0 {
			err := c.Errors.Last()
			if appErr, ok := err.Err.(*common.AppError); ok {
				status := http.StatusInternalServerError
				switch appErr.Code {
				case common.ErrCodeNotFound:
					status = http.StatusNotFound
				case common.ErrCodeInvalidInput:
					status = http.StatusBadRequest
				case common.ErrCodeConflict:
					status = http.StatusConflict
				case common.ErrCodeUnauthorized:
					status = http.StatusUnauthorized
				case common.ErrCodeTimeout:
					status = http.StatusGatewayTimeout
				}
				c.JSON(status, gin.H{
					"code":       status,
					"error_code": appErr.Code,
					"message":    appErr.Message,
					"resource_id": appErr.ResourceID,
				})
			} else {
				c.JSON(http.StatusInternalServerError, gin.H{
					"code":    500,
					"message": err.Error(),
				})
			}
		}
	}
}
