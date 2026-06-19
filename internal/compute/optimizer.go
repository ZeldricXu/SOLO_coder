package compute

import (
	"fmt"
	"math"
)

type Optimizer interface {
	Step(x []float64, grad []float64) []float64
	Reset()
}

type EarlyStoppingConfig struct {
	Enabled        bool
	Patience       int
	MinDelta       float64
	Monitor        string
	RestoreBest    bool
}

type OptimizationResult struct {
	X             []float64
	F             float64
	Gradient      []float64
	Iterations    int
	Converged     bool
	Reason        string
	BestF         float64
	BestX         []float64
	History       []float64
	GradHistory   []float64
}

type GradientDescentOptimizer struct {
	LearningRate float64
}

func NewGradientDescentOptimizer(lr float64) *GradientDescentOptimizer {
	return &GradientDescentOptimizer{LearningRate: lr}
}

func (gd *GradientDescentOptimizer) Step(x []float64, grad []float64) []float64 {
	newX := make([]float64, len(x))
	for i := range x {
		newX[i] = x[i] - gd.LearningRate*grad[i]
	}
	return newX
}

func (gd *GradientDescentOptimizer) Reset() {}

type MomentumOptimizer struct {
	LearningRate float64
	Momentum     float64
	velocity     []float64
}

func NewMomentumOptimizer(lr, momentum float64) *MomentumOptimizer {
	return &MomentumOptimizer{
		LearningRate: lr,
		Momentum:     momentum,
	}
}

func (m *MomentumOptimizer) Step(x []float64, grad []float64) []float64 {
	if m.velocity == nil {
		m.velocity = make([]float64, len(x))
	}

	newX := make([]float64, len(x))
	for i := range x {
		m.velocity[i] = m.Momentum*m.velocity[i] - m.LearningRate*grad[i]
		newX[i] = x[i] + m.velocity[i]
	}
	return newX
}

func (m *MomentumOptimizer) Reset() {
	m.velocity = nil
}

type NesterovOptimizer struct {
	LearningRate float64
	Momentum     float64
	velocity     []float64
}

func NewNesterovOptimizer(lr, momentum float64) *NesterovOptimizer {
	return &NesterovOptimizer{
		LearningRate: lr,
		Momentum:     momentum,
	}
}

func (n *NesterovOptimizer) Step(x []float64, grad []float64) []float64 {
	if n.velocity == nil {
		n.velocity = make([]float64, len(x))
	}

	newX := make([]float64, len(x))
	for i := range x {
		prevV := n.velocity[i]
		n.velocity[i] = n.Momentum*n.velocity[i] - n.LearningRate*grad[i]
		newX[i] = x[i] - n.Momentum*prevV + (1+n.Momentum)*n.velocity[i]
	}
	return newX
}

func (n *NesterovOptimizer) Reset() {
	n.velocity = nil
}

type RMSpropOptimizer struct {
	LearningRate float64
	Gamma        float64
	Epsilon      float64
	cache        []float64
}

func NewRMSpropOptimizer(lr, gamma, epsilon float64) *RMSpropOptimizer {
	return &RMSpropOptimizer{
		LearningRate: lr,
		Gamma:        gamma,
		Epsilon:      epsilon,
	}
}

func (r *RMSpropOptimizer) Step(x []float64, grad []float64) []float64 {
	if r.cache == nil {
		r.cache = make([]float64, len(x))
	}

	newX := make([]float64, len(x))
	for i := range x {
		r.cache[i] = r.Gamma*r.cache[i] + (1-r.Gamma)*grad[i]*grad[i]
		newX[i] = x[i] - r.LearningRate*grad[i]/(math.Sqrt(r.cache[i])+r.Epsilon)
	}
	return newX
}

func (r *RMSpropOptimizer) Reset() {
	r.cache = nil
}

type AdamOptimizer struct {
	LearningRate float64
	Beta1        float64
	Beta2        float64
	Epsilon      float64
	m            []float64
	v            []float64
	t            int
}

