package client

import (
	"context"
	"errors"
	"io"
	"sync"
	"time"

	v1 "github.com/df1-96/experiment/pkg/grpcapi/distcomp/v1"
	"go.uber.org/zap"
	"google.golang.org/grpc"
)

type WorkerClient struct {
	client         *GRPCClient
	grpcClient     v1.WorkerServiceClient
	logger         *zap.Logger
	mu             sync.RWMutex
	streamWorker   v1.WorkerService_StreamWorkerClient
	streamHeartbeat v1.WorkerService_StreamHeartbeatClient
}

func NewWorkerClient(client *GRPCClient, logger *zap.Logger) *WorkerClient {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &WorkerClient{
		client: client,
		logger: logger,
	}
}

func (c *WorkerClient) init() error {
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
		c.grpcClient = v1.NewWorkerServiceClient(c.client.GetConn())
	}
	return nil
}

func (c *WorkerClient) RegisterWorker(ctx context.Context, req *v1.RegisterWorkerRequest, opts ...grpc.CallOption) (*v1.RegisterWorkerResponse, error) {
	if err := c.init(); err != nil {
		return nil, err
	}

	ctx, cancel := c.client.NewContext(ctx)
	defer cancel()

	c.logger.Debug("RegisterWorker request",
		zap.String("name", req.Name),
		zap.Int32("worker_type", int32(req.Type)),
	)

	return c.grpcClient.RegisterWorker(ctx, req, opts...)
}

func (c *WorkerClient) UnregisterWorker(ctx context.Context, req *v1.UnregisterWorkerRequest, opts ...grpc.CallOption) (*v1.UnregisterWorkerResponse, error) {
	if err := c.init(); err != nil {
		return nil, err
	}

	ctx, cancel := c.client.NewContext(ctx)
	defer cancel()

	c.logger.Debug("UnregisterWorker request",
		zap.String("worker_id", req.WorkerId),
		zap.String("reason", req.Reason),
	)

	return c.grpcClient.UnregisterWorker(ctx, req, opts...)
}

func (c *WorkerClient) GetWorker(ctx context.Context, req *v1.GetWorkerRequest, opts ...grpc.CallOption) (*v1.GetWorkerResponse, error) {
	if err := c.init(); err != nil {
		return nil, err
	}

	ctx, cancel := c.client.NewContext(ctx)
	defer cancel()

	c.logger.Debug("GetWorker request", zap.String("worker_id", req.WorkerId))

	return c.grpcClient.GetWorker(ctx, req, opts...)
}

func (c *WorkerClient) Heartbeat(ctx context.Context, req *v1.HeartbeatRequest, opts ...grpc.CallOption) (*v1.HeartbeatResponse, error) {
	if err := c.init(); err != nil {
		return nil, err
	}

	ctx, cancel := c.client.NewContext(ctx)
	defer cancel()

	c.logger.Debug("Heartbeat request",
		zap.String("worker_id", req.WorkerId),
		zap.Int32("status", int32(req.Status)),
	)

	return c.grpcClient.Heartbeat(ctx, req, opts...)
}

func (c *WorkerClient) UpdateWorkerStatus(ctx context.Context, req *v1.UpdateWorkerStatusRequest, opts ...grpc.CallOption) (*v1.UpdateWorkerStatusResponse, error) {
	if err := c.init(); err != nil {
		return nil, err
	}

	ctx, cancel := c.client.NewContext(ctx)
	defer cancel()

	c.logger.Debug("UpdateWorkerStatus request",
		zap.String("worker_id", req.WorkerId),
		zap.Int32("status", int32(req.Status)),
	)

	return c.grpcClient.UpdateWorkerStatus(ctx, req, opts...)
}

func (c *WorkerClient) ListWorkers(ctx context.Context, req *v1.ListWorkersRequest, opts ...grpc.CallOption) (*v1.ListWorkersResponse, error) {
	if err := c.init(); err != nil {
		return nil, err
	}

	ctx, cancel := c.client.NewContext(ctx)
	defer cancel()

	c.logger.Debug("ListWorkers request",
		zap.Int32("status", int32(req.Status)),
		zap.Int32("page_size", req.PageSize),
	)

	return c.grpcClient.ListWorkers(ctx, req, opts...)
}

func (c *WorkerClient) StreamWorker(ctx context.Context, opts ...grpc.CallOption) error {
	if err := c.init(); err != nil {
		return err
	}

	c.mu.Lock()
	if c.streamWorker != nil {
		c.mu.Unlock()
		return errors.New("stream worker already running")
	}

	stream, err := c.grpcClient.StreamWorker(ctx, opts...)
	if err != nil {
		c.mu.Unlock()
		return err
	}
	c.streamWorker = stream
	c.mu.Unlock()

	c.logger.Info("StreamWorker stream started")
	return nil
}

func (c *WorkerClient) SendWorkerRequest(req *v1.WorkerStreamRequest) error {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.streamWorker == nil {
		return errors.New("stream worker not started")
	}

	c.logger.Debug("SendWorkerRequest")
	return c.streamWorker.Send(req)
}

func (c *WorkerClient) RecvWorkerResponse() (*v1.WorkerStreamResponse, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.streamWorker == nil {
		return nil, errors.New("stream worker not started")
	}

	resp, err := c.streamWorker.Recv()
	if err != nil {
		if errors.Is(err, io.EOF) {
			c.logger.Debug("StreamWorker stream closed by server")
		} else {
			c.logger.Error("StreamWorker recv error", zap.Error(err))
		}
		return nil, err
	}

	c.logger.Debug("Received worker response")
	return resp, nil
}

