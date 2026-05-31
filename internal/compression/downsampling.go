package compression

import (
	"math"
	"time"

	"streamsql/internal/common/models"
)

type DownsamplingStrategy interface {
	Downsample(points []models.TimeSeriesPoint, targetSize int) []models.TimeSeriesPoint
	Name() string
}

type LTTBStrategy struct{}

func NewLTTBStrategy() *LTTBStrategy {
	return &LTTBStrategy{}
}

func (l *LTTBStrategy) Name() string {
	return "lttb"
}

func (l *LTTBStrategy) Downsample(points []models.TimeSeriesPoint, targetSize int) []models.TimeSeriesPoint {
	if len(points) <= targetSize || targetSize < 3 {
		return points
	}

	sampled := make([]models.TimeSeriesPoint, targetSize)
	sampled[0] = points[0]

	bucketSize := float64(len(points)-2) / float64(targetSize-2)
	lastIndex := 0

	for i := 0; i < targetSize-2; i++ {
		avgRangeStart := int(float64(i+1)*bucketSize) + 1
		avgRangeEnd := int(float64(i+2)*bucketSize) + 1
		if avgRangeEnd >= len(points) {
			avgRangeEnd = len(points) - 1
		}

		var avgX, avgY float64
		avgRangeLength := avgRangeEnd - avgRangeStart
		for j := avgRangeStart; j < avgRangeEnd; j++ {
			avgX += float64(points[j].Timestamp.UnixNano())
			for _, v := range points[j].Fields {
				if fv, ok := v.(float64); ok {
					avgY += fv
				}
			}
		}
		avgX /= float64(avgRangeLength)
		avgY /= float64(avgRangeLength)

		rangeOff := int(float64(i)*bucketSize) + 1
		rangeTo := int(float64(i+1)*bucketSize) + 1

		maxArea := -1.0
		nextIndex := rangeOff

		pax := float64(points[lastIndex].Timestamp.UnixNano())
		pay := 0.0
		for _, v := range points[lastIndex].Fields {
			if fv, ok := v.(float64); ok {
				pay = fv
			}
		}

		for j := rangeOff; j < rangeTo; j++ {
			cx := float64(points[j].Timestamp.UnixNano())
			cy := 0.0
			for _, v := range points[j].Fields {
				if fv, ok := v.(float64); ok {
					cy = fv
				}
			}
			area := math.Abs((pax-avgX)*(cy-pay) - (pax-cx)*(avgY-pay)) * 0.5
			if area > maxArea {
				maxArea = area
				nextIndex = j
			}
		}

		sampled[i+1] = points[nextIndex]
		lastIndex = nextIndex
	}

	sampled[targetSize-1] = points[len(points)-1]
	return sampled
}

type AverageStrategy struct{}

func NewAverageStrategy() *AverageStrategy {
	return &AverageStrategy{}
}

func (a *AverageStrategy) Name() string {
	return "average"
}

func (a *AverageStrategy) Downsample(points []models.TimeSeriesPoint, targetSize int) []models.TimeSeriesPoint {
	if len(points) <= targetSize {
		return points
	}

	bucketSize := len(points) / targetSize
	sampled := make([]models.TimeSeriesPoint, targetSize)

	for i := 0; i < targetSize; i++ {
		start := i * bucketSize
		end := start + bucketSize
		if end > len(points) {
			end = len(points)
		}

		bucket := points[start:end]
		if len(bucket) == 0 {
			continue
		}

		avgFields := make(map[string]interface{})
		fieldSums := make(map[string]float64)
		fieldCounts := make(map[string]int)

		for _, p := range bucket {
			for k, v := range p.Fields {
				if fv, ok := v.(float64); ok {
					fieldSums[k] += fv
					fieldCounts[k]++
				}
			}
		}

		for k, sum := range fieldSums {
			if count := fieldCounts[k]; count > 0 {
				avgFields[k] = sum / float64(count)
			}
		}

		midIdx := len(bucket) / 2
		sampled[i] = models.TimeSeriesPoint{
			Timestamp: bucket[midIdx].Timestamp,
			Tags:      bucket[midIdx].Tags,
			Fields:    avgFields,
		}
	}

	return sampled
}

