package timeseries

import (
	"bytes"
	"encoding/binary"
	"encoding/gob"
	"fmt"
	"math"
	"sort"
	"sync"
	"time"

	"session172/internal/logger"
	"session172/pkg/models"
	"session172/pkg/utils"
)

type Compressor struct {
	mu            sync.RWMutex
	blocks        map[string]*models.CompressedBlock
	resolutions   []time.Duration
	maxBlockSize  int
}

type DownsamplingStrategy string

const (
	Average   DownsamplingStrategy = "average"
	Min       DownsamplingStrategy = "min"
	Max       DownsamplingStrategy = "max"
	Sum       DownsamplingStrategy = "sum"
	First     DownsamplingStrategy = "first"
	Last      DownsamplingStrategy = "last"
	Median    DownsamplingStrategy = "median"
)

type MultiResolutionStore struct {
	mu         sync.RWMutex
	rawData    []models.TimeSeriesPoint
	highRes    []models.TimeSeriesPoint
	mediumRes  []models.TimeSeriesPoint
	lowRes     []models.TimeSeriesPoint
}

var (
	compressorInstance *Compressor
	compressorOnce     sync.Once
)

func NewCompressor() *Compressor {
	compressorOnce.Do(func() {
		compressorInstance = &Compressor{
			blocks:       make(map[string]*models.CompressedBlock),
			resolutions:  []time.Duration{time.Second, time.Minute, time.Hour, 24 * time.Hour},
			maxBlockSize: 1000,
		}
	})
	return compressorInstance
}

func GetCompressor() *Compressor {
	if compressorInstance == nil {
		return NewCompressor()
	}
	return compressorInstance
}

func (c *Compressor) Compress(points []models.TimeSeriesPoint, resolution time.Duration) (*models.CompressedBlock, error) {
	if len(points) == 0 {
		return nil, fmt.Errorf("no points to compress")
	}

	sort.Slice(points, func(i, j int) bool {
		return points[i].Timestamp.Before(points[j].Timestamp)
	})

	compressed := &models.CompressedBlock{
		ID:         utils.GenerateID("blk"),
		Metric:     getMetricFromPoints(points),
		StartTime:  points[0].Timestamp,
		EndTime:    points[len(points)-1].Timestamp,
		Resolution: resolution.String(),
		Count:      len(points),
		Min:        math.Inf(1),
		Max:        math.Inf(-1),
	}

	var buf bytes.Buffer
	enc := gob.NewEncoder(&buf)

	values := make([]float64, len(points))
	timestamps := make([]int64, len(points))

	for i, p := range points {
		values[i] = p.Value
		timestamps[i] = p.Timestamp.UnixNano()

		if p.Value < compressed.Min {
			compressed.Min = p.Value
		}
		if p.Value > compressed.Max {
			compressed.Max = p.Value
		}
		compressed.Sum += p.Value
	}

	encodedValues := encodeDelta(values)
	encodedTimestamps := encodeDeltaInt64(timestamps)

	err := enc.Encode(map[string]interface{}{
		"values":     encodedValues,
		"timestamps": encodedTimestamps,
		"tags":       points[0].Tags,
	})
	if err != nil {
		return nil, fmt.Errorf("failed to encode: %w", err)
	}

	compressed.Data = buf.Bytes()

	c.mu.Lock()
	c.blocks[compressed.ID] = compressed
	c.mu.Unlock()

	return compressed, nil
}

func (c *Compressor) Decompress(block *models.CompressedBlock) ([]models.TimeSeriesPoint, error) {
	buf := bytes.NewBuffer(block.Data)
	dec := gob.NewDecoder(buf)

	var data map[string]interface{}
	err := dec.Decode(&data)
	if err != nil {
		return nil, fmt.Errorf("failed to decode: %w", err)
	}

	encodedValues, _ := data["values"].([]float64)
	encodedTimestamps, _ := data["timestamps"].([]int64)
	tags, _ := data["tags"].(map[string]string)

	values := decodeDelta(encodedValues)
	timestamps := decodeDeltaInt64(encodedTimestamps)

	points := make([]models.TimeSeriesPoint, len(values))
	for i := range values {
		points[i] = models.TimeSeriesPoint{
			Timestamp: time.Unix(0, timestamps[i]),
			Value:     values[i],
			Tags:      tags,
		}
	}

	return points, nil
}

