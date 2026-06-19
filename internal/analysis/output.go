package analysis

import (
	"encoding/csv"
	"fmt"
	"html/template"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	"github.com/xitongsys/parquet-go-source/local"
	"github.com/xitongsys/parquet-go/writer"
)

type OutputWriter struct {
	config *OutputConfig
}

func NewOutputWriter(config *OutputConfig) *OutputWriter {
	if config == nil {
		config = DefaultOutputConfig()
	}
	return &OutputWriter{config: config}
}

func DefaultOutputConfig() *OutputConfig {
	return &OutputConfig{
		Delimiter:     ",",
		IncludeHeader: true,
		Precision:     6,
		Columns:       DefaultCSVColumns(),
	}
}

func DefaultCSVColumns() []*CSVColumn {
	return []*CSVColumn{
		{Name: "params_hash", Value: func(a *AggregatedResult) interface{} { return a.ParamsHash }},
		{Name: "metric", Value: func(a *AggregatedResult) interface{} { return a.MetricName }},
		{Name: "count", Value: func(a *AggregatedResult) interface{} { return a.Count }},
		{Name: "mean", Value: func(a *AggregatedResult) interface{} { return a.Mean }},
		{Name: "median", Value: func(a *AggregatedResult) interface{} { return a.Median }},
		{Name: "std_dev", Value: func(a *AggregatedResult) interface{} { return a.StdDev }},
		{Name: "std_err", Value: func(a *AggregatedResult) interface{} { return a.StdErr }},
		{Name: "min", Value: func(a *AggregatedResult) interface{} { return a.Min }},
		{Name: "max", Value: func(a *AggregatedResult) interface{} { return a.Max }},
		{Name: "filtered_count", Value: func(a *AggregatedResult) interface{} { return a.FilteredCount }},
	}
}

func (ow *OutputWriter) WriteCSV(results map[string]*AggregatedResult, filePath string) error {
	file, err := os.Create(filePath)
	if err != nil {
		return err
	}
	defer file.Close()

	writer := csv.NewWriter(file)
	writer.Comma = rune(ow.config.Delimiter[0])
	defer writer.Flush()

	if ow.config.IncludeHeader {
		header := make([]string, 0, len(ow.config.Columns))
		for _, col := range ow.config.Columns {
			header = append(header, col.Name)
		}
		if err := writer.Write(header); err != nil {
			return err
		}
	}

	hashes := make([]string, 0, len(results))
	for h := range results {
		hashes = append(hashes, h)
	}
	sort.Strings(hashes)

	for _, hash := range hashes {
		agg := results[hash]
		row := make([]string, 0, len(ow.config.Columns))

		for _, col := range ow.config.Columns {
			val := col.Value(agg)
			row = append(row, ow.formatValue(val))
		}

		if err := writer.Write(row); err != nil {
			return err
		}
	}

	return nil
}

type ParquetRow struct {
	ParamsHash    string  `parquet:"name=params_hash, type=BYTE_ARRAY, convertedtype=UTF8"`
	MetricName    string  `parquet:"name=metric_name, type=BYTE_ARRAY, convertedtype=UTF8"`
	Count         int32   `parquet:"name=count, type=INT32"`
	Mean          float64 `parquet:"name=mean, type=DOUBLE"`
	Median        float64 `parquet:"name=median, type=DOUBLE"`
	Variance      float64 `parquet:"name=variance, type=DOUBLE"`
	StdDev        float64 `parquet:"name=std_dev, type=DOUBLE"`
	StdErr        float64 `parquet:"name=std_err, type=DOUBLE"`
	Min           float64 `parquet:"name=min, type=DOUBLE"`
	Max           float64 `parquet:"name=max, type=DOUBLE"`
	FilteredCount int32   `parquet:"name=filtered_count, type=INT32"`
	P25           float64 `parquet:"name=p25, type=DOUBLE"`
	P50           float64 `parquet:"name=p50, type=DOUBLE"`
	P75           float64 `parquet:"name=p75, type=DOUBLE"`
	P90           float64 `parquet:"name=p90, type=DOUBLE"`
	P95           float64 `parquet:"name=p95, type=DOUBLE"`
	P99           float64 `parquet:"name=p99, type=DOUBLE"`
}