type MinMaxStrategy struct{}

func NewMinMaxStrategy() *MinMaxStrategy {
	return &MinMaxStrategy{}
}

func (m *MinMaxStrategy) Name() string {
	return "minmax"
}

func (m *MinMaxStrategy) Downsample(points []models.TimeSeriesPoint, targetSize int) []models.TimeSeriesPoint {
	if len(points) <= targetSize {
		return points
	}

	numBuckets := targetSize / 2
	if numBuckets == 0 {
		numBuckets = 1
	}
	bucketSize := len(points) / numBuckets
	sampled := make([]models.TimeSeriesPoint, 0, numBuckets*2)

	for i := 0; i < numBuckets; i++ {
		start := i * bucketSize
		end := start + bucketSize
		if end > len(points) {
			end = len(points)
		}

		bucket := points[start:end]
		if len(bucket) == 0 {
			continue
		}

		var minIdx, maxIdx int
		var minVal, maxVal float64 = math.MaxFloat64, -math.MaxFloat64

		for j, p := range bucket {
			for _, v := range p.Fields {
				if fv, ok := v.(float64); ok {
					if fv < minVal {
						minVal = fv
						minIdx = j
					}
					if fv > maxVal {
						maxVal = fv
						maxIdx = j
					}
				}
			}
		}

		if minIdx < maxIdx {
			sampled = append(sampled, bucket[minIdx], bucket[maxIdx])
		} else if minIdx > maxIdx {
			sampled = append(sampled, bucket[maxIdx], bucket[minIdx])
		} else {
			sampled = append(sampled, bucket[minIdx])
		}
	}

	if len(sampled) > targetSize {
		sampled = sampled[:targetSize]
	}

	return sampled
}

type FirstLastStrategy struct{}

func NewFirstLastStrategy() *FirstLastStrategy {
	return &FirstLastStrategy{}
}

func (f *FirstLastStrategy) Name() string {
	return "firstlast"
}

func (f *FirstLastStrategy) Downsample(points []models.TimeSeriesPoint, targetSize int) []models.TimeSeriesPoint {
	if len(points) <= targetSize {
		return points
	}

	if targetSize < 2 {
		return []models.TimeSeriesPoint{points[0]}
	}

	numBuckets := targetSize / 2
	bucketSize := len(points) / numBuckets
	sampled := make([]models.TimeSeriesPoint, 0, numBuckets*2)

	for i := 0; i < numBuckets; i++ {
		start := i * bucketSize
		end := start + bucketSize
		if end > len(points) {
			end = len(points)
		}

		sampled = append(sampled, points[start], points[end-1])
	}

	return sampled
}

type TimeBucketStrategy struct {
	Interval time.Duration
}

func NewTimeBucketStrategy(interval time.Duration) *TimeBucketStrategy {
	return &TimeBucketStrategy{Interval: interval}
}

func (t *TimeBucketStrategy) Name() string {
	return "timebucket"
}

func (t *TimeBucketStrategy) Downsample(points []models.TimeSeriesPoint, targetSize int) []models.TimeSeriesPoint {
	if len(points) <= targetSize || t.Interval <= 0 {
		return points
	}

	buckets := make(map[int64][]models.TimeSeriesPoint)
	var minTime, maxTime int64 = math.MaxInt64, 0

	for _, p := range points {
		bucketKey := p.Timestamp.UnixNano() / int64(t.Interval)
		buckets[bucketKey] = append(buckets[bucketKey], p)
		if bucketKey < minTime {
			minTime = bucketKey
		}
		if bucketKey > maxTime {
			maxTime = bucketKey
		}
	}

	sampled := make([]models.TimeSeriesPoint, 0, len(buckets))
	for key := minTime; key <= maxTime; key++ {
		if bucket, ok := buckets[key]; ok && len(bucket) > 0 {
			midIdx := len(bucket) / 2
			sampled = append(sampled, bucket[midIdx])
		}
	}

	if len(sampled) > targetSize {
		lttb := NewLTTBStrategy()
		return lttb.Downsample(sampled, targetSize)
	}

	return sampled
}
