//go:build plugin
// +build plugin

package main

import (
	"fmt"
	"math"
)

const (
	materialPluginName    = "material_simulation"
	materialPluginVersion = "1.0.0"
	materialAPIVersion    = "1.0.0"
)

type MaterialSimulationPlugin struct {
	youngsModulus float64
	poissonRatio  float64
	yieldStrength float64
	initialized   bool
}

func (p *MaterialSimulationPlugin) Name() string {
	return materialPluginName
}

func (p *MaterialSimulationPlugin) Version() string {
	return materialPluginVersion
}

func (p *MaterialSimulationPlugin) APIVersion() string {
	return materialAPIVersion
}

func (p *MaterialSimulationPlugin) Evaluate(x []float64) float64 {
	if len(x) < 4 {
		return math.Inf(1)
	}

	length := x[0]
	width := x[1]
	height := x[2]
	load := x[3]

	area := width * height
	momentOfInertia := (width * math.Pow(height, 3)) / 12.0

	stress := load / area
	if stress > p.yieldStrength {
		return math.Inf(1)
	}

	deflection := (load * math.Pow(length, 3)) / (3 * p.youngsModulus * momentOfInertia)

	weight := length * width * height * 7850.0

	cost := weight * 2.5 + deflection*1000.0

	return cost
}

func (p *MaterialSimulationPlugin) Gradient(x []float64, grad []float64) {
	if len(x) < 4 {
		for i := range grad {
			grad[i] = 0
		}
		return
	}

	length := x[0]
	width := x[1]
	height := x[2]
	load := x[3]

	h := 1e-6

	for i := range x {
		xPlus := make([]float64, len(x))
		xMinus := make([]float64, len(x))
		copy(xPlus, x)
		copy(xMinus, x)

		xPlus[i] += h
		xMinus[i] -= h

		fPlus := p.Evaluate(xPlus)
		fMinus := p.Evaluate(xMinus)

		grad[i] = (fPlus - fMinus) / (2 * h)
	}

	_ = length
	_ = width
	_ = height
	_ = load
}

func (p *MaterialSimulationPlugin) Validate() error {
	if p.youngsModulus <= 0 {
		return fmt.Errorf("Young's modulus must be positive, got %f", p.youngsModulus)
	}
	if p.poissonRatio <= 0 || p.poissonRatio >= 0.5 {
		return fmt.Errorf("Poisson's ratio out of valid range (0, 0.5): %f", p.poissonRatio)
	}
	if p.yieldStrength <= 0 {
		return fmt.Errorf("yield strength must be positive, got %f", p.yieldStrength)
	}
	p.initialized = true
	return nil
}

func (p *MaterialSimulationPlugin) Close() error {
	p.initialized = false
	return nil
}

var ObjectivePlugin = &MaterialSimulationPlugin{
	youngsModulus: 200e9,
	poissonRatio:  0.3,
	yieldStrength: 250e6,
}
