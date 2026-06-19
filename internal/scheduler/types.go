package scheduler

import (
	"context"
	"sync"
	"time"

	"github.com/df1-96/experiment/internal/models"
)

type ShardStrategy string

const (
	ShardStrategyIDRange    ShardStrategy = "id_range"
	ShardStrategyHashMod    ShardStrategy = "hash_mod"
	ShardStrategyLoadBalance ShardStrategy = "load_balance"
)

type PriorityLevel int

const (
	PriorityCritical PriorityLevel = 0
	PriorityHigh     PriorityLevel = 1
	PriorityNormal   PriorityLevel = 2
	PriorityLow      PriorityLevel = 3
)

type SchedulerEvent string

const (
	EventTaskAssigned    SchedulerEvent = "task_assigned"
	EventTaskStarted     SchedulerEvent = "task_started"
	EventTaskCompleted   SchedulerEvent = "task_completed"
	EventTaskFailed      SchedulerEvent = "task_failed"
	EventTaskTimeout     SchedulerEvent = "task_timeout"
	EventTaskCanceled    SchedulerEvent = "task_canceled"
	EventWorkerRegistered SchedulerEvent = "worker_registered"
	EventWorkerOffline   SchedulerEvent = "worker_offline"
	EventWorkerReconnected SchedulerEvent = "worker_reconnected"
	EventCheckpointSaved SchedulerEvent = "checkpoint_saved"
)

type ShardConfig struct {
	Strategy       ShardStrategy
	TotalParams    int64
	WorkerCount    int
	MinChunkSize   int64
	MaxChunkSize   int64
	TargetDuration time.Duration
}

type ShardResult struct {
	Chunks     []*models.TaskChunk
	Strategy   ShardStrategy
	ChunkSize  int64
	Total      int
	CreatedAt  time.Time
}

type QueuedTask struct {
	Task        *models.Task
	EnqueueTime time.Time
	Deadline    *time.Time
	CancelChan  chan struct{}
}

type SchedulerEventCallback func(event SchedulerEvent, payload interface{})

type WorkerLoad struct {
	WorkerID      int64
	CurrentTasks  int
	TotalCPUUsage float64
	TotalMemory   int
	CompletedLastHour int64
	FailedLastHour    int64
	Score         float64
}

type AssignmentStrategy string

const (
	AssignmentLoadBalance AssignmentStrategy = "load_balance"
	AssignmentCapability  AssignmentStrategy = "capability"
	AssignmentLocality    AssignmentStrategy = "locality"
	AssignmentBestFit     AssignmentStrategy = "best_fit"
)

type TaskProgress struct {
	TaskID        int64
	Status        models.TaskStatus
	Progress      float64
	CurrentStep   int64
	TotalSteps    int64
	StartTime     time.Time
	EstimatedEnd  *time.Time
	WorkerID      *int64
	LastUpdate    time.Time
	CheckpointCount int
	RetryCount    int
}

type TaskCheckpoint struct {
	TaskID    int64
	Step      int64
	Data      models.Params
	Checksum  string
	FilePath  string
	CreatedAt time.Time
}

type SchedulerConfig struct {
	HeartbeatTimeout       time.Duration
	DefaultTaskTimeout     time.Duration
	DefaultMaxRetries      int
	AssignmentStrategy     AssignmentStrategy
	CheckpointInterval     time.Duration
	WorkerOfflineThreshold time.Duration
	MaxConcurrentTasks     int
}

type scheduledTask struct {
	task       *models.Task
	tracker    *TaskTracker
	mu         sync.RWMutex
	progress   TaskProgress
	checkpoints []TaskCheckpoint
}

type scheduledWorker struct {
	worker      *models.Worker
	lastSeen    time.Time
	currentTask *int64
	mu          sync.RWMutex
	load        WorkerLoad
	capabilities map[string]bool
	location    string
}

type Scheduler interface {
	Start(ctx context.Context) error
	Stop() error
	RegisterWorker(worker *models.Worker) error
	UnregisterWorker(workerID int64) error
	SubmitTask(ctx context.Context, task *models.Task) error
	CancelTask(taskID int64) error
	GetTaskProgress(taskID int64) (*TaskProgress, error)
	GetWorkerLoad(workerID int64) (*WorkerLoad, error)
	Heartbeat(workerID int64) error
	ReportTaskProgress(taskID int64, step int64, totalSteps int64, data models.Params) error
	CompleteTask(taskID int64, result *models.Result) error
	FailTask(taskID int64, err string) error
	SaveCheckpoint(taskID int64, step int64, data models.Params, checksum string, filePath string) error
	RestoreCheckpoint(taskID int64) (*TaskCheckpoint, error)
	OnEvent(callback SchedulerEventCallback)
}
