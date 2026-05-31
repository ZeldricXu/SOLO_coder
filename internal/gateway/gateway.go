package gateway

import (
	"context"
	"errors"
	"fmt"
	"math/rand"
	"sync"
	"sync/atomic"
	"time"

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/internal/logging"
)

type ModelProvider string

const (
	ProviderOpenAI    ModelProvider = "openai"
	ProviderAnthropic ModelProvider = "anthropic"
	ProviderAzure     ModelProvider = "azure"
	ProviderGoogle    ModelProvider = "google"
	ProviderBedrock   ModelProvider = "bedrock"
	ProviderLocal     ModelProvider = "local"
	ProviderCustom    ModelProvider = "custom"
)

type LoadBalancingStrategy string

const (
	StrategyRoundRobin LoadBalancingStrategy = "round_robin"
	StrategyLeastConn  LoadBalancingStrategy = "least_connections"
	StrategyRandom     LoadBalancingStrategy = "random"
	StrategyWeighted   LoadBalancingStrategy = "weighted"
	StrategyLatency    LoadBalancingStrategy = "latency_aware"
)

type CircuitBreakerState string

const (
	StateClosed     CircuitBreakerState = "closed"
	StateOpen       CircuitBreakerState = "open"
	StateHalfOpen   CircuitBreakerState = "half_open"
)

type ModelType string

const (
	ModelTypeChat      ModelType = "chat"
	ModelTypeCompletion ModelType = "completion"
	ModelTypeEmbedding ModelType = "embedding"
	ModelTypeImage     ModelType = "image"
	ModelTypeAudio     ModelType = "audio"
)

type ChatMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
	Name    string `json:"name,omitempty"`
}

type ChatRequest struct {
	Model       string        `json:"model"`
	Messages    []ChatMessage `json:"messages"`
	Temperature *float64      `json:"temperature,omitempty"`
	MaxTokens   *int          `json:"max_tokens,omitempty"`
	TopP        *float64      `json:"top_p,omitempty"`
	Stream      bool          `json:"stream,omitempty"`
	User        string        `json:"user,omitempty"`
	Metadata    map[string]interface{} `json:"metadata,omitempty"`
}

type ChatResponse struct {
	ID        string `json:"id"`
	Model     string `json:"model"`
	Provider  string `json:"provider"`
	Message   ChatMessage `json:"message"`
	Usage     Usage  `json:"usage"`
	LatencyMs int64  `json:"latency_ms"`
}

type Usage struct {
	PromptTokens     int `json:"prompt_tokens"`
	CompletionTokens int `json:"completion_tokens"`
	TotalTokens      int `json:"total_tokens"`
}

type EmbeddingRequest struct {
	Model string   `json:"model"`
	Input []string `json:"input"`
	User  string   `json:"user,omitempty"`
}

type EmbeddingResponse struct {
	Model      string      `json:"model"`
	Provider   string      `json:"provider"`
	Embeddings [][]float32 `json:"embeddings"`
	Usage      Usage       `json:"usage"`
	LatencyMs  int64       `json:"latency_ms"`
}

type ProviderConfig struct {
	Provider      ModelProvider `json:"provider"`
	APIKey        string        `json:"api_key,omitempty"`
	Endpoint      string        `json:"endpoint,omitempty"`
	Region        string        `json:"region,omitempty"`
	APIVersion    string        `json:"api_version,omitempty"`
	Weight        float64       `json:"weight"`
	MaxRetries    int           `json:"max_retries"`
	Timeout       time.Duration `json:"timeout"`
	Enabled       bool          `json:"enabled"`
	RateLimit     int           `json:"rate_limit"`
}

type ModelEndpoint struct {
	ID           string        `json:"id"`
	Provider     ModelProvider `json:"provider"`
	ModelName    string        `json:"model_name"`
	ModelType    ModelType     `json:"model_type"`
	BaseURL      string        `json:"base_url"`
	APIKey       string        `json:"-"`
	Weight       float64       `json:"weight"`
	Priority     int           `json:"priority"`
	Enabled      bool          `json:"enabled"`
	Healthy      bool          `json:"healthy"`
	Timeout      time.Duration `json:"timeout"`
	MaxRetries   int           `json:"max_retries"`

	activeRequests  int64
	totalRequests   int64
	failedRequests  int64
	totalLatencyMs  int64
	lastUsed        time.Time
	circuitBreaker  *CircuitBreaker
}

