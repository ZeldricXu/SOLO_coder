package query

import (
	"fmt"
	"sort"
	"strings"

	"github.com/dataexplorer/store"
)

const BatchSize = 1024

type RowBatch struct {
	Columns []string
	Types   []store.DataType
	Rows    [][]interface{}
	Nulls   [][]bool
}

func NewRowBatch(columns []string, types []store.DataType) *RowBatch {
	return &RowBatch{
		Columns: columns,
		Types:   types,
		Rows:    make([][]interface{}, 0, BatchSize),
		Nulls:   make([][]bool, 0, BatchSize),
	}
}

func (b *RowBatch) AddRow(values []interface{}, nulls []bool) {
	b.Rows = append(b.Rows, values)
	b.Nulls = append(b.Nulls, nulls)
}

func (b *RowBatch) Len() int {
	return len(b.Rows)
}

func (b *RowBatch) IsEmpty() bool {
	return len(b.Rows) == 0
}

type Operator interface {
	Next() (*RowBatch, error)
	Close() error
	Schema() ([]string, []store.DataType)
}

type ScanOp struct {
	table     *store.Table
	columns   []string
	types     []store.DataType
	nextRow   int
	totalRows int
}

func NewScanOp(table *store.Table, columns []string) *ScanOp {
	cols := columns
	if len(cols) == 1 && cols[0] == "*" {
		cols = table.ColumnNames()
	}
	types := make([]store.DataType, len(cols))
	for i, name := range cols {
		col := table.GetColumn(name)
		if col != nil {
			types[i] = col.DataType
		}
	}
	return &ScanOp{
		table:     table,
		columns:   cols,
		types:     types,
		nextRow:   0,
		totalRows: table.RowCount,
	}
}

func (op *ScanOp) Schema() ([]string, []store.DataType) {
	return op.columns, op.types
}

func (op *ScanOp) Next() (*RowBatch, error) {
	if op.nextRow >= op.totalRows {
		return nil, nil
	}

	batch := NewRowBatch(op.columns, op.types)
	end := op.nextRow + BatchSize
	if end > op.totalRows {
		end = op.totalRows
	}

	for i := op.nextRow; i < end; i++ {
		row := make([]interface{}, len(op.columns))
		nulls := make([]bool, len(op.columns))
		for j, colName := range op.columns {
			col := op.table.GetColumn(colName)
			if col == nil || col.IsNull(i) {
				nulls[j] = true
				continue
			}
			switch col.DataType {
			case store.TypeInt:
				row[j] = col.IntData[i]
			case store.TypeFloat:
				row[j] = col.FloatData[i]
			case store.TypeString:
				row[j] = col.StrData[i]
			case store.TypeBool:
				row[j] = col.BoolData[i]
			case store.TypeDate:
				row[j] = col.DateData[i]
			}
		}
		batch.AddRow(row, nulls)
	}

	op.nextRow = end
	return batch, nil
}

func (op *ScanOp) Close() error {
	return nil
}

type FilterOp struct {
	input   Operator
	expr    WhereExpr
	eval    *Executor
	table   *store.Table
	colMap  map[string]int
}

func NewFilterOp(input Operator, expr WhereExpr, eval *Executor, table *store.Table) *FilterOp {
	cols, _ := input.Schema()
	colMap := make(map[string]int)
	for i, c := range cols {
		colMap[c] = i
	}
	return &FilterOp{
		input:  input,
		expr:   expr,
		eval:   eval,
		table:  table,
		colMap: colMap,
	}
}

func (op *FilterOp) Schema() ([]string, []store.DataType) {
	return op.input.Schema()
}

func (op *FilterOp) Next() (*RowBatch, error) {
	for {
		batch, err := op.input.Next()
		if err != nil {
			return nil, err
		}
		if batch == nil {
			return nil, nil
		}

		cols, types := op.input.Schema()
		result := NewRowBatch(cols, types)

		for i := 0; i < batch.Len(); i++ {
			if op.evalBatchRow(batch, i) {
				result.AddRow(batch.Rows[i], batch.Nulls[i])
			}
		}

		if !result.IsEmpty() {
			return result, nil
		}
	}
}

