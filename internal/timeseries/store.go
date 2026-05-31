package timeseries

import (
	"errors"
	"fmt"
	"sort"
	"sync"
	"time"
)

type DataPoint struct {
	Timestamp time.Time
	Value     float64
}

type Resolution string

const (
	ResolutionRaw      Resolution = "raw"
	Resolution1Minute  Resolution = "1m"
	Resolution5Minute  Resolution = "5m"
	Resolution15Minute Resolution = "15m"
	Resolution1Hour    Resolution = "1h"
	Resolution1Day     Resolution = "1d"
)

type DownsampleMethod string

const (
	DownsampleAverage DownsampleMethod = "avg"
	DownsampleMax     DownsampleMethod = "max"
	DownsampleMin     DownsampleMethod = "min"
	DownsampleSum     DownsampleMethod = "sum"
	DownsampleFirst   DownsampleMethod = "first"
	DownsampleLast    DownsampleMethod = "last"
)

type TimeSeriesStore struct {
	data map[Resolution][]DataPoint
	mu   sync.RWMutex
}

func NewTimeSeriesStore() *TimeSeriesStore {
	return &TimeSeriesStore{
		data: make(map[Resolution][]DataPoint),
	}
}

func (ts *TimeSeriesStore) Add(point DataPoint) {
	ts.mu.Lock()
	defer ts.mu.Unlock()

	raw := ts.data[ResolutionRaw]
	if len(raw) > 0 && point.Timestamp.Before(raw[len(raw)-1].Timestamp) {
		idx := sort.Search(len(raw), func(i int) bool {
			return raw[i].Timestamp.After(point.Timestamp)
		})
		raw = append(raw[:idx], append([]DataPoint{point}, raw[idx:]...)...)
	} else {
		raw = append(raw, point)
	}
	ts.data[ResolutionRaw] = raw
}

func (ts *TimeSeriesStore) AddBatch(points []DataPoint) {
	if len(points) == 0 {
		return
	}

	ts.mu.Lock()
	defer ts.mu.Unlock()

	ts.data[ResolutionRaw] = append(ts.data[ResolutionRaw], points...)
	sort.Slice(ts.data[ResolutionRaw], func(i, j int) bool {
		return ts.data[ResolutionRaw][i].Timestamp.Before(ts.data[ResolutionRaw][j].Timestamp)
	})
}

func (ts *TimeSeriesStore) Get(resolution Resolution, startTime, endTime time.Time) []DataPoint {
	if startTime.After(endTime) {
		return nil
	}

	ts.mu.RLock()
	defer ts.mu.RUnlock()

	data, ok := ts.data[resolution]
	if !ok || len(data) == 0 {
		return nil
	}

	startIdx := sort.Search(len(data), func(i int) bool {
		return !data[i].Timestamp.Before(startTime)
	})

	endIdx := sort.Search(len(data), func(i int) bool {
		return data[i].Timestamp.After(endTime)
	})

	if startIdx >= endIdx {
		return nil
	}

	result := make([]DataPoint, endIdx-startIdx)
	copy(result, data[startIdx:endIdx])
	return result
}

func (ts *TimeSeriesStore) Downsample(source, target Resolution, method DownsampleMethod) error {
	if source == target {
		return errors.New("source and target resolutions must be different")
	}

	ts.mu.Lock()
	defer ts.mu.Unlock()

	sourceData, ok := ts.data[source]
	if !ok || len(sourceData) == 0 {
		return errors.New("source resolution data not found")
	}

	interval, err := resolutionToInterval(target)
	if err != nil {
		return err
	}

	if interval <= 0 {
		return errors.New("invalid target resolution interval")
	}

	var downsampled []DataPoint
	bucketStart := sourceData[0].Timestamp.Truncate(interval)
	var bucketValues []float64

	for _, point := range sourceData {
		currentBucket := point.Timestamp.Truncate(interval)

		if currentBucket.After(bucketStart) {
			if len(bucketValues) > 0 {
				aggValue := aggregate(bucketValues, method)
				downsampled = append(downsampled, DataPoint{
					Timestamp: bucketStart,
					Value:     aggValue,
				})
			}
			bucketStart = currentBucket
			bucketValues = nil
		}

		bucketValues = append(bucketValues, point.Value)
	}

	if len(bucketValues) > 0 {
		aggValue := aggregate(bucketValues, method)
		downsampled = append(downsampled, DataPoint{
			Timestamp: bucketStart,
			Value:     aggValue,
		})
	}

	ts.data[target] = downsampled
	return nil
}

func (ts *TimeSeriesStore) Replace(resolution Resolution, data []DataPoint) {
	if data == nil {
		data = []DataPoint{}
	}

	ts.mu.Lock()
	defer ts.mu.Unlock()
	ts.data[resolution] = data
}

func (ts *TimeSeriesStore) Count(resolution Resolution) int {
	ts.mu.RLock()
	defer ts.mu.RUnlock()
	return len(ts.data[resolution])
}

