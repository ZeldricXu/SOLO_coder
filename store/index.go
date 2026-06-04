package store

import "fmt"

type BitmapIndex struct {
	ColumnName string
	Bitmaps    map[string][]bool
	TotalRows  int
}

func NewBitmapIndex(col *Column) *BitmapIndex {
	bi := &BitmapIndex{
		ColumnName: col.Name,
		Bitmaps:    make(map[string][]bool),
		TotalRows:  col.Length,
	}

	for i := 0; i < col.Length; i++ {
		var key string
		if col.NullMap[i] {
			key = "__NULL__"
		} else {
			switch col.DataType {
			case TypeInt:
				key = int64ToStr(col.IntData[i])
			case TypeFloat:
				key = float64ToStr(col.FloatData[i])
			case TypeString:
				key = col.StrData[i]
			case TypeBool:
				if col.BoolData[i] {
					key = "true"
				} else {
					key = "false"
				}
			case TypeDate:
				key = int64ToStr(col.DateData[i])
			}
		}
		if _, exists := bi.Bitmaps[key]; !exists {
			bi.Bitmaps[key] = make([]bool, col.Length)
		}
		bi.Bitmaps[key][i] = true
	}

	return bi
}

func (bi *BitmapIndex) Lookup(value string) []bool {
	if bm, ok := bi.Bitmaps[value]; ok {
		return bm
	}
	return make([]bool, bi.TotalRows)
}

func (bi *BitmapIndex) LookupNot(value string) []bool {
	bm := bi.Lookup(value)
	result := make([]bool, bi.TotalRows)
	for i := range result {
		result[i] = !bm[i]
	}
	return result
}

func (bi *BitmapIndex) LookupRange(low, high string) []bool {
	result := make([]bool, bi.TotalRows)
	for val, bm := range bi.Bitmaps {
		if val >= low && val <= high {
			for i := range bm {
				if bm[i] {
					result[i] = true
				}
			}
		}
	}
	return result
}

func (bi *BitmapIndex) And(a, b []bool) []bool {
	result := make([]bool, len(a))
	for i := range result {
		result[i] = a[i] && b[i]
	}
	return result
}

func (bi *BitmapIndex) Or(a, b []bool) []bool {
	result := make([]bool, len(a))
	for i := range result {
		result[i] = a[i] || b[i]
	}
	return result
}

type IndexManager struct {
	Indexes map[string]*BitmapIndex
}

func NewIndexManager() *IndexManager {
	return &IndexManager{
		Indexes: make(map[string]*BitmapIndex),
	}
}

func (im *IndexManager) BuildIndex(col *Column) {
	im.Indexes[col.Name] = NewBitmapIndex(col)
}

func (im *IndexManager) GetIndex(colName string) *BitmapIndex {
	return im.Indexes[colName]
}

func (im *IndexManager) HasIndex(colName string) bool {
	_, ok := im.Indexes[colName]
	return ok
}

func int64ToStr(v int64) string {
	return fmt.Sprintf("%d", v)
}

func float64ToStr(v float64) string {
	return fmt.Sprintf("%g", v)
}
