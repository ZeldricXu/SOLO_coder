package store

import (
	"testing"
)

func TestChunk_NewAndSize(t *testing.T) {
	chunk := NewChunk(TypeInt, 100)
	if chunk.Length != 100 {
		t.Fatalf("expected length 100, got %d", chunk.Length)
	}
	if len(chunk.IntData) != 100 {
		t.Fatalf("expected IntData length 100, got %d", len(chunk.IntData))
	}
	if len(chunk.NullMap) != 100 {
		t.Fatalf("expected NullMap length 100, got %d", len(chunk.NullMap))
	}
}

func TestChunk_UnloadAndLoad(t *testing.T) {
	chunk := NewChunk(TypeFloat, 50)
	for i := 0; i < 50; i++ {
		chunk.FloatData[i] = float64(i)
	}

	initialSize := chunk.SizeBytes(TypeFloat)
	if initialSize == 0 {
		t.Fatal("expected non-zero initial size")
	}

	chunk.Unload(TypeFloat)
	if chunk.loaded {
		t.Fatal("expected chunk to be unloaded")
	}
	if chunk.FloatData != nil {
		t.Fatal("expected FloatData to be nil after unload")
	}
	if chunk.SizeBytes(TypeFloat) != 0 {
		t.Fatalf("expected size 0 after unload, got %d", chunk.SizeBytes(TypeFloat))
	}

	chunk.EnsureLoaded(TypeFloat)
	if !chunk.loaded {
		t.Fatal("expected chunk to be loaded")
	}
	if len(chunk.FloatData) != 50 {
		t.Fatalf("expected FloatData length 50 after reload, got %d", len(chunk.FloatData))
	}
}

func TestChunkedColumn_Basic(t *testing.T) {
	cc := NewChunkedColumn("test", TypeInt, 25000, 10000)
	if cc.Length != 25000 {
		t.Fatalf("expected length 25000, got %d", cc.Length)
	}
	if cc.ChunkSize != 10000 {
		t.Fatalf("expected chunk size 10000, got %d", cc.ChunkSize)
	}
	if len(cc.Chunks) != 3 {
		t.Fatalf("expected 3 chunks (25000/10000), got %d", len(cc.Chunks))
	}
	if cc.Chunks[0].Length != 10000 {
		t.Fatalf("expected first chunk length 10000, got %d", cc.Chunks[0].Length)
	}
	if cc.Chunks[2].Length != 5000 {
		t.Fatalf("expected last chunk length 5000, got %d", cc.Chunks[2].Length)
	}
}

func TestChunkedColumn_GetSetValue(t *testing.T) {
	cc := NewChunkedColumn("test", TypeInt, 25000, 10000)

	values := []int{0, 9999, 10000, 19999, 20000, 24999}
	for _, i := range values {
		cc.SetValue(i, int64(i*10))
	}

	for _, i := range values {
		v, ok := cc.GetValue(i).(int64)
		if !ok || v != int64(i*10) {
			t.Fatalf("at index %d: expected %d, got %v", i, i*10, cc.GetValue(i))
		}
	}
}

func TestChunkedColumn_NullHandling(t *testing.T) {
	cc := NewChunkedColumn("test", TypeFloat, 100, 50)

	cc.SetValue(10, nil)
	if !cc.IsNull(10) {
		t.Fatal("expected IsNull(10) to be true")
	}

	cc.SetValue(10, 3.14)
	if cc.IsNull(10) {
		t.Fatal("expected IsNull(10) to be false after setting value")
	}
	v, ok := cc.GetValue(10).(float64)
	if !ok || v != 3.14 {
		t.Fatalf("expected 3.14, got %v", cc.GetValue(10))
	}
}

func TestChunkedColumn_Stats(t *testing.T) {
	cc := NewChunkedColumn("test", TypeInt, 100, 30)
	for i := 0; i < 100; i++ {
		cc.SetValue(i, int64(i+1))
	}

	min, max, mean, count, nullCount, _ := cc.Stats()
	if min != 1.0 {
		t.Fatalf("expected min=1, got %f", min)
	}
	if max != 100.0 {
		t.Fatalf("expected max=100, got %f", max)
	}
	if mean != 50.5 {
		t.Fatalf("expected mean=50.5, got %f", mean)
	}
	if count != 100 {
		t.Fatalf("expected count=100, got %d", count)
	}
	if nullCount != 0 {
		t.Fatalf("expected nullCount=0, got %d", nullCount)
	}
}