type CircuitBreaker struct {
	mu              sync.Mutex
	state           CircuitBreakerState
	failureCount    int
	successCount    int
	threshold       int
	timeout         time.Duration
	lastFailureTime time.Time
}

func NewCircuitBreaker(threshold int, timeout time.Duration) *CircuitBreaker {
	return &CircuitBreaker{
		state:     StateClosed,
		threshold: threshold,
		timeout:   timeout,
	}
}

func (cb *CircuitBreaker) Allow() bool {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	switch cb.state {
	case StateClosed:
		return true
	case StateOpen:
		if time.Since(cb.lastFailureTime) > cb.timeout {
			cb.state = StateHalfOpen
			cb.successCount = 0
			return true
		}
		return false
	case StateHalfOpen:
		return true
	default:
		return true
	}
}

func (cb *CircuitBreaker) RecordSuccess() {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	if cb.state == StateHalfOpen {
		cb.successCount++
		if cb.successCount >= cb.threshold {
			cb.state = StateClosed
			cb.failureCount = 0
		}
	} else {
		cb.failureCount = 0
	}
}

func (cb *CircuitBreaker) RecordFailure() {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	cb.failureCount++
	cb.lastFailureTime = time.Now()

	if cb.state == StateHalfOpen || cb.failureCount >= cb.threshold {
		cb.state = StateOpen
		cb.successCount = 0
	}
}

func (cb *CircuitBreaker) GetState() CircuitBreakerState {
	cb.mu.Lock()
	defer cb.mu.Unlock()
	return cb.state
}

type Provider interface {
	Chat(ctx context.Context, req *ChatRequest) (*ChatResponse, error)
	Embeddings(ctx context.Context, req *EmbeddingRequest) (*EmbeddingResponse, error)
	SupportsModel(modelName string) bool
	GetProvider() ModelProvider
	HealthCheck(ctx context.Context) bool
}

type MockOpenAIProvider struct {
	config ProviderConfig
}

func NewMockOpenAIProvider(config ProviderConfig) *MockOpenAIProvider {
	return &MockOpenAIProvider{config: config}
}

func (p *MockOpenAIProvider) GetProvider() ModelProvider {
	return ProviderOpenAI
}

func (p *MockOpenAIProvider) SupportsModel(modelName string) bool {
	return modelName == "gpt-4" || modelName == "gpt-3.5-turbo" || modelName == "text-embedding-3-small"
}

func (p *MockOpenAIProvider) Chat(ctx context.Context, req *ChatRequest) (*ChatResponse, error) {
	time.Sleep(100 * time.Millisecond)

	content := "This is a mock response from OpenAI " + req.Model

	combinedInput := ""
	for _, msg := range req.Messages {
		combinedInput += msg.Content + " "
	}
	promptTokens := estimateTokens(combinedInput)
	completionTokens := estimateTokens(content)

	return &ChatResponse{
		ID:       "chatcmpl_" + generateID(),
		Model:    req.Model,
		Provider: string(ProviderOpenAI),
		Message: ChatMessage{
			Role:    "assistant",
			Content: content,
		},
		Usage: Usage{
			PromptTokens:     promptTokens,
			CompletionTokens: completionTokens,
			TotalTokens:      promptTokens + completionTokens,
		},
	}, nil
}

func (p *MockOpenAIProvider) Embeddings(ctx context.Context, req *EmbeddingRequest) (*EmbeddingResponse, error) {
	time.Sleep(50 * time.Millisecond)

	embeddings := make([][]float32, len(req.Input))
	for i, input := range req.Input {
		emb := make([]float32, 1536)
		seed := int64(0)
		for _, c := range input {
			seed += int64(c)
		}
		for j := range emb {
			seed = (seed*1103515245 + 12345) & 0x7fffffff
			emb[j] = float32(seed)/float32(0x7fffffff)*2 - 1
		}
		embeddings[i] = emb
	}

	totalTokens := 0
	for _, input := range req.Input {
		totalTokens += estimateTokens(input)
	}

	return &EmbeddingResponse{
		Model:      req.Model,
		Provider:   string(ProviderOpenAI),
		Embeddings: embeddings,
		Usage: Usage{
			PromptTokens: totalTokens,
			TotalTokens:  totalTokens,
		},
	}, nil
}

