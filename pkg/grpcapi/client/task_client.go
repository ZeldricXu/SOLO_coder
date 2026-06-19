package client

import (
	"context"
	"errors"
	"io"
	"sync"

	v1 "github.com/df1-96/experiment/pkg/grpcapi/distcomp/v1"
	"go.uber.org/zap"
	"google.golang.org/grpc"
)

type TaskClient struct {
	client   *GRPCClient
	grpcClient v1.TaskServiceClient
	logger   *zap.Logger
	mu       sync.RWMutex
	streamTaskStatus v1.TaskService_StreamTaskStatusClient
	streamCreateTask v1.TaskService_StreamCreateTaskClient
}

func NewTaskClient(client *GRPCClient, logger *zap.Logger) *TaskClient {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &TaskClient{
		client: client,
		logger: logger,
	}
}

func (c *TaskClient) init() error {
	c.mu.RLock()
	if c.grpcClient != nil {
		c.mu.RUnlock()
		return nil
	}
	c.mu.RUnlock()

	if !c.client.IsConnected() {
		if err := c.client.Connect(context.Background()); err != nil {
			return err
		}
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	if c.grpcClient == nil {
		c.grpcClient = v1.NewTaskServiceClient(c.client.GetConn())
	}
	return nil
}

func (c *TaskClient) CreateTask(ctx context.Context, req *v1.CreateTaskRequest, opts ...grpc.CallOption) (*v1.CreateTaskResponse, error) {
	if err := c.init(); err != nil {
		return nil, err
	}

	ctx, cancel := c.client.NewContext(ctx)
	defer cancel()

	c.logger.Debug("CreateTask request",
		zap.String("experiment_name", req.ExperimentName),
		zap.String("created_by", req.CreatedBy),
	)

	return c.grpcClient.CreateTask(ctx, req, opts...)
}

func (c *TaskClient) GetTask(ctx context.Context, req *v1.GetTaskRequest, opts ...grpc.CallOption) (*v1.GetTaskResponse, error) {
	if err := c.init(); err != nil {
		return nil, err
	}

	ctx, cancel := c.client.NewContext(ctx)
	defer cancel()

	c.logger.Debug("GetTask request", zap.String("task_id", req.TaskId))

	return c.grpcClient.GetTask(ctx, req, opts...)
}

func (c *TaskClient) ListTasks(ctx context.Context, req *v1.ListTasksRequest, opts ...grpc.CallOption) (*v1.ListTasksResponse, error) {
	if err := c.init(); err != nil {
		return nil, err
	}

	ctx, cancel := c.client.NewContext(ctx)
	defer cancel()

	c.logger.Debug("ListTasks request",
		zap.String("experiment_name", req.ExperimentName),
		zap.Int32("page_size", req.PageSize),
	)

	return c.grpcClient.ListTasks(ctx, req, opts...)
}

func (c *TaskClient) UpdateTaskStatus(ctx context.Context, req *v1.UpdateTaskStatusRequest, opts ...grpc.CallOption) (*v1.UpdateTaskStatusResponse, error) {
	if err := c.init(); err != nil {
		return nil, err
	}

	ctx, cancel := c.client.NewContext(ctx)
	defer cancel()

	c.logger.Debug("UpdateTaskStatus request",
		zap.String("task_id", req.TaskId),
		zap.Int32("status", int32(req.Status)),
	)

	return c.grpcClient.UpdateTaskStatus(ctx, req, opts...)
}

func (c *TaskClient) CancelTask(ctx context.Context, req *v1.CancelTaskRequest, opts ...grpc.CallOption) (*v1.CancelTaskResponse, error) {
	if err := c.init(); err != nil {
		return nil, err
	}

	ctx, cancel := c.client.NewContext(ctx)
	defer cancel()

	c.logger.Debug("CancelTask request",
		zap.String("task_id", req.TaskId),
		zap.String("reason", req.Reason),
	)

	return c.grpcClient.CancelTask(ctx, req, opts...)
}

func (c *TaskClient) StreamTaskStatus(ctx context.Context, opts ...grpc.CallOption) error {
	if err := c.init(); err != nil {
		return err
	}

	c.mu.Lock()
	if c.streamTaskStatus != nil {
		c.mu.Unlock()
		return errors.New("stream task status already running")
	}

	stream, err := c.grpcClient.StreamTaskStatus(ctx, opts...)
	if err != nil {
		c.mu.Unlock()
		return err
	}
	c.streamTaskStatus = stream
	c.mu.Unlock()

	c.logger.Info("StreamTaskStatus stream started")
	return nil
}

func (c *TaskClient) SendTaskStatusRequest(req *v1.GetTaskRequest) error {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.streamTaskStatus == nil {
		return errors.New("stream task status not started")
	}

	c.logger.Debug("SendTaskStatusRequest", zap.String("task_id", req.TaskId))
	return c.streamTaskStatus.Send(req)
}

func (c *TaskClient) RecvTaskStatus() (*v1.Task, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.streamTaskStatus == nil {
		return nil, errors.New("stream task status not started")
	}

	task, err := c.streamTaskStatus.Recv()
	if err != nil {
		if errors.Is(err, io.EOF) {
			c.logger.Debug("StreamTaskStatus stream closed by server")
		} else {
			c.logger.Error("StreamTaskStatus recv error", zap.Error(err))
		}
		return nil, err
	}

	c.logger.Debug("Received task status update",
		zap.String("task_id", task.TaskId),
		zap.Int32("status", int32(task.Status)),
	)
	return task, nil
}

func (c *TaskClient) CloseTaskStatusStream() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.streamTaskStatus != nil {
		err := c.streamTaskStatus.CloseSend()
		c.streamTaskStatus = nil
		c.logger.Info("StreamTaskStatus stream closed")
		return err
	}
	return nil
}

