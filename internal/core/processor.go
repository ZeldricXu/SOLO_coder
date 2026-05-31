package core

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/solocoder/backup-engine/internal/logger"
	"github.com/solocoder/backup-engine/pkg/common"
)

type Operation string

const (
	OpBackup       Operation = "backup"
	OpRestore      Operation = "restore"
	OpList         Operation = "list"
	OpDelete       Operation = "delete"
	OpCleanup      Operation = "cleanup"
	OpDetectAnomaly Operation = "detect_anomaly"
	OpHealthCheck  Operation = "health_check"
)

type HandlerFunc func(ctx context.Context, req *common.Request) (*common.Response, error)

type CircuitBreaker struct {
	name              string
	failureThreshold  int
	resetTimeout      time.Duration
	failures          int
	lastFailureTime   time.Time
	state             string
	mu                sync.RWMutex
}

const (
	StateClosed   = "closed"
	StateOpen     = "open"
	StateHalfOpen = "half_open"
)

func NewCircuitBreaker(name string, failureThreshold int, resetTimeout time.Duration) *CircuitBreaker {
	return &CircuitBreaker{
		name:             name,
		failureThreshold: failureThreshold,
		resetTimeout:     resetTimeout,
		state:            StateClosed,
	}
}

func (cb *CircuitBreaker) Allow() bool {
	cb.mu.RLock()
	state := cb.state
	lastFailure := cb.lastFailureTime
	cb.mu.RUnlock()

	if state == StateOpen {
		if time.Since(lastFailure) > cb.resetTimeout {
			cb.mu.Lock()
			cb.state = StateHalfOpen
			cb.mu.Unlock()
			logger.Info("Circuit breaker half-open", map[string]interface{}{"name": cb.name})
			return true
		}
		return false
	}
	return true
}

func (cb *CircuitBreaker) Success() {
	cb.mu.Lock()
	defer cb.mu.Unlock()
	cb.failures = 0
	if cb.state != StateClosed {
		logger.Info("Circuit breaker closed", map[string]interface{}{"name": cb.name})
	}
	cb.state = StateClosed
}

func (cb *CircuitBreaker) Failure() {
	cb.mu.Lock()
	defer cb.mu.Unlock()
	cb.failures++
	cb.lastFailureTime = time.Now()
	if cb.failures >= cb.failureThreshold && cb.state != StateOpen {
		cb.state = StateOpen
		logger.Warn("Circuit breaker opened", map[string]interface{}{
			"name":     cb.name,
			"failures": cb.failures,
		})
	}
}

func (cb *CircuitBreaker) State() string {
	cb.mu.RLock()
	defer cb.mu.RUnlock()
	return cb.state
}

type Processor struct {
	handlers       map[Operation]HandlerFunc
	circuitBreakers map[string]*CircuitBreaker
	mu             sync.RWMutex
	timeout        time.Duration
	maxRetries     int
	metrics        *ProcessorMetrics
}

type ProcessorMetrics struct {
	TotalRequests   int64
	SuccessRequests int64
	FailedRequests  int64
	TotalDuration   time.Duration
	mu              sync.Mutex
}

func NewProcessor() *Processor {
	return &Processor{
		handlers:        make(map[Operation]HandlerFunc),
		circuitBreakers: make(map[string]*CircuitBreaker),
		timeout:         30 * time.Second,
		maxRetries:      3,
		metrics:         &ProcessorMetrics{},
	}
}

func (p *Processor) SetTimeout(timeout time.Duration) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.timeout = timeout
}

func (p *Processor) SetMaxRetries(retries int) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.maxRetries = retries
}

func (p *Processor) RegisterHandler(op Operation, handler HandlerFunc) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.handlers[op] = handler
	logger.Info("Registered handler", map[string]interface{}{"operation": op})
}

func (p *Processor) RegisterCircuitBreaker(name string, cb *CircuitBreaker) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.circuitBreakers[name] = cb
}

