package store

import (
	"encoding/json"
	"fmt"
	"math"
	"sort"
)

type Table struct {
	Name       string
	Columns    []*Column
	ColIndex   map[string]int
	RowCount   int
	ChunkSize  int
	IsChunked  bool
	ActiveChunk int
}

func NewTable(name string) *Table {
	return &Table{
		Name:      name,
		ColIndex:  make(map[string]int),
		ChunkSize: 100000,
	}
}

func (t *Table) AddColumn(name string, dt DataType) *Column {
	col := NewColumn(name, dt, t.RowCount)
	t.Columns = append(t.Columns, col)
	t.ColIndex[name] = len(t.Columns) - 1
	return col
}

func (t *Table) GetColumn(name string) *Column {
	idx, ok := t.ColIndex[name]
	if !ok {
		return nil
	}
	return t.Columns[idx]
}

func (t *Table) ColumnNames() []string {
	names := make([]string, len(t.Columns))
	for i, c := range t.Columns {
		names[i] = c.Name
	}
	return names
}

func (t *Table) SetRowCount(n int) {
	t.RowCount = n
	for _, col := range t.Columns {
		if col.Length != n {
			old := col.Length
			col.Length = n
			newNull := make([]bool, n)
			newDirty := make([]bool, n)
			copy(newNull, col.NullMap[:min(old, n)])
			copy(newDirty, col.DirtyMap[:min(old, n)])
			col.NullMap = newNull
			col.DirtyMap = newDirty
			switch col.DataType {
			case TypeInt:
				newData := make([]int64, n)
				copy(newData, col.IntData[:min(old, n)])
				col.IntData = newData
			case TypeFloat:
				newData := make([]float64, n)
				copy(newData, col.FloatData[:min(old, n)])
				col.FloatData = newData
			case TypeString:
				newData := make([]string, n)
				copy(newData, col.StrData[:min(old, n)])
				col.StrData = newData
			case TypeBool:
				newData := make([]bool, n)
				copy(newData, col.BoolData[:min(old, n)])
				col.BoolData = newData
			case TypeDate:
				newData := make([]int64, n)
				copy(newData, col.DateData[:min(old, n)])
				col.DateData = newData
			}
		}
	}
}

func (t *Table) Filter(mask []bool) *Table {
	count := 0
	for _, v := range mask {
		if v {
			count++
		}
	}
	result := NewTable(t.Name + "_filtered")
	result.RowCount = count
	for _, col := range t.Columns {
		newCol := NewColumn(col.Name, col.DataType, count)
		j := 0
		for i := 0; i < t.RowCount; i++ {
			if mask[i] {
				newCol.NullMap[j] = col.NullMap[i]
				newCol.DirtyMap[j] = col.DirtyMap[i]
				switch col.DataType {
				case TypeInt:
					newCol.IntData[j] = col.IntData[i]
				case TypeFloat:
					newCol.FloatData[j] = col.FloatData[i]
				case TypeString:
					newCol.StrData[j] = col.StrData[i]
				case TypeBool:
					newCol.BoolData[j] = col.BoolData[i]
				case TypeDate:
					newCol.DateData[j] = col.DateData[i]
				}
				j++
			}
		}
		result.Columns = append(result.Columns, newCol)
		result.ColIndex[newCol.Name] = len(result.Columns) - 1
	}
	return result
}

type AggFunc int

const (
	AggSum AggFunc = iota
	AggCount
	AggAvg
	AggMin
	AggMax
	AggStdDev
	AggCountDistinct
	AggPercentile
)

func (af AggFunc) String() string {
	switch af {
	case AggSum:
		return "SUM"
	case AggCount:
		return "COUNT"
	case AggAvg:
		return "AVG"
	case AggMin:
		return "MIN"
	case AggMax:
		return "MAX"
	case AggStdDev:
		return "STDDEV"
	case AggCountDistinct:
		return "COUNT_DISTINCT"
	case AggPercentile:
		return "PERCENTILE"
	default:
		return "UNKNOWN"
	}
}

