package service

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/robfig/cron/v3"
	"session187/internal/scheduler"
	schedulerAsync "session187/internal/scheduler/async"
	"session187/internal/scheduler/executor"
	"session187/internal/scheduler/repository"
	"session187/pkg/errors"
)

type SchedulerService interface {
	RegisterHandler(name string, handler executor.TaskHandlerFunc)
	CreateTask(tenantID, name string, taskType scheduler.TaskType, cronExpr string, interval int, handler string, params map[string]interface{}) (*scheduler.Task, error)
	GetTask(tenantID, taskID string) (*scheduler.Task, error)
	ListTasks(tenantID string) ([]scheduler.Task, error)
	StartTask(tenantID, taskID string) error
	StopTask(tenantID, taskID string) error
	DeleteTask(tenantID, taskID string) error
	GetTaskExecutions(tenantID, taskID string, limit int) ([]scheduler.TaskExecution, error)
	Start()
	Stop()
	ExecuteTaskAsync(tenantID, taskID string, callback func(result schedulerAsync.TaskResult)) (string, error)
	ExecuteHandlerAsync(tenantID, handlerName string, params map[string]interface{}, callback func(result schedulerAsync.TaskResult), timeout time.Duration) (string, error)
	GetTaskResult(executionID string) (schedulerAsync.TaskResult, bool)
	WaitForTaskResult(executionID string, timeout time.Duration) (schedulerAsync.TaskResult, error)
	GetEventBus() schedulerAsync.EventBus
}

type schedulerServiceImpl struct {
	taskRepo      repository.TaskRepository
	executionRepo repository.ExecutionRepository
	executor      executor.TaskExecutor
	asyncExecutor schedulerAsync.AsyncExecutor
	cron          *cron.Cron
	entries       map[string]cron.EntryID
	intervalTasks map[string]*time.Ticker
	mu            sync.RWMutex
}

func NewSchedulerService(
	taskRepo repository.TaskRepository,
	executionRepo repository.ExecutionRepository,
	executor executor.TaskExecutor,
	asyncExecutor schedulerAsync.AsyncExecutor,
) SchedulerService {
	return &schedulerServiceImpl{
		taskRepo:      taskRepo,
		executionRepo: executionRepo,
		executor:      executor,
		asyncExecutor: asyncExecutor,
		cron:          cron.New(),
		entries:       make(map[string]cron.EntryID),
		intervalTasks: make(map[string]*time.Ticker),
	}
}

func (s *schedulerServiceImpl) GetEventBus() schedulerAsync.EventBus {
	return s.asyncExecutor.EventBus()
}

func (s *schedulerServiceImpl) RegisterHandler(name string, handler executor.TaskHandlerFunc) {
	s.executor.RegisterHandler(name, handler)
	if ae, ok := s.asyncExecutor.(*schedulerAsync.AsyncExecutorImpl); ok {
		ae.RegisterHandler(name, func(ctx context.Context, params map[string]interface{}) (map[string]interface{}, error) {
			return handler(ctx, params)
		})
	}
}

func (s *schedulerServiceImpl) ExecuteTaskAsync(tenantID, taskID string, callback func(result schedulerAsync.TaskResult)) (string, error) {
	task, err := s.taskRepo.Get(tenantID, taskID)
	if err != nil {
		return "", err
	}
	if _, ok := s.executor.GetHandler(task.Handler); !ok {
		return "", errors.NewWithDetail(400, "任务处理器不存在", task.Handler)
	}
	asyncTask := &schedulerAsync.AsyncTask{
		ID:          task.ID,
		TenantID:    task.TenantID,
		HandlerName: task.Handler,
		Params:      task.Params,
		Callback:    callback,
		Timeout:     30 * time.Minute,
		Retries:     3,
	}
	return s.asyncExecutor.ExecuteAsync(asyncTask)
}

func (s *schedulerServiceImpl) ExecuteHandlerAsync(tenantID, handlerName string, params map[string]interface{}, callback func(result schedulerAsync.TaskResult), timeout time.Duration) (string, error) {
	if _, ok := s.executor.GetHandler(handlerName); !ok {
		return "", errors.NewWithDetail(400, "任务处理器不存在", handlerName)
	}
	if timeout <= 0 {
		timeout = 30 * time.Minute
	}
	asyncTask := &schedulerAsync.AsyncTask{
		ID:          "adhoc-" + time.Now().Format("20060102150405"),
		TenantID:    tenantID,
		HandlerName: handlerName,
		Params:      params,
		Callback:    callback,
		Timeout:     timeout,
		Retries:     1,
	}
	return s.asyncExecutor.ExecuteAsync(asyncTask)
}

