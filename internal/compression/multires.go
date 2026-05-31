package compression

import (
	"errors"
	"fmt"
	"sync"
	"time"

	"streamsql/internal/common/logger"
	"streamsql/internal/common/models"
)

type ResolutionLevel struct {
	Name     string
	Interval time.Duration
	Retention time.Duration
	Priority int
}

type MultiResolutionStore struct {
	levels      []ResolutionLevel
	dataStore   map[string]map[string][]models.TimeSeriesPoint
	compressor  CompressionAlgorithm
	downsampler DownsamplingStrategy
	mu          sync.RWMutex
}

func NewMultiResolutionStore(levels []ResolutionLevel, compressor CompressionAlgorithm, downsampler DownsamplingStrategy) (*MultiResolutionStore, error) {
	if len(levels) == 0 {
		return nil, errors.New("resolution levels cannot be empty")
	}

	if compressor == nil {
		return nil, errors.New("compressor cannot be nil")
	}

	if downsampler == nil {
		return nil, errors.New("downsampler cannot be nil")
	}

	return &MultiResolutionStore{
		levels:      levels,
		dataStore:   make(map[string]map[string][]models.TimeSeriesPoint),
		compressor:  compressor,
		downsampler: downsampler,
	}, nil
}

func (m *MultiResolutionStore) Write(metric string, point models.TimeSeriesPoint) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, ok := m.dataStore[metric]; !ok {
		m.dataStore[metric] = make(map[string][]models.TimeSeriesPoint)
		for _, level := range m.levels {
			m.dataStore[metric][level.Name] = make([]models.TimeSeriesPoint, 0)
		}
	}

	for _, level := range m.levels {
		levelData := m.dataStore[metric][level.Name]

		if len(levelData) == 0 {
			m.dataStore[metric][level.Name] = append(levelData, point)
			continue
		}

		lastPoint := levelData[len(levelData)-1]
		if point.Timestamp.Sub(lastPoint.Timestamp) >= level.Interval {
			m.dataStore[metric][level.Name] = append(levelData, point)
		}
	}

	return nil
}

func (m *MultiResolutionStore) WriteBatch(metric string, points []models.TimeSeriesPoint) error {
	for _, p := range points {
		if err := m.Write(metric, p); err != nil {
			return err
		}
	}
	return nil
}

func (m *MultiResolutionStore) Read(metric string, startTime, endTime time.Time, maxPoints int) ([]models.TimeSeriesPoint, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if len(m.levels) == 0 {
		return nil, errors.New("no resolution levels configured")
	}

	if startTime.After(endTime) {
		return nil, fmt.Errorf("start time %v is after end time %v", startTime, endTime)
	}

	metricData, ok := m.dataStore[metric]
	if !ok {
		return []models.TimeSeriesPoint{}, nil
	}

	timeRange := endTime.Sub(startTime)
	selectedLevel := m.selectLevelForTimeRange(timeRange)

	levelData, ok := metricData[selectedLevel.Name]
	if !ok {
		return []models.TimeSeriesPoint{}, nil
	}

	result := make([]models.TimeSeriesPoint, 0)
	for _, p := range levelData {
		if (p.Timestamp.Equal(startTime) || p.Timestamp.After(startTime)) &&
		   (p.Timestamp.Equal(endTime) || p.Timestamp.Before(endTime)) {
			result = append(result, p)
		}
	}

	if len(result) > maxPoints && maxPoints > 0 {
		if m.downsampler == nil {
			return nil, errors.New("downsampler is not initialized")
		}
		result = m.downsampler.Downsample(result, maxPoints)
	}

	logger.Sugar().Infof("Read %d points for metric %s at level %s", len(result), metric, selectedLevel.Name)
	return result, nil
}

func (m *MultiResolutionStore) selectLevelForTimeRange(timeRange time.Duration) ResolutionLevel {
	for _, level := range m.levels {
		if timeRange <= level.Retention {
			return level
		}
	}

	return m.levels[len(m.levels)-1]
}

func (m *MultiResolutionStore) Compact() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now()
	for metric, metricData := range m.dataStore {
		for _, level := range m.levels {
			levelData, ok := metricData[level.Name]
			if !ok {
				continue
			}

			cutoff := now.Add(-level.Retention)
			filtered := make([]models.TimeSeriesPoint, 0, len(levelData))
			for _, p := range levelData {
				if p.Timestamp.After(cutoff) {
					filtered = append(filtered, p)
				}
			}

			removed := len(levelData) - len(filtered)
			if removed > 0 {
				logger.Sugar().Infof("Compacted %d points for metric %s at level %s", removed, metric, level.Name)
			}

			m.dataStore[metric][level.Name] = filtered
		}
	}

	return nil
}

func (m *MultiResolutionStore) Compress(metric string, levelName string) ([]byte, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	metricData, ok := m.dataStore[metric]
	if !ok {
		return nil, nil
	}

	levelData, ok := metricData[levelName]
	if !ok {
		return nil, nil
	}

	values := make([]float64, 0, len(levelData))
	for _, p := range levelData {
		for _, v := range p.Fields {
			if fv, ok := v.(float64); ok {
				values = append(values, fv)
			}
		}
	}

	return m.compressor.Encode(values)
}

func (m *MultiResolutionStore) GetStats() map[string]interface{} {
	m.mu.RLock()
	defer m.mu.RUnlock()

	stats := make(map[string]interface{})
	totalPoints := 0
	metricCount := len(m.dataStore)

	for _, metricData := range m.dataStore {
		for _, levelData := range metricData {
			totalPoints += len(levelData)
		}
	}

	stats["metric_count"] = metricCount
	stats["total_points"] = totalPoints
	stats["resolution_levels"] = len(m.levels)

	return stats
}
