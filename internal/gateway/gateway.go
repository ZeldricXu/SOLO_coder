package gateway

import (
	"bytes"
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/solocoder/tasktracker/internal/logger"
	"io/ioutil"
	"net/http"
	"net/http/httputil"
	"net/url"
	"sync"
	"time"
)

type Route struct {
	Path        string            `json:"path"`
	Method      string            `json:"method"`
	TargetURL   string            `json:"target_url"`
	TimeoutMs   int               `json:"timeout_ms"`
	RateLimit   int               `json:"rate_limit"`
	AuthRequired bool             `json:"auth_required"`
	Headers     map[string]string `json:"headers,omitempty"`
}

type RequestLog struct {
	TraceID     string            `json:"trace_id"`
	ParentID    string            `json:"parent_id,omitempty"`
	ServiceName string            `json:"service_name"`
	Method      string            `json:"method"`
	Path        string            `json:"path"`
	StatusCode  int               `json:"status_code"`
	LatencyMs   int64             `json:"latency_ms"`
	RequestSize int64             `json:"request_size"`
	ResponseSize int64            `json:"response_size"`
	ClientIP    string            `json:"client_ip"`
	UserAgent   string            `json:"user_agent"`
	Timestamp   time.Time         `json:"timestamp"`
	Tags        map[string]string `json:"tags,omitempty"`
	Error       string            `json:"error,omitempty"`
}

type Span struct {
	TraceID    string            `json:"trace_id"`
	SpanID     string            `json:"span_id"`
	ParentID   string            `json:"parent_id,omitempty"`
	Name       string            `json:"name"`
	Service    string            `json:"service"`
	StartTime  time.Time         `json:"start_time"`
	EndTime    *time.Time        `json:"end_time,omitempty"`
	DurationMs int64             `json:"duration_ms,omitempty"`
	Tags       map[string]string `json:"tags,omitempty"`
	Error      string            `json:"error,omitempty"`
}

type APIGateway struct {
	mu         sync.RWMutex
	routes     map[string]*Route
	requestLogs []RequestLog
	spans      []Span
	maxLogs    int
	proxy      *httputil.ReverseProxy
}

type Config struct {
	MaxLogs int `json:"max_logs"`
}

func NewAPIGateway(cfg Config) *APIGateway {
	if cfg.MaxLogs <= 0 {
		cfg.MaxLogs = 1000
	}

	gw := &APIGateway{
		routes:     make(map[string]*Route),
		requestLogs: make([]RequestLog, 0),
		spans:      make([]Span, 0),
		maxLogs:    cfg.MaxLogs,
	}

	gw.proxy = &httputil.ReverseProxy{
		Director:       gw.director,
		ModifyResponse: gw.modifyResponse,
		ErrorHandler:   gw.errorHandler,
	}

	return gw
}

func (gw *APIGateway) AddRoute(route *Route) {
	gw.mu.Lock()
	defer gw.mu.Unlock()

	key := route.Method + ":" + route.Path
	gw.routes[key] = route
	logger.Info("Route added", logger.String("path", route.Path), logger.String("method", route.Method), logger.String("target", route.TargetURL))
}

func (gw *APIGateway) RemoveRoute(method, path string) {
	gw.mu.Lock()
	defer gw.mu.Unlock()

	key := method + ":" + path
	delete(gw.routes, key)
	logger.Info("Route removed", logger.String("path", path), logger.String("method", method))
}

func (gw *APIGateway) GetRoute(method, path string) (*Route, bool) {
	gw.mu.RLock()
	defer gw.mu.RUnlock()

	key := method + ":" + path
	route, exists := gw.routes[key]
	return route, exists
}

func (gw *APIGateway) ListRoutes() []*Route {
	gw.mu.RLock()
	defer gw.mu.RUnlock()

	result := make([]*Route, 0, len(gw.routes))
	for _, r := range gw.routes {
		result = append(result, r)
	}
	return result
}

