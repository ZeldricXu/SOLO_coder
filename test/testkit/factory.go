package testkit

import (
	"fmt"
	"time"

	"github.com/distributed-task-scheduler/internal/dag"
	"github.com/distributed-task-scheduler/internal/models"
	"github.com/google/uuid"
)

type TaskBuilder struct {
	task *models.Task
}

func NewTaskBuilder() *TaskBuilder {
	now := time.Now()
	return &TaskBuilder{
		task: &models.Task{
			ID:             uuid.New().String(),
			Namespace:      "test-ns",
			Name:           fmt.Sprintf("test-task-%s", uuid.New().String()[:8]),
			Type:           models.TaskTypeCron,
			CronExpression: "*/5 * * * *",
			TimeoutSeconds: 300,
			MaxRetries:     3,
			RetryBackoff:   "exponential",
			Status:         models.TaskStatusActive,
			CreatedBy:      "test-user",
			CreatedAt:      now,
			UpdatedAt:      now,
			Tags:           []string{"test"},
		},
	}
}

func (b *TaskBuilder) WithID(id string) *TaskBuilder {
	b.task.ID = id
	return b
}

func (b *TaskBuilder) WithNamespace(ns string) *TaskBuilder {
	b.task.Namespace = ns
	return b
}

func (b *TaskBuilder) WithName(name string) *TaskBuilder {
	b.task.Name = name
	return b
}

func (b *TaskBuilder) WithType(t models.TaskType) *TaskBuilder {
	b.task.Type = t
	return b
}

func (b *TaskBuilder) WithCron(expr string) *TaskBuilder {
	b.task.CronExpression = expr
	b.task.Type = models.TaskTypeCron
	return b
}

func (b *TaskBuilder) WithInterval(seconds int) *TaskBuilder {
	b.task.IntervalSeconds = seconds
	b.task.Type = models.TaskTypeDelay
	return b
}

func (b *TaskBuilder) WithDelay(seconds int) *TaskBuilder {
	b.task.DelaySeconds = seconds
	b.task.Type = models.TaskTypeDelay
	return b
}

func (b *TaskBuilder) WithMaxRetries(n int) *TaskBuilder {
	b.task.MaxRetries = n
	return b
}

func (b *TaskBuilder) WithPayload(payload []byte) *TaskBuilder {
	b.task.Payload = payload
	return b
}

func (b *TaskBuilder) WithStatus(s models.TaskStatus) *TaskBuilder {
	b.task.Status = s
	return b
}

func (b *TaskBuilder) WithDependencies(deps []string) *TaskBuilder {
	b.task.Dependencies = deps
	return b
}

func (b *TaskBuilder) WithNextRunAt(t time.Time) *TaskBuilder {
	b.task.NextRunAt = &t
	return b
}

func (b *TaskBuilder) Build() *models.Task {
	return b.task
}

type ExecutionBuilder struct {
	execution *models.Execution
}

func NewExecutionBuilder() *ExecutionBuilder {
	return &ExecutionBuilder{
		execution: &models.Execution{
			ID:           uuid.New().String(),
			TaskID:       uuid.New().String(),
			Namespace:    "test-ns",
			Status:       models.ExecutionStatusPending,
			InputPayload: []byte(`{"test": true}`),
			CreatedAt:    time.Now(),
		},
	}
}

func (b *ExecutionBuilder) WithID(id string) *ExecutionBuilder {
	b.execution.ID = id
	return b
}

func (b *ExecutionBuilder) WithTaskID(taskID string) *ExecutionBuilder {
	b.execution.TaskID = taskID
	return b
}

func (b *ExecutionBuilder) WithNamespace(ns string) *ExecutionBuilder {
	b.execution.Namespace = ns
	return b
}

func (b *ExecutionBuilder) WithStatus(s models.ExecutionStatus) *ExecutionBuilder {
	b.execution.Status = s
	return b
}

func (b *ExecutionBuilder) WithRetryCount(n int) *ExecutionBuilder {
	b.execution.RetryCount = n
	return b
}

func (b *ExecutionBuilder) WithError(msg string) *ExecutionBuilder {
	b.execution.ErrorMessage = msg
	return b
}

func (b *ExecutionBuilder) WithInputPayload(payload []byte) *ExecutionBuilder {
	b.execution.InputPayload = payload
	return b
}

func (b *ExecutionBuilder) WithParentExecutionID(id string) *ExecutionBuilder {
	b.execution.ParentExecutionID = id
	return b
}

func (b *ExecutionBuilder) Build() *models.Execution {
	return b.execution
}

type DAGBuilder struct {
	nodes []dag.Node
	edges []dag.Edge
}

func NewDAGBuilder() *DAGBuilder {
	return &DAGBuilder{}
}

func (b *DAGBuilder) WithNode(id, name, taskID string) *DAGBuilder {
	b.nodes = append(b.nodes, dag.Node{
		ID:     id,
		Name:   name,
		TaskID: taskID,
	})
	return b
}

func (b *DAGBuilder) WithEdge(from, to string) *DAGBuilder {
	b.edges = append(b.edges, dag.Edge{From: from, To: to})
	return b
}

func (b *DAGBuilder) WithConditionalEdge(from, to, condition string) *DAGBuilder {
	b.edges = append(b.edges, dag.Edge{From: from, To: to, Condition: condition})
	return b
}

func (b *DAGBuilder) Build() ([]dag.Node, []dag.Edge) {
	return b.nodes, b.edges
}

type WorkerBuilder struct {
	reg WorkerRegistrationData
}

type WorkerRegistrationData struct {
	ID           string
	Namespace    string
	Hostname     string
	GRPCAddr     string
	HTTPAddr     string
	Capabilities []string
	MaxLoad      int
}

func NewWorkerBuilder() *WorkerBuilder {
	suffix := uuid.New().String()[:8]
	return &WorkerBuilder{
		reg: WorkerRegistrationData{
			ID:           fmt.Sprintf("worker-%s", suffix),
			Namespace:    "test-ns",
			Hostname:     fmt.Sprintf("host-%s", suffix),
			GRPCAddr:     fmt.Sprintf("localhost:%s", suffix[:4]),
			HTTPAddr:     fmt.Sprintf("localhost:%s", suffix[:4]),
			Capabilities: []string{"generic"},
			MaxLoad:      100,
		},
	}
}

func (b *WorkerBuilder) WithID(id string) *WorkerBuilder {
	b.reg.ID = id
	return b
}

func (b *WorkerBuilder) WithNamespace(ns string) *WorkerBuilder {
	b.reg.Namespace = ns
	return b
}

func (b *WorkerBuilder) WithCapabilities(caps []string) *WorkerBuilder {
	b.reg.Capabilities = caps
	return b
}

func (b *WorkerBuilder) WithMaxLoad(n int) *WorkerBuilder {
	b.reg.MaxLoad = n
	return b
}

func (b *WorkerBuilder) Build() WorkerRegistrationData {
	return b.reg
}

func UniqueNamespace() string {
	return fmt.Sprintf("ns-%s", uuid.New().String()[:8])
}

func UniqueTaskName() string {
	return fmt.Sprintf("task-%s", uuid.New().String()[:8])
}
