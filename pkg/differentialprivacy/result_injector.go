package differentialprivacy

import (
	"math"

	"github.com/solocoder/session136/pkg/common/interfaces"
)

type ResultInjector interface {
	Inject(result *interfaces.QueryResult, noiseGen NoiseGenerator) (*interfaces.QueryResult, error)
}

type DefaultResultInjector struct {
	defaultSensitivity float64
}

func NewDefaultResultInjector() *DefaultResultInjector {
	return &DefaultResultInjector{
		defaultSensitivity: 1.0,
	}
}

func (inj *DefaultResultInjector) Inject(result *interfaces.QueryResult, noiseGen NoiseGenerator) (*interfaces.QueryResult, error) {
	epsilon := result.Epsilon
	delta := result.Delta

	if epsilon <= 0 {
		epsilon = 1.0
	}
	if delta <= 0 {
		delta = 1e-5
	}

	noiseType := NoiseType(result.NoiseType)
	if noiseType == "" {
		noiseType = LaplaceNoise
	}

	for i, row := range result.Data {
		for key, value := range row {
			row[key] = inj.injectValue(value, noiseType, epsilon, delta, noiseGen)
		}
		result.Data[i] = row
	}

	return result, nil
}

func (inj *DefaultResultInjector) injectValue(value interface{}, noiseType NoiseType, epsilon, delta float64, noiseGen NoiseGenerator) interface{} {
	switch v := value.(type) {
	case float64:
		return noiseGen.Generate(noiseType, v, inj.defaultSensitivity, epsilon, delta)
	case int:
		noisyValue := noiseGen.Generate(noiseType, float64(v), inj.defaultSensitivity, epsilon, delta)
		return math.Round(noisyValue)
	case int64:
		noisyValue := noiseGen.Generate(noiseType, float64(v), inj.defaultSensitivity, epsilon, delta)
		return int64(math.Round(noisyValue))
	default:
		return value
	}
}