func (p *MockOpenAIProvider) HealthCheck(ctx context.Context) bool {
	return p.config.Enabled
}

type MockAnthropicProvider struct {
	config ProviderConfig
}

func NewMockAnthropicProvider(config ProviderConfig) *MockAnthropicProvider {
	return &MockAnthropicProvider{config: config}
}

func (p *MockAnthropicProvider) GetProvider() ModelProvider {
	return ProviderAnthropic
}

func (p *MockAnthropicProvider) SupportsModel(modelName string) bool {
	return modelName == "claude-3-opus" || modelName == "claude-3-sonnet" || modelName == "claude-3-haiku"
}

func (p *MockAnthropicProvider) Chat(ctx context.Context, req *ChatRequest) (*ChatResponse, error) {
	time.Sleep(150 * time.Millisecond)

	content := "This is a mock response from Anthropic " + req.Model

	combinedInput := ""
	for _, msg := range req.Messages {
		combinedInput += msg.Content + " "
	}
	promptTokens := estimateTokens(combinedInput)
	completionTokens := estimateTokens(content)

	return &ChatResponse{
		ID:       "msg_" + generateID(),
		Model:    req.Model,
		Provider: string(ProviderAnthropic),
		Message: ChatMessage{
			Role:    "assistant",
			Content: content,
		},
		Usage: Usage{
			PromptTokens:     promptTokens,
			CompletionTokens: completionTokens,
			TotalTokens:      promptTokens + completionTokens,
		},
	}, nil
}

func (p *MockAnthropicProvider) Embeddings(ctx context.Context, req *EmbeddingRequest) (*EmbeddingResponse, error) {
	return nil, errors.New("anthropic does not support embeddings")
}

func (p *MockAnthropicProvider) HealthCheck(ctx context.Context) bool {
	return p.config.Enabled
}

type RouterConfig struct {
	LoadBalancingStrategy LoadBalancingStrategy
	EnableFallback        bool
	EnableCircuitBreaker  bool
	CircuitBreakerThreshold int
	CircuitBreakerTimeout  time.Duration
	GlobalTimeout         time.Duration
	MaxRetries            int
}

type InferenceGateway struct {
	providers      map[ModelProvider]Provider
	endpoints      map[string][]*ModelEndpoint
	endpointsMu    sync.RWMutex
	config         RouterConfig
	roundRobinIdx  uint64
	totalRequests  int64
	totalLatency   int64
	usageTracker   *UsageTracker
}

type UsageTracker struct {
	mu           sync.Mutex
	totalTokens  int64
	promptTokens int64
	completionTokens int64
	requests     map[string]int64
}

func NewUsageTracker() *UsageTracker {
	return &UsageTracker{
		requests: make(map[string]int64),
	}
}

func (t *UsageTracker) Record(model string, usage Usage) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.totalTokens += int64(usage.TotalTokens)
	t.promptTokens += int64(usage.PromptTokens)
	t.completionTokens += int64(usage.CompletionTokens)
	t.requests[model]++
}

func (t *UsageTracker) GetStats() map[string]interface{} {
	t.mu.Lock()
	defer t.mu.Unlock()

	stats := make(map[string]interface{})
	stats["total_tokens"] = t.totalTokens
	stats["prompt_tokens"] = t.promptTokens
	stats["completion_tokens"] = t.completionTokens
	stats["requests_per_model"] = t.requests
	return stats
}

func NewInferenceGateway(config RouterConfig) *InferenceGateway {
	return &InferenceGateway{
		providers:    make(map[ModelProvider]Provider),
		endpoints:    make(map[string][]*ModelEndpoint),
		config:       config,
		usageTracker: NewUsageTracker(),
	}
}

