package gateway

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/solocoder/logrotate/internal/domain"
)

type TraceContext struct {
	TraceID    string
	RequestID  string
	SpanID     string
	ParentID   string
	StartTime  time.Time
	Attributes map[string]interface{}
}

type traceKey struct{}

func GetTraceContext(ctx context.Context) (*TraceContext, bool) {
	tc, ok := ctx.Value(traceKey{}).(*TraceContext)
	return tc, ok
}

func (tc *TraceContext) WithContext(ctx context.Context) context.Context {
	return context.WithValue(ctx, traceKey{}, tc)
}

func NewTraceContext() *TraceContext {
	return &TraceContext{
		TraceID:    uuid.New().String(),
		RequestID:  uuid.New().String(),
		SpanID:     uuid.New().String(),
		StartTime:  time.Now(),
		Attributes: make(map[string]interface{}),
	}
}

type GatewayConfig struct {
	EnableRequestLog  bool
	EnableTrace       bool
	EnableMetrics     bool
	MaxBodySize       int64
	SlowRequestThreshold time.Duration
}

type Gateway struct {
	config   GatewayConfig
	requestLog chan domain.RequestLog
}

func New(cfg GatewayConfig) *Gateway {
	if cfg.MaxBodySize == 0 {
		cfg.MaxBodySize = 10 * 1024 * 1024
	}
	if cfg.SlowRequestThreshold == 0 {
		cfg.SlowRequestThreshold = 1 * time.Second
	}

	return &Gateway{
		config:     cfg,
		requestLog: make(chan domain.RequestLog, 10000),
	}
}

func (g *Gateway) TraceMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		traceID := c.GetHeader("X-Trace-ID")
		if traceID == "" {
			traceID = uuid.New().String()
		}

		spanID := c.GetHeader("X-Span-ID")
		if spanID == "" {
			spanID = uuid.New().String()
		}

		parentID := c.GetHeader("X-Parent-ID")
		requestID := c.GetHeader("X-Request-ID")
		if requestID == "" {
			requestID = uuid.New().String()
		}

		tc := &TraceContext{
			TraceID:    traceID,
			RequestID:  requestID,
			SpanID:     spanID,
			ParentID:   parentID,
			StartTime:  time.Now(),
			Attributes: make(map[string]interface{}),
		}

		c.Set("trace", tc)
		c.Set("traceID", traceID)
		c.Set("requestID", requestID)

		c.Request = c.Request.WithContext(tc.WithContext(c.Request.Context()))

		c.Header("X-Trace-ID", traceID)
		c.Header("X-Request-ID", requestID)

		c.Next()
	}
}

func (g *Gateway) RequestLogMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		startTime := time.Now()

		var requestBody []byte
		if g.config.EnableRequestLog && c.Request.Body != nil {
			requestBody, _ = io.ReadAll(c.Request.Body)
			c.Request.Body = io.NopCloser(bytes.NewBuffer(requestBody))
		}

		blw := &bodyLogWriter{body: bytes.NewBufferString(""), ResponseWriter: c.Writer}
		c.Writer = blw

		c.Next()

		duration := time.Since(startTime)

		traceID, _ := c.Get("traceID")
		requestID, _ := c.Get("requestID")

		headers := make(map[string]string)
		for k, v := range c.Request.Header {
			if len(v) > 0 {
				headers[k] = v[0]
			}
		}

		logEntry := domain.RequestLog{
			TraceID:    fmt.Sprintf("%v", traceID),
			RequestID:  fmt.Sprintf("%v", requestID),
			Method:     c.Request.Method,
			Path:       c.Request.URL.Path,
			StatusCode: c.Writer.Status(),
			DurationMs: duration.Milliseconds(),
			ClientIP:   c.ClientIP(),
			UserAgent:  c.Request.UserAgent(),
			Timestamp:  startTime,
			Headers:    headers,
		}

		if g.config.EnableRequestLog {
			select {
			case g.requestLog <- logEntry:
			default:
			}
		}

		if duration > g.config.SlowRequestThreshold {
			// Slow request logging
		}
	}
}

