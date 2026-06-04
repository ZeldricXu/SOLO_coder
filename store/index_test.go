package store

import (
	"testing"
)

func TestBitmapIndex_Build(t *testing.T) {
	col := NewColumn("category", TypeString, 5)
	col.SetValue(0, "A")
	col.SetValue(1, "A")
	col.SetValue(2, "B")
	col.SetValue(3, "B")
	col.SetValue(4, "C")

	bi := NewBitmapIndex(col)

	if bi.ColumnName != "category" {
		t.Errorf("expected category, got %s", bi.ColumnName)
	}
	if bi.TotalRows != 5 {
		t.Errorf("expected 5 rows, got %d", bi.TotalRows)
	}
	if len(bi.Bitmaps) != 3 {
		t.Errorf("expected 3 unique values in index, got %d", len(bi.Bitmaps))
	}
}

func TestBitmapIndex_Lookup(t *testing.T) {
	col := NewColumn("category", TypeString, 5)
	col.SetValue(0, "A")
	col.SetValue(1, "A")
	col.SetValue(2, "B")
	col.SetValue(3, "B")
	col.SetValue(4, "C")

	bi := NewBitmapIndex(col)

	mask := bi.Lookup("A")
	expected := []bool{true, true, false, false, false}
	for i, e := range expected {
		if mask[i] != e {
			t.Errorf("index %d: expected %v, got %v", i, e, mask[i])
		}
	}
}

func TestBitmapIndex_LookupNot(t *testing.T) {
	col := NewColumn("category", TypeString, 5)
	col.SetValue(0, "A")
	col.SetValue(1, "A")
	col.SetValue(2, "B")
	col.SetValue(3, "B")
	col.SetValue(4, "C")

	bi := NewBitmapIndex(col)

	mask := bi.LookupNot("A")
	expected := []bool{false, false, true, true, true}
	for i, e := range expected {
		if mask[i] != e {
			t.Errorf("index %d: expected %v, got %v", i, e, mask[i])
		}
	}
}

func TestBitmapIndex_LookupNotFound(t *testing.T) {
	col := NewColumn("category", TypeString, 3)
	col.SetValue(0, "A")
	col.SetValue(1, "B")
	col.SetValue(2, "C")

	bi := NewBitmapIndex(col)

	mask := bi.Lookup("X")
	for i, v := range mask {
		if v {
			t.Errorf("index %d: expected false for non-existent value", i)
		}
	}
}

func TestBitmapIndex_And(t *testing.T) {
	col := NewColumn("x", TypeInt, 4)
	bi := NewBitmapIndex(col)

	a := []bool{true, true, false, false}
	b := []bool{true, false, true, false}
	result := bi.And(a, b)

	expected := []bool{true, false, false, false}
	for i, e := range expected {
		if result[i] != e {
			t.Errorf("index %d: expected %v, got %v", i, e, result[i])
		}
	}
}

func TestBitmapIndex_Or(t *testing.T) {
	col := NewColumn("x", TypeInt, 4)
	bi := NewBitmapIndex(col)

	a := []bool{true, true, false, false}
	b := []bool{true, false, true, false}
	result := bi.Or(a, b)

	expected := []bool{true, true, true, false}
	for i, e := range expected {
		if result[i] != e {
			t.Errorf("index %d: expected %v, got %v", i, e, result[i])
		}
	}
}

func TestBitmapIndex_LookupRange(t *testing.T) {
	col := NewColumn("letter", TypeString, 5)
	col.SetValue(0, "A")
	col.SetValue(1, "B")
	col.SetValue(2, "C")
	col.SetValue(3, "D")
	col.SetValue(4, "E")

	bi := NewBitmapIndex(col)

	mask := bi.LookupRange("B", "D")
	expected := []bool{false, true, true, true, false}
	for i, e := range expected {
		if mask[i] != e {
			t.Errorf("index %d: expected %v, got %v", i, e, mask[i])
		}
	}
}

func TestBitmapIndex_BuildIntColumn(t *testing.T) {
	col := NewColumn("value", TypeInt, 4)
	col.SetValue(0, int64(10))
	col.SetValue(1, int64(20))
	col.SetValue(2, int64(10))
	col.SetValue(3, int64(30))

	bi := NewBitmapIndex(col)

	if len(bi.Bitmaps) != 3 {
		t.Errorf("expected 3 unique values, got %d", len(bi.Bitmaps))
	}
}

func TestBitmapIndex_WithNulls(t *testing.T) {
	col := NewColumn("value", TypeString, 4)
	col.SetValue(0, "A")
	col.NullMap[1] = true
	col.SetValue(2, "B")
	col.NullMap[3] = true

	bi := NewBitmapIndex(col)

	nullMask := bi.Lookup("__NULL__")
	if nullMask[0] || !nullMask[1] || nullMask[2] || !nullMask[3] {
		t.Errorf("null mask incorrect: %v", nullMask)
	}
}

func TestIndexManager_BuildAndGet(t *testing.T) {
	im := NewIndexManager()
	col := NewColumn("test", TypeString, 3)
	col.SetValue(0, "A")
	col.SetValue(1, "B")
	col.SetValue(2, "C")

	if im.HasIndex("test") {
		t.Error("should not have index before build")
	}

	im.BuildIndex(col)

	if !im.HasIndex("test") {
		t.Error("should have index after build")
	}

	idx := im.GetIndex("test")
	if idx == nil {
		t.Error("GetIndex returned nil")
	}

	noIdx := im.GetIndex("nonexistent")
	if noIdx != nil {
		t.Error("GetIndex for non-existent should be nil")
	}
}

func TestIndexManager_Multiple(t *testing.T) {
	im := NewIndexManager()

	col1 := NewColumn("a", TypeString, 3)
	col2 := NewColumn("b", TypeString, 3)

	im.BuildIndex(col1)
	im.BuildIndex(col2)

	if !im.HasIndex("a") || !im.HasIndex("b") {
		t.Error("both indices should exist")
	}
}
