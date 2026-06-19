package objective

import (
	"fmt"
	"math"

	"github.com/df1-96/experiment/internal/compute"
)

type ConstraintType int

const (
	Equality ConstraintType = iota
	Inequality
	Bound
	Linear
	Nonlinear
)

type Constraint interface {
	Type() ConstraintType
	Evaluate(x []float64) float64
	Gradient(x []float64, grad []float64)
	IsSatisfied(x []float64, tol float64) bool
	Violation(x []float64) float64
	Project(x []float64) []float64
}

type BoxConstraints struct {
	Lower []float64
	Upper []float64
	n     int
}

func NewBoxConstraints(n int, lower, upper float64) *BoxConstraints {
	l := make([]float64, n)
	u := make([]float64, n)
	for i := range l {
		l[i] = lower
		u[i] = upper
	}
	return &BoxConstraints{
		Lower: l,
		Upper: u,
		n:     n,
	}
}

func NewBoxConstraintsFromBounds(lower, upper []float64) (*BoxConstraints, error) {
	if len(lower) != len(upper) {
		return nil, fmt.Errorf("lower and upper bounds must have the same length")
	}
	return &BoxConstraints{
		Lower: lower,
		Upper: upper,
		n:     len(lower),
	}, nil
}

func (bc *BoxConstraints) Type() ConstraintType {
	return Bound
}

func (bc *BoxConstraints) Project(x []float64) []float64 {
	projected := make([]float64, len(x))
	for i := range x {
		if i < bc.n {
			projected[i] = math.Max(bc.Lower[i], math.Min(bc.Upper[i], x[i]))
		} else {
			projected[i] = x[i]
		}
	}
	return projected
}

func (bc *BoxConstraints) IsSatisfied(x []float64, tol float64) bool {
	for i := 0; i < bc.n && i < len(x); i++ {
		if x[i] < bc.Lower[i]-tol || x[i] > bc.Upper[i]+tol {
			return false
		}
	}
	return true
}

func (bc *BoxConstraints) Evaluate(x []float64) float64 {
	var violation float64
	for i := 0; i < bc.n && i < len(x); i++ {
		if x[i] < bc.Lower[i] {
			violation += math.Pow(bc.Lower[i]-x[i], 2)
		}
		if x[i] > bc.Upper[i] {
			violation += math.Pow(x[i]-bc.Upper[i], 2)
		}
	}
	return violation
}

func (bc *BoxConstraints) Gradient(x []float64, grad []float64) {
	for i := range grad {
		grad[i] = 0
	}
	for i := 0; i < bc.n && i < len(x) && i < len(grad); i++ {
		if x[i] < bc.Lower[i] {
			grad[i] = -2 * (bc.Lower[i] - x[i])
		}
		if x[i] > bc.Upper[i] {
			grad[i] = 2 * (x[i] - bc.Upper[i])
		}
	}
}

func (bc *BoxConstraints) Violation(x []float64) float64 {
	return bc.Evaluate(x)
}

func (bc *BoxConstraints) SetBounds(index int, lower, upper float64) error {
	if index < 0 || index >= bc.n {
		return fmt.Errorf("index out of bounds: %d (n=%d)", index, bc.n)
	}
	bc.Lower[index] = lower
	bc.Upper[index] = upper
	return nil
}

type LinearConstraint struct {
	A      []float64
	B      float64
	CType  ConstraintType
	n      int
}

func NewLinearConstraint(a []float64, b float64, eq bool) *LinearConstraint {
	ct := Inequality
	if eq {
		ct = Equality
	}
	return &LinearConstraint{
		A:     a,
		B:     b,
		CType: ct,
		n:     len(a),
	}
}

func (lc *LinearConstraint) Type() ConstraintType {
	return lc.CType
}

