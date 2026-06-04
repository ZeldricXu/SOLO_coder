package query

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/dataexplorer/store"
)

type Executor struct {
	IndexMgr *store.IndexManager
}

func NewExecutor(im *store.IndexManager) *Executor {
	return &Executor{IndexMgr: im}
}

func (e *Executor) Execute(tbl *store.Table, stmt *SelectStmt) (*store.Table, error) {
	pipeline := NewPipelineExecutor(e.IndexMgr)
	return pipeline.Execute(tbl, stmt)
}

func resolveColumnName(tbl *store.Table, colName string) string {
	if tbl.GetColumn(colName) != nil {
		return colName
	}
	parts := strings.SplitN(colName, ".", 2)
	if len(parts) == 2 {
		if tbl.GetColumn(parts[1]) != nil {
			return parts[1]
		}
	}
	for _, c := range tbl.Columns {
		if strings.HasSuffix(c.Name, "."+colName) {
			return c.Name
		}
	}
	return colName
}

func (e *Executor) ExecuteJoin(tables map[string]*store.Table, stmt *SelectStmt) (*store.Table, error) {
	leftTable, ok := tables[stmt.From]
	if !ok {
		return nil, fmt.Errorf("table %s not found", stmt.From)
	}
	current := leftTable
	currentName := stmt.From
	for _, join := range stmt.Joins {
		rightTable, ok := tables[join.Table]
		if !ok {
			return nil, fmt.Errorf("table %s not found", join.Table)
		}
		joined, err := e.hashJoin(current, currentName, rightTable, join.Table, join)
		if err != nil {
			return nil, err
		}
		current = joined
		currentName = "joined"
	}
	return e.Execute(current, stmt)
}

func (e *Executor) hashJoin(left *store.Table, leftName string, right *store.Table, rightName string, join *JoinClause) (*store.Table, error) {
	leftCol := stripTableName(join.LeftCol, leftName)
	rightCol := stripTableName(join.RightCol, rightName)
	leftColIdx := left.ColIndex[leftCol]
	rightColIdx := right.ColIndex[rightCol]
	buildTable := left
	buildColIdx := leftColIdx
	probeTable := right
	probeColIdx := rightColIdx
	leftIsBuild := true
	if join.JoinType == JoinLeft {
		buildTable = right
		buildColIdx = rightColIdx
		probeTable = left
		probeColIdx = leftColIdx
		leftIsBuild = false
	} else if right.RowCount < left.RowCount {
		buildTable = right
		buildColIdx = rightColIdx
		probeTable = left
		probeColIdx = leftColIdx
		leftIsBuild = false
	}
	hashMap := make(map[string][]int)
	for i := 0; i < buildTable.RowCount; i++ {
		key := e.getJoinKey(buildTable, buildColIdx, i)
		if key == "__NULL__" {
			continue
		}
		hashMap[key] = append(hashMap[key], i)
	}
	result := store.NewTable(leftName + "_" + rightName + "_join")
	dupCols := findDuplicateColumns(left, right)
	for i := 0; i < probeTable.RowCount; i++ {
		probeKey := e.getJoinKey(probeTable, probeColIdx, i)
		matches, ok := hashMap[probeKey]
		if !ok {
			if join.JoinType == JoinLeft {
				leftIdx, rightIdx := -1, -1
				if leftIsBuild {
					leftIdx = -1
					rightIdx = i
				} else {
					leftIdx = i
					rightIdx = -1
				}
				e.appendJoinRow(result, left, leftName, right, rightName, leftIdx, rightIdx, dupCols)
			}
			continue
		}
		for _, buildIdx := range matches {
			leftIdx, rightIdx := buildIdx, i
			if !leftIsBuild {
				leftIdx, rightIdx = i, buildIdx
			}
			e.appendJoinRow(result, left, leftName, right, rightName, leftIdx, rightIdx, dupCols)
		}
	}
	return result, nil
}

func (e *Executor) getJoinKey(tbl *store.Table, colIdx int, row int) string {
	if colIdx < 0 || colIdx >= len(tbl.Columns) {
		return "__NULL__"
	}
	col := tbl.Columns[colIdx]
	if col.IsNull(row) {
		return "__NULL__"
	}
	switch col.DataType {
	case store.TypeInt:
		return fmt.Sprintf("%d", col.IntData[row])
	case store.TypeFloat:
		return fmt.Sprintf("%f", col.FloatData[row])
	case store.TypeString:
		return col.StrData[row]
	case store.TypeBool:
		return fmt.Sprintf("%t", col.BoolData[row])
	case store.TypeDate:
		return fmt.Sprintf("%d", col.DateData[row])
	}
	return "__NULL__"
}

