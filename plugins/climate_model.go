//go:build plugin
// +build plugin

package main

import (
	"fmt"
	"math"
)

const (
	climatePluginName    = "climate_model"
	climatePluginVersion = "1.0.0"
	climateAPIVersion    = "1.0.0"
)

type ClimateModelPlugin struct {
	baseTemperature float64
	co2Sensitivity  float64
	initialized     bool
}

func (p *ClimateModelPlugin) Name() string {
	return climatePluginName
}

func (p *ClimateModelPlugin) Version() string {
	return climatePluginVersion
}

func (p *ClimateModelPlugin) APIVersion() string {
	return climateAPIVersion
}

func (p *ClimateModelPlugin) Evaluate(x []float64) float64 {
	if len(x) < 3 {
		return math.Inf(1)
	}

	co2Emission := x[0]
	solarRadiation := x[1]
	albedo := x[2]

	deltaT := p.co2Sensitivity * math.Log(co2Emission/280.0)
	deltaT += 0.1 * (solarRadiation - 1361.0) / 1361.0
	deltaT += 0.5 * (0.3 - albedo)

	temperature := p.baseTemperature + deltaT

	precipitation := 1000.0 * math.Exp(-0.1*math.Abs(temperature-15.0))
	seaLevel := 0.5 * (temperature - 15.0)

	score := math.Abs(temperature-15.0) + math.Abs(precipitation-1000.0)/100.0 + math.Abs(seaLevel)

	return score
}

func (p *ClimateModelPlugin) Gradient(x []float64, grad []float64) {
	if len(x) < 3 {
		for i := range grad {
			grad[i] = 0
		}
		return
	}

	co2Emission := x[0]
	solarRadiation := x[1]
	albedo := x[2]

	eps := 1e-6

	for i := range x {
		xPlus := make([]float64, len(x))
		xMinus := make([]float64, len(x))
		copy(xPlus, x)
		copy(xMinus, x)

		xPlus[i] += eps
		xMinus[i] -= eps

		fPlus := p.Evaluate(xPlus)
		fMinus := p.Evaluate(xMinus)

		grad[i] = (fPlus - fMinus) / (2 * eps)
	}

	_ = co2Emission
	_ = solarRadiation
	_ = albedo
}

func (p *ClimateModelPlugin) Validate() error {
	if p.co2Sensitivity <= 0 {
		return fmt.Errorf("CO2 sensitivity must be positive, got %f", p.co2Sensitivity)
	}
	if p.baseTemperature < -50 || p.baseTemperature > 50 {
		return fmt.Errorf("base temperature out of reasonable range: %f", p.baseTemperature)
	}
	p.initialized = true
	return nil
}

func (p *ClimateModelPlugin) Close() error {
	p.initialized = false
	return nil
}

var ObjectivePlugin = &ClimateModelPlugin{
	baseTemperature: 15.0,
	co2Sensitivity:  3.0,
}
