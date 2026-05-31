package gateway

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"

	"session130/internal/logger"
	"session130/internal/metrics"
	"session130/internal/tracing"
)

type RateLimiter struct {
	mu        sync.Mutex
	tokens    map[string]int
	limit     int
	window    time.Duration
	lastReset map[string]time.Time
}

func NewRateLimiter(limit int, window time.Duration) *RateLimiter {
	return &RateLimiter{
		tokens:    make(map[string]int),
		limit:     limit,
		window:    window,
		lastReset: make(map[string]time.Time),
	}
}

func (rl *RateLimiter) Allow(key string) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	now := time.Now()
	if last, exists := rl.lastReset[key]; !exists || now.Sub(last) > rl.window {
		rl.tokens[key] = rl.limit
		rl.lastReset[key] = now
	}

	if rl.tokens[key] > 0 {
		rl.tokens[key]--
		return true
	}
	return false
}

type ObservableMiddleware struct {
	metricsEnabled    bool
	tracingEnabled    bool
	loggingEnabled    bool
	requestBodyLimit  int64
	responseBodyLimit int64
}

func NewObservableMiddleware() *ObservableMiddleware {
	return &ObservableMiddleware{
		metricsEnabled:    true,
		tracingEnabled:    true,
		loggingEnabled:    true,
		requestBodyLimit:  1024 * 1024,
		responseBodyLimit: 1024 * 1024,
	}
}

type Gateway struct {
	rateLimiter *RateLimiter
	handlers    map[string]func(http.ResponseWriter, *http.Request)
	mu          sync.RWMutex
	observable  *ObservableMiddleware
	startTime   time.Time
}

var (
	instance *Gateway
	once     sync.Once
)

func NewGateway() *Gateway {
	return &Gateway{
		rateLimiter: NewRateLimiter(1000, time.Second),
		handlers:    make(map[string]func(http.ResponseWriter, *http.Request)),
		observable:  NewObservableMiddleware(),
		startTime:   time.Now(),
	}
}

func GetGateway() *Gateway {
	once.Do(func() {
		instance = NewGateway()
	})
	return instance
}

func (g *Gateway) Middleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		traceID := r.Header.Get("X-Trace-ID")
		if traceID == "" {
			traceID = tracing.GenerateTraceID()
		}

		span := tracing.NewSpan(traceID, "gateway", r.Method+" "+r.URL.Path)
		span.Attributes["method"] = r.Method
		span.Attributes["path"] = r.URL.Path
		span.Attributes["remote_addr"] = r.RemoteAddr
		span.Attributes["user_agent"] = r.UserAgent()
		span.Attributes["content_length"] = r.ContentLength

		w.Header().Set("X-Trace-ID", traceID)
		w.Header().Set("X-Request-ID", traceID)

		if !g.rateLimiter.Allow(r.RemoteAddr) {
			span.Status = "error"
			span.EndTime = time.Now()
			tracing.RecordSpan(span)

			logger.Warn(traceID, "rate limit exceeded", map[string]interface{}{
				"remote_addr": r.RemoteAddr,
				"path":        r.URL.Path,
			})

			metrics.Inc("http_requests_rate_limited_total", map[string]string{
				"path": r.URL.Path,
			})

			http.Error(w, "rate limit exceeded", http.StatusTooManyRequests)
			return
		}

		lrw := &loggingResponseWriter{ResponseWriter: w, status: http.StatusOK}

		defer func() {
			duration := time.Since(start)

			span.Status = "ok"
			if lrw.status >= 400 {
				span.Status = "error"
			}
			span.EndTime = time.Now()
			span.Attributes["status_code"] = lrw.status
			span.Attributes["response_size"] = lrw.size
			tracing.RecordSpan(span)

			labels := map[string]string{
				"method": r.Method,
				"path":   r.URL.Path,
				"status": fmt.Sprintf("%d", lrw.status),
			}

			metrics.Inc("http_requests_total", labels)
			metrics.Observe("http_request_duration_seconds", duration.Seconds(), labels)
			metrics.Observe("http_request_size_bytes", float64(r.ContentLength), labels)
			metrics.Observe("http_response_size_bytes", float64(lrw.size), labels)

			if lrw.status >= 500 {
				metrics.Inc("http_requests_errors_total", labels)
			} else if lrw.status >= 400 {
				metrics.Inc("http_requests_client_errors_total", labels)
			}

			if g.observable.loggingEnabled {
				logEntry := map[string]interface{}{
					"trace_id":      traceID,
					"method":        r.Method,
					"path":          r.URL.Path,
					"status":        lrw.status,
					"duration_ms":   duration.Milliseconds(),
					"remote_addr":   r.RemoteAddr,
					"user_agent":    r.UserAgent(),
					"request_size":  r.ContentLength,
					"response_size": lrw.size,
				}

				if lrw.status >= 500 {
					logger.Error(traceID, "request failed", logEntry)
				} else if lrw.status >= 400 {
					logger.Warn(traceID, "request client error", logEntry)
				} else {
					logger.Info(traceID, "request completed", logEntry)
				}
			}

			if rec := recover(); rec != nil {
				metrics.Inc("http_requests_panic_total", labels)
				logger.Error(traceID, "panic recovered", map[string]interface{}{
					"panic": rec,
				})
				http.Error(w, "internal server error", http.StatusInternalServerError)
			}
		}()

		ctx := r.Context()
		r = r.WithContext(ctx)

		next(lrw, r)
	}
}

type loggingResponseWriter struct {
	http.ResponseWriter
	status int
	size   int64
}

func (lrw *loggingResponseWriter) WriteHeader(code int) {
	lrw.status = code
	lrw.ResponseWriter.WriteHeader(code)
}

func (lrw *loggingResponseWriter) Write(b []byte) (int, error) {
	size, err := lrw.ResponseWriter.Write(b)
	lrw.size += int64(size)
	return size, err
}

func (g *Gateway) RegisterHandler(path string, handler func(http.ResponseWriter, *http.Request)) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.handlers[path] = handler
}

func (g *Gateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	g.mu.RLock()
	handler, exists := g.handlers[r.URL.Path]
	g.mu.RUnlock()

	if !exists {
		metrics.Inc("http_requests_not_found_total", map[string]string{
			"path": r.URL.Path,
		})
		http.NotFound(w, r)
		return
	}

	g.Middleware(handler)(w, r)
}

func (g *Gateway) Start(addr string) error {
	logger.Info("", "gateway starting", map[string]interface{}{
		"addr": addr,
	})
	return http.ListenAndServe(addr, g)
}

func (g *Gateway) GetObservabilityStats() map[string]interface{} {
	return map[string]interface{}{
		"uptime_seconds": time.Since(g.startTime).Seconds(),
		"start_time":     g.startTime.Format(time.RFC3339),
		"metrics":        g.observable.metricsEnabled,
		"tracing":        g.observable.tracingEnabled,
		"logging":        g.observable.loggingEnabled,
	}
}

func (g *Gateway) SetObservability(metrics, tracing, logging bool) {
	g.observable.metricsEnabled = metrics
	g.observable.tracingEnabled = tracing
	g.observable.loggingEnabled = logging
}

func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(data)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]interface{}{
		"code":    status,
		"message": message,
	})
}
