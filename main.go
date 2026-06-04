//go:build js && wasm



package main

import (
	"encoding/json"
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"
	"syscall/js"
	"time"

	"github.com/dataexplorer/anomaly"
	"github.com/dataexplorer/chart"
	"github.com/dataexplorer/export"
	"github.com/dataexplorer/parser"
	"github.com/dataexplorer/pivot"
	"github.com/dataexplorer/persist"
	"github.com/dataexplorer/query"
	"github.com/dataexplorer/store"
)

var (
	currentTable  *store.Table
	indexManager  *store.IndexManager
	projectState  *persist.ProjectState
	tables        map[string]*store.Table
)

func main() {
	js.Global().Set("wasmReady", js.ValueOf(true))
	js.Global().Set("parseData", js.FuncOf(parseData))
	js.Global().Set("getSchema", js.FuncOf(getSchema))
	js.Global().Set("getSummary", js.FuncOf(getSummary))
	js.Global().Set("getData", js.FuncOf(getData))
	js.Global().Set("executeQuery", js.FuncOf(executeQuery))
	js.Global().Set("buildIndex", js.FuncOf(buildIndex))
	js.Global().Set("generateChart", js.FuncOf(generateChart))
	js.Global().Set("generateBrushChart", js.FuncOf(generateBrushChart))
	js.Global().Set("generateMultiSeriesChart", js.FuncOf(generateMultiSeriesChart))
	js.Global().Set("buildPivotTable", js.FuncOf(buildPivotTable))
	js.Global().Set("detectAnomalies", js.FuncOf(detectAnomalies))
	js.Global().Set("getAnomalyHighlight", js.FuncOf(getAnomalyHighlight))
	js.Global().Set("exportCSV", js.FuncOf(exportCSVData))
	js.Global().Set("exportJSON", js.FuncOf(exportJSONData))
	js.Global().Set("exportExcel", js.FuncOf(exportExcelData))
	js.Global().Set("exportChartPNG", js.FuncOf(exportChartPNG))
	js.Global().Set("exportChartSVG", js.FuncOf(exportChartSVG))
	js.Global().Set("saveProject", js.FuncOf(saveProjectState))
	js.Global().Set("loadProject", js.FuncOf(loadProjectState))
	js.Global().Set("listProjects", js.FuncOf(listProjects))
	js.Global().Set("deleteProject", js.FuncOf(deleteProjectState))
	js.Global().Set("getFilterValues", js.FuncOf(getFilterValues))
	js.Global().Set("applyFilter", js.FuncOf(applyFilter))
	js.Global().Set("getColumnStats", js.FuncOf(getColumnStats))
	js.Global().Set("getHistogram", js.FuncOf(getHistogram))
	js.Global().Set("getValueDistribution", js.FuncOf(getValueDistribution))
	js.Global().Set("filtersToQuery", js.FuncOf(filtersToQuery))
	js.Global().Set("registerTable", js.FuncOf(registerTable))
	js.Global().Set("executeJoinQuery", js.FuncOf(executeJoinQuery))

	indexManager = store.NewIndexManager()
	tables = make(map[string]*store.Table)

	fmt.Println("DataExplorer WASM module initialized")

	select {}
}

func parseData(this js.Value, args []js.Value) interface{} {
	if len(args) < 2 {
		return errorResult("parseData requires data and format arguments")
	}

	data := args[0].String()
	format := args[1].String()

	p := parser.NewParser()
	result := p.Parse([]byte(data), format, "main_table")

	if result.Table != nil {
		currentTable = result.Table
		tables["main_table"] = result.Table
		for _, col := range currentTable.Columns {
			if col.DataType == store.TypeString || col.DataType == store.TypeBool {
				indexManager.BuildIndex(col)
			}
		}
	}

	type parseResponse struct {
		Success   bool          `json:"success"`
		RowCount  int           `json:"rowCount"`
		ColCount  int           `json:"colCount"`
		Columns   []string      `json:"columns"`
		Types     []string      `json:"types"`
		ValidRows int           `json:"validRows"`
		DirtyRows int           `json:"dirtyRows"`
		ParseTime int64         `json:"parseTimeMs"`
		Errors    []string      `json:"errors"`
	}

	resp := parseResponse{
		Success:   result.Table != nil,
		RowCount:  result.Stats.TotalRows,
		ColCount:  result.Stats.Columns,
		ValidRows: result.Stats.ValidRows,
		DirtyRows: result.Stats.DirtyRows,
		ParseTime: result.Stats.ParseTimeMs,
	}

	if result.Table != nil {
		resp.Columns = result.Table.ColumnNames()
		for _, col := range result.Table.Columns {
			resp.Types = append(resp.Types, col.DataType.String())
		}
	}

	for _, e := range result.Errors {
		resp.Errors = append(resp.Errors, e.Message)
	}

	b, _ := json.Marshal(resp)
	return string(b)
}