func (op *FilterOp) evalBatchRow(batch *RowBatch, rowIdx int) bool {
	return op.evalExprInBatch(batch, rowIdx, op.expr)
}

func (op *FilterOp) evalExprInBatch(batch *RowBatch, rowIdx int, expr WhereExpr) bool {
	switch ex := expr.(type) {
	case *BinaryExpr:
		switch ex.Op {
		case "AND":
			return op.evalExprInBatch(batch, rowIdx, ex.Left) && op.evalExprInBatch(batch, rowIdx, ex.Right)
		case "OR":
			return op.evalExprInBatch(batch, rowIdx, ex.Left) || op.evalExprInBatch(batch, rowIdx, ex.Right)
		case "NOT":
			return !op.evalExprInBatch(batch, rowIdx, ex.Right)
		}
	case *CompareExpr:
		colVal := op.getBatchValue(batch, rowIdx, ex.Col)
		return op.eval.compare(colVal, ex.Op, ex.Value)
	case *InExpr:
		colVal := op.getBatchValue(batch, rowIdx, ex.Col)
		found := false
		for _, v := range ex.Values {
			if op.eval.valuesEqual(colVal, v) {
				found = true
				break
			}
		}
		if ex.Negated {
			return !found
		}
		return found
	case *BetweenExpr:
		colVal := op.getBatchValue(batch, rowIdx, ex.Col)
		return op.eval.valueAsFloat(colVal) >= op.eval.valueAsFloat(ex.Low) &&
			op.eval.valueAsFloat(colVal) <= op.eval.valueAsFloat(ex.High)
	case *IsNullExpr:
		resolved := resolveColumnName(op.table, ex.Col)
		idx, ok := op.colMap[resolved]
		if !ok {
			return !ex.Negated
		}
		isNull := batch.Nulls[rowIdx][idx]
		if ex.Negated {
			return !isNull
		}
		return isNull
	case *LikeExpr:
		resolved := resolveColumnName(op.table, ex.Col)
		idx, ok := op.colMap[resolved]
		if !ok || batch.Nulls[rowIdx][idx] {
			return false
		}
		s, ok := batch.Rows[rowIdx][idx].(string)
		if !ok {
			return false
		}
		return matchLike(s, ex.Pattern)
	case *BoolExpr:
		return ex.Value
	}
	return false
}

func (op *FilterOp) getBatchValue(batch *RowBatch, rowIdx int, colName string) interface{} {
	resolved := resolveColumnName(op.table, colName)
	idx, ok := op.colMap[resolved]
	if !ok || batch.Nulls[rowIdx][idx] {
		return nil
	}
	return batch.Rows[rowIdx][idx]
}

func (op *FilterOp) Close() error {
	return op.input.Close()
}

type SelectOp struct {
	input     Operator
	columns   []string
	types     []store.DataType
	colIdxMap []int
}

func NewSelectOp(input Operator, columns []string, table *store.Table) *SelectOp {
	inputCols, inputTypes := input.Schema()
	inputMap := make(map[string]int)
	for i, c := range inputCols {
		inputMap[c] = i
	}

	cols := columns
	if len(cols) == 1 && cols[0] == "*" {
		cols = inputCols
	}

	types := make([]store.DataType, len(cols))
	colIdxMap := make([]int, len(cols))
	for i, name := range cols {
		resolved := resolveColumnName(table, name)
		if idx, ok := inputMap[resolved]; ok {
			colIdxMap[i] = idx
			types[i] = inputTypes[idx]
		} else {
			colIdxMap[i] = -1
			types[i] = store.TypeString
		}
	}

	return &SelectOp{
		input:     input,
		columns:   cols,
		types:     types,
		colIdxMap: colIdxMap,
	}
}