func (ow *OutputWriter) WriteParquet(results map[string]*AggregatedResult, filePath string) error {
	if err := os.MkdirAll(filepath.Dir(filePath), 0755); err != nil {
		return err
	}

	fw, err := local.NewLocalFileWriter(filePath)
	if err != nil {
		return err
	}
	defer fw.Close()

	pw, err := writer.NewParquetWriter(fw, new(ParquetRow), 4)
	if err != nil {
		return err
	}
	defer pw.WriteStop()

	hashes := make([]string, 0, len(results))
	for h := range results {
		hashes = append(hashes, h)
	}
	sort.Strings(hashes)

	for _, hash := range hashes {
		agg := results[hash]

		row := ParquetRow{
			ParamsHash:    agg.ParamsHash,
			MetricName:    agg.MetricName,
			Count:         int32(agg.Count),
			Mean:          agg.Mean,
			Median:        agg.Median,
			Variance:      agg.Variance,
			StdDev:        agg.StdDev,
			StdErr:        agg.StdErr,
			Min:           agg.Min,
			Max:           agg.Max,
			FilteredCount: int32(agg.FilteredCount),
			P25:           agg.Percentiles[25],
			P50:           agg.Percentiles[50],
			P75:           agg.Percentiles[75],
			P90:           agg.Percentiles[90],
			P95:           agg.Percentiles[95],
			P99:           agg.Percentiles[99],
		}

		if err := pw.Write(row); err != nil {
			return err
		}
	}

	return nil
}

func (ow *OutputWriter) GenerateMarkdownReport(
	results map[string]*AggregatedResult,
	summary *BasicStats,
	distStats *DistributionStats,
	ci *ConfidenceInterval,
	config *ReportConfig,
	filePath string,
) error {
	if config == nil {
		config = &ReportConfig{
			Title:       "Analysis Report",
			Format:      "markdown",
			IncludePlots: true,
			IncludeStats: true,
		}
	}

	var sb strings.Builder

	sb.WriteString(fmt.Sprintf("# %s\n\n", config.Title))
	sb.WriteString(fmt.Sprintf("*Generated on %s*\n\n", "2026-06-19"))

	sb.WriteString("## Summary Statistics\n\n")
	sb.WriteString("| Metric | Value |\n")
	sb.WriteString("|--------|-------|\n")
	sb.WriteString(fmt.Sprintf("| Total Samples | %d |\n", summary.Count))
	sb.WriteString(fmt.Sprintf("| Mean | %s |\n", ow.formatFloat(summary.Mean)))
	sb.WriteString(fmt.Sprintf("| Median | %s |\n", ow.formatFloat(summary.Median)))
	sb.WriteString(fmt.Sprintf("| Mode | %s |\n", ow.formatFloat(summary.Mode)))
	sb.WriteString(fmt.Sprintf("| Std Dev | %s |\n", ow.formatFloat(summary.StdDev)))
	sb.WriteString(fmt.Sprintf("| Std Err | %s |\n", ow.formatFloat(summary.StdErr)))
	sb.WriteString(fmt.Sprintf("| Min | %s |\n", ow.formatFloat(summary.Min)))
	sb.WriteString(fmt.Sprintf("| Max | %s |\n", ow.formatFloat(summary.Max)))
	sb.WriteString(fmt.Sprintf("| Range | %s |\n", ow.formatFloat(summary.Max-summary.Min)))
	sb.WriteString("\n")

	if distStats != nil {
		sb.WriteString("## Distribution Statistics\n\n")
		sb.WriteString("| Statistic | Value |\n")
		sb.WriteString("|-----------|-------|\n")
		sb.WriteString(fmt.Sprintf("| Skewness | %s |\n", ow.formatFloat(distStats.Skewness)))
		sb.WriteString(fmt.Sprintf("| Kurtosis | %s |\n", ow.formatFloat(distStats.Kurtosis)))
		sb.WriteString("\n")
	}

	if ci != nil {
		sb.WriteString("## Confidence Interval\n\n")
		sb.WriteString(fmt.Sprintf("**%.0f%% Confidence Interval:**\n\n", float64(ci.Level)*100))
		sb.WriteString("| Metric | Value |\n")
		sb.WriteString("|--------|-------|\n")
		sb.WriteString(fmt.Sprintf("| Mean | %s |\n", ow.formatFloat(ci.Mean)))
		sb.WriteString(fmt.Sprintf("| Lower Bound | %s |\n", ow.formatFloat(ci.Lower)))
		sb.WriteString(fmt.Sprintf("| Upper Bound | %s |\n", ow.formatFloat(ci.Upper)))
		sb.WriteString(fmt.Sprintf("| Margin of Error | %s |\n", ow.formatFloat(ci.Margin)))
		sb.WriteString(fmt.Sprintf("| Distribution | %s |\n", map[bool]string{true: "t-distribution", false: "z-distribution"}[ci.UseTDist]))
		if ci.UseTDist {
			sb.WriteString(fmt.Sprintf("| t-score | %s |\n", ow.formatFloat(ci.TScore)))
		} else {
			sb.WriteString(fmt.Sprintf("| z-score | %s |\n", ow.formatFloat(ci.ZScore)))
		}
		sb.WriteString("\n")
	}

	sb.WriteString("## Aggregated Results\n\n")
	sb.WriteString("| Params Hash | Metric | Count | Mean | Std Dev | Std Err | Min | Max |\n")
	sb.WriteString("|-------------|--------|-------|------|---------|---------|-----|-----|\n")

	hashes := make([]string, 0, len(results))
	for h := range results {
		hashes = append(hashes, h)
	}
	sort.Strings(hashes)

	for _, hash := range hashes {
		agg := results[hash]
		sb.WriteString(fmt.Sprintf("| %s | %s | %d | %s | %s | %s | %s | %s |\n",
			agg.ParamsHash[:16]+"...",
			agg.MetricName,
			agg.Count,
			ow.formatFloat(agg.Mean),
			ow.formatFloat(agg.StdDev),
			ow.formatFloat(agg.StdErr),
			ow.formatFloat(agg.Min),
			ow.formatFloat(agg.Max),
		))
	}

	return os.WriteFile(filePath, []byte(sb.String()), 0644)
}

