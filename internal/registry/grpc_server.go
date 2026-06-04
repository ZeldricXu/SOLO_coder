package registry

import (
	"context"
	"fmt"
	"net"

	"github.com/distributed-task-scheduler/internal/models"
	"github.com/distributed-task-scheduler/proto"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type GRPCServer struct {
	registry *Registry
	server   *grpc.Server
	proto.UnimplementedRegistryServiceServer
}

func NewGRPCServer(registry *Registry) *GRPCServer {
	return &GRPCServer{
		registry: registry,
		server:   grpc.NewServer(),
	}
}

func (s *GRPCServer) Start(addr string) error {
	lis, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}

	proto.RegisterRegistryServiceServer(s.server, s)

	go func() {
		if err := s.server.Serve(lis); err != nil {
			fmt.Printf("GRPC server error: %v\n", err)
		}
	}()

	return nil
}

func (s *GRPCServer) Stop() {
	s.server.GracefulStop()
}

func (s *GRPCServer) RegisterWorker(ctx context.Context, req *proto.RegisterWorkerRequest) (*proto.RegisterWorkerResponse, error) {
	reg := WorkerRegistration{
		ID:           req.Id,
		Namespace:    req.Namespace,
		Hostname:     req.Hostname,
		GRPCAddr:     req.GrpcAddr,
		HTTPAddr:     req.HttpAddr,
		Capabilities: req.Capabilities,
		MaxLoad:      int(req.MaxLoad),
	}

	worker, err := s.registry.Register(reg)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to register worker: %v", err)
	}

	return &proto.RegisterWorkerResponse{
		WorkerId: worker.ID,
		Status:   string(worker.Status),
	}, nil
}

func (s *GRPCServer) DeregisterWorker(ctx context.Context, req *proto.DeregisterWorkerRequest) (*proto.DeregisterWorkerResponse, error) {
	err := s.registry.Deregister(req.WorkerId)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to deregister worker: %v", err)
	}

	return &proto.DeregisterWorkerResponse{Success: true}, nil
}

func (s *GRPCServer) Heartbeat(ctx context.Context, req *proto.HeartbeatRequest) (*proto.HeartbeatResponse, error) {
	err := s.registry.Heartbeat(req.WorkerId, int(req.CurrentLoad))
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to send heartbeat: %v", err)
	}

	worker, err := s.registry.GetWorker(req.WorkerId)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to get worker status: %v", err)
	}

	return &proto.HeartbeatResponse{
		Success: true,
		Status:  string(worker.Status),
	}, nil
}

func (s *GRPCServer) ListWorkers(ctx context.Context, req *proto.ListWorkersRequest) (*proto.ListWorkersResponse, error) {
	var workers []models.Worker
	var err error

	if req.HealthyOnly {
		workers, err = s.registry.ListHealthyWorkers(req.Namespace)
	} else {
		workers, err = s.registry.ListWorkers(req.Namespace)
	}

	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to list workers: %v", err)
	}

	resp := &proto.ListWorkersResponse{
		Workers: make([]*proto.WorkerInfo, 0, len(workers)),
	}

	for _, w := range workers {
		resp.Workers = append(resp.Workers, &proto.WorkerInfo{
			Id:            w.ID,
			Namespace:     w.Namespace,
			Hostname:      w.Hostname,
			GrpcAddr:      w.GRPCAddr,
			HttpAddr:      w.HTTPAddr,
			Status:        string(w.Status),
			LastHeartbeat: w.LastHeartbeat.Unix(),
			CurrentLoad:   int32(w.CurrentLoad),
			MaxLoad:       int32(w.MaxLoad),
			Capabilities:  w.Capabilities,
		})
	}

	return resp, nil
}

func (s *GRPCServer) GetWorker(ctx context.Context, req *proto.GetWorkerRequest) (*proto.GetWorkerResponse, error) {
	worker, err := s.registry.GetWorker(req.WorkerId)
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "worker not found: %v", err)
	}

	return &proto.GetWorkerResponse{
		Worker: &proto.WorkerInfo{
			Id:            worker.ID,
			Namespace:     worker.Namespace,
			Hostname:      worker.Hostname,
			GrpcAddr:      worker.GRPCAddr,
			HttpAddr:      worker.HTTPAddr,
			Status:        string(worker.Status),
			LastHeartbeat: worker.LastHeartbeat.Unix(),
			CurrentLoad:   int32(worker.CurrentLoad),
			MaxLoad:       int32(worker.MaxLoad),
			Capabilities:  worker.Capabilities,
		},
	}, nil
}
