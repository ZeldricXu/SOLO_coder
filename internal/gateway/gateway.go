package gateway

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/datatrace/datatrace/internal/models"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type RequestLog struct {
	RequestID    string                 `json:"request_id"`
	TraceID      string                 `json:"trace_id"`
	Method       string                 `json:"method"`
	Path         string                 `json:"path"`
	QueryParams  map[string]string      `json:"query_params"`
	Headers      map[string]string      `json:"headers"`
	RequestBody  string                 `json:"request_body,omitempty"`
	ResponseBody string                 `json:"response_body,omitempty"`
	StatusCode   int                    `json:"status_code"`
	Latency      time.Duration          `json:"latency"`
	ClientIP     string                 `json:"client_ip"`
	UserAgent    string                 `json:"user_agent"`
	Timestamp    time.Time              `json:"timestamp"`
	Attributes   map[string]interface{} `json:"attributes,omitempty"`
}

type TraceSpan struct {
	SpanID     string                 `json:"span_id"`
	TraceID    string                 `json:"trace_id"`
	ParentID   string                 `json:"parent_id,omitempty"`
	Name       string                 `json:"name"`
	Service    string                 `json:"service"`
	StartTime  time.Time              `json:"start_time"`
	EndTime    time.Time              `json:"end_time,omitempty"`
	Duration   time.Duration          `json:"duration"`
	Status     string                 `json:"status"`
	Attributes map[string]interface{} `json:"attributes,omitempty"`
	Events     []SpanEvent            `json:"events,omitempty"`
}

type SpanEvent struct {
	Timestamp time.Time              `json:"timestamp"`
	Name      string                 `json:"name"`
	Attributes map[string]interface{} `json:"attributes,omitempty"`
}

type APIGateway struct {
	requestLogs   []*RequestLog
	traceSpans    map[string][]*TraceSpan
	activeSpans   map[string]*TraceSpan
	mu            sync.RWMutex
	maxLogSize    int
	serviceName   string
}

func NewAPIGateway(serviceName string, maxLogSize int) *APIGateway {
	return &APIGateway{
		requestLogs: make([]*RequestLog, 0, maxLogSize),
		traceSpans:  make(map[string][]*TraceSpan),
		activeSpans: make(map[string]*TraceSpan),
		maxLogSize:  maxLogSize,
		serviceName: serviceName,
	}
}

func (g *APIGateway) Middleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		startTime := time.Now()

		traceID := c.GetHeader("X-Trace-ID")
		if traceID == "" {
			traceID = uuid.New().String()
		}

		requestID := uuid.New().String()
		c.Set("request_id", requestID)
		c.Set("trace_id", traceID)
		c.Header("X-Request-ID", requestID)
		c.Header("X-Trace-ID", traceID)

		var requestBody string
		if c.Request.Body != nil {
			bodyBytes, _ := io.ReadAll(c.Request.Body)
			requestBody = string(bodyBytes)
			c.Request.Body = io.NopCloser(bytes.NewBuffer(bodyBytes))
		}

		blw := &bodyLogWriter{body: bytes.NewBufferString(""), ResponseWriter: c.Writer}
		c.Writer = blw

		c.Next()

		latency := time.Since(startTime)

		queryParams := make(map[string]string)
		for k, v := range c.Request.URL.Query() {
			queryParams[k] = strings.Join(v, ",")
		}

		headers := make(map[string]string)
		for k, v := range c.Request.Header {
			headers[k] = strings.Join(v, ",")
		}

		log := &RequestLog{
			RequestID:    requestID,
			TraceID:      traceID,
			Method:       c.Request.Method,
			Path:         c.Request.URL.Path,
			QueryParams:  queryParams,
			Headers:      headers,
			RequestBody:  requestBody,
			ResponseBody: blw.body.String(),
			StatusCode:   c.Writer.Status(),
			Latency:      latency,
			ClientIP:     c.ClientIP(),
			UserAgent:    c.Request.UserAgent(),
			Timestamp:    startTime,
		}

		g.mu.Lock()
		if len(g.requestLogs) >= g.maxLogSize {
			g.requestLogs = g.requestLogs[1:]
		}
		g.requestLogs = append(g.requestLogs, log)
		g.mu.Unlock()
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

