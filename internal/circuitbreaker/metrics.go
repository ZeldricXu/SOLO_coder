package circuitbreaker

import (
	"sync"
	"time"
)

type requestResult int

const (
	resultSuccess requestResult = iota
	resultFailure
)

type metricsBucket struct {
	success int64
	failure int64
}

type Metrics struct {
	mu          sync.RWMutex
	buckets     []metricsBucket
	bucketSize  time.Duration
	windowSize  time.Duration
	head        int
	lastUpdate  time.Time
}

func NewMetrics(windowSize time.Duration, bucketCount int) *Metrics {
	if bucketCount <= 0 {
		bucketCount = 10
	}
	if windowSize <= 0 {
		windowSize = time.Minute
	}
	bucketSize := windowSize / time.Duration(bucketCount)
	return &Metrics{
		buckets:    make([]metricsBucket, bucketCount),
		bucketSize: bucketSize,
		windowSize: windowSize,
		lastUpdate: time.Now(),
	}
}

func (m *Metrics) RecordSuccess() {
	m.record(resultSuccess)
}

func (m *Metrics) RecordFailure() {
	m.record(resultFailure)
}

func (m *Metrics) record(result requestResult) {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.advance()
	switch result {
	case resultSuccess:
		m.buckets[m.head].success++
	case resultFailure:
		m.buckets[m.head].failure++
	}
}

func (m *Metrics) advance() {
	now := time.Now()
	elapsed := now.Sub(m.lastUpdate)
	if elapsed <= 0 {
		return
	}

	bucketCount := int(elapsed / m.bucketSize)
	if bucketCount >= len(m.buckets) {
		m.buckets = make([]metricsBucket, len(m.buckets))
		m.head = 0
		m.lastUpdate = now
		return
	}

	for i := 0; i < bucketCount; i++ {
		m.head = (m.head + 1) % len(m.buckets)
		m.buckets[m.head] = metricsBucket{}
	}
	m.lastUpdate = m.lastUpdate.Add(time.Duration(bucketCount) * m.bucketSize)
}

func (m *Metrics) TotalRequests() int64 {
	m.mu.RLock()
	defer m.mu.RUnlock()

	m.advance()
	var total int64
	for _, b := range m.buckets {
		total += b.success + b.failure
	}
	return total
}

func (m *Metrics) SuccessCount() int64 {
	m.mu.RLock()
	defer m.mu.RUnlock()

	m.advance()
	var total int64
	for _, b := range m.buckets {
		total += b.success
	}
	return total
}

func (m *Metrics) FailureCount() int64 {
	m.mu.RLock()
	defer m.mu.RUnlock()

	m.advance()
	var total int64
	for _, b := range m.buckets {
		total += b.failure
	}
	return total
}

func (m *Metrics) ErrorRate() float64 {
	total := m.TotalRequests()
	if total == 0 {
		return 0
	}
	failure := m.FailureCount()
	return float64(failure) / float64(total)
}

func (m *Metrics) Reset() {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.buckets = make([]metricsBucket, len(m.buckets))
	m.head = 0
	m.lastUpdate = time.Now()
}
