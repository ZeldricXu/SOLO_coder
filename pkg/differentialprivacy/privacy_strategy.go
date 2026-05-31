package differentialprivacy

import (
	"context"

	"github.com/solocoder/session136/pkg/common/interfaces"
)

type PrivacyStrategy interface {
	Name() string
	Description() string
	Process(ctx context.Context, result *interfaces.QueryResult, noiseGen NoiseGenerator) (*interfaces.QueryResult, error)
	GetBudgetEstimate(query *interfaces.QueryResult) (epsilon, delta float64)
	ValidateConfig() error
}

type StrictPrivacyStrategy struct {
	noiseType NoiseType
}

func NewStrictPrivacyStrategy() *StrictPrivacyStrategy {
	return &StrictPrivacyStrategy{
		noiseType: LaplaceNoise,
	}
}

func (s *StrictPrivacyStrategy) Name() string {
	return "strict"
}

func (s *StrictPrivacyStrategy) Description() string {
	return "严格隐私策略：使用较小的epsilon，提供强隐私保护，但数据可用性较低"
}

func (s *StrictPrivacyStrategy) Process(ctx context.Context, result *interfaces.QueryResult, noiseGen NoiseGenerator) (*interfaces.QueryResult, error) {
	epsilon := result.Epsilon
	delta := result.Delta

	if epsilon <= 0 {
		epsilon = 0.5
	}
	if delta <= 0 {
		delta = 1e-6
	}

	if epsilon > 1.0 {
		epsilon = 1.0
	}

	for i, row := range result.Data {
		for key, value := range row {
			row[key] = s.applyNoise(value, epsilon, delta, noiseGen)
		}
		result.Data[i] = row
	}

	result.Epsilon = epsilon
	result.Delta = delta
	result.NoiseType = string(s.noiseType)

	return result, nil
}

func (s *StrictPrivacyStrategy) applyNoise(value interface{}, epsilon, delta float64, noiseGen NoiseGenerator) interface{} {
	switch v := value.(type) {
	case float64:
		return noiseGen.Generate(s.noiseType, v, 1.0, epsilon, delta)
	case int:
		noisy := noiseGen.Generate(s.noiseType, float64(v), 1.0, epsilon, delta)
		return int(noisy)
	case int64:
		noisy := noiseGen.Generate(s.noiseType, float64(v), 1.0, epsilon, delta)
		return int64(noisy)
	default:
		return value
	}
}

func (s *StrictPrivacyStrategy) GetBudgetEstimate(query *interfaces.QueryResult) (epsilon, delta float64) {
	epsilon = query.Epsilon
	delta = query.Delta

	if epsilon <= 0 {
		epsilon = 0.5
	}
	if delta <= 0 {
		delta = 1e-6
	}
	if epsilon > 1.0 {
		epsilon = 1.0
	}

	return epsilon, delta
}

func (s *StrictPrivacyStrategy) ValidateConfig() error {
	return nil
}

type BalancedPrivacyStrategy struct{}

func NewBalancedPrivacyStrategy() *BalancedPrivacyStrategy {
	return &BalancedPrivacyStrategy{}
}

func (s *BalancedPrivacyStrategy) Name() string {
	return "balanced"
}

func (s *BalancedPrivacyStrategy) Description() string {
	return "平衡策略：在隐私保护和数据可用性之间取得平衡"
}

func (s *BalancedPrivacyStrategy) Process(ctx context.Context, result *interfaces.QueryResult, noiseGen NoiseGenerator) (*interfaces.QueryResult, error) {
	epsilon := result.Epsilon
	delta := result.Delta

	if epsilon <= 0 {
		epsilon = 1.0
	}
	if delta <= 0 {
		delta = 1e-5
	}

	if epsilon > 2.0 {
		epsilon = 2.0
	}

	noiseType := LaplaceNoise
	if epsilon >= 1.0 && delta >= 1e-5 {
		noiseType = GaussNoise
	}

	for i, row := range result.Data {
		for key, value := range row {
			row[key] = s.applyNoise(value, noiseType, epsilon, delta, noiseGen)
		}
		result.Data[i] = row
	}

	result.Epsilon = epsilon
	result.Delta = delta
	result.NoiseType = string(noiseType)

	return result, nil
}

func (s *BalancedPrivacyStrategy) applyNoise(value interface{}, noiseType NoiseType, epsilon, delta float64, noiseGen NoiseGenerator) interface{} {
	switch v := value.(type) {
	case float64:
		return noiseGen.Generate(noiseType, v, 1.0, epsilon, delta)
	case int:
		noisy := noiseGen.Generate(noiseType, float64(v), 1.0, epsilon, delta)
		return int(noisy)
	case int64:
		noisy := noiseGen.Generate(noiseType, float64(v), 1.0, epsilon, delta)
		return int64(noisy)
	default:
		return value
	}
}

func (s *BalancedPrivacyStrategy) GetBudgetEstimate(query *interfaces.QueryResult) (epsilon, delta float64) {
	epsilon = query.Epsilon
	delta = query.Delta

	if epsilon <= 0 {
		epsilon = 1.0
	}
	if delta <= 0 {
		delta = 1e-5
	}
	if epsilon > 2.0 {
		epsilon = 2.0
	}

	return epsilon, delta
}

func (s *BalancedPrivacyStrategy) ValidateConfig() error {
	return nil
}

type RelaxedPrivacyStrategy struct{}

func NewRelaxedPrivacyStrategy() *RelaxedPrivacyStrategy {
	return &RelaxedPrivacyStrategy{}
}