func (gw *APIGateway) Middleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		startTime := time.Now()
		traceID := gw.getOrCreateTraceID(c)
		spanID := uuid.New().String()

		c.Set("trace_id", traceID)
		c.Set("span_id", spanID)

		c.Request.Header.Set("X-Trace-ID", traceID)
		c.Request.Header.Set("X-Span-ID", spanID)

		span := Span{
			TraceID:   traceID,
			SpanID:    spanID,
			Name:      c.Request.Method + " " + c.Request.URL.Path,
			Service:   "api-gateway",
			StartTime: startTime,
			Tags: map[string]string{
				"method": c.Request.Method,
				"path":   c.Request.URL.Path,
			},
		}

		var requestBody []byte
		if c.Request.Body != nil {
			requestBody, _ = ioutil.ReadAll(c.Request.Body)
			c.Request.Body = ioutil.NopCloser(bytes.NewBuffer(requestBody))
		}

		blw := &bodyLogWriter{body: bytes.NewBufferString(""), ResponseWriter: c.Writer}
		c.Writer = blw

		c.Next()

		endTime := time.Now()
		duration := endTime.Sub(startTime)
		span.EndTime = &endTime
		span.DurationMs = duration.Milliseconds()

		if len(c.Errors) > 0 {
			span.Error = c.Errors.String()
		}

		gw.mu.Lock()
		gw.spans = append(gw.spans, span)

		log := RequestLog{
			TraceID:      traceID,
			ServiceName:  "api-gateway",
			Method:       c.Request.Method,
			Path:         c.Request.URL.Path,
			StatusCode:   c.Writer.Status(),
			LatencyMs:    duration.Milliseconds(),
			RequestSize:  int64(len(requestBody)),
			ResponseSize: int64(blw.body.Len()),
			ClientIP:     c.ClientIP(),
			UserAgent:    c.Request.UserAgent(),
			Timestamp:    startTime,
		}

		if len(c.Errors) > 0 {
			log.Error = c.Errors.String()
		}

		gw.requestLogs = append(gw.requestLogs, log)

		if len(gw.requestLogs) > gw.maxLogs {
			gw.requestLogs = gw.requestLogs[len(gw.requestLogs)-gw.maxLogs:]
		}
		if len(gw.spans) > gw.maxLogs {
			gw.spans = gw.spans[len(gw.spans)-gw.maxLogs:]
		}
		gw.mu.Unlock()

		logger.Info("Request processed",
			logger.String("trace_id", traceID),
			logger.String("method", c.Request.Method),
			logger.String("path", c.Request.URL.Path),
			logger.Int("status_code", c.Writer.Status()),
			logger.Int64("latency_ms", duration.Milliseconds()),
		)
	}
}

func (gw *APIGateway) ProxyHandler() gin.HandlerFunc {
	return func(c *gin.Context) {
		route, exists := gw.GetRoute(c.Request.Method, c.Request.URL.Path)
		if !exists {
			c.JSON(404, gin.H{"error": "route not found"})
			c.Abort()
			return
		}

		target, err := url.Parse(route.TargetURL)
		if err != nil {
			c.JSON(500, gin.H{"error": "invalid target URL"})
			c.Abort()
			return
		}

		gw.proxy.ServeHTTP(c.Writer, c.Request)
	}
}

func (gw *APIGateway) director(req *http.Request) {
	route, exists := gw.GetRoute(req.Method, req.URL.Path)
	if !exists {
		return
	}

	target, _ := url.Parse(route.TargetURL)
	req.URL.Scheme = target.Scheme
	req.URL.Host = target.Host
	req.Host = target.Host

	for k, v := range route.Headers {
		req.Header.Set(k, v)
	}
}

func (gw *APIGateway) modifyResponse(resp *http.Response) error {
	resp.Header.Set("X-Gateway-Processed", "true")
	return nil
}

func (gw *APIGateway) errorHandler(w http.ResponseWriter, r *http.Request, err error) {
	w.WriteHeader(502)
	w.Write([]byte(fmt.Sprintf(`{"error": "gateway error: %v"}`, err)))
}

