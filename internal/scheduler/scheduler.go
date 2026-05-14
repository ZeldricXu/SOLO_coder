package scheduler

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"backupmanager/internal/backup"
	"backupmanager/internal/logger"
	"backupmanager/pkg/models"
)

type Scheduler struct {
	tasks         map[string]*models.ScheduledTask
	taskConfig    string
	configFile    string
	logger        *logger.Logger
	backupEng     *backup.Engine
	running       bool
	mu            sync.Mutex
	stopChan      chan struct{}
	configWatcher *time.Ticker
}

func NewScheduler(configPath string, log *logger.Logger, backupEng *backup.Engine) *Scheduler {
	return &Scheduler{
		tasks:      make(map[string]*models.ScheduledTask),
		taskConfig: filepath.Join(configPath, "scheduled_tasks.json"),
		configFile: filepath.Join(configPath, "scheduler.json"),
		logger:     log,
		backupEng:  backupEng,
		stopChan:   make(chan struct{}),
	}
}

func (s *Scheduler) Init() error {
	if err := s.loadFromTaskConfig(); err != nil {
		s.logger.Warn("Failed to load task config: %v", err)
	}

	if err := s.loadFromAppConfig(); err != nil {
		s.logger.Debug("No app config found or error loading: %v", err)
	}

	return nil
}

func (s *Scheduler) loadFromTaskConfig() error {
	if data, err := os.ReadFile(s.taskConfig); err == nil {
		var tasks []*models.ScheduledTask
		if err := json.Unmarshal(data, &tasks); err != nil {
			return fmt.Errorf("failed to parse task config: %w", err)
		}
		s.mu.Lock()
		for _, t := range tasks {
			s.tasks[t.TaskID] = t
		}
		s.mu.Unlock()
		s.logger.Info("Loaded %d tasks from task config", len(tasks))
		return nil
	}
	return fmt.Errorf("task config not found")
}

func (s *Scheduler) loadFromAppConfig() error {
	if data, err := os.ReadFile(s.configFile); err == nil {
		var config models.AppConfig
		if err := json.Unmarshal(data, &config); err != nil {
			return fmt.Errorf("failed to parse scheduler config: %w", err)
		}

		addedCount := 0
		s.mu.Lock()
		for _, taskCfg := range config.ScheduleTasks {
			if _, exists := s.tasks[taskCfg.ID]; !exists {
				if taskCfg.ID == "" {
					taskCfg.ID = generateTaskID()
				}
				task := &models.ScheduledTask{
					TaskID:     taskCfg.ID,
					SourcePath: taskCfg.Source,
					Schedule:   taskCfg.Schedule,
					Enabled:    taskCfg.Enabled,
					CreatedAt:  time.Now(),
				}
				s.tasks[task.TaskID] = task
				addedCount++
			}
		}
		s.mu.Unlock()

		if addedCount > 0 {
			s.logger.Info("Loaded %d new tasks from scheduler config", addedCount)
			s.SaveTasks()
		}
		return nil
	}
	return fmt.Errorf("scheduler config not found")
}

func (s *Scheduler) ReloadConfig() error {
	s.logger.Info("Reloading scheduler configuration")

	if err := s.loadFromAppConfig(); err != nil {
		s.logger.Warn("Failed to reload scheduler config: %v", err)
		return err
	}
	return nil
}

func (s *Scheduler) GetConfigPath() string {
	return s.configFile
}

func (s *Scheduler) SaveTasks() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	tasks := make([]*models.ScheduledTask, 0, len(s.tasks))
	for _, t := range s.tasks {
		tasks = append(tasks, t)
	}

	data, err := json.MarshalIndent(tasks, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal tasks: %w", err)
	}

	if err := os.MkdirAll(filepath.Dir(s.taskConfig), 0755); err != nil {
		return fmt.Errorf("failed to create config directory: %w", err)
	}

	if err := os.WriteFile(s.taskConfig, data, 0644); err != nil {
		return fmt.Errorf("failed to save tasks: %w", err)
	}
	return nil
}

func (s *Scheduler) AddTask(sourcePath, schedule string) (*models.ScheduledTask, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	task := &models.ScheduledTask{
		TaskID:     generateTaskID(),
		SourcePath: sourcePath,
		Schedule:   schedule,
		Enabled:    true,
		CreatedAt:  time.Now(),
	}

	s.tasks[task.TaskID] = task
	s.logger.Info("Added scheduled task: %s (source=%s, schedule=%s)", task.TaskID, sourcePath, schedule)

	return task, s.SaveTasks()
}

func (s *Scheduler) RemoveTask(taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.tasks[taskID]; !exists {
		return fmt.Errorf("task not found: %s", taskID)
	}

	delete(s.tasks, taskID)
	s.logger.Info("Removed scheduled task: %s", taskID)

	return s.SaveTasks()
}

func (s *Scheduler) ListTasks() []*models.ScheduledTask {
	s.mu.Lock()
	defer s.mu.Unlock()

	tasks := make([]*models.ScheduledTask, 0, len(s.tasks))
	for _, t := range s.tasks {
		tasks = append(tasks, t)
	}
	return tasks
}

