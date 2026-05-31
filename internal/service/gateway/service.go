package gateway

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"

	"gorm.io/gorm"

	"llmgateway/internal/domain/entity"
	"llmgateway/internal/infrastructure/cache"
	"llmgateway/internal/infrastructure/config"
	"llmgateway/internal/infrastructure/database"
	"llmgateway/internal/infrastructure/logger"
	"llmgateway/pkg/utils"
)

type Service struct {
	db           *gorm.DB
	providers    map[string]*ProviderClient
	providersMu  sync.RWMutex
	loadBalancer LoadBalancer
}

type ProviderClient struct {
	config     *entity.Provider
	httpClient *http.Client
}

type LoadBalancer interface {
	SelectProvider(modelID string) (*entity.Provider, error)
	ReportSuccess(providerID string)
	ReportFailure(providerID string)
}

type RoundRobinLB struct {
	providers    []*entity.Provider
	currentIndex int
	mu           sync.Mutex
}

func NewService(cfg *config.Config) (*Service, error) {
	s := &Service{
		db:        database.DB(),
		providers: make(map[string]*ProviderClient),
	}

	s.loadBalancer = &RoundRobinLB{}

	for _, pc := range cfg.Gateway.Providers {
		provider := &entity.Provider{
			ID:         utils.GenerateID("prov"),
			Name:       pc.Name,
			Type:       pc.Name,
			BaseURL:    pc.BaseURL,
			APIKey:     pc.APIKey,
			Timeout:    int(pc.Timeout.Seconds()),
			MaxRetries: pc.MaxRetries,
			Enabled:    true,
			Priority:   1,
			Weight:     100,
			CreatedAt:  utils.Now(),
			UpdatedAt:  utils.Now(),
		}
		s.providers[pc.Name] = &ProviderClient{
			config: provider,
			httpClient: &http.Client{
				Timeout: pc.Timeout,
			},
		}
	}

	return s, nil
}

func (lb *RoundRobinLB) SelectProvider(modelID string) (*entity.Provider, error) {
	lb.mu.Lock()
	defer lb.mu.Unlock()

	if len(lb.providers) == 0 {
		return nil, errors.New("no available providers")
	}

	lb.currentIndex = (lb.currentIndex + 1) % len(lb.providers)
	return lb.providers[lb.currentIndex], nil
}

func (lb *RoundRobinLB) ReportSuccess(providerID string) {}
func (lb *RoundRobinLB) ReportFailure(providerID string) {}

func (s *Service) RegisterProvider(provider *entity.Provider) error {
	s.providersMu.Lock()
	defer s.providersMu.Unlock()

	if _, exists := s.providers[provider.Name]; exists {
		return errors.New("provider already registered")
	}

	s.providers[provider.Name] = &ProviderClient{
		config: provider,
		httpClient: &http.Client{
			Timeout: time.Duration(provider.Timeout) * time.Second,
		},
	}

	if err := s.db.Create(provider).Error; err != nil {
		return fmt.Errorf("failed to save provider: %w", err)
	}

	logger.Info("provider registered", "provider_id", provider.ID, "name", provider.Name)
	return nil
}

func (s *Service) GetProvider(name string) (*entity.Provider, error) {
	s.providersMu.RLock()
	defer s.providersMu.RUnlock()

	client, exists := s.providers[name]
	if !exists {
		return nil, errors.New("provider not found")
	}
	return client.config, nil
}

func (s *Service) ListProviders() ([]entity.Provider, error) {
	s.providersMu.RLock()
	defer s.providersMu.RUnlock()

	providers := make([]entity.Provider, 0, len(s.providers))
	for _, p := range s.providers {
		providers = append(providers, *p.config)
	}
	return providers, nil
}

func (s *Service) Infer(ctx context.Context, req *entity.InferenceRequest) (*entity.InferenceResponse, error) {
	startTime := time.Now()

	provider, err := s.selectProvider(req)
	if err != nil {
		return nil, fmt.Errorf("failed to select provider: %w", err)
	}

	logger.Info("routing inference request", "request_id", req.RequestID, "model_id", req.ModelID, "provider", provider.Name)

	response, err := s.executeWithRetry(ctx, provider, req)
	if err != nil {
		logger.Error("inference failed after all retries", "request_id", req.RequestID, "error", err)
		return s.tryFallback(ctx, req, provider.Name)
	}

	latency := time.Since(startTime).Milliseconds()
	s.updateProviderMetrics(provider, latency, false)

	response.Latency = latency
	logger.Info("inference completed", "request_id", req.RequestID, "provider", provider.Name, "latency_ms", latency)

	return response, nil
}

func (s *Service) selectProvider(req *entity.InferenceRequest) (*entity.Provider, error) {
	if req.Provider != "" {
		s.providersMu.RLock()
		defer s.providersMu.RUnlock()
		client, exists := s.providers[req.Provider]
		if !exists {
			return nil, fmt.Errorf("provider %s not found", req.Provider)
		}
		if !client.config.Enabled {
			return nil, fmt.Errorf("provider %s is disabled", req.Provider)
		}
		return client.config, nil
	}

	return s.loadBalancer.SelectProvider(req.ModelID)
}

