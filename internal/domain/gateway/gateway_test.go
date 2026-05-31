package gateway

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/dataplatform/engine/internal/common/errors"
	"github.com/dataplatform/engine/internal/domain"
)

type testGatewayLogger struct{}

func (l *testGatewayLogger) Debug(msg string, fields ...domain.Field) {}
func (l *testGatewayLogger) Info(msg string, fields ...domain.Field)  {}
func (l *testGatewayLogger) Warn(msg string, fields ...domain.Field)  {}
func (l *testGatewayLogger) Error(msg string, fields ...domain.Field) {}
func (l *testGatewayLogger) Fatal(msg string, fields ...domain.Field) {}
func (l *testGatewayLogger) SetLevel(level domain.LogLevel)           {}
func (l *testGatewayLogger) GetLevel() domain.LogLevel                { return domain.LogLevelInfo }
func (l *testGatewayLogger) With(fields ...domain.Field) domain.Logger { return l }
func (l *testGatewayLogger) Sync() error                              { return nil }

type mockModelProvider struct {
	name     string
	healthy  bool
	delay    time.Duration
	failNext bool
}

func (m *mockModelProvider) Name() string { return m.name }
func (m *mockModelProvider) Capabilities() []string { return []string{"gpt-4", "gpt-3.5"} }
func (m *mockModelProvider) Healthy(ctx context.Context) bool { return m.healthy }
func (m *mockModelProvider) Infer(ctx context.Context, req *InferenceRequest) (*InferenceResponse, error) {
	if m.failNext {
		m.failNext = false
		return nil, errors.New(errors.ErrCodeInternal, "mock error")
	}
	if m.delay > 0 {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(m.delay):
		}
	}
	return &InferenceResponse{
		TraceID: req.TraceID,
		Model:   req.Model,
		Text:    "mocked response",
		Usage: &TokenUsage{
			PromptTokens:     10,
			CompletionTokens: 5,
			TotalTokens:      15,
		},
	}, nil
}

func TestGatewayBoundaryValidation(t *testing.T) {
	logger := &testGatewayLogger{}
	lb := NewRoundRobinLoadBalancer()
	gateway := NewInferenceGatewayImpl(lb, 5, time.Minute, logger)

	tests := []struct {
		name    string
		req     *InferenceRequest
		wantErr bool
	}{
		{
			name:    "nil request",
			req:     nil,
			wantErr: true,
		},
		{
			name:    "empty trace ID",
			req:     &InferenceRequest{TraceID: "", Model: "gpt-4", Prompt: "test"},
			wantErr: true,
		},
		{
			name:    "whitespace trace ID",
			req:     &InferenceRequest{TraceID: "   ", Model: "gpt-4", Prompt: "test"},
			wantErr: true,
		},
		{
			name:    "empty model",
			req:     &InferenceRequest{TraceID: "trace-1", Model: "", Prompt: "test"},
			wantErr: true,
		},
		{
			name:    "whitespace model",
			req:     &InferenceRequest{TraceID: "trace-1", Model: "   ", Prompt: "test"},
			wantErr: true,
		},
		{
			name:    "model too short",
			req:     &InferenceRequest{TraceID: "trace-1", Model: "", Prompt: "test"},
			wantErr: true,
		},
		{
			name:    "model too long",
			req:     &InferenceRequest{TraceID: "trace-1", Model: strings.Repeat("x", 200), Prompt: "test"},
			wantErr: true,
		},
		{
			name:    "prompt too long",
			req:     &InferenceRequest{TraceID: "trace-1", Model: "gpt-4", Prompt: strings.Repeat("x", 200000)},
			wantErr: true,
		},
		{
			name:    "negative timeout",
			req:     &InferenceRequest{TraceID: "trace-1", Model: "gpt-4", Prompt: "test", TimeoutMs: -1},
			wantErr: true,
		},
		{
			name:    "timeout too large",
			req:     &InferenceRequest{TraceID: "trace-1", Model: "gpt-4", Prompt: "test", TimeoutMs: 400000},
			wantErr: true,
		},
		{
			name:    "negative max retries",
			req:     &InferenceRequest{TraceID: "trace-1", Model: "gpt-4", Prompt: "test", MaxRetries: -1},
			wantErr: true,
		},
		{
			name:    "max retries too large",
			req:     &InferenceRequest{TraceID: "trace-1", Model: "gpt-4", Prompt: "test", MaxRetries: 20},
			wantErr: true,
		},
		{
			name: "nil message in array",
			req: &InferenceRequest{
				TraceID: "trace-1",
				Model:   "gpt-4",
				Prompt:  "test",
				Messages: []*Message{
					{Role: "user", Content: "hello"},
					nil,
				},
			},
			wantErr: true,
		},
		{
			name: "empty message role",
			req: &InferenceRequest{
				TraceID: "trace-1",
				Model:   "gpt-4",
				Prompt:  "test",
				Messages: []*Message{
					{Role: "", Content: "hello"},
				},
			},
			wantErr: true,
		},
		{
			name: "valid request",
			req: &InferenceRequest{
				TraceID: "trace-1",
				Model:   "gpt-4",
				Prompt:  "Hello, how are you?",
				Messages: []*Message{
					{Role: "user", Content: "Hello"},
					{Role: "assistant", Content: "Hi there!"},
				},
				TimeoutMs:  10000,
				MaxRetries: 3,
			},
			wantErr: false,
		},
		{
			name: "valid request with empty prompt",
			req: &InferenceRequest{
				TraceID: "trace-2",
				Model:   "gpt-4",
				Prompt:  "",
				Messages: []*Message{
					{Role: "user", Content: "Hello"},
				},
			},
			wantErr: false,
		},
		{
			name: "zero timeout (uses default)",
			req: &InferenceRequest{
				TraceID: "trace-3",
				Model:   "gpt-4",
				Prompt:  "test",
				TimeoutMs: 0,
			},
			wantErr: false,
		},
		{
			name: "zero max retries (uses default)",
			req: &InferenceRequest{
				TraceID:    "trace-4",
				Model:      "gpt-4",
				Prompt:     "test",
				MaxRetries: 0,
			},
			wantErr: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := gateway.validateRequest(tt.req)
			if (err != nil) != tt.wantErr {
				t.Errorf("validateRequest() error = %v, wantErr %v", err, tt.wantErr)
			}
		})
	}
}