type GroupResult struct {
	Keys       map[string]interface{}
	Values     map[string]float64
	Count      int
	Percentile float64
}

type groupAccum struct {
	keys       map[string]interface{}
	values     map[string]float64
	count      int
	distinct   map[interface{}]bool
	allValues  []float64
	percentile float64
}

func (t *Table) GroupBy(groupCols []string, aggCol string, aggFn AggFunc, percentile ...float64) ([]GroupResult, error) {
	groupMap := make(map[string]*groupAccum)
	var order []string

	pval := 0.0
	if len(percentile) > 0 {
		pval = percentile[0]
	}

	aggColumn := t.GetColumn(aggCol)
	if aggColumn == nil && aggFn != AggCount {
		return nil, fmt.Errorf("column %s not found", aggCol)
	}

	for i := 0; i < t.RowCount; i++ {
		key := ""
		keys := make(map[string]interface{})
		for _, gc := range groupCols {
			col := t.GetColumn(gc)
			if col == nil {
				continue
			}
			if col.IsNull(i) {
				key += "NULL|"
				keys[gc] = nil
				continue
			}
			switch col.DataType {
			case TypeInt:
				v := col.IntData[i]
				key += fmt.Sprintf("%d|", v)
				keys[gc] = v
			case TypeFloat:
				v := col.FloatData[i]
				key += fmt.Sprintf("%f|", v)
				keys[gc] = v
			case TypeString:
				v := col.StrData[i]
				key += v + "|"
				keys[gc] = v
			case TypeBool:
				v := col.BoolData[i]
				key += fmt.Sprintf("%t|", v)
				keys[gc] = v
			case TypeDate:
				v := col.DateData[i]
				key += fmt.Sprintf("%d|", v)
				keys[gc] = v
			}
		}

		ga, exists := groupMap[key]
		if !exists {
			ga = &groupAccum{
				keys:       keys,
				values:     make(map[string]float64),
				percentile: pval,
			}
			if aggFn == AggCountDistinct {
				ga.distinct = make(map[interface{}]bool)
			}
			groupMap[key] = ga
			order = append(order, key)
		}
		ga.count++

		if aggColumn != nil && !aggColumn.IsNull(i) {
			var val float64
			var rawVal interface{}
			switch aggColumn.DataType {
			case TypeInt:
				val = float64(aggColumn.IntData[i])
				rawVal = aggColumn.IntData[i]
			case TypeFloat:
				val = aggColumn.FloatData[i]
				rawVal = aggColumn.FloatData[i]
			default:
				continue
			}
			aggKey := aggFn.String() + "(" + aggCol + ")"
			switch aggFn {
			case AggSum, AggAvg:
				ga.values[aggKey] += val
			case AggMin:
				if _, ok := ga.values[aggKey]; !ok || val < ga.values[aggKey] {
					ga.values[aggKey] = val
				}
			case AggMax:
				if val > ga.values[aggKey] {
					ga.values[aggKey] = val
				}
			case AggStdDev:
				ga.values[aggKey] += val
			case AggCountDistinct:
				ga.distinct[rawVal] = true
			case AggPercentile:
				ga.allValues = append(ga.allValues, val)
			}
		}
	}

	results := make([]GroupResult, 0, len(groupMap))
	for _, k := range order {
		ga := groupMap[k]
		gr := GroupResult{
			Keys:       ga.keys,
			Values:     ga.values,
			Count:      ga.count,
			Percentile: ga.percentile,
		}
		aggKey := aggFn.String() + "(" + aggCol + ")"
		switch aggFn {
		case AggAvg:
			if gr.Count > 0 {
				gr.Values[aggKey] /= float64(gr.Count)
			}
		case AggStdDev:
			mean := 0.0
			if gr.Count > 0 {
				mean = gr.Values[aggKey] / float64(gr.Count)
			}
			variance := 0.0
			for i := 0; i < t.RowCount; i++ {
				key2 := ""
				for _, gc := range groupCols {
					col := t.GetColumn(gc)
					if col == nil {
						continue
					}
					if col.IsNull(i) {
						key2 += "NULL|"
						continue
					}
					switch col.DataType {
					case TypeInt:
						key2 += fmt.Sprintf("%d|", col.IntData[i])
					case TypeFloat:
						key2 += fmt.Sprintf("%f|", col.FloatData[i])
					case TypeString:
						key2 += col.StrData[i] + "|"
					case TypeBool:
						key2 += fmt.Sprintf("%t|", col.BoolData[i])
					case TypeDate:
						key2 += fmt.Sprintf("%d|", col.DateData[i])
					}
				}
				if key2 == k && aggColumn != nil && !aggColumn.IsNull(i) {
					var val float64
					switch aggColumn.DataType {
					case TypeInt:
						val = float64(aggColumn.IntData[i])
					case TypeFloat:
						val = aggColumn.FloatData[i]
					default:
						continue
					}
					diff := val - mean
					variance += diff * diff
				}
			}
			if gr.Count > 1 {
				gr.Values[aggKey] = math.Sqrt(variance / float64(gr.Count-1))
			}
		case AggCount:
			gr.Values[aggKey] = float64(gr.Count)
		case AggCountDistinct:
			gr.Values[aggKey] = float64(len(ga.distinct))
		case AggPercentile:
			if len(ga.allValues) > 0 {
				p := ga.percentile
				if p < 0 {
					p = 0
				}
				if p > 100 {
					p = 100
				}
				idx := int((p / 100.0) * float64(len(ga.allValues)-1))
				if idx < 0 {
					idx = 0
				}
				if idx >= len(ga.allValues) {
					idx = len(ga.allValues) - 1
				}
				arrCopy := make([]float64, len(ga.allValues))
				copy(arrCopy, ga.allValues)
				gr.Values[aggKey] = quickselect(arrCopy, idx)
			}
		}
		results = append(results, gr)
	}

	return results, nil
}

