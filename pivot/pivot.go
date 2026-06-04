package pivot

import (
	"encoding/json"
	"fmt"
	"math"
	"sort"
	"strings"

	"github.com/dataexplorer/store"
)

type AggMethod int

const (
	AggSum AggMethod = iota
	AggCount
	AggAvg
	AggMin
	AggMax
	AggStdDev
	AggCountDistinct
	AggPercentile
)

type PivotConfig struct {
	RowDims        []string
	ColDims        []string
	ValueField     string
	AggMethod      AggMethod
	PercentileValue float64
}

type PivotCell struct {
	Value    float64 `json:"value"`
	Count    int     `json:"count"`
	HasValue bool    `json:"hasValue"`
}

type PivotResult struct {
	Rows        []map[string]interface{}        `json:"rows"`
	Cols        []map[string]interface{}        `json:"cols"`
	Cells       map[string]map[string]PivotCell `json:"cells"`
	RowTotals   map[string]PivotCell            `json:"rowTotals"`
	ColTotals   map[string]PivotCell            `json:"colTotals"`
	GrandTotal  PivotCell                       `json:"grandTotal"`
}

type cellAccum struct {
	values    []float64
	sum       float64
	count     int
	min       float64
	max       float64
	distinct  map[float64]bool
	allValues []float64
}

func makeKey(dims []string, rowIdx int, table *store.Table) string {
	parts := make([]string, len(dims))
	for i, dim := range dims {
		col := table.GetColumn(dim)
		if col == nil || col.IsNull(rowIdx) {
			parts[i] = "NULL"
			continue
		}
		switch col.DataType {
		case store.TypeInt:
			parts[i] = fmt.Sprintf("%d", col.IntData[rowIdx])
		case store.TypeFloat:
			parts[i] = fmt.Sprintf("%f", col.FloatData[rowIdx])
		case store.TypeString:
			parts[i] = col.StrData[rowIdx]
		case store.TypeBool:
			parts[i] = fmt.Sprintf("%t", col.BoolData[rowIdx])
		case store.TypeDate:
			parts[i] = fmt.Sprintf("%d", col.DateData[rowIdx])
		default:
			parts[i] = "NULL"
		}
	}
	return strings.Join(parts, "|")
}

func getDimValues(dims []string, rowIdx int, table *store.Table) map[string]interface{} {
	result := make(map[string]interface{}, len(dims))
	for _, dim := range dims {
		col := table.GetColumn(dim)
		if col == nil || col.IsNull(rowIdx) {
			result[dim] = nil
			continue
		}
		switch col.DataType {
		case store.TypeInt:
			result[dim] = col.IntData[rowIdx]
		case store.TypeFloat:
			result[dim] = col.FloatData[rowIdx]
		case store.TypeString:
			result[dim] = col.StrData[rowIdx]
		case store.TypeBool:
			result[dim] = col.BoolData[rowIdx]
		case store.TypeDate:
			result[dim] = col.DateData[rowIdx]
		default:
			result[dim] = nil
		}
	}
	return result
}

func getCellValue(table *store.Table, colName string, rowIdx int) (float64, bool) {
	col := table.GetColumn(colName)
	if col == nil || col.IsNull(rowIdx) {
		return 0, false
	}
	switch col.DataType {
	case store.TypeInt:
		return float64(col.IntData[rowIdx]), true
	case store.TypeFloat:
		return col.FloatData[rowIdx], true
	default:
		return 0, false
	}
}

func sortDimensionKeys(keys []string) {
	sort.Slice(keys, func(i, j int) bool {
		return keys[i] < keys[j]
	})
}