func (c *TaskClient) StreamCreateTask(ctx context.Context, opts ...grpc.CallOption) error {
	if err := c.init(); err != nil {
		return err
	}

	c.mu.Lock()
	if c.streamCreateTask != nil {
		c.mu.Unlock()
		return errors.New("stream create task already running")
	}

	stream, err := c.grpcClient.StreamCreateTask(ctx, opts...)
	if err != nil {
		c.mu.Unlock()
		return err
	}
	c.streamCreateTask = stream
	c.mu.Unlock()

	c.logger.Info("StreamCreateTask stream started")
	return nil
}

func (c *TaskClient) SendCreateTaskRequest(req *v1.CreateTaskRequest) error {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.streamCreateTask == nil {
		return errors.New("stream create task not started")
	}

	c.logger.Debug("SendCreateTaskRequest",
		zap.String("experiment_name", req.ExperimentName),
	)
	return c.streamCreateTask.Send(req)
}

func (c *TaskClient) RecvCreateTaskResponse() (*v1.CreateTaskResponse, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.streamCreateTask == nil {
		return nil, errors.New("stream create task not started")
	}

	resp, err := c.streamCreateTask.Recv()
	if err != nil {
		if errors.Is(err, io.EOF) {
			c.logger.Debug("StreamCreateTask stream closed by server")
		} else {
			c.logger.Error("StreamCreateTask recv error", zap.Error(err))
		}
		return nil, err
	}

	c.logger.Debug("Received create task response",
		zap.String("task_id", resp.TaskId),
		zap.Int32("status", int32(resp.Status)),
	)
	return resp, nil
}

func (c *TaskClient) CloseCreateTaskStream() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.streamCreateTask != nil {
		err := c.streamCreateTask.CloseSend()
		c.streamCreateTask = nil
		c.logger.Info("StreamCreateTask stream closed")
		return err
	}
	return nil
}

func (c *TaskClient) StartTaskStatusMonitoring(ctx context.Context, taskIDs []string, onUpdate func(*v1.Task)) error {
	if err := c.StreamTaskStatus(ctx); err != nil {
		return err
	}

	go func() {
		for _, taskID := range taskIDs {
			req := &v1.GetTaskRequest{TaskId: taskID}
			if err := c.SendTaskStatusRequest(req); err != nil {
				c.logger.Error("Failed to send task status request",
					zap.String("task_id", taskID),
					zap.Error(err),
				)
				return
			}
		}

		for {
			select {
			case <-ctx.Done():
				c.CloseTaskStatusStream()
				return
			default:
			}

			task, err := c.RecvTaskStatus()
			if err != nil {
				if !errors.Is(err, io.EOF) {
					c.logger.Error("Task status monitoring error", zap.Error(err))
				}
				return
			}

			if onUpdate != nil {
				onUpdate(task)
			}
		}
	}()

	return nil
}

func (c *TaskClient) Close() error {
	var errs []error

	if err := c.CloseTaskStatusStream(); err != nil {
		errs = append(errs, err)
	}
	if err := c.CloseCreateTaskStream(); err != nil {
		errs = append(errs, err)
	}

	if len(errs) > 0 {
		return errors.Join(errs...)
	}
	return nil
}
