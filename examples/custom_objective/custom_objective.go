package main

import (
	"fmt"
	"log"
	"math"

	"github.com/df1-96/experiment/internal/compute"
	"github.com/df1-96/experiment/internal/compute/objective"
)

type CustomObjective struct {
	name       string
	center     []float64
	amplitude  float64
	frequency  float64
}

func NewCustomObjective(dimensions int) *CustomObjective {
	center := make([]float64, dimensions)
	for i := range center {
		center[i] = float64(i+1) * 0.5
	}

	return &CustomObjective{
		name:      "CustomWaveFunction",
		center:    center,
		amplitude: 10.0,
		frequency: 2.0,
	}
}

func (c *CustomObjective) Name() string {
	return c.name
}

func (c *CustomObjective) Evaluate(x []float64) float64 {
	dim := len(x)
	if dim != len(c.center) {
		return math.Inf(1)
	}

	var distanceSum float64
	var waveSum float64

	for i, xi := range x {
		diff := xi - c.center[i]
		distanceSum += diff * diff
		waveSum += math.Sin(c.frequency * diff)
	}

	distance := math.Sqrt(distanceSum)
	waveComponent := c.amplitude * math.Sin(waveSum/float64(dim))

	result := distance + waveComponent

	return result
}

func (c *CustomObjective) Gradient(x []float64, grad []float64) {
	dim := len(x)
	if dim != len(c.center) {
		for i := range grad {
			grad[i] = 0
		}
		return
	}

	var distanceSum float64
	var waveSum float64
	var cosSum float64

	for i, xi := range x {
		diff := xi - c.center[i]
		distanceSum += diff * diff
		waveSum += math.Sin(c.frequency * diff)
		cosSum += math.Cos(c.frequency * diff)
	}

	distance := math.Sqrt(distanceSum)
	waveDeriv := c.amplitude * math.Cos(waveSum/float64(dim)) / float64(dim)

	for i, xi := range x {
		diff := xi - c.center[i]

		distGrad := 0.0
		if distance > 1e-10 {
			distGrad = diff / distance
		}

		waveGrad := waveDeriv * c.frequency * math.Cos(c.frequency * diff)

		grad[i] = distGrad + waveGrad
	}

	_ = cosSum
}

func (c *CustomObjective) Minimum() ([]float64, float64) {
	return c.center, 0.0
}

func (c *CustomObjective) Dimensions() int {
	return len(c.center)
}

func (c *CustomObjective) ToObjective() (compute.ObjectiveFunction, compute.GradientFunction) {
	return c.Evaluate, c.Gradient
}

