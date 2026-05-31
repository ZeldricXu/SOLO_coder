package middleware

import (
	"net/http"
	"time"

	"github.com/enterprise/config-platform/internal/gateway"
	"github.com/enterprise/config-platform/internal/monitoring"
	"github.com/gin-gonic/gin"
)

func AuthMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		gw := gateway.GetManager()

		authenticated, apiKey, err := gw.AuthenticateRequest(c.Request)
		if err != nil {
			c.JSON(http.StatusUnauthorized, gin.H{
				"code":    401,
				"message": "Authentication failed: " + err.Error(),
			})
			c.Abort()
			return
		}

		if authenticated {
			c.Set("api_key", apiKey)
			c.Set("user_id", apiKey.UserID)
		}

		c.Next()
	}
}

func RateLimitMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		gw := gateway.GetManager()
		clientIP := gateway.GetClientIP(c.Request)

		defaultConfig := gateway.RateLimitConfig{
			RequestsPerSecond: 100,
			BurstSize:         200,
		}

		if !gw.CheckRateLimit(clientIP, defaultConfig) {
			monitoring.GetManager().RecordRateLimit(clientIP)
			c.JSON(http.StatusTooManyRequests, gin.H{
				"code":    429,
				"message": "Rate limit exceeded",
			})
			c.Abort()
			return
		}

		c.Next()
	}
}

func MetricsMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		startTime := time.Now()
		path := c.Request.URL.Path
		method := c.Request.Method

		c.Next()

		duration := time.Since(startTime)
		statusCode := c.Writer.Status()

		monitoring.GetManager().RecordHTTPRequest(method, path, statusCode, duration)
	}
}

func CORSMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}

		c.Next()
	}
}

func TraceIDMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		traceID := c.GetHeader("X-Trace-ID")
		if traceID == "" {
			traceID = "trace_" + time.Now().Format("20060102150405")
		}
		c.Set("trace_id", traceID)
		c.Writer.Header().Set("X-Trace-ID", traceID)
		c.Next()
	}
}

func RequiredRole(role gateway.UserRole) gin.HandlerFunc {
	return func(c *gin.Context) {
		apiKey, exists := c.Get("api_key")
		if !exists {
			c.JSON(http.StatusUnauthorized, gin.H{
				"code":    401,
				"message": "Authentication required",
			})
			c.Abort()
			return
		}

		gw := gateway.GetManager()
		if !gw.CheckRole(apiKey.(*gateway.APIKey), role) {
			c.JSON(http.StatusForbidden, gin.H{
				"code":    403,
				"message": "Insufficient permissions",
			})
			c.Abort()
			return
		}

		c.Next()
	}
}