func NewAdamOptimizer(lr, beta1, beta2, epsilon float64) *AdamOptimizer {
	return &AdamOptimizer{
		LearningRate: lr,
		Beta1:        beta1,
		Beta2:        beta2,
		Epsilon:      epsilon,
		t:            0,
	}
}

func (a *AdamOptimizer) Step(x []float64, grad []float64) []float64 {
	if a.m == nil {
		a.m = make([]float64, len(x))
		a.v = make([]float64, len(x))
	}

	a.t++
	t := float64(a.t)
	newX := make([]float64, len(x))

	for i := range x {
		a.m[i] = a.Beta1*a.m[i] + (1-a.Beta1)*grad[i]
		a.v[i] = a.Beta2*a.v[i] + (1-a.Beta2)*grad[i]*grad[i]

		mHat := a.m[i] / (1 - math.Pow(a.Beta1, t))
		vHat := a.v[i] / (1 - math.Pow(a.Beta2, t))

		newX[i] = x[i] - a.LearningRate*mHat/(math.Sqrt(vHat)+a.Epsilon)
	}
	return newX
}

func (a *AdamOptimizer) Reset() {
	a.m = nil
	a.v = nil
	a.t = 0
}

type AdaGradOptimizer struct {
	LearningRate float64
	Epsilon      float64
	cache        []float64
}

func NewAdaGradOptimizer(lr, epsilon float64) *AdaGradOptimizer {
	return &AdaGradOptimizer{
		LearningRate: lr,
		Epsilon:      epsilon,
	}
}

func (ag *AdaGradOptimizer) Step(x []float64, grad []float64) []float64 {
	if ag.cache == nil {
		ag.cache = make([]float64, len(x))
	}

	newX := make([]float64, len(x))
	for i := range x {
		ag.cache[i] += grad[i] * grad[i]
		newX[i] = x[i] - ag.LearningRate*grad[i]/(math.Sqrt(ag.cache[i])+ag.Epsilon)
	}
	return newX
}

func (ag *AdaGradOptimizer) Reset() {
	ag.cache = nil
}

type AdaDeltaOptimizer struct {
	Gamma   float64
	Epsilon float64
	cache   []float64
	delta   []float64
}

func NewAdaDeltaOptimizer(gamma, epsilon float64) *AdaDeltaOptimizer {
	return &AdaDeltaOptimizer{
		Gamma:   gamma,
		Epsilon: epsilon,
	}
}

func (ad *AdaDeltaOptimizer) Step(x []float64, grad []float64) []float64 {
	if ad.cache == nil {
		ad.cache = make([]float64, len(x))
		ad.delta = make([]float64, len(x))
	}

	newX := make([]float64, len(x))
	for i := range x {
		ad.cache[i] = ad.Gamma*ad.cache[i] + (1-ad.Gamma)*grad[i]*grad[i]
		update := -math.Sqrt(ad.delta[i]+ad.Epsilon) / math.Sqrt(ad.cache[i]+ad.Epsilon) * grad[i]
		ad.delta[i] = ad.Gamma*ad.delta[i] + (1-ad.Gamma)*update*update
		newX[i] = x[i] + update
	}
	return newX
}

func (ad *AdaDeltaOptimizer) Reset() {
	ad.cache = nil
	ad.delta = nil
}

type Constraint interface {
	Project(x []float64) []float64
	IsSatisfied(x []float64) bool
}

type BoxConstraint struct {
	Lower []float64
	Upper []float64
}

func NewBoxConstraint(lower, upper []float64) *BoxConstraint {
	return &BoxConstraint{
		Lower: lower,
		Upper: upper,
	}
}

func (bc *BoxConstraint) Project(x []float64) []float64 {
	projected := make([]float64, len(x))
	for i := range x {
		projected[i] = math.Max(bc.Lower[i], math.Min(bc.Upper[i], x[i]))
	}
	return projected
}