func (gw *APIGateway) getOrCreateTraceID(c *gin.Context) string {
	traceID := c.GetHeader("X-Trace-ID")
	if traceID == "" {
		traceID = uuid.New().String()
	}
	return traceID
}

func (gw *APIGateway) GetRequestLogs(traceID string, limit int) []RequestLog {
	gw.mu.RLock()
	defer gw.mu.RUnlock()

	result := make([]RequestLog, 0)
	for i := len(gw.requestLogs) - 1; i >= 0 && len(result) < limit; i-- {
		log := gw.requestLogs[i]
		if traceID == "" || log.TraceID == traceID {
			result = append(result, log)
		}
	}
	return result
}

func (gw *APIGateway) GetSpans(traceID string) []Span {
	gw.mu.RLock()
	defer gw.mu.RUnlock()

	result := make([]Span, 0)
	for _, span := range gw.spans {
		if traceID == "" || span.TraceID == traceID {
			result = append(result, span)
		}
	}
	return result
}

func (gw *APIGateway) GetTrace(traceID string) map[string]interface{} {
	logs := gw.GetRequestLogs(traceID, 100)
	spans := gw.GetSpans(traceID)

	totalLatency := int64(0)
	for _, span := range spans {
		totalLatency += span.DurationMs
	}

	return map[string]interface{}{
		"trace_id":  traceID,
		"spans":     spans,
		"logs":      logs,
		"total_spans": len(spans),
		"total_logs":  len(logs),
		"total_latency_ms": totalLatency,
	}
}

func (gw *APIGateway) GetStats() map[string]interface{} {
	gw.mu.RLock()
	defer gw.mu.RUnlock()

	statusCodes := make(map[int]int)
	totalRequests := len(gw.requestLogs)
	totalLatency := int64(0)
	errorCount := 0

	for _, log := range gw.requestLogs {
		statusCodes[log.StatusCode]++
		totalLatency += log.LatencyMs
		if log.StatusCode >= 500 {
			errorCount++
		}
	}

	avgLatency := int64(0)
	if totalRequests > 0 {
		avgLatency = totalLatency / int64(totalRequests)
	}

	return map[string]interface{}{
		"total_requests":   totalRequests,
		"total_routes":     len(gw.routes),
		"status_codes":     statusCodes,
		"avg_latency_ms":   avgLatency,
		"error_count":      errorCount,
		"error_rate":       float64(errorCount) / float64(totalRequests) * 100,
		"stored_logs":      len(gw.requestLogs),
		"stored_spans":     len(gw.spans),
	}
}

func (gw *APIGateway) CreateSpan(traceID, parentID, name, service string) *Span {
	span := &Span{
		TraceID:   traceID,
		SpanID:    uuid.New().String(),
		ParentID:  parentID,
		Name:      name,
		Service:   service,
		StartTime: time.Now(),
		Tags:      make(map[string]string),
	}

	gw.mu.Lock()
	gw.spans = append(gw.spans, *span)
	if len(gw.spans) > gw.maxLogs {
		gw.spans = gw.spans[len(gw.spans)-gw.maxLogs:]
	}
	gw.mu.Unlock()

	return span
}

func (gw *APIGateway) EndSpan(spanID string, err error) {
	gw.mu.Lock()
	defer gw.mu.Unlock()

	for i := range gw.spans {
		if gw.spans[i].SpanID == spanID && gw.spans[i].EndTime == nil {
			now := time.Now()
			gw.spans[i].EndTime = &now
			gw.spans[i].DurationMs = now.Sub(gw.spans[i].StartTime).Milliseconds()
			if err != nil {
				gw.spans[i].Error = err.Error()
			}
			break
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

func (w *bodyLogWriter) WriteString(s string) (int, error) {
	w.body.WriteString(s)
	return w.ResponseWriter.WriteString(s)
}
