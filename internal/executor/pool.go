package executor

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/distributed-task-scheduler/internal/config"
	"github.com/distributed-task-scheduler/internal/models"
	"github.com/distributed-task-scheduler/internal/storage"
)

type TaskHandler func(ctx context.Context, task *models.Task, executionID string) ([]byte, error)

type ExecutorPool struct {
	db              *storage.Database
	cfg             config.ExecutorConfig
	nodeID          string
	handler         TaskHandler
	workerChan      chan *workerJob
	wg              sync.WaitGroup
	ctx             context.Context
	cancel          context.CancelFunc
	runningTasks    sync.Map
	namespaceSem    map[string]chan struct{}
	namespaceMutex  sync.RWMutex
}

type workerJob struct {
	Task      *models.Task
	ExecutionID string
}

func NewExecutorPool(db *storage.Database, cfg config.ExecutorConfig, nodeID string, handler TaskHandler) *ExecutorPool {
	ctx, cancel := context.WithCancel(context.Background())
	pool := &ExecutorPool{
		db:           db,
		cfg:          cfg,
		nodeID:       nodeID,
		handler:      handler,
		workerChan:   make(chan *workerJob, cfg.WorkerPoolSize*2),
		ctx:          ctx,
		cancel:       cancel,
		namespaceSem: make(map[string]chan struct{}),
	}

	pool.startWorkers()
	return pool
}

func (p *ExecutorPool) startWorkers() {
	for i := 0; i < p.cfg.WorkerPoolSize; i++ {
		p.wg.Add(1)
		go p.worker(i)
	}
}

func (p *ExecutorPool) worker(id int) {
	defer p.wg.Done()

	for {
		select {
		case <-p.ctx.Done():
			return
		case job, ok := <-p.workerChan:
			if !ok {
				return
			}
			p.executeJob(job)
		}
	}
}

func (p *ExecutorPool) executeJob(job *workerJob) {
	if p.cfg.IsolationStrategy == "namespace" {
		sem := p.getNamespaceSemaphore(job.Task.Namespace)
		select {
		case sem <- struct{}{}:
			defer func() { <-sem }()
		default:
			p.updateExecutionStatus(job.ExecutionID, models.ExecutionStatusFailed, nil, "namespace concurrency limit reached")
			return
		}
	}

	p.runningTasks.Store(job.ExecutionID, true)
	defer p.runningTasks.Delete(job.ExecutionID)

	p.updateExecutionStatus(job.ExecutionID, models.ExecutionStatusRunning, nil, "")

	ctx, cancel := context.WithTimeout(p.ctx, p.cfg.TaskTimeout)
	defer cancel()

	startTime := time.Now()
	output, err := p.handler(ctx, job.Task, job.ExecutionID)
	duration := time.Since(startTime).Milliseconds()

	endTime := time.Now()
	if err != nil {
		p.updateExecutionStatusWithTime(job.ExecutionID, models.ExecutionStatusFailed, output, err.Error(), &startTime, &endTime, duration)
	} else {
		p.updateExecutionStatusWithTime(job.ExecutionID, models.ExecutionStatusSuccess, output, "", &startTime, &endTime, duration)
	}
}

func (p *ExecutorPool) Submit(task *models.Task, executionID string) error {
	select {
	case <-p.ctx.Done():
		return fmt.Errorf("pool is shutting down")
	default:
	}

	select {
	case p.workerChan <- &workerJob{Task: task, ExecutionID: executionID}:
		return nil
	default:
		return fmt.Errorf("worker pool is full")
	}
}

func (p *ExecutorPool) updateExecutionStatus(executionID string, status models.ExecutionStatus, output []byte, errorMsg string) {
	query := `
		UPDATE executions 
		SET status = $2, output_payload = $3, error_message = $4, node_id = $5
		WHERE id = $1
	`
	_, err := p.db.Exec(query, executionID, status, output, errorMsg, p.nodeID)
	if err != nil {
		fmt.Printf("Failed to update execution status: %v\n", err)
	}
}

func (p *ExecutorPool) updateExecutionStatusWithTime(executionID string, status models.ExecutionStatus, output []byte, errorMsg string, startTime, endTime *time.Time, duration int64) {
	query := `
		UPDATE executions 
		SET status = $2, output_payload = $3, error_message = $4, node_id = $5,
			start_time = $6, end_time = $7, duration_ms = $8
		WHERE id = $1
	`
	_, err := p.db.Exec(query, executionID, status, output, errorMsg, p.nodeID, startTime, endTime, duration)
	if err != nil {
		fmt.Printf("Failed to update execution status: %v\n", err)
	}
}

func (p *ExecutorPool) getNamespaceSemaphore(namespace string) chan struct{} {
	p.namespaceMutex.RLock()
	sem, exists := p.namespaceSem[namespace]
	p.namespaceMutex.RUnlock()

	if exists {
		return sem
	}

	p.namespaceMutex.Lock()
	defer p.namespaceMutex.Unlock()

	sem, exists = p.namespaceSem[namespace]
	if !exists {
		sem = make(chan struct{}, p.cfg.MaxConcurrency)
		p.namespaceSem[namespace] = sem
	}

	return sem
}

func (p *ExecutorPool) Shutdown() {
	p.cancel()

	done := make(chan struct{})
	go func() {
		p.wg.Wait()
		close(done)
	}()

	select {
	case <-done:
		fmt.Println("All tasks completed gracefully")
	case <-time.After(p.cfg.GracefulShutdown):
		fmt.Println("Graceful shutdown timeout, some tasks may be interrupted")
	}
}

func (p *ExecutorPool) RunningCount() int {
	count := 0
	p.runningTasks.Range(func(_, _ interface{}) bool {
		count++
		return true
	})
	return count
}
