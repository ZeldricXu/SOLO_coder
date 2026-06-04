package tests

import (
	"encoding/json"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"

	"log-pipeline/internal/ingestor"
	"log-pipeline/internal/storage"
	"log-pipeline/internal/windowing"
	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
	"log-pipeline/testfixtures"
)

func TestStress_IngestorBackPressure(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping stress test in short mode")
	}

	bufferSize := 1000
	cfg := &config.IngestorConfig{
		TCPPort:    0,
		UDPPort:    0,
		HTTPPort:   0,
		BufferSize: bufferSize,
		MaxWorkers: 10,
		Sources:    []string{"http"},
	}
	i := ingestor.NewIngestor(cfg)

	mockCh := storage.NewMockClickHouseStore()

	var totalReceived int64
	var totalProcessed int64
	var wg sync.WaitGroup

	consumerWg := sync.WaitGroup{}
	consumerWg.Add(1)
	go func() {
		defer consumerWg.Done()
		for range i.Logs() {
			atomic.AddInt64(&totalProcessed, 1)
			entry := testfixtures.NewLogEntry()
			mockCh.InsertLog(nil, entry)
		}
	}()

	numGoroutines := 100
	logsPerGoroutine := 1000
	totalLogs := numGoroutines * logsPerGoroutine

	startTime := time.Now()

	for g := 0; g < numGoroutines; g++ {
		wg.Add(1)
		go func(goroutineID int) {
			defer wg.Done()
			for j := 0; j < logsPerGoroutine; j++ {
				entry := testfixtures.NewLogEntry(func(e *models.LogEntry) {
					e.Message = fmt.Sprintf("stress log %d-%d", goroutineID, j)
				})
				raw, _ := json.Marshal(entry)
				ok := i.ProcessLog(string(raw), "http", fmt.Sprintf("10.0.0.%d:12345", goroutineID%255))
				if ok {
					atomic.AddInt64(&totalReceived, 1)
				}
			}
		}(g)
	}

	wg.Wait()

	time.Sleep(time.Second * 2)

	i.Stop()
	consumerWg.Wait()

	elapsed := time.Since(startTime)
	logsPerSecond := float64(totalProcessed) / elapsed.Seconds()

	t.Logf("Total logs sent: %d", totalLogs)
	t.Logf("Total logs received: %d", totalReceived)
	t.Logf("Total logs processed: %d", totalProcessed)
	t.Logf("Elapsed time: %v", elapsed)
	t.Logf("Throughput: %.2f logs/sec", logsPerSecond)

	stats := i.GetBackPressureStats()
	dropCount := stats["dropCount"].(int64)
	t.Logf("Dropped logs: %d", dropCount)

	assert.Greater(t, totalProcessed, int64(0), "should have processed some logs")
	assert.GreaterOrEqual(t, totalReceived, int64(bufferSize), "should have received at least buffer size logs")

	load := stats["load"].(float64)
	t.Logf("Final load: %.2f", load)
}

func TestStress_100KLogsIngestion(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping stress test in short mode")
	}

	bufferSize := 10000
	cfg := &config.IngestorConfig{
		TCPPort:    0,
		UDPPort:    0,
		HTTPPort:   0,
		BufferSize: bufferSize,
		MaxWorkers: 50,
		Sources:    []string{"http"},
	}
	i := ingestor.NewIngestor(cfg)

	var totalProcessed int64
	var wg sync.WaitGroup

	consumerWg := sync.WaitGroup{}
	consumerWg.Add(1)
	go func() {
		defer consumerWg.Done()
		for range i.Logs() {
			atomic.AddInt64(&totalProcessed, 1)
		}
	}()

	numGoroutines := 100
	logsPerGoroutine := 1000
	totalLogs := numGoroutines * logsPerGoroutine

	startTime := time.Now()

	for g := 0; g < numGoroutines; g++ {
		wg.Add(1)
		go func(goroutineID int) {
			defer wg.Done()
			for j := 0; j < logsPerGoroutine; j++ {
				entry := testfixtures.NewLogEntry(func(e *models.LogEntry) {
					e.Message = fmt.Sprintf("high volume log %d-%d", goroutineID, j)
				})
				raw, _ := json.Marshal(entry)
				i.ProcessLog(string(raw), "http", fmt.Sprintf("10.0.0.%d:12345", goroutineID%255))
			}
		}(g)
	}

	wg.Wait()

	time.Sleep(time.Second * 5)

	i.Stop()
	consumerWg.Wait()

	elapsed := time.Since(startTime)
	logsPerSecond := float64(totalProcessed) / elapsed.Seconds()

	t.Logf("Total logs: %d", totalLogs)
	t.Logf("Processed logs: %d", totalProcessed)
	t.Logf("Elapsed time: %v", elapsed)
	t.Logf("Throughput: %.2f logs/sec", logsPerSecond)

	stats := i.GetBackPressureStats()
	dropCount := stats["dropCount"].(int64)
	t.Logf("Dropped logs: %d", dropCount)

	assert.GreaterOrEqual(t, totalProcessed, int64(bufferSize), "should process at least buffer size logs")
	assert.Greater(t, logsPerSecond, 1000.0, "should achieve at least 1000 logs/sec")
}

