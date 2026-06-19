package compute

import (
	"fmt"
	"math"

	"gonum.org/v1/gonum/mat"
	"gonum.org/v1/gonum/optimize"
)

type ObjectiveFunction func(x []float64) float64

type GradientFunction func(x []float64, grad []float64)

type Matrix struct {
	data *mat.Dense
}

type Engine struct {
	objective  ObjectiveFunction
	gradient   GradientFunction
	dimensions int
}

type OptimizerType int

const (
	GradientDescent OptimizerType = iota
	Adam
	LBFGS
)

type OptimizerConfig struct {
	Type       OptimizerType
	MaxIter    int
	Tolerance  float64
	LearningRate float64
	Beta1      float64
	Beta2      float64
	Epsilon    float64
}

func NewMatrix(rows, cols int, data []float64) *Matrix {
	return &Matrix{
		data: mat.NewDense(rows, cols, data),
	}
}

func NewMatrixFromDense(d *mat.Dense) *Matrix {
	return &Matrix{data: d}
}

func (m *Matrix) Dims() (r, c int) {
	return m.data.Dims()
}

func (m *Matrix) RawData() []float64 {
	return m.data.RawMatrix().Data
}

func (m *Matrix) At(i, j int) float64 {
	return m.data.At(i, j)
}

func (m *Matrix) Set(i, j int, v float64) {
	m.data.Set(i, j, v)
}

func (m *Matrix) Add(other *Matrix) (*Matrix, error) {
	r1, c1 := m.Dims()
	r2, c2 := other.Dims()
	if r1 != r2 || c1 != c2 {
		return nil, fmt.Errorf("matrix dimensions mismatch: (%d,%d) vs (%d,%d)", r1, c1, r2, c2)
	}
	result := mat.NewDense(r1, c1, nil)
	result.Add(m.data, other.data)
	return NewMatrixFromDense(result), nil
}

func (m *Matrix) Sub(other *Matrix) (*Matrix, error) {
	r1, c1 := m.Dims()
	r2, c2 := other.Dims()
	if r1 != r2 || c1 != c2 {
		return nil, fmt.Errorf("matrix dimensions mismatch: (%d,%d) vs (%d,%d)", r1, c1, r2, c2)
	}
	result := mat.NewDense(r1, c1, nil)
	result.Sub(m.data, other.data)
	return NewMatrixFromDense(result), nil
}

func (m *Matrix) Mul(other *Matrix) (*Matrix, error) {
	r1, c1 := m.Dims()
	r2, c2 := other.Dims()
	if c1 != r2 {
		return nil, fmt.Errorf("matrix dimensions mismatch for multiplication: (%d,%d) vs (%d,%d)", r1, c1, r2, c2)
	}
	result := mat.NewDense(r1, c2, nil)
	result.Mul(m.data, other.data)
	return NewMatrixFromDense(result), nil
}

func (m *Matrix) Scale(scalar float64) *Matrix {
	r, c := m.Dims()
	result := mat.NewDense(r, c, nil)
	result.Scale(scalar, m.data)
	return NewMatrixFromDense(result)
}

func (m *Matrix) Div(other *Matrix) (*Matrix, error) {
	r1, c1 := m.Dims()
	r2, c2 := other.Dims()
	if r1 != r2 || c1 != c2 {
		return nil, fmt.Errorf("matrix dimensions mismatch: (%d,%d) vs (%d,%d)", r1, c1, r2, c2)
	}
	result := mat.NewDense(r1, c1, nil)
	result.DivElem(m.data, other.data)
	return NewMatrixFromDense(result), nil
}

func (m *Matrix) Transpose() *Matrix {
	r, c := m.Dims()
	result := mat.NewDense(c, r, nil)
	for i := 0; i < r; i++ {
		for j := 0; j < c; j++ {
			result.Set(j, i, m.data.At(i, j))
		}
	}
	return NewMatrixFromDense(result)
}

func (m *Matrix) Inverse() (*Matrix, error) {
	r, c := m.Dims()
	if r != c {
		return nil, fmt.Errorf("only square matrices can be inverted, got (%d,%d)", r, c)
	}
	result := mat.NewDense(r, r, nil)
	err := result.Inverse(m.data)
	if err != nil {
		return nil, fmt.Errorf("matrix inversion failed: %w", err)
	}
	return NewMatrixFromDense(result), nil
}

