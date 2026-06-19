package analysis

import (
	"math"
	"sort"
	"sync"

	"gonum.org/v1/gonum/floats"
	"gonum.org/v1/gonum/mat"
	"gonum.org/v1/gonum/stat"
	"gonum.org/v1/gonum/stat/distuv"
)

type Statistics struct {
	options *AnalysisOptions
}

func NewStatistics(options *AnalysisOptions) *Statistics {
	if options == nil {
		options = DefaultAnalysisOptions()
	}
	return &Statistics{options: options}
}

func (s *Statistics) ComputeBasicStats(values []float64) *BasicStats {
	if len(values) == 0 {
		return &BasicStats{}
	}

	sorted := make([]float64, len(values))
	copy(sorted, values)
	sort.Float64s(sorted)

	mean := stat.Mean(values, nil)
	variance := stat.Variance(values, nil)
	stdDev := math.Sqrt(variance)
	mode, _ := stat.Mode(values, nil)

	return &BasicStats{
		Mean:     mean,
		Median:   stat.Quantile(0.5, stat.Empirical, sorted, nil),
		Mode:     mode,
		Variance: variance,
		StdDev:   stdDev,
		StdErr:   stdDev / math.Sqrt(float64(len(values))),
		Min:      floats.Min(values),
		Max:      floats.Max(values),
		Count:    len(values),
	}
}

func (s *Statistics) ComputePercentiles(values []float64, percentiles []float64) []*PercentileResult {
	if len(values) == 0 {
		return nil
	}

	sorted := make([]float64, len(values))
	copy(sorted, values)
	sort.Float64s(sorted)

	results := make([]*PercentileResult, 0, len(percentiles))
	for _, p := range percentiles {
		results = append(results, &PercentileResult{
			Percentile: p,
			Value:      stat.Quantile(p/100.0, stat.Empirical, sorted, nil),
		})
	}

	return results
}

func (s *Statistics) ComputeConfidenceInterval(values []float64, level ConfidenceLevel) *ConfidenceInterval {
	if len(values) < 2 {
		return nil
	}

	mean := stat.Mean(values, nil)
	stdDev := stat.StdDev(values, nil)
	n := float64(len(values))
	df := n - 1

	alpha := 1.0 - float64(level)
	alphaHalf := alpha / 2.0

	useTDist := len(values) < 30

	var margin, zScore, tScore float64
	if useTDist {
		tDist := distuv.StudentsT{Mu: 0, Sigma: 1, Nu: df}
		tScore = math.Abs(tDist.Quantile(alphaHalf))
		margin = tScore * stdDev / math.Sqrt(n)
	} else {
		normDist := distuv.Normal{Mu: 0, Sigma: 1}
		zScore = math.Abs(normDist.Quantile(alphaHalf))
		margin = zScore * stdDev / math.Sqrt(n)
	}

	return &ConfidenceInterval{
		Level:    level,
		Lower:    mean - margin,
		Upper:    mean + margin,
		Mean:     mean,
		Margin:   margin,
		ZScore:   zScore,
		TScore:   tScore,
		UseTDist: useTDist,
	}
}

func (s *Statistics) ComputeDistributionStats(values []float64) *DistributionStats {
	if len(values) < 4 {
		return &DistributionStats{}
	}

	mean := stat.Mean(values, nil)
	stdDev := stat.StdDev(values, nil)

	if stdDev == 0 {
		return &DistributionStats{Skewness: 0, Kurtosis: 0}
	}

	n := float64(len(values))

	var skewness, kurtosis float64
	for _, v := range values {
		dev := (v - mean) / stdDev
		skewness += math.Pow(dev, 3)
		kurtosis += math.Pow(dev, 4)
	}

	skewness *= n / ((n - 1) * (n - 2))
	kurtosis = kurtosis*n*(n+1)/((n-1)*(n-2)*(n-3)) - 3*(n-1)*(n-1)/((n-2)*(n-3))

	return &DistributionStats{
		Skewness: skewness,
		Kurtosis: kurtosis,
	}
}

