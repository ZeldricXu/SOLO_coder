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

type ComputeHandler interface {
	SubmitResult(ctx context.Context, req *v1.SubmitResultRequest) (*v1.SubmitResultResponse, error)
	GetShard(ctx context.Context, req *v1.GetShardRequest) (*v1.GetShardResponse, error)
	HandleComputeRequest(ctx context.Context, req *v1.ComputeRequest) (*v1.ComputeResponse, error)
	HandleHeartbeat(ctx context.Context, req *v1.Heartbeat) (*v1.TaskStatusUpdate, error)
	HandleProgress(ctx context.Context, req *v1.ProgressUpdate) (*v1.TaskCancellation, error)
}

type ComputeServer struct {
	v1.UnimplementedComputeServiceServer
	handler      ComputeHandler
	logger       *zap.Logger
	mu           sync.RWMutex
	computeStreams map[string]v1.ComputeService_StreamComputeServer
	heartbeatStreams map[string]v1.ComputeService_StreamHeartbeatServer
	progressStreams map[string]v1.ComputeService_StreamProgressServer
}

func NewComputeServer(handler ComputeHandler, logger *zap.Logger) *ComputeServer {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &ComputeServer{
		handler:          handler,
		logger:           logger,
		computeStreams:   make(map[string]v1.ComputeService_StreamComputeServer),
		heartbeatStreams: make(map[string]v1.ComputeService_StreamHeartbeatServer),
		progressStreams:  make(map[string]v1.ComputeService_StreamProgressServer),
	}
}

func (s *ComputeServer) SubmitResult(ctx context.Context, req *v1.SubmitResultRequest) (*v1.SubmitResultResponse, error) {
	if req == nil || req.Result == nil {
		return nil, status.Error(codes.InvalidArgument, "result is required")
	}
	if req.Result.TaskId == "" {
		return nil, status.Error(codes.InvalidArgument, "task ID is required")
	}

	s.logger.Debug("SubmitResult called",
		zap.String("task_id", req.Result.TaskId),
		zap.Int32("shard_id", req.Result.ShardId),
		zap.Int32("total_evaluations", req.Result.TotalEvaluations),
	)

	if s.handler != nil {
		return s.handler.SubmitResult(ctx, req)
	}

	return &v1.SubmitResultResponse{
		Success: true,
		Message: "result submitted successfully",
	}, nil
}

func (s *ComputeServer) GetShard(ctx context.Context, req *v1.GetShardRequest) (*v1.GetShardResponse, error) {
	if req == nil || req.WorkerId == "" {
		return nil, status.Error(codes.InvalidArgument, "worker ID is required")
	}

	s.logger.Debug("GetShard called",
		zap.String("worker_id", req.WorkerId),
		zap.String("task_id", req.TaskId),
	)

	if s.handler != nil {
		return s.handler.GetShard(ctx, req)
	}

	return &v1.GetShardResponse{
		Shard: &v1.TaskShard{
			TaskId:      req.TaskId,
			ShardId:     0,
			TotalShards: 1,
		},
	}, nil
}