func main() {
	fmt.Println("=== Custom Objective Function Example ===\n")

	dimensions := 4
	fmt.Printf("Creating custom objective with %d dimensions\n\n", dimensions)

	customObj := NewCustomObjective(dimensions)

	fmt.Printf("Function Name: %s\n", customObj.Name())
	fmt.Printf("Theoretical Minimum at: %v\n\n", customObj.center)

	fmt.Println("=== Testing Objective Function ===")
	testPoints := [][]float64{
		{0.0, 0.0, 0.0, 0.0},
		{0.5, 1.0, 1.5, 2.0},
		{1.0, 2.0, 3.0, 4.0},
		{0.5, 1.0, 1.5, 2.0},
		{-1.0, -1.0, -1.0, -1.0},
	}

	for i, x := range testPoints {
		value := customObj.Evaluate(x)
		fmt.Printf("Test Point %d: x=%v, f(x)=%.6f\n", i+1, formatFloatSlice(x), value)
	}

	fmt.Println("\n=== Testing Gradient Computation ===")
	gradTest := []float64{0.5, 1.0, 1.5, 2.0}
	grad := make([]float64, dimensions)
	customObj.Gradient(gradTest, grad)
	fmt.Printf("At x=%v:\n", formatFloatSlice(gradTest))
	fmt.Printf("  Gradient: %v\n", formatFloatSlice(grad))

	numericalGrad := computeNumericalGradient(customObj.Evaluate, gradTest)
	fmt.Printf("  Numerical Gradient: %v\n", formatFloatSlice(numericalGrad))

	gradError := 0.0
	for i := range grad {
		gradError += math.Abs(grad[i] - numericalGrad[i])
	}
	fmt.Printf("  Gradient Error: %.6f\n\n", gradError)

	fmt.Println("=== Comparing with Built-in Functions ===")
	builtInFunctions := []struct {
		name string
		fn   objective.TestFunction
	}{
		{"Rosenbrock", objective.NewRosenbrock(dimensions)},
		{"Sphere", objective.NewSphere(dimensions)},
		{"Ackley", objective.NewAckley(dimensions)},
		{"Rastrigin", objective.NewRastrigin(dimensions)},
	}

	comparePoint := []float64{0.5, 1.0, 1.5, 2.0}
	fmt.Printf("Comparison point: %v\n\n", formatFloatSlice(comparePoint))

	for _, bf := range builtInFunctions {
		value := bf.fn.Evaluate(comparePoint)
		minX, minVal := bf.fn.Minimum()
		fmt.Printf("%-12s: f(x)=%.6f, min at %v, min_val=%.6f\n",
			bf.name, value, formatFloatSlice(minX), minVal)
	}

	customVal := customObj.Evaluate(comparePoint)
	minX, minVal := customObj.Minimum()
	fmt.Printf("%-12s: f(x)=%.6f, min at %v, min_val=%.6f\n\n",
		"Custom", customVal, formatFloatSlice(minX), minVal)

	fmt.Println("=== Optimization Test ===")
	config := compute.OptimizationConfig{
		MaxIterations: 500,
		Tolerance:     1e-8,
		GradTolerance: 1e-6,
		LearningRate:  0.01,
		OptimizerType: compute.Adam,
		RecordHistory: true,
	}

	objFunc, gradFunc := customObj.ToObjective()
	engine := compute.NewEngine(dimensions, objFunc, gradFunc)

	initialX := make([]float64, dimensions)
	for i := range initialX {
		initialX[i] = float64(i+1) * 2.0
	}

	fmt.Printf("Initial point: %v\n", formatFloatSlice(initialX))
	fmt.Printf("Initial value: %.6f\n", objFunc(initialX))

	result, err := engine.Minimize(initialX, config)
	if err != nil {
		log.Fatalf("Optimization failed: %v", err)
	}

	fmt.Printf("\nOptimization Result:\n")
	fmt.Printf("  Iterations: %d\n", result.Iterations)
	fmt.Printf("  Final x: %v\n", formatFloatSlice(result.X))
	fmt.Printf("  Final value: %.6f\n", result.F)
	fmt.Printf("  Converged: %v\n", result.Converged)
	fmt.Printf("  Reason: %s\n", result.Reason)
	fmt.Printf("  Best value: %.6f\n", result.BestF)

	minDist := 0.0
	for i := range result.X {
		minDist += math.Pow(result.X[i]-customObj.center[i], 2)
	}
	minDist = math.Sqrt(minDist)
	fmt.Printf("  Distance from theoretical minimum: %.6f\n", minDist)

	fmt.Println("\n=== Using with Compute Engine ===")
	engine2 := compute.NewEngine(dimensions, objFunc, gradFunc)
	_ = engine2

	testX := []float64{1.0, 1.0, 1.0, 1.0}
	objVal := objFunc(testX)
	testGrad := make([]float64, dimensions)
	gradFunc(testX, testGrad)

	fmt.Printf("Point: %v\n", formatFloatSlice(testX))
	fmt.Printf("Objective: %.6f\n", objVal)
	fmt.Printf("Gradient: %v\n", formatFloatSlice(testGrad))

	fmt.Println("\n=== Example Complete ===")
}

func formatFloatSlice(s []float64) string {
	result := "["
	for i, v := range s {
		if i > 0 {
			result += ", "
		}
		result += fmt.Sprintf("%.4f", v)
	}
	result += "]"
	return result
}

func computeNumericalGradient(f compute.ObjectiveFunction, x []float64) []float64 {
	h := 1e-6
	grad := make([]float64, len(x))

	for i := range x {
		xPlus := make([]float64, len(x))
		xMinus := make([]float64, len(x))
		copy(xPlus, x)
		copy(xMinus, x)

		xPlus[i] += h
		xMinus[i] -= h

		fPlus := f(xPlus)
		fMinus := f(xMinus)

		grad[i] = (fPlus - fMinus) / (2 * h)
	}

	return grad
}
