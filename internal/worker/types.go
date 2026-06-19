package worker

import (
	"context"
	"sync"
	"time"

	"github.com/df1-96/experiment/internal/compute"
)

type WorkerStatus int

const (
	WorkerStatusUnspecified WorkerStatus = iota
	WorkerStatusOffline
	WorkerStatusIdle
	WorkerStatusBusy
	WorkerStatusPaused
	WorkerStatusError
	WorkerStatusDraining
)

func (s WorkerStatus) String() string {
	switch s {
	case WorkerStatusOffline:
		return "offline"
	case WorkerStatusIdle:
		return "idle"
	case WorkerStatusBusy:
		return "busy"
	case WorkerStatusPaused:
		return "paused"
	case WorkerStatusError:
		return "error"
	case WorkerStatusDraining:
		return "draining"
	default:
		return "unspecified"
	}
}

type WorkerType int

const (
	WorkerTypeUnspecified WorkerType = iota
	WorkerTypeCPU
	WorkerTypeGPU
	WorkerTypeTPU
	WorkerTypeFPGA
	WorkerTypeHybrid
)

func (t WorkerType) String() string {
	switch t {
	case WorkerTypeCPU:
		return "cpu"
	case WorkerTypeGPU:
		return "gpu"
	case WorkerTypeTPU:
		return "tpu"
	case WorkerTypeFPGA:
		return "fpga"
	case WorkerTypeHybrid:
		return "hybrid"
	default:
		return "unspecified"
	}
}

type TaskStatus int

const (
	TaskStatusUnspecified TaskStatus = iota
	TaskStatusPending
	TaskStatusQueued
	TaskStatusRunning
	TaskStatusPaused
	TaskStatusCompleted
	TaskStatusFailed
	TaskStatusCancelled
	TaskStatusTimedOut
)

func (s TaskStatus) String() string {
	switch s {
	case TaskStatusPending:
		return "pending"
	case TaskStatusQueued:
		return "queued"
	case TaskStatusRunning:
		return "running"
	case TaskStatusPaused:
		return "paused"
	case TaskStatusCompleted:
		return "completed"
	case TaskStatusFailed:
		return "failed"
	case TaskStatusCancelled:
		return "cancelled"
	case TaskStatusTimedOut:
		return "timed_out"
	default:
		return "unspecified"
	}
}

type ResourceInfo struct {
	TotalMemoryBytes     uint64
	AvailableMemoryBytes uint64
	TotalCPUCores        int32
	CPUUsagePercent      float64
	PerCoreCPUUsage      []float64
	GPUCount             int32
	GPUUsagePercent      float64
	GPUMemoryBytes       uint64
	AvailableGPUMemory   uint64
	DiskUsageBytes       uint64
	TotalDiskBytes       uint64
	NetworkBandwidthMbps float64
	DiskIOReadBytes      uint64
	DiskIOWriteBytes     uint64
	NetworkIOReadBytes   uint64
	NetworkIOWriteBytes  uint64
	RSSBytes             uint64
	VirtualMemoryBytes   uint64
}

type LoadInfo struct {
	ActiveTasks         int32
	PendingTasks        int32
	CompletedTasks      int32
	FailedTasks         int32
	AverageTaskDuration float64
	Resources           ResourceInfo
	LastUpdated         time.Time
}

type WorkerCapabilities struct {
	SupportedFunctions []string
	SupportedFrameworks []string
	MaxParallelTasks   int32
	MaxMemoryGB        float64
	PerformanceScore   float64
	Tags               map[string]string
}

type WorkerInfo struct {
	WorkerID       string
	Name           string
	Address        string
	Type           WorkerType
	Status         WorkerStatus
	Capabilities   WorkerCapabilities
	Load           LoadInfo
	RegisteredAt   time.Time
	LastHeartbeat  time.Time
	Version        string
	Zone           string
}

type Task struct {
	TaskID              string
	ExperimentName      string
	Description         string
	Priority            int32
	Status              TaskStatus
	Objective           compute.ObjectiveFunction
	Gradient            compute.GradientFunction
	ParameterCombinations []map[string]float64
	InitialPoint        []float64
	OptimizerConfig     compute.OptimizerConfig
	CreatedAt           time.Time
	UpdatedAt           time.Time
	Deadline            time.Time
	Timeout             time.Duration
	MaxRetries          int32
	RetryCount          int32
	CreatedBy           string
	Tags                map[string]string
}

type TaskResult struct {
	TaskID           string
	Status           TaskStatus
	OptimalPoint     []float64
	OptimalValue     float64
	Iterations       int64
	DurationMs       int64
	ErrorMessage     string
	CheckpointPath   string
	ResultPath       string
	CompletedAt      time.Time
	CacheHit         bool
	ParameterCombination map[string]float64
	CurrentX         []float64
	CurrentF         float64
}

type TaskProgress struct {
	TaskID      string
	Status      TaskStatus
	CurrentIter int64
	CurrentX    []float64
	CurrentF    float64
	Progress    float64
	Timestamp   time.Time
}

type HeartbeatConfig struct {
	Interval      time.Duration
	Timeout       time.Duration
	MaxRetries    int
	RetryInterval time.Duration
}

type CacheConfig struct {
	MaxSize       int
	TTL           time.Duration
	PersistPath   string
	PersistInterval time.Duration
}

type CollectorConfig struct {
	Interval    time.Duration
	CPUInterval time.Duration
}

type ExecutorConfig struct {
	MaxParallelTasks int32
	ProgressInterval time.Duration
	TaskTimeout      time.Duration
}

type Config struct {
	WorkerID     string
	Name         string
	Address      string
	Type         WorkerType
	Version      string
	Zone         string
	Tags         map[string]string
	Heartbeat    HeartbeatConfig
	Cache        CacheConfig
	Collector    CollectorConfig
	Executor     ExecutorConfig
	Capabilities WorkerCapabilities
}

type CommandType int

const (
	CommandTypeUnspecified CommandType = iota
	CommandTypePause
	CommandTypeResume
	CommandTypeDrain
	CommandTypeShutdown
	CommandTypeUpdateCapabilities
)

type WorkerCommand struct {
	Type       CommandType
	WorkerID   string
	Message    string
	IssuedAt   time.Time
	Parameters map[string]string
}

type TaskExecutionContext struct {
	ctx        context.Context
	cancel     context.CancelFunc
	task       *Task
	progress   chan TaskProgress
	result     chan TaskResult
	startedAt  time.Time
	executorID string
}

type runningTask struct {
	ctx     *TaskExecutionContext
	mu      sync.Mutex
}

type cacheEntry struct {
	key        string
	value      interface{}
	expiresAt  time.Time
	accessTime time.Time
}