func (lc *LinearConstraint) Project(x []float64) []float64 {
	projected := make([]float64, len(x))
	copy(projected, x)

	dot := 0.0
	aNormSq := 0.0
	for i := 0; i < lc.n && i < len(x); i++ {
		dot += lc.A[i] * x[i]
		aNormSq += lc.A[i] * lc.A[i]
	}

	if aNormSq == 0 {
		return projected
	}

	if lc.CType == Equality {
		violation := dot - lc.B
		scale := violation / aNormSq
		for i := 0; i < lc.n && i < len(x); i++ {
			projected[i] -= scale * lc.A[i]
		}
	} else {
		if dot > lc.B {
			violation := dot - lc.B
			scale := violation / aNormSq
			for i := 0; i < lc.n && i < len(x); i++ {
				projected[i] -= scale * lc.A[i]
			}
		}
	}

	return projected
}

func (lc *LinearConstraint) IsSatisfied(x []float64, tol float64) bool {
	dot := 0.0
	for i := 0; i < lc.n && i < len(x); i++ {
		dot += lc.A[i] * x[i]
	}

	if lc.CType == Equality {
		return math.Abs(dot-lc.B) <= tol
	}
	return dot <= lc.B+tol
}

func (lc *LinearConstraint) IsSatisfiedTol(x []float64, tol float64) bool {
	return lc.IsSatisfied(x, tol)
}

func (lc *LinearConstraint) Evaluate(x []float64) float64 {
	dot := 0.0
	for i := 0; i < lc.n && i < len(x); i++ {
		dot += lc.A[i] * x[i]
	}

	if lc.CType == Equality {
		return math.Pow(dot-lc.B, 2)
	}

	violation := dot - lc.B
	if violation > 0 {
		return violation * violation
	}
	return 0
}

func (lc *LinearConstraint) Gradient(x []float64, grad []float64) {
	for i := range grad {
		grad[i] = 0
	}

	dot := 0.0
	for i := 0; i < lc.n && i < len(x); i++ {
		dot += lc.A[i] * x[i]
	}

	if lc.CType == Equality {
		violation := dot - lc.B
		for i := 0; i < lc.n && i < len(grad); i++ {
			grad[i] = 2 * violation * lc.A[i]
		}
	} else {
		violation := dot - lc.B
		if violation > 0 {
			for i := 0; i < lc.n && i < len(grad); i++ {
				grad[i] = 2 * violation * lc.A[i]
			}
		}
	}
}

func (lc *LinearConstraint) Violation(x []float64) float64 {
	return lc.Evaluate(x)
}

type NonlinearConstraint struct {
	EvalFunc func(x []float64) float64
	GradFunc func(x []float64, grad []float64)
	CType    ConstraintType
	Value    float64
}

func NewNonlinearConstraint(
	eval func(x []float64) float64,
	grad func(x []float64, grad []float64),
	eq bool,
	value float64,
) *NonlinearConstraint {
	ct := Inequality
	if eq {
		ct = Equality
	}
	return &NonlinearConstraint{
		EvalFunc: eval,
		GradFunc: grad,
		CType:    ct,
		Value:    value,
	}
}

func (nc *NonlinearConstraint) Type() ConstraintType {
	return Nonlinear
}

func (nc *NonlinearConstraint) Project(x []float64) []float64 {
	projected := make([]float64, len(x))
	copy(projected, x)

	val := nc.EvalFunc(x)
	violation := val - nc.Value

	grad := make([]float64, len(x))
	nc.GradFunc(x, grad)

	gradNormSq := 0.0
	for _, g := range grad {
		gradNormSq += g * g
	}

	if gradNormSq == 0 {
		return projected
	}

	if nc.CType == Equality {
		if math.Abs(violation) > 1e-10 {
			scale := violation / gradNormSq
			for i := range projected {
				projected[i] -= scale * grad[i]
			}
		}
	} else {
		if violation > 0 {
			scale := violation / gradNormSq
			for i := range projected {
				projected[i] -= scale * grad[i]
			}
		}
	}

	return projected
}

func (nc *NonlinearConstraint) IsSatisfied(x []float64, tol float64) bool {
	val := nc.EvalFunc(x)
	if nc.CType == Equality {
		return math.Abs(val-nc.Value) <= tol
	}
	return val <= nc.Value+tol
}