func (op *SelectOp) Schema() ([]string, []store.DataType) {
	return op.columns, op.types
}

func (op *SelectOp) Next() (*RowBatch, error) {
	batch, err := op.input.Next()
	if err != nil {
		return nil, err
	}
	if batch == nil {
		return nil, nil
	}

	result := NewRowBatch(op.columns, op.types)
	for i := 0; i < batch.Len(); i++ {
		row := make([]interface{}, len(op.columns))
		nulls := make([]bool, len(op.columns))
		for j, idx := range op.colIdxMap {
			if idx >= 0 {
				row[j] = batch.Rows[i][idx]
				nulls[j] = batch.Nulls[i][idx]
			} else {
				nulls[j] = true
			}
		}
		result.AddRow(row, nulls)
	}
	return result, nil
}

func (op *SelectOp) Close() error {
	return op.input.Close()
}

type AggregateOp struct {
	input       Operator
	groupCols   []string
	aggFunc     string
	aggCol      string
	percentile  float64
	table       *store.Table
	accumulator map[string]*groupAccum
	order       []string
	done        bool
}

type groupAccum struct {
	keys       []interface{}
	values     map[string]float64
	count      int
	distinct   map[interface{}]bool
	allValues  []float64
	percentile float64
}

func NewAggregateOp(input Operator, groupCols []string, aggFunc string, aggCol string, percentile float64, table *store.Table) *AggregateOp {
	return &AggregateOp{
		input:       input,
		groupCols:   groupCols,
		aggFunc:     aggFunc,
		aggCol:      aggCol,
		percentile:  percentile,
		table:       table,
		accumulator: make(map[string]*groupAccum),
	}
}

func (op *AggregateOp) Schema() ([]string, []store.DataType) {
	cols := make([]string, len(op.groupCols)+1)
	types := make([]store.DataType, len(op.groupCols)+1)
	for i, gc := range op.groupCols {
		cols[i] = gc
		col := op.table.GetColumn(resolveColumnName(op.table, gc))
		if col != nil {
			types[i] = col.DataType
		} else {
			types[i] = store.TypeString
		}
	}
	aggKey := op.aggFunc + "(" + op.aggCol + ")"
	cols[len(op.groupCols)] = aggKey
	types[len(op.groupCols)] = store.TypeFloat
	return cols, types
}

func (op *AggregateOp) Next() (*RowBatch, error) {
	if !op.done {
		if err := op.consumeAll(); err != nil {
			return nil, err
		}
		op.done = true
	}

	if len(op.order) == 0 {
		return nil, nil
	}

	cols, types := op.Schema()
	batch := NewRowBatch(cols, types)
	count := 0

	for len(op.order) > 0 && count < BatchSize {
		key := op.order[0]
		op.order = op.order[1:]
		ga := op.accumulator[key]

		row := make([]interface{}, len(cols))
		nulls := make([]bool, len(cols))

		for j, gk := range ga.keys {
			if gk != nil {
				row[j] = gk
			} else {
				nulls[j] = true
			}
		}

		aggKey := op.aggFunc + "(" + op.aggCol + ")"
		if val, ok := ga.values[aggKey]; ok {
			row[len(op.groupCols)] = val
		} else {
			nulls[len(op.groupCols)] = true
		}

		batch.AddRow(row, nulls)
		count++
	}

	return batch, nil
}