func (s *schedulerServiceImpl) GetTaskResult(executionID string) (schedulerAsync.TaskResult, bool) {
	return s.asyncExecutor.GetResult(executionID)
}

func (s *schedulerServiceImpl) WaitForTaskResult(executionID string, timeout time.Duration) (schedulerAsync.TaskResult, error) {
	return s.asyncExecutor.WaitForResult(executionID, timeout)
}

func (s *schedulerServiceImpl) CreateTask(tenantID, name string, taskType scheduler.TaskType, cronExpr string, interval int, handler string, params map[string]interface{}) (*scheduler.Task, error) {
	task := &scheduler.Task{
		TenantID: tenantID,
		Name:     name,
		Type:     taskType,
		CronExpr: cronExpr,
		Interval: interval,
		Handler:  handler,
		Params:   params,
	}
	return s.taskRepo.Create(task)
}

func (s *schedulerServiceImpl) GetTask(tenantID, taskID string) (*scheduler.Task, error) {
	return s.taskRepo.Get(tenantID, taskID)
}

func (s *schedulerServiceImpl) ListTasks(tenantID string) ([]scheduler.Task, error) {
	return s.taskRepo.List(tenantID)
}

func (s *schedulerServiceImpl) StartTask(tenantID, taskID string) error {
	task, err := s.taskRepo.Get(tenantID, taskID)
	if err != nil {
		return err
	}
	if _, ok := s.executor.GetHandler(task.Handler); !ok {
		return errors.NewWithDetail(400, "任务处理器不存在", task.Handler)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	switch task.Type {
	case scheduler.TaskTypeCron:
		entryID, err := s.cron.AddFunc(task.CronExpr, func() {
			s.executor.Execute(task)
		})
		if err != nil {
			return errors.NewWithDetail(500, "添加Cron任务失败", err.Error())
		}
		s.entries[taskID] = entryID
	case scheduler.TaskTypeInterval:
		if task.Interval <= 0 {
			return errors.NewWithDetail(400, "间隔时间必须大于0", fmt.Sprintf("%d", task.Interval))
		}
		go s.runIntervalTask(task)
	}
	task.Status = scheduler.TaskStatusRunning
	s.taskRepo.Update(task)
	return nil
}

func (s *schedulerServiceImpl) runIntervalTask(task *scheduler.Task) {
	ticker := time.NewTicker(time.Duration(task.Interval) * time.Second)
	s.mu.Lock()
	s.intervalTasks[task.ID] = ticker
	s.mu.Unlock()
	defer func() {
		ticker.Stop()
		s.mu.Lock()
		delete(s.intervalTasks, task.ID)
		s.mu.Unlock()
	}()
	for range ticker.C {
		s.executor.Execute(task)
	}
}

func (s *schedulerServiceImpl) StopTask(tenantID, taskID string) error {
	task, err := s.taskRepo.Get(tenantID, taskID)
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if entryID, ok := s.entries[taskID]; ok {
		s.cron.Remove(entryID)
		delete(s.entries, taskID)
	}
	if ticker, ok := s.intervalTasks[taskID]; ok {
		ticker.Stop()
		delete(s.intervalTasks, taskID)
	}
	task.Status = scheduler.TaskStatusPaused
	s.taskRepo.Update(task)
	return nil
}

func (s *schedulerServiceImpl) DeleteTask(tenantID, taskID string) error {
	s.StopTask(tenantID, taskID)
	return s.taskRepo.Delete(tenantID, taskID)
}

func (s *schedulerServiceImpl) GetTaskExecutions(tenantID, taskID string, limit int) ([]scheduler.TaskExecution, error) {
	return s.executionRepo.List(tenantID, taskID, limit)
}

func (s *schedulerServiceImpl) Start() {
	s.cron.Start()
}

func (s *schedulerServiceImpl) Stop() {
	s.cron.Stop()
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, ticker := range s.intervalTasks {
		ticker.Stop()
	}
	s.intervalTasks = make(map[string]*time.Ticker)
}
