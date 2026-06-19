package objective

import (
	"math"

	"github.com/df1-96/experiment/internal/compute"
)

type TestFunction interface {
	Name() string
	Evaluate(x []float64) float64
	Gradient(x []float64, grad []float64)
	Minimum() ([]float64, float64)
	Dimensions() int
}

type Rosenbrock struct {
	A float64
	B float64
	n int
}

func NewRosenbrock(dimensions int) *Rosenbrock {
	return &Rosenbrock{
		A: 1.0,
		B: 100.0,
		n: dimensions,
	}
}

func (r *Rosenbrock) Name() string {
	return "Rosenbrock"
}

func (r *Rosenbrock) Evaluate(x []float64) float64 {
	var sum float64
	for i := 0; i < len(x)-1; i++ {
		sum += math.Pow(r.A-x[i], 2) + r.B*math.Pow(x[i+1]-x[i]*x[i], 2)
	}
	return sum
}

func (r *Rosenbrock) Gradient(x []float64, grad []float64) {
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

func (r *Rosenbrock) Minimum() ([]float64, float64) {
	minX := make([]float64, r.n)
	for i := range minX {
		minX[i] = r.A
	}
	return minX, 0.0
}

func (r *Rosenbrock) Dimensions() int {
	return r.n
}

func (r *Rosenbrock) ToObjective() (compute.ObjectiveFunction, compute.GradientFunction) {
	return r.Evaluate, r.Gradient
}

type Sphere struct {
	n int
}

func NewSphere(dimensions int) *Sphere {
	return &Sphere{n: dimensions}
}

func (s *Sphere) Name() string {
	return "Sphere"
}

func (s *Sphere) Evaluate(x []float64) float64 {
	var sum float64
	for _, xi := range x {
		sum += xi * xi
	}
	return sum
}

func (s *Sphere) Gradient(x []float64, grad []float64) {
	for i, xi := range x {
		grad[i] = 2 * xi
	}
}

func (s *Sphere) Minimum() ([]float64, float64) {
	minX := make([]float64, s.n)
	return minX, 0.0
}

func (s *Sphere) Dimensions() int {
	return s.n
}

func (s *Sphere) ToObjective() (compute.ObjectiveFunction, compute.GradientFunction) {
	return s.Evaluate, s.Gradient
}

type Ackley struct {
	A       float64
	B       float64
	C       float64
	n       int
}

func NewAckley(dimensions int) *Ackley {
	return &Ackley{
		A: 20.0,
		B: 0.2,
		C: 2 * math.Pi,
		n: dimensions,
	}
}

func (a *Ackley) Name() string {
	return "Ackley"
}

func (a *Ackley) Evaluate(x []float64) float64 {
	n := float64(len(x))
	var sum1, sum2 float64

	for _, xi := range x {
		sum1 += xi * xi
		sum2 += math.Cos(a.C * xi)
	}

	return -a.A*math.Exp(-a.B*math.Sqrt(sum1/n)) - math.Exp(sum2/n) + a.A + math.E
}

func (a *Ackley) Gradient(x []float64, grad []float64) {
	n := float64(len(x))
	var sum1, sum2 float64

	for _, xi := range x {
		sum1 += xi * xi
		sum2 += math.Cos(a.C * xi)
	}

	term1 := -a.A * math.Exp(-a.B*math.Sqrt(sum1/n)) * (-a.B / (2 * n * math.Sqrt(sum1/n)))
	term2 := -math.Exp(sum2/n) * (1.0 / n)

	for i, xi := range x {
		grad[i] = term1*2*xi + term2*(-a.C*math.Sin(a.C*xi))
	}
}

func (a *Ackley) Minimum() ([]float64, float64) {
	minX := make([]float64, a.n)
	return minX, 0.0
}

func (a *Ackley) Dimensions() int {
	return a.n
}

func (a *Ackley) ToObjective() (compute.ObjectiveFunction, compute.GradientFunction) {
	return a.Evaluate, a.Gradient
}

type Rastrigin struct {
	A float64
	n int
}

func NewRastrigin(dimensions int) *Rastrigin {
	return &Rastrigin{
		A: 10.0,
		n: dimensions,
	}
}

func (r *Rastrigin) Name() string {
	return "Rastrigin"
}

func (r *Rastrigin) Evaluate(x []float64) float64 {
	n := len(x)
	sum := r.A * float64(n)
	for _, xi := range x {
		sum += xi*xi - r.A*math.Cos(2*math.Pi*xi)
	}
	return sum
}

func (r *Rastrigin) Gradient(x []float64, grad []float64) {
	for i, xi := range x {
		grad[i] = 2*xi + r.A*2*math.Pi*math.Sin(2*math.Pi*xi)
	}
}

func (r *Rastrigin) Minimum() ([]float64, float64) {
	minX := make([]float64, r.n)
	return minX, 0.0
}

func (r *Rastrigin) Dimensions() int {
	return r.n
}

func (r *Rastrigin) ToObjective() (compute.ObjectiveFunction, compute.GradientFunction) {
	return r.Evaluate, r.Gradient
}

type Griewank struct {
	n int
}

func NewGriewank(dimensions int) *Griewank {
	return &Griewank{n: dimensions}
}

func (g *Griewank) Name() string {
	return "Griewank"
}

func (g *Griewank) Evaluate(x []float64) float64 {
	var sum float64
	product := 1.0

	for i, xi := range x {
		sum += xi * xi / 4000.0
		product *= math.Cos(xi / math.Sqrt(float64(i+1)))
	}

	return sum - product + 1.0
}

func (g *Griewank) Gradient(x []float64, grad []float64) {
	for i, xi := range x {
		sumPart := xi / 2000.0

		prodPart := 1.0
		for j, xj := range x {
			if j != i {
				prodPart *= math.Cos(xj / math.Sqrt(float64(j+1)))
			}
		}
		prodPart *= -math.Sin(xi / math.Sqrt(float64(i+1))) / math.Sqrt(float64(i+1))

		grad[i] = sumPart - prodPart
	}
}

func (g *Griewank) Minimum() ([]float64, float64) {
	minX := make([]float64, g.n)
	return minX, 0.0
}

func (g *Griewank) Dimensions() int {
	return g.n
}

func (g *Griewank) ToObjective() (compute.ObjectiveFunction, compute.GradientFunction) {
	return g.Evaluate, g.Gradient
}

type Schwefel struct {
	n int
}

func NewSchwefel(dimensions int) *Schwefel {
	return &Schwefel{n: dimensions}
}

func (s *Schwefel) Name() string {
	return "Schwefel"
}

func (s *Schwefel) Evaluate(x []float64) float64 {
	var sum float64
	for _, xi := range x {
		sum += xi * math.Sin(math.Sqrt(math.Abs(xi)))
	}
	return 418.9829*float64(len(x)) - sum
}

func (s *Schwefel) Gradient(x []float64, grad []float64) {
	for i, xi := range x {
		absXi := math.Abs(xi)
		sqrtAbs := math.Sqrt(absXi)
		sinPart := math.Sin(sqrtAbs)

		sign := 1.0
		if xi < 0 {
			sign = -1.0
		}

		cosPart := math.Cos(sqrtAbs) * (sign) / (2 * sqrtAbs) * xi

		grad[i] = -(sinPart + cosPart)
	}
}

func (s *Schwefel) Minimum() ([]float64, float64) {
	minX := make([]float64, s.n)
	for i := range minX {
		minX[i] = 420.9687
	}
	return minX, 0.0
}

func (s *Schwefel) Dimensions() int {
	return s.n
}

func (s *Schwefel) ToObjective() (compute.ObjectiveFunction, compute.GradientFunction) {
	return s.Evaluate, s.Gradient
}

type Beale struct{}

func NewBeale() *Beale {
	return &Beale{}
}

func (b *Beale) Name() string {
	return "Beale"
}

func (b *Beale) Evaluate(x []float64) float64 {
	return math.Pow(1.5-x[0]+x[0]*x[1], 2) +
		math.Pow(2.25-x[0]+x[0]*x[1]*x[1], 2) +
		math.Pow(2.625-x[0]+x[0]*x[1]*x[1]*x[1], 2)
}

func (b *Beale) Gradient(x []float64, grad []float64) {
	x0, x1 := x[0], x[1]
	x1_2 := x1 * x1
	x1_3 := x1_2 * x1

	term1 := 1.5 - x0 + x0*x1
	term2 := 2.25 - x0 + x0*x1_2
	term3 := 2.625 - x0 + x0*x1_3

	grad[0] = 2*term1*(-1+x1) + 2*term2*(-1+x1_2) + 2*term3*(-1+x1_3)
	grad[1] = 2*term1*x0 + 2*term2*x0*2*x1 + 2*term3*x0*3*x1_2
}

func (b *Beale) Minimum() ([]float64, float64) {
	return []float64{3.0, 0.5}, 0.0
}

func (b *Beale) Dimensions() int {
	return 2
}

func (b *Beale) ToObjective() (compute.ObjectiveFunction, compute.GradientFunction) {
	return b.Evaluate, b.Gradient
}

type Booth struct{}

func NewBooth() *Booth {
	return &Booth{}
}

func (b *Booth) Name() string {
	return "Booth"
}

func (b *Booth) Evaluate(x []float64) float64 {
	return math.Pow(x[0]+2*x[1]-7, 2) + math.Pow(2*x[0]+x[1]-5, 2)
}

func (b *Booth) Gradient(x []float64, grad []float64) {
	term1 := x[0] + 2*x[1] - 7
	term2 := 2*x[0] + x[1] - 5

	grad[0] = 2*term1 + 4*term2
	grad[1] = 4*term1 + 2*term2
}

func (b *Booth) Minimum() ([]float64, float64) {
	return []float64{1.0, 3.0}, 0.0
}

func (b *Booth) Dimensions() int {
	return 2
}

func (b *Booth) ToObjective() (compute.ObjectiveFunction, compute.GradientFunction) {
	return b.Evaluate, b.Gradient
}

type Matyas struct{}

func NewMatyas() *Matyas {
	return &Matyas{}
}

func (m *Matyas) Name() string {
	return "Matyas"
}

func (m *Matyas) Evaluate(x []float64) float64 {
	return 0.26*(x[0]*x[0]+x[1]*x[1]) - 0.48*x[0]*x[1]
}

func (m *Matyas) Gradient(x []float64, grad []float64) {
	grad[0] = 0.52*x[0] - 0.48*x[1]
	grad[1] = 0.52*x[1] - 0.48*x[0]
}

func (m *Matyas) Minimum() ([]float64, float64) {
	return []float64{0.0, 0.0}, 0.0
}

func (m *Matyas) Dimensions() int {
	return 2
}

func (m *Matyas) ToObjective() (compute.ObjectiveFunction, compute.GradientFunction) {
	return m.Evaluate, m.Gradient
}

type Levi struct{}

func NewLevi() *Levi {
	return &Levi{}
}

func (l *Levi) Name() string {
	return "Levi"
}

func (l *Levi) Evaluate(x []float64) float64 {
	sin3pi := math.Sin(3 * math.Pi * x[0])
	sin3piY := math.Sin(3 * math.Pi * x[1])
	term := (x[1] - 1) * (x[1] - 1) * (1 + sin3piY*sin3piY)
	sin2pi := math.Sin(2 * math.Pi * x[1])
	return sin3pi*sin3pi + term + (x[0]-1)*(x[0]-1)*(1+sin2pi*sin2pi)
}

func (l *Levi) Gradient(x []float64, grad []float64) {
	x0, x1 := x[0], x[1]

	sin3piX := math.Sin(3 * math.Pi * x0)
	sin3piY := math.Sin(3 * math.Pi * x1)
	sin2piY := math.Sin(2 * math.Pi * x1)

	dSin3piX := 3 * math.Pi * math.Cos(3*math.Pi*x0)
	dSin3piY := 3 * math.Pi * math.Cos(3*math.Pi*x1)
	dSin2piY := 2 * math.Pi * math.Cos(2*math.Pi*x1)

	grad[0] = 2*sin3piX*dSin3piX + 2*(x0-1)*(1+sin2piY*sin2piY)
	grad[1] = 2*(x1-1)*(1+sin3piY*sin3piY) +
		(x1-1)*(x1-1)*2*sin3piY*dSin3piY +
		(x0-1)*(x0-1)*2*sin2piY*dSin2piY
}

func (l *Levi) Minimum() ([]float64, float64) {
	return []float64{1.0, 1.0}, 0.0
}

func (l *Levi) Dimensions() int {
	return 2
}

func (l *Levi) ToObjective() (compute.ObjectiveFunction, compute.GradientFunction) {
	return l.Evaluate, l.Gradient
}

type ThreeHumpCamel struct{}

func NewThreeHumpCamel() *ThreeHumpCamel {
	return &ThreeHumpCamel{}
}

func (t *ThreeHumpCamel) Name() string {
	return "ThreeHumpCamel"
}

func (t *ThreeHumpCamel) Evaluate(x []float64) float64 {
	return 2*x[0]*x[0] - 1.05*x[0]*x[0]*x[0]*x[0] +
		x[0]*x[0]*x[0]*x[0]*x[0]*x[0]/6.0 +
		x[0]*x[1] + x[1]*x[1]
}

func (t *ThreeHumpCamel) Gradient(x []float64, grad []float64) {
	x0, x1 := x[0], x[1]
	grad[0] = 4*x0 - 4.2*x0*x0*x0 + x0*x0*x0*x0*x0 + x1
	grad[1] = x0 + 2*x1
}

func (t *ThreeHumpCamel) Minimum() ([]float64, float64) {
	return []float64{0.0, 0.0}, 0.0
}

func (t *ThreeHumpCamel) Dimensions() int {
	return 2
}

func (t *ThreeHumpCamel) ToObjective() (compute.ObjectiveFunction, compute.GradientFunction) {
	return t.Evaluate, t.Gradient
}

type FunctionRegistry struct {
	functions map[string]func(int) TestFunction
}

func NewFunctionRegistry() *FunctionRegistry {
	fr := &FunctionRegistry{
		functions: make(map[string]func(int) TestFunction),
	}
	fr.Register("rosenbrock", func(d int) TestFunction { return NewRosenbrock(d) })
	fr.Register("sphere", func(d int) TestFunction { return NewSphere(d) })
	fr.Register("ackley", func(d int) TestFunction { return NewAckley(d) })
	fr.Register("rastrigin", func(d int) TestFunction { return NewRastrigin(d) })
	fr.Register("griewank", func(d int) TestFunction { return NewGriewank(d) })
	fr.Register("schwefel", func(d int) TestFunction { return NewSchwefel(d) })
	return fr
}

func (fr *FunctionRegistry) Register(name string, factory func(int) TestFunction) {
	fr.functions[name] = factory
}

func (fr *FunctionRegistry) Create(name string, dimensions int) (TestFunction, error) {
	factory, ok := fr.functions[name]
	if !ok {
		return nil, nil
	}
	return factory(dimensions), nil
}

func (fr *FunctionRegistry) List() []string {
	names := make([]string, 0, len(fr.functions))
	for name := range fr.functions {
		names = append(names, name)
	}
	return names
}