func (bc *BoxConstraint) IsSatisfied(x []float64) bool {
	for i := range x {
		if x[i] < bc.Lower[i] || x[i] > bc.Upper[i] {
			return false
		}
	}
	return true
}

type ProjectedGradientOptimizer struct {
	baseOptimizer Optimizer
	constraint    Constraint
}

func NewProjectedGradientOptimizer(base Optimizer, constraint Constraint) *ProjectedGradientOptimizer {
	return &ProjectedGradientOptimizer{
		baseOptimizer: base,
		constraint:    constraint,
	}
}

func (pg *ProjectedGradientOptimizer) Step(x []float64, grad []float64) []float64 {
	unconstrained := pg.baseOptimizer.Step(x, grad)
	return pg.constraint.Project(unconstrained)
}

func (pg *ProjectedGradientOptimizer) Reset() {
	pg.baseOptimizer.Reset()
}

type EarlyStopping struct {
	config       EarlyStoppingConfig
	patienceLeft int
	bestF        float64
	bestX        []float64
	history      []float64
}

func NewEarlyStopping(config EarlyStoppingConfig) *EarlyStopping {
	return &EarlyStopping{
		config:       config,
		patienceLeft: config.Patience,
		bestF:        math.Inf(1),
		history:      make([]float64, 0),
	}
}

func (es *EarlyStopping) ShouldStop(f float64, x []float64) (bool, string) {
	if !es.config.Enabled {
		return false, ""
	}

	es.history = append(es.history, f)

	if f < es.bestF-es.config.MinDelta {
		es.bestF = f
		es.bestX = make([]float64, len(x))
		copy(es.bestX, x)
		es.patienceLeft = es.config.Patience
		return false, ""
	}

	es.patienceLeft--
	if es.patienceLeft <= 0 {
		return true, fmt.Sprintf("early stopping: no improvement for %d iterations", es.config.Patience)
	}

	return false, ""
}

func (es *EarlyStopping) GetBest() (float64, []float64) {
	return es.bestF, es.bestX
}

func (es *EarlyStopping) Reset() {
	es.patienceLeft = es.config.Patience
	es.bestF = math.Inf(1)
	es.bestX = nil
	es.history = make([]float64, 0)
}

type OptimizationConfig struct {
	MaxIterations      int
	Tolerance          float64
	GradTolerance      float64
	LearningRate       float64
	OptimizerType      OptimizerType
	EarlyStopping      EarlyStoppingConfig
	Constraint         Constraint
	RecordHistory      bool
}

func DefaultOptimizationConfig() OptimizationConfig {
	return OptimizationConfig{
		MaxIterations: 1000,
		Tolerance:     1e-8,
		GradTolerance: 1e-6,
		LearningRate:  0.001,
		OptimizerType: Adam,
		EarlyStopping: EarlyStoppingConfig{
			Enabled:     false,
			Patience:    50,
			MinDelta:    1e-6,
			RestoreBest: true,
		},
		RecordHistory: false,
	}
}

func createOptimizer(config OptimizationConfig) Optimizer {
	switch config.OptimizerType {
	case GradientDescent:
		return NewGradientDescentOptimizer(config.LearningRate)
	case Adam:
		return NewAdamOptimizer(config.LearningRate, 0.9, 0.999, 1e-8)
	default:
		return NewGradientDescentOptimizer(config.LearningRate)
	}
}

