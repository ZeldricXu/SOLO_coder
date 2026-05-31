package compression

import (
	"errors"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"streamsql/internal/common/models"
)

func TestNewMultiResolutionStore_Validation(t *testing.T) {
	compressor := NewGorillaCompressor()
	downsampler := NewLTTBStrategy()

	t.Run("empty levels should return error", func(t *testing.T) {
		store, err := NewMultiResolutionStore([]ResolutionLevel{}, compressor, downsampler)
		assert.Error(t, err)
		assert.Nil(t, store)
		assert.Contains(t, err.Error(), "cannot be empty")
	})

	t.Run("nil compressor should return error", func(t *testing.T) {
		levels := []ResolutionLevel{
			{Name: "raw", Interval: time.Second, Retention: time.Hour},
		}
		store, err := NewMultiResolutionStore(levels, nil, downsampler)
		assert.Error(t, err)
		assert.Nil(t, store)
		assert.Contains(t, err.Error(), "compressor cannot be nil")
	})

	t.Run("nil downsampler should return error", func(t *testing.T) {
		levels := []ResolutionLevel{
			{Name: "raw", Interval: time.Second, Retention: time.Hour},
		}
		store, err := NewMultiResolutionStore(levels, compressor, nil)
		assert.Error(t, err)
		assert.Nil(t, store)
		assert.Contains(t, err.Error(), "downsampler cannot be nil")
	})

	t.Run("valid params should succeed", func(t *testing.T) {
		levels := []ResolutionLevel{
			{Name: "raw", Interval: time.Second, Retention: time.Hour},
		}
		store, err := NewMultiResolutionStore(levels, compressor, downsampler)
		assert.NoError(t, err)
		assert.NotNil(t, store)
	})
}

func TestMultiResolutionStore_Read_Validation(t *testing.T) {
	levels := []ResolutionLevel{
		{Name: "raw", Interval: time.Second, Retention: time.Hour},
		{Name: "1m", Interval: time.Minute, Retention: time.Hour * 24},
		{Name: "1d", Interval: time.Hour * 24, Retention: time.Hour * 24 * 30},
	}
	compressor := NewGorillaCompressor()
	downsampler := NewLTTBStrategy()
	store, _ := NewMultiResolutionStore(levels, compressor, downsampler)

	t.Run("start time after end time should return error", func(t *testing.T) {
		endTime := time.Now()
		startTime := endTime.Add(time.Hour)

		result, err := store.Read("test_metric", startTime, endTime, 100)
		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Contains(t, err.Error(), "after end time")
	})

	t.Run("non-existent metric should return empty result", func(t *testing.T) {
		endTime := time.Now()
		startTime := endTime.Add(-time.Hour)

		result, err := store.Read("non_existent", startTime, endTime, 100)
		assert.NoError(t, err)
		assert.Empty(t, result)
	})
}

func TestMultiResolutionStore_SelectLevelForTimeRange(t *testing.T) {
	levels := []ResolutionLevel{
		{Name: "raw", Interval: time.Second, Retention: time.Hour},
		{Name: "1m", Interval: time.Minute, Retention: time.Hour * 24},
		{Name: "1d", Interval: time.Hour * 24, Retention: time.Hour * 24 * 30},
	}
	compressor := NewGorillaCompressor()
	downsampler := NewLTTBStrategy()
	store, _ := NewMultiResolutionStore(levels, compressor, downsampler)

	t.Run("30 minute range should select raw level", func(t *testing.T) {
		level := store.selectLevelForTimeRange(30 * time.Minute)
		assert.Equal(t, "raw", level.Name)
	})

	t.Run("12 hour range should select 1m level", func(t *testing.T) {
		level := store.selectLevelForTimeRange(12 * time.Hour)
		assert.Equal(t, "1m", level.Name)
	})

	t.Run("7 day range should select 1d level", func(t *testing.T) {
		level := store.selectLevelForTimeRange(7 * 24 * time.Hour)
		assert.Equal(t, "1d", level.Name)
	})

	t.Run("exceed max retention should select last level", func(t *testing.T) {
		level := store.selectLevelForTimeRange(365 * 24 * time.Hour)
		assert.Equal(t, "1d", level.Name)
	})
}

func TestMultiResolutionStore_WriteAndRead(t *testing.T) {
	levels := []ResolutionLevel{
		{Name: "raw", Interval: time.Millisecond, Retention: time.Hour},
	}
	compressor := NewGorillaCompressor()
	downsampler := NewLTTBStrategy()
	store, err := NewMultiResolutionStore(levels, compressor, downsampler)
	assert.NoError(t, err)

	metric := "test_metric"
	now := time.Now()

	points := make([]models.TimeSeriesPoint, 10)
	for i := 0; i < 10; i++ {
		points[i] = models.TimeSeriesPoint{
			Timestamp: now.Add(time.Duration(i) * 10 * time.Millisecond),
			Fields:    map[string]interface{}{"value": float64(i)},
		}
		err := store.Write(metric, points[i])
		assert.NoError(t, err)
	}

	startTime := now.Add(-time.Second)
	endTime := now.Add(time.Second)

	result, err := store.Read(metric, startTime, endTime, 100)
	assert.NoError(t, err)
	assert.NotEmpty(t, result)
}

func TestMultiResolutionStore_Compact(t *testing.T) {
	levels := []ResolutionLevel{
		{Name: "raw", Interval: time.Second, Retention: 100 * time.Millisecond},
	}
	compressor := NewGorillaCompressor()
	downsampler := NewLTTBStrategy()
	store, _ := NewMultiResolutionStore(levels, compressor, downsampler)

	metric := "test_metric"
	oldTime := time.Now().Add(-time.Second)

	for i := 0; i < 5; i++ {
		point := models.TimeSeriesPoint{
			Timestamp: oldTime.Add(time.Duration(i) * time.Millisecond),
			Fields:    map[string]interface{}{"value": float64(i)},
		}
		_ = store.Write(metric, point)
	}

	stats := store.GetStats()
	assert.Equal(t, 5, stats["total_points"])

	time.Sleep(200 * time.Millisecond)
	err := store.Compact()
	assert.NoError(t, err)

	stats = store.GetStats()
	assert.Equal(t, 0, stats["total_points"])
}

func TestMultiResolutionStore_GetStats(t *testing.T) {
	levels := []ResolutionLevel{
		{Name: "raw", Interval: time.Second, Retention: time.Hour},
		{Name: "1m", Interval: time.Minute, Retention: time.Hour * 24},
	}
	compressor := NewGorillaCompressor()
	downsampler := NewLTTBStrategy()
	store, _ := NewMultiResolutionStore(levels, compressor, downsampler)

	stats := store.GetStats()
	assert.Equal(t, 0, stats["metric_count"])
	assert.Equal(t, 0, stats["total_points"])
	assert.Equal(t, 2, stats["resolution_levels"])

	point := models.TimeSeriesPoint{
		Timestamp: time.Now(),
		Fields:    map[string]interface{}{"value": 1.0},
	}
	_ = store.Write("metric1", point)
	_ = store.Write("metric2", point)

	stats = store.GetStats()
	assert.Equal(t, 2, stats["metric_count"])
	assert.Equal(t, 2, stats["total_points"])
}
