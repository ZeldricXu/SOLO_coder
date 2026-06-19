package analysis

import (
	"math"
	"sort"
	"sync"

	"gonum.org/v1/gonum/mat"
	"gonum.org/v1/gonum/stat"
	"gonum.org/v1/gonum/stat/distuv"
)

type SensitivityAnalyzer struct {
	options *AnalysisOptions
}

func NewSensitivityAnalyzer(options *AnalysisOptions) *SensitivityAnalyzer {
	if options == nil {
		options = DefaultAnalysisOptions()
	}
	return &SensitivityAnalyzer{options: options}
}

type SobolSample struct {
	Params     []float64
	Output     float64
	ParamNames []string
}

func (sa *SensitivityAnalyzer) ComputeSobolIndices(samples []*SobolSample) (*SensitivityResult, error) {
	if len(samples) < 10 {
		return nil, nil
	}

	k := len(samples[0].Params)
	n := len(samples)

	paramNames := samples[0].ParamNames
	if paramNames == nil {
		paramNames = make([]string, k)
		for i := 0; i < k; i++ {
			paramNames[i] = string(rune('A' + i))
		}
	}

	outputs := make([]float64, n)
	for i, s := range samples {
		outputs[i] = s.Output
	}

	totalVariance := stat.Variance(outputs, nil)
	if totalVariance == 0 {
		return &SensitivityResult{
			Method:        "sobol",
			OutputMetric:  "output",
			TotalVariance: 0,
		}, nil
	}

	indices := make([]*SensitivityIndex, k)
	var wg sync.WaitGroup
	sem := make(chan struct{}, sa.options.Concurrency)

	for i := 0; i < k; i++ {
		i := i
		sem <- struct{}{}
		wg.Add(1)

		go func() {
			defer wg.Done()
			defer func() { <-sem }()

			firstOrder := sa.computeFirstOrderSobol(samples, i, totalVariance)
			totalOrder := sa.computeTotalSobol(samples, i, totalVariance)

			indices[i] = &SensitivityIndex{
				Parameter:   paramNames[i],
				FirstOrder:  firstOrder,
				TotalOrder:  totalOrder,
				SecondOrder: make(map[string]float64),
			}
		}()
	}

	wg.Wait()

	for i := 0; i < k; i++ {
		for j := i + 1; j < k; j++ {
			secondOrder := sa.computeSecondOrderSobol(samples, i, j, totalVariance)
			indices[i].SecondOrder[paramNames[j]] = secondOrder
			indices[j].SecondOrder[paramNames[i]] = secondOrder
		}
	}

	ranking := sa.rankBySensitivity(indices)

	return &SensitivityResult{
		Method:        "sobol",
		OutputMetric:  "output",
		Indices:       indices,
		Ranking:       ranking,
		TotalVariance: totalVariance,
	}, nil
}

func (sa *SensitivityAnalyzer) computeFirstOrderSobol(samples []*SobolSample, idx int, totalVariance float64) float64 {
	n := len(samples)
	params := make([]float64, n)
	outputs := make([]float64, n)

	for i, s := range samples {
		params[i] = s.Params[idx]
		outputs[i] = s.Output
	}

	meanX := stat.Mean(params, nil)
	meanY := stat.Mean(outputs, nil)

	var covariance, varX float64
	for i := 0; i < n; i++ {
		dX := params[i] - meanX
		dY := outputs[i] - meanY
		covariance += dX * dY
		varX += dX * dX
	}

	covariance /= float64(n - 1)
	varX /= float64(n - 1)

	if varX == 0 {
		return 0
	}

	slope := covariance / varX
	intercept := meanY - slope*meanX

	var explainedVar float64
	for i := 0; i < n; i++ {
		predicted := slope*params[i] + intercept
		explainedVar += math.Pow(predicted-meanY, 2)
	}
	explainedVar /= float64(n - 1)

	return explainedVar / totalVariance
}

func (sa *SensitivityAnalyzer) computeTotalSobol(samples []*SobolSample, idx int, totalVariance float64) float64 {
	n := len(samples)
	k := len(samples[0].Params)

	otherIndices := make([]int, 0, k-1)
	for i := 0; i < k; i++ {
		if i != idx {
			otherIndices = append(otherIndices, i)
		}
	}

	outputs := make([]float64, n)
	for i, s := range samples {
		outputs[i] = s.Output
	}

	meanY := stat.Mean(outputs, nil)

	residuals := make([]float64, n)
	for i := range residuals {
		residuals[i] = outputs[i]
	}

	for _, oi := range otherIndices {
		params := make([]float64, n)
		for i, s := range samples {
			params[i] = s.Params[oi]
		}

		meanX := stat.Mean(params, nil)
		meanY := stat.Mean(residuals, nil)

		var covariance, varX float64
		for i := 0; i < n; i++ {
			dX := params[i] - meanX
			dY := residuals[i] - meanY
			covariance += dX * dY
			varX += dX * dX
		}

		covariance /= float64(n - 1)
		varX /= float64(n - 1)

		if varX > 0 {
			slope := covariance / varX
			intercept := meanY - slope*meanX

			for i := 0; i < n; i++ {
				predicted := slope*params[i] + intercept
				residuals[i] -= predicted
			}
		}
	}

	var residualVar float64
	for _, r := range residuals {
		residualVar += math.Pow(r-meanY, 2)
	}
	residualVar /= float64(n - 1)

	return 1 - residualVar/totalVariance
}

