package gateway

import (
	"context"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	applogger "session172/internal/logger"
)

type AsyncHandler func(ctx context.Context, request map[string]interface{}) (interface{}, error)

type AsyncRequest struct {
	RequestID   string                 `json:"request_id"`
	TraceID     string                 `json:"trace_id"`
	Path        string                 `json:"path"`
	Method      string                 `json:"method"`
	Payload     map[string]interface{} `json:"payload"`
	CallbackURL string                 `json:"callback_url,omitempty"`
	Status      string                 `json:"status"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
	Result      interface{}            `json:"result,omitempty"`
	Error       string                 `json:"error,omitempty"`
	Handler     AsyncHandler           `json:"-"`
}

type AsyncResponse struct {
	RequestID string `json:"request_id"`
	Status    string `json:"status"`
	Message   string `json:"message"`
}

type AsyncResult struct {
	RequestID string      `json:"request_id"`
	Status    string      `json:"status"`
	Result    interface{} `json:"result,omitempty"`
	Error     string      `json:"error,omitempty"`
	CreatedAt time.Time   `json:"created_at"`
	UpdatedAt time.Time   `json:"updated_at"`
}

type EventHandler func(event AsyncEvent)

type AsyncEvent struct {
	EventType string                 `json:"event_type"`
	RequestID string                 `json:"request_id"`
	TraceID   string                 `json:"trace_id"`
	Timestamp time.Time              `json:"timestamp"`
	Data      map[string]interface{} `json:"data,omitempty"`
}

type AsyncManager struct {
	mu              sync.RWMutex
	requests        map[string]*AsyncRequest
	results         map[string]*AsyncResult
	eventHandlers   []EventHandler
	workerPoolSize  int
	jobQueue        chan *AsyncRequest
	timeout         time.Duration
	maxRetries      int
	shutdown        chan struct{}
	isRunning       bool
}

const (
	StatusPending   = "pending"
	StatusRunning   = "running"
	StatusCompleted = "completed"
	StatusFailed    = "failed"
	StatusTimeout   = "timeout"

	EventRequestStarted  = "request.started"
	EventRequestProgress = "request.progress"
	EventRequestSuccess  = "request.success"
	EventRequestFailed   = "request.failed"
	EventRequestTimeout  = "request.timeout"

	defaultWorkerPoolSize = 10
	defaultQueueSize      = 1000
	defaultTimeout        = 5 * time.Minute
	defaultMaxRetries     = 3
)

var (
	asyncManagerInstance *AsyncManager
	asyncManagerOnce     sync.Once
)

func NewAsyncManager() *AsyncManager {
	asyncManagerOnce.Do(func() {
		am := &AsyncManager{
			requests:       make(map[string]*AsyncRequest),
			results:        make(map[string]*AsyncResult),
			eventHandlers:  make([]EventHandler, 0),
			workerPoolSize: defaultWorkerPoolSize,
			jobQueue:       make(chan *AsyncRequest, defaultQueueSize),
			timeout:        defaultTimeout,
			maxRetries:     defaultMaxRetries,
			shutdown:       make(chan struct{}),
			isRunning:      false,
		}
		am.startWorkers()
		asyncManagerInstance = am
	})
	return asyncManagerInstance
}

func GetAsyncManager() *AsyncManager {
	if asyncManagerInstance == nil {
		return NewAsyncManager()
	}
	return asyncManagerInstance
}

func (am *AsyncManager) startWorkers() {
	if am.isRunning {
		return
	}

	am.isRunning = true
	for i := 0; i < am.workerPoolSize; i++ {
		go am.worker(i)
	}

	applogger.Infof("Async manager started with %d workers", am.workerPoolSize)
}

func (am *AsyncManager) worker(id int) {
	for {
		select {
		case <-am.shutdown:
			applogger.Infof("Worker %d stopped", id)
			return
		case request := <-am.jobQueue:
			am.processRequest(request)
		}
	}
}

func (am *AsyncManager) processRequest(request *AsyncRequest) {
	am.updateRequestStatus(request, StatusRunning)
	am.emitEvent(EventRequestStarted, request, nil)

	ctx, cancel := context.WithTimeout(context.Background(), am.timeout)
	defer cancel()

	done := make(chan struct{})
	var result interface{}
	var err error

	go func() {
		defer close(done)
		for attempt := 0; attempt < am.maxRetries; attempt++ {
			result, err = request.Handler(ctx, request.Payload)
			if err == nil {
				return
			}
			applogger.Warnf("Async request %s attempt %d failed: %v",
				request.RequestID, attempt+1, err)
			time.Sleep(time.Second * time.Duration(attempt+1))
		}
	}()

	select {
	case <-done:
		if err != nil {
			am.handleFailure(request, err)
		} else {
			am.handleSuccess(request, result)
		}
	case <-ctx.Done():
		am.handleTimeout(request)
	}
}

func (am *AsyncManager) handleSuccess(request *AsyncRequest, result interface{}) {
	am.mu.Lock()
	am.results[request.RequestID] = &AsyncResult{
		RequestID: request.RequestID,
		Status:    StatusCompleted,
		Result:    result,
		CreatedAt: request.CreatedAt,
		UpdatedAt: time.Now(),
	}
	am.mu.Unlock()

	am.updateRequestStatus(request, StatusCompleted)
	am.emitEvent(EventRequestSuccess, request, map[string]interface{}{
		"result": result,
	})

	if request.CallbackURL != "" {
		go am.invokeCallback(request, result, nil)
	}

	applogger.Infof("Async request %s completed", request.RequestID)
}

func (am *AsyncManager) handleFailure(request *AsyncRequest, err error) {
	am.mu.Lock()
	am.results[request.RequestID] = &AsyncResult{
		RequestID: request.RequestID,
		Status:    StatusFailed,
		Error:     err.Error(),
		CreatedAt: request.CreatedAt,
		UpdatedAt: time.Now(),
	}
	am.mu.Unlock()

	am.updateRequestStatus(request, StatusFailed)
	am.emitEvent(EventRequestFailed, request, map[string]interface{}{
		"error": err.Error(),
	})

	if request.CallbackURL != "" {
		go am.invokeCallback(request, nil, err)
	}

	applogger.Errorf("Async request %s failed: %v", request.RequestID, err)
}

func (am *AsyncManager) handleTimeout(request *AsyncRequest) {
	am.mu.Lock()
	am.results[request.RequestID] = &AsyncResult{
		RequestID: request.RequestID,
		Status:    StatusTimeout,
		Error:     "request timed out",
		CreatedAt: request.CreatedAt,
		UpdatedAt: time.Now(),
	}
	am.mu.Unlock()

	am.updateRequestStatus(request, StatusTimeout)
	am.emitEvent(EventRequestTimeout, request, nil)

	if request.CallbackURL != "" {
		go am.invokeCallback(request, nil, fmt.Errorf("request timed out"))
	}

	applogger.Warnf("Async request %s timed out", request.RequestID)
}

func (am *AsyncManager) updateRequestStatus(request *AsyncRequest, status string) {
	am.mu.Lock()
	defer am.mu.Unlock()
	request.Status = status
	request.UpdatedAt = time.Now()
}

func (am *AsyncManager) emitEvent(eventType string, request *AsyncRequest, data map[string]interface{}) {
	event := AsyncEvent{
		EventType: eventType,
		RequestID: request.RequestID,
		TraceID:   request.TraceID,
		Timestamp: time.Now(),
		Data:      data,
	}

	for _, handler := range am.eventHandlers {
		go handler(event)
	}
}

func (am *AsyncManager) invokeCallback(request *AsyncRequest, result interface{}, err error) {
	callbackData := map[string]interface{}{
		"request_id": request.RequestID,
		"status":     request.Status,
	}

	if result != nil {
		callbackData["result"] = result
	}
	if err != nil {
		callbackData["error"] = err.Error()
	}

	applogger.Infof("Invoking callback for %s: %s", request.RequestID, request.CallbackURL)
}

func (am *AsyncManager) SubmitAsync(c *gin.Context, handler AsyncHandler) *AsyncResponse {
	traceID := getTraceID(c)

	var payload map[string]interface{}
	if err := c.ShouldBindJSON(&payload); err != nil {
		payload = make(map[string]interface{})
	}

	return am.SubmitAsyncPayload(
		traceID,
		c.Request.URL.Path,
		c.Request.Method,
		payload,
		c.GetHeader("X-Callback-URL"),
		handler,
	)
}

func (am *AsyncManager) SubmitAsyncPayload(
	traceID, path, method string,
	payload map[string]interface{},
	callbackURL string,
	handler AsyncHandler,
) *AsyncResponse {
	if traceID == "" {
		traceID = uuid.New().String()
	}
	if payload == nil {
		payload = make(map[string]interface{})
	}

	request := &AsyncRequest{
		RequestID:   uuid.New().String(),
		TraceID:     traceID,
		Path:        path,
		Method:      method,
		Payload:     payload,
		CallbackURL: callbackURL,
		Status:      StatusPending,
		CreatedAt:   time.Now(),
		UpdatedAt:   time.Now(),
		Handler:     handler,
	}

	am.mu.Lock()
	am.requests[request.RequestID] = request
	am.mu.Unlock()

	select {
	case am.jobQueue <- request:
		am.updateRequestStatus(request, StatusPending)
	default:
		return &AsyncResponse{
			RequestID: request.RequestID,
			Status:    StatusFailed,
			Message:   "Job queue is full",
		}
	}

	return &AsyncResponse{
		RequestID: request.RequestID,
		Status:    StatusPending,
		Message:   "Request accepted for async processing",
	}
}

func (am *AsyncManager) GetResult(requestID string) (*AsyncResult, bool) {
	am.mu.RLock()
	defer am.mu.RUnlock()
	result, exists := am.results[requestID]
	return result, exists
}

func (am *AsyncManager) GetRequest(requestID string) (*AsyncRequest, bool) {
	am.mu.RLock()
	defer am.mu.RUnlock()
	request, exists := am.requests[requestID]
	return request, exists
}

func (am *AsyncManager) CancelRequest(requestID string) error {
	am.mu.Lock()
	defer am.mu.Unlock()

	request, exists := am.requests[requestID]
	if !exists {
		return fmt.Errorf("request not found: %s", requestID)
	}

	if request.Status == StatusPending || request.Status == StatusRunning {
		request.Status = "cancelled"
		request.UpdatedAt = time.Now()
		applogger.Infof("Async request %s cancelled", requestID)
		return nil
	}

	return fmt.Errorf("request already in terminal state: %s", request.Status)
}

func (am *AsyncManager) AddEventHandler(handler EventHandler) {
	am.mu.Lock()
	defer am.mu.Unlock()
	am.eventHandlers = append(am.eventHandlers, handler)
}

func (am *AsyncManager) SetWorkerPoolSize(size int) error {
	if size <= 0 {
		return fmt.Errorf("invalid worker pool size: %d", size)
	}
	am.workerPoolSize = size
	applogger.Infof("Worker pool size set to: %d", size)
	return nil
}

func (am *AsyncManager) SetTimeout(timeout time.Duration) {
	am.mu.Lock()
	defer am.mu.Unlock()
	am.timeout = timeout
}

func (am *AsyncManager) SetMaxRetries(retries int) {
	am.mu.Lock()
	defer am.mu.Unlock()
	am.maxRetries = retries
}

func (am *AsyncManager) GetStats() map[string]interface{} {
	am.mu.RLock()
	defer am.mu.RUnlock()

	pending := 0
	running := 0
	completed := 0
	failed := 0
	timeout := 0

	for _, req := range am.requests {
		switch req.Status {
		case StatusPending:
			pending++
		case StatusRunning:
			running++
		case StatusCompleted:
			completed++
		case StatusFailed:
			failed++
		case StatusTimeout:
			timeout++
		}
	}

	return map[string]interface{}{
		"pending":        pending,
		"running":        running,
		"completed":      completed,
		"failed":         failed,
		"timeout":        timeout,
		"total_requests": len(am.requests),
		"worker_pool":    am.workerPoolSize,
		"queue_size":     len(am.jobQueue),
		"queue_capacity": cap(am.jobQueue),
	}
}

func (am *AsyncManager) Shutdown() {
	close(am.shutdown)
	am.isRunning = false
	applogger.Info("Async manager shutdown completed")
}

func AsyncEndpoint(handler AsyncHandler) gin.HandlerFunc {
	return func(c *gin.Context) {
		am := GetAsyncManager()
		response := am.SubmitAsync(c, handler)

		if response.Status == StatusFailed {
			c.JSON(http.StatusServiceUnavailable, response)
			return
		}

		c.Header("X-Request-ID", response.RequestID)
		c.JSON(http.StatusAccepted, response)
	}
}

func GetAsyncResultEndpoint(c *gin.Context) {
	requestID := c.Param("request_id")
	am := GetAsyncManager()

	result, exists := am.GetResult(requestID)
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{
			"code":    404,
			"message": "Request not found",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": result,
	})
}

func CancelAsyncRequestEndpoint(c *gin.Context) {
	requestID := c.Param("request_id")
	am := GetAsyncManager()

	if err := am.CancelRequest(requestID); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "Request cancelled",
	})
}