func (nc *NonlinearConstraint) Evaluate(x []float64) float64 {
	val := nc.EvalFunc(x)
	if nc.CType == Equality {
		return math.Pow(val-nc.Value, 2)
	}
	violation := val - nc.Value
	if violation > 0 {
		return violation * violation
	}
	return 0
}

func (nc *NonlinearConstraint) Gradient(x []float64, grad []float64) {
	nc.GradFunc(x, grad)
	val := nc.EvalFunc(x)

	if nc.CType == Equality {
		violation := val - nc.Value
		for i := range grad {
			grad[i] = 2 * violation * grad[i]
		}
	} else {
		violation := val - nc.Value
		if violation > 0 {
			for i := range grad {
				grad[i] = 2 * violation * grad[i]
			}
		} else {
			for i := range grad {
				grad[i] = 0
			}
		}
	}
}

func (nc *NonlinearConstraint) Violation(x []float64) float64 {
	return nc.Evaluate(x)
}

type CompositeConstraint struct {
	constraints []Constraint
}

func NewCompositeConstraint(constraints ...Constraint) *CompositeConstraint {
	return &CompositeConstraint{
		constraints: constraints,
	}
}

func (cc *CompositeConstraint) Add(c Constraint) {
	cc.constraints = append(cc.constraints, c)
}

func (cc *CompositeConstraint) Type() ConstraintType {
	return Nonlinear
}

func (cc *CompositeConstraint) Project(x []float64) []float64 {
	result := make([]float64, len(x))
	copy(result, x)

	for _, c := range cc.constraints {
		result = c.Project(result)
	}

	return result
}

func (cc *CompositeConstraint) IsSatisfied(x []float64, tol float64) bool {
	for _, c := range cc.constraints {
		if !c.IsSatisfied(x, tol) {
			return false
		}
	}
	return true
}

func (cc *CompositeConstraint) Evaluate(x []float64) float64 {
	var total float64
	for _, c := range cc.constraints {
		total += c.Evaluate(x)
	}
	return total
}

func (cc *CompositeConstraint) Gradient(x []float64, grad []float64) {
	for i := range grad {
		grad[i] = 0
	}

	tempGrad := make([]float64, len(grad))
	for _, c := range cc.constraints {
		c.Gradient(x, tempGrad)
		for i := range grad {
			grad[i] += tempGrad[i]
		}
	}
}

func (cc *CompositeConstraint) Violation(x []float64) float64 {
	return cc.Evaluate(x)
}

func (cc *CompositeConstraint) Constraints() []Constraint {
	return cc.constraints
}

func (cc *CompositeConstraint) Len() int {
	return len(cc.constraints)
}

type AugmentedLagrangian struct {
	Objective   compute.ObjectiveFunction
	ObjGradient compute.GradientFunction
	Constraints []Constraint
	Lambda      []float64
	Penalty     float64
	nDim        int
}

func NewAugmentedLagrangian(
	obj compute.ObjectiveFunction,
	objGrad compute.GradientFunction,
	constraints []Constraint,
	nDim int,
) *AugmentedLagrangian {
	al := &AugmentedLagrangian{
		Objective:   obj,
		ObjGradient: objGrad,
		Constraints: constraints,
		Penalty:     1.0,
		nDim:        nDim,
	}
	al.Lambda = make([]float64, len(constraints))
	return al
}

func (al *AugmentedLagrangian) Evaluate(x []float64) float64 {
	f := al.Objective(x)

	for i, c := range al.Constraints {
		cVal := c.Evaluate(x)
		f += al.Lambda[i] * cVal
		f += 0.5 * al.Penalty * cVal * cVal
	}

	return f
}

func (al *AugmentedLagrangian) Gradient(x []float64, grad []float64) {
	al.ObjGradient(x, grad)

	tempGrad := make([]float64, len(grad))
	for i, c := range al.Constraints {
		c.Gradient(x, tempGrad)
		cVal := c.Evaluate(x)
		for j := range grad {
			grad[j] += (al.Lambda[i] + al.Penalty*cVal) * tempGrad[j]
		}
	}
}

