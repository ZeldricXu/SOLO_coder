package gateway

import (
	"context"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"streamsql/internal/common/logger"
)

type RequestContext struct {
	RequestID    string
	TraceID      string
	UserID       string
	Timestamp    time.Time
	Path         string
	Method       string
	ClientIP     string
	UserAgent    string
	Context      context.Context
}

type ResponseContext struct {
	StatusCode   int
	Duration     time.Duration
	Error        error
}

type Middleware interface {
	Handle(c *gin.Context, reqCtx *RequestContext) error
}

type RateLimitMiddleware struct {
	requests map[string]*time.Time
	mu       sync.Mutex
	limit    int
	window   time.Duration
}

func NewRateLimitMiddleware(limit int, window time.Duration) *RateLimitMiddleware {
	return &RateLimitMiddleware{
		requests: make(map[string]*time.Time),
		limit:    limit,
		window:   window,
	}
}

func (m *RateLimitMiddleware) Handle(c *gin.Context, reqCtx *RequestContext) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	key := reqCtx.ClientIP
	lastReq, exists := m.requests[key]

	if exists && time.Since(*lastReq) < m.window {
		return fmt.Errorf("rate limit exceeded")
	}

	now := time.Now()
	m.requests[key] = &now
	return nil
}

type AuthMiddleware struct {
	validTokens map[string]bool
}

func NewAuthMiddleware() *AuthMiddleware {
	return &AuthMiddleware{
		validTokens: map[string]bool{
			"streamsql-token-123": true,
			"admin-token-456":     true,
		},
	}
}

func (m *AuthMiddleware) Handle(c *gin.Context, reqCtx *RequestContext) error {
	token := c.GetHeader("Authorization")
	if token == "" {
		return fmt.Errorf("authorization header required")
	}

	if len(token) > 7 && token[:7] == "Bearer " {
		token = token[7:]
	}

	if !m.validTokens[token] {
		return fmt.Errorf("invalid authorization token")
	}

	reqCtx.UserID = "user_" + token[:8]
	return nil
}

type LoggingMiddleware struct{}

func NewLoggingMiddleware() *LoggingMiddleware {
	return &LoggingMiddleware{}
}

func (m *LoggingMiddleware) Handle(c *gin.Context, reqCtx *RequestContext) error {
	logger.Sugar().Infof("Request started: %s %s from %s",
		reqCtx.Method, reqCtx.Path, reqCtx.ClientIP)
	return nil
}

type APIGateway struct {
	router        *gin.Engine
	middlewares   []Middleware
	rateLimiter   *RateLimitMiddleware
	auth          *AuthMiddleware
	stats         *GatewayStats
}

type GatewayStats struct {
	TotalRequests   int64
	SuccessRequests int64
	FailedRequests  int64
	TotalLatency    int64
	mu              sync.Mutex
}

func NewAPIGateway() *APIGateway {
	gin.SetMode(gin.ReleaseMode)
	router := gin.New()
	router.Use(gin.Recovery())

	gateway := &APIGateway{
		router:      router,
		rateLimiter: NewRateLimitMiddleware(1000, time.Minute),
		auth:        NewAuthMiddleware(),
		stats:       &GatewayStats{},
	}

	gateway.middlewares = []Middleware{
		NewLoggingMiddleware(),
	}

	gateway.router.Use(gateway.globalMiddleware())

	return gateway
}

func (g *APIGateway) globalMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		reqCtx := &RequestContext{
			RequestID: uuid.New().String(),
			TraceID:   c.GetHeader("X-Trace-ID"),
			Timestamp: time.Now(),
			Path:      c.Request.URL.Path,
			Method:    c.Request.Method,
			ClientIP:  c.ClientIP(),
			UserAgent: c.Request.UserAgent(),
			Context:   c.Request.Context(),
		}

		if reqCtx.TraceID == "" {
			reqCtx.TraceID = reqCtx.RequestID
		}

		c.Set("reqCtx", reqCtx)
		c.Header("X-Request-ID", reqCtx.RequestID)
		c.Header("X-Trace-ID", reqCtx.TraceID)

		for _, mw := range g.middlewares {
			if err := mw.Handle(c, reqCtx); err != nil {
				logger.Sugar().Warnf("Middleware failed: %v", err)
			}
		}

		start := time.Now()
		c.Next()
		duration := time.Since(start)

		g.stats.mu.Lock()
		g.stats.TotalRequests++
		if c.Writer.Status() >= 200 && c.Writer.Status() < 400 {
			g.stats.SuccessRequests++
		} else {
			g.stats.FailedRequests++
		}
		g.stats.TotalLatency += duration.Milliseconds()
		g.stats.mu.Unlock()

		logger.Sugar().Infof("Request completed: %s %s - Status: %d, Duration: %v",
			reqCtx.Method, reqCtx.Path, c.Writer.Status(), duration)
	}
}

func (g *APIGateway) AuthRequired() gin.HandlerFunc {
	return func(c *gin.Context) {
		reqCtx, exists := c.Get("reqCtx")
		if !exists {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "request context not found"})
			c.Abort()
			return
		}

		if err := g.auth.Handle(c, reqCtx.(*RequestContext)); err != nil {
			c.JSON(http.StatusUnauthorized, gin.H{"error": err.Error()})
			c.Abort()
			return
		}

		c.Next()
	}
}

func (g *APIGateway) RateLimit() gin.HandlerFunc {
	return func(c *gin.Context) {
		reqCtx, exists := c.Get("reqCtx")
		if !exists {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "request context not found"})
			c.Abort()
			return
		}

		if err := g.rateLimiter.Handle(c, reqCtx.(*RequestContext)); err != nil {
			c.JSON(http.StatusTooManyRequests, gin.H{"error": err.Error()})
			c.Abort()
			return
		}

		c.Next()
	}
}

func (g *APIGateway) GET(path string, handlers ...gin.HandlerFunc) {
	g.router.GET(path, handlers...)
}

func (g *APIGateway) POST(path string, handlers ...gin.HandlerFunc) {
	g.router.POST(path, handlers...)
}

func (g *APIGateway) PUT(path string, handlers ...gin.HandlerFunc) {
	g.router.PUT(path, handlers...)
}

func (g *APIGateway) DELETE(path string, handlers ...gin.HandlerFunc) {
	g.router.DELETE(path, handlers...)
}

func (g *APIGateway) GETRouter() *gin.Engine {
	return g.router
}

func (g *APIGateway) Start(port int) error {
	logger.Sugar().Infof("API Gateway starting on port %d", port)
	return g.router.Run(fmt.Sprintf(":%d", port))
}

func (g *APIGateway) GetStats() map[string]interface{} {
	g.stats.mu.Lock()
	defer g.stats.mu.Unlock()

	avgLatency := int64(0)
	if g.stats.TotalRequests > 0 {
		avgLatency = g.stats.TotalLatency / g.stats.TotalRequests
	}

	return map[string]interface{}{
		"total_requests":   g.stats.TotalRequests,
		"success_requests": g.stats.SuccessRequests,
		"failed_requests":  g.stats.FailedRequests,
		"avg_latency_ms":   avgLatency,
	}
}

func GetRequestContext(c *gin.Context) *RequestContext {
	reqCtx, exists := c.Get("reqCtx")
	if !exists {
		return &RequestContext{
			RequestID: uuid.New().String(),
			Timestamp: time.Now(),
		}
	}
	return reqCtx.(*RequestContext)
}
