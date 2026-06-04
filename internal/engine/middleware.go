package engine

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"time"

	"golang.org/x/net/http2"
)

type MiddlewareFunc func(next ProtocolClient) ProtocolClient

func ChainMiddleware(client ProtocolClient, middlewares ...MiddlewareFunc) ProtocolClient {
	for i := 0; i < len(middlewares); i++ {
		client = middlewares[i](client)
	}
	return client
}

type timeoutMiddleware struct {
	next    ProtocolClient
	timeout time.Duration
}

func WithTimeout(timeout time.Duration) MiddlewareFunc {
	return func(next ProtocolClient) ProtocolClient {
		return &timeoutMiddleware{next: next, timeout: timeout}
	}
}

func (m *timeoutMiddleware) Execute(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error) {
	timeout := m.timeout
	if req.Timeout > 0 {
		timeout = time.Duration(req.Timeout) * time.Second
	}
	if timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, timeout)
		defer cancel()
	}
	return m.next.Execute(ctx, req)
}

type retryMiddleware struct {
	next       ProtocolClient
	maxRetries int
	delay      time.Duration
}

func WithRetry(maxRetries int, delay time.Duration) MiddlewareFunc {
	return func(next ProtocolClient) ProtocolClient {
		return &retryMiddleware{next: next, maxRetries: maxRetries, delay: delay}
	}
}

func (m *retryMiddleware) Execute(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error) {
	var lastErr error
	var lastResp *ProtocolResponse
	for i := 0; i <= m.maxRetries; i++ {
		if i > 0 {
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(m.delay):
			}
		}
		lastResp, lastErr = m.next.Execute(ctx, req)
		if lastErr == nil && lastResp.StatusCode < 500 {
			return lastResp, nil
		}
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		lastErr = fmt.Errorf("retry %d: %w", i, lastErr)
	}
	if lastResp != nil {
		return lastResp, nil
	}
	return nil, lastErr
}

type tlsMiddleware struct {
	next       ProtocolClient
	skipVerify bool
}

func WithTLSConfig(skipVerify bool) MiddlewareFunc {
	return func(next ProtocolClient) ProtocolClient {
		return &tlsMiddleware{next: next, skipVerify: skipVerify}
	}
}

func (m *tlsMiddleware) Execute(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error) {
	return m.next.Execute(ctx, req)
}

func NewTLSTransport(timeout time.Duration, skipVerify bool) *http.Transport {
	transport := &http.Transport{
		DialContext: (&net.Dialer{
			Timeout: timeout,
		}).DialContext,
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: skipVerify,
		},
		ForceAttemptHTTP2: true,
	}
	http2.ConfigureTransports(transport)
	return transport
}
