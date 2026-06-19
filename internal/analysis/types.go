package analysis

import (
	"time"

	"github.com/df1-96/experiment/internal/models"
	"gonum.org/v1/gonum/mat"
)

type MetricType string

const (
	MetricTypeMean       MetricType = "mean"
	MetricTypeMedian     MetricType = "median"
	MetricTypeMode       MetricType = "mode"
	MetricTypeStdDev     MetricType = "std_dev"
	MetricTypeVariance   MetricType = "variance"
	MetricTypeStdErr     MetricType = "std_err"
	MetricTypeMin        MetricType = "min"
	MetricTypeMax        MetricType = "max"
	MetricTypePercentile MetricType = "percentile"
)

type OutlierMethod string

const (
	OutlierMethodThreeSigma OutlierMethod = "three_sigma"
	OutlierMethodIQR        OutlierMethod = "iqr"
)

type AggregateMethod string

const (
	AggregateMethodMean   AggregateMethod = "mean"
	AggregateMethodMedian AggregateMethod = "median"
	AggregateMethodSum    AggregateMethod = "sum"
)

type ConfidenceLevel float64

const (
	Confidence90 ConfidenceLevel = 0.90
	Confidence95 ConfidenceLevel = 0.95
	Confidence99 ConfidenceLevel = 0.99
)

type ResultWithParams struct {
	Result     *models.Result
	Task       *models.Task
	Params     models.Params
	ParamsHash string
	Values     map[string]float64
}

type AggregatedResult struct {
	ParamsHash    string
	Params        models.Params
	MetricName    string
	Count         int
	Mean          float64
	Median        float64
	Variance      float64
	StdDev        float64
	StdErr        float64
	Min           float64
	Max           float64
	Percentiles   map[float64]float64
	RawValues     []float64
	FilteredCount int
}

type BasicStats struct {
	Mean     float64
	Median   float64
	Mode     float64
	Variance float64
	StdDev   float64
	StdErr   float64
	Min      float64
	Max      float64
	Count    int
}

type PercentileResult struct {
	Percentile float64
	Value      float64
}

type ConfidenceInterval struct {
	Level    ConfidenceLevel
	Lower    float64
	Upper    float64
	Mean     float64
	Margin   float64
	ZScore   float64
	TScore   float64
	UseTDist bool
}

type DistributionStats struct {
	Skewness float64
	Kurtosis float64
}

type CorrelationResult struct {
	Variable1   string
	Variable2   string
	Correlation float64
	PValue      float64
	Significant bool
}

type CovarianceResult struct {
	Variable1  string
	Variable2  string
	Covariance float64
}

type SensitivityIndex struct {
	Parameter   string
	FirstOrder  float64
	TotalOrder  float64
	SecondOrder map[string]float64
	Confidence  float64
}

type SensitivityResult struct {
	Method        string
	OutputMetric  string
	Indices       []*SensitivityIndex
	Ranking       []string
	TotalVariance float64
}

type RegressionSensitivity struct {
	Parameter    string
	Coefficient  float64
	StdErr       float64
	TStat        float64
	PValue       float64
	Standardized float64
	Significant  bool
}

type HeatmapData struct {
	XLabels []string
	YLabels []string
	Values  [][]float64
	Metric  string
	XAxis   string
	YAxis   string
}

type ScatterPoint struct {
	X     float64
	Y     float64
	Label string
	Color float64
}

type ScatterData struct {
	Points []*ScatterPoint
	XLabel string
	YLabel string
	Title  string
}

type ConvergencePoint struct {
	Iteration int64
	Value     float64
	Metric    string
}

type ConvergenceData struct {
	Points  []*ConvergencePoint
	Metric  string
	Running []float64
}

type BarData struct {
	Labels []string
	Values []float64
	Errors []float64
	Title  string
	YLabel string
}

type CSVColumn struct {
	Name     string
	Value    func(*AggregatedResult) interface{}
	Optional bool
}

type OutputConfig struct {
	Delimiter     string
	IncludeHeader bool
	Precision     int
	Columns       []*CSVColumn
}

type ReportConfig struct {
	Title        string
	Format       string
	IncludePlots bool
	IncludeStats bool
}

type AnalysisOptions struct {
	OutlierMethod   OutlierMethod
	OutlierEnabled  bool
	AggregateMethod AggregateMethod
	Concurrency     int
	ConfidenceLevel ConfidenceLevel
	Percentiles     []float64
}

func DefaultAnalysisOptions() *AnalysisOptions {
	return &AnalysisOptions{
		OutlierMethod:   OutlierMethodIQR,
		OutlierEnabled:  true,
		AggregateMethod: AggregateMethodMean,
		Concurrency:     4,
		ConfidenceLevel: Confidence95,
		Percentiles:     []float64{25, 50, 75, 90, 95, 99},
	}
}

type AnalysisContext struct {
	Experiment *models.Experiment
	Tasks      []*models.Task
	Results    []*models.Result
	StartTime  time.Time
	EndTime    time.Time
	Options    *AnalysisOptions
	Cache      map[string]interface{}
}

type MatrixData struct {
	*mat.Dense
	RowLabels []string
	ColLabels []string
}
