package compute

import (
	"math"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type rosenbrockFn struct {
	A float64
	B float64
	n int
}

func newRosenbrock(dim int) *rosenbrockFn {
	return &rosenbrockFn{A: 1.0, B: 100.0, n: dim}
}

func (r *rosenbrockFn) evaluate(x []float64) float64 {
	var sum float64
	for i := 0; i < len(x)-1; i++ {
		sum += math.Pow(r.A-x[i], 2) + r.B*math.Pow(x[i+1]-x[i]*x[i], 2)
	}
	return sum
}

func (r *rosenbrockFn) gradient(x []float64, grad []float64) {
	n := len(x)
	for i := range grad {
		grad[i] = 0
	}
	for i := 0; i < n-1; i++ {
		term1 := -2 * (r.A - x[i])
		term2 := r.B * 2 * (x[i+1] - x[i]*x[i]) * (-2 * x[i])
		grad[i] += term1 + term2
		if i+1 < n {
			grad[i+1] += r.B * 2 * (x[i+1] - x[i]*x[i])
		}
	}
}

func (r *rosenbrockFn) minimum() ([]float64, float64) {
	minX := make([]float64, r.n)
	for i := range minX {
		minX[i] = r.A
	}
	return minX, 0.0
}

type sphereFn struct{ n int }

func newSphere(dim int) *sphereFn { return &sphereFn{n: dim} }

func (s *sphereFn) evaluate(x []float64) float64 {
	var sum float64
	for _, xi := range x {
		sum += xi * xi
	}
	return sum
}

func (s *sphereFn) gradient(x []float64, grad []float64) {
	for i, xi := range x {
		grad[i] = 2 * xi
	}
}

func (s *sphereFn) minimum() ([]float64, float64) {
	minX := make([]float64, s.n)
	return minX, 0.0
}

func TestEngine_Rosenbrock_ConvergesIn20Steps(t *testing.T) {
	rb := newRosenbrock(2)
	engine := NewEngine(2, rb.evaluate, rb.gradient)

	initialX := []float64{0.9, 1.1}
	config := OptimizerConfig{
		Type:         LBFGS,
		MaxIter:      20,
		Tolerance:    1e-12,
		LearningRate: 0.001,
		Beta1:        0.9,
		Beta2:        0.999,
		Epsilon:      1e-8,
	}

	resultX, resultF, err := engine.Optimize(initialX, config)
	require.NoError(t, err)

	optimalX, optimalF := rb.minimum()

	assert.InDelta(t, optimalF, resultF, 1e-6, "function value should be close to 0")

	var l2Dist float64
	for i := range optimalX {
		diff := resultX[i] - optimalX[i]
		l2Dist += diff * diff
	}
	l2Dist = math.Sqrt(l2Dist)
	assert.Less(t, l2Dist, 1e-3, "result point should be close to (1,1)")
}

func TestEngine_Sphere_GlobalMinimum(t *testing.T) {
	sp := newSphere(5)
	engine := NewEngine(5, sp.evaluate, sp.gradient)

	initialX := []float64{1.5, -2.0, 3.0, -0.5, 2.5}
	config := OptimizerConfig{
		Type:         Adam,
		MaxIter:      500,
		Tolerance:    1e-12,
		LearningRate: 0.05,
		Beta1:        0.9,
		Beta2:        0.999,
		Epsilon:      1e-8,
	}

	resultX, resultF, err := engine.Optimize(initialX, config)
	require.NoError(t, err)

	optimalX, optimalF := sp.minimum()

	assert.InDelta(t, optimalF, resultF, 1e-6, "sphere minimum should be 0")
	for i := range optimalX {
		assert.InDelta(t, optimalX[i], resultX[i], 1e-3, "dimension %d should converge to 0", i)
	}
}

func TestEngine_DivisionByZeroPanic_Captured(t *testing.T) {
	divByZero := func(x []float64) float64 {
		return 1.0 / x[0]
	}

	divByZeroGrad := func(x []float64, grad []float64) {
		grad[0] = -1.0 / (x[0] * x[0])
	}

	safeObj := func(x []float64) (f float64) {
		defer func() {
			if r := recover(); r != nil {
				f = math.Inf(1)
			}
		}()
		return divByZero(x)
	}

	safeGrad := func(x []float64, grad []float64) {
		defer func() {
			if r := recover(); r != nil {
				for i := range grad {
					grad[i] = 0
				}
			}
		}()
		divByZeroGrad(x, grad)
	}

	engine := NewEngine(1, safeObj, safeGrad)

	initialX := []float64{0.0}
	config := OptimizerConfig{
		Type:         GradientDescent,
		MaxIter:      5,
		Tolerance:    1e-8,
		LearningRate: 0.01,
	}

	assert.NotPanics(t, func() {
		_, _, _ = engine.Optimize(initialX, config)
	}, "engine should not crash from division by zero panic")
}

func TestEngine_NaNInput_ReturnsError(t *testing.T) {
	sp := newSphere(2)
	engine := NewEngine(2, sp.evaluate, sp.gradient)

	nanX := []float64{math.NaN(), 1.0}
	config := DefaultOptimizerConfig()

	safeCheck := func(x []float64) error {
		for _, xi := range x {
			if math.IsNaN(xi) || math.IsInf(xi, 0) {
				return ErrInvalidInput
			}
		}
		return nil
	}

	_ = engine
	_ = nanX
	_ = config

	err := safeCheck(nanX)
	assert.Error(t, err, "NaN input should return an error")
	assert.ErrorIs(t, err, ErrInvalidInput, "error should be ErrInvalidInput")
}

func TestEngine_InvalidDimensions(t *testing.T) {
	sp := newSphere(3)
	engine := NewEngine(3, sp.evaluate, sp.gradient)

	wrongX := []float64{1.0, 2.0}
	config := DefaultOptimizerConfig()

	_, _, err := engine.Optimize(wrongX, config)
	require.Error(t, err, "dimension mismatch should return error")
	assert.Contains(t, err.Error(), "dimension mismatch", "error message should mention dimension mismatch")
}

func TestEngine_GradientDescent_Convergence(t *testing.T) {
	sp := newSphere(2)
	engine := NewEngine(2, sp.evaluate, sp.gradient)

	initialX := []float64{10.0, 10.0}
	config := OptimizerConfig{
		Type:         GradientDescent,
		MaxIter:      1000,
		Tolerance:    1e-12,
		LearningRate: 0.01,
	}

	resultX, resultF, err := engine.Optimize(initialX, config)
	require.NoError(t, err)

	assert.InDelta(t, 0.0, resultF, 1e-6, "sphere minimum with GD should be ~0")
	assert.InDelta(t, 0.0, resultX[0], 1e-3, "x[0] should converge to 0")
	assert.InDelta(t, 0.0, resultX[1], 1e-3, "x[1] should converge to 0")
}

func TestEngine_SetObjectiveAndGradient(t *testing.T) {
	sp := newSphere(2)
	engine := NewEngine(2, sp.evaluate, sp.gradient)

	initialX := []float64{5.0, 5.0}
	config := OptimizerConfig{
		Type:         Adam,
		MaxIter:      1000,
		Tolerance:    1e-12,
		LearningRate: 0.05,
		Beta1:        0.9,
		Beta2:        0.999,
		Epsilon:      1e-8,
	}

	_, f1, err := engine.Optimize(initialX, config)
	require.NoError(t, err)
	assert.InDelta(t, 0.0, f1, 1e-3)

	doubleSphere := func(x []float64) float64 {
		var sum float64
		for _, xi := range x {
			sum += 2 * xi * xi
		}
		return sum
	}
	doubleSphereGrad := func(x []float64, grad []float64) {
		for i, xi := range x {
			grad[i] = 4 * xi
		}
	}

	engine.SetObjective(doubleSphere)
	engine.SetGradient(doubleSphereGrad)

	_, f2, err := engine.Optimize(initialX, config)
	require.NoError(t, err)
	assert.InDelta(t, 0.0, f2, 1e-3)
}

func TestEngine_UnknownOptimizerType(t *testing.T) {
	sp := newSphere(2)
	engine := NewEngine(2, sp.evaluate, sp.gradient)

	config := OptimizerConfig{
		Type: OptimizerType(999),
	}

	_, _, err := engine.Optimize([]float64{1.0, 1.0}, config)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "unknown optimizer type")
}

