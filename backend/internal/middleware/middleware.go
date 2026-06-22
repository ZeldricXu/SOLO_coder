package middleware

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/featureflag/platform/internal/dao"
	"github.com/featureflag/platform/internal/model"
	"github.com/featureflag/platform/pkg/logger"
	"github.com/featureflag/platform/pkg/utils"
)

func CORS() gin.HandlerFunc {
	return func(c *gin.Context) {
		origin := c.Request.Header.Get("Origin")
		if origin == "" {
			origin = "*"
		}

		c.Writer.Header().Set("Access-Control-Allow-Origin", origin)
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, X-User-ID, X-User-Name")
		c.Writer.Header().Set("Access-Control-Allow-Credentials", "true")
		c.Writer.Header().Set("Access-Control-Max-Age", "86400")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}

		c.Next()
	}
}

func RequestID() gin.HandlerFunc {
	return func(c *gin.Context) {
		requestID := c.Request.Header.Get("X-Request-ID")
		if requestID == "" {
			requestID = utils.GenerateUUID()
		}
		c.Set("request_id", requestID)
		c.Writer.Header().Set("X-Request-ID", requestID)
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

		latency := time.Since(start)
		status := c.Writer.Status()
		clientIP := c.ClientIP()
		userAgent := c.Request.UserAgent()
		method := c.Request.Method

		requestID, _ := c.Get("request_id")
		userID, _ := c.Get("user_id")
		userName, _ := c.Get("user_name")

		fields := map[string]interface{}{
			"status":     status,
			"method":     method,
			"path":       path,
			"query":      query,
			"latency":    latency.String(),
			"client_ip":  clientIP,
			"user_agent": userAgent,
			"request_id": requestID,
		}

		if userID != nil {
			fields["user_id"] = userID
		}
		if userName != nil {
			fields["user_name"] = userName
		}

		if len(bodyBytes) > 0 && len(bodyBytes) < 2048 && !strings.Contains(path, "/login") {
			fields["body"] = string(bodyBytes)
		}

		logEntry := logger.WithFields(fields)
		if status >= 500 {
			logEntry.Error("request failed")
		} else if status >= 400 {
			logEntry.Warn("request warning")
		} else {
			logEntry.Info("request completed")
		}
	}
}

func Auth() gin.HandlerFunc {
	return func(c *gin.Context) {
		userID := c.Request.Header.Get("X-User-ID")
		userName := c.Request.Header.Get("X-User-Name")

		if userID == "" || userName == "" {
			userID = "anonymous"
			userName = "anonymous"
		}

		c.Set("user_id", userID)
		c.Set("user_name", userName)
		c.Next()
	}
}

func CurrentUser(c *gin.Context) (string, string) {
	userID, _ := c.Get("user_id")
	userName, _ := c.Get("user_name")
	return userID.(string), userName.(string)
}

func Recovery() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if err := recover(); err != nil {
				logger.Errorf("panic recovered: %v", err)
				c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, "Internal Server Error"))
				c.Abort()
			}
		}()
		c.Next()
	}
}

func RateLimit(limit int, duration time.Duration) gin.HandlerFunc {
	type client struct {
		count     int
		lastReset time.Time
	}

	var (
		clients = make(map[string]*client)
	)

	return func(c *gin.Context) {
		ip := c.ClientIP()

		cl, ok := clients[ip]
		if !ok || time.Since(cl.lastReset) > duration {
			cl = &client{
				count:     0,
				lastReset: time.Now(),
			}
			clients[ip] = cl
		}

		cl.count++

		if cl.count > limit {
			c.JSON(http.StatusTooManyRequests, model.NewErrorResponse(429, "Too Many Requests"))
			c.Abort()
			return
		}

		c.Next()
	}
}

func AuditLog() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Next()

		if c.Request.Method == "GET" || c.Request.Method == "OPTIONS" {
			return
		}

		userID, _ := c.Get("user_id")
		userIDStr, _ := userID.(string)
		if userIDStr == "" || userIDStr == "anonymous" {
			return
		}

		action := getAction(c.Request.Method, c.Request.URL.Path)
		resourceType := getResourceType(c.Request.URL.Path)
		resourceID := c.Param("id")

		auditLog := &model.AuditLog{
			ID:           utils.GenerateUUID(),
			UserID:       userIDStr,
			Action:       action,
			ResourceType: resourceType,
			ResourceID:   resourceID,
			IPAddress:    c.ClientIP(),
			UserAgent:    c.Request.UserAgent(),
			CreatedAt:    time.Now(),
		}

		go func() {
			auditDAO := dao.NewAuditLogDAO()
			_ = auditDAO.Create(context.Background(), auditLog)
		}()
	}
}

func getAction(method, path string) string {
	methodMap := map[string]string{
		"POST":   "create",
		"PUT":    "update",
		"DELETE": "delete",
		"PATCH":  "update",
	}

	action := methodMap[method]
	if strings.Contains(path, "enable") {
		action = "enable"
	} else if strings.Contains(path, "disable") {
		action = "disable"
	} else if strings.Contains(path, "approve") {
		action = "approve"
	} else if strings.Contains(path, "reject") {
		action = "reject"
	} else if strings.Contains(path, "batch") {
		action = "batch_" + action
	}

	return action
}

func getResourceType(path string) string {
	if strings.Contains(path, "/api/switches") {
		return "switch"
	} else if strings.Contains(path, "/api/approvals") {
		return "approval"
	} else if strings.Contains(path, "/api/strategies") {
		return "strategy"
	} else if strings.Contains(path, "/api/services") {
		return "service"
	}
	return "unknown"
}
