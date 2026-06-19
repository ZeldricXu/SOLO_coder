package server

import (
	"context"
	"errors"
	"io"
	"sync"

	v1 "github.com/df1-96/experiment/pkg/grpcapi/distcomp/v1"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type TaskHandler interface {
	CreateTask(ctx context.Context, req *v1.CreateTaskRequest) (*v1.CreateTaskResponse, error)
	GetTask(ctx context.Context, req *v1.GetTaskRequest) (*v1.GetTaskResponse, error)
	ListTasks(ctx context.Context, req *v1.ListTasksRequest) (*v1.ListTasksResponse, error)
	UpdateTaskStatus(ctx context.Context, req *v1.UpdateTaskStatusRequest) (*v1.UpdateTaskStatusResponse, error)
	CancelTask(ctx context.Context, req *v1.CancelTaskRequest) (*v1.CancelTaskResponse, error)
}

type TaskServer struct {
	v1.UnimplementedTaskServiceServer
	handler TaskHandler
	logger  *zap.Logger
	mu      sync.RWMutex
	streams map[string]v1.TaskService_StreamTaskStatusServer
}

func NewTaskServer(handler TaskHandler, logger *zap.Logger) *TaskServer {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &TaskServer{
		handler: handler,
		logger:  logger,
		streams: make(map[string]v1.TaskService_StreamTaskStatusServer),
	}
}

func (s *TaskServer) CreateTask(ctx context.Context, req *v1.CreateTaskRequest) (*v1.CreateTaskResponse, error) {
	if req == nil {
		return nil, status.Error(codes.InvalidArgument, "request is nil")
	}
	if req.ExperimentName == "" {
		return nil, status.Error(codes.InvalidArgument, "experiment name is required")
	}
	if req.Objective == nil {
		return nil, status.Error(codes.InvalidArgument, "objective is required")
	}

	s.logger.Debug("CreateTask called",
		zap.String("experiment_name", req.ExperimentName),
		zap.String("created_by", req.CreatedBy),
	)

	if s.handler != nil {
		return s.handler.CreateTask(ctx, req)
	}

	return &v1.CreateTaskResponse{
		TaskId: generateTaskID(),
		Status: v1.TaskStatus_TASK_STATUS_PENDING,
	}, nil
}

func (s *TaskServer) GetTask(ctx context.Context, req *v1.GetTaskRequest) (*v1.GetTaskResponse, error) {
	if req == nil || req.TaskId == "" {
		return nil, status.Error(codes.InvalidArgument, "task ID is required")
	}

	s.logger.Debug("GetTask called", zap.String("task_id", req.TaskId))

	if s.handler != nil {
		return s.handler.GetTask(ctx, req)
	}

	return &v1.GetTaskResponse{
		Task: &v1.Task{
			TaskId:   req.TaskId,
			Status:   v1.TaskStatus_TASK_STATUS_RUNNING,
			Priority: v1.TaskPriority_TASK_PRIORITY_MEDIUM,
		},
	}, nil
}

func (s *TaskServer) ListTasks(ctx context.Context, req *v1.ListTasksRequest) (*v1.ListTasksResponse, error) {
	if req == nil {
		return nil, status.Error(codes.InvalidArgument, "request is nil")
	}

	s.logger.Debug("ListTasks called",
		zap.String("experiment_name", req.ExperimentName),
		zap.Int32("status", int32(req.Status)),
	)

	if s.handler != nil {
		return s.handler.ListTasks(ctx, req)
	}

	return &v1.ListTasksResponse{
		Tasks:      []*v1.Task{},
		TotalCount: 0,
	}, nil
}

func (s *TaskServer) UpdateTaskStatus(ctx context.Context, req *v1.UpdateTaskStatusRequest) (*v1.UpdateTaskStatusResponse, error) {
	if req == nil || req.TaskId == "" {
		return nil, status.Error(codes.InvalidArgument, "task ID is required")
	}

	s.logger.Debug("UpdateTaskStatus called",
		zap.String("task_id", req.TaskId),
		zap.Int32("status", int32(req.Status)),
	)

	if s.handler != nil {
		return s.handler.UpdateTaskStatus(ctx, req)
	}

	return &v1.UpdateTaskStatusResponse{Success: true}, nil
}

func (s *TaskServer) CancelTask(ctx context.Context, req *v1.CancelTaskRequest) (*v1.CancelTaskResponse, error) {
	if req == nil || req.TaskId == "" {
		return nil, status.Error(codes.InvalidArgument, "task ID is required")
	}

	s.logger.Debug("CancelTask called",
		zap.String("task_id", req.TaskId),
		zap.String("reason", req.Reason),
	)

	if s.handler != nil {
		return s.handler.CancelTask(ctx, req)
	}

	return &v1.CancelTaskResponse{Success: true}, nil
}

func (s *TaskServer) StreamTaskStatus(stream v1.TaskService_StreamTaskStatusServer) error {
	streamID := generateStreamID()
	s.logger.Debug("StreamTaskStatus connected", zap.String("stream_id", streamID))

	s.mu.Lock()
	s.streams[streamID] = stream
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.streams, streamID)
		s.mu.Unlock()
		s.logger.Debug("StreamTaskStatus disconnected", zap.String("stream_id", streamID))
	}()

	ctx := stream.Context()

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}

		req, err := stream.Recv()
		if err != nil {
			if errors.Is(err, io.EOF) {
				return nil
			}
			s.logger.Error("StreamTaskStatus recv error",
				zap.String("stream_id", streamID),
				zap.Error(err),
			)
			return status.Errorf(codes.Internal, "recv error: %v", err)
		}

		if req == nil {
			continue
		}

		s.logger.Debug("StreamTaskStatus received request",
			zap.String("stream_id", streamID),
			zap.String("task_id", req.TaskId),
		)

		task, err := s.GetTask(ctx, req)
		if err != nil {
			s.logger.Error("StreamTaskStatus get task error",
				zap.String("stream_id", streamID),
				zap.Error(err),
			)
			continue
		}

		if task != nil && task.Task != nil {
			if err := stream.Send(task.Task); err != nil {
				s.logger.Error("StreamTaskStatus send error",
					zap.String("stream_id", streamID),
					zap.Error(err),
				)
				return status.Errorf(codes.Internal, "send error: %v", err)
			}
		}
	}
}