func resolutionToInterval(res Resolution) (time.Duration, error) {
	switch res {
	case Resolution1Minute:
		return time.Minute, nil
	case Resolution5Minute:
		return 5 * time.Minute, nil
	case Resolution15Minute:
		return 15 * time.Minute, nil
	case Resolution1Hour:
		return time.Hour, nil
	case Resolution1Day:
		return 24 * time.Hour, nil
	default:
		return 0, fmt.Errorf("unsupported resolution: %s", res)
	}
}

func aggregate(values []float64, method DownsampleMethod) float64 {
	if len(values) == 0 {
		return 0
	}

	switch method {
	case DownsampleAverage:
		sum := 0.0
		for _, v := range values {
			sum += v
		}
		return sum / float64(len(values))
	case DownsampleMax:
		max := values[0]
		for _, v := range values[1:] {
			if v > max {
				max = v
			}
		}
		return max
	case DownsampleMin:
		min := values[0]
		for _, v := range values[1:] {
			if v < min {
				min = v
			}
		}
		return min
	case DownsampleSum:
		sum := 0.0
		for _, v := range values {
			sum += v
		}
		return sum
	case DownsampleFirst:
		return values[0]
	case DownsampleLast:
		return values[len(values)-1]
	default:
		sum := 0.0
		for _, v := range values {
			sum += v
		}
		return sum / float64(len(values))
	}
}

func (m *MultiResolutionStore) Write(metric string, point DataPoint) {
	if metric == "" {
		return
	}
	store := m.getOrCreateStore(metric)
	store.Add(point)
}

func (m *MultiResolutionStore) WriteBatch(metric string, points []DataPoint) {
	if metric == "" || len(points) == 0 {
		return
	}
	store := m.getOrCreateStore(metric)
	store.AddBatch(points)
}

func (m *MultiResolutionStore) Read(metric string, resolution Resolution, start, end time.Time) []DataPoint {
	if metric == "" || start.After(end) {
		return nil
	}

	m.mu.RLock()
	store, ok := m.stores[metric]
	m.mu.RUnlock()

	if !ok {
		return nil
	}
	return store.Get(resolution, start, end)
}

func (m *MultiResolutionStore) Compact(metric string) error {
	if metric == "" {
		return errors.New("metric name cannot be empty")
	}

	m.mu.RLock()
	store, ok := m.stores[metric]
	m.mu.RUnlock()

	if !ok {
		return errors.New("metric not found")
	}

	resolutions := []Resolution{Resolution1Minute, Resolution5Minute, Resolution15Minute, Resolution1Hour, Resolution1Day}
	source := ResolutionRaw

	for _, target := range resolutions {
		if err := store.Downsample(source, target, m.downsampleMethod); err != nil {
			return err
		}
		source = target
	}

	m.applyRetention(metric)
	return nil
}

func (m *MultiResolutionStore) Compress(metric string, algo CompressionAlgorithm) (*CompressedSeries, error) {
	if metric == "" {
		return nil, errors.New("metric name cannot be empty")
	}

	data := m.Read(metric, ResolutionRaw, time.Time{}, time.Now())
	if data == nil || len(data) == 0 {
		return nil, errors.New("no data to compress")
	}

	codec, err := NewCodecFactory().Create(algo)
	if err != nil {
		return nil, err
	}

	return codec.Compress(data)
}

func (m *MultiResolutionStore) Decompress(metric string, series *CompressedSeries) ([]DataPoint, error) {
	if series == nil {
		return nil, errors.New("compressed series cannot be nil")
	}

	codec, err := NewCodecFactory().Create(series.Algorithm)
	if err != nil {
		return nil, err
	}

	return codec.Decompress(series)
}

type RetentionPolicy struct {
	Resolution Resolution
	Retention  time.Duration
}

type MultiResolutionStore struct {
	stores           map[string]*TimeSeriesStore
	retentionPolicy  []RetentionPolicy
	downsampleMethod DownsampleMethod
	mu               sync.RWMutex
}

func NewMultiResolutionStore() *MultiResolutionStore {
	return &MultiResolutionStore{
		stores:           make(map[string]*TimeSeriesStore),
		downsampleMethod: DownsampleAverage,
	}
}

func (m *MultiResolutionStore) SetRetentionPolicy(policy []RetentionPolicy) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.retentionPolicy = policy
}

func (m *MultiResolutionStore) SetDownsampleMethod(method DownsampleMethod) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.downsampleMethod = method
}

func (m *MultiResolutionStore) getOrCreateStore(metric string) *TimeSeriesStore {
	m.mu.RLock()
	store, ok := m.stores[metric]
	m.mu.RUnlock()

	if !ok {
		m.mu.Lock()
		store = NewTimeSeriesStore()
		m.stores[metric] = store
		m.mu.Unlock()
	}
	return store
}

func (m *MultiResolutionStore) applyRetention(metric string) {
	m.mu.RLock()
	store, ok := m.stores[metric]
	m.mu.RUnlock()

	if !ok {
		return
	}

	now := time.Now()
	for _, policy := range m.retentionPolicy {
		cutoff := now.Add(-policy.Retention)
		data := store.Get(policy.Resolution, cutoff, now)
		store.Replace(policy.Resolution, data)
	}
}
