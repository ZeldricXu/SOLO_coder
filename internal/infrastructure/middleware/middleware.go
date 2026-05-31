package middleware

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"

	"llmgateway/internal/infrastructure/logger"
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

func RequestLogger() gin.HandlerFunc {
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

		latency := time.Since(start)
		status := c.Writer.Status()
		clientIP := c.ClientIP()
		userAgent := c.Request.UserAgent()
		method := c.Request.Method
		requestID, _ := c.Get("request_id")

		fields := []zap.Field{
			zap.String("request_id", requestID.(string)),
			zap.String("method", method),
			zap.String("path", path),
			zap.String("query", query),
			zap.Int("status", status),
			zap.Duration("latency", latency),
			zap.String("client_ip", clientIP),
			zap.String("user_agent", userAgent),
		}

		if len(bodyBytes) > 0 && len(bodyBytes) < 1024 {
			fields = append(fields, zap.String("request_body", string(bodyBytes)))
		}

		if status >= 500 {
			logger.L().Error("request failed", fields...)
		} else if status >= 400 {
			logger.L().Warn("request completed with error", fields...)
		} else {
			logger.L().Info("request completed", fields...)
		}
	}
}

func SignatureValidation(secret string) gin.HandlerFunc {
	return func(c *gin.Context) {
		signature := c.GetHeader("X-Signature")
		timestampStr := c.GetHeader("X-Timestamp")

		if signature == "" || timestampStr == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{
				"code":  401,
				"error": "missing signature or timestamp",
			})
			return
		}

		timestamp, err := strconv.ParseInt(timestampStr, 10, 64)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{
				"code":  401,
				"error": "invalid timestamp format",
			})
			return
		}

		if time.Now().Unix()-timestamp > 300 {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{
				"code":  401,
				"error": "request expired",
			})
			return
		}

		var bodyBytes []byte
		if c.Request.Body != nil {
			bodyBytes, _ = io.ReadAll(c.Request.Body)
			c.Request.Body = io.NopCloser(bytes.NewBuffer(bodyBytes))
		}

		message := timestampStr + string(bodyBytes)
		expectedSignature := generateHMAC(message, secret)

		if !hmac.Equal([]byte(signature), []byte(expectedSignature)) {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{
				"code":  401,
				"error": "invalid signature",
			})
			return
		}

		c.Next()
	}
}

func generateHMAC(message, secret string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(message))
	return hex.EncodeToString(mac.Sum(nil))
}

func CORSMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Request-ID, X-Signature, X-Timestamp")
		c.Writer.Header().Set("Access-Control-Max-Age", "86400")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}

		c.Next()
	}
}

func RateLimiter(maxRequests int, window time.Duration) gin.HandlerFunc {
	requests := make(map[string][]time.Time)

	return func(c *gin.Context) {
		clientIP := c.ClientIP()
		now := time.Now()

		windowStart := now.Add(-window)
		var validRequests []time.Time
		for _, t := range requests[clientIP] {
			if t.After(windowStart) {
				validRequests = append(validRequests, t)
			}
		}

		if len(validRequests) >= maxRequests {
			c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
				"code":  429,
				"error": "rate limit exceeded",
			})
			return
		}

		validRequests = append(validRequests, now)
		requests[clientIP] = validRequests

		c.Next()
	}
}

func Recovery() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if err := recover(); err != nil {
				requestID, _ := c.Get("request_id")
				logger.Error("panic recovered",
					zap.Any("error", err),
					zap.String("request_id", requestID.(string)),
					zap.Stack("stack"),
				)
				c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{
					"code":  500,
					"error": "internal server error",
				})
			}
		}()
		c.Next()
	}
}