func (s *Service) executeWithRetry(ctx context.Context, provider *entity.Provider, req *entity.InferenceRequest) (*entity.InferenceResponse, error) {
	var lastErr error

	for attempt := 0; attempt <= provider.MaxRetries; attempt++ {
		if attempt > 0 {
			delay := time.Duration(attempt*attempt) * time.Second
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(delay):
			}
			logger.Info("retrying inference", "request_id", req.RequestID, "attempt", attempt, "provider", provider.Name)
		}

		resp, err := s.executeProviderCall(ctx, provider, req)
		if err == nil {
			s.loadBalancer.ReportSuccess(provider.ID)
			return resp, nil
		}

		lastErr = err
		s.loadBalancer.ReportFailure(provider.ID)
		logger.Warn("inference attempt failed", "request_id", req.RequestID, "attempt", attempt, "error", err)
	}

	return nil, lastErr
}

func (s *Service) executeProviderCall(ctx context.Context, provider *entity.Provider, req *entity.InferenceRequest) (*entity.InferenceResponse, error) {
	s.providersMu.RLock()
	client := s.providers[provider.Name]
	s.providersMu.RUnlock()

	payload := s.buildProviderPayload(provider, req)
	body, err := json.Marshal(payload)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal payload: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, "POST", provider.BaseURL+"/chat/completions", bytes.NewBuffer(body))
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("Authorization", "Bearer "+provider.APIKey)

	httpResp, err := client.httpClient.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("request failed: %w", err)
	}
	defer httpResp.Body.Close()

	respBody, err := io.ReadAll(httpResp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response: %w", err)
	}

	if httpResp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("provider returned status %d: %s", httpResp.StatusCode, string(respBody))
	}

	return s.parseProviderResponse(provider, req, respBody)
}

func (s *Service) buildProviderPayload(provider *entity.Provider, req *entity.InferenceRequest) map[string]interface{} {
	payload := map[string]interface{}{
		"model": req.ModelID,
	}

	if messages, ok := req.Input["messages"].([]interface{}); ok {
		payload["messages"] = messages
	}

	for k, v := range req.Parameters {
		payload[k] = v
	}

	if req.Stream {
		payload["stream"] = true
	}

	return payload
}

func (s *Service) parseProviderResponse(provider *entity.Provider, req *entity.InferenceRequest, body []byte) (*entity.InferenceResponse, error) {
	var openAIResp struct {
		ID      string `json:"id"`
		Object  string `json:"object"`
		Choices []struct {
			Message struct {
				Role    string `json:"role"`
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
		Usage struct {
			PromptTokens     int `json:"prompt_tokens"`
			CompletionTokens int `json:"completion_tokens"`
			TotalTokens      int `json:"total_tokens"`
		} `json:"usage"`
	}

	if err := json.Unmarshal(body, &openAIResp); err != nil {
		return nil, fmt.Errorf("failed to parse response: %w", err)
	}

	content := ""
	if len(openAIResp.Choices) > 0 {
		content = openAIResp.Choices[0].Message.Content
	}

	return &entity.InferenceResponse{
		RequestID: req.RequestID,
		ModelID:   req.ModelID,
		Provider:  provider.Name,
		Output: map[string]interface{}{
			"content": content,
		},
		Tokens: entity.InferenceTokens{
			Prompt:     openAIResp.Usage.PromptTokens,
			Completion: openAIResp.Usage.CompletionTokens,
			Total:      openAIResp.Usage.TotalTokens,
		},
	}, nil
}

func (s *Service) tryFallback(ctx context.Context, req *entity.InferenceRequest, failedProvider string) (*entity.InferenceResponse, error) {
	s.providersMu.RLock()
	defer s.providersMu.RUnlock()

	for name, client := range s.providers {
		if name == failedProvider {
			continue
		}
		if !client.config.Enabled {
			continue
		}

		logger.Info("trying fallback provider", "request_id", req.RequestID, "fallback_provider", name)
		resp, err := s.executeProviderCall(ctx, client.config, req)
		if err == nil {
			return resp, nil
		}
		logger.Warn("fallback provider also failed", "request_id", req.RequestID, "provider", name, "error", err)
	}

	return nil, errors.New("all providers failed, no fallback available")
}

func (s *Service) updateProviderMetrics(provider *entity.Provider, latency int64, isError bool) {
	cacheKey := fmt.Sprintf("provider_metrics:%s", provider.ID)
	_, err := cache.HGetAll(context.Background(), cacheKey)
	if err != nil {
		return
	}

	errorRate := 0.0
	if isError {
		errorRate = 1.0
	}

	_ = cache.HSet(context.Background(), cacheKey,
		"last_latency", latency,
		"error_rate", errorRate,
		"last_updated", time.Now().Unix(),
	)
}

type StreamCallback func(chunk map[string]interface{}) error

func (s *Service) StreamInfer(ctx context.Context, req *entity.InferenceRequest, callback StreamCallback) error {
	provider, err := s.selectProvider(req)
	if err != nil {
		return err
	}

	s.providersMu.RLock()
	client := s.providers[provider.Name]
	s.providersMu.RUnlock()

	payload := s.buildProviderPayload(provider, req)
	body, _ := json.Marshal(payload)

	httpReq, _ := http.NewRequestWithContext(ctx, "POST", provider.BaseURL+"/chat/completions", bytes.NewBuffer(body))
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("Authorization", "Bearer "+provider.APIKey)
	httpReq.Header.Set("Accept", "text/event-stream")

	resp, err := client.httpClient.Do(httpReq)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	reader := io.Reader(resp.Body)
	buf := make([]byte, 1024)
	for {
		n, err := reader.Read(buf)
		if n > 0 {
			_ = callback(map[string]interface{}{
				"chunk": string(buf[:n]),
			})
		}
		if err != nil {
			if err == io.EOF {
				break
			}
			return err
		}
	}

	return nil
}
