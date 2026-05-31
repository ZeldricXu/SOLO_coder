package tsdb

import (
	"encoding/binary"
	"errors"
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/datatrace/datatrace/internal/models"
	"github.com/google/uuid"
)

type TimeSeriesPoint struct {
	Timestamp time.Time
	Value     float64
}

type Resolution string

const (
	ResolutionRaw     Resolution = "raw"
	Resolution1Min    Resolution = "1min"
	Resolution5Min    Resolution = "5min"
	Resolution15Min   Resolution = "15min"
	Resolution1Hour   Resolution = "1hour"
	Resolution1Day    Resolution = "1day"
)

type AggregatedData struct {
	Min       float64
	Max       float64
	Avg       float64
	Sum       float64
	Count     int
	First     float64
	Last      float64
	Timestamp time.Time
}

type TimeSeries struct {
	ID         string
	Name       string
	Tags       map[string]string
	RawData    []TimeSeriesPoint
	Aggregated map[Resolution][]AggregatedData
	mu         sync.RWMutex
	Retention  time.Duration
}

type CompressionType string

const (
	CompressionGorilla CompressionType = "gorilla"
	CompressionDelta   CompressionType = "delta"
	CompressionRLE     CompressionType = "rle"
	CompressionNone    CompressionType = "none"
)

type TSDB struct {
	series         map[string]*TimeSeries
	compression    CompressionType
	defaultRes     []Resolution
	maxRawPoints   int
	mu             sync.RWMutex
}

func NewTSDB() *TSDB {
	return &TSDB{
		series:       make(map[string]*TimeSeries),
		compression:  CompressionGorilla,
		defaultRes:   []Resolution{Resolution1Min, Resolution5Min, Resolution15Min, Resolution1Hour, Resolution1Day},
		maxRawPoints: 100000,
	}
}

func (ts *TSDB) CreateSeries(name string, tags map[string]string, retention time.Duration) *TimeSeries {
	ts.mu.Lock()
	defer ts.mu.Unlock()

	id := uuid.New().String()
	series := &TimeSeries{
		ID:         id,
		Name:       name,
		Tags:       tags,
		RawData:    make([]TimeSeriesPoint, 0),
		Aggregated: make(map[Resolution][]AggregatedData),
		Retention:  retention,
	}

	ts.series[id] = series
	return series
}

func (ts *TSDB) AddPoint(seriesID string, timestamp time.Time, value float64) error {
	ts.mu.RLock()
	series, ok := ts.series[seriesID]
	ts.mu.RUnlock()

	if !ok {
		return errors.New("series not found")
	}

	series.mu.Lock()
	defer series.mu.Unlock()

	series.RawData = append(series.RawData, TimeSeriesPoint{
		Timestamp: timestamp,
		Value:     value,
	})

	if len(series.RawData) > ts.maxRawPoints {
		series.RawData = series.RawData[len(series.RawData)-ts.maxRawPoints:]
	}

	return nil
}

func (ts *TSDB) Query(seriesID string, start, end time.Time, resolution Resolution) ([]TimeSeriesPoint, []AggregatedData, error) {
	ts.mu.RLock()
	series, ok := ts.series[seriesID]
	ts.mu.RUnlock()

	if !ok {
		return nil, nil, errors.New("series not found")
	}

	series.mu.RLock()
	defer series.mu.RUnlock()

	rawPoints := make([]TimeSeriesPoint, 0)
	for _, p := range series.RawData {
		if (p.Timestamp.Equal(start) || p.Timestamp.After(start)) && (p.Timestamp.Equal(end) || p.Timestamp.Before(end)) {
			rawPoints = append(rawPoints, p)
		}
	}

	if resolution == ResolutionRaw {
		return rawPoints, nil, nil
	}

	aggregated := ts.downsample(rawPoints, resolution)
	return nil, aggregated, nil
}