func (e *Executor) appendJoinRow(result *store.Table, left *store.Table, leftName string, right *store.Table, rightName string, leftIdx int, rightIdx int, dupCols map[string]bool) {
	rowIdx := result.RowCount
	result.RowCount++
	result.SetRowCount(result.RowCount)
	for _, col := range left.Columns {
		name := col.Name
		if dupCols[col.Name] {
			name = leftName + "." + col.Name
		}
		resultCol := result.GetColumn(name)
		if resultCol == nil {
			resultCol = result.AddColumn(name, col.DataType)
		}
		if leftIdx >= 0 && leftIdx < left.RowCount && !col.IsNull(leftIdx) {
			switch col.DataType {
			case store.TypeInt:
				resultCol.IntData[rowIdx] = col.IntData[leftIdx]
			case store.TypeFloat:
				resultCol.FloatData[rowIdx] = col.FloatData[leftIdx]
			case store.TypeString:
				resultCol.StrData[rowIdx] = col.StrData[leftIdx]
			case store.TypeBool:
				resultCol.BoolData[rowIdx] = col.BoolData[leftIdx]
			case store.TypeDate:
				resultCol.DateData[rowIdx] = col.DateData[leftIdx]
			}
		} else {
			resultCol.NullMap[rowIdx] = true
		}
	}
	for _, col := range right.Columns {
		name := col.Name
		if dupCols[col.Name] {
			name = rightName + "." + col.Name
		}
		resultCol := result.GetColumn(name)
		if resultCol == nil {
			resultCol = result.AddColumn(name, col.DataType)
		}
		if rightIdx >= 0 && rightIdx < right.RowCount && !col.IsNull(rightIdx) {
			switch col.DataType {
			case store.TypeInt:
				resultCol.IntData[rowIdx] = col.IntData[rightIdx]
			case store.TypeFloat:
				resultCol.FloatData[rowIdx] = col.FloatData[rightIdx]
			case store.TypeString:
				resultCol.StrData[rowIdx] = col.StrData[rightIdx]
			case store.TypeBool:
				resultCol.BoolData[rowIdx] = col.BoolData[rightIdx]
			case store.TypeDate:
				resultCol.DateData[rowIdx] = col.DateData[rightIdx]
			}
		} else {
			resultCol.NullMap[rowIdx] = true
		}
	}
}

func stripTableName(colName, tableName string) string {
	parts := strings.SplitN(colName, ".", 2)
	if len(parts) == 2 {
		return parts[1]
	}
	return colName
}

func findDuplicateColumns(left, right *store.Table) map[string]bool {
	dupCols := make(map[string]bool)
	leftCols := make(map[string]bool)
	for _, col := range left.Columns {
		leftCols[col.Name] = true
	}
	for _, col := range right.Columns {
		if leftCols[col.Name] {
			dupCols[col.Name] = true
		}
	}
	return dupCols
}

func (e *Executor) evalExpr(tbl *store.Table, expr WhereExpr) []bool {
	if e.IndexMgr != nil {
		if mask, ok := e.tryBitmapEval(tbl, expr); ok {
			return mask
		}
	}
	mask := make([]bool, tbl.RowCount)
	for i := 0; i < tbl.RowCount; i++ {
		mask[i] = e.evalRow(tbl, i, expr)
	}
	return mask
}