func computeCell(accum *cellAccum, method AggMethod, percentile float64) PivotCell {
	if accum.count == 0 {
		return PivotCell{}
	}
	switch method {
	case AggSum:
		return PivotCell{Value: accum.sum, Count: accum.count, HasValue: true}
	case AggCount:
		return PivotCell{Value: float64(accum.count), Count: accum.count, HasValue: true}
	case AggAvg:
		return PivotCell{Value: accum.sum / float64(accum.count), Count: accum.count, HasValue: true}
	case AggMin:
		return PivotCell{Value: accum.min, Count: accum.count, HasValue: true}
	case AggMax:
		return PivotCell{Value: accum.max, Count: accum.count, HasValue: true}
	case AggStdDev:
		if accum.count < 2 {
			return PivotCell{Value: 0, Count: accum.count, HasValue: true}
		}
		mean := accum.sum / float64(accum.count)
		var variance float64
		for _, v := range accum.values {
			diff := v - mean
			variance += diff * diff
		}
		return PivotCell{Value: math.Sqrt(variance / float64(accum.count)), Count: accum.count, HasValue: true}
	case AggCountDistinct:
		return PivotCell{Value: float64(len(accum.distinct)), Count: accum.count, HasValue: true}
	case AggPercentile:
		if len(accum.allValues) == 0 {
			return PivotCell{}
		}
		p := percentile
		if p < 0 {
			p = 0
		}
		if p > 100 {
			p = 100
		}
		idx := int((p / 100.0) * float64(len(accum.allValues)-1))
		if idx < 0 {
			idx = 0
		}
		if idx >= len(accum.allValues) {
			idx = len(accum.allValues) - 1
		}
		arrCopy := make([]float64, len(accum.allValues))
		copy(arrCopy, accum.allValues)
		return PivotCell{Value: quickselect(arrCopy, idx), Count: accum.count, HasValue: true}
	default:
		return PivotCell{}
	}
}