func getSchema(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}
	return currentTable.SchemaJSON()
}

func getSummary(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}
	return currentTable.SummaryJSON()
}

func getData(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}
	offset := 0
	limit := 100
	if len(args) > 0 {
		offset = args[0].Int()
	}
	if len(args) > 1 {
		limit = args[1].Int()
	}
	return currentTable.ToJSONString(offset, limit)
}

func executeQuery(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}
	if len(args) < 1 {
		return errorResult("query string required")
	}

	queryStr := args[0].String()
	stmt, err := query.NewParser().Parse(queryStr)
	if err != nil {
		return errorResult("parse error: " + err.Error())
	}

	executor := query.NewExecutor(indexManager)
	result, err := executor.Execute(currentTable, stmt)
	if err != nil {
		return errorResult("execution error: " + err.Error())
	}

	type queryResult struct {
		Success  bool     `json:"success"`
		RowCount int      `json:"rowCount"`
		Columns  []string `json:"columns"`
		Data     string   `json:"data"`
	}

	data := result.ToJSONString(0, 10000)
	resp := queryResult{
		Success:  true,
		RowCount: result.RowCount,
		Columns:  result.ColumnNames(),
		Data:     data,
	}

	b, _ := json.Marshal(resp)
	return string(b)
}

func buildIndex(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}
	if len(args) < 1 {
		return errorResult("column name required")
	}

	colName := args[0].String()
	col := currentTable.GetColumn(colName)
	if col == nil {
		return errorResult("column not found: " + colName)
	}

	indexManager.BuildIndex(col)

	return `{"success":true,"message":"index built for ` + colName + `"}`
}

func generateChart(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}
	if len(args) < 1 {
		return errorResult("chart config required")
	}

	var config chart.ChartConfig
	if err := json.Unmarshal([]byte(args[0].String()), &config); err != nil {
		return errorResult("invalid chart config: " + err.Error())
	}

	spec, err := chart.GenerateSpec(currentTable, config)
	if err != nil {
		return errorResult("chart generation error: " + err.Error())
	}

	return spec
}

func generateBrushChart(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}
	if len(args) < 1 {
		return errorResult("chart config required")
	}

	var config chart.ChartConfig
	if err := json.Unmarshal([]byte(args[0].String()), &config); err != nil {
		return errorResult("invalid chart config: " + err.Error())
	}

	spec, err := chart.GenerateBrushSpec(currentTable, config)
	if err != nil {
		return errorResult("chart generation error: " + err.Error())
	}

	return spec
}

func generateMultiSeriesChart(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}
	if len(args) < 2 {
		return errorResult("chart config and series field required")
	}

	var config chart.ChartConfig
	if err := json.Unmarshal([]byte(args[0].String()), &config); err != nil {
		return errorResult("invalid chart config: " + err.Error())
	}

	seriesField := args[1].String()
	spec, err := chart.GenerateMultiSeriesSpec(currentTable, config, seriesField)
	if err != nil {
		return errorResult("chart generation error: " + err.Error())
	}

	return spec
}

func buildPivotTable(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}
	if len(args) < 1 {
		return errorResult("pivot config required")
	}

	var config pivot.PivotConfig
	if err := json.Unmarshal([]byte(args[0].String()), &config); err != nil {
		return errorResult("invalid pivot config: " + err.Error())
	}

	result, err := pivot.NewPivotTable(currentTable, config)
	if err != nil {
		return errorResult("pivot error: " + err.Error())
	}

	return result.ToJSON()
}

