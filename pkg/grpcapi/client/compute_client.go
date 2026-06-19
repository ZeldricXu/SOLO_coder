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

type ComputeClient struct {
	client       *GRPCClient
	grpcClient   v1.ComputeServiceClient
	logger       *zap.Logger
	mu           sync.RWMutex
	streamCompute v1.ComputeService_StreamComputeClient
	streamHeartbeat v1.ComputeService_StreamHeartbeatClient
	streamProgress v1.ComputeService_StreamProgressClient
}

func NewComputeClient(client *GRPCClient, logger *zap.Logger) *ComputeClient {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &ComputeClient{
		client: client,
		logger: logger,
	}
}

func (c *ComputeClient) init() error {
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
		c.grpcClient = v1.NewComputeServiceClient(c.client.GetConn())
	}
	return nil
}

func (c *ComputeClient) SubmitResult(ctx context.Context, req *v1.SubmitResultRequest, opts ...grpc.CallOption) (*v1.SubmitResultResponse, error) {
	if err := c.init(); err != nil {
		return nil, err
	}

	ctx, cancel := c.client.NewContext(ctx)
	defer cancel()

	c.logger.Debug("SubmitResult request",
		zap.String("task_id", req.Result.TaskId),
		zap.Int32("shard_id", req.Result.ShardId),
	)

	return c.grpcClient.SubmitResult(ctx, req, opts...)
}

func (c *ComputeClient) GetShard(ctx context.Context, req *v1.GetShardRequest, opts ...grpc.CallOption) (*v1.GetShardResponse, error) {
	if err := c.init(); err != nil {
		return nil, err
	}

	ctx, cancel := c.client.NewContext(ctx)
	defer cancel()

	c.logger.Debug("GetShard request",
		zap.String("worker_id", req.WorkerId),
		zap.String("task_id", req.TaskId),
	)

	return c.grpcClient.GetShard(ctx, req, opts...)
}

func (c *ComputeClient) StreamCompute(ctx context.Context, opts ...grpc.CallOption) error {
	if err := c.init(); err != nil {
		return err
	}

	c.mu.Lock()
	if c.streamCompute != nil {
		c.mu.Unlock()
		return errors.New("stream compute already running")
	}

	stream, err := c.grpcClient.StreamCompute(ctx, opts...)
	if err != nil {
		c.mu.Unlock()
		return err
	}
	c.streamCompute = stream
	c.mu.Unlock()

	c.logger.Info("StreamCompute stream started")
	return nil
}

func (c *ComputeClient) SendComputeRequest(req *v1.ComputeRequest) error {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.streamCompute == nil {
		return errors.New("stream compute not started")
	}

	c.logger.Debug("SendComputeRequest")
	return c.streamCompute.Send(req)
}

func (c *ComputeClient) RecvComputeResponse() (*v1.ComputeResponse, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.streamCompute == nil {
		return nil, errors.New("stream compute not started")
	}

	resp, err := c.streamCompute.Recv()
	if err != nil {
		if errors.Is(err, io.EOF) {
			c.logger.Debug("StreamCompute stream closed by server")
		} else {
			c.logger.Error("StreamCompute recv error", zap.Error(err))
		}
		return nil, err
	}

	c.logger.Debug("Received compute response")
	return resp, nil
}

func (c *ComputeClient) CloseComputeStream() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.streamCompute != nil {
		err := c.streamCompute.CloseSend()
		c.streamCompute = nil
		c.logger.Info("StreamCompute stream closed")
		return err
	}
	return nil
}

func (c *ComputeClient) StreamHeartbeat(ctx context.Context, opts ...grpc.CallOption) error {
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

func (c *ComputeClient) SendHeartbeat(req *v1.Heartbeat) error {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.streamHeartbeat == nil {
		return errors.New("stream heartbeat not started")
	}

	c.logger.Debug("SendHeartbeat",
		zap.String("worker_id", req.WorkerId),
		zap.String("task_id", req.TaskId),
		zap.Bool("healthy", req.Healthy),
	)
	return c.streamHeartbeat.Send(req)
}

func (c *ComputeClient) RecvHeartbeatResponse() (*v1.TaskStatusUpdate, error) {
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
		zap.String("task_id", resp.TaskId),
		zap.Int32("status", int32(resp.Status)),
	)
	return resp, nil
}

