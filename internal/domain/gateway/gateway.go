package gateway

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/dataplatform/engine/internal/common/errors"
	"github.com/dataplatform/engine/internal/domain"
)

const (
	DefaultMaxRetries  = 2
	MaxAllowedRetries  = 10
	DefaultTimeoutMs   = 30000
	MaxAllowedTimeoutMs = 300000
	MinModelLength     = 1
	MaxModelLength     = 128
	MinPromptLength    = 0
	MaxPromptLength    = 100000
)

type InferenceGatewayImpl struct {
	providers        map[string]domain.ModelProvider
	loadBalancer     domain.LoadBalancer
	circuitBreakers  map[string]*CircuitBreaker
	circuitThreshold int
	circuitTimeout   time.Duration
	logger           domain.Logger
	mu               sync.RWMutex
}

func NewInferenceGatewayImpl(
	loadBalancer domain.LoadBalancer,
	circuitThreshold int,
	circuitTimeout time.Duration,
	logger domain.Logger,
) *InferenceGatewayImpl {
	return &InferenceGatewayImpl{
		providers:        make(map[string]domain.ModelProvider),
		loadBalancer:     loadBalancer,
		circuitBreakers:  make(map[string]*CircuitBreaker),
		circuitThreshold: circuitThreshold,
		circuitTimeout:   circuitTimeout,
		logger:           logger,
	}
}

func (g *InferenceGatewayImpl) RegisterProvider(provider domain.ModelProvider) error {
	if provider == nil {
		return errors.New(errors.ErrCodeValidation, "provider cannot be nil")
	}
	if strings.TrimSpace(provider.Name()) == "" {
		return errors.New(errors.ErrCodeValidation, "provider name cannot be empty")
	}

	g.mu.Lock()
	defer g.mu.Unlock()

	g.providers[provider.Name()] = provider
	g.circuitBreakers[provider.Name()] = NewCircuitBreaker(g.circuitThreshold, g.circuitTimeout)

	g.logger.Info("Provider registered", domain.String("provider", provider.Name()))
	return nil
}

func (g *InferenceGatewayImpl) RemoveProvider(name string) error {
	if strings.TrimSpace(name) == "" {
		return errors.New(errors.ErrCodeValidation, "provider name cannot be empty")
	}

	g.mu.Lock()
	defer g.mu.Unlock()

	if _, exists := g.providers[name]; !exists {
		return errors.New(errors.ErrCodeNotFound, "provider not found")
	}

	delete(g.providers, name)
	delete(g.circuitBreakers, name)

	g.logger.Info("Provider removed", domain.String("provider", name))
	return nil
}

func (g *InferenceGatewayImpl) ListProviders() []string {
	g.mu.RLock()
	defer g.mu.RUnlock()

	names := make([]string, 0, len(g.providers))
	for name := range g.providers {
		names = append(names, name)
	}
	return names
}

func (g *InferenceGatewayImpl) GetFallbackProvider(priority int) (domain.ModelProvider, error) {
	g.mu.RLock()
	defer g.mu.RUnlock()

	for _, p := range g.providers {
		if p.Healthy(context.Background()) {
			return p, nil
		}
	}

	for _, p := range g.providers {
		return p, nil
	}

	return nil, errors.New(errors.ErrCodeUnavailable, "no providers available")
}

