package differentialprivacy

import (
	"math"
	"math/rand"
	"time"
)

type NoiseType string

const (
	LaplaceNoise NoiseType = "laplace"
	GaussNoise   NoiseType = "gauss"
)

type NoiseGenerator interface {
	Generate(noiseType NoiseType, value, sensitivity, epsilon, delta float64) float64
	GenerateLaplace(value, sensitivity, epsilon float64) float64
	GenerateGaussian(value, sensitivity, epsilon, delta float64) float64
	GenerateAdaptive(value, sensitivity, epsilon, delta, confidence float64) float64
}

type DefaultNoiseGenerator struct {
	rand *rand.Rand
}

func NewDefaultNoiseGenerator() *DefaultNoiseGenerator {
	return &DefaultNoiseGenerator{
		rand: rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

func (g *DefaultNoiseGenerator) Generate(noiseType NoiseType, value, sensitivity, epsilon, delta float64) float64 {
	switch noiseType {
	case LaplaceNoise:
		return g.GenerateLaplace(value, sensitivity, epsilon)
	case GaussNoise:
		return g.GenerateGaussian(value, sensitivity, epsilon, delta)
	default:
		return g.GenerateLaplace(value, sensitivity, epsilon)
	}
}

func (g *DefaultNoiseGenerator) GenerateLaplace(value, sensitivity, epsilon float64) float64 {
	scale := sensitivity / epsilon
	noise := g.laplace(scale)
	return value + noise
}

func (g *DefaultNoiseGenerator) GenerateGaussian(value, sensitivity, epsilon, delta float64) float64 {
	sigma := sensitivity * math.Sqrt(2*math.Log(1.25/delta)) / epsilon
	noise := g.rand.NormFloat64() * sigma
	return value + noise
}

func (g *DefaultNoiseGenerator) GenerateAdaptive(value, sensitivity, epsilon, delta, confidence float64) float64 {
	confidenceFactor := 1.0 / (1 - confidence)
	adjustedEpsilon := epsilon / confidenceFactor

	if delta >= 1e-5 {
		return g.GenerateGaussian(value, sensitivity, adjustedEpsilon, delta)
	}

	return g.GenerateLaplace(value, sensitivity, adjustedEpsilon)
}

func (g *DefaultNoiseGenerator) laplace(scale float64) float64 {
	u := g.rand.Float64() - 0.5
	return -scale * math.Copysign(1, u) * math.Log(1-2*math.Abs(u))
}
