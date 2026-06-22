package scheduler

import (
	"testing"

	"github.com/df1-96/experiment/pkg/util"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestSharder_IDRange_100Params_3Workers(t *testing.T) {
	sharder := NewSharder()
	sharder.SetDefaultLimits(1, 1000)

	taskID := util.GenerateID()
	totalParams := int64(100)
	workerCount := 3

	result, err := sharder.Shard(taskID, ShardConfig{
		Strategy:     ShardStrategyIDRange,
		TotalParams:  totalParams,
		WorkerCount:  workerCount,
		MinChunkSize: 25,
		MaxChunkSize: 25,
	})

	require.NoError(t, err)
	require.NotNil(t, result)
	assert.Equal(t, ShardStrategyIDRange, result.Strategy)
	assert.GreaterOrEqual(t, len(result.Chunks), workerCount,
		"should have at least workerCount chunks for parallelism")

	chunkSizes := make([]int64, len(result.Chunks))
	for i, chunk := range result.Chunks {
		chunkSizes[i] = chunk.EndRange - chunk.StartRange
		assert.Greater(t, chunkSizes[i], int64(0), "chunk %d should be non-empty", i)
	}

	minSize := chunkSizes[0]
	maxSize := chunkSizes[0]
	for _, s := range chunkSizes {
		if s < minSize {
			minSize = s
		}
		if s > maxSize {
			maxSize = s
		}
	}
	assert.LessOrEqual(t, maxSize-minSize, int64(1),
		"chunk size deviation should be <= 1, got min=%d max=%d", minSize, maxSize)

	covered := make(map[int64]bool)
	for _, chunk := range result.Chunks {
		for id := chunk.StartRange; id < chunk.EndRange; id++ {
			assert.False(t, covered[id], "paramID %d appears in multiple chunks", id)
			covered[id] = true
		}
	}

	for id := int64(0); id < totalParams; id++ {
		assert.True(t, covered[id], "paramID %d should be covered", id)
	}
	assert.Len(t, covered, int(totalParams))
}

func TestSharder_HashMod_EvenDistribution(t *testing.T) {
	sharder := NewSharder()
	sharder.SetDefaultLimits(1, 10000)

	taskID := util.GenerateID()
	totalParams := int64(99)
	workerCount := 3

	result, err := sharder.Shard(taskID, ShardConfig{
		Strategy:     ShardStrategyHashMod,
		TotalParams:  totalParams,
		WorkerCount:  workerCount,
		MinChunkSize: 33,
		MaxChunkSize: 10000,
	})

	require.NoError(t, err)
	require.NotNil(t, result)
	assert.Equal(t, ShardStrategyHashMod, result.Strategy)

	for _, chunk := range result.Chunks {
		size := chunk.EndRange - chunk.StartRange
		expectedAvg := float64(totalParams) / float64(len(result.Chunks))
		variance := util.Abs(float64(size)-expectedAvg) / expectedAvg
		assert.LessOrEqual(t, variance, 0.2,
			"chunk size should be evenly distributed, got size=%d, expected_avg=%.2f, variance=%.2f%%",
			size, expectedAvg, variance*100)
	}

	covered := make(map[int64]bool)
	for _, chunk := range result.Chunks {
		for id := chunk.StartRange; id < chunk.EndRange; id++ {
			assert.False(t, covered[id], "paramID %d appears in multiple chunks", id)
			covered[id] = true
		}
	}
	assert.Len(t, covered, int(totalParams))
}

func TestSharder_LoadBalance_VarianceCheck(t *testing.T) {
	sharder := NewSharder()
	sharder.SetDefaultLimits(1, 10000)

	taskID := util.GenerateID()
	totalParams := int64(1000)
	workerCount := 5

	result, err := sharder.Shard(taskID, ShardConfig{
		Strategy:     ShardStrategyLoadBalance,
		TotalParams:  totalParams,
		WorkerCount:  workerCount,
		MinChunkSize: 100,
		MaxChunkSize: 500,
	})

	require.NoError(t, err)
	require.NotNil(t, result)
	assert.Equal(t, ShardStrategyLoadBalance, result.Strategy)

	chunkSizes := make([]float64, len(result.Chunks))
	var totalCovered int64
	for i, chunk := range result.Chunks {
		size := chunk.EndRange - chunk.StartRange
		chunkSizes[i] = float64(size)
		totalCovered += size
	}
	assert.Equal(t, totalParams, totalCovered, "all params should be covered")

	avgSize := float64(totalParams) / float64(len(result.Chunks))
	maxVariance := 0.0
	for _, size := range chunkSizes {
		variance := util.Abs(size-avgSize) / avgSize
		if variance > maxVariance {
			maxVariance = variance
		}
	}
	t.Logf("avgSize=%.2f, maxVariance=%.2f%%, chunks=%d", avgSize, maxVariance*100, len(result.Chunks))
	assert.LessOrEqual(t, maxVariance, 0.5,
		"chunk size variance should be within 50%%, got max=%.2f%%",
		maxVariance*100)
}

func TestSharder_ZeroParams_NoPanic(t *testing.T) {
	sharder := NewSharder()

	taskID := util.GenerateID()
	result, err := sharder.Shard(taskID, ShardConfig{
		Strategy:    ShardStrategyIDRange,
		TotalParams: 0,
		WorkerCount: 3,
	})

	assert.Error(t, err)
	assert.Nil(t, result)
	assert.Contains(t, err.Error(), "positive")
}

func TestSharder_NegativeWorkerCount(t *testing.T) {
	sharder := NewSharder()

	taskID := util.GenerateID()
	result, err := sharder.Shard(taskID, ShardConfig{
		Strategy:    ShardStrategyIDRange,
		TotalParams: 100,
		WorkerCount: -1,
	})

	assert.Error(t, err)
	assert.Nil(t, result)
	assert.Contains(t, err.Error(), "positive")

	result, err = sharder.Shard(taskID, ShardConfig{
		Strategy:    ShardStrategyIDRange,
		TotalParams: 100,
		WorkerCount: 0,
	})

	assert.Error(t, err)
	assert.Nil(t, result)
}

func TestSharder_Reshard_PartialProgress(t *testing.T) {
	sharder := NewSharder()
	sharder.SetDefaultLimits(1, 10000)

	taskID := util.GenerateID()
	totalParams := int64(1000)
	initialResult, err := sharder.Shard(taskID, ShardConfig{
		Strategy:     ShardStrategyIDRange,
		TotalParams:  totalParams,
		WorkerCount:  2,
		MinChunkSize: 1,
		MaxChunkSize: 10000,
	})
	require.NoError(t, err)
	require.NotNil(t, initialResult)

	completedRatio := 0.3
	newWorkerCount := 4

	reshardResult, err := sharder.Reshard(taskID, initialResult.Chunks, newWorkerCount, completedRatio)
	require.NoError(t, err)
	require.NotNil(t, reshardResult)

	unprocessedStart := int64(float64(totalParams) * completedRatio)
	for _, chunk := range reshardResult.Chunks {
		assert.GreaterOrEqual(t, chunk.StartRange, unprocessedStart,
			"new chunks should start after processed range, got start=%d expected >=%d",
			chunk.StartRange, unprocessedStart)
	}

	covered := make(map[int64]bool)
	for _, chunk := range initialResult.Chunks {
		for id := chunk.StartRange; id < chunk.EndRange && id < unprocessedStart; id++ {
			covered[id] = true
		}
	}
	for _, chunk := range reshardResult.Chunks {
		for id := chunk.StartRange; id < chunk.EndRange; id++ {
			covered[id] = true
		}
	}

	for id := int64(0); id < totalParams; id++ {
		assert.True(t, covered[id], "paramID %d should be covered after reshard", id)
	}
	assert.Len(t, covered, int(totalParams))
}

func TestSharder_Reshard_TooMuchProgress(t *testing.T) {
	sharder := NewSharder()

	taskID := util.GenerateID()
	initialResult, err := sharder.Shard(taskID, ShardConfig{
		Strategy:    ShardStrategyIDRange,
		TotalParams: 1000,
		WorkerCount: 2,
	})
	require.NoError(t, err)

	result, err := sharder.Reshard(taskID, initialResult.Chunks, 4, 0.95)
	assert.Error(t, err)
	assert.Nil(t, result)
	assert.Contains(t, err.Error(), "too much progress")
}

func TestSharder_Reshard_InvalidWorkerCount(t *testing.T) {
	sharder := NewSharder()

	taskID := util.GenerateID()
	initialResult, err := sharder.Shard(taskID, ShardConfig{
		Strategy:    ShardStrategyIDRange,
		TotalParams: 1000,
		WorkerCount: 2,
	})
	require.NoError(t, err)

	result, err := sharder.Reshard(taskID, initialResult.Chunks, 0, 0.3)
	assert.Error(t, err)
	assert.Nil(t, result)

	result, err = sharder.Reshard(taskID, initialResult.Chunks, -5, 0.3)
	assert.Error(t, err)
	assert.Nil(t, result)
}

func TestSharder_Reshard_NoExistingChunks(t *testing.T) {
	sharder := NewSharder()

	taskID := util.GenerateID()
	result, err := sharder.Reshard(taskID, nil, 3, 0.3)
	assert.Error(t, err)
	assert.Nil(t, result)
	assert.Contains(t, err.Error(), "no existing chunks")
}