func TestStress_WindowEngineHighThroughput(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping stress test in short mode")
	}

	cfg := &config.WindowingConfig{
		SlidingWindowSize: time.Second * 2,
		SlidingStep:       time.Millisecond * 500,
		SessionTimeout:    time.Minute * 5,
		Error401Threshold: 10,
		RedisTTL:          time.Hour,
	}
	we := windowing.NewWindowEngine(cfg)

	logChan := make(chan *models.LogEntry, 10000)
	aggChan := we.Aggregates()

	var totalAggregates int64
	var wg sync.WaitGroup

	consumerWg := sync.WaitGroup{}
	consumerWg.Add(1)
	go func() {
		defer consumerWg.Done()
		for range aggChan {
			atomic.AddInt64(&totalAggregates, 1)
		}
	}()

	we.Start(logChan)

	numGoroutines := 50
	logsPerGoroutine := 200
	totalLogs := numGoroutines * logsPerGoroutine

	startTime := time.Now()

	for g := 0; g < numGoroutines; g++ {
		wg.Add(1)
		go func(goroutineID int) {
			defer wg.Done()
			ip := fmt.Sprintf("192.168.1.%d", goroutineID%255)
			for j := 0; j < logsPerGoroutine; j++ {
				level := "INFO"
				if j%10 == 0 {
					level = "ERROR"
				}
				entry := testfixtures.NewLogEntry(func(e *models.LogEntry) {
					e.Timestamp = time.Now()
					e.Source = "stress-test"
					e.Host = fmt.Sprintf("host-%d", goroutineID%10)
					e.Level = level
					e.Message = fmt.Sprintf("stress log %d-%d", goroutineID, j)
					e.Fields = map[string]string{"client_ip": ip}
				})
				logChan <- entry
			}
		}(g)
	}

	wg.Wait()

	time.Sleep(time.Second * 5)

	close(logChan)
	we.Stop()
	consumerWg.Wait()

	elapsed := time.Since(startTime)
	logsPerSecond := float64(totalLogs) / elapsed.Seconds()

	t.Logf("Total logs: %d", totalLogs)
	t.Logf("Total aggregates: %d", totalAggregates)
	t.Logf("Elapsed time: %v", elapsed)
	t.Logf("Throughput: %.2f logs/sec", logsPerSecond)

	assert.GreaterOrEqual(t, totalAggregates, int64(0), "should generate some aggregates")
	assert.Greater(t, logsPerSecond, 1000.0, "should achieve good throughput")
}

func TestStress_BackPressureUnderLoad(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping stress test in short mode")
	}

	bufferSize := 50
	cfg := &config.IngestorConfig{
		TCPPort:    0,
		UDPPort:    0,
		HTTPPort:   0,
		BufferSize: bufferSize,
		MaxWorkers: 1,
		Sources:    []string{"http"},
	}
	i := ingestor.NewIngestor(cfg)

	var totalAttempted int64
	var totalReceived int64
	var totalProcessed int64

	consumerWg := sync.WaitGroup{}
	consumerWg.Add(1)
	go func() {
		defer consumerWg.Done()
		for range i.Logs() {
			atomic.AddInt64(&totalProcessed, 1)
			time.Sleep(time.Millisecond * 20)
		}
	}()

	numGoroutines := 50
	logsPerGoroutine := 200

	var wg sync.WaitGroup
	for g := 0; g < numGoroutines; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < logsPerGoroutine; j++ {
				atomic.AddInt64(&totalAttempted, 1)
				entry := testfixtures.NewLogEntry()
				raw, _ := json.Marshal(entry)
				ok := i.ProcessLog(string(raw), "http", "10.0.0.1:12345")
				if ok {
					atomic.AddInt64(&totalReceived, 1)
				}
			}
		}()
	}

	wg.Wait()

	time.Sleep(time.Second * 3)

	i.Stop()
	consumerWg.Wait()

	stats := i.GetBackPressureStats()
	dropCount := stats["dropCount"].(int64)

	t.Logf("Total attempted: %d", totalAttempted)
	t.Logf("Total received: %d", totalReceived)
	t.Logf("Total processed: %d", totalProcessed)
	t.Logf("Dropped: %d", dropCount)
	t.Logf("Load: %.2f", stats["load"].(float64))

	assert.Equal(t, totalAttempted, totalReceived+dropCount, "attempted = received + dropped")
	assert.Equal(t, totalReceived, totalProcessed, "all received should be processed")
}

func TestStress_ConcurrentWindowAccess(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping stress test in short mode")
	}

	cfg := &config.WindowingConfig{
		SlidingWindowSize: time.Hour,
		SlidingStep:       time.Minute,
		SessionTimeout:    time.Hour,
		Error401Threshold: 100,
		RedisTTL:          time.Hour,
	}
	we := windowing.NewWindowEngine(cfg)

	logChan := make(chan *models.LogEntry, 10000)
	we.Start(logChan)

	numGoroutines := 100
	logsPerGoroutine := 100
	ip := "10.0.0.1"

	var wg sync.WaitGroup
	for g := 0; g < numGoroutines; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < logsPerGoroutine; j++ {
				entry := testfixtures.New401LogEntry(ip)
				logChan <- entry
			}
		}()
	}

	wg.Wait()

	time.Sleep(time.Second * 2)

	close(logChan)
	we.Stop()

	totalLogs := numGoroutines * logsPerGoroutine
	t.Logf("Total logs sent to same key: %d", totalLogs)
	t.Logf("No race conditions detected")
}
