package engine

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestProtocolRequest_Fields(t *testing.T) {
	req := &ProtocolRequest{
		Protocol: "rest",
		Method:   "GET",
		URL:      "/api/health",
		Headers:  map[string]string{"Authorization": "Bearer token"},
		Timeout:  30,
	}
	assert.Equal(t, "rest", req.Protocol)
	assert.Equal(t, "GET", req.Method)
	assert.Equal(t, "/api/health", req.URL)
	assert.Equal(t, "Bearer token", req.Headers["Authorization"])
	assert.Equal(t, 30, req.Timeout)
}

func TestChainMiddleware_Order(t *testing.T) {
	var order []string
	base := &mockClient{executeFunc: func(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error) {
		order = append(order, "base")
		return &ProtocolResponse{StatusCode: 200}, nil
	}}
	mw1 := func(next ProtocolClient) ProtocolClient {
		return &mockClient{executeFunc: func(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error) {
			order = append(order, "mw1-before")
			resp, err := next.Execute(ctx, req)
			order = append(order, "mw1-after")
			return resp, err
		}}
	}
	mw2 := func(next ProtocolClient) ProtocolClient {
		return &mockClient{executeFunc: func(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error) {
			order = append(order, "mw2-before")
			resp, err := next.Execute(ctx, req)
			order = append(order, "mw2-after")
			return resp, err
		}}
	}

	client := ChainMiddleware(base, mw1, mw2)
	_, err := client.Execute(context.Background(), &ProtocolRequest{})
	require.NoError(t, err)

	assert.Equal(t, []string{"mw2-before", "mw1-before", "base", "mw1-after", "mw2-after"}, order)
}

func TestWithTimeout_Expired(t *testing.T) {
	base := &mockClient{executeFunc: func(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error) {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(200 * time.Millisecond):
			return &ProtocolResponse{StatusCode: 200}, nil
		}
	}}

	client := ChainMiddleware(base, WithTimeout(50*time.Millisecond))
	_, err := client.Execute(context.Background(), &ProtocolRequest{})
	assert.Error(t, err)
}

func TestWithTimeout_Success(t *testing.T) {
	base := &mockClient{executeFunc: func(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error) {
		return &ProtocolResponse{StatusCode: 200}, nil
	}}

	client := ChainMiddleware(base, WithTimeout(5*time.Second))
	resp, err := client.Execute(context.Background(), &ProtocolRequest{})
	require.NoError(t, err)
	assert.Equal(t, 200, resp.StatusCode)
}

func TestWithRetry_Success(t *testing.T) {
	callCount := 0
	base := &mockClient{executeFunc: func(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error) {
		callCount++
		if callCount < 3 {
			return &ProtocolResponse{StatusCode: 500}, fmt.Errorf("server error")
		}
		return &ProtocolResponse{StatusCode: 200}, nil
	}}

	client := ChainMiddleware(base, WithRetry(3, 10*time.Millisecond))
	resp, err := client.Execute(context.Background(), &ProtocolRequest{})
	require.NoError(t, err)
	assert.Equal(t, 200, resp.StatusCode)
	assert.Equal(t, 3, callCount)
}

func TestWithRetry_NoRetryOnSuccess(t *testing.T) {
	callCount := 0
	base := &mockClient{executeFunc: func(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error) {
		callCount++
		return &ProtocolResponse{StatusCode: 200}, nil
	}}

	client := ChainMiddleware(base, WithRetry(3, 10*time.Millisecond))
	_, err := client.Execute(context.Background(), &ProtocolRequest{})
	require.NoError(t, err)
	assert.Equal(t, 1, callCount)
}

func TestRESTAdapter_Execute(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Test", "value")
		w.WriteHeader(200)
		fmt.Fprint(w, `{"ok":true}`)
	}))
	defer server.Close()

	adapter := NewRESTAdapter(server.URL, nil, 5)
	resp, err := adapter.Execute(context.Background(), &ProtocolRequest{
		Protocol: "rest",
		Method:   "GET",
		URL:      "/test",
	})
	require.NoError(t, err)
	assert.Equal(t, 200, resp.StatusCode)
	assert.Contains(t, resp.Body, "ok")
	assert.NotNil(t, resp.Raw)
}

func TestNewTLSTransport(t *testing.T) {
	transport := NewTLSTransport(30*time.Second, false)
	require.NotNil(t, transport)
	assert.NotNil(t, transport.TLSClientConfig)
}

type mockClient struct {
	executeFunc func(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error)
}

func (m *mockClient) Execute(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error) {
	return m.executeFunc(ctx, req)
}