func (g *InferenceGatewayImpl) Route(ctx context.Context, req *InferenceRequest) (*InferenceResponse, error) {
	if err := g.validateRequest(req); err != nil {
		return nil, err
	}

	g.mu.RLock()
	providers := make([]domain.ModelProvider, 0, len(g.providers))
	for _, p := range g.providers {
		providers = append(providers, p)
	}
	g.mu.RUnlock()

	if len(providers) == 0 {
		return nil, errors.New(errors.ErrCodeUnavailable, "no providers registered")
	}

	maxRetries := req.MaxRetries
	if maxRetries <= 0 {
		maxRetries = DefaultMaxRetries
	}
	if maxRetries > MaxAllowedRetries {
		maxRetries = MaxAllowedRetries
	}

	var lastErr error
	for attempt := 0; attempt <= maxRetries; attempt++ {
		select {
		case <-ctx.Done():
			return nil, errors.Wrap(ctx.Err(), errors.ErrCodeTimeout, "route cancelled")
		default:
		}

		provider, err := g.loadBalancer.Select(ctx, providers)
		if err != nil {
			lastErr = err
			continue
		}

		cb := g.getCircuitBreaker(provider.Name())
		if !cb.Allow() {
			g.logger.Warn("Circuit breaker open, skipping provider",
				domain.String("provider", provider.Name()),
			)
			continue
		}

		resp, err := g.callProvider(ctx, provider, req)
		if err == nil {
			cb.RecordSuccess()
			g.loadBalancer.RecordSuccess(provider)
			return resp, nil
		}

		cb.RecordFailure()
		g.loadBalancer.RecordFailure(provider)
		lastErr = err

		g.logger.Warn("Provider call failed",
			domain.String("provider", provider.Name()),
			domain.Int("attempt", attempt),
			domain.Error(err),
		)

		if attempt < maxRetries {
			backoff := time.Duration(100*(attempt+1)) * time.Millisecond
			select {
			case <-ctx.Done():
				return nil, errors.Wrap(ctx.Err(), errors.ErrCodeTimeout, "route cancelled during backoff")
			case <-time.After(backoff):
			}
		}
	}

	return nil, errors.Wrap(lastErr, errors.ErrCodeUnavailable, "all providers failed")
}

func (g *InferenceGatewayImpl) validateRequest(req *InferenceRequest) error {
	if req == nil {
		return errors.New(errors.ErrCodeValidation, "request cannot be nil")
	}
	if strings.TrimSpace(req.TraceID) == "" {
		return errors.New(errors.ErrCodeValidation, "trace ID cannot be empty")
	}
	if strings.TrimSpace(req.Model) == "" {
		return errors.New(errors.ErrCodeValidation, "model cannot be empty")
	}
	if len(req.Model) < MinModelLength {
		return errors.New(errors.ErrCodeValidation,
			"model name too short, minimum length is %d", MinModelLength)
	}
	if len(req.Model) > MaxModelLength {
		return errors.New(errors.ErrCodeValidation,
			"model name too long, maximum length is %d", MaxModelLength)
	}
	if len(req.Prompt) < MinPromptLength {
		return errors.New(errors.ErrCodeValidation,
			"prompt too short, minimum length is %d", MinPromptLength)
	}
	if len(req.Prompt) > MaxPromptLength {
		return errors.New(errors.ErrCodeValidation,
			"prompt too long, maximum length is %d", MaxPromptLength)
	}
	if req.TimeoutMs < 0 {
		return errors.New(errors.ErrCodeValidation, "timeout cannot be negative")
	}
	if req.TimeoutMs > MaxAllowedTimeoutMs {
		return errors.New(errors.ErrCodeValidation,
			"timeout too long, maximum is %d ms", MaxAllowedTimeoutMs)
	}
	if req.MaxRetries < 0 {
		return errors.New(errors.ErrCodeValidation, "max retries cannot be negative")
	}
	if req.MaxRetries > MaxAllowedRetries {
		return errors.New(errors.ErrCodeValidation,
			"max retries too large, maximum is %d", MaxAllowedRetries)
	}

	if req.Messages != nil {
		for i, msg := range req.Messages {
			if msg == nil {
				return errors.New(errors.ErrCodeValidation,
					"message at index %d cannot be nil", i)
			}
			if strings.TrimSpace(msg.Role) == "" {
				return errors.New(errors.ErrCodeValidation,
					"message role at index %d cannot be empty", i)
			}
			if len(msg.Content) > MaxPromptLength {
				return errors.New(errors.ErrCodeValidation,
					"message content at index %d too long, maximum is %d", i, MaxPromptLength)
			}
		}
	}

	return nil
}

func (g *InferenceGatewayImpl) callProvider(
	ctx context.Context,
	provider domain.ModelProvider,
	req *InferenceRequest,
) (*InferenceResponse, error) {
	timeout := time.Duration(req.TimeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = DefaultTimeoutMs * time.Millisecond
	}
	if timeout > time.Duration(MaxAllowedTimeoutMs)*time.Millisecond {
		timeout = time.Duration(MaxAllowedTimeoutMs) * time.Millisecond
	}

	callCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	start := time.Now()
	resp, err := provider.Infer(callCtx, req)
	if err != nil {
		return nil, err
	}

	resp.LatencyMs = time.Since(start).Milliseconds()
	return resp, nil
}