func (sa *SensitivityAnalyzer) computeSecondOrderSobol(samples []*SobolSample, idx1, idx2 int, totalVariance float64) float64 {
	n := len(samples)

	first1 := sa.computeFirstOrderSobol(samples, idx1, totalVariance)
	first2 := sa.computeFirstOrderSobol(samples, idx2, totalVariance)

	params1 := make([]float64, n)
	params2 := make([]float64, n)
	outputs := make([]float64, n)

	for i, s := range samples {
		params1[i] = s.Params[idx1]
		params2[i] = s.Params[idx2]
		outputs[i] = s.Output
	}

	X := mat.NewDense(n, 4, nil)
	for i := 0; i < n; i++ {
		X.Set(i, 0, 1)
		X.Set(i, 1, params1[i])
		X.Set(i, 2, params2[i])
		X.Set(i, 3, params1[i]*params2[i])
	}

	y := mat.NewVecDense(n, outputs)
	var coef mat.VecDense
	if err := coef.SolveVec(X, y); err != nil {
		return 0
	}

	meanY := stat.Mean(outputs, nil)
	var explainedVar float64

	for i := 0; i < n; i++ {
		predicted := coef.At(0, 0) + coef.At(1, 0)*params1[i] + coef.At(2, 0)*params2[i] + coef.At(3, 0)*params1[i]*params2[i]
		explainedVar += math.Pow(predicted-meanY, 2)
	}
	explainedVar /= float64(n - 1)

	totalInteraction := explainedVar/totalVariance - first1 - first2
	return math.Max(0, totalInteraction)
}

func (sa *SensitivityAnalyzer) rankBySensitivity(indices []*SensitivityIndex) []string {
	type indexed struct {
		name  string
		value float64
	}

	pairs := make([]*indexed, len(indices))
	for i, idx := range indices {
		pairs[i] = &indexed{
			name:  idx.Parameter,
			value: idx.TotalOrder,
		}
	}

	sort.Slice(pairs, func(i, j int) bool {
		return pairs[i].value > pairs[j].value
	})

	ranking := make([]string, len(pairs))
	for i, p := range pairs {
		ranking[i] = p.name
	}

	return ranking
}

func (sa *SensitivityAnalyzer) ComputeRegressionSensitivity(samples []*SobolSample) ([]*RegressionSensitivity, error) {
	if len(samples) < 10 {
		return nil, nil
	}

	k := len(samples[0].Params)
	n := len(samples)

	paramNames := samples[0].ParamNames
	if paramNames == nil {
		paramNames = make([]string, k)
		for i := 0; i < k; i++ {
			paramNames[i] = string(rune('A' + i))
		}
	}

	X := mat.NewDense(n, k+1, nil)
	y := make([]float64, n)

	for i, s := range samples {
		X.Set(i, 0, 1)
		for j := 0; j < k; j++ {
			X.Set(i, j+1, s.Params[j])
		}
		y[i] = s.Output
	}

	yVec := mat.NewVecDense(n, y)
	var coef mat.VecDense
	if err := coef.SolveVec(X, yVec); err != nil {
		return nil, err
	}

	var residuals mat.VecDense
	residuals.MulVec(X, &coef)
	residuals.SubVec(yVec, &residuals)

	var rss float64
	for i := 0; i < n; i++ {
		rss += math.Pow(residuals.At(i, 0), 2)
	}
	mse := rss / float64(n-k-1)

	var XtX mat.Dense
	XtX.Mul(X.T(), X)

	var invXtX mat.Dense
	if err := invXtX.Inverse(&XtX); err != nil {
		return nil, err
	}

	stdDevY := stat.StdDev(y, nil)

	results := make([]*RegressionSensitivity, k)
	df := n - k - 1

	for j := 0; j < k; j++ {
		coefVal := coef.At(j+1, 0)
		stdErr := math.Sqrt(mse * invXtX.At(j+1, j+1))

		stdDevX := stat.StdDev(X.ColView(j+1).(*mat.VecDense).RawVector().Data, nil)
		standardized := coefVal * stdDevX / stdDevY

		var tStat, pValue float64
		if stdErr > 0 {
			tStat = coefVal / stdErr
			tDist := distuv.StudentsT{Mu: 0, Sigma: 1, Nu: float64(df)}
			pValue = 2 * (1 - tDist.CDF(math.Abs(tStat)))
		}

		results[j] = &RegressionSensitivity{
			Parameter:    paramNames[j],
			Coefficient:  coefVal,
			StdErr:       stdErr,
			TStat:        tStat,
			PValue:       pValue,
			Standardized: standardized,
			Significant:  pValue < 0.05,
		}
	}

	return results, nil
}