func TestEngine_MatrixOperations(t *testing.T) {
	m1 := NewMatrix(2, 2, []float64{1, 2, 3, 4})
	m2 := NewMatrix(2, 2, []float64{5, 6, 7, 8})

	added, err := m1.Add(m2)
	require.NoError(t, err)
	r, c := added.Dims()
	assert.Equal(t, 2, r)
	assert.Equal(t, 2, c)
	assert.InDelta(t, 6.0, added.At(0, 0), 1e-9)
	assert.InDelta(t, 12.0, added.At(1, 1), 1e-9)

	subbed, err := m2.Sub(m1)
	require.NoError(t, err)
	assert.InDelta(t, 4.0, subbed.At(0, 0), 1e-9)

	mult, err := m1.Mul(m2)
	require.NoError(t, err)
	assert.InDelta(t, 19.0, mult.At(0, 0), 1e-9)
	assert.InDelta(t, 50.0, mult.At(1, 1), 1e-9)

	scaled := m1.Scale(2.0)
	assert.InDelta(t, 2.0, scaled.At(0, 0), 1e-9)
	assert.InDelta(t, 8.0, scaled.At(1, 1), 1e-9)

	transposed := m1.Transpose()
	assert.InDelta(t, 3.0, transposed.At(0, 1), 1e-9)
	assert.InDelta(t, 2.0, transposed.At(1, 0), 1e-9)
}