func (al *AugmentedLagrangian) UpdateMultipliers(x []float64) {
	for i, c := range al.Constraints {
		cVal := c.Evaluate(x)
		al.Lambda[i] += al.Penalty * cVal
	}
}

func (al *AugmentedLagrangian) SetPenalty(penalty float64) {
	al.Penalty = penalty
}

func (al *AugmentedLagrangian) GetPenalty() float64 {
	return al.Penalty
}

func (al *AugmentedLagrangian) MaxViolation(x []float64) float64 {
	var maxV float64
	for _, c := range al.Constraints {
		v := c.Violation(x)
		if v > maxV {
			maxV = v
		}
	}
	return maxV
}

type PenalizedObjective struct {
	objective   compute.ObjectiveFunction
	objGradient compute.GradientFunction
	constraints []Constraint
	penalty     float64
}

func NewPenalizedObjective(
	obj compute.ObjectiveFunction,
	objGrad compute.GradientFunction,
	constraints []Constraint,
	penalty float64,
) *PenalizedObjective {
	return &PenalizedObjective{
		objective:   obj,
		objGradient: objGrad,
		constraints: constraints,
		penalty:     penalty,
	}
}

func (po *PenalizedObjective) Evaluate(x []float64) float64 {
	f := po.objective(x)
	for _, c := range po.constraints {
		f += po.penalty * c.Violation(x)
	}
	return f
}

func (po *PenalizedObjective) Gradient(x []float64, grad []float64) {
	po.objGradient(x, grad)
	tempGrad := make([]float64, len(grad))
	for _, c := range po.constraints {
		c.Gradient(x, tempGrad)
		for i := range grad {
			grad[i] += po.penalty * tempGrad[i]
		}
	}
}

func (po *PenalizedObjective) SetPenalty(penalty float64) {
	po.penalty = penalty
}

func (po *PenalizedObjective) ToObjective() (compute.ObjectiveFunction, compute.GradientFunction) {
	return po.Evaluate, po.Gradient
}

type BarrierMethod struct {
	objective   compute.ObjectiveFunction
	constraints []Constraint
	mu          float64
}

func NewBarrierMethod(
	obj compute.ObjectiveFunction,
	constraints []Constraint,
	mu float64,
) *BarrierMethod {
	return &BarrierMethod{
		objective:   obj,
		constraints: constraints,
		mu:          mu,
	}
}

func (bm *BarrierMethod) Evaluate(x []float64) float64 {
	f := bm.objective(x)

	for _, c := range bm.constraints {
		if c.Type() == Equality {
			continue
		}

		violation := c.Violation(x)
		if violation <= 0 {
			val := c.Evaluate(x)
			if val < 0 {
				f -= bm.mu * math.Log(-val)
			}
		}
	}

	return f
}

func (bm *BarrierMethod) SetMu(mu float64) {
	bm.mu = mu
}

func ProjectToFeasible(x []float64, constraints []Constraint, maxIter int, tol float64) ([]float64, bool) {
	result := make([]float64, len(x))
	copy(result, x)

	for iter := 0; iter < maxIter; iter++ {
		allSatisfied := true

		for _, c := range constraints {
			if !c.IsSatisfied(result, tol) {
				allSatisfied = false
				result = c.Project(result)
			}
		}

		if allSatisfied {
			return result, true
		}

		maxV := 0.0
		for _, c := range constraints {
			v := c.Violation(result)
			if v > maxV {
				maxV = v
			}
		}
		if maxV < tol {
			return result, true
		}
	}

	return result, false
}

type FeasibleResult struct {
	Feasible    bool
	MaxViolation float64
	Violations  []float64
}

func CheckFeasibility(x []float64, constraints []Constraint, tol float64) *FeasibleResult {
	result := &FeasibleResult{
		Feasible:   true,
		Violations: make([]float64, len(constraints)),
	}

	for i, c := range constraints {
		result.Violations[i] = c.Violation(x)
		if result.Violations[i] > result.MaxViolation {
			result.MaxViolation = result.Violations[i]
		}
		if result.Violations[i] > tol {
			result.Feasible = false
		}
	}

	return result
}
