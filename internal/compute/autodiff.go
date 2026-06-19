package compute

import (
	"fmt"
	"math"
)

type Variable struct {
	Value     float64
	Grad      float64
	parents   []*Variable
	gradFn    func()
	seed      float64
	index     int
}

type Expression struct {
	inputs   []*Variable
	output   *Variable
	reverse  bool
}

var varCounter = 0

func NewVariable(value float64) *Variable {
	v := &Variable{
		Value: value,
		Grad:  0,
		seed:  1.0,
		index: varCounter,
	}
	varCounter++
	return v
}

func NewInputVariable(value float64) *Variable {
	return NewVariable(value)
}

func (v *Variable) SetSeed(seed float64) {
	v.seed = seed
}

func (v *Variable) Reset() {
	v.Grad = 0
}

func (v *Variable) ZeroGrad() {
	v.Grad = 0
}

func Add(a, b *Variable) *Variable {
	out := &Variable{
		Value:   a.Value + b.Value,
		parents: []*Variable{a, b},
	}
	out.gradFn = func() {
		a.Grad += out.Grad
		b.Grad += out.Grad
	}
	return out
}

func Sub(a, b *Variable) *Variable {
	out := &Variable{
		Value:   a.Value - b.Value,
		parents: []*Variable{a, b},
	}
	out.gradFn = func() {
		a.Grad += out.Grad
		b.Grad -= out.Grad
	}
	return out
}

func Mul(a, b *Variable) *Variable {
	out := &Variable{
		Value:   a.Value * b.Value,
		parents: []*Variable{a, b},
	}
	out.gradFn = func() {
		a.Grad += b.Value * out.Grad
		b.Grad += a.Value * out.Grad
	}
	return out
}

func Div(a, b *Variable) *Variable {
	out := &Variable{
		Value:   a.Value / b.Value,
		parents: []*Variable{a, b},
	}
	out.gradFn = func() {
		a.Grad += out.Grad / b.Value
		b.Grad += -a.Value * out.Grad / (b.Value * b.Value)
	}
	return out
}

func Pow(a *Variable, power float64) *Variable {
	out := &Variable{
		Value:   math.Pow(a.Value, power),
		parents: []*Variable{a},
	}
	out.gradFn = func() {
		a.Grad += power * math.Pow(a.Value, power-1) * out.Grad
	}
	return out
}

func Sin(a *Variable) *Variable {
	out := &Variable{
		Value:   math.Sin(a.Value),
		parents: []*Variable{a},
	}
	out.gradFn = func() {
		a.Grad += math.Cos(a.Value) * out.Grad
	}
	return out
}

func Cos(a *Variable) *Variable {
	out := &Variable{
		Value:   math.Cos(a.Value),
		parents: []*Variable{a},
	}
	out.gradFn = func() {
		a.Grad += -math.Sin(a.Value) * out.Grad
	}
	return out
}

func Exp(a *Variable) *Variable {
	out := &Variable{
		Value:   math.Exp(a.Value),
		parents: []*Variable{a},
	}
	out.gradFn = func() {
		a.Grad += out.Value * out.Grad
	}
	return out
}

func Log(a *Variable) *Variable {
	out := &Variable{
		Value:   math.Log(a.Value),
		parents: []*Variable{a},
	}
	out.gradFn = func() {
		a.Grad += out.Grad / a.Value
	}
	return out
}

func Tanh(a *Variable) *Variable {
	t := math.Tanh(a.Value)
	out := &Variable{
		Value:   t,
		parents: []*Variable{a},
	}
	out.gradFn = func() {
		a.Grad += (1 - t*t) * out.Grad
	}
	return out
}

func Sigmoid(a *Variable) *Variable {
	s := 1.0 / (1.0 + math.Exp(-a.Value))
	out := &Variable{
		Value:   s,
		parents: []*Variable{a},
	}
	out.gradFn = func() {
		a.Grad += s * (1 - s) * out.Grad
	}
	return out
}

func Relu(a *Variable) *Variable {
	var val float64
	if a.Value > 0 {
		val = a.Value
	} else {
		val = 0
	}
	out := &Variable{
		Value:   val,
		parents: []*Variable{a},
	}
	out.gradFn = func() {
		if a.Value > 0 {
			a.Grad += out.Grad
		}
	}
	return out
}

func (v *Variable) Neg() *Variable {
	out := &Variable{
		Value:   -v.Value,
		parents: []*Variable{v},
	}
	out.gradFn = func() {
		v.Grad += -out.Grad
	}
	return out
}

func (a *Variable) Add(b *Variable) *Variable {
	return Add(a, b)
}

func (a *Variable) Sub(b *Variable) *Variable {
	return Sub(a, b)
}

func (a *Variable) Mul(b *Variable) *Variable {
	return Mul(a, b)
}

func (a *Variable) Div(b *Variable) *Variable {
	return Div(a, b)
}

func (v *Variable) Pow(power float64) *Variable {
	return Pow(v, power)
}

func topoSort(root *Variable) []*Variable {
	var topo []*Variable
	visited := make(map[*Variable]bool)

	var buildTopo func(v *Variable)
	buildTopo = func(v *Variable) {
		if !visited[v] {
			visited[v] = true
			for _, parent := range v.parents {
				buildTopo(parent)
			}
			topo = append(topo, v)
		}
	}
	buildTopo(root)

	return topo
}