func (ts *TSDB) downsample(points []TimeSeriesPoint, resolution Resolution) []AggregatedData {
	if len(points) == 0 {
		return nil
	}

	bucketDuration := ts.getBucketDuration(resolution)
	buckets := make(map[time.Time][]float64)

	for _, p := range points {
		bucket := p.Timestamp.Truncate(bucketDuration)
		buckets[bucket] = append(buckets[bucket], p.Value)
	}

	result := make([]AggregatedData, 0, len(buckets))
	for bucket, values := range buckets {
		agg := AggregatedData{
			Timestamp: bucket,
			Count:     len(values),
			Min:       math.Inf(1),
			Max:       math.Inf(-1),
			Sum:       0,
		}

		for i, v := range values {
			if v < agg.Min {
				agg.Min = v
			}
			if v > agg.Max {
				agg.Max = v
			}
			agg.Sum += v
			if i == 0 {
				agg.First = v
			}
			agg.Last = v
		}

		if agg.Count > 0 {
			agg.Avg = agg.Sum / float64(agg.Count)
		}

		result = append(result, agg)
	}

	return result
}

func (ts *TSDB) getBucketDuration(resolution Resolution) time.Duration {
	switch resolution {
	case Resolution1Min:
		return time.Minute
	case Resolution5Min:
		return 5 * time.Minute
	case Resolution15Min:
		return 15 * time.Minute
	case Resolution1Hour:
		return time.Hour
	case Resolution1Day:
		return 24 * time.Hour
	default:
		return time.Minute
	}
}

func (ts *TSDB) Compress(seriesID string) ([]byte, error) {
	ts.mu.RLock()
	series, ok := ts.series[seriesID]
	ts.mu.RUnlock()

	if !ok {
		return nil, errors.New("series not found")
	}

	series.mu.RLock()
	defer series.mu.RUnlock()

	switch ts.compression {
	case CompressionGorilla:
		return ts.gorillaCompress(series.RawData)
	case CompressionDelta:
		return ts.deltaCompress(series.RawData)
	case CompressionRLE:
		return ts.rleCompress(series.RawData)
	default:
		return ts.rawCompress(series.RawData)
	}
}

func (ts *TSDB) gorillaCompress(points []TimeSeriesPoint) ([]byte, error) {
	if len(points) == 0 {
		return nil, nil
	}

	buf := make([]byte, 0, len(points)*16)

	prevTime := points[0].Timestamp.UnixNano()
	prevValue := math.Float64bits(points[0].Value)

	binary.BigEndian.PutUint64(buf, uint64(prevTime))
	binary.BigEndian.PutUint64(buf, prevValue)

	for i := 1; i < len(points); i++ {
		currTime := points[i].Timestamp.UnixNano()
		currValue := math.Float64bits(points[i].Value)

		timeDelta := currTime - prevTime
		valueXOR := currValue ^ prevValue

		tsBuf := make([]byte, 8)
		binary.BigEndian.PutUint64(tsBuf, uint64(timeDelta))
		buf = append(buf, tsBuf...)

		valBuf := make([]byte, 8)
		binary.BigEndian.PutUint64(valBuf, valueXOR)
		buf = append(buf, valBuf...)

		prevTime = currTime
		prevValue = currValue
	}

	return buf, nil
}

func (ts *TSDB) deltaCompress(points []TimeSeriesPoint) ([]byte, error) {
	if len(points) == 0 {
		return nil, nil
	}

	buf := make([]byte, 0, len(points)*16)

	prevTime := points[0].Timestamp.UnixNano()
	prevValue := points[0].Value

	tsBuf := make([]byte, 8)
	binary.BigEndian.PutUint64(tsBuf, uint64(prevTime))
	buf = append(buf, tsBuf...)

	valBuf := make([]byte, 8)
	binary.BigEndian.PutUint64(valBuf, math.Float64bits(prevValue))
	buf = append(buf, valBuf...)

	for i := 1; i < len(points); i++ {
		timeDelta := points[i].Timestamp.UnixNano() - prevTime
		valueDelta := points[i].Value - prevValue

		tsBuf := make([]byte, 8)
		binary.BigEndian.PutUint64(tsBuf, uint64(timeDelta))
		buf = append(buf, tsBuf...)

		valBuf := make([]byte, 8)
		binary.BigEndian.PutUint64(valBuf, math.Float64bits(valueDelta))
		buf = append(buf, valBuf...)

		prevTime = points[i].Timestamp.UnixNano()
		prevValue = points[i].Value
	}

	return buf, nil
}