func (c *Compressor) Downsample(points []models.TimeSeriesPoint, targetResolution time.Duration, strategy DownsamplingStrategy) []models.TimeSeriesPoint {
	if len(points) == 0 {
		return points
	}

	sort.Slice(points, func(i, j int) bool {
		return points[i].Timestamp.Before(points[j].Timestamp)
	})

	buckets := make(map[int64][]models.TimeSeriesPoint)

	for _, p := range points {
		bucketKey := p.Timestamp.Truncate(targetResolution).Unix()
		buckets[bucketKey] = append(buckets[bucketKey], p)
	}

	downsampled := make([]models.TimeSeriesPoint, 0, len(buckets))

	for bucketKey, bucketPoints := range buckets {
		if len(bucketPoints) == 0 {
			continue
		}

		var value float64
		switch strategy {
		case Average:
			value = average(bucketPoints)
		case Min:
			value = minimum(bucketPoints)
		case Max:
			value = maximum(bucketPoints)
		case Sum:
			value = sum(bucketPoints)
		case First:
			value = bucketPoints[0].Value
		case Last:
			value = bucketPoints[len(bucketPoints)-1].Value
		case Median:
			value = median(bucketPoints)
		default:
			value = average(bucketPoints)
		}

		downsampled = append(downsampled, models.TimeSeriesPoint{
			Timestamp: time.Unix(bucketKey, 0),
			Value:     value,
			Tags:      bucketPoints[0].Tags,
		})
	}

	sort.Slice(downsampled, func(i, j int) bool {
		return downsampled[i].Timestamp.Before(downsampled[j].Timestamp)
	})

	return downsampled
}

func (c *Compressor) GetBlock(id string) (*models.CompressedBlock, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	block, ok := c.blocks[id]
	return block, ok
}

func (c *Compressor) GetAllBlocks() []*models.CompressedBlock {
	c.mu.RLock()
	defer c.mu.RUnlock()
	blocks := make([]*models.CompressedBlock, 0, len(c.blocks))
	for _, block := range c.blocks {
		blocks = append(blocks, block)
	}
	return blocks
}

func NewMultiResolutionStore() *MultiResolutionStore {
	return &MultiResolutionStore{
		rawData:   make([]models.TimeSeriesPoint, 0),
		highRes:   make([]models.TimeSeriesPoint, 0),
		mediumRes: make([]models.TimeSeriesPoint, 0),
		lowRes:    make([]models.TimeSeriesPoint, 0),
	}
}

func (m *MultiResolutionStore) Add(points ...models.TimeSeriesPoint) {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.rawData = append(m.rawData, points...)

	if len(m.rawData) > 10000 {
		m.compressRawData()
	}
}

func (m *MultiResolutionStore) compressRawData() {
	compressor := GetCompressor()

	m.highRes = append(m.highRes, compressor.Downsample(m.rawData, time.Second, Average)...)
	m.mediumRes = append(m.mediumRes, compressor.Downsample(m.rawData, time.Minute, Average)...)
	m.lowRes = append(m.lowRes, compressor.Downsample(m.rawData, time.Hour, Average)...)

	m.rawData = make([]models.TimeSeriesPoint, 0)

	if len(m.highRes) > 100000 {
		m.highRes = m.highRes[50000:]
	}
	if len(m.mediumRes) > 100000 {
		m.mediumRes = m.mediumRes[50000:]
	}
	if len(m.lowRes) > 100000 {
		m.lowRes = m.lowRes[50000:]
	}
}

