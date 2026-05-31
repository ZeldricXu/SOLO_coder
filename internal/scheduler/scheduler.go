package scheduler

import (
	"context"
	"sort"
	"sync"
	"time"

	"github.com/solocoder/backup-engine/internal/logger"
	"github.com/solocoder/backup-engine/pkg/common"
)

type TaskStatus string

const (
	TaskStatusPending   TaskStatus = "pending"
	TaskStatusRunning   TaskStatus = "running"
	TaskStatusCompleted TaskStatus = "completed"
	TaskStatusFailed    TaskStatus = "failed"
	TaskStatusCancelled TaskStatus = "cancelled"
	TaskStatusSkipped   TaskStatus = "skipped"
)

type TaskPriority int

const (
	PriorityLow    TaskPriority = 0
	PriorityNormal TaskPriority = 50
	PriorityHigh   TaskPriority = 100
)

type TaskFunc func(ctx context.Context, task *common.Task) error

type ScheduledTask struct {
	common.Task
	TaskFunc    TaskFunc
	Priority    TaskPriority
	MaxRetries  int
	Timeout     time.Duration
	RetryDelay  time.Duration
	DependsOn   []string
	Attempts    int
	LastError   error
}

type DependencyGraph struct {
	nodes map[string]*ScheduledTask
	edges map[string][]string
	mu    sync.RWMutex
}

func NewDependencyGraph() *DependencyGraph {
	return &DependencyGraph{
		nodes: make(map[string]*ScheduledTask),
		edges: make(map[string][]string),
	}
}

func (g *DependencyGraph) AddTask(task *ScheduledTask) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.nodes[task.ID] = task
	if _, exists := g.edges[task.ID]; !exists {
		g.edges[task.ID] = make([]string, 0)
	}
}

func (g *DependencyGraph) AddDependency(taskID, dependencyID string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.edges[dependencyID] = append(g.edges[dependencyID], taskID)
}

func (g *DependencyGraph) GetDependencies(taskID string) []string {
	g.mu.RLock()
	defer g.mu.RUnlock()

	task, exists := g.nodes[taskID]
	if !exists {
		return nil
	}

	deps := make([]string, 0, len(task.DependsOn))
	deps = append(deps, task.DependsOn...)
	return deps
}

func (g *DependencyGraph) HasCircularDependency() bool {
	g.mu.RLock()
	defer g.mu.RUnlock()

	visited := make(map[string]bool)
	recStack := make(map[string]bool)

	for id := range g.nodes {
		if g.hasCycle(id, visited, recStack) {
			return true
		}
	}
	return false
}

func (g *DependencyGraph) hasCycle(node string, visited, recStack map[string]bool) bool {
	if recStack[node] {
		return true
	}
	if visited[node] {
		return false
	}

	visited[node] = true
	recStack[node] = true

	for _, neighbor := range g.edges[node] {
		if g.hasCycle(neighbor, visited, recStack) {
			return true
		}
	}

	recStack[node] = false
	return false
}

func (g *DependencyGraph) TopologicalSort() ([]string, error) {
	g.mu.RLock()
	defer g.mu.RUnlock()

	inDegree := make(map[string]int)
	for id := range g.nodes {
		inDegree[id] = 0
	}

	for _, deps := range g.edges {
		for _, dep := range deps {
			inDegree[dep]++
		}
	}

	for id, task := range g.nodes {
		inDegree[id] += len(task.DependsOn)
	}

	queue := make([]string, 0)
	for id, degree := range inDegree {
		if degree == 0 {
			queue = append(queue, id)
		}
	}

	sort.Slice(queue, func(i, j int) bool {
		return g.nodes[queue[i]].Priority > g.nodes[queue[j]].Priority
	})

	var result []string
	for len(queue) > 0 {
		sort.Slice(queue, func(i, j int) bool {
			return g.nodes[queue[i]].Priority > g.nodes[queue[j]].Priority
		})

		node := queue[0]
		queue = queue[1:]
		result = append(result, node)

		for _, neighbor := range g.edges[node] {
			inDegree[neighbor]--
			if inDegree[neighbor] == 0 {
				queue = append(queue, neighbor)
			}
		}
	}

	if len(result) != len(g.nodes) {
		return nil, common.NewValidationError("graph", "contains circular dependency")
	}

	return result, nil
}

func (g *DependencyGraph) GetTask(id string) (*ScheduledTask, bool) {
	g.mu.RLock()
	defer g.mu.RUnlock()
	task, exists := g.nodes[id]
	return task, exists
}

