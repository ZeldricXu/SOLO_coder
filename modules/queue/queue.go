package queue

import (
	"context"
	"socialfeed/models"
	"sync"
	"time"
)

type NotificationService interface {
	SendNotification(ctx context.Context, notification *models.Notification) error
}

type TaskType string

const (
	TaskTypePush  TaskType = "push"
	TaskTypeAudit TaskType = "audit"
)

type Task interface {
	GetID() string
	GetType() TaskType
	GetPayload() interface{}
	GetCreatedAt() time.Time
	SetStatus(status TaskStatus)
	GetStatus() TaskStatus
	GetRetryCount() int
	IncrementRetryCount()
	GetMaxRetries() int
}

type TaskStatus string

const (
	TaskStatusPending   TaskStatus = "pending"
	TaskStatusRunning   TaskStatus = "running"
	TaskStatusCompleted TaskStatus = "completed"
	TaskStatusFailed    TaskStatus = "failed"
	TaskStatusRetry     TaskStatus = "retry"
)

type BaseTask struct {
	ID          string      `json:"id"`
	Type        TaskType    `json:"type"`
	Payload     interface{} `json:"payload"`
	CreatedAt   time.Time   `json:"created_at"`
	Status      TaskStatus  `json:"status"`
	RetryCount  int         `json:"retry_count"`
	MaxRetries  int         `json:"max_retries"`
}

func (t *BaseTask) GetID() string {
	return t.ID
}

func (t *BaseTask) GetType() TaskType {
	return t.Type
}

func (t *BaseTask) GetPayload() interface{} {
	return t.Payload
}

func (t *BaseTask) GetCreatedAt() time.Time {
	return t.CreatedAt
}

func (t *BaseTask) SetStatus(status TaskStatus) {
	t.Status = status
}

func (t *BaseTask) GetStatus() TaskStatus {
	return t.Status
}

func (t *BaseTask) GetRetryCount() int {
	return t.RetryCount
}

func (t *BaseTask) IncrementRetryCount() {
	t.RetryCount++
}

func (t *BaseTask) GetMaxRetries() int {
	return t.MaxRetries
}

type PushTaskPayload struct {
	PostID    string   `json:"post_id"`
	AuthorID  string   `json:"author_id"`
	Followers []string `json:"followers"`
}

type AuditTaskPayload struct {
	PostID   string `json:"post_id"`
	UserID   string `json:"user_id"`
	Content  string `json:"content"`
	Media    []string `json:"media,omitempty"`
}

type Queue interface {
	Enqueue(ctx context.Context, task Task) error
	Dequeue(ctx context.Context, taskType TaskType) (Task, error)
	Ack(ctx context.Context, taskID string) error
	Nack(ctx context.Context, taskID string, requeue bool) error
	Len(ctx context.Context, taskType TaskType) (int, error)
	Close() error
}

type InMemoryQueue struct {
	queues map[TaskType]chan Task
	mu     sync.RWMutex
	closed bool
}

func NewInMemoryQueue(bufferSize int) *InMemoryQueue {
	return &InMemoryQueue{
		queues: map[TaskType]chan Task{
			TaskTypePush:  make(chan Task, bufferSize),
			TaskTypeAudit: make(chan Task, bufferSize),
		},
		closed: false,
	}
}

