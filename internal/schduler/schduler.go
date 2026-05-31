package schduler

import (
	"errors"
	"sync"
	"time"

	"github.com/enterprise/config-platform/pkg/utils"
	"github.com/robfig/cron/v3"
)

type TaskStatus string

const (
	TaskStatusPending   TaskStatus = "pending"
	TaskStatusRunning   TaskStatus = "running"
	TaskStatusCompleted TaskStatus = "completed"
	TaskStatusFailed    TaskStatus = "failed"
	TaskStatusPaused    TaskStatus = "paused"
)

type TaskType string

const (
	TaskTypeOneShot TaskType = "oneshot"
	TaskTypeCron    TaskType = "cron"
	TaskTypeInterval TaskType = "interval"
)

type Task struct {
	ID          string                 `json:"id"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	Type        TaskType               `json:"type"`
	CronExpr    string                 `json:"cron_expr,omitempty"`
	Interval    int                    `json:"interval_seconds,omitempty"`
	Payload     map[string]interface{} `json:"payload"`
	Handler     func(map[string]interface{}) error `json:"-"`
	Status      TaskStatus             `json:"status"`
	LastRun     *time.Time             `json:"last_run"`
	NextRun     *time.Time             `json:"next_run"`
	RunCount    int                    `json:"run_count"`
	ErrorCount  int                    `json:"error_count"`
	CreatedAt   time.Time              `json:"created_at"`
	Enabled     bool                   `json:"enabled"`
	MaxRetries  int                    `json:"max_retries"`
	Timeout     int                    `json:"timeout_seconds"`
}

type TaskExecution struct {
	ID        string    `json:"id"`
	TaskID    string    `json:"task_id"`
	Status    TaskStatus `json:"status"`
	StartedAt time.Time `json:"started_at"`
	EndedAt   *time.Time `json:"ended_at"`
	Error     string    `json:"error,omitempty"`
}

type Manager struct {
	tasks       map[string]*Task
	executions  map[string][]*TaskExecution
	cron        *cron.Cron
	cronEntries map[string]cron.EntryID
	mu          sync.RWMutex
}

var (
	instance *Manager
	once     sync.Once
)

func GetManager() *Manager {
	once.Do(func() {
		instance = &Manager{
			tasks:       make(map[string]*Task),
			executions:  make(map[string][]*TaskExecution),
			cron:        cron.New(cron.WithSeconds()),
			cronEntries: make(map[string]cron.EntryID),
		}
		instance.cron.Start()
	})
	return instance
}

func (m *Manager) CreateTask(task *Task) (*Task, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	task.ID = utils.GenerateID("task")
	task.Status = TaskStatusPending
	task.CreatedAt = time.Now().UTC()
	task.Enabled = true
	task.RunCount = 0
	task.ErrorCount = 0

	if task.MaxRetries == 0 {
		task.MaxRetries = 3
	}
	if task.Timeout == 0 {
		task.Timeout = 300
	}

	m.tasks[task.ID] = task

	if task.Enabled {
		m.scheduleTaskLocked(task)
	}

	return task, nil
}

func (m *Manager) scheduleTaskLocked(task *Task) {
	switch task.Type {
	case TaskTypeCron:
		if task.CronExpr != "" {
			entryID, err := m.cron.AddFunc(task.CronExpr, func() {
				m.executeTask(task.ID)
			})
			if err == nil {
				m.cronEntries[task.ID] = entryID
				entry := m.cron.Entry(entryID)
				task.NextRun = &entry.Next
			}
		}
	case TaskTypeInterval:
		if task.Interval > 0 {
			go m.runIntervalTask(task)
		}
	case TaskTypeOneShot:
		next := time.Now().Add(time.Second)
		task.NextRun = &next
		go func() {
			time.Sleep(time.Second)
			m.executeTask(task.ID)
		}()
	}
}

func (m *Manager) runIntervalTask(task *Task) {
	ticker := time.NewTicker(time.Duration(task.Interval) * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		m.mu.RLock()
		currentTask, exists := m.tasks[task.ID]
		if !exists || !currentTask.Enabled {
			m.mu.RUnlock()
			return
		}
		m.mu.RUnlock()

		m.executeTask(task.ID)
	}
}

func (m *Manager) executeTask(taskID string) {
	m.mu.RLock()
	task, exists := m.tasks[taskID]
	m.mu.RUnlock()

	if !exists || !task.Enabled {
		return
	}

	exec := &TaskExecution{
		ID:        utils.GenerateID("exec"),
		TaskID:    taskID,
		Status:    TaskStatusRunning,
		StartedAt: time.Now().UTC(),
	}

	m.mu.Lock()
	task.Status = TaskStatusRunning
	task.LastRun = &exec.StartedAt
	m.executions[taskID] = append(m.executions[taskID], exec)
	m.mu.Unlock()

	err := m.runWithTimeout(task)

	m.mu.Lock()
	exec.EndedAt = utils.NowPtr()
	if err != nil {
		exec.Status = TaskStatusFailed
		exec.Error = err.Error()
		task.ErrorCount++
		task.Status = TaskStatusFailed
	} else {
		exec.Status = TaskStatusCompleted
		task.RunCount++
		task.Status = TaskStatusCompleted
	}

	if task.Type == TaskTypeCron {
		if entryID, ok := m.cronEntries[task.ID]; ok {
			entry := m.cron.Entry(entryID)
			task.NextRun = &entry.Next
		}
	} else if task.Type == TaskTypeInterval {
		next := time.Now().Add(time.Duration(task.Interval) * time.Second)
		task.NextRun = &next
	}

	m.mu.Unlock()
}

func (m *Manager) runWithTimeout(task *Task) error {
	if task.Handler == nil {
		return errors.New("no handler defined")
	}

	done := make(chan error, 1)
	go func() {
		done <- task.Handler(task.Payload)
	}()

	select {
	case err := <-done:
		return err
	case <-time.After(time.Duration(task.Timeout) * time.Second):
		return errors.New("task execution timeout")
	}
}

func (m *Manager) GetTask(taskID string) (*Task, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	task, exists := m.tasks[taskID]
	if !exists {
		return nil, errors.New("task not found")
	}
	return task, nil
}

func (m *Manager) UpdateTask(taskID string, updates map[string]interface{}) (*Task, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	task, exists := m.tasks[taskID]
	if !exists {
		return nil, errors.New("task not found")
	}

	if name, ok := updates["name"].(string); ok {
		task.Name = name
	}
	if desc, ok := updates["description"].(string); ok {
		task.Description = desc
	}
	if cronExpr, ok := updates["cron_expr"].(string); ok {
		task.CronExpr = cronExpr
		if entryID, ok := m.cronEntries[taskID]; ok {
			m.cron.Remove(entryID)
		}
		if task.Enabled {
			entryID, err := m.cron.AddFunc(cronExpr, func() {
				m.executeTask(taskID)
			})
			if err == nil {
				m.cronEntries[taskID] = entryID
			}
		}
	}
	if interval, ok := updates["interval_seconds"].(int); ok {
		task.Interval = interval
	}
	if maxRetries, ok := updates["max_retries"].(int); ok {
		task.MaxRetries = maxRetries
	}
	if timeout, ok := updates["timeout_seconds"].(int); ok {
		task.Timeout = timeout
	}
	if handler, ok := updates["handler"].(func(map[string]interface{}) error); ok {
		task.Handler = handler
	}

	return task, nil
}

func (m *Manager) DeleteTask(taskID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.tasks[taskID]; !exists {
		return errors.New("task not found")
	}

	if entryID, ok := m.cronEntries[taskID]; ok {
		m.cron.Remove(entryID)
		delete(m.cronEntries, taskID)
	}

	delete(m.tasks, taskID)
	delete(m.executions, taskID)
	return nil
}

func (m *Manager) ListTasks() []*Task {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]*Task, 0, len(m.tasks))
	for _, t := range m.tasks {
		result = append(result, t)
	}
	return result
}

func (m *Manager) PauseTask(taskID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	task, exists := m.tasks[taskID]
	if !exists {
		return errors.New("task not found")
	}

	task.Enabled = false
	task.Status = TaskStatusPaused

	if entryID, ok := m.cronEntries[taskID]; ok {
		m.cron.Remove(entryID)
		delete(m.cronEntries, taskID)
	}

	return nil
}

func (m *Manager) ResumeTask(taskID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	task, exists := m.tasks[taskID]
	if !exists {
		return errors.New("task not found")
	}

	task.Enabled = true
	task.Status = TaskStatusPending
	m.scheduleTaskLocked(task)

	return nil
}

func (m *Manager) TriggerTask(taskID string) error {
	m.mu.RLock()
	_, exists := m.tasks[taskID]
	m.mu.RUnlock()

	if !exists {
		return errors.New("task not found")
	}

	go m.executeTask(taskID)
	return nil
}

func (m *Manager) GetTaskExecutions(taskID string) []*TaskExecution {
	m.mu.RLock()
	defer m.mu.RUnlock()

	return m.executions[taskID]
}

func (m *Manager) Stop() {
	m.cron.Stop()
}