func detectAnomalies(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}
	if len(args) < 1 {
		return errorResult("anomaly config required")
	}

	var config anomaly.AnomalyConfig
	if err := json.Unmarshal([]byte(args[0].String()), &config); err != nil {
		return errorResult("invalid anomaly config: " + err.Error())
	}

	result, err := anomaly.Detect(currentTable, config)
	if err != nil {
		return errorResult("anomaly detection error: " + err.Error())
	}

	return result.ToJSON()
}

func getAnomalyHighlight(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}
	if len(args) < 2 {
		return errorResult("anomaly config and chart type required")
	}

	var config anomaly.AnomalyConfig
	if err := json.Unmarshal([]byte(args[0].String()), &config); err != nil {
		return errorResult("invalid anomaly config: " + err.Error())
	}

	chartType := args[1].String()

	result, err := anomaly.Detect(currentTable, config)
	if err != nil {
		return errorResult("anomaly detection error: " + err.Error())
	}

	return anomaly.HighlightSpec(currentTable, result, chartType)
}

func exportCSVData(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}

	data, err := export.ExportCSV(currentTable)
	if err != nil {
		return errorResult("export error: " + err.Error())
	}

	js.Global().Call("downloadFile", "export.csv", "text/csv", string(data))
	return `{"success":true}`
}

func exportJSONData(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}

	data, err := export.ExportJSON(currentTable)
	if err != nil {
		return errorResult("export error: " + err.Error())
	}

	js.Global().Call("downloadFile", "export.json", "application/json", string(data))
	return `{"success":true}`
}

func exportExcelData(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}

	data, err := export.ExportExcel(currentTable)
	if err != nil {
		return errorResult("export error: " + err.Error())
	}

	js.Global().Call("downloadFile", "export.xls", "application/vnd.ms-excel", string(data))
	return `{"success":true}`
}

func exportChartPNG(this js.Value, args []js.Value) interface{} {
	if len(args) < 1 {
		return errorResult("vega spec required")
	}

	jsCode := export.ChartExportPNG(args[0].String())
	js.Global().Call("eval", jsCode)
	return `{"success":true}`
}

func exportChartSVG(this js.Value, args []js.Value) interface{} {
	if len(args) < 1 {
		return errorResult("vega spec required")
	}

	jsCode := export.ChartExportSVG(args[0].String())
	js.Global().Call("eval", jsCode)
	return `{"success":true}`
}

func saveProjectState(this js.Value, args []js.Value) interface{} {
	if len(args) < 1 {
		return errorResult("project state JSON required")
	}

	var state persist.ProjectState
	if err := json.Unmarshal([]byte(args[0].String()), &state); err != nil {
		return errorResult("invalid project state: " + err.Error())
	}

	state.UpdatedAt = time.Now().Unix()
	projectState = &state

	if err := persist.SaveProject(state); err != nil {
		return errorResult("save error: " + err.Error())
	}

	return `{"success":true}`
}

func loadProjectState(this js.Value, args []js.Value) interface{} {
	if len(args) < 1 {
		return errorResult("project ID required")
	}

	id := args[0].String()
	state, err := persist.LoadProject(id)
	if err != nil {
		return errorResult("load error: " + err.Error())
	}

	if state != nil {
		projectState = state
		b, _ := json.Marshal(state)
		return string(b)
	}

	return errorResult("project not found")
}

func listProjects(this js.Value, args []js.Value) interface{} {
	projects, err := persist.ListProjects()
	if err != nil {
		return errorResult("list error: " + err.Error())
	}

	b, _ := json.Marshal(projects)
	return string(b)
}

func deleteProjectState(this js.Value, args []js.Value) interface{} {
	if len(args) < 1 {
		return errorResult("project ID required")
	}

	id := args[0].String()
	if err := persist.DeleteProject(id); err != nil {
		return errorResult("delete error: " + err.Error())
	}

	return `{"success":true}`
}

func getFilterValues(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}
	if len(args) < 1 {
		return errorResult("column name required")
	}

	colName := args[0].String()
	col := currentTable.GetColumn(colName)
	if col == nil {
		return errorResult("column not found: " + colName)
	}

	values := col.UniqueValues()
	b, _ := json.Marshal(values)
	return string(b)
}

