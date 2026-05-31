package aggregation

import (
	"context"
	"time"

	"github.com/edgevision/edgevision/internal/domain/model"
)

type AsyncTaskStatus string

const (
	TaskStatusPending   AsyncTaskStatus = "pending"
	TaskStatusRunning   AsyncTaskStatus = "running"
	TaskStatusCompleted AsyncTaskStatus = "completed"
	TaskStatusFailed    AsyncTaskStatus = "failed"
	TaskStatusCancelled AsyncTaskStatus = "cancelled"
)

type AggregationTask struct {
	TaskID     string                 `json:"task_id"`
	StreamID   string                 `json:"stream_id"`
	Metric     string                 `json:"metric"`
	Status     AsyncTaskStatus        `json:"status"`
	Progress   float64                `json:"progress"`
	Result     *model.AggregatedData  `json:"result,omitempty"`
	Error      string                 `json:"error,omitempty"`
	CreatedAt  time.Time              `json:"created_at"`
	StartedAt  *time.Time             `json:"started_at,omitempty"`
	FinishedAt *time.Time             `json:"finished_at,omitempty"`
	Callbacks  []AggregationCallback  `json:"-"`
	Context    context.Context        `json:"-"`
}

type AggregationCallback interface {
	OnComplete(ctx context.Context, task *AggregationTask)
	OnFailure(ctx context.Context, task *AggregationTask)
	OnProgress(ctx context.Context, task *AggregationTask)
}

type AsyncTaskManager interface {
	SubmitTask(ctx context.Context, streamID string, metric string) (*AggregationTask, error)
	GetTask(ctx context.Context, taskID string) (*AggregationTask, bool)
	ListTasks(ctx context.Context, streamID string, status AsyncTaskStatus) []*AggregationTask
	CancelTask(ctx context.Context, taskID string) bool
	RegisterCallback(taskID string, callback AggregationCallback) error
	Start(ctx context.Context)
	Stop()
}

type TaskQueue interface {
	Enqueue(ctx context.Context, task *AggregationTask) error
	Dequeue(ctx context.Context) (*AggregationTask, error)
	Size() int
}

type WorkerPool interface {
	Start(ctx context.Context)
	Stop()
	Submit(task *AggregationTask)
	Workers() int
}

type AsyncAggregationService interface {
	AggregateDataAsync(ctx context.Context, streamID string, callback AggregationCallback) (*AggregationTask, error)
	AggregateDataSync(ctx context.Context, streamID string) (*model.AggregatedData, error)
	GetTaskStatus(ctx context.Context, taskID string) (*AggregationTask, error)
	CancelTask(ctx context.Context, taskID string) error
	WaitForTask(ctx context.Context, taskID string, timeout time.Duration) (*AggregationTask, error)
}

type SimpleCallback struct {
	CompleteFunc func(ctx context.Context, task *AggregationTask)
	FailureFunc  func(ctx context.Context, task *AggregationTask)
	ProgressFunc func(ctx context.Context, task *AggregationTask)
}

func (c *SimpleCallback) OnComplete(ctx context.Context, task *AggregationTask) {
	if c.CompleteFunc != nil {
		c.CompleteFunc(ctx, task)
	}
}

func (c *SimpleCallback) OnFailure(ctx context.Context, task *AggregationTask) {
	if c.FailureFunc != nil {
		c.FailureFunc(ctx, task)
	}
}

func (c *SimpleCallback) OnProgress(ctx context.Context, task *AggregationTask) {
	if c.ProgressFunc != nil {
		c.ProgressFunc(ctx, task)
	}
}

type EventNotifier interface {
	NotifyTaskComplete(ctx context.Context, task *AggregationTask)
	NotifyTaskFailed(ctx context.Context, task *AggregationTask)
	NotifyThresholdExceeded(ctx context.Context, streamID, metric string, value, threshold float64)
}