func (sa *SensitivityAnalyzer) ComputeMorrisIndices(samples []*SobolSample, delta float64) (*SensitivityResult, error) {
	if len(samples) < 4 {
		return nil, nil
	}

	k := len(samples[0].Params)
	n := len(samples)

	paramNames := samples[0].ParamNames
	if paramNames == nil {
		paramNames = make([]string, k)
		for i := 0; i < k; i++ {
			paramNames[i] = string(rune('A' + i))
		}
	}

	outputs := make([]float64, n)
	for i, s := range samples {
		outputs[i] = s.Output
	}

	totalVariance := stat.Variance(outputs, nil)

	indices := make([]*SensitivityIndex, k)

	for i := 0; i < k; i++ {
		ee := make([]float64, 0)
		params := make([]float64, n)
		for j, s := range samples {
			params[j] = s.Params[i]
		}

		for j := 0; j < n; j++ {
			for l := j + 1; l < n; l++ {
				if math.Abs(params[j]-params[l]) < delta {
					var otherDiff bool
					for m := 0; m < k; m++ {
						if m != i && math.Abs(samples[j].Params[m]-samples[l].Params[m]) > delta {
							otherDiff = true
							break
						}
					}

					if !otherDiff {
						eeVal := (outputs[l] - outputs[j]) / (params[l] - params[j] + 1e-10)
						ee = append(ee, eeVal)
					}
				}
			}
		}

		if len(ee) > 0 {
			meanEE := stat.Mean(ee, nil)
			stdEE := stat.StdDev(ee, nil)

			indices[i] = &SensitivityIndex{
				Parameter:   paramNames[i],
				FirstOrder:  math.Abs(meanEE),
				TotalOrder:  stdEE,
				SecondOrder: make(map[string]float64),
			}
		} else {
			indices[i] = &SensitivityIndex{
				Parameter:   paramNames[i],
				FirstOrder:  0,
				TotalOrder:  0,
				SecondOrder: make(map[string]float64),
			}
		}
	}

	ranking := sa.rankBySensitivity(indices)

	return &SensitivityResult{
		Method:        "morris",
		OutputMetric:  "output",
		Indices:       indices,
		Ranking:       ranking,
		TotalVariance: totalVariance,
	}, nil
}

func (sa *SensitivityAnalyzer) ComputeANOVASensitivity(groups map[string][]*SobolSample, paramIdx int) (*SensitivityIndex, error) {
	if len(groups) < 2 {
		return nil, nil
	}

	groupValues := make(map[string][]float64)
	for name, samples := range groups {
		values := make([]float64, len(samples))
		for i, s := range samples {
			values[i] = s.Output
		}
		groupValues[name] = values
	}

	fStat, pValue, _, _ := sa.computeANOVA(groupValues)

	paramNames := make([]string, 0, len(groups))
	for name := range groups {
		paramNames = append(paramNames, name)
	}

	sortedGroups := make([]float64, 0)
	for _, v := range groupValues {
		sortedGroups = append(sortedGroups, v...)
	}
	totalVariance := stat.Variance(sortedGroups, nil)

	var etaSquared float64
	if totalVariance > 0 {
		allMeans := make([]float64, 0, len(groupValues))
		allN := make([]int, 0, len(groupValues))
		for _, v := range groupValues {
			allMeans = append(allMeans, stat.Mean(v, nil))
			allN = append(allN, len(v))
		}

		grandMean := stat.Mean(sortedGroups, nil)
		var ssBetween float64
		for i, m := range allMeans {
			ssBetween += float64(allN[i]) * math.Pow(m-grandMean, 2)
		}
		etaSquared = ssBetween / (totalVariance * float64(len(sortedGroups)-1))
	}

	return &SensitivityIndex{
		Parameter:   paramNames[paramIdx],
		FirstOrder:  etaSquared,
		TotalOrder:  fStat,
		SecondOrder: map[string]float64{"p_value": pValue},
	}, nil
}

func (sa *SensitivityAnalyzer) computeANOVA(groups map[string][]float64) (fStat, pValue float64, dfBetween, dfWithin int) {
	stats := NewStatistics(sa.options)
	return stats.ANOVA(groups)
}

func (sa *SensitivityAnalyzer) RankByImportance(results []*RegressionSensitivity) []string {
	type indexed struct {
		name  string
		value float64
	}

	pairs := make([]*indexed, len(results))
	for i, r := range results {
		pairs[i] = &indexed{
			name:  r.Parameter,
			value: math.Abs(r.Standardized),
		}
	}

	sort.Slice(pairs, func(i, j int) bool {
		return pairs[i].value > pairs[j].value
	})

	ranking := make([]string, len(pairs))
	for i, p := range pairs {
		ranking[i] = p.name
	}

	return ranking
}
