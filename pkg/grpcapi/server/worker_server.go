package server

import (
	"context"
	"errors"
	"io"
	"sync"
	"time"

	v1 "github.com/df1-96/experiment/pkg/grpcapi/distcomp/v1"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/timestamppb"
)

type WorkerHandler interface {
	RegisterWorker(ctx context.Context, req *v1.RegisterWorkerRequest) (*v1.RegisterWorkerResponse, error)
	UnregisterWorker(ctx context.Context, req *v1.UnregisterWorkerRequest) (*v1.UnregisterWorkerResponse, error)
	Heartbeat(ctx context.Context, req *v1.HeartbeatRequest) (*v1.HeartbeatResponse, error)
	GetWorker(ctx context.Context, req *v1.GetWorkerRequest) (*v1.GetWorkerResponse, error)
	ListWorkers(ctx context.Context, req *v1.ListWorkersRequest) (*v1.ListWorkersResponse, error)
	UpdateWorkerStatus(ctx context.Context, req *v1.UpdateWorkerStatusRequest) (*v1.UpdateWorkerStatusResponse, error)
	HandleWorkerStreamRequest(ctx context.Context, req *v1.WorkerStreamRequest) (*v1.WorkerStreamResponse, error)
}

type WorkerServer struct {
	v1.UnimplementedWorkerServiceServer
	handler        WorkerHandler
	logger         *zap.Logger
	mu             sync.RWMutex
	workers        map[string]*v1.WorkerInfo
	workerStreams  map[string]v1.WorkerService_StreamWorkerServer
	heartbeatStreams map[string]v1.WorkerService_StreamHeartbeatServer
	heartbeatInterval time.Duration
}

func NewWorkerServer(handler WorkerHandler, logger *zap.Logger) *WorkerServer {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &WorkerServer{
		handler:          handler,
		logger:           logger,
		workers:          make(map[string]*v1.WorkerInfo),
		workerStreams:    make(map[string]v1.WorkerService_StreamWorkerServer),
		heartbeatStreams: make(map[string]v1.WorkerService_StreamHeartbeatServer),
		heartbeatInterval: 30 * time.Second,
	}
}

func (s *WorkerServer) RegisterWorker(ctx context.Context, req *v1.RegisterWorkerRequest) (*v1.RegisterWorkerResponse, error) {
	if req == nil {
		return nil, status.Error(codes.InvalidArgument, "request is nil")
	}
	if req.Name == "" {
		return nil, status.Error(codes.InvalidArgument, "worker name is required")
	}
	if req.Address == "" {
		return nil, status.Error(codes.InvalidArgument, "worker address is required")
	}

	s.logger.Debug("RegisterWorker called",
		zap.String("name", req.Name),
		zap.String("address", req.Address),
		zap.Int32("type", int32(req.Type)),
	)

	if s.handler != nil {
		return s.handler.RegisterWorker(ctx, req)
	}

	workerID := generateWorkerID()
	now := time.Now()

	s.mu.Lock()
	s.workers[workerID] = &v1.WorkerInfo{
		WorkerId:     workerID,
		Name:         req.Name,
		Address:      req.Address,
		Type:         req.Type,
		Status:       v1.WorkerStatus_WORKER_STATUS_IDLE,
		Capabilities: req.Capabilities,
		RegisteredAt: timestamppb.New(now),
		LastHeartbeat: timestamppb.New(now),
		Version:      req.Version,
		Zone:         req.Zone,
	}
	s.mu.Unlock()

	return &v1.RegisterWorkerResponse{
		WorkerId:   workerID,
		Success:    true,
		Message:    "worker registered successfully",
		AssignedAt: timestamppb.New(now),
	}, nil
}

func (s *WorkerServer) UnregisterWorker(ctx context.Context, req *v1.UnregisterWorkerRequest) (*v1.UnregisterWorkerResponse, error) {
	if req == nil || req.WorkerId == "" {
		return nil, status.Error(codes.InvalidArgument, "worker ID is required")
	}

	s.logger.Debug("UnregisterWorker called",
		zap.String("worker_id", req.WorkerId),
		zap.String("reason", req.Reason),
	)

	if s.handler != nil {
		return s.handler.UnregisterWorker(ctx, req)
	}

	s.mu.Lock()
	delete(s.workers, req.WorkerId)
	s.mu.Unlock()

	return &v1.UnregisterWorkerResponse{
		Success: true,
		Message: "worker unregistered successfully",
	}, nil
}

