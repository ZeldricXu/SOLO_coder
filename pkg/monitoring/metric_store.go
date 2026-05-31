package monitoring

import (
	"sync"
)

type MetricsStore interface {
	Add(point *MetricPoint)
	GetByName(name string, startTime, endTime int64) []*MetricPoint
	GetByDimensions(metricName string, dimensions map[string]string) []*MetricPoint
	GetAll() []*MetricPoint
	Clear()
	Len() int
}

type InMemoryMetricsStore struct {
	points    []*MetricPoint
	maxPoints int
	mu        sync.RWMutex
}

func NewInMemoryMetricsStore(maxPoints int) *InMemoryMetricsStore {
	return &InMemoryMetricsStore{
		points:    make([]*MetricPoint, 0, maxPoints),
		maxPoints: maxPoints,
	}
}

func (s *InMemoryMetricsStore) Add(point *MetricPoint) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if len(s.points) >= s.maxPoints {
		s.points = s.points[1:]
	}
	s.points = append(s.points, point)
}

func (s *InMemoryMetricsStore) GetByName(name string, startTime, endTime int64) []*MetricPoint {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var result []*MetricPoint
	for _, p := range s.points {
		if p.Name != name {
			continue
		}
		if p.Timestamp < startTime || p.Timestamp > endTime {
			continue
		}
		result = append(result, p)
	}
	return result
}

func (s *InMemoryMetricsStore) GetByDimensions(metricName string, dimensions map[string]string) []*MetricPoint {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var result []*MetricPoint
	for _, p := range s.points {
		if p.Name != metricName {
			continue
		}
		if !p.MatchDimensions(dimensions) {
			continue
		}
		result = append(result, p)
	}
	return result
}

func (s *InMemoryMetricsStore) GetAll() []*MetricPoint {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make([]*MetricPoint, len(s.points))
	copy(result, s.points)
	return result
}

func (s *InMemoryMetricsStore) Clear() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.points = s.points[:0]
}

func (s *InMemoryMetricsStore) Len() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.points)
}
