package gateway

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"
	applogger "session172/internal/logger"
	"session172/pkg/models"
)

const (
	TraceContextKey contextKey = "trace_context"
	TraceIDHeader   string     = "X-Trace-ID"
	SpanIDHeader    string     = "X-Span-ID"

	defaultMaxLogs       = 10000
	defaultLogBufferSize = 1000
)

type (
	contextKey string

	TracingContext struct {
		TraceID    string
		SpanID     string
		ParentID   string
		Service    string
		StartTime  time.Time
		Attributes map[string]interface{}
	}

	Gateway struct {
		mu          sync.RWMutex
		router      *gin.Engine
		middlewares []gin.HandlerFunc
		logs        []*models.RequestLog
	}

	bodyLogWriter struct {
		gin.ResponseWriter
		body *bytes.Buffer
	}
)

var (
	gatewayInstance *Gateway
	gatewayOnce     sync.Once
)

func NewGateway() *Gateway {
	gatewayOnce.Do(func() {
		gin.SetMode(gin.ReleaseMode)
		gatewayInstance = &Gateway{
			router:      gin.New(),
			middlewares: make([]gin.HandlerFunc, 0),
			logs:        make([]*models.RequestLog, 0, defaultLogBufferSize),
		}
		gatewayInstance.setupDefaultMiddlewares()
	})
	return gatewayInstance
}

func GetGateway() *Gateway {
	if gatewayInstance == nil {
		return NewGateway()
	}
	return gatewayInstance
}

func (g *Gateway) setupDefaultMiddlewares() {
	g.router.Use(
		g.RequestIDMiddleware(),
		g.TracingMiddleware(),
		g.LoggingMiddleware(),
		g.RecoveryMiddleware(),
		gin.Logger(),
	)
}

func (g *Gateway) RequestIDMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		traceCtx := &TracingContext{
			TraceID:    g.getOrGenerateHeader(c, TraceIDHeader),
			SpanID:     g.generateSpanID(c.GetHeader(SpanIDHeader)),
			Service:    "gateway",
			StartTime:  time.Now(),
			Attributes: make(map[string]interface{}),
		}

		c.Set(string(TraceContextKey), traceCtx)
		c.Header(TraceIDHeader, traceCtx.TraceID)
		c.Header(SpanIDHeader, traceCtx.SpanID)

		c.Next()
	}
}

func (g *Gateway) getOrGenerateHeader(c *gin.Context, header string) string {
	value := c.GetHeader(header)
	if value == "" {
		return uuid.New().String()
	}
	return value
}

func (g *Gateway) generateSpanID(parentSpanID string) string {
	if parentSpanID == "" {
		return uuid.New().String()[:8]
	}
	return parentSpanID
}

func (g *Gateway) TracingMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		traceCtx := GetTracingContext(c)
		if traceCtx == nil {
			c.Next()
			return
		}

		traceCtx.Attributes["method"] = c.Request.Method
		traceCtx.Attributes["path"] = c.Request.URL.Path
		traceCtx.Attributes["client_ip"] = c.ClientIP()

		c.Next()
	}
}

func (g *Gateway) LoggingMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path
		method := c.Request.Method

		bodyBytes := g.readAndRestoreBody(c)
		blw := g.wrapResponseWriter(c)

		c.Next()

		logEntry := g.buildRequestLog(c, start, method, path, bodyBytes, blw)
		g.storeLog(logEntry)
		g.logRequest(logEntry, method, path)
	}
}

func (g *Gateway) readAndRestoreBody(c *gin.Context) []byte {
	if c.Request.Body == nil {
		return nil
	}

	bodyBytes, _ := io.ReadAll(c.Request.Body)
	c.Request.Body = io.NopCloser(bytes.NewBuffer(bodyBytes))
	return bodyBytes
}

func (g *Gateway) wrapResponseWriter(c *gin.Context) *bodyLogWriter {
	blw := &bodyLogWriter{
		body:           bytes.NewBufferString(""),
		ResponseWriter: c.Writer,
	}
	c.Writer = blw
	return blw
}

func (g *Gateway) buildRequestLog(c *gin.Context, start time.Time, method, path string, bodyBytes []byte, blw *bodyLogWriter) *models.RequestLog {
	duration := time.Since(start)
	statusCode := c.Writer.Status()

	logEntry := &models.RequestLog{
		TraceID:    getTraceID(c),
		Method:     method,
		Path:       path,
		StatusCode: statusCode,
		Duration:   duration.Milliseconds(),
		ClientIP:   c.ClientIP(),
		UserAgent:  c.Request.UserAgent(),
		RequestAt:  start,
	}

	if len(c.Errors) > 0 {
		logEntry.Error = c.Errors.String()
	}

	return logEntry
}

