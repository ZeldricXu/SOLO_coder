package timeseries

import (
	"math"
	"testing"
	"time"
)

func TestDeltaCodec_EmptyInput(t *testing.T) {
	codec := NewDeltaCodec()
	_, err := codec.Compress([]DataPoint{})
	if err == nil {
		t.Error("expected error for empty input")
	}
}

func TestDeltaCodec_Decompress_NilSeries(t *testing.T) {
	codec := NewDeltaCodec()
	_, err := codec.Decompress(nil)
	if err == nil {
		t.Error("expected error for nil series")
	}
}

func TestDeltaCodec_Decompress_EmptyData(t *testing.T) {
	codec := NewDeltaCodec()
	_, err := codec.Decompress(&CompressedSeries{
		Algorithm: CompressionDelta,
		Data:      []byte{},
	})
	if err == nil {
		t.Error("expected error for empty data")
	}
}

func TestDeltaCodec_Decompress_NegativeCount(t *testing.T) {
	codec := NewDeltaCodec()
	_, err := codec.Decompress(&CompressedSeries{
		Algorithm: CompressionDelta,
		Data:      []byte{0x00},
		Count:     -1,
	})
	if err == nil {
		t.Error("expected error for negative count")
	}
}

func TestDeltaCodec_Decompress_WrongAlgorithm(t *testing.T) {
	codec := NewDeltaCodec()
	_, err := codec.Decompress(&CompressedSeries{
		Algorithm: CompressionGorilla,
		Data:      []byte{0x00, 0x00},
	})
	if err == nil {
		t.Error("expected error for wrong algorithm")
	}
}

func TestDeltaCodec_SinglePoint(t *testing.T) {
	points := []DataPoint{
		{Timestamp: time.Unix(0, 1000), Value: 42.0},
	}

	codec := NewDeltaCodec()
	compressed, err := codec.Compress(points)
	if err != nil {
		t.Fatalf("compress failed: %v", err)
	}

	decompressed, err := codec.Decompress(compressed)
	if err != nil {
		t.Fatalf("decompress failed: %v", err)
	}

	if len(decompressed) != 1 {
		t.Fatalf("expected 1 point, got %d", len(decompressed))
	}
	if decompressed[0].Value != 42.0 {
		t.Errorf("value mismatch")
	}
}

func TestDeltaCodec_NaNAndInf(t *testing.T) {
	points := []DataPoint{
		{Timestamp: time.Unix(0, 1000), Value: math.NaN()},
		{Timestamp: time.Unix(0, 2000), Value: math.Inf(1)},
		{Timestamp: time.Unix(0, 3000), Value: math.Inf(-1)},
	}

	codec := NewDeltaCodec()
	compressed, err := codec.Compress(points)
	if err != nil {
		t.Fatalf("compress failed: %v", err)
	}

	decompressed, err := codec.Decompress(compressed)
	if err != nil {
		t.Fatalf("decompress failed: %v", err)
	}

	if len(decompressed) != 3 {
		t.Fatalf("expected 3 points, got %d", len(decompressed))
	}
	if !math.IsNaN(decompressed[0].Value) {
		t.Error("expected NaN")
	}
	if !math.IsInf(decompressed[1].Value, 1) {
		t.Error("expected +Inf")
	}
	if !math.IsInf(decompressed[2].Value, -1) {
		t.Error("expected -Inf")
	}
}

func TestGorillaCodec_Decompress_NilSeries(t *testing.T) {
	codec := NewGorillaCodec()
	_, err := codec.Decompress(nil)
	if err == nil {
		t.Error("expected error for nil series")
	}
}

func TestGorillaCodec_Decompress_EmptyData(t *testing.T) {
	codec := NewGorillaCodec()
	_, err := codec.Decompress(&CompressedSeries{
		Algorithm: CompressionGorilla,
		Data:      []byte{},
	})
	if err == nil {
		t.Error("expected error for empty data")
	}
}

func TestGorillaCodec_Decompress_DataTooShort(t *testing.T) {
	codec := NewGorillaCodec()
	_, err := codec.Decompress(&CompressedSeries{
		Algorithm: CompressionGorilla,
		Data:      make([]byte, 10),
		Count:     1,
	})
	if err == nil {
		t.Error("expected error for data too short")
	}
}

func TestGorillaCodec_Decompress_NegativeCount(t *testing.T) {
	codec := NewGorillaCodec()
	_, err := codec.Decompress(&CompressedSeries{
		Algorithm: CompressionGorilla,
		Data:      make([]byte, 16),
		Count:     -1,
	})
	if err == nil {
		t.Error("expected error for negative count")
	}
}