func (m *MultiResolutionStore) Query(start, end time.Time, resolution time.Duration) []models.TimeSeriesPoint {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var data []models.TimeSeriesPoint

	switch {
	case resolution <= time.Second:
		data = m.rawData
	case resolution <= time.Minute:
		data = m.highRes
	case resolution <= time.Hour:
		data = m.mediumRes
	default:
		data = m.lowRes
	}

	result := make([]models.TimeSeriesPoint, 0)
	for _, p := range data {
		if (p.Timestamp.Equal(start) || p.Timestamp.After(start)) &&
			(p.Timestamp.Equal(end) || p.Timestamp.Before(end)) {
			result = append(result, p)
		}
	}

	return result
}

func (m *MultiResolutionStore) Stats() map[string]int {
	m.mu.RLock()
	defer m.mu.RUnlock()

	return map[string]int{
		"raw":     len(m.rawData),
		"high":    len(m.highRes),
		"medium":  len(m.mediumRes),
		"low":     len(m.lowRes),
	}
}

func encodeDelta(values []float64) []float64 {
	if len(values) == 0 {
		return values
	}

	encoded := make([]float64, len(values))
	encoded[0] = values[0]

	for i := 1; i < len(values); i++ {
		encoded[i] = values[i] - values[i-1]
	}

	return encoded
}

func decodeDelta(encoded []float64) []float64 {
	if len(encoded) == 0 {
		return encoded
	}

	decoded := make([]float64, len(encoded))
	decoded[0] = encoded[0]

	for i := 1; i < len(encoded); i++ {
		decoded[i] = decoded[i-1] + encoded[i]
	}

	return decoded
}

func encodeDeltaInt64(values []int64) []int64 {
	if len(values) == 0 {
		return values
	}

	encoded := make([]int64, len(values))
	encoded[0] = values[0]

	for i := 1; i < len(values); i++ {
		encoded[i] = values[i] - values[i-1]
	}

	return encoded
}

func decodeDeltaInt64(encoded []int64) []int64 {
	if len(encoded) == 0 {
		return encoded
	}

	decoded := make([]int64, len(encoded))
	decoded[0] = encoded[0]

	for i := 1; i < len(encoded); i++ {
		decoded[i] = decoded[i-1] + encoded[i]
	}

	return decoded
}

func average(points []models.TimeSeriesPoint) float64 {
	if len(points) == 0 {
		return 0
	}
	sum := 0.0
	for _, p := range points {
		sum += p.Value
	}
	return sum / float64(len(points))
}

func minimum(points []models.TimeSeriesPoint) float64 {
	if len(points) == 0 {
		return 0
	}
	min := math.Inf(1)
	for _, p := range points {
		if p.Value < min {
			min = p.Value
		}
	}
	return min
}

func maximum(points []models.TimeSeriesPoint) float64 {
	if len(points) == 0 {
		return 0
	}
	max := math.Inf(-1)
	for _, p := range points {
		if p.Value > max {
			max = p.Value
		}
	}
	return max
}

func sum(points []models.TimeSeriesPoint) float64 {
	sum := 0.0
	for _, p := range points {
		sum += p.Value
	}
	return sum
}

func median(points []models.TimeSeriesPoint) float64 {
	if len(points) == 0 {
		return 0
	}

	values := make([]float64, len(points))
	for i, p := range points {
		values[i] = p.Value
	}

	sort.Float64s(values)

	mid := len(values) / 2
	if len(values)%2 == 0 {
		return (values[mid-1] + values[mid]) / 2
	}
	return values[mid]
}

func getMetricFromPoints(points []models.TimeSeriesPoint) string {
	if len(points) > 0 && points[0].Tags != nil {
		if metric, ok := points[0].Tags["metric"]; ok {
			return metric
		}
	}
	return "default"
}

func (c *Compressor) Serialize(block *models.CompressedBlock) ([]byte, error) {
	var buf bytes.Buffer
	enc := gob.NewEncoder(&buf)
	err := enc.Encode(block)
	return buf.Bytes(), err
}

func (c *Compressor) Deserialize(data []byte) (*models.CompressedBlock, error) {
	buf := bytes.NewBuffer(data)
	dec := gob.NewDecoder(buf)
	var block models.CompressedBlock
	err := dec.Decode(&block)
	return &block, err
}
