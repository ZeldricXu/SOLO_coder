package scheduler

import (
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/df1-96/experiment/internal/models"
	"github.com/df1-96/experiment/pkg/util"
)

type Sharder struct {
	mu          sync.RWMutex
	defaultMin  int64
	defaultMax  int64
	historical  map[int64][]int64
}

func NewSharder() *Sharder {
	return &Sharder{
		defaultMin: 100,
		defaultMax: 10000,
		historical: make(map[int64][]int64),
	}
}

func (s *Sharder) Shard(taskID int64, config ShardConfig) (*ShardResult, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if config.TotalParams <= 0 {
		return nil, fmt.Errorf("total params must be positive")
	}
	if config.WorkerCount <= 0 {
		return nil, fmt.Errorf("worker count must be positive")
	}

	chunkSize := s.calculateChunkSize(config)
	chunkCount := int(math.Ceil(float64(config.TotalParams) / float64(chunkSize)))

	if config.MinChunkSize > 0 && chunkSize < config.MinChunkSize {
		chunkSize = config.MinChunkSize
		chunkCount = int(math.Ceil(float64(config.TotalParams) / float64(chunkSize)))
	}
	if config.MaxChunkSize > 0 && chunkSize > config.MaxChunkSize {
		chunkSize = config.MaxChunkSize
		chunkCount = int(math.Ceil(float64(config.TotalParams) / float64(chunkSize)))
	}

	strategy := config.Strategy
	if strategy == "" {
		strategy = s.selectOptimalStrategy(config)
	}

	chunks, err := s.createChunks(taskID, config.TotalParams, chunkSize, chunkCount, strategy)
	if err != nil {
		return nil, err
	}

	return &ShardResult{
		Chunks:    chunks,
		Strategy:  strategy,
		ChunkSize: chunkSize,
		Total:     chunkCount,
		CreatedAt: time.Now(),
	}, nil
}

func (s *Sharder) calculateChunkSize(config ShardConfig) int64 {
	if config.TargetDuration > 0 && config.WorkerCount > 0 {
		estimatedRate := s.estimateProcessingRate()
		if estimatedRate > 0 {
			idealChunk := int64(config.TargetDuration.Seconds() * estimatedRate)
			return util.Clamp(idealChunk, s.defaultMin, s.defaultMax)
		}
	}

	baseSize := config.TotalParams / int64(config.WorkerCount)
	overProvision := 1.5
	optimalSize := int64(float64(baseSize) / overProvision)

	return util.Clamp(optimalSize, s.defaultMin, s.defaultMax)
}

func (s *Sharder) selectOptimalStrategy(config ShardConfig) ShardStrategy {
	if config.WorkerCount == 1 {
		return ShardStrategyIDRange
	}

	if config.TotalParams > int64(config.WorkerCount)*1000 {
		return ShardStrategyLoadBalance
	}

	if config.TotalParams%int64(config.WorkerCount) == 0 {
		return ShardStrategyHashMod
	}

	return ShardStrategyIDRange
}

func (s *Sharder) createChunks(taskID int64, totalParams, chunkSize int64, chunkCount int, strategy ShardStrategy) ([]*models.TaskChunk, error) {
	now := time.Now()

	switch strategy {
	case ShardStrategyIDRange:
		return s.createIDRangeChunks(taskID, totalParams, chunkSize, chunkCount, now)
	case ShardStrategyHashMod:
		return s.createHashModChunks(taskID, totalParams, chunkCount, now)
	case ShardStrategyLoadBalance:
		return s.createLoadBalanceChunks(taskID, totalParams, chunkSize, chunkCount, now)
	default:
		return s.createIDRangeChunks(taskID, totalParams, chunkSize, chunkCount, now)
	}
}

func (s *Sharder) createIDRangeChunks(taskID int64, totalParams, chunkSize int64, chunkCount int, now time.Time) ([]*models.TaskChunk, error) {
	chunks := make([]*models.TaskChunk, chunkCount)
	for i := 0; i < chunkCount; i++ {
		start := int64(i) * chunkSize
		end := util.Min(int64(i+1)*chunkSize, totalParams)
		if i == chunkCount-1 {
			end = totalParams
		}

		chunks[i] = &models.TaskChunk{
			ID:         util.GenerateID(),
			TaskID:     taskID,
			Index:      i,
			Total:      chunkCount,
			Status:     models.TaskStatusPending,
			StartRange: start,
			EndRange:   end,
			CreatedAt:  now,
			UpdatedAt:  now,
		}
	}
	return chunks, nil
}

func (s *Sharder) createHashModChunks(taskID int64, totalParams int64, chunkCount int, now time.Time) ([]*models.TaskChunk, error) {
	chunks := make([]*models.TaskChunk, chunkCount)
	baseSize := totalParams / int64(chunkCount)

	for i := 0; i < chunkCount; i++ {
		start := int64(i) * baseSize
		end := start + baseSize
		if i == chunkCount-1 {
			end = totalParams
		}

		chunks[i] = &models.TaskChunk{
			ID:         util.GenerateID(),
			TaskID:     taskID,
			Index:      i,
			Total:      chunkCount,
			Status:     models.TaskStatusPending,
			StartRange: start,
			EndRange:   end,
			CreatedAt:  now,
			UpdatedAt:  now,
		}
	}
	return chunks, nil
}

