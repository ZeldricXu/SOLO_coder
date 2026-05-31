package traffic

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/abstraction"
	"github.com/chaoslab/platform/internal/common"
	"go.uber.org/zap"
)

type TrafficControllerService struct {
	policies map[string]*common.TrafficPolicy
	mu       sync.RWMutex
}

func NewTrafficControllerService() abstraction.TrafficController {
	return &TrafficControllerService{
		policies: make(map[string]*common.TrafficPolicy),
	}
}

func (s *TrafficControllerService) ConfigureCanary(ctx context.Context, cfg *common.CanaryConfig) (*common.TrafficPolicy, error) {
	if cfg == nil {
		return nil, common.NewBadRequestError("canary config cannot be nil")
	}
	if cfg.Namespace == "" {
		return nil, common.NewValidationError("namespace is required", "namespace")
	}
	if cfg.Service == "" {
		return nil, common.NewValidationError("service is required", "service")
	}
	if cfg.PrimaryVersion == "" || cfg.CanaryVersion == "" {
		return nil, common.NewValidationError("primary and canary versions are required", "versions")
	}
	if cfg.TrafficWeight < 0 || cfg.TrafficWeight > 100 {
		return nil, common.NewValidationError("traffic weight must be between 0 and 100", "traffic_weight")
	}

	cfg.PolicyID = fmt.Sprintf("pol_canary_%d", time.Now().UnixNano())

	configBytes, _ := json.Marshal(cfg)
	var configMap map[string]interface{}
	json.Unmarshal(configBytes, &configMap)

	policy := &common.TrafficPolicy{
		PolicyID:  cfg.PolicyID,
		Type:      "canary",
		Namespace: cfg.Namespace,
		Service:   cfg.Service,
		Config:    configMap,
		Status:    "active",
		Enabled:   true,
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}

	s.mu.Lock()
	s.policies[policy.PolicyID] = policy
	s.mu.Unlock()

	common.Info("canary policy configured",
		zap.String("policy_id", policy.PolicyID),
		zap.String("service", cfg.Service),
		zap.Int32("traffic_weight", cfg.TrafficWeight),
		zap.String("primary_version", cfg.PrimaryVersion),
		zap.String("canary_version", cfg.CanaryVersion),
	)

	return policy, nil
}

func (s *TrafficControllerService) ConfigureBlueGreen(ctx context.Context, cfg *common.BlueGreenConfig) (*common.TrafficPolicy, error) {
	if cfg == nil {
		return nil, common.NewBadRequestError("bluegreen config cannot be nil")
	}
	if cfg.Namespace == "" {
		return nil, common.NewValidationError("namespace is required", "namespace")
	}
	if cfg.Service == "" {
		return nil, common.NewValidationError("service is required", "service")
	}
	if cfg.BlueVersion == "" || cfg.GreenVersion == "" {
		return nil, common.NewValidationError("blue and green versions are required", "versions")
	}
	if cfg.Active != "blue" && cfg.Active != "green" {
		cfg.Active = "blue"
	}

	cfg.PolicyID = fmt.Sprintf("pol_bg_%d", time.Now().UnixNano())

	configBytes, _ := json.Marshal(cfg)
	var configMap map[string]interface{}
	json.Unmarshal(configBytes, &configMap)

	policy := &common.TrafficPolicy{
		PolicyID:  cfg.PolicyID,
		Type:      "bluegreen",
		Namespace: cfg.Namespace,
		Service:   cfg.Service,
		Config:    configMap,
		Status:    "active",
		Enabled:   true,
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}

	s.mu.Lock()
	s.policies[policy.PolicyID] = policy
	s.mu.Unlock()

	common.Info("bluegreen policy configured",
		zap.String("policy_id", policy.PolicyID),
		zap.String("service", cfg.Service),
		zap.String("active", cfg.Active),
		zap.String("blue_version", cfg.BlueVersion),
		zap.String("green_version", cfg.GreenVersion),
	)

	return policy, nil
}