func (op *AggregateOp) consumeAll() error {
	inputCols, _ := op.input.Schema()
	colMap := make(map[string]int)
	for i, c := range inputCols {
		colMap[c] = i
	}

	resolvedGroupCols := make([]string, len(op.groupCols))
	for i, gc := range op.groupCols {
		resolvedGroupCols[i] = resolveColumnName(op.table, gc)
	}
	resolvedAggCol := resolveColumnName(op.table, op.aggCol)
	aggColIdx, aggColExists := colMap[resolvedAggCol]

	for {
		batch, err := op.input.Next()
		if err != nil {
			return err
		}
		if batch == nil {
			break
		}

		for i := 0; i < batch.Len(); i++ {
			key := ""
			keys := make([]interface{}, len(op.groupCols))
			for j, gc := range resolvedGroupCols {
				idx, ok := colMap[gc]
				if !ok || batch.Nulls[i][idx] {
					key += "NULL|"
					keys[j] = nil
					continue
				}
				v := batch.Rows[i][idx]
				keys[j] = v
				switch vt := v.(type) {
				case int64:
					key += fmt.Sprintf("%d|", vt)
				case float64:
					key += fmt.Sprintf("%f|", vt)
				case string:
					key += vt + "|"
				case bool:
					key += fmt.Sprintf("%t|", vt)
				}
			}

			ga, exists := op.accumulator[key]
			if !exists {
				ga = &groupAccum{
					keys:       keys,
					values:     make(map[string]float64),
					percentile: op.percentile,
				}
				if strings.ToUpper(op.aggFunc) == "COUNT_DISTINCT" {
					ga.distinct = make(map[interface{}]bool)
				}
				if strings.ToUpper(op.aggFunc) == "STDDEV" || strings.ToUpper(op.aggFunc) == "PERCENTILE" {
					ga.allValues = make([]float64, 0)
				}
				op.accumulator[key] = ga
				op.order = append(op.order, key)
			}
			ga.count++

			if aggColExists && !batch.Nulls[i][aggColIdx] {
				var val float64
				rawVal := batch.Rows[i][aggColIdx]
				switch v := rawVal.(type) {
				case int64:
					val = float64(v)
				case float64:
					val = v
				default:
					continue
				}

				aggKey := op.aggFunc + "(" + op.aggCol + ")"
				switch strings.ToUpper(op.aggFunc) {
				case "SUM", "AVG", "STDDEV":
					ga.values[aggKey] += val
				case "MIN":
					if _, ok := ga.values[aggKey]; !ok || val < ga.values[aggKey] {
						ga.values[aggKey] = val
					}
				case "MAX":
					if val > ga.values[aggKey] {
						ga.values[aggKey] = val
					}
				case "COUNT_DISTINCT":
					ga.distinct[rawVal] = true
				case "PERCENTILE":
					ga.allValues = append(ga.allValues, val)
				}

				if strings.ToUpper(op.aggFunc) == "STDDEV" {
					ga.allValues = append(ga.allValues, val)
				}
			}
		}
	}

	op.finalizeAggregates()
	return nil
}

func (op *AggregateOp) finalizeAggregates() {
	aggKey := op.aggFunc + "(" + op.aggCol + ")"
	aggFnUpper := strings.ToUpper(op.aggFunc)

	for _, key := range op.order {
		ga := op.accumulator[key]
		switch aggFnUpper {
		case "AVG":
			if ga.count > 0 {
				ga.values[aggKey] /= float64(ga.count)
			}
		case "STDDEV":
			ga.values[aggKey] = op.computeStdDev(key, ga)
		case "COUNT":
			ga.values[aggKey] = float64(ga.count)
		case "COUNT_DISTINCT":
			ga.values[aggKey] = float64(len(ga.distinct))
		case "PERCENTILE":
			if len(ga.allValues) > 0 {
				ga.values[aggKey] = op.computePercentile(ga)
			}
		}
	}
}

func (op *AggregateOp) computeStdDev(groupKey string, ga *groupAccum) float64 {
	if ga.count <= 1 {
		return 0
	}
	sum := ga.values[op.aggFunc+"("+op.aggCol+")"]
	mean := sum / float64(ga.count)
	variance := 0.0

	for _, v := range ga.allValues {
		diff := v - mean
		variance += diff * diff
	}

	return mathSqrt(variance / float64(ga.count-1))
}

func (op *AggregateOp) computePercentile(ga *groupAccum) float64 {
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
	return quickselect(arrCopy, idx)
}

func mathSqrt(v float64) float64 {
	if v < 0 {
		return 0
	}
	return floatSqrt(v)
}