func (s *WorkerServer) Heartbeat(ctx context.Context, req *v1.HeartbeatRequest) (*v1.HeartbeatResponse, error) {
	if req == nil || req.WorkerId == "" {
		return nil, status.Error(codes.InvalidArgument, "worker ID is required")
	}

	s.logger.Debug("Heartbeat called",
		zap.String("worker_id", req.WorkerId),
		zap.Int32("status", int32(req.Status)),
	)

	if s.handler != nil {
		return s.handler.Heartbeat(ctx, req)
	}

	s.mu.Lock()
	if worker, ok := s.workers[req.WorkerId]; ok {
		worker.Status = req.Status
		worker.LastHeartbeat = timestamppb.Now()
		if req.Load != nil {
			worker.Load = req.Load
		}
	}
	s.mu.Unlock()

	return &v1.HeartbeatResponse{
		Acknowledged:         true,
		DesiredStatus:        v1.WorkerStatus_WORKER_STATUS_IDLE,
		Message:              "heartbeat acknowledged",
		NextHeartbeatDeadline: timestamppb.New(time.Now().Add(s.heartbeatInterval)),
	}, nil
}

func (s *WorkerServer) GetWorker(ctx context.Context, req *v1.GetWorkerRequest) (*v1.GetWorkerResponse, error) {
	if req == nil || req.WorkerId == "" {
		return nil, status.Error(codes.InvalidArgument, "worker ID is required")
	}

	s.logger.Debug("GetWorker called", zap.String("worker_id", req.WorkerId))

	if s.handler != nil {
		return s.handler.GetWorker(ctx, req)
	}

	s.mu.RLock()
	worker, ok := s.workers[req.WorkerId]
	s.mu.RUnlock()

	if !ok {
		return nil, status.Errorf(codes.NotFound, "worker %s not found", req.WorkerId)
	}

	return &v1.GetWorkerResponse{Worker: worker}, nil
}

func (s *WorkerServer) ListWorkers(ctx context.Context, req *v1.ListWorkersRequest) (*v1.ListWorkersResponse, error) {
	if req == nil {
		return nil, status.Error(codes.InvalidArgument, "request is nil")
	}

	s.logger.Debug("ListWorkers called",
		zap.Int32("status", int32(req.Status)),
		zap.Int32("type", int32(req.Type)),
		zap.String("zone", req.Zone),
	)

	if s.handler != nil {
		return s.handler.ListWorkers(ctx, req)
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	var workers []*v1.WorkerInfo
	for _, worker := range s.workers {
		if req.Status != v1.WorkerStatus_WORKER_STATUS_UNSPECIFIED && worker.Status != req.Status {
			continue
		}
		if req.Type != v1.WorkerType_WORKER_TYPE_UNSPECIFIED && worker.Type != req.Type {
			continue
		}
		if req.Zone != "" && worker.Zone != req.Zone {
			continue
		}
		workers = append(workers, worker)
	}

	return &v1.ListWorkersResponse{
		Workers:    workers,
		TotalCount: int32(len(workers)),
	}, nil
}

func (s *WorkerServer) UpdateWorkerStatus(ctx context.Context, req *v1.UpdateWorkerStatusRequest) (*v1.UpdateWorkerStatusResponse, error) {
	if req == nil || req.WorkerId == "" {
		return nil, status.Error(codes.InvalidArgument, "worker ID is required")
	}

	s.logger.Debug("UpdateWorkerStatus called",
		zap.String("worker_id", req.WorkerId),
		zap.Int32("status", int32(req.Status)),
	)

	if s.handler != nil {
		return s.handler.UpdateWorkerStatus(ctx, req)
	}

	s.mu.Lock()
	if worker, ok := s.workers[req.WorkerId]; ok {
		worker.Status = req.Status
	}
	s.mu.Unlock()

	return &v1.UpdateWorkerStatusResponse{Success: true}, nil
}

func (s *WorkerServer) StreamWorker(stream v1.WorkerService_StreamWorkerServer) error {
	streamID := generateStreamID()
	var workerID string

	s.logger.Debug("StreamWorker connected", zap.String("stream_id", streamID))

	s.mu.Lock()
	s.workerStreams[streamID] = stream
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.workerStreams, streamID)
		if workerID != "" {
			if worker, ok := s.workers[workerID]; ok {
				worker.Status = v1.WorkerStatus_WORKER_STATUS_OFFLINE
			}
		}
		s.mu.Unlock()
		s.logger.Debug("StreamWorker disconnected",
			zap.String("stream_id", streamID),
			zap.String("worker_id", workerID),
		)
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
			s.logger.Error("StreamWorker recv error",
				zap.String("stream_id", streamID),
				zap.Error(err),
			)
			return status.Errorf(codes.Internal, "recv error: %v", err)
		}

		if req == nil {
			continue
		}

		var resp *v1.WorkerStreamResponse
		var errHandle error

		if s.handler != nil {
			resp, errHandle = s.handler.HandleWorkerStreamRequest(ctx, req)
		} else {
			resp, workerID = s.handleWorkerStreamRequestDefault(ctx, req, workerID)
		}

		if errHandle != nil {
			s.logger.Error("StreamWorker handle error",
				zap.String("stream_id", streamID),
				zap.Error(errHandle),
			)
			continue
		}

		if resp != nil {
			if err := stream.Send(resp); err != nil {
				s.logger.Error("StreamWorker send error",
					zap.String("stream_id", streamID),
					zap.Error(err),
				)
				return status.Errorf(codes.Internal, "send error: %v", err)
			}
		}
	}
}