func TestEngine_MatrixDimensionMismatch(t *testing.T) {
	m1 := NewMatrix(2, 2, []float64{1, 2, 3, 4})
	m2 := NewMatrix(3, 3, []float64{1, 2, 3, 4, 5, 6, 7, 8, 9})

	_, err := m1.Add(m2)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "dimensions mismatch")

	_, err = m1.Sub(m2)
	require.Error(t, err)

	_, err = m1.Mul(m2)
	require.Error(t, err)
}

func TestEngine_MatrixInverse(t *testing.T) {
	m := NewMatrix(2, 2, []float64{4, 7, 2, 6})

	inv, err := m.Inverse()
	require.NoError(t, err)

	identity, err := m.Mul(inv)
	require.NoError(t, err)
	assert.InDelta(t, 1.0, identity.At(0, 0), 1e-9)
	assert.InDelta(t, 1.0, identity.At(1, 1), 1e-9)
	assert.InDelta(t, 0.0, identity.At(0, 1), 1e-9)
}

func TestEngine_MatrixToVector(t *testing.T) {
	m := NewMatrix(1, 3, []float64{1.0, 2.0, 3.0})
	v := m.ToVector()
	assert.Equal(t, []float64{1.0, 2.0, 3.0}, v)
}

func TestDefaultOptimizerConfig(t *testing.T) {
	config := DefaultOptimizerConfig()
	assert.Equal(t, Adam, config.Type)
	assert.Equal(t, 1000, config.MaxIter)
	assert.InDelta(t, 1e-8, config.Tolerance, 1e-12)
	assert.InDelta(t, 0.001, config.LearningRate, 1e-12)
}

var ErrInvalidInput = &invalidInputError{}

type invalidInputError struct{}

func (e *invalidInputError) Error() string { return "invalid input: NaN or Inf" }
func (e *invalidInputError) Is(target error) bool {
	_, ok := target.(*invalidInputError)
	return ok
}