func floatSqrt(x float64) float64 {
	z := 1.0
	for i := 0; i < 10; i++ {
		z -= (z*z - x) / (2 * z)
	}
	return z
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

func (op *AggregateOp) Close() error {
	return op.input.Close()
}

type SortOp struct {
	input    Operator
	sortCol  string
	asc      bool
	table    *store.Table
	buffer   [][][]interface{}
	nullsBuf [][][]bool
	done     bool
	sorted   []int
	nextIdx  int
}

func NewSortOp(input Operator, sortCol string, ascending bool, table *store.Table) *SortOp {
	return &SortOp{
		input:   input,
		sortCol: sortCol,
		asc:     ascending,
		table:   table,
	}
}

func (op *SortOp) Schema() ([]string, []store.DataType) {
	return op.input.Schema()
}

func (op *SortOp) Next() (*RowBatch, error) {
	if !op.done {
		if err := op.consumeAndSort(); err != nil {
			return nil, err
		}
		op.done = true
	}

	if op.nextIdx >= len(op.sorted) {
		return nil, nil
	}

	cols, types := op.input.Schema()
	batch := NewRowBatch(cols, types)
	count := 0

	for op.nextIdx < len(op.sorted) && count < BatchSize {
		globalIdx := op.sorted[op.nextIdx]
		batchIdx := globalIdx / BatchSize
		rowIdx := globalIdx % BatchSize
		batch.AddRow(op.buffer[batchIdx][rowIdx], op.nullsBuf[batchIdx][rowIdx])
		op.nextIdx++
		count++
	}

	return batch, nil
}

func (op *SortOp) consumeAndSort() error {
	cols, _ := op.input.Schema()
	colMap := make(map[string]int)
	for i, c := range cols {
		colMap[c] = i
	}
	resolved := resolveColumnName(op.table, op.sortCol)
	sortColIdx, hasSortCol := colMap[resolved]

	totalRows := 0
	for {
		batch, err := op.input.Next()
		if err != nil {
			return err
		}
		if batch == nil {
			break
		}
		op.buffer = append(op.buffer, batch.Rows)
		op.nullsBuf = append(op.nullsBuf, batch.Nulls)
		totalRows += batch.Len()
	}

	op.sorted = make([]int, totalRows)
	for i := range op.sorted {
		op.sorted[i] = i
	}

	sort.SliceStable(op.sorted, func(a, b int) bool {
		aBatch := a / BatchSize
		aRow := a % BatchSize
		bBatch := b / BatchSize
		bRow := b % BatchSize

		if !hasSortCol {
			return false
		}

		aNull := op.nullsBuf[aBatch][aRow][sortColIdx]
		bNull := op.nullsBuf[bBatch][bRow][sortColIdx]

		if aNull && bNull {
			return false
		}
		if aNull {
			return false
		}
		if bNull {
			return true
		}

		aVal := op.buffer[aBatch][aRow][sortColIdx]
		bVal := op.buffer[bBatch][bRow][sortColIdx]

		var less bool
		switch av := aVal.(type) {
		case int64:
			bv, ok := bVal.(int64)
			less = ok && av < bv
		case float64:
			bv, ok := bVal.(float64)
			less = ok && av < bv
		case string:
			bv, ok := bVal.(string)
			less = ok && av < bv
		case bool:
			bv, ok := bVal.(bool)
			less = ok && !av && bv
		default:
			less = false
		}

		if !op.asc {
			return !less
		}
		return less
	})

	return nil
}

func (op *SortOp) Close() error {
	return op.input.Close()
}

type LimitOp struct {
	input     Operator
	limit     int
	returned  int
}

func NewLimitOp(input Operator, limit int) *LimitOp {
	return &LimitOp{
		input: input,
		limit: limit,
	}
}

func (op *LimitOp) Schema() ([]string, []store.DataType) {
	return op.input.Schema()
}

func (op *LimitOp) Next() (*RowBatch, error) {
	if op.returned >= op.limit {
		return nil, nil
	}

	batch, err := op.input.Next()
	if err != nil {
		return nil, err
	}
	if batch == nil {
		return nil, nil
	}

	remaining := op.limit - op.returned
	if batch.Len() > remaining {
		cols, types := op.input.Schema()
		result := NewRowBatch(cols, types)
		for i := 0; i < remaining; i++ {
			result.AddRow(batch.Rows[i], batch.Nulls[i])
		}
		op.returned = op.limit
		return result, nil
	}

	op.returned += batch.Len()
	return batch, nil
}

func (op *LimitOp) Close() error {
	return op.input.Close()
}

func batchToTable(batch *RowBatch, name string) *store.Table {
	table := store.NewTable(name)
	for i, colName := range batch.Columns {
		table.AddColumn(colName, batch.Types[i])
	}

	totalRows := 0
	for !batch.IsEmpty() {
		for i := 0; i < batch.Len(); i++ {
			rowIdx := totalRows
			totalRows++
			table.SetRowCount(totalRows)
			for j := range batch.Columns {
				if batch.Nulls[i][j] {
					table.Columns[j].NullMap[rowIdx] = true
				} else {
					table.Columns[j].SetValue(rowIdx, batch.Rows[i][j])
				}
			}
		}
		var err error
		batch, err = nil, nil
		_ = err
		break
	}

	return table
}

func CollectToTable(op Operator, name string) (*store.Table, error) {
	defer op.Close()

	cols, types := op.Schema()
	table := store.NewTable(name)
	for i, colName := range cols {
		table.AddColumn(colName, types[i])
	}

	totalRows := 0
	for {
		batch, err := op.Next()
		if err != nil {
			return nil, err
		}
		if batch == nil {
			break
		}

		for i := 0; i < batch.Len(); i++ {
			rowIdx := totalRows
			totalRows++
			table.SetRowCount(totalRows)
			for j := range batch.Columns {
				if batch.Nulls[i][j] {
					table.Columns[j].NullMap[rowIdx] = true
				} else {
					table.Columns[j].SetValue(rowIdx, batch.Rows[i][j])
				}
			}
		}
	}

	return table, nil
}

type PipelineExecutor struct {
	IndexMgr *store.IndexManager
}

func NewPipelineExecutor(im *store.IndexManager) *PipelineExecutor {
	return &PipelineExecutor{IndexMgr: im}
}

func (e *PipelineExecutor) BuildPipeline(table *store.Table, stmt *SelectStmt) (Operator, error) {
	var op Operator = NewScanOp(table, []string{"*"})

	eval := &Executor{IndexMgr: e.IndexMgr}

	if stmt.Where != nil {
		op = NewFilterOp(op, stmt.Where, eval, table)
	}

	if len(stmt.GroupBy) > 0 {
		var pval float64
		if strings.ToUpper(stmt.AggFunc) == "PERCENTILE" {
			switch strings.ToUpper(stmt.AggCol) {
			case "P50":
				pval = 50
			case "P90":
				pval = 90
			case "P95":
				pval = 95
			case "P99":
				pval = 99
			default:
				pval = 50
			}
		}
		op = NewAggregateOp(op, stmt.GroupBy, stmt.AggFunc, stmt.AggCol, pval, table)
	} else if len(stmt.Columns) > 0 && !(len(stmt.Columns) == 1 && stmt.Columns[0] == "*") {
		op = NewSelectOp(op, stmt.Columns, table)
	}

	if stmt.OrderBy != "" {
		op = NewSortOp(op, stmt.OrderBy, stmt.OrderAsc, table)
	}

	if stmt.Limit >= 0 {
		op = NewLimitOp(op, stmt.Limit)
	}

	return op, nil
}

func (e *PipelineExecutor) Execute(table *store.Table, stmt *SelectStmt) (*store.Table, error) {
	op, err := e.BuildPipeline(table, stmt)
	if err != nil {
		return nil, err
	}
	return CollectToTable(op, table.Name+"_result")
}