func (g *InferenceGateway) RegisterProvider(provider Provider, endpointConfigs []*ModelEndpoint) {
	g.providers[provider.GetProvider()] = provider

	for _, ep := range endpointConfigs {
		ep.circuitBreaker = NewCircuitBreaker(g.config.CircuitBreakerThreshold, g.config.CircuitBreakerTimeout)
		g.endpointsMu.Lock()
		g.endpoints[ep.ModelName] = append(g.endpoints[ep.ModelName], ep)
		g.endpointsMu.Unlock()
	}
}

func (g *InferenceGateway) RegisterProviderConfig(config ProviderConfig) {
	var provider Provider
	switch config.Provider {
	case ProviderOpenAI:
		provider = NewMockOpenAIProvider(config)
	case ProviderAnthropic:
		provider = NewMockAnthropicProvider(config)
	default:
		provider = NewMockOpenAIProvider(config)
	}

	g.providers[config.Provider] = provider
}

func (g *InferenceGateway) Chat(ctx context.Context, req *ChatRequest) (*ChatResponse, error) {
	startTime := time.Now()
	ctx, cancel := context.WithTimeout(ctx, g.config.GlobalTimeout)
	defer cancel()

	endpoint, err := g.selectEndpoint(req.Model, ModelTypeChat)
	if err != nil {
		return nil, fmt.Errorf("no endpoint available: %w", err)
	}

	provider, exists := g.providers[endpoint.Provider]
	if !exists {
		return nil, fmt.Errorf("provider not found: %s", endpoint.Provider)
	}

	atomic.AddInt64(&endpoint.activeRequests, 1)
	atomic.AddInt64(&endpoint.totalRequests, 1)
	endpoint.lastUsed = time.Now()

	defer func() {
		atomic.AddInt64(&endpoint.activeRequests, -1)
	}()

	var lastErr error
	for attempt := 0; attempt < g.config.MaxRetries; attempt++ {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		default:
		}

		if g.config.EnableCircuitBreaker && !endpoint.circuitBreaker.Allow() {
			lastErr = fmt.Errorf("circuit breaker open for endpoint %s", endpoint.ID)
			break
		}

		resp, err := provider.Chat(ctx, req)

		if err != nil {
			lastErr = err
			atomic.AddInt64(&endpoint.failedRequests, 1)
			if g.config.EnableCircuitBreaker {
				endpoint.circuitBreaker.RecordFailure()
			}

			if g.config.EnableFallback {
				endpoint, err = g.getFallbackEndpoint(req.Model, ModelTypeChat, endpoint)
				if err != nil {
					break
				}
				provider = g.providers[endpoint.Provider]
				continue
			}
			break
		}

		if g.config.EnableCircuitBreaker {
			endpoint.circuitBreaker.RecordSuccess()
		}

		resp.LatencyMs = time.Since(startTime).Milliseconds()
		atomic.AddInt64(&endpoint.totalLatencyMs, resp.LatencyMs)
		atomic.AddInt64(&g.totalRequests, 1)
		atomic.AddInt64(&g.totalLatency, resp.LatencyMs)

		g.usageTracker.Record(req.Model, resp.Usage)

		return resp, nil
	}

	return nil, fmt.Errorf("all attempts failed: %w", lastErr)
}

func (g *InferenceGateway) Embeddings(ctx context.Context, req *EmbeddingRequest) (*EmbeddingResponse, error) {
	startTime := time.Now()
	ctx, cancel := context.WithTimeout(ctx, g.config.GlobalTimeout)
	defer cancel()

	endpoint, err := g.selectEndpoint(req.Model, ModelTypeEmbedding)
	if err != nil {
		return nil, fmt.Errorf("no endpoint available: %w", err)
	}

	provider, exists := g.providers[endpoint.Provider]
	if !exists {
		return nil, fmt.Errorf("provider not found: %s", endpoint.Provider)
	}

	atomic.AddInt64(&endpoint.activeRequests, 1)
	atomic.AddInt64(&endpoint.totalRequests, 1)
	endpoint.lastUsed = time.Now()

	defer func() {
		atomic.AddInt64(&endpoint.activeRequests, -1)
	}()

	resp, err := provider.Embeddings(ctx, req)
	if err != nil {
		atomic.AddInt64(&endpoint.failedRequests, 1)
		if g.config.EnableFallback {
			fallbackEndpoint, fbErr := g.getFallbackEndpoint(req.Model, ModelTypeEmbedding, endpoint)
			if fbErr == nil {
				fallbackProvider := g.providers[fallbackEndpoint.Provider]
				resp, err = fallbackProvider.Embeddings(ctx, req)
			}
		}
		if err != nil {
			return nil, err
		}
	}

	resp.LatencyMs = time.Since(startTime).Milliseconds()
	atomic.AddInt64(&endpoint.totalLatencyMs, resp.LatencyMs)
	atomic.AddInt64(&g.totalRequests, 1)
	atomic.AddInt64(&g.totalLatency, resp.LatencyMs)

	g.usageTracker.Record(req.Model, resp.Usage)

	return resp, nil
}

