package async

import (
	"context"
	"sync"
	"time"
)

type TaskResult struct {
	TaskID       string                 `json:"task_id"`
	ExecutionID  string                 `json:"execution_id"`
	TenantID     string                 `json:"tenant_id"`
	Status       string                 `json:"status"`
	Result       map[string]interface{} `json:"result"`
	Error        string                 `json:"error,omitempty"`
	StartTime    time.Time              `json:"start_time"`
	EndTime      time.Time              `json:"end_time"`
	DurationMs   int64                  `json:"duration_ms"`
}

type AsyncTask struct {
	ID          string
	TenantID    string
	HandlerName string
	Params      map[string]interface{}
	Callback    func(result TaskResult)
	Timeout     time.Duration
	Retries     int
}

type EventType string

const (
	EventTaskStarted   EventType = "task.started"
	EventTaskCompleted EventType = "task.completed"
	EventTaskFailed    EventType = "task.failed"
	EventTaskTimeout   EventType = "task.timeout"
)

type Event struct {
	Type      EventType   `json:"type"`
	Timestamp time.Time   `json:"timestamp"`
	Payload   interface{} `json:"payload"`
}

type EventHandler func(event Event)

type EventBus interface {
	Publish(eventType EventType, payload interface{})
	Subscribe(eventType EventType, handler EventHandler)
	Unsubscribe(eventType EventType, handler EventHandler)
}

type eventBus struct {
	mu       sync.RWMutex
	handlers map[EventType][]EventHandler
}

func NewEventBus() EventBus {
	return &eventBus{
		handlers: make(map[EventType][]EventHandler),
	}
}

func (b *eventBus) Publish(eventType EventType, payload interface{}) {
	b.mu.RLock()
	defer b.mu.RUnlock()
	event := Event{
		Type:      eventType,
		Timestamp: time.Now(),
		Payload:   payload,
	}
	for _, handler := range b.handlers[eventType] {
		go handler(event)
	}
}

func (b *eventBus) Subscribe(eventType EventType, handler EventHandler) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.handlers[eventType] = append(b.handlers[eventType], handler)
}

func (b *eventBus) Unsubscribe(eventType EventType, handler EventHandler) {
	b.mu.Lock()
	defer b.mu.Unlock()
	handlers := b.handlers[eventType]
	for i, h := range handlers {
		if &h == &handler {
			b.handlers[eventType] = append(handlers[:i], handlers[i+1:]...)
			break
		}
	}
}

type TaskQueue interface {
	Enqueue(task *AsyncTask) string
	Process(handler func(ctx context.Context, task *AsyncTask) TaskResult)
	Close()
}

type taskQueue struct {
	queue       chan *AsyncTask
	resultStore map[string]TaskResult
	workerCount int
	wg          sync.WaitGroup
	mu          sync.RWMutex
	closeChan   chan struct{}
	eventBus    EventBus
}

func NewTaskQueue(workerCount int, eventBus EventBus) TaskQueue {
	return &taskQueue{
		queue:       make(chan *AsyncTask, 10000),
		resultStore: make(map[string]TaskResult),
		workerCount: workerCount,
		closeChan:   make(chan struct{}),
		eventBus:    eventBus,
	}
}

func (q *taskQueue) Enqueue(task *AsyncTask) string {
	executionID := "exec-" + time.Now().Format("20060102150405") + "-" + task.ID
	select {
	case q.queue <- task:
		return executionID
	case <-time.After(5 * time.Second):
		return ""
	}
}

func (q *taskQueue) Process(handler func(ctx context.Context, task *AsyncTask) TaskResult) {
	for i := 0; i < q.workerCount; i++ {
		q.wg.Add(1)
		go q.worker(handler)
	}
}

func (q *taskQueue) worker(handler func(ctx context.Context, task *AsyncTask) TaskResult) {
	defer q.wg.Done()
	for {
		select {
		case task := <-q.queue:
			q.executeTask(task, handler)
		case <-q.closeChan:
			return
		}
	}
}

func (q *taskQueue) executeTask(task *AsyncTask, handler func(ctx context.Context, task *AsyncTask) TaskResult) {
	startTime := time.Now()
	var ctx context.Context
	var cancel context.CancelFunc
	if task.Timeout > 0 {
		ctx, cancel = context.WithTimeout(context.Background(), task.Timeout)
	} else {
		ctx, cancel = context.WithCancel(context.Background())
	}
	defer cancel()
	resultChan := make(chan TaskResult, 1)
	go func() {
		result := handler(ctx, task)
		result.TaskID = task.ID
		result.TenantID = task.TenantID
		result.StartTime = startTime
		result.EndTime = time.Now()
		result.DurationMs = result.EndTime.Sub(result.StartTime).Milliseconds()
		resultChan <- result
	}()
	select {
	case result := <-resultChan:
		if result.Status == "success" {
			q.eventBus.Publish(EventTaskCompleted, result)
		} else {
			q.eventBus.Publish(EventTaskFailed, result)
		}
		q.storeResult(result)
		if task.Callback != nil {
			go task.Callback(result)
		}
	case <-ctx.Done():
		result := TaskResult{
			TaskID:     task.ID,
			TenantID:   task.TenantID,
			Status:     "timeout",
			Error:      ctx.Err().Error(),
			StartTime:  startTime,
			EndTime:    time.Now(),
			DurationMs: time.Since(startTime).Milliseconds(),
		}
		q.eventBus.Publish(EventTaskTimeout, result)
		q.storeResult(result)
		if task.Callback != nil {
			go task.Callback(result)
		}
	}
}

