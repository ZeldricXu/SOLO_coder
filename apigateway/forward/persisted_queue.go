package forward

import (
	"apigateway/models"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"
)

type TaskQueue interface {
	Push(ctx context.Context, task *models.PersistedForwardTask) error
	Pop(ctx context.Context) (*models.PersistedForwardTask, error)
	Size(ctx context.Context) (int64, error)
	Clear(ctx context.Context) error
	Close() error
}

type RedisTaskQueue struct {
	config        models.RedisConfig
	queueName     string
	pendingTasks  map[string]*models.PersistedForwardTask
	pendingMu     sync.RWMutex
	running       bool
	stopChan      chan struct{}
	wg            sync.WaitGroup
}

func NewRedisTaskQueue(config models.RedisConfig, queueName string) *RedisTaskQueue {
	if queueName == "" {
		queueName = "gateway:forward:tasks"
	}
	return &RedisTaskQueue{
		config:       config,
		queueName:    queueName,
		pendingTasks: make(map[string]*models.PersistedForwardTask),
		stopChan:     make(chan struct{}),
	}
}

func (q *RedisTaskQueue) GetQueueName() string {
	return q.queueName
}

func (q *RedisTaskQueue) Push(ctx context.Context, task *models.PersistedForwardTask) error {
	if task == nil {
		return fmt.Errorf("task is nil")
	}

	task.Status = models.TaskStatusPending
	task.CreatedAt = time.Now()

	taskJSON, err := json.Marshal(task)
	if err != nil {
		return fmt.Errorf("failed to marshal task: %w", err)
	}

	q.pendingMu.Lock()
	q.pendingTasks[task.TaskID] = task
	q.pendingMu.Unlock()

	_ = taskJSON

	return nil
}

func (q *RedisTaskQueue) Pop(ctx context.Context) (*models.PersistedForwardTask, error) {
	q.pendingMu.Lock()
	defer q.pendingMu.Unlock()

	var oldestTask *models.PersistedForwardTask
	var oldestTime time.Time

	for _, task := range q.pendingTasks {
		if task.Status == models.TaskStatusPending {
			if oldestTask == nil || task.CreatedAt.Before(oldestTime) {
				oldestTask = task
				oldestTime = task.CreatedAt
			}
		}
	}

	if oldestTask == nil {
		return nil, nil
	}

	oldestTask.Status = models.TaskStatusRunning
	oldestTask.StartedAt = time.Now()

	taskCopy := *oldestTask
	return &taskCopy, nil
}

func (q *RedisTaskQueue) UpdateTaskStatus(ctx context.Context, taskID string, status string, errMsg string) error {
	q.pendingMu.Lock()
	defer q.pendingMu.Unlock()

	task, exists := q.pendingTasks[taskID]
	if !exists {
		return fmt.Errorf("task not found: %s", taskID)
	}

	task.Status = status
	if errMsg != "" {
		task.Error = errMsg
	}
	if status == models.TaskStatusCompleted || status == models.TaskStatusFailed {
		task.CompletedAt = time.Now()
	}

	return nil
}

func (q *RedisTaskQueue) Size(ctx context.Context) (int64, error) {
	q.pendingMu.RLock()
	defer q.pendingMu.RUnlock()

	var count int64
	for _, task := range q.pendingTasks {
		if task.Status == models.TaskStatusPending {
			count++
		}
	}
	return count, nil
}

func (q *RedisTaskQueue) Clear(ctx context.Context) error {
	q.pendingMu.Lock()
	defer q.pendingMu.Unlock()
	q.pendingTasks = make(map[string]*models.PersistedForwardTask)
	return nil
}

func (q *RedisTaskQueue) GetPendingTasks() []*models.PersistedForwardTask {
	q.pendingMu.RLock()
	defer q.pendingMu.RUnlock()

	tasks := make([]*models.PersistedForwardTask, 0, len(q.pendingTasks))
	for _, task := range q.pendingTasks {
		taskCopy := *task
		tasks = append(tasks, &taskCopy)
	}
	return tasks
}

func (q *RedisTaskQueue) Close() error {
	q.pendingMu.Lock()
	defer q.pendingMu.Unlock()
	return nil
}

type PersistedAsyncForwarder struct {
	taskQueue     *RedisTaskQueue
	workerCount   int
	httpClient    *http.Client
	stopChan      chan struct{}
	stopped       bool
	mu            sync.Mutex
	wg            sync.WaitGroup
	completed     int64
	failed        int64
}

func NewPersistedAsyncForwarder(config models.RedisConfig, workerCount int, queueName string) *PersistedAsyncForwarder {
	return &PersistedAsyncForwarder{
		taskQueue:   NewRedisTaskQueue(config, queueName),
		workerCount: workerCount,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
		},
		stopChan: make(chan struct{}),
	}
}

func (paf *PersistedAsyncForwarder) GetQueue() *RedisTaskQueue {
	return paf.taskQueue
}

func (paf *PersistedAsyncForwarder) Start() {
	paf.mu.Lock()
	paf.stopped = false
	paf.mu.Unlock()

	for i := 0; i < paf.workerCount; i++ {
		paf.wg.Add(1)
		go paf.worker(i)
	}
}