func (g *InferenceGateway) selectEndpoint(modelName string, modelType ModelType) (*ModelEndpoint, error) {
	g.endpointsMu.RLock()
	endpoints, exists := g.endpoints[modelName]
	g.endpointsMu.RUnlock()

	if !exists || len(endpoints) == 0 {
		return nil, fmt.Errorf("no endpoints registered for model: %s", modelName)
	}

	var healthyEndpoints []*ModelEndpoint
	for _, ep := range endpoints {
		if ep.Enabled && ep.Healthy && ep.circuitBreaker.GetState() != StateOpen {
			healthyEndpoints = append(healthyEndpoints, ep)
		}
	}

	if len(healthyEndpoints) == 0 {
		for _, ep := range endpoints {
			if ep.Enabled {
				healthyEndpoints = append(healthyEndpoints, ep)
			}
		}
	}

	if len(healthyEndpoints) == 0 {
		return nil, fmt.Errorf("no healthy endpoints for model: %s", modelName)
	}

	switch g.config.LoadBalancingStrategy {
	case StrategyRoundRobin:
		return g.roundRobin(healthyEndpoints), nil
	case StrategyLeastConn:
		return g.leastConnections(healthyEndpoints), nil
	case StrategyRandom:
		return g.random(healthyEndpoints), nil
	case StrategyWeighted:
		return g.weighted(healthyEndpoints), nil
	case StrategyLatency:
		return g.latencyAware(healthyEndpoints), nil
	default:
		return g.roundRobin(healthyEndpoints), nil
	}
}

func (g *InferenceGateway) roundRobin(endpoints []*ModelEndpoint) *ModelEndpoint {
	idx := atomic.AddUint64(&g.roundRobinIdx, 1) - 1
	return endpoints[idx%uint64(len(endpoints))]
}

func (g *InferenceGateway) leastConnections(endpoints []*ModelEndpoint) *ModelEndpoint {
	var best *ModelEndpoint
	var minActive int64 = 1<<63 - 1

	for _, ep := range endpoints {
		active := atomic.LoadInt64(&ep.activeRequests)
		if active < minActive {
			minActive = active
			best = ep
		}
	}

	return best
}

func (g *InferenceGateway) random(endpoints []*ModelEndpoint) *ModelEndpoint {
	idx := rand.Intn(len(endpoints))
	return endpoints[idx]
}

func (g *InferenceGateway) weighted(endpoints []*ModelEndpoint) *ModelEndpoint {
	totalWeight := 0.0
	for _, ep := range endpoints {
		totalWeight += ep.Weight
	}

	if totalWeight == 0 {
		return endpoints[0]
	}

	r := rand.Float64() * totalWeight
	cumulative := 0.0
	for _, ep := range endpoints {
		cumulative += ep.Weight
		if r < cumulative {
			return ep
		}
	}

	return endpoints[len(endpoints)-1]
}

func (g *InferenceGateway) latencyAware(endpoints []*ModelEndpoint) *ModelEndpoint {
	var best *ModelEndpoint
	var minLatency int64 = 1<<63 - 1

	for _, ep := range endpoints {
		totalReq := atomic.LoadInt64(&ep.totalRequests)
		if totalReq == 0 {
			return ep
		}
		avgLatency := atomic.LoadInt64(&ep.totalLatencyMs) / totalReq
		if avgLatency < minLatency {
			minLatency = avgLatency
			best = ep
		}
	}

	if best == nil {
		return endpoints[0]
	}
	return best
}