func (s *Sharder) createLoadBalanceChunks(taskID int64, totalParams, chunkSize int64, chunkCount int, now time.Time) ([]*models.TaskChunk, error) {
	chunks := make([]*models.TaskChunk, chunkCount)
	remaining := totalParams
	currentStart := int64(0)

	for i := 0; i < chunkCount; i++ {
		var currentChunkSize int64
		if i == chunkCount-1 {
			currentChunkSize = remaining
		} else {
			variance := int64(float64(chunkSize) * 0.2)
			adjustment := int64(util.HashInt(int64(i))%uint64(variance*2)) - variance
			currentChunkSize = util.Max(chunkSize+adjustment, chunkSize/2)
			currentChunkSize = util.Min(currentChunkSize, remaining-int64(chunkCount-i-1))
		}

		end := currentStart + currentChunkSize
		if end > totalParams {
			end = totalParams
		}

		chunks[i] = &models.TaskChunk{
			ID:         util.GenerateID(),
			TaskID:     taskID,
			Index:      i,
			Total:      chunkCount,
			Status:     models.TaskStatusPending,
			StartRange: currentStart,
			EndRange:   end,
			CreatedAt:  now,
			UpdatedAt:  now,
		}

		remaining -= currentChunkSize
		currentStart = end
	}

	return chunks, nil
}

func (s *Sharder) Reshard(taskID int64, existingChunks []*models.TaskChunk, newWorkerCount int, completedRatio float64) (*ShardResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if len(existingChunks) == 0 {
		return nil, fmt.Errorf("no existing chunks to reshard")
	}
	if newWorkerCount <= 0 {
		return nil, fmt.Errorf("new worker count must be positive")
	}

	totalParams := existingChunks[len(existingChunks)-1].EndRange
	unprocessedStart := int64(float64(totalParams) * completedRatio)

	if completedRatio >= 0.9 {
		return nil, fmt.Errorf("too much progress to reshard (%.1f%% done)", completedRatio*100)
	}

	remaining := totalParams - unprocessedStart
	newChunkSize := s.calculateChunkSize(ShardConfig{
		TotalParams: remaining,
		WorkerCount: newWorkerCount,
	})

	newChunkCount := int(math.Ceil(float64(remaining) / float64(newChunkSize)))
	if newChunkCount == 0 {
		newChunkCount = 1
		newChunkSize = remaining
	}

	newChunks := make([]*models.TaskChunk, 0, newChunkCount)
	now := time.Now()

	for i := 0; i < newChunkCount; i++ {
		start := unprocessedStart + int64(i)*newChunkSize
		end := util.Min(unprocessedStart+int64(i+1)*newChunkSize, totalParams)

		newChunks = append(newChunks, &models.TaskChunk{
			ID:         util.GenerateID(),
			TaskID:     taskID,
			Index:      len(existingChunks) + i,
			Total:      len(existingChunks) + newChunkCount,
			Status:     models.TaskStatusPending,
			StartRange: start,
			EndRange:   end,
			CreatedAt:  now,
			UpdatedAt:  now,
		})
	}

	return &ShardResult{
		Chunks:    newChunks,
		Strategy:  ShardStrategyLoadBalance,
		ChunkSize: newChunkSize,
		Total:     len(existingChunks) + newChunkCount,
		CreatedAt: now,
	}, nil
}

func (s *Sharder) GetChunkForID(chunks []*models.TaskChunk, paramID int64) *models.TaskChunk {
	for _, chunk := range chunks {
		if paramID >= chunk.StartRange && paramID < chunk.EndRange {
			return chunk
		}
	}
	return nil
}

func (s *Sharder) GetChunkForHash(chunks []*models.TaskChunk, hashKey string) *models.TaskChunk {
	if len(chunks) == 0 {
		return nil
	}
	idx := util.ConsistentHash(hashKey, len(chunks))
	if idx >= 0 && idx < len(chunks) {
		return chunks[idx]
	}
	return chunks[0]
}

func (s *Sharder) SetDefaultLimits(min, max int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.defaultMin = min
	s.defaultMax = max
}

func (s *Sharder) RecordProcessingTime(taskID int64, duration time.Duration, count int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if count > 0 && duration > 0 {
		rate := float64(count) / duration.Seconds()
		s.historical[taskID] = append(s.historical[taskID], int64(rate))
		if len(s.historical[taskID]) > 100 {
			s.historical[taskID] = s.historical[taskID][1:]
		}
	}
}

func (s *Sharder) estimateProcessingRate() float64 {
	var allRates []float64
	for _, rates := range s.historical {
		for _, r := range rates {
			allRates = append(allRates, float64(r))
		}
	}
	if len(allRates) == 0 {
		return 1000
	}
	return util.Average(allRates)
}

func (s *Sharder) DynamicAdjust(taskID int64, currentChunkSize int64, avgProcessTime time.Duration, targetDuration time.Duration) int64 {
	if avgProcessTime <= 0 || targetDuration <= 0 {
		return currentChunkSize
	}

	ratio := targetDuration.Seconds() / avgProcessTime.Seconds()
	newSize := int64(float64(currentChunkSize) * math.Sqrt(ratio))

	s.mu.RLock()
	minSize := s.defaultMin
	maxSize := s.defaultMax
	s.mu.RUnlock()

	return util.Clamp(newSize, minSize, maxSize)
}