func (p *Processor) Process(ctx context.Context, req *common.Request) *common.Response {
	startTime := time.Now()

	if req.ID == "" {
		req.ID = common.NewID()
	}
	if req.TraceID == "" {
		req.TraceID = common.GenerateTraceID()
	}
	if req.Timestamp.IsZero() {
		req.Timestamp = time.Now()
	}

	logger.Info("Processing request", map[string]interface{}{
		"request_id": req.ID,
		"trace_id":   req.TraceID,
		"operation":  req.Operation,
	})

	resp := &common.Response{
		RequestID: req.ID,
		TraceID:   req.TraceID,
		Headers:   make(map[string]string),
	}

	p.mu.RLock()
	handler, exists := p.handlers[Operation(req.Operation)]
	timeout := p.timeout
	maxRetries := p.maxRetries
	p.mu.RUnlock()

	if !exists {
		resp.Success = false
		resp.Code = 404
		resp.Message = fmt.Sprintf("operation %s not found", req.Operation)
		resp.Error = common.ErrInvalidInput.Error()
		resp.Duration = time.Since(startTime)
		return resp
	}

	if cbName := req.Headers["circuit_breaker"]; cbName != "" {
		p.mu.RLock()
		cb, cbExists := p.circuitBreakers[cbName]
		p.mu.RUnlock()
		if cbExists && !cb.Allow() {
			resp.Success = false
			resp.Code = 503
			resp.Message = "service unavailable"
			resp.Error = common.ErrCircuitBreakerOpen.Error()
			resp.Duration = time.Since(startTime)
			p.recordMetrics(false, resp.Duration)
			return resp
		}
	}

	reqTimeout := timeout
	if req.Timeout > 0 {
		reqTimeout = req.Timeout
	}

	procCtx, cancel := context.WithTimeout(ctx, reqTimeout)
	defer cancel()

	var handlerResp *common.Response
	var handlerErr error

	err := common.Retry(maxRetries, 100*time.Millisecond, func() error {
		select {
		case <-procCtx.Done():
			return procCtx.Err()
		default:
		}

		handlerResp, handlerErr = handler(procCtx, req)
		return handlerErr
	})

	if err != nil {
		resp.Success = false
		if err == context.DeadlineExceeded {
			resp.Code = 408
			resp.Message = "request timed out"
			logger.Warn("Request timed out", map[string]interface{}{
				"request_id": req.ID,
				"operation":  req.Operation,
				"timeout":    reqTimeout,
			})
		} else if err == common.ErrCircuitBreakerOpen {
			resp.Code = 503
			resp.Message = "service unavailable"
		} else {
			resp.Code = 500
			resp.Message = "internal error"
		}
		resp.Error = err.Error()

		if cbName := req.Headers["circuit_breaker"]; cbName != "" {
			p.mu.RLock()
			cb, cbExists := p.circuitBreakers[cbName]
			p.mu.RUnlock()
			if cbExists {
				cb.Failure()
			}
		}
	} else {
		resp = handlerResp
		resp.RequestID = req.ID
		resp.TraceID = req.TraceID

		if cbName := req.Headers["circuit_breaker"]; cbName != "" {
			p.mu.RLock()
			cb, cbExists := p.circuitBreakers[cbName]
			p.mu.RUnlock()
			if cbExists {
				cb.Success()
			}
		}
	}

	resp.Duration = time.Since(startTime)
	resp.Headers["X-Processed-At"] = time.Now().Format(time.RFC3339)

	p.recordMetrics(resp.Success, resp.Duration)

	if resp.Success {
		logger.Info("Request completed successfully", map[string]interface{}{
			"request_id": req.ID,
			"trace_id":   req.TraceID,
			"operation":  req.Operation,
			"duration":   common.FormatDuration(resp.Duration),
		})
	} else {
		logger.Error("Request failed", map[string]interface{}{
			"request_id": req.ID,
			"trace_id":   req.TraceID,
			"operation":  req.Operation,
			"error":      resp.Error,
			"duration":   common.FormatDuration(resp.Duration),
		})
	}

	return resp
}

func (p *Processor) recordMetrics(success bool, duration time.Duration) {
	p.metrics.mu.Lock()
	defer p.metrics.mu.Unlock()
	p.metrics.TotalRequests++
	if success {
		p.metrics.SuccessRequests++
	} else {
		p.metrics.FailedRequests++
	}
	p.metrics.TotalDuration += duration
}

func (p *Processor) GetMetrics() map[string]interface{} {
	p.metrics.mu.Lock()
	defer p.metrics.mu.Unlock()

	avgDuration := time.Duration(0)
	if p.metrics.TotalRequests > 0 {
		avgDuration = p.metrics.TotalDuration / time.Duration(p.metrics.TotalRequests)
	}

	return map[string]interface{}{
		"total_requests":   p.metrics.TotalRequests,
		"success_requests": p.metrics.SuccessRequests,
		"failed_requests":  p.metrics.FailedRequests,
		"success_rate":     float64(p.metrics.SuccessRequests) / float64(common.Max(1, int(p.metrics.TotalRequests))),
		"avg_duration_ms":  avgDuration.Milliseconds(),
	}
}

func (p *Processor) HealthCheck() *common.Response {
	metrics := p.GetMetrics()
	data, _ := json.Marshal(metrics)
	return &common.Response{
		Success: true,
		Code:    200,
		Message: "healthy",
		Data:    string(data),
	}
}

type DegradedResponse struct {
	Message    string      `json:"message"`
	CacheData  interface{} `json:"cache_data,omitempty"`
	RetryAfter int         `json:"retry_after,omitempty"`
}

func (p *Processor) Degrade(req *common.Request, cacheData interface{}) *common.Response {
	degraded := &DegradedResponse{
		Message:    "Service is currently degraded, returning cached data",
		CacheData:  cacheData,
		RetryAfter: 60,
	}

	return &common.Response{
		RequestID: req.ID,
		TraceID:   req.TraceID,
		Success:   false,
		Code:      503,
		Message:   "service degraded",
		Data:      degraded,
		Duration:  time.Since(req.Timestamp),
		Error:     "service unavailable, using fallback",
	}
}