func (s *RelaxedPrivacyStrategy) Name() string {
	return "relaxed"
}

func (s *RelaxedPrivacyStrategy) Description() string {
	return "宽松策略：使用较大的epsilon，数据可用性高，但隐私保护较弱"
}

func (s *RelaxedPrivacyStrategy) Process(ctx context.Context, result *interfaces.QueryResult, noiseGen NoiseGenerator) (*interfaces.QueryResult, error) {
	epsilon := result.Epsilon
	delta := result.Delta

	if epsilon <= 0 {
		epsilon = 5.0
	}
	if delta <= 0 {
		delta = 1e-4
	}

	if epsilon > 10.0 {
		epsilon = 10.0
	}

	noiseType := GaussNoise

	for i, row := range result.Data {
		for key, value := range row {
			row[key] = s.applyNoise(value, noiseType, epsilon, delta, noiseGen)
		}
		result.Data[i] = row
	}

	result.Epsilon = epsilon
	result.Delta = delta
	result.NoiseType = string(noiseType)

	return result, nil
}

func (s *RelaxedPrivacyStrategy) applyNoise(value interface{}, noiseType NoiseType, epsilon, delta float64, noiseGen NoiseGenerator) interface{} {
	switch v := value.(type) {
	case float64:
		return noiseGen.Generate(noiseType, v, 1.0, epsilon, delta)
	case int:
		noisy := noiseGen.Generate(noiseType, float64(v), 1.0, epsilon, delta)
		return int(noisy)
	case int64:
		noisy := noiseGen.Generate(noiseType, float64(v), 1.0, epsilon, delta)
		return int64(noisy)
	default:
		return value
	}
}

func (s *RelaxedPrivacyStrategy) GetBudgetEstimate(query *interfaces.QueryResult) (epsilon, delta float64) {
	epsilon = query.Epsilon
	delta = query.Delta

	if epsilon <= 0 {
		epsilon = 5.0
	}
	if delta <= 0 {
		delta = 1e-4
	}
	if epsilon > 10.0 {
		epsilon = 10.0
	}

	return epsilon, delta
}

func (s *RelaxedPrivacyStrategy) ValidateConfig() error {
	return nil
}

type AdaptivePrivacyStrategy struct {
	confidenceThreshold float64
}

func NewAdaptivePrivacyStrategy() *AdaptivePrivacyStrategy {
	return &AdaptivePrivacyStrategy{
		confidenceThreshold: 0.95,
	}
}

func (s *AdaptivePrivacyStrategy) Name() string {
	return "adaptive"
}

func (s *AdaptivePrivacyStrategy) Description() string {
	return "自适应策略：根据数据敏感度和查询类型动态调整隐私参数"
}

func (s *AdaptivePrivacyStrategy) Process(ctx context.Context, result *interfaces.QueryResult, noiseGen NoiseGenerator) (*interfaces.QueryResult, error) {
	dataSize := len(result.Data)

	var baseEpsilon float64
	switch {
	case dataSize < 10:
		baseEpsilon = 0.5
	case dataSize < 100:
		baseEpsilon = 1.0
	case dataSize < 1000:
		baseEpsilon = 2.0
	default:
		baseEpsilon = 5.0
	}

	userEpsilon := result.Epsilon
	if userEpsilon > 0 && userEpsilon < baseEpsilon {
		baseEpsilon = userEpsilon
	}

	delta := result.Delta
	if delta <= 0 {
		delta = 1e-5
	}

	noiseType := LaplaceNoise
	if baseEpsilon >= 1.0 {
		noiseType = GaussNoise
	}

	for i, row := range result.Data {
		for key, value := range row {
			row[key] = s.applyNoise(value, noiseType, baseEpsilon, delta, noiseGen)
		}
		result.Data[i] = row
	}

	result.Epsilon = baseEpsilon
	result.Delta = delta
	result.NoiseType = string(noiseType)

	return result, nil
}

func (s *AdaptivePrivacyStrategy) applyNoise(value interface{}, noiseType NoiseType, epsilon, delta float64, noiseGen NoiseGenerator) interface{} {
	switch v := value.(type) {
	case float64:
		return noiseGen.GenerateAdaptive(v, 1.0, epsilon, delta, s.confidenceThreshold)
	case int:
		noisy := noiseGen.GenerateAdaptive(float64(v), 1.0, epsilon, delta, s.confidenceThreshold)
		return int(noisy)
	case int64:
		noisy := noiseGen.GenerateAdaptive(float64(v), 1.0, epsilon, delta, s.confidenceThreshold)
		return int64(noisy)
	default:
		return value
	}
}

func (s *AdaptivePrivacyStrategy) GetBudgetEstimate(query *interfaces.QueryResult) (epsilon, delta float64) {
	dataSize := len(query.Data)

	switch {
	case dataSize < 10:
		epsilon = 0.5
	case dataSize < 100:
		epsilon = 1.0
	case dataSize < 1000:
		epsilon = 2.0
	default:
		epsilon = 5.0
	}

	if query.Epsilon > 0 && query.Epsilon < epsilon {
		epsilon = query.Epsilon
	}

	delta = query.Delta
	if delta <= 0 {
		delta = 1e-5
	}

	return epsilon, delta
}

func (s *AdaptivePrivacyStrategy) ValidateConfig() error {
	if s.confidenceThreshold <= 0 || s.confidenceThreshold >= 1 {
		return nil
	}
	return nil
}