func (s *Statistics) ComputeCovarianceMatrix(data map[string][]float64) (*MatrixData, error) {
	names := make([]string, 0, len(data))
	for name := range data {
		names = append(names, name)
	}
	sort.Strings(names)

	n := len(names)
	m := 0
	for _, v := range data {
		if len(v) > m {
			m = len(v)
		}
	}

	matrix := mat.NewDense(m, n, nil)
	for j, name := range names {
		values := data[name]
		for i := 0; i < m; i++ {
			if i < len(values) {
				matrix.Set(i, j, values[i])
			} else {
				matrix.Set(i, j, 0)
			}
		}
	}

	var cov mat.SymDense
	stat.CovarianceMatrix(&cov, matrix, nil)

	r, c := cov.Dims()
	covDense := mat.NewDense(r, c, nil)
	for i := 0; i < r; i++ {
		for j := 0; j < c; j++ {
			covDense.Set(i, j, cov.At(i, j))
		}
	}

	return &MatrixData{
		Dense:     covDense,
		RowLabels: names,
		ColLabels: names,
	}, nil
}

func (s *Statistics) ComputeCorrelationMatrix(data map[string][]float64) (*MatrixData, error) {
	names := make([]string, 0, len(data))
	for name := range data {
		names = append(names, name)
	}
	sort.Strings(names)

	n := len(names)
	m := 0
	for _, v := range data {
		if len(v) > m {
			m = len(v)
		}
	}

	matrix := mat.NewDense(m, n, nil)
	for j, name := range names {
		values := data[name]
		for i := 0; i < m; i++ {
			if i < len(values) {
				matrix.Set(i, j, values[i])
			} else {
				matrix.Set(i, j, 0)
			}
		}
	}

	var corr mat.SymDense
	stat.CorrelationMatrix(&corr, matrix, nil)

	r, c := corr.Dims()
	corrDense := mat.NewDense(r, c, nil)
	for i := 0; i < r; i++ {
		for j := 0; j < c; j++ {
			corrDense.Set(i, j, corr.At(i, j))
		}
	}

	return &MatrixData{
		Dense:     corrDense,
		RowLabels: names,
		ColLabels: names,
	}, nil
}

func (s *Statistics) ComputeCorrelation(x, y []float64) *CorrelationResult {
	if len(x) != len(y) || len(x) < 3 {
		return nil
	}

	corr := stat.Correlation(x, y, nil)
	n := len(x)
	df := n - 2

	tStat := corr * math.Sqrt(float64(df)/float64(1-corr*corr))
	tDist := distuv.StudentsT{Mu: 0, Sigma: 1, Nu: float64(df)}
	pValue := 2 * (1 - tDist.CDF(math.Abs(tStat)))

	return &CorrelationResult{
		Variable1:   "x",
		Variable2:   "y",
		Correlation: corr,
		PValue:      pValue,
		Significant: pValue < 0.05,
	}
}

func (s *Statistics) ComputeCovariance(x, y []float64) *CovarianceResult {
	if len(x) != len(y) || len(x) < 2 {
		return nil
	}

	cov := stat.Covariance(x, y, nil)

	return &CovarianceResult{
		Variable1:  "x",
		Variable2:  "y",
		Covariance: cov,
	}
}

func (s *Statistics) ComputeMultipleCorrelations(data map[string][]float64, target string) []*CorrelationResult {
	targetValues, ok := data[target]
	if !ok {
		return nil
	}

	results := make([]*CorrelationResult, 0, len(data)-1)
	var wg sync.WaitGroup
	var mu sync.Mutex
	sem := make(chan struct{}, s.options.Concurrency)

	for name, values := range data {
		if name == target {
			continue
		}

		name := name
		values := values
		sem <- struct{}{}
		wg.Add(1)

		go func() {
			defer wg.Done()
			defer func() { <-sem }()

			if corr := s.ComputeCorrelation(targetValues, values); corr != nil {
				corr.Variable1 = target
				corr.Variable2 = name

				mu.Lock()
				results = append(results, corr)
				mu.Unlock()
			}
		}()
	}

	wg.Wait()
	return results
}

func (s *Statistics) TTestOneSample(values []float64, testMean float64) (tStat, pValue float64, df int) {
	if len(values) < 2 {
		return 0, 1, 0
	}

	mean := stat.Mean(values, nil)
	stdDev := stat.StdDev(values, nil)
	n := len(values)
	df = n - 1

	standardError := stdDev / math.Sqrt(float64(n))
	tStat = (mean - testMean) / standardError

	tDist := distuv.StudentsT{Mu: 0, Sigma: 1, Nu: float64(df)}
	pValue = 2 * (1 - tDist.CDF(math.Abs(tStat)))

	return tStat, pValue, df
}