type bodyLogWriter struct {
	gin.ResponseWriter
	body *bytes.Buffer
}

func (w *bodyLogWriter) Write(b []byte) (int, error) {
	w.body.Write(b)
	return w.ResponseWriter.Write(b)
}

func (g *Gateway) CORSMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Trace-ID, X-Request-ID, X-Span-ID, X-Parent-ID")
		c.Writer.Header().Set("Access-Control-Expose-Headers", "X-Trace-ID, X-Request-ID")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}

		c.Next()
	}
}

func (g *Gateway) RecoveryMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if err := recover(); err != nil {
				traceID, _ := c.Get("traceID")

				c.JSON(http.StatusInternalServerError, gin.H{
					"code":      500,
					"message":   "Internal Server Error",
					"trace_id":  traceID,
					"timestamp": time.Now().Format(time.RFC3339),
				})

				c.Abort()
			}
		}()
		c.Next()
	}
}

func (g *Gateway) AuthMiddleware(validateToken func(token string) (map[string]interface{}, bool)) gin.HandlerFunc {
	return func(c *gin.Context) {
		token := c.GetHeader("Authorization")
		if len(token) > 7 && token[:7] == "Bearer " {
			token = token[7:]
		}

		if token == "" {
			c.JSON(http.StatusUnauthorized, gin.H{
				"code":    401,
				"message": "Authorization header required",
			})
			c.Abort()
			return
		}

		claims, valid := validateToken(token)
		if !valid {
			c.JSON(http.StatusUnauthorized, gin.H{
				"code":    401,
				"message": "Invalid or expired token",
			})
			c.Abort()
			return
		}

		c.Set("user", claims)
		c.Next()
	}
}

func (g *Gateway) RateLimitMiddleware(limit int, window time.Duration, getKey func(c *gin.Context) string) gin.HandlerFunc {
	type rateInfo struct {
		count     int
		resetTime time.Time
	}

	rateMap := make(map[string]*rateInfo)

	return func(c *gin.Context) {
		key := getKey(c)
		now := time.Now()

		info, exists := rateMap[key]
		if !exists || now.After(info.resetTime) {
			rateMap[key] = &rateInfo{
				count:     1,
				resetTime: now.Add(window),
			}
			c.Next()
			return
		}

		if info.count >= limit {
			remaining := info.resetTime.Sub(now)
			c.Header("X-RateLimit-Limit", fmt.Sprintf("%d", limit))
			c.Header("X-RateLimit-Remaining", "0")
			c.Header("X-RateLimit-Reset", fmt.Sprintf("%d", info.resetTime.Unix()))
			c.JSON(http.StatusTooManyRequests, gin.H{
				"code":       429,
				"message":    "Rate limit exceeded",
				"retry_after": remaining.Seconds(),
			})
			c.Abort()
			return
		}

		info.count++
		c.Header("X-RateLimit-Limit", fmt.Sprintf("%d", limit))
		c.Header("X-RateLimit-Remaining", fmt.Sprintf("%d", limit-info.count))
		c.Header("X-RateLimit-Reset", fmt.Sprintf("%d", info.resetTime.Unix()))

		c.Next()
	}
}

func (g *Gateway) GetRequestLogs() <-chan domain.RequestLog {
	return g.requestLog
}

func (g *Gateway) Close() {
	close(g.requestLog)
}

func (g *Gateway) SetupRoutes(r *gin.Engine) {
	r.Use(g.TraceMiddleware())
	r.Use(g.RequestLogMiddleware())
	r.Use(g.CORSMiddleware())
	r.Use(g.RecoveryMiddleware())

	api := r.Group("/api/v1")
	{
		api.GET("/health", g.HealthCheck)
		api.GET("/trace/:traceID", g.GetTraceInfo)
		api.POST("/resources", g.CreateResource)
		api.GET("/resources/:id/status", g.GetResourceStatus)
		api.POST("/resources/batch", g.BatchOperation)
	}
}