func (g *InferenceGatewayImpl) getCircuitBreaker(name string) *CircuitBreaker {
	g.mu.RLock()
	cb, exists := g.circuitBreakers[name]
	g.mu.RUnlock()

	if exists {
		return cb
	}

	g.mu.Lock()
	defer g.mu.Unlock()

	if cb, exists := g.circuitBreakers[name]; exists {
		return cb
	}

	cb = NewCircuitBreaker(g.circuitThreshold, g.circuitTimeout)
	g.circuitBreakers[name] = cb
	return cb
}

type HTTPModelProvider struct {
	name       string
	baseURL    string
	apiKey     string
	models     []string
	httpClient *http.Client
	healthy    atomicBool
}

func NewHTTPModelProvider(cfg *ProviderConfig) *HTTPModelProvider {
	timeout := time.Duration(cfg.TimeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = DefaultTimeoutMs * time.Millisecond
	}

	p := &HTTPModelProvider{
		name:       cfg.Name,
		baseURL:    cfg.BaseURL,
		apiKey:     cfg.APIKey,
		models:     cfg.Models,
		httpClient: &http.Client{
			Timeout: timeout,
		},
	}
	p.healthy.set(true)
	go p.healthCheckLoop(timeout)
	return p
}

func (p *HTTPModelProvider) Name() string {
	return p.name
}

func (p *HTTPModelProvider) Capabilities() []string {
	models := make([]string, len(p.models))
	copy(models, p.models)
	return models
}

func (p *HTTPModelProvider) Healthy(ctx context.Context) bool {
	return p.healthy.get()
}

func (p *HTTPModelProvider) Infer(ctx context.Context, req *InferenceRequest) (*InferenceResponse, error) {
	if req == nil {
		return nil, errors.New(errors.ErrCodeValidation, "request cannot be nil")
	}

	body := map[string]interface{}{
		"model":    req.Model,
		"messages": req.Messages,
	}
	for k, v := range req.Params {
		body[k] = v
	}

	jsonBody, err := json.Marshal(body)
	if err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to marshal request")
	}

	httpReq, err := http.NewRequestWithContext(ctx, "POST", p.baseURL+"/chat/completions", bytes.NewReader(jsonBody))
	if err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to create request")
	}

	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("Authorization", "Bearer "+p.apiKey)

	resp, err := p.httpClient.Do(httpReq)
	if err != nil {
		p.healthy.set(false)
		return nil, errors.Wrap(err, errors.ErrCodeUnavailable, "provider request failed")
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 500 {
		p.healthy.set(false)
		io.Copy(io.Discard, resp.Body)
		return nil, errors.New(errors.ErrCodeUnavailable, "provider server error")
	}

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to read response")
	}

	var result struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
		Usage struct {
			PromptTokens     int `json:"prompt_tokens"`
			CompletionTokens int `json:"completion_tokens"`
			TotalTokens      int `json:"total_tokens"`
		} `json:"usage"`
	}

	if err := json.Unmarshal(respBody, &result); err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to parse response")
	}

	text := ""
	if len(result.Choices) > 0 {
		text = result.Choices[0].Message.Content
	}

	return &InferenceResponse{
		TraceID:  req.TraceID,
		Provider: p.name,
		Model:    req.Model,
		Text:     text,
		Usage: &TokenUsage{
			PromptTokens:     result.Usage.PromptTokens,
			CompletionTokens: result.Usage.CompletionTokens,
			TotalTokens:      result.Usage.TotalTokens,
		},
	}, nil
}

func (p *HTTPModelProvider) healthCheckLoop(interval time.Duration) {
	if interval <= 0 {
		interval = DefaultTimeoutMs * time.Millisecond
	}

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for range ticker.C {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		req, err := http.NewRequestWithContext(ctx, "GET", p.baseURL+"/models", nil)
		if err == nil {
			req.Header.Set("Authorization", "Bearer "+p.apiKey)
			resp, err := p.httpClient.Do(req)
			if err == nil {
				io.Copy(io.Discard, resp.Body)
				resp.Body.Close()
				p.healthy.set(resp.StatusCode < 500)
			} else {
				p.healthy.set(false)
			}
		}
		cancel()
	}
}

type atomicBool struct {
	val int32
}

func (b *atomicBool) set(value bool) {
	if value {
		atomic.StoreInt32(&b.val, 1)
	} else {
		atomic.StoreInt32(&b.val, 0)
	}
}

func (b *atomicBool) get() bool {
	return atomic.LoadInt32(&b.val) == 1
}