func (m *Matrix) ToVector() []float64 {
	return m.RawData()
}

func (m *Matrix) String() string {
	return fmt.Sprintf("%v", mat.Formatted(m.data, mat.Squeeze()))
}

func NewEngine(dim int, obj ObjectiveFunction, grad GradientFunction) *Engine {
	return &Engine{
		objective:  obj,
		gradient:   grad,
		dimensions: dim,
	}
}

func DefaultOptimizerConfig() OptimizerConfig {
	return OptimizerConfig{
		Type:         Adam,
		MaxIter:      1000,
		Tolerance:    1e-8,
		LearningRate: 0.001,
		Beta1:        0.9,
		Beta2:        0.999,
		Epsilon:      1e-8,
	}
}

func (e *Engine) Optimize(initialX []float64, config OptimizerConfig) ([]float64, float64, error) {
	if len(initialX) != e.dimensions {
		return nil, 0, fmt.Errorf("initial point dimension mismatch: expected %d, got %d", e.dimensions, len(initialX))
	}

	switch config.Type {
	case GradientDescent:
		return e.gradientDescent(initialX, config)
	case Adam:
		return e.adam(initialX, config)
	case LBFGS:
		return e.lbfgs(initialX, config)
	default:
		return nil, 0, fmt.Errorf("unknown optimizer type: %d", config.Type)
	}
}

func (e *Engine) gradientDescent(initialX []float64, config OptimizerConfig) ([]float64, float64, error) {
	x := make([]float64, len(initialX))
	copy(x, initialX)
	grad := make([]float64, len(x))
	prevF := math.Inf(1)

	for iter := 0; iter < config.MaxIter; iter++ {
		f := e.objective(x)
		e.gradient(x, grad)

		if math.Abs(prevF-f) < config.Tolerance {
			return x, f, nil
		}
		prevF = f

		for i := range x {
			x[i] -= config.LearningRate * grad[i]
		}
	}

	return x, e.objective(x), nil
}

func (e *Engine) adam(initialX []float64, config OptimizerConfig) ([]float64, float64, error) {
	x := make([]float64, len(initialX))
	copy(x, initialX)
	m := make([]float64, len(x))
	v := make([]float64, len(x))
	grad := make([]float64, len(x))
	prevF := math.Inf(1)

	beta1, beta2, eps := config.Beta1, config.Beta2, config.Epsilon

	for iter := 0; iter < config.MaxIter; iter++ {
		f := e.objective(x)
		e.gradient(x, grad)

		if math.Abs(prevF-f) < config.Tolerance {
			return x, f, nil
		}
		prevF = f

		t := float64(iter + 1)
		for i := range x {
			m[i] = beta1*m[i] + (1-beta1)*grad[i]
			v[i] = beta2*v[i] + (1-beta2)*grad[i]*grad[i]

			mHat := m[i] / (1 - math.Pow(beta1, t))
			vHat := v[i] / (1 - math.Pow(beta2, t))

			x[i] -= config.LearningRate * mHat / (math.Sqrt(vHat) + eps)
		}
	}

	return x, e.objective(x), nil
}

func (e *Engine) lbfgs(initialX []float64, config OptimizerConfig) ([]float64, float64, error) {
	problem := optimize.Problem{
		Func: func(x []float64) float64 {
			return e.objective(x)
		},
		Grad: func(grad, x []float64) {
			e.gradient(x, grad)
		},
	}

	settings := &optimize.Settings{
		GradientThreshold: config.Tolerance,
	}

	method := &optimize.LBFGS{
		Store: 10,
		Linesearcher: &optimize.Backtracking{
			DecreaseFactor: 1e-4,
		},
	}

	result, err := optimize.Minimize(problem, initialX, settings, method)
	if err != nil {
		return nil, 0, err
	}

	return result.X, result.F, nil
}

func (e *Engine) SetObjective(obj ObjectiveFunction) {
	e.objective = obj
}

func (e *Engine) SetGradient(grad GradientFunction) {
	e.gradient = grad
}