func (g *Gateway) storeLog(logEntry *models.RequestLog) {
	g.mu.Lock()
	defer g.mu.Unlock()

	g.logs = append(g.logs, logEntry)
	if len(g.logs) > defaultMaxLogs {
		g.logs = g.logs[defaultMaxLogs/10:]
	}
}

func (g *Gateway) logRequest(logEntry *models.RequestLog, method, path string) {
	applogger.WithTraceID(logEntry.TraceID).Info("Request completed",
		zap.String("method", method),
		zap.String("path", path),
		zap.Int("status", logEntry.StatusCode),
		zap.Int64("duration_ms", logEntry.Duration),
		zap.String("client_ip", logEntry.ClientIP),
	)
}

func (g *Gateway) RecoveryMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if err := recover(); err != nil {
				traceID := getTraceID(c)
				applogger.WithTraceID(traceID).Error("Panic recovered",
					zap.Any("error", err),
					zap.String("path", c.Request.URL.Path),
				)

				c.JSON(http.StatusInternalServerError, gin.H{
					"code":     500,
					"message":  "Internal Server Error",
					"trace_id": traceID,
				})
				c.Abort()
			}
		}()
		c.Next()
	}
}

func (g *Gateway) AddMiddleware(middleware gin.HandlerFunc) {
	g.router.Use(middleware)
}

func (g *Gateway) GET(path string, handlers ...gin.HandlerFunc) gin.IRoutes {
	return g.router.GET(path, handlers...)
}

func (g *Gateway) POST(path string, handlers ...gin.HandlerFunc) gin.IRoutes {
	return g.router.POST(path, handlers...)
}

func (g *Gateway) PUT(path string, handlers ...gin.HandlerFunc) gin.IRoutes {
	return g.router.PUT(path, handlers...)
}

func (g *Gateway) DELETE(path string, handlers ...gin.HandlerFunc) gin.IRoutes {
	return g.router.DELETE(path, handlers...)
}

func (g *Gateway) Group(path string, handlers ...gin.HandlerFunc) *gin.RouterGroup {
	return g.router.Group(path, handlers...)
}

func (g *Gateway) Run(addr string) error {
	applogger.Infof("Gateway starting on %s", addr)
	return g.router.Run(addr)
}

func (g *Gateway) GetRouter() *gin.Engine {
	return g.router
}

func (g *Gateway) GetLogs(limit int) []*models.RequestLog {
	g.mu.RLock()
	defer g.mu.RUnlock()

	if limit <= 0 || limit > len(g.logs) {
		limit = len(g.logs)
	}

	logs := make([]*models.RequestLog, limit)
	start := len(g.logs) - limit
	for i := 0; i < limit; i++ {
		logs[i] = g.logs[start+i]
	}
	return logs
}

func getTraceID(c *gin.Context) string {
	if traceCtx := GetTracingContext(c); traceCtx != nil {
		return traceCtx.TraceID
	}
	return ""
}

func GetTracingContext(c *gin.Context) *TracingContext {
	ctx, exists := c.Get(string(TraceContextKey))
	if !exists {
		return nil
	}
	return ctx.(*TracingContext)
}

func (tc *TracingContext) ToContext(ctx context.Context) context.Context {
	return context.WithValue(ctx, TraceContextKey, tc)
}

func FromContext(ctx context.Context) *TracingContext {
	if ctx == nil {
		return nil
	}
	tc, ok := ctx.Value(TraceContextKey).(*TracingContext)
	if !ok {
		return nil
	}
	return tc
}

func (tc *TracingContext) NewChildSpan(service string) *TracingContext {
	return &TracingContext{
		TraceID:    tc.TraceID,
		SpanID:     uuid.New().String()[:8],
		ParentID:   tc.SpanID,
		Service:    service,
		StartTime:  time.Now(),
		Attributes: make(map[string]interface{}),
	}
}

func (w *bodyLogWriter) Write(b []byte) (int, error) {
	w.body.Write(b)
	return w.ResponseWriter.Write(b)
}

func (w *bodyLogWriter) WriteString(s string) (int, error) {
	w.body.WriteString(s)
	return w.ResponseWriter.WriteString(s)
}