func TestColumn_ChunkedMode(t *testing.T) {
	mbc := NewMemoryBudgetController()
	col := NewColumnWithChunking("test", TypeFloat, 100, mbc)

	if !col.IsChunked() {
		t.Fatal("expected column to be in chunked mode")
	}

	col.SetValue(0, 42.5)
	col.SetValue(1, 99.9)

	if col.GetFloat(0) != 42.5 {
		t.Fatalf("expected 42.5, got %f", col.GetFloat(0))
	}
	if col.GetFloat(1) != 99.9 {
		t.Fatalf("expected 99.9, got %f", col.GetFloat(1))
	}
	if col.IsNull(0) {
		t.Fatal("expected IsNull(0) to be false")
	}
}

func TestColumn_EnsureFallback(t *testing.T) {
	mbc := NewMemoryBudgetController()
	col := NewColumnWithChunking("test", TypeInt, 50, mbc)

	for i := 0; i < 50; i++ {
		col.SetValue(i, int64(i*2))
	}

	if len(col.IntData) != 0 {
		t.Fatal("expected IntData to be empty before fallback")
	}

	col.EnsureFallback()
	if len(col.IntData) != 50 {
		t.Fatalf("expected IntData length 50 after fallback, got %d", len(col.IntData))
	}
	for i := 0; i < 50; i++ {
		if col.IntData[i] != int64(i*2) {
			t.Fatalf("at %d: expected %d, got %d", i, i*2, col.IntData[i])
		}
	}
}

func TestMemoryBudgetController_Estimate(t *testing.T) {
	mbc := NewMemoryBudgetController()

	columns := []struct {
		Name     string
		DataType DataType
	}{
		{"id", TypeInt},
		{"value", TypeFloat},
		{"name", TypeString},
		{"active", TypeBool},
	}

	estimate := mbc.EstimateMemory(100000, columns)
	if estimate == 0 {
		t.Fatal("expected non-zero memory estimate")
	}

	t.Logf("Estimated memory for 100k rows × 4 cols: %d bytes (%.2f MB)", estimate, float64(estimate)/1024/1024)
}

func TestMemoryBudgetController_Threshold(t *testing.T) {
	mbc := NewMemoryBudgetController()
	mbc.SetThreshold(0.5)

	smallEstimate := uint64(1024)
	if mbc.ShouldUseChunkedMode(smallEstimate) {
		t.Fatal("small estimate should not trigger chunked mode")
	}

	hugeEstimate := uint64(1024 * 1024 * 1024 * 16)
	if !mbc.ShouldUseChunkedMode(hugeEstimate) {
		t.Fatal("huge estimate should trigger chunked mode")
	}
}

func TestChunk_StringType(t *testing.T) {
	chunk := NewChunk(TypeString, 10)
	chunk.StrData[0] = "hello"
	chunk.StrData[1] = "world"

	size := chunk.SizeBytes(TypeString)
	if size <= 0 {
		t.Fatal("expected positive size for string chunk")
	}
	t.Logf("String chunk size: %d bytes", size)

	chunk.Unload(TypeString)
	if chunk.StrData != nil {
		t.Fatal("expected StrData to be nil after unload")
	}
	if chunk.SizeBytes(TypeString) != 0 {
		t.Fatalf("expected size 0 after unload, got %d", chunk.SizeBytes(TypeString))
	}
}

func TestChunkedColumn_ChunkUnload(t *testing.T) {
	cc := NewChunkedColumn("test", TypeInt, 25000, 10000)

	initialSize := cc.TotalBytes
	if initialSize == 0 {
		t.Fatal("expected non-zero total bytes")
	}

	cc.UnloadChunk(1)
	if cc.Chunks[1].loaded {
		t.Fatal("expected chunk 1 to be unloaded")
	}
	if cc.TotalBytes >= initialSize {
		t.Fatal("expected total bytes to decrease after unloading chunk")
	}

	cc.LoadChunk(1)
	if !cc.Chunks[1].loaded {
		t.Fatal("expected chunk 1 to be loaded")
	}
	if cc.TotalBytes != initialSize {
		t.Fatalf("expected total bytes to be %d after reload, got %d", initialSize, cc.TotalBytes)
	}
}