func (g *DependencyGraph) GetAllTasks() []*ScheduledTask {
	g.mu.RLock()
	defer g.mu.RUnlock()

	tasks := make([]*ScheduledTask, 0, len(g.nodes))
	for _, task := range g.nodes {
		tasks = append(tasks, task)
	}
	return tasks
}

type TaskScheduler struct {
	graph        *DependencyGraph
	runningTasks map[string]context.CancelFunc
	mu           sync.RWMutex
	maxParallel  int
	completed    map[string]bool
	errors       map[string]error
	wg           sync.WaitGroup
}

func NewTaskScheduler(maxParallel int) *TaskScheduler {
	if maxParallel <= 0 {
		maxParallel = 5
	}
	return &TaskScheduler{
		graph:        NewDependencyGraph(),
		runningTasks: make(map[string]context.CancelFunc),
		maxParallel:  maxParallel,
		completed:    make(map[string]bool),
		errors:       make(map[string]error),
	}
}

func (s *TaskScheduler) AddTask(task *common.Task, taskFunc TaskFunc, priority TaskPriority, maxRetries int, timeout time.Duration) string {
	if task.ID == "" {
		task.ID = common.NewID()
	}
	task.Status = string(TaskStatusPending)
	task.CreatedAt = time.Now()

	scheduledTask := &ScheduledTask{
		Task:       *task,
		TaskFunc:   taskFunc,
		Priority:   priority,
		MaxRetries: maxRetries,
		Timeout:    timeout,
		RetryDelay: 1 * time.Second,
	}

	s.graph.AddTask(scheduledTask)
	logger.Info("Added task to scheduler", map[string]interface{}{
		"task_id":   task.ID,
		"task_name": task.Name,
		"priority":  priority,
	})

	return task.ID
}

func (s *TaskScheduler) AddDependency(taskID, dependencyID string) error {
	task, taskExists := s.graph.GetTask(taskID)
	_, depExists := s.graph.GetTask(dependencyID)

	if !taskExists {
		return common.NewValidationError("task_id", "not found")
	}
	if !depExists {
		return common.NewValidationError("dependency_id", "not found")
	}

	task.DependsOn = append(task.DependsOn, dependencyID)
	s.graph.AddDependency(taskID, dependencyID)

	logger.Info("Added task dependency", map[string]interface{}{
		"task_id":       taskID,
		"dependency_id": dependencyID,
	})

	return nil
}

func (s *TaskScheduler) Validate() error {
	if s.graph.HasCircularDependency() {
		return common.NewValidationError("dependencies", "circular dependency detected")
	}

	_, err := s.graph.TopologicalSort()
	return err
}

func (s *TaskScheduler) Run(ctx context.Context) error {
	if err := s.Validate(); err != nil {
		return err
	}

	order, err := s.graph.TopologicalSort()
	if err != nil {
		return err
	}

	logger.Info("Starting task execution", map[string]interface{}{
		"task_count":   len(order),
		"max_parallel": s.maxParallel,
	})

	sem := make(chan struct{}, s.maxParallel)
	taskCh := make(chan *ScheduledTask)
	doneCh := make(chan string)

	go func() {
		for _, taskID := range order {
			task, _ := s.graph.GetTask(taskID)
			select {
			case <-ctx.Done():
				return
			case taskCh <- task:
			}
		}
		close(taskCh)
	}()

	go func() {
		for task := range taskCh {
			select {
			case <-ctx.Done():
				return
			default:
			}

			if !s.checkDependencies(task) {
				s.mu.Lock()
				task.Status = string(TaskStatusSkipped)
				s.completed[task.ID] = true
				s.errors[task.ID] = common.NewValidationError("dependencies", "not satisfied")
				s.mu.Unlock()
				logger.Warn("Task skipped due to unmet dependencies", map[string]interface{}{
					"task_id":   task.ID,
					"task_name": task.Name,
				})
				doneCh <- task.ID
				continue
			}

			sem <- struct{}{}
			go func(t *ScheduledTask) {
				defer func() { <-sem }()
				s.executeTask(ctx, t)
				doneCh <- t.ID
			}(task)
		}
	}()

	completedCount := 0
	for completedCount < len(order) {
		select {
		case <-ctx.Done():
			logger.Warn("Scheduler cancelled", map[string]interface{}{
				"completed": completedCount,
				"total":     len(order),
			})
			return ctx.Err()
		case <-doneCh:
			completedCount++
			logger.Debug("Task execution progress", map[string]interface{}{
				"completed": completedCount,
				"total":     len(order),
			})
		}
	}

	logger.Info("All tasks completed", map[string]interface{}{
		"task_count": len(order),
	})
	return nil
}

