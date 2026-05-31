package tracing

import (
	"fmt"
	"math/rand"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"session189/internal/domain"
	"session189/internal/infrastructure/database"
	"session189/internal/infrastructure/logger"
)

type Sampler struct {
	policies []*domain.SamplingPolicy
}

func NewSampler() *Sampler {
	return &Sampler{
		policies: make([]*domain.SamplingPolicy{},
	}
}

func (s *Sampler) LoadPolicies() error {
	var policies []domain.SamplingPolicy
	if err := database.DB.Where("enabled = ?", true).Order("priority DESC").Find(&policies).Error; err != nil {
		return fmt.Errorf("load sampling policies failed: %w", err)
	}

	s.policies = make([]*domain.SamplingPolicy, len(policies))
	for i := range policies {
		s.policies[i] = &policies[i]
	}

	logger.Info("Sampling policies loaded", zap.Int("count", len(s.policies)))
	return nil
}

func (s *Sampler) ShouldSample(span *domain.TraceSpan) (bool, error) {
	if len(s.policies) == 0 {
		return true, nil
	}

	for _, policy := range s.policies {
		match, err := s.matchPolicy(policy, span)
		if err != nil {
			continue
		}

		if match {
			return s.applySampling(policy.SampleRate), nil
		}
	}

	return true, nil
}

func (s *Sampler) matchPolicy(policy *domain.SamplingPolicy, span *domain.TraceSpan) (bool, error) {
	switch policy.RuleType {
	case "service":
		serviceName, ok := policy.Rules["service_name"].(string)
		if !ok {
			return false, fmt.Errorf("invalid service rule")
		}
		return span.ServiceName == serviceName, nil

	case "operation":
		operationName, ok := policy.Rules["operation_name"].(string)
		if !ok {
			return false, fmt.Errorf("invalid operation rule")
		}
		return span.Name == operationName, nil

	case "error":
		hasError, ok := policy.Rules["has_error"].(bool)
		if !ok {
			return false, fmt.Errorf("invalid error rule")
		}
		return span.Status == domain.SpanStatusError == hasError, nil

	case "duration":
		minDuration, ok := policy.Rules["min_duration_ns"].(float64)
		if !ok {
			return false, fmt.Errorf("invalid duration rule")
		}
		return span.DurationNano > int64(minDuration), nil

	case "attribute":
		attrKey, ok1 := policy.Rules["attribute_key"].(string)
		attrValue, ok2 := policy.Rules["attribute_value"].(string)
		if !ok1 || !ok2 {
			return false, fmt.Errorf("invalid attribute rule")
		}
		spanAttr, exists := span.Attributes[attrKey]
		return exists && fmt.Sprintf("%v", spanAttr) == attrValue, nil

	case "always":
		return true, nil

	default:
		return false, fmt.Errorf("unknown rule type: %s", policy.RuleType)
	}
}

func (s *Sampler) applySampling(rate float64) bool {
	if rate >= 1.0 {
		return true
	}
	if rate <= 0 {
		return false
	}
	return rand.Float64() < rate
}

func (s *Sampler) CreatePolicy(ctx context.Context, policy *domain.SamplingPolicy) (*domain.SamplingPolicy, error) {
	policy.PolicyID = uuid.New().String()
	policy.CreatedAt = time.Now()
	policy.UpdatedAt = time.Now()

	if err := database.DB.WithContext(ctx).Create(policy).Error; err != nil {
		return nil, fmt.Errorf("create sampling policy failed: %w", err)
	}

	if policy.Enabled {
		_ = s.LoadPolicies()
	}

	logger.Info("Sampling policy created",
		zap.String("policy_id", policy.PolicyID),
		zap.String("name", policy.Name))

	return policy, nil
}

func (s *Sampler) UpdatePolicy(ctx context.Context, policyID string, updates map[string]interface{}) (*domain.SamplingPolicy, error) {
	var policy domain.SamplingPolicy
	if err := database.DB.WithContext(ctx).Where("policy_id = ?", policyID).First(&policy).Error; err != nil {
		return nil, fmt.Errorf("policy not found: %w", err)
	}

	updates["updated_at"] = time.Now()
	if err := database.DB.WithContext(ctx).Model(&policy).Updates(updates).Error; err != nil {
		return nil, fmt.Errorf("update policy failed: %w", err)
	}

	if err := database.DB.WithContext(ctx).Where("policy_id = ?", policyID).First(&policy).Error; err != nil {
		return nil, fmt.Errorf("reload policy failed: %w", err)
	}

	_ = s.LoadPolicies()

	return &policy, nil
}

func (s *Sampler) DeletePolicy(ctx context.Context, policyID string) error {
	if err := database.DB.WithContext(ctx).Where("policy_id = ?", policyID).Delete(&domain.SamplingPolicy{}).Error; err != nil {
		return fmt.Errorf("delete policy failed: %w", err)
	}

	_ = s.LoadPolicies()
	logger.Info("Sampling policy deleted", zap.String("policy_id", policyID))
	return nil
}

func (s *Sampler) GetPolicy(ctx context.Context, policyID string) (*domain.SamplingPolicy, error) {
	var policy domain.SamplingPolicy
	if err := database.DB.WithContext(ctx).Where("policy_id = ?", policyID).First(&policy).Error; err != nil {
		return nil, fmt.Errorf("get policy failed: %w", err)
	}
	return &policy, nil
}

func (s *Sampler) ListPolicies(ctx context.Context, offset, limit int) ([]domain.SamplingPolicy, int64, error) {
	var policies []domain.SamplingPolicy
	var total int64

	if err := database.DB.WithContext(ctx).Model(&domain.SamplingPolicy{}).Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count policies failed: %w", err)
	}

	if err := database.DB.WithContext(ctx).Order("priority DESC, created_at DESC").Offset(offset).Limit(limit).Find(&policies).Error; err != nil {
		return nil, 0, fmt.Errorf("list policies failed: %w", err)
	}

	return policies, total, nil
}

func init() {
	rand.Seed(time.Now().UnixNano())
}