func (s *Statistics) TTestTwoSample(x, y []float64, equalVariance bool) (tStat, pValue float64, df int) {
	if len(x) < 2 || len(y) < 2 {
		return 0, 1, 0
	}

	meanX := stat.Mean(x, nil)
	meanY := stat.Mean(y, nil)
	varX := stat.Variance(x, nil)
	varY := stat.Variance(y, nil)
	nX := float64(len(x))
	nY := float64(len(y))

	var se, pooledVar float64
	if equalVariance {
		df = int(nX + nY - 2)
		pooledVar = ((nX-1)*varX + (nY-1)*varY) / float64(df)
		se = math.Sqrt(pooledVar * (1/nX + 1/nY))
	} else {
		se = math.Sqrt(varX/nX + varY/nY)
		dfNum := math.Pow(varX/nX+varY/nY, 2)
		dfDen := math.Pow(varX/nX, 2)/(nX-1) + math.Pow(varY/nY, 2)/(nY-1)
		df = int(dfNum / dfDen)
	}

	tStat = (meanX - meanY) / se
	tDist := distuv.StudentsT{Mu: 0, Sigma: 1, Nu: float64(df)}
	pValue = 2 * (1 - tDist.CDF(math.Abs(tStat)))

	return tStat, pValue, df
}

func (s *Statistics) ANOVA(groups map[string][]float64) (fStat, pValue float64, dfBetween, dfWithin int) {
	if len(groups) < 2 {
		return 0, 1, 0, 0
	}

	allValues := make([]float64, 0)
	for _, values := range groups {
		allValues = append(allValues, values...)
	}

	grandMean := stat.Mean(allValues, nil)
	totalN := len(allValues)
	k := len(groups)

	var ssBetween, ssWithin float64
	dfBetween = k - 1
	dfWithin = totalN - k

	for _, values := range groups {
		groupMean := stat.Mean(values, nil)
		n := float64(len(values))
		ssBetween += n * math.Pow(groupMean-grandMean, 2)

		for _, v := range values {
			ssWithin += math.Pow(v-groupMean, 2)
		}
	}

	msBetween := ssBetween / float64(dfBetween)
	msWithin := ssWithin / float64(dfWithin)

	if msWithin == 0 {
		return 0, 1, dfBetween, dfWithin
	}

	fStat = msBetween / msWithin
	fDist := distuv.F{D1: float64(dfBetween), D2: float64(dfWithin)}
	pValue = 1 - fDist.CDF(fStat)

	return fStat, pValue, dfBetween, dfWithin
}

func (s *Statistics) MannWhitneyU(x, y []float64) (u, z, pValue float64) {
	if len(x) == 0 || len(y) == 0 {
		return 0, 0, 1
	}

	allData := make([]struct {
		value float64
		group int
		rank  float64
	}, 0, len(x)+len(y))

	for _, v := range x {
		allData = append(allData, struct {
			value float64
			group int
			rank  float64
		}{v, 0, 0})
	}

	for _, v := range y {
		allData = append(allData, struct {
			value float64
			group int
			rank  float64
		}{v, 1, 0})
	}

	sort.Slice(allData, func(i, j int) bool {
		return allData[i].value < allData[j].value
	})

	n := len(allData)
	for i := 0; i < n; {
		j := i
		for j < n && allData[j].value == allData[i].value {
			j++
		}

		rank := float64(i+j+1) / 2.0
		for k := i; k < j; k++ {
			allData[k].rank = rank
		}

		i = j
	}

	var sumRanks0, sumRanks1 float64
	for _, d := range allData {
		if d.group == 0 {
			sumRanks0 += d.rank
		} else {
			sumRanks1 += d.rank
		}
	}

	n1 := float64(len(x))
	n2 := float64(len(y))

	u1 := sumRanks0 - n1*(n1+1)/2
	u2 := sumRanks1 - n2*(n2+1)/2
	u = math.Min(u1, u2)

	meanU := n1 * n2 / 2
	varU := n1 * n2 * (n1 + n2 + 1) / 12

	if varU == 0 {
		z = 0
	} else {
		z = (u - meanU) / math.Sqrt(varU)
	}

	normDist := distuv.Normal{Mu: 0, Sigma: 1}
	pValue = 2 * (1 - normDist.CDF(math.Abs(z)))

	return u, z, pValue
}