func (s *Scheduler) EnableTask(taskID string, enable bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	task, exists := s.tasks[taskID]
	if !exists {
		return fmt.Errorf("task not found: %s", taskID)
	}

	task.Enabled = enable
	s.logger.Info("Task %s enabled: %v", taskID, enable)

	return s.SaveTasks()
}

func (s *Scheduler) Start() {
	s.mu.Lock()
	if s.running {
		s.mu.Unlock()
		return
	}
	s.running = true
	s.mu.Unlock()

	s.logger.Info("Scheduler started")

	go func() {
		ticker := time.NewTicker(1 * time.Minute)
		defer ticker.Stop()

		configTicker := time.NewTicker(5 * time.Minute)
		defer configTicker.Stop()

		for {
			select {
			case <-ticker.C:
				s.executeDueTasks()
			case <-configTicker.C:
				s.ReloadConfig()
			case <-s.stopChan:
				s.logger.Info("Scheduler stopped")
				return
			}
		}
	}()
}

func (s *Scheduler) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.running {
		return
	}

	s.running = false
	close(s.stopChan)
	s.stopChan = make(chan struct{})
}

func (s *Scheduler) executeDueTasks() {
	now := time.Now()

	s.mu.Lock()
	tasksToExecute := make([]*models.ScheduledTask, 0)
	for _, task := range s.tasks {
		if !task.Enabled {
			continue
		}

		if s.shouldRun(task, now) {
			tasksToExecute = append(tasksToExecute, task)
		}
	}
	s.mu.Unlock()

	for _, task := range tasksToExecute {
		s.executeTask(task)
	}
}

func (s *Scheduler) executeTask(task *models.ScheduledTask) {
	s.logger.Info("Executing scheduled task: %s (source=%s)", task.TaskID, task.SourcePath)

	result, err := s.backupEng.Backup(task.SourcePath)

	s.mu.Lock()
	task.LastRunAt = time.Now()
	if err != nil || !result.Success {
		task.LastRunStatus = "failed"
	} else {
		task.LastRunStatus = "success"
	}
	s.mu.Unlock()

	s.SaveTasks()

	if err != nil {
		s.logger.Error("Scheduled task %s failed: %v", task.TaskID, err)
	} else if !result.Success {
		s.logger.Warn("Scheduled task %s completed with errors", task.TaskID)
	} else {
		s.logger.Info("Scheduled task %s completed successfully", task.TaskID)
	}
}

func (s *Scheduler) shouldRun(task *models.ScheduledTask, now time.Time) bool {
	if !task.Enabled {
		return false
	}

	switch task.Schedule {
	case "daily":
		if task.LastRunAt.IsZero() {
			return true
		}
		return now.Sub(task.LastRunAt) >= 24*time.Hour
	case "hourly":
		if task.LastRunAt.IsZero() {
			return true
		}
		return now.Sub(task.LastRunAt) >= 1*time.Hour
	case "weekly":
		if task.LastRunAt.IsZero() {
			return true
		}
		return now.Sub(task.LastRunAt) >= 7*24*time.Hour
	case "minute":
		if task.LastRunAt.IsZero() {
			return true
		}
		return now.Sub(task.LastRunAt) >= 1*time.Minute
	default:
		return s.parseCustomSchedule(task.Schedule, task.LastRunAt, now)
	}
}

func (s *Scheduler) parseCustomSchedule(schedule string, lastRun, now time.Time) bool {
	var duration time.Duration
	var err error

	switch {
	case len(schedule) > 1 && (schedule[len(schedule)-1] == 'h' || schedule[len(schedule)-1] == 'H'):
		var hours int
		fmt.Sscanf(schedule[:len(schedule)-1], "%d", &hours)
		duration = time.Duration(hours) * time.Hour
	case len(schedule) > 1 && (schedule[len(schedule)-1] == 'm' || schedule[len(schedule)-1] == 'M'):
		var minutes int
		fmt.Sscanf(schedule[:len(schedule)-1], "%d", &minutes)
		duration = time.Duration(minutes) * time.Minute
	case len(schedule) > 1 && (schedule[len(schedule)-1] == 's' || schedule[len(schedule)-1] == 'S'):
		var seconds int
		fmt.Sscanf(schedule[:len(schedule)-1], "%d", &seconds)
		duration = time.Duration(seconds) * time.Second
	case len(schedule) > 2 && schedule[len(schedule)-2:] == "d":
		var days int
		fmt.Sscanf(schedule[:len(schedule)-1], "%d", &days)
		duration = time.Duration(days) * 24 * time.Hour
	default:
		duration, err = time.ParseDuration(schedule)
		if err != nil {
			s.logger.Warn("Invalid schedule format: %s", schedule)
			return false
		}
	}

	if lastRun.IsZero() {
		return true
	}
	return now.Sub(lastRun) >= duration
}

func generateTaskID() string {
	return fmt.Sprintf("task_%s", time.Now().Format("20060102150405"))
}
