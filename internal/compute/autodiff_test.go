package compute

import (
	"math"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestAutoDiff_ForwardMode_SimpleFunction(t *testing.T) {
	f := func(x *Variable) *Variable {
		return Pow(x, 2)
	}

	x := NewInputVariable(3.0)

	grads := BackwardDiff(func(vars ...*Variable) *Variable {
		return f(vars[0])
	}, []*Variable{x}, 1.0)

	assert.InDelta(t, 6.0, grads[0], 1e-6, "f(x)=x^2 at x=3, derivative should be 6")
}

func TestAutoDiff_BackwardMode_Rosenbrock(t *testing.T) {
	rosenbrock := func(vars ...*Variable) *Variable {
		x := vars[0]
		y := vars[1]
		a := NewVariable(1.0)
		b := NewVariable(100.0)

		term1 := Pow(Sub(a, x), 2)
		inner := Sub(y, Pow(x, 2))
		term2 := Mul(b, Pow(inner, 2))
		return Add(term1, term2)
	}

	x := NewInputVariable(1.5)
	y := NewInputVariable(1.5)

	grads := BackwardDiff(rosenbrock, []*Variable{x, y}, 1.0)

	expectedDx := -2*(1.0-1.5) + 100.0*2*(1.5-1.5*1.5)*(-2*1.5)
	expectedDy := 100.0 * 2 * (1.5 - 1.5*1.5)

	assert.InDelta(t, expectedDx, grads[0], 1e-6, "dRosenbrock/dx mismatch")
	assert.InDelta(t, expectedDy, grads[1], 1e-6, "dRosenbrock/dy mismatch")
}

func TestAutoDiff_HigherOrder_Hessian(t *testing.T) {
	f := func(vars ...*Variable) *Variable {
		return Add(Pow(vars[0], 2), Pow(vars[1], 2))
	}

	x := NewInputVariable(2.0)
	y := NewInputVariable(3.0)

	ho := NewHigherOrder()
	hessian, firstGrads, fVal := ho.Hessian(f, []*Variable{x, y})

	expectedFVal := 2.0*2.0 + 3.0*3.0
	assert.InDelta(t, expectedFVal, fVal, 1e-6, "function value mismatch")

	expectedDfDx := 2.0 * 2.0
	expectedDfDy := 2.0 * 3.0
	assert.InDelta(t, expectedDfDx, firstGrads[0], 1e-5, "df/dx mismatch")
	assert.InDelta(t, expectedDfDy, firstGrads[1], 1e-5, "df/dy mismatch")

	n := len(hessian)
	for i := 0; i < n; i++ {
		assert.Len(t, hessian[i], n, "hessian row %d should have %d elements", i, n)
	}

	for i := 0; i < n; i++ {
		assert.Greater(t, hessian[i][i], 0.0, "diagonal element hessian[%d][%d] should be positive for convex function", i, i)
	}
}

func TestAutoDiff_GradientCheck(t *testing.T) {
	autoDiffFunc := MakeAutoDiff(func(vars ...*Variable) *Variable {
		x := vars[0]
		y := vars[1]
		return Add(Pow(x, 2), Mul(Pow(y, 3), NewVariable(2.0)))
	}, 2)

	x := []float64{1.5, 2.0}

	fVal, analyticGrad := autoDiffFunc(x)

	expectedFVal := 1.5*1.5 + 2.0*(2.0*2.0*2.0)
	assert.InDelta(t, expectedFVal, fVal, 1e-6, "function value mismatch")

	numericGrad := NumericalGradient(func(v []float64) float64 {
		val, _ := autoDiffFunc(v)
		return val
	}, x, 1e-8)

	for i := range analyticGrad {
		assert.InDelta(t, numericGrad[i], analyticGrad[i], 1e-5,
			"gradient mismatch at index %d: analytic=%.8f, numeric=%.8f",
			i, analyticGrad[i], numericGrad[i])
	}

	ok, err := CheckGradient(autoDiffFunc, x, 1e-5)
	require.NoError(t, err)
	assert.True(t, ok, "CheckGradient should pass")
}

func TestNumericalGradient_Simple(t *testing.T) {
	f := func(x []float64) float64 {
		return x[0]*x[0] + x[1]*x[1]
	}

	x := []float64{3.0, 4.0}
	grad := NumericalGradient(f, x, 1e-8)

	assert.InDelta(t, 6.0, grad[0], 1e-5, "df/dx should be 2*3=6")
	assert.InDelta(t, 8.0, grad[1], 1e-5, "df/dy should be 2*4=8")
}

func TestMakeAutoDiff_SinCos(t *testing.T) {
	f := func(vars ...*Variable) *Variable {
		return Add(Sin(vars[0]), Cos(vars[1]))
	}

	ad := MakeAutoDiff(f, 2)
	x := []float64{math.Pi / 4, math.Pi / 3}
	fVal, grads := ad(x)

	expectedF := math.Sin(math.Pi/4) + math.Cos(math.Pi/3)
	assert.InDelta(t, expectedF, fVal, 1e-6)
	assert.InDelta(t, math.Cos(math.Pi/4), grads[0], 1e-5, "d/dx sin(x) = cos(x)")
	assert.InDelta(t, -math.Sin(math.Pi/3), grads[1], 1e-5, "d/dy cos(y) = -sin(y)")
}

func TestMakeAutoDiff_ExpLog(t *testing.T) {
	f := func(vars ...*Variable) *Variable {
		return Exp(vars[0])
	}
	ad := MakeAutoDiff(f, 1)
	x := []float64{1.0}
	fVal, grads := ad(x)
	assert.InDelta(t, math.E, fVal, 1e-6)
	assert.InDelta(t, math.E, grads[0], 1e-5, "d/dx e^x = e^x")
}

func TestMakeAutoDiff_Tanh(t *testing.T) {
	f := func(vars ...*Variable) *Variable {
		return Tanh(vars[0])
	}
	ad := MakeAutoDiff(f, 1)
	x := []float64{0.5}
	fVal, grads := ad(x)
	expected := math.Tanh(0.5)
	assert.InDelta(t, expected, fVal, 1e-6)
	expectedGrad := 1.0 - expected*expected
	assert.InDelta(t, expectedGrad, grads[0], 1e-5)
}

func TestMakeAutoDiff_Sigmoid(t *testing.T) {
	f := func(vars ...*Variable) *Variable {
		return Sigmoid(vars[0])
	}
	ad := MakeAutoDiff(f, 1)
	x := []float64{0.0}
	fVal, grads := ad(x)
	assert.InDelta(t, 0.5, fVal, 1e-6)
	assert.InDelta(t, 0.25, grads[0], 1e-5, "sigmoid(0)' = sigmoid(0)*(1-sigmoid(0)) = 0.25")
}

func TestMakeAutoDiff_Relu(t *testing.T) {
	f := func(vars ...*Variable) *Variable {
		return Relu(vars[0])
	}
	ad := MakeAutoDiff(f, 1)

	xPos := []float64{2.0}
	fVal, grads := ad(xPos)
	assert.InDelta(t, 2.0, fVal, 1e-6)
	assert.InDelta(t, 1.0, grads[0], 1e-5, "relu'(x) = 1 for x > 0")

	xNeg := []float64{-1.0}
	fVal, grads = ad(xNeg)
	assert.InDelta(t, 0.0, fVal, 1e-6)
	assert.InDelta(t, 0.0, grads[0], 1e-5, "relu'(x) = 0 for x < 0")
}

func TestVariable_ArithmeticMethods(t *testing.T) {
	x := NewInputVariable(3.0)
	y := NewInputVariable(4.0)

	sum := x.Add(y)
	assert.InDelta(t, 7.0, sum.Value, 1e-9)

	diff := x.Sub(y)
	assert.InDelta(t, -1.0, diff.Value, 1e-9)

	prod := x.Mul(y)
	assert.InDelta(t, 12.0, prod.Value, 1e-9)

	quot := x.Div(y)
	assert.InDelta(t, 0.75, quot.Value, 1e-9)

	pow := x.Pow(2.0)
	assert.InDelta(t, 9.0, pow.Value, 1e-9)

	neg := x.Neg()
	assert.InDelta(t, -3.0, neg.Value, 1e-9)
}

func TestReverseGradient_Compute(t *testing.T) {
	rg := NewReverseGradient()
	require.NotNil(t, rg)

	x := NewInputVariable(2.0)
	y := NewInputVariable(3.0)

	f := func(vars ...*Variable) *Variable {
		return Mul(vars[0], vars[1])
	}

	grads, fVal := rg.Compute(f, []*Variable{x, y})
	assert.InDelta(t, 6.0, fVal, 1e-9)
	assert.InDelta(t, 3.0, grads[0], 1e-9, "d(xy)/dx = y")
	assert.InDelta(t, 2.0, grads[1], 1e-9, "d(xy)/dy = x")
}

func TestForwardGradient_Compute(t *testing.T) {
	f := func(x []float64) float64 {
		return x[0]*x[0] + x[1]*x[1]
	}
	fg := NewForwardGradient([]float64{1.0, 0.0})
	require.NotNil(t, fg)

	x := []float64{3.0, 4.0}
	fVal, dirGrad := fg.Compute(f, x)
	assert.InDelta(t, 25.0, fVal, 1e-6)
	assert.InDelta(t, 6.0, dirGrad, 1e-5, "directional derivative in x direction should be 2*3=6")
}

func TestHigherOrder_Derivative(t *testing.T) {
	ho := NewHigherOrder()
	require.NotNil(t, ho)

	f := func(x *Variable) *Variable {
		return Pow(x, 3)
	}
	x := NewInputVariable(2.0)

	deriv := ho.Derivative(f, x, 1)
	assert.InDelta(t, 12.0, deriv, 1e-5, "d/dx x^3 at x=2 = 3*4 = 12")
}

func TestCheckGradient_Fail(t *testing.T) {
	wrongGradFunc := func(x []float64) (float64, []float64) {
		f := x[0]*x[0] + x[1]*x[1]
		grad := []float64{2*x[0] + 10.0, 2 * x[1]}
		return f, grad
	}

	ok, err := CheckGradient(wrongGradFunc, []float64{1.0, 2.0}, 1e-5)
	assert.False(t, ok)
	assert.Error(t, err)
}

func TestVariable_ZeroGrad(t *testing.T) {
	x := NewInputVariable(5.0)
	x.Grad = 100.0
	x.ZeroGrad()
	assert.InDelta(t, 0.0, x.Grad, 1e-9)
}

func TestVariable_SetSeed(t *testing.T) {
	x := NewInputVariable(1.0)
	x.SetSeed(2.0)
	assert.InDelta(t, 2.0, x.seed, 1e-9)
}