func (c *ComputeClient) CloseHeartbeatStream() error {
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

func (c *ComputeClient) StreamProgress(ctx context.Context, opts ...grpc.CallOption) error {
	if err := c.init(); err != nil {
		return err
	}

	c.mu.Lock()
	if c.streamProgress != nil {
		c.mu.Unlock()
		return errors.New("stream progress already running")
	}

	stream, err := c.grpcClient.StreamProgress(ctx, opts...)
	if err != nil {
		c.mu.Unlock()
		return err
	}
	c.streamProgress = stream
	c.mu.Unlock()

	c.logger.Info("StreamProgress stream started")
	return nil
}

func (c *ComputeClient) SendProgressUpdate(req *v1.ProgressUpdate) error {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.streamProgress == nil {
		return errors.New("stream progress not started")
	}

	c.logger.Debug("SendProgressUpdate",
		zap.String("task_id", req.TaskId),
		zap.Int32("shard_id", req.ShardId),
		zap.Float64("percentage", req.Percentage),
	)
	return c.streamProgress.Send(req)
}

func (c *ComputeClient) RecvProgressResponse() (*v1.TaskCancellation, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.streamProgress == nil {
		return nil, errors.New("stream progress not started")
	}

	resp, err := c.streamProgress.Recv()
	if err != nil {
		if errors.Is(err, io.EOF) {
			c.logger.Debug("StreamProgress stream closed by server")
		} else {
			c.logger.Error("StreamProgress recv error", zap.Error(err))
		}
		return nil, err
	}

	c.logger.Debug("Received progress response (cancellation)",
		zap.String("task_id", resp.TaskId),
		zap.String("reason", resp.Reason),
	)
	return resp, nil
}

func (c *ComputeClient) CloseProgressStream() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.streamProgress != nil {
		err := c.streamProgress.CloseSend()
		c.streamProgress = nil
		c.logger.Info("StreamProgress stream closed")
		return err
	}
	return nil
}

func (c *ComputeClient) StartComputeSession(
	ctx context.Context,
	onResponse func(*v1.ComputeResponse),
	onError func(error),
) error {
	if err := c.StreamCompute(ctx); err != nil {
		return err
	}

	go func() {
		for {
			select {
			case <-ctx.Done():
				c.CloseComputeStream()
				return
			default:
			}

			resp, err := c.RecvComputeResponse()
			if err != nil {
				if !errors.Is(err, io.EOF) && onError != nil {
					onError(err)
				}
				return
			}

			if onResponse != nil {
				onResponse(resp)
			}
		}
	}()

	return nil
}

func (c *ComputeClient) StartProgressStreaming(
	ctx context.Context,
	onCancellation func(*v1.TaskCancellation),
) error {
	if err := c.StreamProgress(ctx); err != nil {
		return err
	}

	go func() {
		for {
			select {
			case <-ctx.Done():
				c.CloseProgressStream()
				return
			default:
			}

			cancellation, err := c.RecvProgressResponse()
			if err != nil {
				if !errors.Is(err, io.EOF) {
					c.logger.Error("Progress streaming error", zap.Error(err))
				}
				return
			}

			if cancellation != nil && onCancellation != nil {
				onCancellation(cancellation)
			}
		}
	}()

	return nil
}

func (c *ComputeClient) SubmitTaskAssignment(req *v1.TaskAssignment) error {
	return c.SendComputeRequest(&v1.ComputeRequest{
		Request: &v1.ComputeRequest_Assignment{
			Assignment: req,
		},
	})
}

func (c *ComputeClient) SubmitComputeResult(req *v1.ComputeResult) error {
	return c.SendComputeRequest(&v1.ComputeRequest{
		Request: &v1.ComputeRequest_Result{
			Result: req,
		},
	})
}

func (c *ComputeClient) SubmitComputeError(req *v1.ComputeError) error {
	return c.SendComputeRequest(&v1.ComputeRequest{
		Request: &v1.ComputeRequest_Error{
			Error: req,
		},
	})
}

func (c *ComputeClient) SubmitIntermediateState(req *v1.IntermediateState) error {
	return c.SendComputeRequest(&v1.ComputeRequest{
		Request: &v1.ComputeRequest_Intermediate{
			Intermediate: req,
		},
	})
}

func (c *ComputeClient) Close() error {
	var errs []error

	if err := c.CloseComputeStream(); err != nil {
		errs = append(errs, err)
	}
	if err := c.CloseHeartbeatStream(); err != nil {
		errs = append(errs, err)
	}
	if err := c.CloseProgressStream(); err != nil {
		errs = append(errs, err)
	}

	if len(errs) > 0 {
		return errors.Join(errs...)
	}
	return nil
}