func (t *Table) Sort(sortCol string, ascending bool) error {
	col := t.GetColumn(sortCol)
	if col == nil {
		return fmt.Errorf("column %s not found", sortCol)
	}

	indices := make([]int, t.RowCount)
	for i := range indices {
		indices[i] = i
	}

	sort.SliceStable(indices, func(a, b int) bool {
		ia, ib := indices[a], indices[b]
		if col.NullMap[ia] && col.NullMap[ib] {
			return false
		}
		if col.NullMap[ia] {
			return false
		}
		if col.NullMap[ib] {
			return true
		}
		var less bool
		switch col.DataType {
		case TypeInt:
			less = col.IntData[ia] < col.IntData[ib]
		case TypeFloat:
			less = col.FloatData[ia] < col.FloatData[ib]
		case TypeString:
			less = col.StrData[ia] < col.StrData[ib]
		case TypeBool:
			less = !col.BoolData[ia] && col.BoolData[ib]
		case TypeDate:
			less = col.DateData[ia] < col.DateData[ib]
		default:
			less = false
		}
		if !ascending {
			return !less
		}
		return less
	})

	for _, c := range t.Columns {
		switch c.DataType {
		case TypeInt:
			newData := make([]int64, t.RowCount)
			newNull := make([]bool, t.RowCount)
			newDirty := make([]bool, t.RowCount)
			for i, idx := range indices {
				newData[i] = c.IntData[idx]
				newNull[i] = c.NullMap[idx]
				newDirty[i] = c.DirtyMap[idx]
			}
			c.IntData = newData
			c.NullMap = newNull
			c.DirtyMap = newDirty
		case TypeFloat:
			newData := make([]float64, t.RowCount)
			newNull := make([]bool, t.RowCount)
			newDirty := make([]bool, t.RowCount)
			for i, idx := range indices {
				newData[i] = c.FloatData[idx]
				newNull[i] = c.NullMap[idx]
				newDirty[i] = c.DirtyMap[idx]
			}
			c.FloatData = newData
			c.NullMap = newNull
			c.DirtyMap = newDirty
		case TypeString:
			newData := make([]string, t.RowCount)
			newNull := make([]bool, t.RowCount)
			newDirty := make([]bool, t.RowCount)
			for i, idx := range indices {
				newData[i] = c.StrData[idx]
				newNull[i] = c.NullMap[idx]
				newDirty[i] = c.DirtyMap[idx]
			}
			c.StrData = newData
			c.NullMap = newNull
			c.DirtyMap = newDirty
		case TypeBool:
			newData := make([]bool, t.RowCount)
			newNull := make([]bool, t.RowCount)
			newDirty := make([]bool, t.RowCount)
			for i, idx := range indices {
				newData[i] = c.BoolData[idx]
				newNull[i] = c.NullMap[idx]
				newDirty[i] = c.DirtyMap[idx]
			}
			c.BoolData = newData
			c.NullMap = newNull
			c.DirtyMap = newDirty
		case TypeDate:
			newData := make([]int64, t.RowCount)
			newNull := make([]bool, t.RowCount)
			newDirty := make([]bool, t.RowCount)
			for i, idx := range indices {
				newData[i] = c.DateData[idx]
				newNull[i] = c.NullMap[idx]
				newDirty[i] = c.DirtyMap[idx]
			}
			c.DateData = newData
			c.NullMap = newNull
			c.DirtyMap = newDirty
		}
	}

	return nil
}