func (s *TaskScheduler) checkDependencies(task *ScheduledTask) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()

	for _, depID := range task.DependsOn {
		if !s.completed[depID] {
			return false
		}
		if s.errors[depID] != nil {
			return false
		}
	}
	return true
}

func (s *TaskScheduler) executeTask(ctx context.Context, task *ScheduledTask) {
	s.mu.Lock()
	task.Status = string(TaskStatusRunning)
	task.StartedAt = time.Now()
	s.mu.Unlock()

	logger.Info("Starting task execution", map[string]interface{}{
		"task_id":   task.ID,
		"task_name": task.Name,
	})

	taskCtx, cancel := context.WithTimeout(ctx, task.Timeout)
	s.mu.Lock()
	s.runningTasks[task.ID] = cancel
	s.mu.Unlock()

	defer func() {
		cancel()
		s.mu.Lock()
		delete(s.runningTasks, task.ID)
		s.mu.Unlock()
	}()

	var err error
	for attempt := 0; attempt <= task.MaxRetries; attempt++ {
		select {
		case <-taskCtx.Done():
			err = taskCtx.Err()
			break
		default:
		}

		task.Attempts = attempt + 1
		task.Progress = 0

		execErr := task.TaskFunc(taskCtx, &task.Task)
		if execErr == nil {
			s.mu.Lock()
			task.Status = string(TaskStatusCompleted)
			task.CompletedAt = time.Now()
			task.Progress = 100
			s.completed[task.ID] = true
			s.mu.Unlock()

			logger.Info("Task completed successfully", map[string]interface{}{
				"task_id":   task.ID,
				"task_name": task.Name,
				"attempts":  task.Attempts,
				"duration":  common.FormatDuration(task.CompletedAt.Sub(task.StartedAt)),
			})
			return
		}

		err = execErr
		task.LastError = execErr

		logger.Warn("Task attempt failed", map[string]interface{}{
			"task_id":   task.ID,
			"task_name": task.Name,
			"attempt":   attempt + 1,
			"error":     execErr.Error(),
		})

		if attempt < task.MaxRetries {
			select {
			case <-taskCtx.Done():
				break
			case <-time.After(task.RetryDelay * time.Duration(attempt+1)):
			}
		}
	}

	s.mu.Lock()
	task.Status = string(TaskStatusFailed)
	task.CompletedAt = time.Now()
	task.Error = err.Error()
	s.completed[task.ID] = true
	s.errors[task.ID] = err
	s.mu.Unlock()

	logger.Error("Task failed permanently", map[string]interface{}{
		"task_id":   task.ID,
		"task_name": task.Name,
		"attempts":  task.Attempts,
		"error":     err.Error(),
	})
}

func (s *TaskScheduler) CancelTask(taskID string) error {
	s.mu.RLock()
	cancel, running := s.runningTasks[taskID]
	task, exists := s.graph.GetTask(taskID)
	s.mu.RUnlock()

	if !exists {
		return common.ErrNotFound
	}

	if running {
		cancel()
	}

	s.mu.Lock()
	if task != nil {
		task.Status = string(TaskStatusCancelled)
	}
	s.mu.Unlock()

	logger.Info("Task cancelled", map[string]interface{}{
		"task_id":   taskID,
		"task_name": task.Name,
	})

	return nil
}

func (s *TaskScheduler) GetTaskStatus(taskID string) (*common.Task, error) {
	task, exists := s.graph.GetTask(taskID)
	if !exists {
		return nil, common.ErrNotFound
	}
	return &task.Task, nil
}

func (s *TaskScheduler) GetAllTasks() []*common.Task {
	scheduledTasks := s.graph.GetAllTasks()
	tasks := make([]*common.Task, 0, len(scheduledTasks))
	for _, st := range scheduledTasks {
		tasks = append(tasks, &st.Task)
	}
	return tasks
}

func (s *TaskScheduler) GetErrors() map[string]error {
	s.mu.RLock()
	defer s.mu.RUnlock()

	errors := make(map[string]error, len(s.errors))
	for k, v := range s.errors {
		errors[k] = v
	}
	return errors
}

func (s *TaskScheduler) GetProgress() float64 {
	s.mu.RLock()
	defer s.mu.RUnlock()

	tasks := s.graph.GetAllTasks()
	if len(tasks) == 0 {
		return 0
	}

	completed := 0
	for _, t := range tasks {
		if t.Status == string(TaskStatusCompleted) {
			completed++
		}
	}

	return float64(completed) / float64(len(tasks)) * 100
}