func (c *WorkerClient) CloseWorkerStream() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.streamWorker != nil {
		err := c.streamWorker.CloseSend()
		c.streamWorker = nil
		c.logger.Info("StreamWorker stream closed")
		return err
	}
	return nil
}

func (c *WorkerClient) StreamHeartbeat(ctx context.Context, opts ...grpc.CallOption) error {
	if err := c.init(); err != nil {
		return err
	}

	c.mu.Lock()
	if c.streamHeartbeat != nil {
		c.mu.Unlock()
		return errors.New("stream heartbeat already running")
	}

	stream, err := c.grpcClient.StreamHeartbeat(ctx, opts...)
	if err != nil {
		c.mu.Unlock()
		return err
	}
	c.streamHeartbeat = stream
	c.mu.Unlock()

	c.logger.Info("StreamHeartbeat stream started")
	return nil
}

func (c *WorkerClient) SendHeartbeat(req *v1.HeartbeatRequest) error {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.streamHeartbeat == nil {
		return errors.New("stream heartbeat not started")
	}

	c.logger.Debug("SendHeartbeat",
		zap.String("worker_id", req.WorkerId),
		zap.Int32("status", int32(req.Status)),
	)
	return c.streamHeartbeat.Send(req)
}

func (c *WorkerClient) RecvHeartbeatResponse() (*v1.HeartbeatResponse, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.streamHeartbeat == nil {
		return nil, errors.New("stream heartbeat not started")
	}

	resp, err := c.streamHeartbeat.Recv()
	if err != nil {
		if errors.Is(err, io.EOF) {
			c.logger.Debug("StreamHeartbeat stream closed by server")
		} else {
			c.logger.Error("StreamHeartbeat recv error", zap.Error(err))
		}
		return nil, err
	}

	c.logger.Debug("Received heartbeat response",
		zap.Bool("acknowledged", resp.Acknowledged),
	)
	return resp, nil
}

func (c *WorkerClient) CloseHeartbeatStream() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.streamHeartbeat != nil {
		err := c.streamHeartbeat.CloseSend()
		c.streamHeartbeat = nil
		c.logger.Info("StreamHeartbeat stream closed")
		return err
	}
	return nil
}

func (c *WorkerClient) StartWorkerSession(
	ctx context.Context,
	onCommand func(*v1.WorkerCommand),
	onError func(error),
) error {
	if err := c.StreamWorker(ctx); err != nil {
		return err
	}

	go func() {
		for {
			select {
			case <-ctx.Done():
				c.CloseWorkerStream()
				return
			default:
			}

			resp, err := c.RecvWorkerResponse()
			if err != nil {
				if !errors.Is(err, io.EOF) && onError != nil {
					onError(err)
				}
				return
			}

			if cmd, ok := resp.Response.(*v1.WorkerStreamResponse_Command); ok && onCommand != nil {
				onCommand(cmd.Command)
			}
		}
	}()

	return nil
}

func (c *WorkerClient) StartHeartbeatLoop(
	ctx context.Context,
	workerID string,
	interval time.Duration,
	onResponse func(*v1.HeartbeatResponse),
) error {
	if err := c.StreamHeartbeat(ctx); err != nil {
		return err
	}

	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				c.CloseHeartbeatStream()
				return
			case <-ticker.C:
				heartbeat := &v1.HeartbeatRequest{
					WorkerId: workerID,
					Status:   v1.WorkerStatus_WORKER_STATUS_IDLE,
				}
				if err := c.SendHeartbeat(heartbeat); err != nil {
					c.logger.Error("Failed to send heartbeat", zap.Error(err))
					return
				}
			}
		}
	}()

	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			default:
			}

			resp, err := c.RecvHeartbeatResponse()
			if err != nil {
				if !errors.Is(err, io.EOF) {
					c.logger.Error("Heartbeat recv error", zap.Error(err))
				}
				return
			}

			if resp != nil && onResponse != nil {
				onResponse(resp)
			}
		}
	}()

	return nil
}

func (c *WorkerClient) SendWorkerRegistration(req *v1.RegisterWorkerRequest) error {
	return c.SendWorkerRequest(&v1.WorkerStreamRequest{
		Request: &v1.WorkerStreamRequest_Register{
			Register: req,
		},
	})
}

func (c *WorkerClient) SendWorkerHeartbeat(req *v1.HeartbeatRequest) error {
	return c.SendWorkerRequest(&v1.WorkerStreamRequest{
		Request: &v1.WorkerStreamRequest_Heartbeat{
			Heartbeat: req,
		},
	})
}

func (c *WorkerClient) SendWorkerUnregister(req *v1.UnregisterWorkerRequest) error {
	return c.SendWorkerRequest(&v1.WorkerStreamRequest{
		Request: &v1.WorkerStreamRequest_Unregister{
			Unregister: req,
		},
	})
}

func (c *WorkerClient) SendLoadUpdate(info *v1.LoadInfo) error {
	return c.SendWorkerRequest(&v1.WorkerStreamRequest{
		Request: &v1.WorkerStreamRequest_LoadUpdate{
			LoadUpdate: info,
		},
	})
}

func (c *WorkerClient) Close() error {
	var errs []error

	if err := c.CloseWorkerStream(); err != nil {
		errs = append(errs, err)
	}
	if err := c.CloseHeartbeatStream(); err != nil {
		errs = append(errs, err)
	}

	if len(errs) > 0 {
		return errors.Join(errs...)
	}
	return nil
}