func (t *Table) Limit(n int) *Table {
	if n >= t.RowCount {
		return t
	}
	result := NewTable(t.Name + "_limited")
	result.RowCount = n
	for _, col := range t.Columns {
		newCol := NewColumn(col.Name, col.DataType, n)
		switch col.DataType {
		case TypeInt:
			copy(newCol.IntData, col.IntData[:n])
		case TypeFloat:
			copy(newCol.FloatData, col.FloatData[:n])
		case TypeString:
			copy(newCol.StrData, col.StrData[:n])
		case TypeBool:
			copy(newCol.BoolData, col.BoolData[:n])
		case TypeDate:
			copy(newCol.DateData, col.DateData[:n])
		}
		copy(newCol.NullMap, col.NullMap[:n])
		copy(newCol.DirtyMap, col.DirtyMap[:n])
		result.Columns = append(result.Columns, newCol)
		result.ColIndex[newCol.Name] = len(result.Columns) - 1
	}
	return result
}

func (t *Table) Select(colNames []string) *Table {
	result := NewTable(t.Name + "_selected")
	result.RowCount = t.RowCount
	for _, name := range colNames {
		col := t.GetColumn(name)
		if col == nil {
			continue
		}
		newCol := NewColumn(col.Name, col.DataType, t.RowCount)
		switch col.DataType {
		case TypeInt:
			copy(newCol.IntData, col.IntData)
		case TypeFloat:
			copy(newCol.FloatData, col.FloatData)
		case TypeString:
			copy(newCol.StrData, col.StrData)
		case TypeBool:
			copy(newCol.BoolData, col.BoolData)
		case TypeDate:
			copy(newCol.DateData, col.DateData)
		}
		copy(newCol.NullMap, col.NullMap)
		copy(newCol.DirtyMap, col.DirtyMap)
		result.Columns = append(result.Columns, newCol)
		result.ColIndex[newCol.Name] = len(result.Columns) - 1
	}
	return result
}