func (s *WorkerServer) handleWorkerStreamRequestDefault(ctx context.Context, req *v1.WorkerStreamRequest, currentWorkerID string) (*v1.WorkerStreamResponse, string) {
	switch r := req.Request.(type) {
	case *v1.WorkerStreamRequest_Register:
		regResp, err := s.RegisterWorker(ctx, r.Register)
		if err != nil {
			return &v1.WorkerStreamResponse{
				Response: &v1.WorkerStreamResponse_RegisterAck{
					RegisterAck: &v1.RegisterWorkerResponse{
						Success: false,
						Message: err.Error(),
					},
				},
			}, currentWorkerID
		}
		return &v1.WorkerStreamResponse{
			Response: &v1.WorkerStreamResponse_RegisterAck{
				RegisterAck: regResp,
			},
		}, regResp.WorkerId

	case *v1.WorkerStreamRequest_Heartbeat:
		if currentWorkerID == "" {
			r.Heartbeat.WorkerId = currentWorkerID
		}
		hbResp, err := s.Heartbeat(ctx, r.Heartbeat)
		if err != nil {
			return nil, currentWorkerID
		}
		return &v1.WorkerStreamResponse{
			Response: &v1.WorkerStreamResponse_HeartbeatAck{
				HeartbeatAck: hbResp,
			},
		}, currentWorkerID

	case *v1.WorkerStreamRequest_Unregister:
		if currentWorkerID == "" {
			r.Unregister.WorkerId = currentWorkerID
		}
		unregResp, _ := s.UnregisterWorker(ctx, r.Unregister)
		return &v1.WorkerStreamResponse{
			Response: &v1.WorkerStreamResponse_StatusUpdate{
				StatusUpdate: &v1.UpdateWorkerStatusResponse{
					Success: unregResp.Success,
				},
			},
		}, ""

	case *v1.WorkerStreamRequest_LoadUpdate:
		s.logger.Debug("Received load update",
			zap.String("worker_id", currentWorkerID),
			zap.Int32("active_tasks", r.LoadUpdate.ActiveTasks),
		)
		s.mu.Lock()
		if worker, ok := s.workers[currentWorkerID]; ok {
			worker.Load = r.LoadUpdate
		}
		s.mu.Unlock()
		return nil, currentWorkerID

	default:
		return nil, currentWorkerID
	}
}

func (s *WorkerServer) StreamHeartbeat(stream v1.WorkerService_StreamHeartbeatServer) error {
	streamID := generateStreamID()
	var workerID string

	s.logger.Debug("StreamHeartbeat connected", zap.String("stream_id", streamID))

	s.mu.Lock()
	s.heartbeatStreams[streamID] = stream
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.heartbeatStreams, streamID)
		if workerID != "" {
			if worker, ok := s.workers[workerID]; ok {
				worker.Status = v1.WorkerStatus_WORKER_STATUS_OFFLINE
			}
		}
		s.mu.Unlock()
		s.logger.Debug("StreamHeartbeat disconnected",
			zap.String("stream_id", streamID),
			zap.String("worker_id", workerID),
		)
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

		workerID = req.WorkerId

		s.logger.Debug("StreamHeartbeat received heartbeat",
			zap.String("stream_id", streamID),
			zap.String("worker_id", workerID),
			zap.Int32("status", int32(req.Status)),
		)

		resp, err := s.Heartbeat(ctx, req)
		if err != nil {
			s.logger.Error("StreamHeartbeat handle error",
				zap.String("stream_id", streamID),
				zap.Error(err),
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

func (s *WorkerServer) SendCommand(workerID string, command *v1.WorkerCommand) error {
	if command == nil {
		return errors.New("command is nil")
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	resp := &v1.WorkerStreamResponse{
		Response: &v1.WorkerStreamResponse_Command{
			Command: command,
		},
	}

	for streamID, stream := range s.workerStreams {
		if err := stream.Send(resp); err != nil {
			s.logger.Warn("SendCommand failed",
				zap.String("stream_id", streamID),
				zap.String("worker_id", workerID),
				zap.Error(err),
			)
			return err
		}
	}

	return nil
}

func (s *WorkerServer) GetWorkerCount() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.workers)
}

func (s *WorkerServer) GetActiveStreams() (workerStreams, heartbeatStreams int) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.workerStreams), len(s.heartbeatStreams)
}

func (s *WorkerServer) SetHeartbeatInterval(interval time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.heartbeatInterval = interval
}