func applyFilter(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}
	if len(args) < 2 {
		return errorResult("column and filter JSON required")
	}

	colName := args[0].String()
	filterJSON := args[1].String()

	var filterDef struct {
		Operator string      `json:"operator"`
		Value    interface{} `json:"value"`
		Value2   interface{} `json:"value2"`
		Values   []string    `json:"values"`
	}
	if err := json.Unmarshal([]byte(filterJSON), &filterDef); err != nil {
		return errorResult("invalid filter: " + err.Error())
	}

	col := currentTable.GetColumn(colName)
	if col == nil {
		return errorResult("column not found: " + colName)
	}

	mask := make([]bool, currentTable.RowCount)

	switch strings.ToUpper(filterDef.Operator) {
	case "=":
		for i := 0; i < col.Length; i++ {
			mask[i] = !col.IsNull(i) && valueToString(col, i) == toString(filterDef.Value)
		}
	case "!=":
		for i := 0; i < col.Length; i++ {
			mask[i] = col.IsNull(i) || valueToString(col, i) != toString(filterDef.Value)
		}
	case ">":
		for i := 0; i < col.Length; i++ {
			mask[i] = !col.IsNull(i) && compareValues(col, i, filterDef.Value) > 0
		}
	case "<":
		for i := 0; i < col.Length; i++ {
			mask[i] = !col.IsNull(i) && compareValues(col, i, filterDef.Value) < 0
		}
	case ">=":
		for i := 0; i < col.Length; i++ {
			mask[i] = !col.IsNull(i) && compareValues(col, i, filterDef.Value) >= 0
		}
	case "<=":
		for i := 0; i < col.Length; i++ {
			mask[i] = !col.IsNull(i) && compareValues(col, i, filterDef.Value) <= 0
		}
	case "IN":
		valSet := make(map[string]bool)
		for _, v := range filterDef.Values {
			valSet[v] = true
		}
		for i := 0; i < col.Length; i++ {
			mask[i] = !col.IsNull(i) && valSet[valueToString(col, i)]
		}
	case "BETWEEN":
		low := toFloat(filterDef.Value)
		high := toFloat(filterDef.Value2)
		for i := 0; i < col.Length; i++ {
			if col.IsNull(i) {
				continue
			}
			v := colValueToFloat(col, i)
			mask[i] = v >= low && v <= high
		}
	case "IS_NULL":
		for i := 0; i < col.Length; i++ {
			mask[i] = col.IsNull(i)
		}
	case "IS_NOT_NULL":
		for i := 0; i < col.Length; i++ {
			mask[i] = !col.IsNull(i)
		}
	default:
		for i := range mask {
			mask[i] = true
		}
	}

	filtered := currentTable.Filter(mask)
	currentTable = filtered

	type filterResult struct {
		Success  bool     `json:"success"`
		RowCount int      `json:"rowCount"`
		Columns  []string `json:"columns"`
	}

	resp := filterResult{
		Success:  true,
		RowCount: currentTable.RowCount,
		Columns:  currentTable.ColumnNames(),
	}

	b, _ := json.Marshal(resp)
	return string(b)
}

