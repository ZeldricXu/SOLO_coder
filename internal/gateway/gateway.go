package gateway

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/solocoder/backup-engine/internal/core"
	"github.com/solocoder/backup-engine/internal/logger"
	"github.com/solocoder/backup-engine/pkg/common"
)

type Middleware func(http.HandlerFunc) http.HandlerFunc

type Route struct {
	Path        string
	Method      string
	Handler     func(ctx context.Context, req *common.Request) (*common.Response, error)
	Middlewares []Middleware
}

type TracingContext struct {
	TraceID    string
	SpanID     string
	ParentID   string
	StartTime  time.Time
	Attributes map[string]string
}

type APIGateway struct {
	processor  *core.Processor
	routes     map[string]*Route
	middleware []Middleware
	mu         sync.RWMutex
	traces     map[string][]*TracingContext
	maxTraces  int
	server     *http.Server
}

func NewAPIGateway(processor *core.Processor) *APIGateway {
	return &APIGateway{
		processor: processor,
		routes:    make(map[string]*Route),
		middleware: []Middleware{
			RequestIDMiddleware,
			LoggingMiddleware,
			TracingMiddleware,
			TimeoutMiddleware,
			RecoveryMiddleware,
		},
		traces:    make(map[string][]*TracingContext),
		maxTraces: 1000,
	}
}

func (g *APIGateway) AddRoute(path, method string, handler func(ctx context.Context, req *common.Request) (*common.Response, error), middlewares ...Middleware) {
	g.mu.Lock()
	defer g.mu.Unlock()

	key := method + ":" + path
	g.routes[key] = &Route{
		Path:        path,
		Method:      method,
		Handler:     handler,
		Middlewares: middlewares,
	}

	logger.Info("Registered route", map[string]interface{}{
		"path":   path,
		"method": method,
	})
}

func (g *APIGateway) Start(port int) error {
	mux := http.NewServeMux()

	g.mu.RLock()
	routes := make([]*Route, 0, len(g.routes))
	for _, r := range g.routes {
		routes = append(routes, r)
	}
	g.mu.RUnlock()

	for _, route := range routes {
		handler := g.wrapHandler(route)
		mux.HandleFunc(route.Path, handler)
	}

	mux.HandleFunc("/health", g.healthHandler)
	mux.HandleFunc("/metrics", g.metricsHandler)
	mux.HandleFunc("/traces", g.tracesHandler)

	g.server = &http.Server{
		Addr:         fmt.Sprintf(":%d", port),
		Handler:      mux,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  120 * time.Second,
	}

	logger.Info("API Gateway starting", map[string]interface{}{"port": port})
	go func() {
		if err := g.server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Error("Gateway server error", map[string]interface{}{"error": err.Error()})
		}
	}()

	return nil
}

func (g *APIGateway) Stop(ctx context.Context) error {
	if g.server != nil {
		logger.Info("API Gateway stopping")
		return g.server.Shutdown(ctx)
	}
	return nil
}

func (g *APIGateway) wrapHandler(route *Route) http.HandlerFunc {
	var handler http.HandlerFunc = func(w http.ResponseWriter, r *http.Request) {
		g.handleRequest(w, r, route)
	}

	for i := len(g.middleware) - 1; i >= 0; i-- {
		handler = g.middleware[i](handler)
	}

	for i := len(route.Middlewares) - 1; i >= 0; i-- {
		handler = route.Middlewares[i](handler)
	}

	return handler
}

func (g *APIGateway) handleRequest(w http.ResponseWriter, r *http.Request, route *Route) {
	ctx := r.Context()

	traceID := r.Header.Get("X-Trace-ID")
	if traceID == "" {
		traceID = common.GenerateTraceID()
	}

	spanID := common.NewID()
	parentID := r.Header.Get("X-Parent-ID")

	tracingCtx := &TracingContext{
		TraceID:    traceID,
		SpanID:     spanID,
		ParentID:   parentID,
		StartTime:  time.Now(),
		Attributes: map[string]string{
			"http.method": r.Method,
			"http.path":   r.URL.Path,
			"http.remote": r.RemoteAddr,
		},
	}

	g.addTrace(traceID, tracingCtx)

	var payload interface{}
	if r.Body != http.NoBody {
		json.NewDecoder(r.Body).Decode(&payload)
	}

	headers := make(map[string]string)
	for k, v := range r.Header {
		if len(v) > 0 {
			headers[k] = v[0]
		}
	}
	headers["X-Trace-ID"] = traceID
	headers["X-Span-ID"] = spanID

	timeout := 30 * time.Second
	if timeoutHeader := r.Header.Get("X-Timeout"); timeoutHeader != "" {
		if d, err := time.ParseDuration(timeoutHeader); err == nil {
			timeout = d
		}
	}

	req := &common.Request{
		ID:        common.NewID(),
		TraceID:   traceID,
		Timestamp: time.Now(),
		Operation: r.URL.Path,
		Payload:   payload,
		Headers:   headers,
		Timeout:   timeout,
	}

	if route.Handler != nil {
		customResp, err := route.Handler(ctx, req)
		if err != nil {
			resp := &common.Response{
				RequestID: req.ID,
				TraceID:   req.TraceID,
				Success:   false,
				Code:      500,
				Message:   err.Error(),
				Error:     err.Error(),
			}
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("X-Trace-ID", traceID)
			w.Header().Set("X-Span-ID", spanID)
			w.WriteHeader(resp.Code)
			json.NewEncoder(w).Encode(resp)
			return
		} else if customResp != nil {
			customResp.RequestID = req.ID
			customResp.TraceID = req.TraceID
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("X-Trace-ID", traceID)
			w.Header().Set("X-Span-ID", spanID)
			w.WriteHeader(customResp.Code)
			json.NewEncoder(w).Encode(customResp)
			return
		}
	}

	resp := g.processor.Process(ctx, req)

	tracingCtx.Attributes["http.status_code"] = fmt.Sprintf("%d", resp.Code)
	tracingCtx.Attributes["duration_ms"] = fmt.Sprintf("%d", resp.Duration.Milliseconds())

	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("X-Trace-ID", traceID)
	w.Header().Set("X-Span-ID", spanID)
	w.WriteHeader(resp.Code)
	json.NewEncoder(w).Encode(resp)
}