func (e *Executor) tryBitmapEval(tbl *store.Table, expr WhereExpr) ([]bool, bool) {
	switch ex := expr.(type) {
	case *BinaryExpr:
		switch ex.Op {
		case "AND":
			left, ok1 := e.tryBitmapEval(tbl, ex.Left)
			right, ok2 := e.tryBitmapEval(tbl, ex.Right)
			if ok1 && ok2 {
				for i := range left {
					left[i] = left[i] && right[i]
				}
				return left, true
			}
		case "OR":
			left, ok1 := e.tryBitmapEval(tbl, ex.Left)
			right, ok2 := e.tryBitmapEval(tbl, ex.Right)
			if ok1 && ok2 {
				for i := range left {
					left[i] = left[i] || right[i]
				}
				return left, true
			}
		case "NOT":
			child, ok := e.tryBitmapEval(tbl, ex.Right)
			if ok {
				for i := range child {
					child[i] = !child[i]
				}
				return child, true
			}
		}
	case *CompareExpr:
		resolved := resolveColumnName(tbl, ex.Col)
		if e.IndexMgr.HasIndex(resolved) {
			idx := e.IndexMgr.GetIndex(resolved)
			switch ex.Op {
			case "=":
				return idx.Lookup(fmt.Sprintf("%v", ex.Value)), true
			case "!=":
				return idx.LookupNot(fmt.Sprintf("%v", ex.Value)), true
			case "<", "<=", ">", ">=":
				return e.bitmapCompare(idx, ex.Op, ex.Value), true
			}
		}
	case *InExpr:
		resolved := resolveColumnName(tbl, ex.Col)
		if e.IndexMgr.HasIndex(resolved) {
			idx := e.IndexMgr.GetIndex(resolved)
			result := make([]bool, idx.TotalRows)
			for _, v := range ex.Values {
				bm := idx.Lookup(fmt.Sprintf("%v", v))
				for i := range result {
					result[i] = result[i] || bm[i]
				}
			}
			if ex.Negated {
				for i := range result {
					result[i] = !result[i]
				}
			}
			return result, true
		}
	case *BetweenExpr:
		resolved := resolveColumnName(tbl, ex.Col)
		if e.IndexMgr.HasIndex(resolved) {
			idx := e.IndexMgr.GetIndex(resolved)
			low := fmt.Sprintf("%v", ex.Low)
			high := fmt.Sprintf("%v", ex.High)
			result := idx.LookupRange(low, high)
			return result, true
		}
	case *IsNullExpr:
		resolved := resolveColumnName(tbl, ex.Col)
		if e.IndexMgr.HasIndex(resolved) {
			idx := e.IndexMgr.GetIndex(resolved)
			bm := idx.Lookup("__NULL__")
			if ex.Negated {
				result := make([]bool, len(bm))
				for i := range result {
					result[i] = !bm[i]
				}
				return result, true
			}
			return bm, true
		}
	}
	return nil, false
}

func (e *Executor) bitmapCompare(idx *store.BitmapIndex, op string, value interface{}) []bool {
	result := make([]bool, idx.TotalRows)
	strVal := fmt.Sprintf("%v", value)
	floatVal, floatErr := strconv.ParseFloat(strVal, 64)
	for val, bm := range idx.Bitmaps {
		var cmpFloat float64
		_, err := fmt.Sscanf(val, "%f", &cmpFloat)
		if err != nil || floatErr != nil {
			switch op {
			case "<":
				if val < strVal {
					for i := range bm {
						if bm[i] {
							result[i] = true
						}
					}
				}
			case "<=":
				if val <= strVal {
					for i := range bm {
						if bm[i] {
							result[i] = true
						}
					}
				}
			case ">":
				if val > strVal {
					for i := range bm {
						if bm[i] {
							result[i] = true
						}
					}
				}
			case ">=":
				if val >= strVal {
					for i := range bm {
						if bm[i] {
							result[i] = true
						}
					}
				}
			}
			continue
		}
		switch op {
		case "<":
			if cmpFloat < floatVal {
				for i := range bm {
					if bm[i] {
						result[i] = true
					}
				}
			}
		case "<=":
			if cmpFloat <= floatVal {
				for i := range bm {
					if bm[i] {
						result[i] = true
					}
				}
			}
		case ">":
			if cmpFloat > floatVal {
				for i := range bm {
					if bm[i] {
						result[i] = true
					}
				}
			}
		case ">=":
			if cmpFloat >= floatVal {
				for i := range bm {
					if bm[i] {
						result[i] = true
					}
				}
			}
		}
	}
	return result
}