func (e *Engine) Minimize(initialX []float64, config OptimizationConfig) (*OptimizationResult, error) {
	if len(initialX) != e.dimensions {
		return nil, fmt.Errorf("initial point dimension mismatch: expected %d, got %d",
			e.dimensions, len(initialX))
	}

	x := make([]float64, len(initialX))
	copy(x, initialX)
	grad := make([]float64, len(x))

	var opt Optimizer
	if config.Constraint != nil {
		baseOpt := createOptimizer(config)
		opt = NewProjectedGradientOptimizer(baseOpt, config.Constraint)
	} else {
		opt = createOptimizer(config)
	}

	earlyStop := NewEarlyStopping(config.EarlyStopping)

	result := &OptimizationResult{
		X:           x,
		Iterations:  0,
		Converged:   false,
		BestF:       math.Inf(1),
	}

	if config.RecordHistory {
		result.History = make([]float64, 0, config.MaxIterations)
		result.GradHistory = make([]float64, 0, config.MaxIterations)
	}

	prevF := math.Inf(1)

	for iter := 0; iter < config.MaxIterations; iter++ {
		f := e.objective(x)
		e.gradient(x, grad)

		result.Iterations = iter
		result.F = f
		result.Gradient = make([]float64, len(grad))
		copy(result.Gradient, grad)

		if config.RecordHistory {
			result.History = append(result.History, f)
			result.GradHistory = append(result.GradHistory, l2Norm(grad))
		}

		if f < result.BestF {
			result.BestF = f
			result.BestX = make([]float64, len(x))
			copy(result.BestX, x)
		}

		if stop, reason := earlyStop.ShouldStop(f, x); stop {
			result.Converged = false
			result.Reason = reason
			if config.EarlyStopping.RestoreBest {
				bestF, bestX := earlyStop.GetBest()
				result.F = bestF
				result.X = bestX
			}
			return result, nil
		}

		if math.Abs(prevF-f) < config.Tolerance {
			result.Converged = true
			result.Reason = fmt.Sprintf("function value converged: |f_prev - f| < %e", config.Tolerance)
			return result, nil
		}

		gradNorm := l2Norm(grad)
		if gradNorm < config.GradTolerance {
			result.Converged = true
			result.Reason = fmt.Sprintf("gradient converged: ||grad|| < %e", config.GradTolerance)
			return result, nil
		}

		prevF = f
		x = opt.Step(x, grad)
	}

	result.Reason = fmt.Sprintf("max iterations reached: %d", config.MaxIterations)
	result.X = x
	result.F = e.objective(x)
	return result, nil
}

func l2Norm(v []float64) float64 {
	var sum float64
	for _, x := range v {
		sum += x * x
	}
	return math.Sqrt(sum)
}

type LearningRateScheduler interface {
	GetLearningRate(iteration int) float64
}

type ExponentialDecay struct {
	InitialLR float64
	DecayRate float64
	DecaySteps int
}

func (ed *ExponentialDecay) GetLearningRate(iteration int) float64 {
	return ed.InitialLR * math.Pow(ed.DecayRate, float64(iteration)/float64(ed.DecaySteps))
}

type StepDecay struct {
	InitialLR  float64
	DropFactor float64
	StepSize   int
}

func (sd *StepDecay) GetLearningRate(iteration int) float64 {
	steps := iteration / sd.StepSize
	return sd.InitialLR * math.Pow(sd.DropFactor, float64(steps))
}

type ReduceLROnPlateau struct {
	CurrentLR   float64
	Factor      float64
	Patience    int
	MinDelta    float64
	MinLR       float64
	patienceLeft int
	bestF       float64
	history     []float64
}

func NewReduceLROnPlateau(initialLR, factor float64, patience int, minDelta, minLR float64) *ReduceLROnPlateau {
	return &ReduceLROnPlateau{
		CurrentLR:    initialLR,
		Factor:       factor,
		Patience:     patience,
		MinDelta:     minDelta,
		MinLR:        minLR,
		patienceLeft: patience,
		bestF:        math.Inf(1),
	}
}

func (rlr *ReduceLROnPlateau) GetLearningRate(iteration int) float64 {
	return rlr.CurrentLR
}

func (rlr *ReduceLROnPlateau) Update(f float64) {
	rlr.history = append(rlr.history, f)

	if f < rlr.bestF-rlr.MinDelta {
		rlr.bestF = f
		rlr.patienceLeft = rlr.Patience
		return
	}

	rlr.patienceLeft--
	if rlr.patienceLeft <= 0 {
		rlr.CurrentLR = math.Max(rlr.MinLR, rlr.CurrentLR*rlr.Factor)
		rlr.patienceLeft = rlr.Patience
	}
}