func (g *APIGateway) addTrace(traceID string, span *TracingContext) {
	g.mu.Lock()
	defer g.mu.Unlock()

	g.traces[traceID] = append(g.traces[traceID], span)

	if len(g.traces) > g.maxTraces {
		oldestID := ""
		oldestTime := time.Now()
		for id, spans := range g.traces {
			if len(spans) > 0 && spans[0].StartTime.Before(oldestTime) {
				oldestTime = spans[0].StartTime
				oldestID = id
			}
		}
		if oldestID != "" {
			delete(g.traces, oldestID)
		}
	}
}

func (g *APIGateway) healthHandler(w http.ResponseWriter, r *http.Request) {
	resp := g.processor.HealthCheck()
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(resp.Code)
	json.NewEncoder(w).Encode(resp)
}

func (g *APIGateway) metricsHandler(w http.ResponseWriter, r *http.Request) {
	metrics := g.processor.GetMetrics()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status": "ok",
		"data":   metrics,
	})
}

func (g *APIGateway) tracesHandler(w http.ResponseWriter, r *http.Request) {
	traceID := r.URL.Query().Get("trace_id")

	g.mu.RLock()
	defer g.mu.RUnlock()

	if traceID != "" {
		if traces, exists := g.traces[traceID]; exists {
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(traces)
			return
		}
		w.WriteHeader(http.StatusNotFound)
		json.NewEncoder(w).Encode(map[string]string{"error": "trace not found"})
		return
	}

	traceIDs := make([]string, 0, len(g.traces))
	for id := range g.traces {
		traceIDs = append(traceIDs, id)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(traceIDs)
}

func RequestIDMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Request-ID") == "" {
			r.Header.Set("X-Request-ID", common.NewID())
		}
		w.Header().Set("X-Request-ID", r.Header.Get("X-Request-ID"))
		next(w, r)
	}
}

func LoggingMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		traceID := r.Header.Get("X-Trace-ID")
		if traceID == "" {
			traceID = common.GenerateTraceID()
			r.Header.Set("X-Trace-ID", traceID)
		}

		logger.Info("Request started", map[string]interface{}{
			"trace_id":   traceID,
			"method":     r.Method,
			"path":       r.URL.Path,
			"remote":     r.RemoteAddr,
			"user_agent": r.UserAgent(),
		})

		lrw := &loggingResponseWriter{ResponseWriter: w, statusCode: http.StatusOK}
		next(lrw, r)

		duration := time.Since(start)
		logger.Info("Request completed", map[string]interface{}{
			"trace_id":   traceID,
			"status":     lrw.statusCode,
			"duration":   common.FormatDuration(duration),
			"bytes_sent": lrw.bytesWritten,
		})
	}
}

func TracingMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		traceID := r.Header.Get("X-Trace-ID")
		if traceID == "" {
			traceID = common.GenerateTraceID()
			r.Header.Set("X-Trace-ID", traceID)
		}

		ctx := context.WithValue(r.Context(), "trace_id", traceID)
		ctx = context.WithValue(ctx, "span_id", common.NewID())

		next(w, r.WithContext(ctx))
	}
}

func TimeoutMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		timeout := 30 * time.Second
		if timeoutHeader := r.Header.Get("X-Timeout"); timeoutHeader != "" {
			if d, err := time.ParseDuration(timeoutHeader); err == nil {
				timeout = d
			}
		}

		ctx, cancel := context.WithTimeout(r.Context(), timeout)
		defer cancel()

		done := make(chan bool, 1)
		go func() {
			next(w, r.WithContext(ctx))
			done <- true
		}()

		select {
		case <-done:
		case <-ctx.Done():
			w.WriteHeader(http.StatusRequestTimeout)
			json.NewEncoder(w).Encode(common.Response{
				Success: false,
				Code:    http.StatusRequestTimeout,
				Message: "request timed out",
				Error:   ctx.Err().Error(),
			})
		}
	}
}

func RecoveryMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if err := recover(); err != nil {
				logger.Error("Panic recovered", map[string]interface{}{
					"error": err,
					"path":  r.URL.Path,
				})
				w.WriteHeader(http.StatusInternalServerError)
				json.NewEncoder(w).Encode(common.Response{
					Success: false,
					Code:    http.StatusInternalServerError,
					Message: "internal server error",
					Error:   fmt.Sprintf("%v", err),
				})
			}
		}()
		next(w, r)
	}
}

type loggingResponseWriter struct {
	http.ResponseWriter
	statusCode   int
	bytesWritten int
}

func (lrw *loggingResponseWriter) WriteHeader(code int) {
	lrw.statusCode = code
	lrw.ResponseWriter.WriteHeader(code)
}

func (lrw *loggingResponseWriter) Write(b []byte) (int, error) {
	n, err := lrw.ResponseWriter.Write(b)
	lrw.bytesWritten += n
	return n, err
}

func (g *APIGateway) GetTrace(traceID string) []*TracingContext {
	g.mu.RLock()
	defer g.mu.RUnlock()
	return g.traces[traceID]
}