func (g *APIGateway) StartSpan(ctx context.Context, name string, parentSpanID string) *TraceSpan {
	traceID := getTraceIDFromContext(ctx)
	spanID := uuid.New().String()

	span := &TraceSpan{
		SpanID:     spanID,
		TraceID:    traceID,
		ParentID:   parentSpanID,
		Name:       name,
		Service:    g.serviceName,
		StartTime:  time.Now(),
		Status:     "in_progress",
		Attributes: make(map[string]interface{}),
	}

	g.mu.Lock()
	g.activeSpans[spanID] = span
	g.mu.Unlock()

	return span
}

func (g *APIGateway) EndSpan(span *TraceSpan, status string) {
	g.mu.Lock()
	defer g.mu.Unlock()

	span.EndTime = time.Now()
	span.Duration = span.EndTime.Sub(span.StartTime)
	span.Status = status

	delete(g.activeSpans, span.SpanID)

	if _, ok := g.traceSpans[span.TraceID]; !ok {
		g.traceSpans[span.TraceID] = make([]*TraceSpan, 0)
	}
	g.traceSpans[span.TraceID] = append(g.traceSpans[span.TraceID], span)
}

func (g *APIGateway) AddSpanEvent(span *TraceSpan, name string, attributes map[string]interface{}) {
	event := SpanEvent{
		Timestamp:  time.Now(),
		Name:       name,
		Attributes: attributes,
	}
	span.Events = append(span.Events, event)
}

func (g *APIGateway) GetRequestLogs() []*RequestLog {
	g.mu.RLock()
	defer g.mu.RUnlock()

	logs := make([]*RequestLog, len(g.requestLogs))
	copy(logs, g.requestLogs)
	return logs
}

func (g *APIGateway) GetTraceSpans(traceID string) []*TraceSpan {
	g.mu.RLock()
	defer g.mu.RUnlock()

	spans, ok := g.traceSpans[traceID]
	if !ok {
		return nil
	}
	result := make([]*TraceSpan, len(spans))
	copy(result, spans)
	return result
}

func (g *APIGateway) GetRequestLog(requestID string) *RequestLog {
	g.mu.RLock()
	defer g.mu.RUnlock()

	for _, log := range g.requestLogs {
		if log.RequestID == requestID {
			return log
		}
	}
	return nil
}

func (g *APIGateway) GetActiveSpans() []*TraceSpan {
	g.mu.RLock()
	defer g.mu.RUnlock()

	spans := make([]*TraceSpan, 0, len(g.activeSpans))
	for _, span := range g.activeSpans {
		spans = append(spans, span)
	}
	return spans
}

func getTraceIDFromContext(ctx context.Context) string {
	if traceID, ok := ctx.Value("trace_id").(string); ok {
		return traceID
	}
	return uuid.New().String()
}

func (g *APIGateway) ToEntity() *models.Entity {
	return &models.Entity{
		ID:        uuid.New().String(),
		Type:      "api_gateway",
		Status:    "active",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
}

func (g *APIGateway) CORSMiddleware() gin.HandlerFunc {
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

func (g *APIGateway) RateLimitMiddleware(maxRequests int, window time.Duration) gin.HandlerFunc {
	type clientData struct {
		count    int
		windowStart time.Time
	}

	clients := make(map[string]*clientData)
	var mu sync.Mutex

	return func(c *gin.Context) {
		clientIP := c.ClientIP()

		mu.Lock()
		data, ok := clients[clientIP]
		if !ok {
			data = &clientData{
				count:    0,
				windowStart: time.Now(),
			}
			clients[clientIP] = data
		}

		if time.Since(data.windowStart) > window {
			data.count = 0
			data.windowStart = time.Now()
		}

		data.count++

		if data.count > maxRequests {
			mu.Unlock()
			c.JSON(http.StatusTooManyRequests, models.APIResponse{
				Code: 429,
				Msg:  "Rate limit exceeded",
			})
			c.Abort()
			return
		}
		mu.Unlock()

		c.Next()
	}
}