func ForwardDiff(f func(...*Variable) *Variable, inputs []*Variable) float64 {
	for _, in := range inputs {
		in.Reset()
	}
	for _, in := range inputs {
		in.Grad = in.seed
	}

	topo := topoSort(f(inputs...))
	for _, v := range topo {
		if v.gradFn != nil {
			v.Value = computeValue(v)
		}
	}

	return topo[len(topo)-1].Grad
}

func computeValue(v *Variable) float64 {
	return v.Value
}

func BackwardDiff(f func(...*Variable) *Variable, inputs []*Variable, seedGrad float64) []float64 {
	for _, in := range inputs {
		in.Reset()
	}

	output := f(inputs...)
	output.Grad = seedGrad

	topo := topoSort(output)

	for i := len(topo) - 1; i >= 0; i-- {
		v := topo[i]
		if v.gradFn != nil {
			v.gradFn()
		}
	}

	grads := make([]float64, len(inputs))
	for i, in := range inputs {
		grads[i] = in.Grad
	}

	return grads
}

type ForwardGradient struct {
	direction []float64
}

func NewForwardGradient(direction []float64) *ForwardGradient {
	return &ForwardGradient{direction: direction}
}

func (fg *ForwardGradient) Compute(f func([]float64) float64, x []float64) (float64, float64) {
	h := 1e-8
	base := f(x)

	perturbed := make([]float64, len(x))
	for i := range x {
		perturbed[i] = x[i] + h*fg.direction[i]
	}
	perturbedVal := f(perturbed)

	grad := (perturbedVal - base) / h
	return base, grad
}

type ReverseGradient struct {
	tape []func()
}

func NewReverseGradient() *ReverseGradient {
	return &ReverseGradient{}
}

func (rg *ReverseGradient) Compute(f func(...*Variable) *Variable, inputs []*Variable) ([]float64, float64) {
	for _, in := range inputs {
		in.Reset()
	}

	output := f(inputs...)
	output.Grad = 1.0

	topo := topoSort(output)
	for i := len(topo) - 1; i >= 0; i-- {
		v := topo[i]
		if v.gradFn != nil {
			v.gradFn()
		}
	}

	grads := make([]float64, len(inputs))
	for i, in := range inputs {
		grads[i] = in.Grad
	}

	return grads, output.Value
}

type HigherOrder struct{}

func NewHigherOrder() *HigherOrder {
	return &HigherOrder{}
}

func (ho *HigherOrder) Gradient(f func(...*Variable) *Variable, inputs []*Variable) ([]float64, float64) {
	return NewReverseGradient().Compute(f, inputs)
}

func (ho *HigherOrder) Hessian(f func(...*Variable) *Variable, inputs []*Variable) ([][]float64, []float64, float64) {
	n := len(inputs)
	hessian := make([][]float64, n)
	for i := range hessian {
		hessian[i] = make([]float64, n)
	}

	firstGrads, fVal := ho.Gradient(f, inputs)

	for i := 0; i < n; i++ {
		gradI := func(vars ...*Variable) *Variable {
			g, _ := ho.Gradient(f, vars)
			out := NewVariable(g[i])
			return out
		}

		secondGrads, _ := ho.Gradient(gradI, inputs)
		for j := 0; j < n; j++ {
			hessian[i][j] = secondGrads[j]
		}
	}

	return hessian, firstGrads, fVal
}

func (ho *HigherOrder) Derivative(f func(*Variable) *Variable, x *Variable, order int) float64 {
	if order == 0 {
		return f(x).Value
	}

	df := func(vars ...*Variable) *Variable {
		return f(vars[0])
	}

	inputs := []*Variable{x}
	grads, _ := ho.Gradient(df, inputs)
	return grads[0]
}

type AutoDiffFunc func(x []float64) (float64, []float64)

func MakeAutoDiff(f func(...*Variable) *Variable, n int) AutoDiffFunc {
	return func(x []float64) (float64, []float64) {
		inputs := make([]*Variable, n)
		for i, xi := range x {
			inputs[i] = NewInputVariable(xi)
		}
		grads, fVal := NewReverseGradient().Compute(f, inputs)
		return fVal, grads
	}
}

func NumericalGradient(f func([]float64) float64, x []float64, h float64) []float64 {
	grad := make([]float64, len(x))
	xEps := make([]float64, len(x))
	copy(xEps, x)

	for i := range x {
		xEps[i] = x[i] + h
		fPlus := f(xEps)
		xEps[i] = x[i] - h
		fMinus := f(xEps)
		xEps[i] = x[i]

		grad[i] = (fPlus - fMinus) / (2 * h)
	}

	return grad
}

func CheckGradient(f func([]float64) (float64, []float64), x []float64, tol float64) (bool, error) {
	fVal, analyticGrad := f(x)
	numericGrad := NumericalGradient(func(v []float64) float64 {
		val, _ := f(v)
		return val
	}, x, 1e-8)

	for i := range analyticGrad {
		diff := math.Abs(analyticGrad[i] - numericGrad[i])
		if diff > tol {
			return false, fmt.Errorf("gradient mismatch at index %d: analytic=%.8f, numeric=%.8f, diff=%.8f",
				i, analyticGrad[i], numericGrad[i], diff)
		}
	}

	_ = fVal
	return true, nil
}