func (s *TaskServer) StreamCreateTask(stream v1.TaskService_StreamCreateTaskServer) error {
	streamID := generateStreamID()
	s.logger.Debug("StreamCreateTask connected", zap.String("stream_id", streamID))

	ctx := stream.Context()

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}

		req, err := stream.Recv()
		if err != nil {
			if errors.Is(err, io.EOF) {
				return nil
			}
			s.logger.Error("StreamCreateTask recv error",
				zap.String("stream_id", streamID),
				zap.Error(err),
			)
			return status.Errorf(codes.Internal, "recv error: %v", err)
		}

		if req == nil {
			continue
		}

		s.logger.Debug("StreamCreateTask received request",
			zap.String("stream_id", streamID),
			zap.String("experiment_name", req.ExperimentName),
		)

		resp, err := s.CreateTask(ctx, req)
		if err != nil {
			s.logger.Error("StreamCreateTask create task error",
				zap.String("stream_id", streamID),
				zap.Error(err),
			)
			resp = &v1.CreateTaskResponse{
				Status: v1.TaskStatus_TASK_STATUS_FAILED,
			}
		}

		if err := stream.Send(resp); err != nil {
			s.logger.Error("StreamCreateTask send error",
				zap.String("stream_id", streamID),
				zap.Error(err),
			)
			return status.Errorf(codes.Internal, "send error: %v", err)
		}
	}
}

func (s *TaskServer) BroadcastTaskStatus(task *v1.Task) {
	if task == nil {
		return
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	for streamID, stream := range s.streams {
		if err := stream.Send(task); err != nil {
			s.logger.Warn("BroadcastTaskStatus send failed",
				zap.String("stream_id", streamID),
				zap.Error(err),
			)
		}
	}
}

func (s *TaskServer) GetActiveStreams() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.streams)
}