func NewPivotTable(table *store.Table, config PivotConfig) (*PivotResult, error) {
	if table == nil {
		return nil, fmt.Errorf("table is nil")
	}
	if len(config.RowDims) == 0 {
		return nil, fmt.Errorf("at least one row dimension is required")
	}

	valueCol := table.GetColumn(config.ValueField)
	if valueCol == nil && config.AggMethod != AggCount {
		return nil, fmt.Errorf("value field %s not found", config.ValueField)
	}

	rowKeySet := make(map[string]map[string]interface{})
	colKeySet := make(map[string]map[string]interface{})

	type accumKey struct {
		rowKey string
		colKey string
	}
	accums := make(map[accumKey]*cellAccum)

	for i := 0; i < table.RowCount; i++ {
		rowKey := makeKey(config.RowDims, i, table)
		rowKeySet[rowKey] = getDimValues(config.RowDims, i, table)

		var colKey string
		if len(config.ColDims) > 0 {
			colKey = makeKey(config.ColDims, i, table)
			colKeySet[colKey] = getDimValues(config.ColDims, i, table)
		}

		ak := accumKey{rowKey: rowKey, colKey: colKey}
		accum, exists := accums[ak]
		if !exists {
			accum = &cellAccum{}
			if config.AggMethod == AggCountDistinct {
				accum.distinct = make(map[float64]bool)
			}
			accums[ak] = accum
		}

		val, ok := getCellValue(table, config.ValueField, i)
		if ok {
			accum.count++
			accum.sum += val
			accum.values = append(accum.values, val)
			if accum.count == 1 {
				accum.min = val
				accum.max = val
			} else {
				if val < accum.min {
					accum.min = val
				}
				if val > accum.max {
					accum.max = val
				}
			}
			if config.AggMethod == AggCountDistinct {
				accum.distinct[val] = true
			}
			if config.AggMethod == AggPercentile {
				accum.allValues = append(accum.allValues, val)
			}
		} else if config.AggMethod == AggCount {
			continue
		}
	}

	rowKeys := make([]string, 0, len(rowKeySet))
	for k := range rowKeySet {
		rowKeys = append(rowKeys, k)
	}
	sortDimensionKeys(rowKeys)

	colKeys := make([]string, 0, len(colKeySet))
	for k := range colKeySet {
		colKeys = append(colKeys, k)
	}
	sortDimensionKeys(colKeys)

	rows := make([]map[string]interface{}, len(rowKeys))
	for i, k := range rowKeys {
		rows[i] = rowKeySet[k]
	}

	cols := make([]map[string]interface{}, len(colKeys))
	for i, k := range colKeys {
		cols[i] = colKeySet[k]
	}

	cells := make(map[string]map[string]PivotCell)
	for rk := range rowKeySet {
		cells[rk] = make(map[string]PivotCell)
	}

	rowAccums := make(map[string]*cellAccum)
	colAccums := make(map[string]*cellAccum)
	var grandAccum cellAccum

	for rk := range rowKeySet {
		rowAccums[rk] = &cellAccum{}
		if config.AggMethod == AggCountDistinct {
			rowAccums[rk].distinct = make(map[float64]bool)
		}
	}
	for ck := range colKeySet {
		colAccums[ck] = &cellAccum{}
		if config.AggMethod == AggCountDistinct {
			colAccums[ck].distinct = make(map[float64]bool)
		}
	}
	if config.AggMethod == AggCountDistinct {
		grandAccum.distinct = make(map[float64]bool)
	}

	for ak, accum := range accums {
		cell := computeCell(accum, config.AggMethod, config.PercentileValue)
		cells[ak.rowKey][ak.colKey] = cell

		if cell.HasValue {
			ra := rowAccums[ak.rowKey]
			ra.count += accum.count
			ra.sum += accum.sum
			ra.values = append(ra.values, accum.values...)
			if ra.count == accum.count || accum.min < ra.min {
				ra.min = accum.min
			}
			if ra.count == accum.count || accum.max > ra.max {
				ra.max = accum.max
			}
			if config.AggMethod == AggCountDistinct {
				for v := range accum.distinct {
					ra.distinct[v] = true
				}
			}
			if config.AggMethod == AggPercentile {
				ra.allValues = append(ra.allValues, accum.allValues...)
			}

			if len(colKeySet) > 0 {
				ca := colAccums[ak.colKey]
				ca.count += accum.count
				ca.sum += accum.sum
				ca.values = append(ca.values, accum.values...)
				if ca.count == accum.count || accum.min < ca.min {
					ca.min = accum.min
				}
				if ca.count == accum.count || accum.max > ca.max {
					ca.max = accum.max
				}
				if config.AggMethod == AggCountDistinct {
					for v := range accum.distinct {
						ca.distinct[v] = true
					}
				}
				if config.AggMethod == AggPercentile {
					ca.allValues = append(ca.allValues, accum.allValues...)
				}
			}

			grandAccum.count += accum.count
			grandAccum.sum += accum.sum
			grandAccum.values = append(grandAccum.values, accum.values...)
			if grandAccum.count == accum.count || accum.min < grandAccum.min {
				grandAccum.min = accum.min
			}
			if grandAccum.count == accum.count || accum.max > grandAccum.max {
				grandAccum.max = accum.max
			}
			if config.AggMethod == AggCountDistinct {
				for v := range accum.distinct {
					grandAccum.distinct[v] = true
				}
			}
			if config.AggMethod == AggPercentile {
				grandAccum.allValues = append(grandAccum.allValues, accum.allValues...)
			}
		}
	}

	rowTotals := make(map[string]PivotCell)
	for rk, ra := range rowAccums {
		rowTotals[rk] = computeCell(ra, config.AggMethod, config.PercentileValue)
	}

	colTotals := make(map[string]PivotCell)
	for ck, ca := range colAccums {
		colTotals[ck] = computeCell(ca, config.AggMethod, config.PercentileValue)
	}

	grandTotal := computeCell(&grandAccum, config.AggMethod, config.PercentileValue)

	return &PivotResult{
		Rows:       rows,
		Cols:       cols,
		Cells:      cells,
		RowTotals:  rowTotals,
		ColTotals:  colTotals,
		GrandTotal: grandTotal,
	}, nil
}