func (q *InMemoryQueue) Enqueue(ctx context.Context, task Task) error {
	q.mu.RLock()
	defer q.mu.RUnlock()

	if q.closed {
		return ErrQueueClosed
	}

	queue, ok := q.queues[task.GetType()]
	if !ok {
		return ErrInvalidTaskType
	}

	select {
	case queue <- task:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (q *InMemoryQueue) Dequeue(ctx context.Context, taskType TaskType) (Task, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()

	if q.closed {
		return nil, ErrQueueClosed
	}

	queue, ok := q.queues[taskType]
	if !ok {
		return nil, ErrInvalidTaskType
	}

	select {
	case task := <-queue:
		return task, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (q *InMemoryQueue) Ack(ctx context.Context, taskID string) error {
	return nil
}

func (q *InMemoryQueue) Nack(ctx context.Context, taskID string, requeue bool) error {
	return nil
}

func (q *InMemoryQueue) Len(ctx context.Context, taskType TaskType) (int, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()

	if q.closed {
		return 0, ErrQueueClosed
	}

	queue, ok := q.queues[taskType]
	if !ok {
		return 0, ErrInvalidTaskType
	}

	return len(queue), nil
}

func (q *InMemoryQueue) Close() error {
	q.mu.Lock()
	defer q.mu.Unlock()

	if q.closed {
		return nil
	}

	q.closed = true
	for _, ch := range q.queues {
		close(ch)
	}
	return nil
}

var (
	ErrQueueClosed      = &QueueError{Message: "queue is closed"}
	ErrInvalidTaskType  = &QueueError{Message: "invalid task type"}
	ErrTaskNotFound     = &QueueError{Message: "task not found"}
)

type QueueError struct {
	Message string
}

func (e *QueueError) Error() string {
	return e.Message
}

type TaskHandler interface {
	Handle(ctx context.Context, task Task) error
}

type WorkerPool struct {
	queue        Queue
	handlers     map[TaskType]TaskHandler
	workerCount  int
	stopCh       chan struct{}
	wg           sync.WaitGroup
}

func NewWorkerPool(queue Queue, workerCount int) *WorkerPool {
	return &WorkerPool{
		queue:       queue,
		handlers:    make(map[TaskType]TaskHandler),
		workerCount: workerCount,
		stopCh:      make(chan struct{}),
	}
}

func (p *WorkerPool) RegisterHandler(taskType TaskType, handler TaskHandler) {
	p.handlers[taskType] = handler
}

func (p *WorkerPool) Start(ctx context.Context) {
	for i := 0; i < p.workerCount; i++ {
		p.wg.Add(1)
		go p.worker(ctx, i)
	}
}

func (p *WorkerPool) worker(ctx context.Context, workerID int) {
	defer p.wg.Done()

	for {
		select {
		case <-p.stopCh:
			return
		case <-ctx.Done():
			return
		default:
			for taskType, handler := range p.handlers {
				task, err := p.queue.Dequeue(ctx, taskType)
				if err != nil {
					if err == context.Canceled || err == context.DeadlineExceeded {
						return
					}
					continue
				}

				if task == nil {
					continue
				}

				task.SetStatus(TaskStatusRunning)

				err = handler.Handle(ctx, task)
				if err != nil {
					task.IncrementRetryCount()
					if task.GetRetryCount() < task.GetMaxRetries() {
						task.SetStatus(TaskStatusRetry)
						requeueErr := p.queue.Enqueue(ctx, task)
						if requeueErr != nil {
							task.SetStatus(TaskStatusFailed)
						}
					} else {
						task.SetStatus(TaskStatusFailed)
					}
				} else {
					task.SetStatus(TaskStatusCompleted)
				}
			}
		}
	}
}

func (p *WorkerPool) Stop() {
	close(p.stopCh)
	p.wg.Wait()
}

type QueueManager struct {
	pushQueue   Queue
	auditQueue  Queue
	pushWorker  *WorkerPool
	auditWorker *WorkerPool
}

func NewQueueManager(pushQueue Queue, auditQueue Queue, pushWorkerCount, auditWorkerCount int) *QueueManager {
	qm := &QueueManager{
		pushQueue:  pushQueue,
		auditQueue: auditQueue,
	}

	if pushQueue != nil {
		qm.pushWorker = NewWorkerPool(pushQueue, pushWorkerCount)
	}

	if auditQueue != nil {
		qm.auditWorker = NewWorkerPool(auditQueue, auditWorkerCount)
	}

	return qm
}

func (qm *QueueManager) RegisterPushHandler(handler TaskHandler) {
	if qm.pushWorker != nil {
		qm.pushWorker.RegisterHandler(TaskTypePush, handler)
	}
}

func (qm *QueueManager) RegisterAuditHandler(handler TaskHandler) {
	if qm.auditWorker != nil {
		qm.auditWorker.RegisterHandler(TaskTypeAudit, handler)
	}
}

func (qm *QueueManager) Start(ctx context.Context) {
	if qm.pushWorker != nil {
		qm.pushWorker.Start(ctx)
	}
	if qm.auditWorker != nil {
		qm.auditWorker.Start(ctx)
	}
}

func (qm *QueueManager) Stop() {
	if qm.pushWorker != nil {
		qm.pushWorker.Stop()
	}
	if qm.auditWorker != nil {
		qm.auditWorker.Stop()
	}
}

func (qm *QueueManager) EnqueuePushTask(ctx context.Context, task Task) error {
	if qm.pushQueue == nil {
		return ErrInvalidTaskType
	}
	return qm.pushQueue.Enqueue(ctx, task)
}

func (qm *QueueManager) EnqueueAuditTask(ctx context.Context, task Task) error {
	if qm.auditQueue == nil {
		return ErrInvalidTaskType
	}
	return qm.auditQueue.Enqueue(ctx, task)
}