func (e *Executor) evalRow(tbl *store.Table, row int, expr WhereExpr) bool {
	switch ex := expr.(type) {
	case *BinaryExpr:
		switch ex.Op {
		case "AND":
			return e.evalRow(tbl, row, ex.Left) && e.evalRow(tbl, row, ex.Right)
		case "OR":
			return e.evalRow(tbl, row, ex.Left) || e.evalRow(tbl, row, ex.Right)
		case "NOT":
			return !e.evalRow(tbl, row, ex.Right)
		}
	case *CompareExpr:
		colVal := e.getColValue(tbl, row, ex.Col)
		return e.compare(colVal, ex.Op, ex.Value)
	case *InExpr:
		colVal := e.getColValue(tbl, row, ex.Col)
		found := false
		for _, v := range ex.Values {
			if e.valuesEqual(colVal, v) {
				found = true
				break
			}
		}
		if ex.Negated {
			return !found
		}
		return found
	case *BetweenExpr:
		colVal := e.getColValue(tbl, row, ex.Col)
		return e.valueAsFloat(colVal) >= e.valueAsFloat(ex.Low) &&
			e.valueAsFloat(colVal) <= e.valueAsFloat(ex.High)
	case *IsNullExpr:
		resolved := resolveColumnName(tbl, ex.Col)
		col := tbl.GetColumn(resolved)
		if col == nil {
			return !ex.Negated
		}
		isNull := col.IsNull(row)
		if ex.Negated {
			return !isNull
		}
		return isNull
	case *LikeExpr:
		resolved := resolveColumnName(tbl, ex.Col)
		col := tbl.GetColumn(resolved)
		if col == nil {
			return false
		}
		if col.IsNull(row) {
			return false
		}
		s := col.GetString(row)
		return matchLike(s, ex.Pattern)
	case *BoolExpr:
		return ex.Value
	}
	return false
}

func (e *Executor) getColValue(tbl *store.Table, row int, colName string) interface{} {
	resolved := resolveColumnName(tbl, colName)
	col := tbl.GetColumn(resolved)
	if col == nil || col.IsNull(row) {
		return nil
	}
	switch col.DataType {
	case store.TypeInt:
		return col.IntData[row]
	case store.TypeFloat:
		return col.FloatData[row]
	case store.TypeString:
		return col.StrData[row]
	case store.TypeBool:
		return col.BoolData[row]
	case store.TypeDate:
		return col.DateData[row]
	}
	return nil
}

func (e *Executor) compare(colVal interface{}, op string, val interface{}) bool {
	if colVal == nil {
		return false
	}
	switch op {
	case "=":
		return e.valuesEqual(colVal, val)
	case "!=":
		return !e.valuesEqual(colVal, val)
	case "<":
		return e.valueAsFloat(colVal) < e.valueAsFloat(val)
	case "<=":
		return e.valueAsFloat(colVal) <= e.valueAsFloat(val)
	case ">":
		return e.valueAsFloat(colVal) > e.valueAsFloat(val)
	case ">=":
		return e.valueAsFloat(colVal) >= e.valueAsFloat(val)
	}
	return false
}

func (e *Executor) valuesEqual(a, b interface{}) bool {
	if a == nil || b == nil {
		return a == b
	}
	switch av := a.(type) {
	case int64:
		switch bv := b.(type) {
		case int64:
			return av == bv
		case float64:
			return float64(av) == bv
		}
	case float64:
		switch bv := b.(type) {
		case float64:
			return av == bv
		case int64:
			return av == float64(bv)
		}
	case string:
		if bs, ok := b.(string); ok {
			return av == bs
		}
	case bool:
		if bs, ok := b.(bool); ok {
			return av == bs
		}
	}
	return fmt.Sprintf("%v", a) == fmt.Sprintf("%v", b)
}

func (e *Executor) valueAsFloat(v interface{}) float64 {
	switch val := v.(type) {
	case int64:
		return float64(val)
	case float64:
		return val
	case int:
		return float64(val)
	case string:
		f, err := strconv.ParseFloat(val, 64)
		if err != nil {
			return 0
		}
		return f
	}
	return 0
}

func matchLike(s, pattern string) bool {
	regex := likeToRegex(pattern)
	return simpleMatch(s, regex)
}

func likeToRegex(pattern string) string {
	var buf strings.Builder
	for i := 0; i < len(pattern); i++ {
		ch := pattern[i]
		switch ch {
		case '%':
			buf.WriteString(".*")
		case '_':
			buf.WriteString(".")
		default:
			buf.WriteByte(ch)
		}
	}
	return buf.String()
}

func simpleMatch(s, pattern string) bool {
	si, pi := 0, 0
	starIdx, matchIdx := -1, 0
	for si < len(s) {
		if pi < len(pattern) && (pattern[pi] == s[si] || pattern[pi] == '.') {
			si++
			pi++
		} else if pi < len(pattern) && pattern[pi] == '*' {
			starIdx = pi
			matchIdx = si
			pi++
		} else if starIdx != -1 {
			pi = starIdx + 1
			matchIdx++
			si = matchIdx
		} else {
			return false
		}
	}
	for pi < len(pattern) && pattern[pi] == '*' {
		pi++
	}
	return pi == len(pattern)
}