func (g *InferenceGateway) getFallbackEndpoint(modelName string, modelType ModelType, failed *ModelEndpoint) (*ModelEndpoint, error) {
	g.endpointsMu.RLock()
	endpoints, exists := g.endpoints[modelName]
	g.endpointsMu.RUnlock()

	if !exists {
		return nil, fmt.Errorf("no endpoints for model: %s", modelName)
	}

	for _, ep := range endpoints {
		if ep.ID != failed.ID && ep.Enabled && ep.Healthy && ep.circuitBreaker.GetState() != StateOpen {
			return ep, nil
		}
	}

	return nil, errors.New("no fallback endpoint available")
}

func (g *InferenceGateway) HealthCheck(ctx context.Context) map[string]bool {
	results := make(map[string]bool)

	for name, provider := range g.providers {
		results[string(name)] = provider.HealthCheck(ctx)
	}

	g.endpointsMu.RLock()
	for model, endpoints := range g.endpoints {
		for _, ep := range endpoints {
			healthy := g.providers[ep.Provider].HealthCheck(ctx)
			ep.Healthy = healthy
			results[model+"/"+ep.ID] = healthy
		}
	}
	g.endpointsMu.RUnlock()

	return results
}

func (g *InferenceGateway) GetEndpointStats() map[string]interface{} {
	g.endpointsMu.RLock()
	defer g.endpointsMu.RUnlock()

	stats := make(map[string]interface{})

	for model, endpoints := range g.endpoints {
		modelStats := make([]map[string]interface{}, 0, len(endpoints))
		for _, ep := range endpoints {
			totalReq := atomic.LoadInt64(&ep.totalRequests)
			failedReq := atomic.LoadInt64(&ep.failedRequests)
			activeReq := atomic.LoadInt64(&ep.activeRequests)
			avgLatency := int64(0)
			if totalReq > 0 {
				avgLatency = atomic.LoadInt64(&ep.totalLatencyMs) / totalReq
			}

			modelStats = append(modelStats, map[string]interface{}{
				"id":                ep.ID,
				"provider":          ep.Provider,
				"model":             ep.ModelName,
				"healthy":           ep.Healthy,
				"circuit_state":     ep.circuitBreaker.GetState(),
				"total_requests":    totalReq,
				"failed_requests":   failedReq,
				"active_requests":   activeReq,
				"avg_latency_ms":    avgLatency,
				"weight":            ep.Weight,
				"last_used":         ep.lastUsed,
			})
		}
		stats[model] = modelStats
	}

	gatewayStats := make(map[string]interface{})
	gatewayStats["total_requests"] = atomic.LoadInt64(&g.totalRequests)
	if total := atomic.LoadInt64(&g.totalRequests); total > 0 {
		gatewayStats["avg_latency_ms"] = atomic.LoadInt64(&g.totalLatency) / total
	}
	gatewayStats["usage"] = g.usageTracker.GetStats()
	stats["_gateway"] = gatewayStats

	return stats
}

func (g *InferenceGateway) GetUsageStats() map[string]interface{} {
	return g.usageTracker.GetStats()
}

func estimateTokens(text string) int {
	return len([]rune(text)) / 4
}

func generateID() string {
	const letters = "abcdefghijklmnopqrstuvwxyz0123456789"
	b := make([]byte, 16)
	for i := range b {
		b[i] = letters[time.Now().UnixNano()%int64(len(letters))]
	}
	return string(b)
}

type GatewayMiddleware func(next HandlerFunc) HandlerFunc

type HandlerFunc func(ctx context.Context, req *ChatRequest) (*ChatResponse, error)

func (g *InferenceGateway) Use(middleware GatewayMiddleware) {
}

func LoggingMiddleware() GatewayMiddleware {
	return func(next HandlerFunc) HandlerFunc {
		return func(ctx context.Context, req *ChatRequest) (*ChatResponse, error) {
			startTime := time.Now()
			resp, err := next(ctx, req)

			duration := time.Since(startTime)
			logging.Info(ctx, "Inference request completed",
				zap.String("model", req.Model),
				zap.Duration("duration", duration),
				zap.Error(err))

			return resp, err
		}
	}
}
