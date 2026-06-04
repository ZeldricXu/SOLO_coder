package engine

import (
	"context"

	"github.com/htest/htest/internal/engine/rest"
)

type RESTAdapter struct {
	client *rest.Client
}

func NewRESTAdapter(baseURL string, defaultHeaders map[string]string, timeout int) *RESTAdapter {
	return &RESTAdapter{
		client: rest.NewClient(baseURL, defaultHeaders, timeout),
	}
}

func (a *RESTAdapter) Execute(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error) {
	restReq := &rest.Request{
		Method:  req.Method,
		URL:     req.URL,
		Headers: req.Headers,
		Body:    req.Body,
		Timeout: req.Timeout,
	}

	resp, err := a.client.Do(restReq)
	if err != nil {
		return nil, err
	}

	return &ProtocolResponse{
		StatusCode: resp.StatusCode,
		Status:     resp.Status,
		Headers:    resp.Headers,
		Body:       resp.Body,
		Duration:   resp.Duration,
		Proto:      resp.Proto,
		Raw:        resp,
	}, nil
}