func getColumnStats(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}
	if len(args) < 1 {
		return errorResult("column name required")
	}

	colName := args[0].String()
	col := currentTable.GetColumn(colName)
	if col == nil {
		return errorResult("column not found: " + colName)
	}

	min, max, mean, count, nullCount, _ := col.Stats()
	
	type colStats struct {
		Min       interface{} `json:"min"`
		Max       interface{} `json:"max"`
		Mean      float64     `json:"mean"`
		StdDev    float64     `json:"stddev"`
		Count     int         `json:"count"`
		NullCount int         `json:"nullCount"`
		Unique    int         `json:"unique"`
		DataType  string      `json:"dataType"`
	}

	dt := ""
	switch col.DataType {
	case store.TypeInt:
		dt = "int"
	case store.TypeFloat:
		dt = "float"
	case store.TypeString:
		dt = "string"
	case store.TypeBool:
		dt = "bool"
	case store.TypeDate:
		dt = "date"
	}

	uniqueCount := len(col.UniqueValues())

	result := colStats{
		Count:     count,
		NullCount: nullCount,
		Unique:    uniqueCount,
		DataType:  dt,
		Mean:      mean,
	}

	if col.DataType == store.TypeInt {
		result.Min = int64(min)
		result.Max = int64(max)
	} else if col.DataType == store.TypeFloat {
		result.Min = min
		result.Max = max
	} else if col.DataType == store.TypeDate {
		result.Min = min
		result.Max = max
	}

	if col.DataType == store.TypeInt || col.DataType == store.TypeFloat {
		var sum, sumSq float64
		nonNullCount := 0
		for i := 0; i < col.Length; i++ {
			if col.NullMap[i] {
				continue
			}
			var val float64
			switch col.DataType {
			case store.TypeInt:
				val = float64(col.IntData[i])
			case store.TypeFloat:
				val = col.FloatData[i]
			}
			sum += val
			sumSq += val * val
			nonNullCount++
		}
		if nonNullCount > 0 {
			avg := sum / float64(nonNullCount)
			variance := (sumSq / float64(nonNullCount)) - (avg * avg)
			if variance < 0 {
				variance = 0
			}
			result.StdDev = math.Sqrt(variance)
		}
	}

	b, _ := json.Marshal(result)
	return string(b)
}

func getHistogram(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}
	if len(args) < 1 {
		return errorResult("column name required")
	}

	colName := args[0].String()
	bins := 20
	if len(args) >= 2 {
		bins = args[1].Int()
	}

	col := currentTable.GetColumn(colName)
	if col == nil {
		return errorResult("column not found: " + colName)
	}

	if col.DataType != store.TypeInt && col.DataType != store.TypeFloat {
		return errorResult("histogram only supported for numeric columns")
	}

	var values []float64
	for i := 0; i < col.Length; i++ {
		if col.IsNull(i) {
			continue
		}
		if col.DataType == store.TypeInt {
			values = append(values, float64(col.IntData[i]))
		} else {
			values = append(values, col.FloatData[i])
		}
	}

	if len(values) == 0 {
		return `{"bins":[],"counts":[]}`
	}

	minVal := values[0]
	maxVal := values[0]
	for _, v := range values {
		if v < minVal {
			minVal = v
		}
		if v > maxVal {
			maxVal = v
		}
	}

	if minVal == maxVal {
		maxVal = minVal + 1
	}

	binWidth := (maxVal - minVal) / float64(bins)
	counts := make([]int, bins)
	binEdges := make([]float64, bins+1)

	for i := 0; i <= bins; i++ {
		binEdges[i] = minVal + float64(i)*binWidth
	}

	for _, v := range values {
		binIdx := int((v - minVal) / binWidth)
		if binIdx >= bins {
			binIdx = bins - 1
		}
		counts[binIdx]++
	}

	type histResult struct {
		BinEdges []float64 `json:"binEdges"`
		Counts   []int     `json:"counts"`
		Min      float64   `json:"min"`
		Max      float64   `json:"max"`
		Bins     int       `json:"bins"`
	}

	b, _ := json.Marshal(histResult{
		BinEdges: binEdges,
		Counts:   counts,
		Min:      minVal,
		Max:      maxVal,
		Bins:     bins,
	})
	return string(b)
}

func getValueDistribution(this js.Value, args []js.Value) interface{} {
	if currentTable == nil {
		return errorResult("no data loaded")
	}
	if len(args) < 1 {
		return errorResult("column name required")
	}

	colName := args[0].String()
	limit := 20
	if len(args) >= 2 {
		limit = args[1].Int()
	}

	col := currentTable.GetColumn(colName)
	if col == nil {
		return errorResult("column not found: " + colName)
	}

	counts := make(map[string]int)
	for i := 0; i < col.Length; i++ {
		if col.IsNull(i) {
			counts["__NULL__"]++
			continue
		}
		key := valueToString(col, i)
		counts[key]++
	}

	type valueCount struct {
		Value string `json:"value"`
		Count int    `json:"count"`
	}

	var dist []valueCount
	for v, c := range counts {
		dist = append(dist, valueCount{v, c})
	}

	sort.Slice(dist, func(i, j int) bool {
		return dist[i].Count > dist[j].Count
	})

	if len(dist) > limit {
		dist = dist[:limit]
	}

	b, _ := json.Marshal(dist)
	return string(b)
}