const htmlReportTemplate = `
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>{{.Title}}</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 40px; background: #f5f5f5; }
        .container { max-width: 1200px; margin: 0 auto; background: white; padding: 40px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        h1 { color: #333; border-bottom: 3px solid #4a90d9; padding-bottom: 10px; }
        h2 { color: #444; margin-top: 30px; }
        table { width: 100%; border-collapse: collapse; margin: 20px 0; }
        th, td { padding: 12px; text-align: left; border-bottom: 1px solid #ddd; }
        th { background: #f8f9fa; font-weight: 600; }
        tr:hover { background: #f5f5f5; }
        .stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 20px; margin: 20px 0; }
        .stat-card { background: #f8f9fa; padding: 20px; border-radius: 8px; border-left: 4px solid #4a90d9; }
        .stat-card h3 { margin: 0; color: #666; font-size: 0.9em; text-transform: uppercase; }
        .stat-card .value { font-size: 1.8em; font-weight: 700; color: #333; margin-top: 5px; }
        .ci { background: #e8f4f8; padding: 20px; border-radius: 8px; margin: 20px 0; }
        .ci-range { font-size: 1.5em; font-weight: 600; color: #2c5282; }
    </style>
</head>
<body>
    <div class="container">
        <h1>{{.Title}}</h1>
        <p style="color: #666; font-style: italic;">Generated on {{.Date}}</p>

        <h2>Summary Statistics</h2>
        <div class="stat-grid">
            <div class="stat-card">
                <h3>Total Samples</h3>
                <div class="value">{{.Summary.Count}}</div>
            </div>
            <div class="stat-card">
                <h3>Mean</h3>
                <div class="value">{{.Summary.Mean}}</div>
            </div>
            <div class="stat-card">
                <h3>Median</h3>
                <div class="value">{{.Summary.Median}}</div>
            </div>
            <div class="stat-card">
                <h3>Std Dev</h3>
                <div class="value">{{.Summary.StdDev}}</div>
            </div>
            <div class="stat-card">
                <h3>Std Err</h3>
                <div class="value">{{.Summary.StdErr}}</div>
            </div>
            <div class="stat-card">
                <h3>Min</h3>
                <div class="value">{{.Summary.Min}}</div>
            </div>
            <div class="stat-card">
                <h3>Max</h3>
                <div class="value">{{.Summary.Max}}</div>
            </div>
            <div class="stat-card">
                <h3>Mode</h3>
                <div class="value">{{.Summary.Mode}}</div>
            </div>
        </div>

        {{if .DistStats}}
        <h2>Distribution Statistics</h2>
        <div class="stat-grid">
            <div class="stat-card">
                <h3>Skewness</h3>
                <div class="value">{{.DistStats.Skewness}}</div>
            </div>
            <div class="stat-card">
                <h3>Kurtosis</h3>
                <div class="value">{{.DistStats.Kurtosis}}</div>
            </div>
        </div>
        {{end}}

        {{if .CI}}
        <h2>Confidence Interval</h2>
        <div class="ci">
            <h3>{{.CI.Level}}% Confidence Interval</h3>
            <div class="ci-range">[{{.CI.Lower}}, {{.CI.Upper}}]</div>
            <p>Mean: {{.CI.Mean}} ± {{.CI.Margin}}</p>
            <p>Using {{if .CI.UseTDist}}t-distribution (t-score: {{.CI.TScore}}){{else}}z-distribution (z-score: {{.CI.ZScore}}){{end}}</p>
        </div>
        {{end}}

        <h2>Aggregated Results</h2>
        <table>
            <thead>
                <tr>
                    <th>Params Hash</th>
                    <th>Metric</th>
                    <th>Count</th>
                    <th>Mean</th>
                    <th>Std Dev</th>
                    <th>Std Err</th>
                    <th>Min</th>
                    <th>Max</th>
                </tr>
            </thead>
            <tbody>
                {{range .Results}}
                <tr>
                    <td>{{.ParamsHash}}</td>
                    <td>{{.MetricName}}</td>
                    <td>{{.Count}}</td>
                    <td>{{.Mean}}</td>
                    <td>{{.StdDev}}</td>
                    <td>{{.StdErr}}</td>
                    <td>{{.Min}}</td>
                    <td>{{.Max}}</td>
                </tr>
                {{end}}
            </tbody>
        </table>
    </div>
</body>
</html>
`