func TestTimeSeriesStore_Get_InvalidTimeRange(t *testing.T) {
	store := NewTimeSeriesStore()
	store.Add(DataPoint{Timestamp: time.Now(), Value: 1.0})

	start := time.Now()
	end := start.Add(-time.Hour)
	result := store.Get(ResolutionRaw, start, end)
	if result != nil {
		t.Error("expected nil for invalid time range")
	}
}

func TestTimeSeriesStore_Add_OutOfOrder(t *testing.T) {
	store := NewTimeSeriesStore()

	t1 := time.Unix(0, 3000)
	t2 := time.Unix(0, 1000)
	t3 := time.Unix(0, 2000)

	store.Add(DataPoint{Timestamp: t1, Value: 3.0})
	store.Add(DataPoint{Timestamp: t2, Value: 1.0})
	store.Add(DataPoint{Timestamp: t3, Value: 2.0})

	data := store.Get(ResolutionRaw, time.Time{}, time.Now())
	if len(data) != 3 {
		t.Fatalf("expected 3 points, got %d", len(data))
	}

	if !data[0].Timestamp.Equal(t2) || data[0].Value != 1.0 {
		t.Error("first point should be t2")
	}
	if !data[1].Timestamp.Equal(t3) || data[1].Value != 2.0 {
		t.Error("second point should be t3")
	}
	if !data[2].Timestamp.Equal(t1) || data[2].Value != 3.0 {
		t.Error("third point should be t1")
	}
}

func TestTimeSeriesStore_AddBatch_Empty(t *testing.T) {
	store := NewTimeSeriesStore()
	store.AddBatch(nil)
	store.AddBatch([]DataPoint{})

	count := store.Count(ResolutionRaw)
	if count != 0 {
		t.Errorf("expected 0 points, got %d", count)
	}
}

func TestTimeSeriesStore_Downsample_SameResolution(t *testing.T) {
	store := NewTimeSeriesStore()
	store.Add(DataPoint{Timestamp: time.Now(), Value: 1.0})

	err := store.Downsample(ResolutionRaw, ResolutionRaw, DownsampleAverage)
	if err == nil {
		t.Error("expected error for same resolution")
	}
}

func TestTimeSeriesStore_Replace_NilData(t *testing.T) {
	store := NewTimeSeriesStore()
	store.Replace(ResolutionRaw, nil)

	count := store.Count(ResolutionRaw)
	if count != 0 {
		t.Errorf("expected 0 points after nil replace, got %d", count)
	}
}

func TestMultiResolutionStore_EmptyMetric(t *testing.T) {
	mr := NewMultiResolutionStore()

	err := mr.Write("", DataPoint{Timestamp: time.Now(), Value: 1.0})
	if err != nil {
		t.Logf("write with empty metric returned (expected nil return): %v", err)
	}

	result := mr.Read("", ResolutionRaw, time.Time{}, time.Now())
	if result != nil {
		t.Error("expected nil for empty metric read")
	}

	err = mr.Compact("")
	if err == nil {
		t.Error("expected error for empty metric compact")
	}
}

func TestMultiResolutionStore_WriteBatch_EmptyPoints(t *testing.T) {
	mr := NewMultiResolutionStore()
	mr.WriteBatch("metric", nil)
	mr.WriteBatch("metric", []DataPoint{})

	result := mr.Read("metric", ResolutionRaw, time.Time{}, time.Now())
	if result != nil {
		t.Error("expected nil after writing empty points")
	}
}

func TestMultiResolutionStore_Decompress_NilSeries(t *testing.T) {
	mr := NewMultiResolutionStore()
	_, err := mr.Decompress("metric", nil)
	if err == nil {
		t.Error("expected error for nil series")
	}
}

func TestMultiResolutionStore_Compress_NoData(t *testing.T) {
	mr := NewMultiResolutionStore()
	_, err := mr.Compress("nonexistent", CompressionDelta)
	if err == nil {
		t.Error("expected error for nonexistent metric")
	}
}

func TestCalculateStats_EmptyInput(t *testing.T) {
	stats := CalculateStats([]DataPoint{}, &CompressedSeries{Data: []byte{}})
	if stats.OriginalSize != 0 || stats.PointCount != 0 {
		t.Errorf("expected zero stats for empty input")
	}
}

func TestCodecFactory_UnsupportedAlgorithm(t *testing.T) {
	factory := NewCodecFactory()
	_, err := factory.Create("unsupported")
	if err == nil {
		t.Error("expected error for unsupported algorithm")
	}
}
