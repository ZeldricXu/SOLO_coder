package engine

import (
	"context"
	"time"
)

type ProtocolRequest struct {
	Protocol string
	Method   string
	URL      string
	Headers  map[string]string
	Body     string
	Service  string
	RpcName  string
	Query    string
	Message  string
	Timeout  int
}

type ProtocolResponse struct {
	StatusCode int
	Status     string
	Headers    map[string][]string
	Body       string
	Duration   time.Duration
	Proto      string
	Raw        interface{}
}

type ProtocolClient interface {
	Execute(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error)
}