func (s *ComputeServer) StreamCompute(stream v1.ComputeService_StreamComputeServer) error {
	streamID := generateStreamID()
	s.logger.Debug("StreamCompute connected", zap.String("stream_id", streamID))

	s.mu.Lock()
	s.computeStreams[streamID] = stream
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.computeStreams, streamID)
		s.mu.Unlock()
		s.logger.Debug("StreamCompute disconnected", zap.String("stream_id", streamID))
	}()

	ctx := stream.Context()
	recvCh := make(chan *v1.ComputeRequest, 100)
	errCh := make(chan error, 1)

	go func() {
		defer close(recvCh)
		for {
			req, err := stream.Recv()
			if err != nil {
				if !errors.Is(err, io.EOF) {
					errCh <- err
				}
				return
			}
			select {
			case recvCh <- req:
			case <-ctx.Done():
				return
			}
		}
	}()

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case err := <-errCh:
			s.logger.Error("StreamCompute recv error",
				zap.String("stream_id", streamID),
				zap.Error(err),
			)
			return status.Errorf(codes.Internal, "recv error: %v", err)
		case req, ok := <-recvCh:
			if !ok {
				return nil
			}
			if req == nil {
				continue
			}

			s.logger.Debug("StreamCompute received request",
				zap.String("stream_id", streamID),
			)

			var resp *v1.ComputeResponse
			var err error

			if s.handler != nil {
				resp, err = s.handler.HandleComputeRequest(ctx, req)
			} else {
				resp = s.handleComputeRequestDefault(ctx, req)
			}

			if err != nil {
				s.logger.Error("StreamCompute handle request error",
					zap.String("stream_id", streamID),
					zap.Error(err),
				)
				continue
			}

			if resp != nil {
				if err := stream.Send(resp); err != nil {
					s.logger.Error("StreamCompute send error",
						zap.String("stream_id", streamID),
						zap.Error(err),
					)
					return status.Errorf(codes.Internal, "send error: %v", err)
				}
			}
		}
	}
}

func (s *ComputeServer) handleComputeRequestDefault(ctx context.Context, req *v1.ComputeRequest) *v1.ComputeResponse {
	switch r := req.Request.(type) {
	case *v1.ComputeRequest_Assignment:
		return &v1.ComputeResponse{
			Response: &v1.ComputeResponse_Ack{
				Ack: &v1.TaskAssignmentAck{
					TaskId:   r.Assignment.Shard.TaskId,
					ShardId:  r.Assignment.Shard.ShardId,
					Accepted: true,
				},
			},
		}
	case *v1.ComputeRequest_Heartbeat:
		return &v1.ComputeResponse{
			Response: &v1.ComputeResponse_StatusUpdate{
				StatusUpdate: &v1.TaskStatusUpdate{
					TaskId:  r.Heartbeat.TaskId,
					ShardId: r.Heartbeat.ShardId,
					Status:  v1.TaskStatus_TASK_STATUS_RUNNING,
				},
			},
		}
	case *v1.ComputeRequest_Progress:
		s.logger.Debug("Received progress update",
			zap.String("task_id", r.Progress.TaskId),
			zap.Int32("shard_id", r.Progress.ShardId),
			zap.Float64("percentage", r.Progress.Percentage),
		)
		return nil
	case *v1.ComputeRequest_Result:
		s.logger.Debug("Received compute result",
			zap.String("task_id", r.Result.TaskId),
			zap.Int32("shard_id", r.Result.ShardId),
		)
		return &v1.ComputeResponse{
			Response: &v1.ComputeResponse_Ack{
				Ack: &v1.TaskAssignmentAck{
					TaskId:   r.Result.TaskId,
					ShardId:  r.Result.ShardId,
					Accepted: true,
					Message:  "result received",
				},
			},
		}
	case *v1.ComputeRequest_Intermediate:
		s.logger.Debug("Received intermediate state",
			zap.String("task_id", r.Intermediate.CurrentParameters.String()),
			zap.Int32("iteration", r.Intermediate.Iteration),
		)
		return nil
	case *v1.ComputeRequest_Error:
		s.logger.Error("Received compute error",
			zap.String("task_id", r.Error.TaskId),
			zap.Int32("shard_id", r.Error.ShardId),
			zap.String("error", r.Error.Message),
		)
		return &v1.ComputeResponse{
			Response: &v1.ComputeResponse_Cancellation{
				Cancellation: &v1.TaskCancellation{
					TaskId:  r.Error.TaskId,
					ShardId: r.Error.ShardId,
					Reason:  "compute error occurred",
				},
			},
		}
	default:
		return nil
	}
}