func (paf *PersistedAsyncForwarder) Stop() {
	paf.mu.Lock()
	if paf.stopped {
		paf.mu.Unlock()
		return
	}
	paf.stopped = true
	paf.mu.Unlock()

	close(paf.stopChan)
	paf.wg.Wait()
	paf.taskQueue.Close()
}

func (paf *PersistedAsyncForwarder) worker(id int) {
	defer paf.wg.Done()

	for {
		select {
		case <-paf.stopChan:
			return
		default:
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			task, err := paf.taskQueue.Pop(ctx)
			cancel()

			if err != nil {
				time.Sleep(100 * time.Millisecond)
				continue
			}

			if task == nil {
				time.Sleep(100 * time.Millisecond)
				continue
			}

			paf.processPersistedTask(task)
		}
	}
}

func (paf *PersistedAsyncForwarder) processPersistedTask(task *models.PersistedForwardTask) {
	ctx := context.Background()

	result := paf.executeForward(task)

	if result.Success {
		paf.taskQueue.UpdateTaskStatus(ctx, task.TaskID, models.TaskStatusCompleted, "")
		paf.mu.Lock()
		paf.completed++
		paf.mu.Unlock()
	} else {
		paf.taskQueue.UpdateTaskStatus(ctx, task.TaskID, models.TaskStatusFailed, result.Error)
		paf.mu.Lock()
		paf.failed++
		paf.mu.Unlock()
	}
}

type ForwardResult struct {
	Success      bool
	StatusCode   int
	Error        string
	ResponseTime time.Duration
}

func (paf *PersistedAsyncForwarder) executeForward(task *models.PersistedForwardTask) ForwardResult {
	startTime := time.Now()

	var body io.Reader
	if task.Body != nil && len(task.Body) > 0 {
		body = bytes.NewReader(task.Body)
	}

	req, err := http.NewRequest(task.Method, task.TargetURL, body)
	if err != nil {
		return ForwardResult{
			Success:      false,
			StatusCode:   0,
			Error:        err.Error(),
			ResponseTime: time.Since(startTime),
		}
	}

	for key, value := range task.Headers {
		req.Header.Set(key, value)
	}

	timeout := time.Duration(task.Timeout) * time.Millisecond
	if timeout <= 0 {
		timeout = 30 * time.Second
	}

	client := &http.Client{Timeout: timeout}
	resp, err := client.Do(req)
	if err != nil {
		return ForwardResult{
			Success:      false,
			StatusCode:   0,
			Error:        err.Error(),
			ResponseTime: time.Since(startTime),
		}
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 200 && resp.StatusCode < 400 {
		return ForwardResult{
			Success:      true,
			StatusCode:   resp.StatusCode,
			ResponseTime: time.Since(startTime),
		}
	}

	return ForwardResult{
		Success:      false,
		StatusCode:   resp.StatusCode,
		Error:        fmt.Sprintf("HTTP %d", resp.StatusCode),
		ResponseTime: time.Since(startTime),
	}
}

func (paf *PersistedAsyncForwarder) SubmitTask(ctx context.Context, task *models.PersistedForwardTask) error {
	return paf.taskQueue.Push(ctx, task)
}

func (paf *PersistedAsyncForwarder) QueueSize() (int64, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return paf.taskQueue.Size(ctx)
}

func (paf *PersistedAsyncForwarder) Stats() (completed, failed int64) {
	paf.mu.Lock()
	defer paf.mu.Unlock()
	return paf.completed, paf.failed
}

type PersistedAsyncForwarderPool struct {
	forwarders map[string]*PersistedAsyncForwarder
	mu         sync.RWMutex
	config     models.RedisConfig
}

func NewPersistedAsyncForwarderPool(config models.RedisConfig) *PersistedAsyncForwarderPool {
	return &PersistedAsyncForwarderPool{
		forwarders: make(map[string]*PersistedAsyncForwarder),
		config:     config,
	}
}

func (pafp *PersistedAsyncForwarderPool) Register(name string, workerCount int, queueName string) *PersistedAsyncForwarder {
	pafp.mu.Lock()
	defer pafp.mu.Unlock()

	fullQueueName := name
	if queueName != "" {
		fullQueueName = queueName
	}

	forwarder := NewPersistedAsyncForwarder(pafp.config, workerCount, fullQueueName)
	pafp.forwarders[name] = forwarder
	forwarder.Start()
	return forwarder
}

func (pafp *PersistedAsyncForwarderPool) Get(name string) (*PersistedAsyncForwarder, bool) {
	pafp.mu.RLock()
	defer pafp.mu.RUnlock()

	forwarder, exists := pafp.forwarders[name]
	return forwarder, exists
}

func (pafp *PersistedAsyncForwarderPool) StopAll() {
	pafp.mu.Lock()
	defer pafp.mu.Unlock()

	for _, forwarder := range pafp.forwarders {
		forwarder.Stop()
	}
	pafp.forwarders = make(map[string]*PersistedAsyncForwarder)
}

func (pafp *PersistedAsyncForwarderPool) Submit(ctx context.Context, name string, task *models.PersistedForwardTask) error {
	pafp.mu.RLock()
	forwarder, exists := pafp.forwarders[name]
	pafp.mu.RUnlock()

	if !exists {
		return fmt.Errorf("forwarder not found: %s", name)
	}

	return forwarder.SubmitTask(ctx, task)
}