func (s *TrafficControllerService) ConfigureMirroring(ctx context.Context, cfg *common.MirrorConfig) (*common.TrafficPolicy, error) {
	if cfg == nil {
		return nil, common.NewBadRequestError("mirror config cannot be nil")
	}
	if cfg.Namespace == "" {
		return nil, common.NewValidationError("namespace is required", "namespace")
	}
	if cfg.Service == "" {
		return nil, common.NewValidationError("service is required", "service")
	}
	if cfg.Source == "" || cfg.Target == "" {
		return nil, common.NewValidationError("source and target are required", "source_target")
	}
	if cfg.SampleRate < 0 || cfg.SampleRate > 1 {
		cfg.SampleRate = 1.0
	}

	cfg.PolicyID = fmt.Sprintf("pol_mirror_%d", time.Now().UnixNano())

	configBytes, _ := json.Marshal(cfg)
	var configMap map[string]interface{}
	json.Unmarshal(configBytes, &configMap)

	policy := &common.TrafficPolicy{
		PolicyID:  cfg.PolicyID,
		Type:      "mirroring",
		Namespace: cfg.Namespace,
		Service:   cfg.Service,
		Config:    configMap,
		Status:    "active",
		Enabled:   true,
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}

	s.mu.Lock()
	s.policies[policy.PolicyID] = policy
	s.mu.Unlock()

	common.Info("mirroring policy configured",
		zap.String("policy_id", policy.PolicyID),
		zap.String("service", cfg.Service),
		zap.String("source", cfg.Source),
		zap.String("target", cfg.Target),
		zap.Float64("sample_rate", cfg.SampleRate),
	)

	return policy, nil
}

func (s *TrafficControllerService) ConfigureCircuitBreaker(ctx context.Context, cfg *common.CircuitBreakerConfig) (*common.TrafficPolicy, error) {
	if cfg == nil {
		return nil, common.NewBadRequestError("circuit breaker config cannot be nil")
	}
	if cfg.Namespace == "" {
		return nil, common.NewValidationError("namespace is required", "namespace")
	}
	if cfg.Service == "" {
		return nil, common.NewValidationError("service is required", "service")
	}
	if cfg.ErrorThreshold <= 0 {
		cfg.ErrorThreshold = 5
	}
	if cfg.Timeout <= 0 {
		cfg.Timeout = 30 * time.Second
	}

	cfg.PolicyID = fmt.Sprintf("pol_cb_%d", time.Now().UnixNano())

	configBytes, _ := json.Marshal(cfg)
	var configMap map[string]interface{}
	json.Unmarshal(configBytes, &configMap)

	policy := &common.TrafficPolicy{
		PolicyID:  cfg.PolicyID,
		Type:      "circuitbreaker",
		Namespace: cfg.Namespace,
		Service:   cfg.Service,
		Config:    configMap,
		Status:    "closed",
		Enabled:   true,
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}

	s.mu.Lock()
	s.policies[policy.PolicyID] = policy
	s.mu.Unlock()

	common.Info("circuit breaker policy configured",
		zap.String("policy_id", policy.PolicyID),
		zap.String("service", cfg.Service),
		zap.Int("error_threshold", cfg.ErrorThreshold),
		zap.Duration("timeout", cfg.Timeout),
	)

	return policy, nil
}

func (s *TrafficControllerService) GetPolicy(ctx context.Context, policyID string) (*common.TrafficPolicy, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	policy, exists := s.policies[policyID]
	if !exists {
		return nil, common.NewNotFoundError(fmt.Sprintf("policy %s not found", policyID))
	}
	return policy, nil
}

func (s *TrafficControllerService) UpdateTrafficWeight(ctx context.Context, policyID string, weight int32) error {
	if weight < 0 || weight > 100 {
		return common.NewValidationError("weight must be between 0 and 100", "weight")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	policy, exists := s.policies[policyID]
	if !exists {
		return common.NewNotFoundError(fmt.Sprintf("policy %s not found", policyID))
	}
	if policy.Type != "canary" {
		return common.NewConflictError("weight update only applies to canary policies")
	}

	if policy.Config == nil {
		policy.Config = make(map[string]interface{})
	}
	policy.Config["traffic_weight"] = weight
	policy.UpdatedAt = time.Now()

	common.Info("canary traffic weight updated",
		zap.String("policy_id", policyID),
		zap.Int32("new_weight", weight),
	)

	return nil
}

func (s *TrafficControllerService) DisablePolicy(ctx context.Context, policyID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	policy, exists := s.policies[policyID]
	if !exists {
		return common.NewNotFoundError(fmt.Sprintf("policy %s not found", policyID))
	}

	policy.Enabled = false
	policy.Status = "disabled"
	policy.UpdatedAt = time.Now()

	common.Info("traffic policy disabled",
		zap.String("policy_id", policyID),
		zap.String("type", policy.Type),
	)

	return nil
}

func (s *TrafficControllerService) ListPolicies(ctx context.Context, namespace string) ([]*common.TrafficPolicy, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	list := make([]*common.TrafficPolicy, 0)
	for _, p := range s.policies {
		if namespace == "" || p.Namespace == namespace {
			list = append(list, p)
		}
	}
	return list, nil
}