func (ts *TSDB) rleCompress(points []TimeSeriesPoint) ([]byte, error) {
	if len(points) == 0 {
		return nil, nil
	}

	buf := make([]byte, 0)

	count := 1
	currentValue := points[0].Value
	currentTime := points[0].Timestamp

	for i := 1; i < len(points); i++ {
		if points[i].Value == currentValue {
			count++
		} else {
			tsBuf := make([]byte, 8)
			binary.BigEndian.PutUint64(tsBuf, uint64(currentTime.UnixNano()))
			buf = append(buf, tsBuf...)

			valBuf := make([]byte, 8)
			binary.BigEndian.PutUint64(valBuf, math.Float64bits(currentValue))
			buf = append(buf, valBuf...)

			countBuf := make([]byte, 4)
			binary.BigEndian.PutUint32(countBuf, uint32(count))
			buf = append(buf, countBuf...)

			currentValue = points[i].Value
			currentTime = points[i].Timestamp
			count = 1
		}
	}

	tsBuf := make([]byte, 8)
	binary.BigEndian.PutUint64(tsBuf, uint64(currentTime.UnixNano()))
	buf = append(buf, tsBuf...)

	valBuf := make([]byte, 8)
	binary.BigEndian.PutUint64(valBuf, math.Float64bits(currentValue))
	buf = append(buf, valBuf...)

	countBuf := make([]byte, 4)
	binary.BigEndian.PutUint32(countBuf, uint32(count))
	buf = append(buf, countBuf...)

	return buf, nil
}

func (ts *TSDB) rawCompress(points []TimeSeriesPoint) ([]byte, error) {
	buf := make([]byte, 0, len(points)*16)

	for _, p := range points {
		tsBuf := make([]byte, 8)
		binary.BigEndian.PutUint64(tsBuf, uint64(p.Timestamp.UnixNano()))
		buf = append(buf, tsBuf...)

		valBuf := make([]byte, 8)
		binary.BigEndian.PutUint64(valBuf, math.Float64bits(p.Value))
		buf = append(buf, valBuf...)
	}

	return buf, nil
}

func (ts *TSDB) Cleanup() {
	ts.mu.Lock()
	defer ts.mu.Unlock()

	now := time.Now()
	for id, series := range ts.series {
		series.mu.Lock()
		cutoff := now.Add(-series.Retention)
		filtered := make([]TimeSeriesPoint, 0)
		for _, p := range series.RawData {
			if p.Timestamp.After(cutoff) {
				filtered = append(filtered, p)
			}
		}
		series.RawData = filtered
		series.mu.Unlock()

		if len(series.RawData) == 0 && series.Retention > 0 {
			delete(ts.series, id)
		}
	}
}

func (ts *TSDB) GetSeries(seriesID string) (*TimeSeries, bool) {
	ts.mu.RLock()
	defer ts.mu.RUnlock()

	series, ok := ts.series[seriesID]
	return series, ok
}

func (ts *TSDB) ListSeries() []*TimeSeries {
	ts.mu.RLock()
	defer ts.mu.RUnlock()

	series := make([]*TimeSeries, 0, len(ts.series))
	for _, s := range ts.series {
		series = append(series, s)
	}
	return series
}

func (ts *TSDB) SetCompression(compression CompressionType) {
	ts.mu.Lock()
	defer ts.mu.Unlock()
	ts.compression = compression
}

func (ts *TSDB) ToEntity() *models.Entity {
	return &models.Entity{
		ID:        uuid.New().String(),
		Type:      "tsdb",
		Status:    "active",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
}

func (a *AggregatedData) String() string {
	return fmt.Sprintf("Time: %v, Min: %.2f, Max: %.2f, Avg: %.2f, Count: %d",
		a.Timestamp, a.Min, a.Max, a.Avg, a.Count)
}