func (t *Table) ToJSON(offset, limit int) []map[string]interface{} {
	if offset < 0 {
		offset = 0
	}
	end := offset + limit
	if end > t.RowCount {
		end = t.RowCount
	}
	result := make([]map[string]interface{}, end-offset)
	for i := offset; i < end; i++ {
		row := make(map[string]interface{})
		for _, col := range t.Columns {
			if col.NullMap[i] {
				row[col.Name] = nil
				continue
			}
			switch col.DataType {
			case TypeInt:
				row[col.Name] = col.IntData[i]
			case TypeFloat:
				row[col.Name] = col.FloatData[i]
			case TypeString:
				row[col.Name] = col.StrData[i]
			case TypeBool:
				row[col.Name] = col.BoolData[i]
			case TypeDate:
				row[col.Name] = col.DateData[i]
			}
		}
		result[i-offset] = row
	}
	return result
}

func (t *Table) ToJSONString(offset, limit int) string {
	data := t.ToJSON(offset, limit)
	b, _ := json.Marshal(data)
	return string(b)
}

func (t *Table) SchemaJSON() string {
	type colSchema struct {
		Name     string `json:"name"`
		DataType string `json:"type"`
	}
	schemas := make([]colSchema, len(t.Columns))
	for i, col := range t.Columns {
		schemas[i] = colSchema{Name: col.Name, DataType: col.DataType.String()}
	}
	b, _ := json.Marshal(schemas)
	return string(b)
}

func (t *Table) SummaryJSON() string {
	type colSummary struct {
		Name      string  `json:"name"`
		DataType  string  `json:"type"`
		Count     int     `json:"count"`
		NullCount int     `json:"nullCount"`
		DirtyCount int    `json:"dirtyCount"`
		Min       float64 `json:"min,omitempty"`
		Max       float64 `json:"max,omitempty"`
		Mean      float64 `json:"mean,omitempty"`
	}
	summaries := make([]colSummary, len(t.Columns))
	for i, col := range t.Columns {
		min, max, mean, count, nullCount, dirtyCount := col.Stats()
		s := colSummary{
			Name:       col.Name,
			DataType:   col.DataType.String(),
			Count:      count,
			NullCount:  nullCount,
			DirtyCount: dirtyCount,
		}
		if col.DataType == TypeInt || col.DataType == TypeFloat {
			s.Min = min
			s.Max = max
			s.Mean = mean
		}
		summaries[i] = s
	}
	b, _ := json.Marshal(summaries)
	return string(b)
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

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func (t *Table) Clone() *Table {
	clone := NewTable(t.Name)
	clone.RowCount = t.RowCount
	clone.ChunkSize = t.ChunkSize
	clone.IsChunked = t.IsChunked
	clone.ActiveChunk = t.ActiveChunk
	for _, col := range t.Columns {
		clonedCol := col.Clone()
		clone.Columns = append(clone.Columns, clonedCol)
		clone.ColIndex[clonedCol.Name] = len(clone.Columns) - 1
	}
	return clone
}

func (t *Table) AppendColumnsFrom(other *Table, prefix string) {
	for _, col := range other.Columns {
		name := col.Name
		if prefix != "" {
			name = prefix + "." + name
		}
		newCol := NewColumn(name, col.DataType, t.RowCount)
		for i := 0; i < t.RowCount; i++ {
			if i < col.Length {
				newCol.NullMap[i] = col.NullMap[i]
				newCol.DirtyMap[i] = col.DirtyMap[i]
				switch col.DataType {
				case TypeInt:
					newCol.IntData[i] = col.IntData[i]
				case TypeFloat:
					newCol.FloatData[i] = col.FloatData[i]
				case TypeString:
					newCol.StrData[i] = col.StrData[i]
				case TypeBool:
					newCol.BoolData[i] = col.BoolData[i]
				case TypeDate:
					newCol.DateData[i] = col.DateData[i]
				}
			} else {
				newCol.NullMap[i] = true
			}
		}
		t.Columns = append(t.Columns, newCol)
		t.ColIndex[newCol.Name] = len(t.Columns) - 1
	}
}