type HTMLTemplateData struct {
	Title     string
	Date      string
	Summary   *BasicStats
	DistStats *DistributionStats
	CI        *ConfidenceInterval
	Results   []*AggregatedResult
}

func (ow *OutputWriter) GenerateHTMLReport(
	results map[string]*AggregatedResult,
	summary *BasicStats,
	distStats *DistributionStats,
	ci *ConfidenceInterval,
	config *ReportConfig,
	filePath string,
) error {
	if config == nil {
		config = &ReportConfig{
			Title:       "Analysis Report",
			Format:      "html",
			IncludePlots: true,
			IncludeStats: true,
		}
	}

	tmpl, err := template.New("report").Parse(htmlReportTemplate)
	if err != nil {
		return err
	}

	resultsList := make([]*AggregatedResult, 0, len(results))
	for _, r := range results {
		resultsList = append(resultsList, r)
	}

	sort.Slice(resultsList, func(i, j int) bool {
		return resultsList[i].ParamsHash < resultsList[j].ParamsHash
	})

	formattedSummary := &BasicStats{
		Mean:     summary.Mean,
		Median:   summary.Median,
		Mode:     summary.Mode,
		Variance: summary.Variance,
		StdDev:   summary.StdDev,
		StdErr:   summary.StdErr,
		Min:      summary.Min,
		Max:      summary.Max,
		Count:    summary.Count,
	}

	data := HTMLTemplateData{
		Title:     config.Title,
		Date:      "2026-06-19",
		Summary:   formattedSummary,
		DistStats: distStats,
		CI:        ci,
		Results:   resultsList,
	}

	file, err := os.Create(filePath)
	if err != nil {
		return err
	}
	defer file.Close()

	return tmpl.Execute(file, data)
}

func (ow *OutputWriter) formatValue(v interface{}) string {
	switch val := v.(type) {
	case float64:
		return ow.formatFloat(val)
	case float32:
		return ow.formatFloat(float64(val))
	case int:
		return strconv.Itoa(val)
	case int32:
		return strconv.FormatInt(int64(val), 10)
	case int64:
		return strconv.FormatInt(val, 10)
	case string:
		return val
	case bool:
		return strconv.FormatBool(val)
	default:
		return fmt.Sprintf("%v", val)
	}
}

func (ow *OutputWriter) formatFloat(v float64) string {
	return strconv.FormatFloat(v, 'f', ow.config.Precision, 64)
}

func (ow *OutputWriter) WriteSensitivityCSV(result *SensitivityResult, filePath string) error {
	file, err := os.Create(filePath)
	if err != nil {
		return err
	}
	defer file.Close()

	writer := csv.NewWriter(file)
	writer.Comma = rune(ow.config.Delimiter[0])
	defer writer.Flush()

	header := []string{"parameter", "first_order", "total_order", "rank"}
	if err := writer.Write(header); err != nil {
		return err
	}

	rankMap := make(map[string]int)
	for i, name := range result.Ranking {
		rankMap[name] = i + 1
	}

	for _, idx := range result.Indices {
		row := []string{
			idx.Parameter,
			ow.formatFloat(idx.FirstOrder),
			ow.formatFloat(idx.TotalOrder),
			strconv.Itoa(rankMap[idx.Parameter]),
		}
		if err := writer.Write(row); err != nil {
			return err
		}
	}

	return nil
}

func (ow *OutputWriter) WriteRegressionSensitivityCSV(results []*RegressionSensitivity, filePath string) error {
	file, err := os.Create(filePath)
	if err != nil {
		return err
	}
	defer file.Close()

	writer := csv.NewWriter(file)
	writer.Comma = rune(ow.config.Delimiter[0])
	defer writer.Flush()

	header := []string{"parameter", "coefficient", "std_err", "t_stat", "p_value", "standardized", "significant"}
	if err := writer.Write(header); err != nil {
		return err
	}

	for _, r := range results {
		row := []string{
			r.Parameter,
			ow.formatFloat(r.Coefficient),
			ow.formatFloat(r.StdErr),
			ow.formatFloat(r.TStat),
			ow.formatFloat(r.PValue),
			ow.formatFloat(r.Standardized),
			strconv.FormatBool(r.Significant),
		}
		if err := writer.Write(row); err != nil {
			return err
		}
	}

	return nil
}
