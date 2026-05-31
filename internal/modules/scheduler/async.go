package scheduler

import (
	"context"
	"sync"
	"time"

	"session189/internal/domain"
	"session189/pkg/eventbus"
)

type TaskResult struct {
	TaskID    string
	JobID     string
	Success   bool
	Error     string
	StartedAt time.Time
	EndedAt   time.Time
	Result    interface{}
}

type TaskCallback func(ctx context.Context, result TaskResult)

type AsyncScheduler struct {
	*Scheduler
	bus            eventbus.EventBus
	workerCount    int
	taskQueue      chan *AsyncTask
	wg             sync.WaitGroup
	ctx            context.Context
	cancel         context.CancelFunc
	callbacks      map[string]TaskCallback
	callbacksMu    sync.RWMutex
}

type AsyncTask struct {
	Task     *domain.Task
	JobID    string
	Callback TaskCallback
	CreatedAt time.Time
}

type AsyncOption func(*AsyncScheduler)

func WithWorkerCount(count int) AsyncOption {
	return func(s *AsyncScheduler) {
		s.workerCount = count
	}
}

func WithQueueSize(size int) AsyncOption {
	return func(s *AsyncScheduler) {
		s.taskQueue = make(chan *AsyncTask, size)
	}
}

func NewAsyncScheduler(base *Scheduler, bus eventbus.EventBus, opts ...AsyncOption) *AsyncScheduler {
	ctx, cancel := context.WithCancel(context.Background())
	as := &AsyncScheduler{
		Scheduler:   base,
		bus:         bus,
		workerCount: 5,
		taskQueue:   make(chan *AsyncTask, 100),
		ctx:         ctx,
		cancel:      cancel,
		callbacks:   make(map[string]TaskCallback),
	}

	for _, opt := range opts {
		opt(as)
	}

	as.startWorkers()
	return as
}

func (as *AsyncScheduler) startWorkers() {
	for i := 0; i < as.workerCount; i++ {
		as.wg.Add(1)
		go as.worker(i)
	}
}

func (as *AsyncScheduler) worker(id int) {
	defer as.wg.Done()
	for {
		select {
		case <-as.ctx.Done():
			return
		case asyncTask := <-as.taskQueue:
			as.executeTask(asyncTask)
		}
	}
}

func (as *AsyncScheduler) executeTask(asyncTask *AsyncTask) {
	result := TaskResult{
		TaskID:    asyncTask.Task.ID,
		JobID:     asyncTask.JobID,
		StartedAt: time.Now(),
	}

	as.bus.Publish(as.ctx, eventbus.Event{
		Type:      eventbus.EventTypeTaskCreated,
		Source:    "scheduler.async",
		Timestamp: time.Now().UnixNano(),
		Data:      asyncTask.Task,
	})

	handler := as.getTaskHandler()
	if handler != nil {
		if err := handler(as.ctx, asyncTask.Task); err != nil {
			result.Success = false
			result.Error = err.Error()
			asyncTask.Task.Status = domain.TaskStatusFailed
			asyncTask.Task.ErrorMessage = err.Error()

			as.bus.Publish(as.ctx, eventbus.Event{
				Type:      eventbus.EventTypeTaskFailed,
				Source:    "scheduler.async",
				Timestamp: time.Now().UnixNano(),
				Data:      map[string]interface{}{"task": asyncTask.Task, "error": err.Error()},
			})
		} else {
			result.Success = true
			asyncTask.Task.Status = domain.TaskStatusCompleted
			result.Result = asyncTask.Task.Result

			as.bus.Publish(as.ctx, eventbus.Event{
				Type:      eventbus.EventTypeTaskCompleted,
				Source:    "scheduler.async",
				Timestamp: time.Now().UnixNano(),
				Data:      asyncTask.Task,
			})
		}
	}

	if asyncTask.Task.EndedAt == nil {
		now := time.Now()
		asyncTask.Task.EndedAt = &now
	}
	result.EndedAt = *asyncTask.Task.EndedAt

	if asyncTask.Task.StartedAt == nil {
		asyncTask.Task.StartedAt = &result.StartedAt
	}

	if repo := as.getTaskRepository(); repo != nil {
		_ = repo.Update(as.ctx, asyncTask.Task.ID, asyncTask.Task)
	}

	if asyncTask.Callback != nil {
		go asyncTask.Callback(as.ctx, result)
	}

	as.callbacksMu.RLock()
	if cb, exists := as.callbacks[asyncTask.JobID]; exists {
		go cb(as.ctx, result)
	}
	as.callbacksMu.RUnlock()
}

func (as *AsyncScheduler) TriggerJobAsync(jobID string, createdBy string, callback ...TaskCallback) (string, error) {
	job, err := as.GetJob(jobID)
	if err != nil {
		return "", err
	}

	task := as.createTaskFromJob(job, "manual_trigger_async", createdBy)

	repo := as.getTaskRepository()
	if repo != nil {
		if err := repo.Create(as.ctx, task); err != nil {
			return "", err
		}
	}

	var cb TaskCallback
	if len(callback) > 0 {
		cb = callback[0]
	}

	asyncTask := &AsyncTask{
		Task:      task,
		JobID:     jobID,
		Callback:  cb,
		CreatedAt: time.Now(),
	}

	select {
	case as.taskQueue <- asyncTask:
		return task.ID, nil
	default:
		return "", ErrQueueFull
	}
}

func (as *AsyncScheduler) RegisterCallback(jobID string, callback TaskCallback) {
	as.callbacksMu.Lock()
	defer as.callbacksMu.Unlock()
	as.callbacks[jobID] = callback
}

func (as *AsyncScheduler) UnregisterCallback(jobID string) {
	as.callbacksMu.Lock()
	defer as.callbacksMu.Unlock()
	delete(as.callbacks, jobID)
}

func (as *AsyncScheduler) QueueSize() int {
	return len(as.taskQueue)
}

func (as *AsyncScheduler) WorkerCount() int {
	return as.workerCount
}

func (as *AsyncScheduler) Close() {
	as.cancel()
	close(as.taskQueue)
	as.wg.Wait()
}

func (as *AsyncScheduler) getTaskHandler() TaskHandler {
	if as.taskHandler != nil {
		return as.taskHandler
	}
	return func(ctx context.Context, task *domain.Task) error {
		return nil
	}
}

func (as *AsyncScheduler) getTaskRepository() interface{
	Create(context.Context, *domain.Task) error
	Update(context.Context, string, *domain.Task) error
} {
	type taskRepo interface {
		Create(context.Context, *domain.Task) error
		Update(context.Context, string, *domain.Task) error
	}
	if as.taskRepo != nil {
		if repo, ok := as.taskRepo.(taskRepo); ok {
			return repo
		}
	}
	return nil
}