func (pr *PivotResult) ToJSON() string {
	b, err := json.Marshal(pr)
	if err != nil {
		return "{}"
	}
	return string(b)
}

func (pr *PivotResult) Flatten() *store.Table {
	result := store.NewTable("pivot_flat")

	rowDimSet := make(map[string]bool)
	for _, row := range pr.Rows {
		for k := range row {
			rowDimSet[k] = true
		}
	}
	colDimSet := make(map[string]bool)
	for _, col := range pr.Cols {
		for k := range col {
			colDimSet[k] = true
		}
	}

	rowDimNames := make([]string, 0, len(rowDimSet))
	for k := range rowDimSet {
		rowDimNames = append(rowDimNames, k)
	}
	sort.Strings(rowDimNames)

	colDimNames := make([]string, 0, len(colDimSet))
	for k := range colDimSet {
		colDimNames = append(colDimNames, k)
	}
	sort.Strings(colDimNames)

	for _, name := range rowDimNames {
		result.AddColumn(name, store.TypeString)
	}

	colKeyOrder := make([]string, len(pr.Cols))
	for i, col := range pr.Cols {
		key := makeDimKeyFromMap(col, colDimNames)
		colKeyOrder[i] = key
	}

	for _, ck := range colKeyOrder {
		result.AddColumn(fmt.Sprintf("col_%s", ck), store.TypeFloat)
	}

	for _, name := range colDimNames {
		result.AddColumn(name, store.TypeString)
	}

	totalRows := len(pr.Rows)
	result.SetRowCount(totalRows)

	rowKeyOrder := make([]string, len(pr.Rows))
	for i, row := range pr.Rows {
		rowKeyOrder[i] = makeDimKeyFromMap(row, rowDimNames)
	}

	rowDimCols := make(map[string]*store.Column)
	for _, name := range rowDimNames {
		rowDimCols[name] = result.GetColumn(name)
	}

	valueCols := make(map[string]*store.Column)
	for _, ck := range colKeyOrder {
		colName := fmt.Sprintf("col_%s", ck)
		valueCols[ck] = result.GetColumn(colName)
	}

	for i, rk := range rowKeyOrder {
		row := pr.Rows[i]
		for _, name := range rowDimNames {
			col := rowDimCols[name]
			v, ok := row[name]
			if ok && v != nil {
				col.StrData[i] = fmt.Sprintf("%v", v)
			} else {
				col.NullMap[i] = true
			}
		}
		for _, ck := range colKeyOrder {
			vc := valueCols[ck]
			cell, exists := pr.Cells[rk][ck]
			if exists && cell.HasValue {
				vc.FloatData[i] = cell.Value
			} else {
				vc.NullMap[i] = true
			}
		}
	}

	return result
}

func quickselect(arr []float64, k int) float64 {
	if len(arr) == 0 {
		return 0
	}
	return quickselectHelper(arr, 0, len(arr)-1, k)
}

func quickselectHelper(arr []float64, left, right, k int) float64 {
	if left == right {
		return arr[left]
	}
	pivotIndex := partition(arr, left, right)
	if k == pivotIndex {
		return arr[k]
	} else if k < pivotIndex {
		return quickselectHelper(arr, left, pivotIndex-1, k)
	} else {
		return quickselectHelper(arr, pivotIndex+1, right, k)
	}
}

func partition(arr []float64, left, right int) int {
	pivot := arr[right]
	i := left
	for j := left; j < right; j++ {
		if arr[j] <= pivot {
			arr[i], arr[j] = arr[j], arr[i]
			i++
		}
	}
	arr[i], arr[right] = arr[right], arr[i]
	return i
}

func makeDimKeyFromMap(m map[string]interface{}, dimNames []string) string {
	parts := make([]string, len(dimNames))
	for i, name := range dimNames {
		v, ok := m[name]
		if !ok || v == nil {
			parts[i] = "NULL"
		} else {
			parts[i] = fmt.Sprintf("%v", v)
		}
	}
	return strings.Join(parts, "|")
}