func TestGatewayProviderManagementValidation(t *testing.T) {
	logger := &testGatewayLogger{}
	lb := NewRoundRobinLoadBalancer()
	gateway := NewInferenceGatewayImpl(lb, 5, time.Minute, logger)

	err := gateway.RegisterProvider(nil)
	if err == nil {
		t.Error("Expected error for nil provider")
	}

	err = gateway.RemoveProvider("")
	if err == nil {
		t.Error("Expected error for empty provider name")
	}

	err = gateway.RemoveProvider("   ")
	if err == nil {
		t.Error("Expected error for whitespace provider name")
	}

	err = gateway.RemoveProvider("non-existent")
	if err == nil {
		t.Error("Expected error for non-existent provider")
	}
}

func TestGatewayRouteWithValidation(t *testing.T) {
	logger := &testGatewayLogger{}
	lb := NewRoundRobinLoadBalancer()
	gateway := NewInferenceGatewayImpl(lb, 5, time.Minute, logger)

	provider := &mockModelProvider{name: "test-provider", healthy: true}
	gateway.RegisterProvider(provider)

	_, err := gateway.Route(context.Background(), nil)
	if err == nil {
		t.Error("Expected error for nil request")
	}

	_, err = gateway.Route(context.Background(), &InferenceRequest{
		TraceID: "",
		Model:   "gpt-4",
		Prompt:  "test",
	})
	if err == nil {
		t.Error("Expected error for empty trace ID")
	}

	validReq := &InferenceRequest{
		TraceID: "test-trace",
		Model:   "gpt-4",
		Prompt:  "Hello",
	}

	resp, err := gateway.Route(context.Background(), validReq)
	if err != nil {
		t.Fatalf("Route() unexpected error: %v", err)
	}

	if resp == nil {
		t.Fatal("Route() returned nil response")
	}

	if resp.TraceID != "test-trace" {
		t.Errorf("Expected trace ID 'test-trace', got '%s'", resp.TraceID)
	}
}

func TestGatewayRetryLimit(t *testing.T) {
	logger := &testGatewayLogger{}
	lb := NewRoundRobinLoadBalancer()
	gateway := NewInferenceGatewayImpl(lb, 5, time.Minute, logger)

	provider := &mockModelProvider{name: "failing-provider", healthy: true, failNext: true}
	gateway.RegisterProvider(provider)

	req := &InferenceRequest{
		TraceID:    "retry-test",
		Model:      "gpt-4",
		Prompt:     "test",
		MaxRetries: 11,
	}

	_, err := gateway.Route(context.Background(), req)
	if err == nil {
		t.Error("Expected error when all retries fail")
	}
}

func TestGatewayContextCancellation(t *testing.T) {
	logger := &testGatewayLogger{}
	lb := NewRoundRobinLoadBalancer()
	gateway := NewInferenceGatewayImpl(lb, 5, time.Minute, logger)

	provider := &mockModelProvider{
		name:    "slow-provider",
		healthy: true,
		delay:   500 * time.Millisecond,
	}
	gateway.RegisterProvider(provider)

	req := &InferenceRequest{
		TraceID:    "cancel-test",
		Model:      "gpt-4",
		Prompt:     "test",
		TimeoutMs:  50,
		MaxRetries: 0,
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err := gateway.Route(ctx, req)
	if err == nil {
		t.Error("Expected error for cancelled context")
	}
}

func TestHTTPModelProviderBoundaryHandling(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{
			"choices": [{"message": {"content": "Hello"}}],
			"usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
		}`))
	}))
	defer server.Close()

	cfg := &ProviderConfig{
		Name:      "test",
		BaseURL:   server.URL,
		APIKey:    "test-key",
		Models:    []string{"gpt-4"},
		TimeoutMs: 1000,
	}

	provider := NewHTTPModelProvider(cfg)

	_, err := provider.Infer(context.Background(), nil)
	if err == nil {
		t.Error("Expected error for nil request")
	}

	caps := provider.Capabilities()
	caps[0] = "modified"

	originalCaps := provider.Capabilities()
	if originalCaps[0] == "modified" {
		t.Error("Capabilities() should return a copy, not the internal slice")
	}
}

func TestGatewayConcurrentRequests(t *testing.T) {
	logger := &testGatewayLogger{}
	lb := NewRoundRobinLoadBalancer()
	gateway := NewInferenceGatewayImpl(lb, 5, time.Minute, logger)

	provider := &mockModelProvider{name: "concurrent-provider", healthy: true}
	gateway.RegisterProvider(provider)

	var wg sync.WaitGroup
	const numRequests = 50

	for i := 0; i < numRequests; i++ {
		wg.Add(1)
		go func(reqNum int) {
			defer wg.Done()

			req := &InferenceRequest{
				TraceID:    "concurrent-trace",
				Model:      "gpt-4",
				Prompt:     "test",
				MaxRetries: 0,
			}

			_, err := gateway.Route(context.Background(), req)
			if err != nil {
				t.Logf("Request %d failed: %v", reqNum, err)
			}
		}(i)
	}

	wg.Wait()
}