func (s *ComputeServer) StreamHeartbeat(stream v1.ComputeService_StreamHeartbeatServer) error {
	streamID := generateStreamID()
	s.logger.Debug("StreamHeartbeat connected", zap.String("stream_id", streamID))

	s.mu.Lock()
	s.heartbeatStreams[streamID] = stream
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.heartbeatStreams, streamID)
		s.mu.Unlock()
		s.logger.Debug("StreamHeartbeat disconnected", zap.String("stream_id", streamID))
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
			s.logger.Error("StreamHeartbeat recv error",
				zap.String("stream_id", streamID),
				zap.Error(err),
			)
			return status.Errorf(codes.Internal, "recv error: %v", err)
		}

		if req == nil {
			continue
		}

		s.logger.Debug("StreamHeartbeat received heartbeat",
			zap.String("stream_id", streamID),
			zap.String("worker_id", req.WorkerId),
			zap.String("task_id", req.TaskId),
			zap.Bool("healthy", req.Healthy),
		)

		var resp *v1.TaskStatusUpdate
		var errHandle error

		if s.handler != nil {
			resp, errHandle = s.handler.HandleHeartbeat(ctx, req)
		} else {
			resp = &v1.TaskStatusUpdate{
				TaskId:  req.TaskId,
				ShardId: req.ShardId,
				Status:  v1.TaskStatus_TASK_STATUS_RUNNING,
			}
		}

		if errHandle != nil {
			s.logger.Error("StreamHeartbeat handle error",
				zap.String("stream_id", streamID),
				zap.Error(errHandle),
			)
			continue
		}

		if resp != nil {
			if err := stream.Send(resp); err != nil {
				s.logger.Error("StreamHeartbeat send error",
					zap.String("stream_id", streamID),
					zap.Error(err),
				)
				return status.Errorf(codes.Internal, "send error: %v", err)
			}
		}
	}
}

func (s *ComputeServer) StreamProgress(stream v1.ComputeService_StreamProgressServer) error {
	streamID := generateStreamID()
	s.logger.Debug("StreamProgress connected", zap.String("stream_id", streamID))

	s.mu.Lock()
	s.progressStreams[streamID] = stream
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.progressStreams, streamID)
		s.mu.Unlock()
		s.logger.Debug("StreamProgress disconnected", zap.String("stream_id", streamID))
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
			s.logger.Error("StreamProgress recv error",
				zap.String("stream_id", streamID),
				zap.Error(err),
			)
			return status.Errorf(codes.Internal, "recv error: %v", err)
		}

		if req == nil {
			continue
		}

		s.logger.Debug("StreamProgress received update",
			zap.String("stream_id", streamID),
			zap.String("task_id", req.TaskId),
			zap.Int32("shard_id", req.ShardId),
			zap.Float64("percentage", req.Percentage),
		)

		var resp *v1.TaskCancellation
		var errHandle error

		if s.handler != nil {
			resp, errHandle = s.handler.HandleProgress(ctx, req)
		}

		if errHandle != nil {
			s.logger.Error("StreamProgress handle error",
				zap.String("stream_id", streamID),
				zap.Error(errHandle),
			)
			continue
		}

		if resp != nil {
			if err := stream.Send(resp); err != nil {
				s.logger.Error("StreamProgress send error",
					zap.String("stream_id", streamID),
					zap.Error(err),
				)
				return status.Errorf(codes.Internal, "send error: %v", err)
			}
		}
	}
}

func (s *ComputeServer) SendCancellation(taskID string, shardID int32, reason string) {
	cancellation := &v1.TaskCancellation{
		TaskId:  taskID,
		ShardId: shardID,
		Reason:  reason,
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	for streamID, stream := range s.progressStreams {
		if err := stream.Send(cancellation); err != nil {
			s.logger.Warn("SendCancellation failed",
				zap.String("stream_id", streamID),
				zap.Error(err),
			)
		}
	}
}

func (s *ComputeServer) GetActiveStreams() (compute, heartbeat, progress int) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.computeStreams), len(s.heartbeatStreams), len(s.progressStreams)
}
