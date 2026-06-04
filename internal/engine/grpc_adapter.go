package engine

import (
	"context"
	"fmt"

	"github.com/htest/htest/internal/engine/grpc"
	googlegrpc "google.golang.org/grpc"
)

type GRPCAdapter struct {
	target string
	opts   []googlegrpc.DialOption
}

func NewGRPCAdapter(target string, opts ...googlegrpc.DialOption) *GRPCAdapter {
	return &GRPCAdapter{target: target, opts: opts}
}

func (a *GRPCAdapter) Execute(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error) {
	client, err := grpc.NewClient(a.target, a.opts...)
	if err != nil {
		return nil, fmt.Errorf("creating gRPC client: %w", err)
	}
	defer client.Close()

	respJSON, err := client.Invoke(ctx, req.Service, req.RpcName, req.Body)
	if err != nil {
		return nil, err
	}

	return &ProtocolResponse{
		StatusCode: 0,
		Status:     "OK",
		Headers:    map[string][]string{"Content-Type": {"application/json"}},
		Body:       respJSON,
		Raw:        respJSON,
	}, nil
}