func filtersToQuery(this js.Value, args []js.Value) interface{} {
	if len(args) < 1 {
		return errorResult("filters JSON required")
	}

	var filters []struct {
		Column   string      `json:"column"`
		Operator string      `json:"operator"`
		Value    interface{} `json:"value"`
		Value2   interface{} `json:"value2"`
		Values   []string    `json:"values"`
	}

	if err := json.Unmarshal([]byte(args[0].String()), &filters); err != nil {
		return errorResult("invalid filters: " + err.Error())
	}

	var conditions []string
	for _, f := range filters {
		cond := formatFilterCondition(f)
		if cond != "" {
			conditions = append(conditions, cond)
		}
	}

	result := struct {
		Success     bool     `json:"success"`
		WhereClause string   `json:"whereClause"`
		Conditions  []string `json:"conditions"`
	}{
		Success:     true,
		WhereClause: strings.Join(conditions, " AND "),
		Conditions:  conditions,
	}

	b, _ := json.Marshal(result)
	return string(b)
}

func formatFilterCondition(f struct {
	Column   string      `json:"column"`
	Operator string      `json:"operator"`
	Value    interface{} `json:"value"`
	Value2   interface{} `json:"value2"`
	Values   []string    `json:"values"`
}) string {
	col := "`" + f.Column + "`"
	switch strings.ToUpper(f.Operator) {
	case "=":
		return col + " = " + formatValue(f.Value)
	case "!=":
		return col + " != " + formatValue(f.Value)
	case ">":
		return col + " > " + formatValue(f.Value)
	case "<":
		return col + " < " + formatValue(f.Value)
	case ">=":
		return col + " >= " + formatValue(f.Value)
	case "<=":
		return col + " <= " + formatValue(f.Value)
	case "IN":
		if len(f.Values) == 0 {
			return ""
		}
		var quoted []string
		for _, v := range f.Values {
			quoted = append(quoted, "'"+strings.ReplaceAll(v, "'", "''")+"'")
		}
		return col + " IN (" + strings.Join(quoted, ", ") + ")"
	case "BETWEEN":
		return col + " BETWEEN " + formatValue(f.Value) + " AND " + formatValue(f.Value2)
	case "IS_NULL":
		return col + " IS NULL"
	case "IS_NOT_NULL":
		return col + " IS NOT NULL"
	}
	return ""
}

func formatValue(v interface{}) string {
	if v == nil {
		return "NULL"
	}
	switch val := v.(type) {
	case string:
		return "'" + strings.ReplaceAll(val, "'", "''") + "'"
	case float64:
		return strconv.FormatFloat(val, 'g', -1, 64)
	case int:
		return strconv.Itoa(val)
	case bool:
		if val {
			return "true"
		}
		return "false"
	default:
		return fmt.Sprintf("'%v'", v)
	}
}

func registerTable(this js.Value, args []js.Value) interface{} {
	if len(args) < 2 {
		return errorResult("table name and data required")
	}

	tableName := args[0].String()
	dataStr := args[1].String()
	format := "csv"
	if len(args) >= 3 {
		format = args[2].String()
	}

	p := parser.NewParser()
	result := p.Parse([]byte(dataStr), format, tableName)

	if result.Table != nil {
		tables[tableName] = result.Table
		if tableName == "main_table" || currentTable == nil {
			currentTable = result.Table
			for _, col := range currentTable.Columns {
				if col.DataType == store.TypeString || col.DataType == store.TypeBool {
					indexManager.BuildIndex(col)
				}
			}
		}
	}

	type registerResponse struct {
		Success  bool   `json:"success"`
		RowCount int    `json:"rowCount"`
		ColCount int    `json:"colCount"`
		Columns  []string `json:"columns"`
		Types    []string `json:"types"`
	}

	resp := registerResponse{
		Success: result.Table != nil,
	}

	if result.Table != nil {
		resp.RowCount = result.Table.RowCount
		resp.ColCount = len(result.Table.Columns)
		resp.Columns = result.Table.ColumnNames()
		types := make([]string, len(result.Table.Columns))
		for i, col := range result.Table.Columns {
			switch col.DataType {
			case store.TypeInt:
				types[i] = "int"
			case store.TypeFloat:
				types[i] = "float"
			case store.TypeString:
				types[i] = "string"
			case store.TypeBool:
				types[i] = "bool"
			case store.TypeDate:
				types[i] = "date"
			default:
				types[i] = "string"
			}
		}
		resp.Types = types
	}

	b, _ := json.Marshal(resp)
	return string(b)
}