func (g *Gateway) HealthCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"status":  "healthy",
		"time":    time.Now().Format(time.RFC3339),
	})
}

func (g *Gateway) GetTraceInfo(c *gin.Context) {
	traceID := c.Param("traceID")
	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"trace_id": traceID,
			"status":   "available",
		},
	})
}

type CreateResourceRequest struct {
	Type   string                 `json:"type" binding:"required"`
	Config map[string]interface{} `json:"config"`
	Labels map[string]string      `json:"labels"`
}

func (g *Gateway) CreateResource(c *gin.Context) {
	var req CreateResourceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": "Invalid request body",
			"error":   err.Error(),
		})
		return
	}

	resourceID := "rsc_" + uuid.New().String()[:8]

	c.JSON(http.StatusCreated, gin.H{
		"code": 201,
		"data": gin.H{
			"id":     resourceID,
			"status": "provisioning",
		},
	})
}

func (g *Gateway) GetResourceStatus(c *gin.Context) {
	id := c.Param("id")

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"id":       id,
			"status":   "completed",
			"progress": 0.8,
		},
	})
}

type BatchOperationRequest struct {
	Operations []struct {
		Action string `json:"action" binding:"required"`
		ID     string `json:"id" binding:"required"`
	} `json:"operations" binding:"required"`
}

func (g *Gateway) BatchOperation(c *gin.Context) {
	var req BatchOperationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": "Invalid request body",
			"error":   err.Error(),
		})
		return
	}

	batchID := "batch_" + uuid.New().String()[:8]
	results := make([]map[string]interface{}, len(req.Operations))

	for i, op := range req.Operations {
		results[i] = map[string]interface{}{
			"id":     op.ID,
			"action": op.Action,
			"status": "success",
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"batch_id": batchID,
			"results":  results,
		},
	})
}

func (g *Gateway) JSON(c *gin.Context, code int, data interface{}) {
	c.JSON(code, gin.H{
		"code": code,
		"data": data,
	})
}

func (g *Gateway) Error(c *gin.Context, code int, message string) {
	traceID, _ := c.Get("traceID")
	c.JSON(code, gin.H{
		"code":     code,
		"message":  message,
		"trace_id": traceID,
	})
}

func (g *Gateway) Success(c *gin.Context, data interface{}) {
	g.JSON(c, 200, data)
}

func (g *Gateway) Created(c *gin.Context, data interface{}) {
	g.JSON(c, 201, data)
}

func (g *Gateway) Accepted(c *gin.Context, data interface{}) {
	g.JSON(c, 202, data)
}

func (g *Gateway) NoContent(c *gin.Context) {
	c.Status(http.StatusNoContent)
}

func (g *Gateway) BadRequest(c *gin.Context, message string) {
	g.Error(c, 400, message)
}

func (g *Gateway) Unauthorized(c *gin.Context, message string) {
	g.Error(c, 401, message)
}

func (g *Gateway) Forbidden(c *gin.Context, message string) {
	g.Error(c, 403, message)
}

func (g *Gateway) NotFound(c *gin.Context, message string) {
	g.Error(c, 404, message)
}

func (g *Gateway) Conflict(c *gin.Context, message string) {
	g.Error(c, 409, message)
}

func (g *Gateway) InternalError(c *gin.Context, message string) {
	g.Error(c, 500, message)
}

func (g *Gateway) NotImplemented(c *gin.Context, message string) {
	g.Error(c, 501, message)
}

func (g *Gateway) ServiceUnavailable(c *gin.Context, message string) {
	g.Error(c, 503, message)
}

func (g *Gateway) WriteJSON(w http.ResponseWriter, code int, v interface{}) error {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	return json.NewEncoder(w).Encode(v)
}