func (q *taskQueue) storeResult(result TaskResult) {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.resultStore[result.ExecutionID] = result
}

func (q *taskQueue) GetResult(executionID string) (TaskResult, bool) {
	q.mu.RLock()
	defer q.mu.RUnlock()
	result, ok := q.resultStore[executionID]
	return result, ok
}

func (q *taskQueue) Close() {
	close(q.closeChan)
	q.wg.Wait()
	close(q.queue)
}

type AsyncExecutor interface {
	ExecuteAsync(task *AsyncTask) (executionID string, err error)
	GetResult(executionID string) (TaskResult, bool)
	RegisterCallback(executionID string, callback func(result TaskResult))
	WaitForResult(executionID string, timeout time.Duration) (TaskResult, error)
	EventBus() EventBus
	Close()
}

type AsyncExecutorImpl struct {
	queue        TaskQueue
	eventBus     EventBus
	handlers     map[string]func(ctx context.Context, params map[string]interface{}) (map[string]interface{}, error)
	callbacks    map[string][]func(result TaskResult)
	resultChan   map[string]chan TaskResult
	mu           sync.RWMutex
}

func NewAsyncExecutor(workerCount int) AsyncExecutor {
	eventBus := NewEventBus()
	queue := NewTaskQueue(workerCount, eventBus)
	exec := &AsyncExecutorImpl{
		queue:      queue,
		eventBus:   eventBus,
		handlers:   make(map[string]func(ctx context.Context, params map[string]interface{}) (map[string]interface{}, error)),
		callbacks:  make(map[string][]func(result TaskResult)),
		resultChan: make(map[string]chan TaskResult),
	}
	queue.Process(func(ctx context.Context, task *AsyncTask) TaskResult {
		return exec.executeHandler(ctx, task)
	})
	return exec
}

func (e *AsyncExecutorImpl) RegisterHandler(name string, handler func(ctx context.Context, params map[string]interface{}) (map[string]interface{}, error)) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.handlers[name] = handler
}

func (e *AsyncExecutorImpl) executeHandler(ctx context.Context, task *AsyncTask) TaskResult {
	e.mu.RLock()
	handler, ok := e.handlers[task.HandlerName]
	e.mu.RUnlock()
	if !ok {
		return TaskResult{
			Status: "failed",
			Error:  "handler not found: " + task.HandlerName,
		}
	}
	result, err := handler(ctx, task.Params)
	if err != nil {
		return TaskResult{
			Status: "failed",
			Error:  err.Error(),
		}
	}
	return TaskResult{
		Status: "success",
		Result: result,
	}
}

func (e *AsyncExecutorImpl) ExecuteAsync(task *AsyncTask) (string, error) {
	e.mu.RLock()
	_, ok := e.handlers[task.HandlerName]
	e.mu.RUnlock()
	if !ok {
		return "", nil
	}
	executionID := e.queue.Enqueue(task)
	e.eventBus.Publish(EventTaskStarted, map[string]interface{}{
		"task_id":      task.ID,
		"execution_id": executionID,
		"tenant_id":    task.TenantID,
		"handler":      task.HandlerName,
	})
	return executionID, nil
}

func (e *AsyncExecutorImpl) GetResult(executionID string) (TaskResult, bool) {
	return e.queue.(*taskQueue).GetResult(executionID)
}

func (e *AsyncExecutorImpl) RegisterCallback(executionID string, callback func(result TaskResult)) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.callbacks[executionID] = append(e.callbacks[executionID], callback)
	e.eventBus.Subscribe(EventTaskCompleted, func(event Event) {
		if result, ok := event.Payload.(TaskResult); ok && result.ExecutionID == executionID {
			callback(result)
		}
	})
	e.eventBus.Subscribe(EventTaskFailed, func(event Event) {
		if result, ok := event.Payload.(TaskResult); ok && result.ExecutionID == executionID {
			callback(result)
		}
	})
	e.eventBus.Subscribe(EventTaskTimeout, func(event Event) {
		if result, ok := event.Payload.(TaskResult); ok && result.ExecutionID == executionID {
			callback(result)
		}
	})
}

func (e *AsyncExecutorImpl) WaitForResult(executionID string, timeout time.Duration) (TaskResult, error) {
	result, ok := e.GetResult(executionID)
	if ok {
		return result, nil
	}
	ch := make(chan TaskResult, 1)
	e.mu.Lock()
	e.resultChan[executionID] = ch
	e.mu.Unlock()
	var resultHandler EventHandler
	resultHandler = func(event Event) {
		if result, ok := event.Payload.(TaskResult); ok && result.ExecutionID == executionID {
			select {
			case ch <- result:
			default:
			}
		}
	}
	e.eventBus.Subscribe(EventTaskCompleted, resultHandler)
	e.eventBus.Subscribe(EventTaskFailed, resultHandler)
	e.eventBus.Subscribe(EventTaskTimeout, resultHandler)
	defer func() {
		e.eventBus.Unsubscribe(EventTaskCompleted, resultHandler)
		e.eventBus.Unsubscribe(EventTaskFailed, resultHandler)
		e.eventBus.Unsubscribe(EventTaskTimeout, resultHandler)
	}()
	select {
	case result := <-ch:
		return result, nil
	case <-time.After(timeout):
		return TaskResult{}, context.DeadlineExceeded
	}
}

func (e *AsyncExecutorImpl) EventBus() EventBus {
	return e.eventBus
}

func (e *AsyncExecutorImpl) Close() {
	e.queue.Close()
}