func executeJoinQuery(this js.Value, args []js.Value) interface{} {
	if len(args) < 1 {
		return errorResult("query required")
	}

	queryStr := args[0].String()

	stmt, err := query.NewParser().Parse(queryStr)
	if err != nil {
		return errorResult("parse error: " + err.Error())
	}

	executor := query.NewExecutor(indexManager)

	var result *store.Table
	if len(stmt.Joins) > 0 {
		result, err = executor.ExecuteJoin(tables, stmt)
	} else {
		table := currentTable
		if tables[stmt.From] != nil {
			table = tables[stmt.From]
		}
		if table == nil {
			return errorResult("table not found: " + stmt.From)
		}
		result, err = executor.Execute(table, stmt)
	}

	if err != nil {
		return errorResult("execute error: " + err.Error())
	}

	type queryResult struct {
		Success bool        `json:"success"`
		Data    []map[string]interface{} `json:"data"`
		Columns []string  `json:"columns"`
		RowCount int      `json:"rowCount"`
	}

	data := result.ToJSON(0, 10000)
	resp := queryResult{
		Success:  true,
		Data:     data,
		Columns:  result.ColumnNames(),
		RowCount: result.RowCount,
	}

	b, _ := json.Marshal(resp)
	return string(b)
}

func valueToString(col *store.Column, i int) string {
	switch col.DataType {
	case store.TypeInt:
		return strconv.FormatInt(col.IntData[i], 10)
	case store.TypeFloat:
		return strconv.FormatFloat(col.FloatData[i], 'g', -1, 64)
	case store.TypeString:
		return col.StrData[i]
	case store.TypeBool:
		return strconv.FormatBool(col.BoolData[i])
	case store.TypeDate:
		return strconv.FormatInt(col.DateData[i], 10)
	}
	return ""
}

func toString(v interface{}) string {
	switch val := v.(type) {
	case string:
		return val
	case float64:
		if val == float64(int64(val)) {
			return strconv.FormatInt(int64(val), 10)
		}
		return strconv.FormatFloat(val, 'g', -1, 64)
	case int:
		return strconv.Itoa(val)
	case int64:
		return strconv.FormatInt(val, 10)
	case bool:
		return strconv.FormatBool(val)
	default:
		return fmt.Sprintf("%v", val)
	}
}

func toFloat(v interface{}) float64 {
	switch val := v.(type) {
	case float64:
		return val
	case int:
		return float64(val)
	case int64:
		return float64(val)
	case string:
		f, _ := strconv.ParseFloat(val, 64)
		return f
	default:
		return 0
	}
}

func colValueToFloat(col *store.Column, i int) float64 {
	switch col.DataType {
	case store.TypeInt:
		return float64(col.IntData[i])
	case store.TypeFloat:
		return col.FloatData[i]
	}
	return 0
}

func compareValues(col *store.Column, i int, target interface{}) int {
	switch col.DataType {
	case store.TypeInt:
		a := col.IntData[i]
		b := int64(toFloat(target))
		if a < b {
			return -1
		}
		if a > b {
			return 1
		}
		return 0
	case store.TypeFloat:
		a := col.FloatData[i]
		b := toFloat(target)
		if a < b {
			return -1
		}
		if a > b {
			return 1
		}
		return 0
	case store.TypeString:
		a := col.StrData[i]
		b := toString(target)
		if a < b {
			return -1
		}
		if a > b {
			return 1
		}
		return 0
	}
	return 0
}

func errorResult(msg string) string {
	type errResp struct {
		Success bool   `json:"success"`
		Error   string `json:"error"`
	}
	b, _ := json.Marshal(errResp{Success: false, Error: msg})
	return string(b)
}
