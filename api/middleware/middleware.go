package middleware

import (
	"net/http"
	"strconv"
	"time"

	"github.com/edgevision/edgevision/internal/infrastructure/logger"
	"github.com/edgevision/edgevision/pkg/errors"
	"github.com/edgevision/edgevision/pkg/utils"
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

func TraceID() gin.HandlerFunc {
	return func(c *gin.Context) {
		traceID := c.GetHeader("X-Trace-ID")
		if traceID == "" {
			traceID = utils.GenerateTraceID()
		}
		c.Set("trace_id", traceID)
		c.Header("X-Trace-ID", traceID)
		c.Next()
	}
}

func Logger() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path
		query := c.Request.URL.RawQuery

		c.Next()

		cost := time.Since(start)
		statusCode := c.Writer.Status()
		clientIP := utils.GetClientIP(c.Request)
		traceID, _ := c.Get("trace_id")

		logger.Get().Info("HTTP Request",
			zap.String("trace_id", traceID.(string)),
			zap.String("method", c.Request.Method),
			zap.String("path", path),
			zap.String("query", query),
			zap.Int("status", statusCode),
			zap.String("ip", clientIP),
			zap.Duration("cost", cost),
		)
	}
}

func Recovery() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if r := recover(); r != nil {
				traceID, _ := c.Get("trace_id")
				logger.Get().Error("Panic recovered",
					zap.String("trace_id", traceID.(string)),
					zap.Any("panic", r),
					zap.Stack("stack"),
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

func CORS() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Trace-ID")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}

		c.Next()
	}
}

func Auth() gin.HandlerFunc {
	return func(c *gin.Context) {
		token := c.GetHeader("Authorization")
		if token == "" {
			c.JSON(http.StatusUnauthorized, errors.Unauthorized("Authorization header is required"))
			c.Abort()
			return
		}

		c.Set("auth_token", token)
		c.Next()
	}
}

func ValidateSignature() gin.HandlerFunc {
	return func(c *gin.Context) {
		signature := c.GetHeader("X-Signature")
		timestamp := c.GetHeader("X-Timestamp")

		if signature == "" || timestamp == "" {
			c.JSON(http.StatusBadRequest, errors.BadRequest("Signature and timestamp are required"))
			c.Abort()
			return
		}

		ts, err := strconv.ParseInt(timestamp, 10, 64)
		if err != nil {
			c.JSON(http.StatusBadRequest, errors.BadRequest("Invalid timestamp"))
			c.Abort()
			return
		}

		now := time.Now().Unix()
		if abs(now-ts) > 300 {
			c.JSON(http.StatusBadRequest, errors.BadRequest("Timestamp expired"))
			c.Abort()
			return
		}

		c.Next()
	}
}

func abs(x int64) int64 {
	if x < 0 {
		return -x
	}
	return x
}

func RateLimit(maxRequests int, duration time.Duration) gin.HandlerFunc {
	requests := make(map[string][]time.Time)

	return func(c *gin.Context) {
		clientIP := utils.GetClientIP(c.Request)
		now := time.Now()

		if times, ok := requests[clientIP]; ok {
			valid := make([]time.Time, 0)
			for _, t := range times {
				if now.Sub(t) < duration {
					valid = append(valid, t)
				}
			}
			requests[clientIP] = valid

			if len(valid) >= maxRequests {
				c.JSON(http.StatusTooManyRequests, errors.ServiceUnavailable("Rate limit exceeded"))
				c.Abort()
				return
			}
		}

		requests[clientIP] = append(requests[clientIP], now)
		c.Next()
	}
}
