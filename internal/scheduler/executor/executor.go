package executor

import (
	"context"
	"time"

	"session187/internal/common"
	"session187/internal/scheduler"
	"session187/internal/scheduler/repository"
)

type TaskHandlerFunc func(ctx context.Context, params map[string]interface{}) (map[string]interface{}, error)

type TaskExecutor interface {
	RegisterHandler(name string, handler TaskHandlerFunc)
	Execute(task *scheduler.Task)
	GetHandler(name string) (TaskHandlerFunc, bool)
}

type taskExecutorImpl struct {
	handlers        map[string]TaskHandlerFunc
	executionRepo   repository.ExecutionRepository
	taskRepo        repository.TaskRepository
}

func NewTaskExecutor(
	executionRepo repository.ExecutionRepository,
	taskRepo repository.TaskRepository,
) TaskExecutor {
	return &taskExecutorImpl{
		handlers:      make(map[string]TaskHandlerFunc),
		executionRepo: executionRepo,
		taskRepo:      taskRepo,
	}
}

func (e *taskExecutorImpl) RegisterHandler(name string, handler TaskHandlerFunc) {
	e.handlers[name] = handler
}

func (e *taskExecutorImpl) GetHandler(name string) (TaskHandlerFunc, bool) {
	handler, ok := e.handlers[name]
	return handler, ok
}

func (e *taskExecutorImpl) Execute(task *scheduler.Task) {
	execution := &scheduler.TaskExecution{
		TaskID:    task.ID,
		TenantID:  task.TenantID,
		Status:    "running",
		StartedAt: common.TimeNowUTC(),
	}
	e.executionRepo.Create(execution)
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(task.Timeout)*time.Second)
	defer cancel()
	handler, ok := e.handlers[task.Handler]
	if !ok {
		e.markExecutionFailed(execution, task, "handler not found")
		return
	}
	now := common.TimeNowUTC()
	task.LastRunAt = &now
	e.taskRepo.Update(task)
	var result map[string]interface{}
	err := common.Retry(func() error {
		r, handlerErr := handler(ctx, task.Params)
		if handlerErr == nil {
			result = r
			execution.Result = r
		}
		return handlerErr
	}, task.MaxRetries, time.Second)
	finishedAt := common.TimeNowUTC()
	execution.FinishedAt = &finishedAt
	execution.Duration = finishedAt.Sub(execution.StartedAt).Milliseconds()
	if err != nil {
		e.markExecutionFailed(execution, task, err.Error())
	} else {
		e.markExecutionSuccess(execution, task)
	}
}

func (e *taskExecutorImpl) markExecutionFailed(execution *scheduler.TaskExecution, task *scheduler.Task, errMsg string) {
	execution.Status = "failed"
	execution.Error = &errMsg
	task.LastError = &errMsg
	task.RetryCount++
	e.executionRepo.Update(execution)
	e.taskRepo.Update(task)
}

func (e *taskExecutorImpl) markExecutionSuccess(execution *scheduler.TaskExecution, task *scheduler.Task) {
	execution.Status = "success"
	e.executionRepo.Update(execution)
	e.taskRepo.Update(task)
}
