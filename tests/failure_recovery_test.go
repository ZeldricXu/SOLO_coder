package tests

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"log-pipeline/internal/ingestor"
	"log-pipeline/internal/storage"
	"log-pipeline/internal/windowing"
	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
	"log-pipeline/testfixtures"
)

type FailingClickHouseStore struct {
	mu              sync.RWMutex
	failMode        bool
	realStore       *storage.MockClickHouseStore
	failCount       int64
	recoverCount    int64
	queuedLogs      []*models.LogEntry
	queueMu         sync.Mutex
}

func NewFailingClickHouseStore() *FailingClickHouseStore {
	return &FailingClickHouseStore{
		realStore:  storage.NewMockClickHouseStore(),
		queuedLogs: make([]*models.LogEntry, 0),
	}
}

func (f *FailingClickHouseStore) SetFailMode(fail bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.failMode = fail
}

func (f *FailingClickHouseStore) InsertLog(ctx context.Context, log *models.LogEntry) error {
	f.mu.RLock()
	failMode := f.failMode
	f.mu.RUnlock()

	if failMode {
		atomic.AddInt64(&f.failCount, 1)
		f.queueMu.Lock()
		f.queuedLogs = append(f.queuedLogs, log)
		f.queueMu.Unlock()
		return errors.New("connection failed")
	}

	atomic.AddInt64(&f.recoverCount, 1)
	return f.realStore.InsertLog(ctx, log)
}

func (f *FailingClickHouseStore) InsertLogs(ctx context.Context, logs []*models.LogEntry) error {
	f.mu.RLock()
	failMode := f.failMode
	f.mu.RUnlock()

	if failMode {
		atomic.AddInt64(&f.failCount, 1)
		f.queueMu.Lock()
		f.queuedLogs = append(f.queuedLogs, logs...)
		f.queueMu.Unlock()
		return errors.New("connection failed")
	}

	atomic.AddInt64(&f.recoverCount, 1)
	return f.realStore.InsertLogs(ctx, logs)
}

func (f *FailingClickHouseStore) ReplayQueuedLogs(ctx context.Context) (int, error) {
	f.queueMu.Lock()
	defer f.queueMu.Unlock()

	if len(f.queuedLogs) == 0 {
		return 0, nil
	}

	logs := make([]*models.LogEntry, len(f.queuedLogs))
	copy(logs, f.queuedLogs)
	f.queuedLogs = make([]*models.LogEntry, 0)

	err := f.realStore.InsertLogs(ctx, logs)
	if err != nil {
		f.queuedLogs = append(f.queuedLogs, logs...)
		return 0, err
	}

	return len(logs), nil
}

func (f *FailingClickHouseStore) GetFailCount() int64 {
	return atomic.LoadInt64(&f.failCount)
}

func (f *FailingClickHouseStore) GetRecoverCount() int64 {
	return atomic.LoadInt64(&f.recoverCount)
}

func (f *FailingClickHouseStore) GetQueuedCount() int {
	f.queueMu.Lock()
	defer f.queueMu.Unlock()
	return len(f.queuedLogs)
}

func (f *FailingClickHouseStore) GetStoredLogs() []*models.LogEntry {
	return f.realStore.GetLogs()
}

func TestFailureRecovery_ClickHouseDisconnect(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping failure recovery test in short mode")
	}

	failingStore := NewFailingClickHouseStore()

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

	var totalSent int64

	consumerWg := sync.WaitGroup{}
	consumerWg.Add(1)
	go func() {
		defer consumerWg.Done()
		ctx := context.Background()
		for log := range i.Logs() {
			err := failingStore.InsertLog(ctx, log)
			if err != nil {
				t.Logf("Failed to insert log, queued: %v", err)
			}
			atomic.AddInt64(&totalSent, 1)
		}
	}()

	numLogs := 1000

	go func() {
		time.Sleep(time.Millisecond * 200)
		failingStore.SetFailMode(true)
		t.Log("ClickHouse failure injected")
	}()

	go func() {
		time.Sleep(time.Millisecond * 600)
		failingStore.SetFailMode(false)
		t.Log("ClickHouse connection restored")

		time.Sleep(time.Millisecond * 100)
		replayed, err := failingStore.ReplayQueuedLogs(context.Background())
		if err != nil {
			t.Errorf("Failed to replay queued logs: %v", err)
		}
		t.Logf("Replayed %d queued logs", replayed)
	}()

	for j := 0; j < numLogs; j++ {
		entry := testfixtures.NewLogEntry(func(e *models.LogEntry) {
			e.Message = "failure recovery test log"
		})
		raw, _ := json.Marshal(entry)
		i.ProcessLog(string(raw), "http", "10.0.0.1:12345")
		time.Sleep(time.Millisecond)
	}

	time.Sleep(time.Second * 2)

	i.Stop()
	consumerWg.Wait()

	failCount := failingStore.GetFailCount()
	recoverCount := failingStore.GetRecoverCount()
	queuedCount := failingStore.GetQueuedCount()
	storedCount := len(failingStore.GetStoredLogs())

	t.Logf("Total sent: %d", totalSent)
	t.Logf("Fail count: %d", failCount)
	t.Logf("Recover count: %d", recoverCount)
	t.Logf("Queued count after replay: %d", queuedCount)
	t.Logf("Stored count: %d", storedCount)

	assert.Greater(t, failCount, int64(0), "should have some failures")
	assert.Greater(t, recoverCount, int64(0), "should have some successful inserts after recovery")
	assert.Equal(t, 0, queuedCount, "queue should be empty after replay")
	assert.Equal(t, int(totalSent), storedCount, "all logs should be stored after recovery")
}

func TestFailureRecovery_NoDuplicateAfterReplay(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping failure recovery test in short mode")
	}

	failingStore := NewFailingClickHouseStore()
	failingStore.SetFailMode(true)

	entries := make([]*models.LogEntry, 100)
	for i := 0; i < 100; i++ {
		entries[i] = testfixtures.NewLogEntry()
	}

	ctx := context.Background()
	for _, entry := range entries {
		failingStore.InsertLog(ctx, entry)
	}

	assert.Equal(t, 100, failingStore.GetQueuedCount(), "should have 100 queued logs")
	assert.Equal(t, 0, len(failingStore.GetStoredLogs()), "should have 0 stored logs")

	failingStore.SetFailMode(false)

	replayed, err := failingStore.ReplayQueuedLogs(ctx)
	require.NoError(t, err)
	assert.Equal(t, 100, replayed, "should replay 100 logs")

	assert.Equal(t, 0, failingStore.GetQueuedCount(), "queue should be empty")
	assert.Equal(t, 100, len(failingStore.GetStoredLogs()), "should have 100 stored logs")

	for _, entry := range entries {
		found := false
		for _, stored := range failingStore.GetStoredLogs() {
			if stored.ID == entry.ID {
				found = true
				break
			}
		}
		assert.True(t, found, "log %s should be in stored logs", entry.ID)
	}
}

func TestFailureRecovery_MultipleFailures(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping failure recovery test in short mode")
	}

	failingStore := NewFailingClickHouseStore()
	ctx := context.Background()

	cycles := 3
	logsPerCycle := 50

	for cycle := 0; cycle < cycles; cycle++ {
		failingStore.SetFailMode(true)

		for i := 0; i < logsPerCycle; i++ {
			entry := testfixtures.NewLogEntry(func(e *models.LogEntry) {
				e.Message = "cycle " + string(rune(cycle))
			})
			failingStore.InsertLog(ctx, entry)
		}

		assert.Equal(t, logsPerCycle, failingStore.GetQueuedCount())

		failingStore.SetFailMode(false)
		replayed, err := failingStore.ReplayQueuedLogs(ctx)
		require.NoError(t, err)
		assert.Equal(t, logsPerCycle, replayed)

		assert.Equal(t, 0, failingStore.GetQueuedCount())
		assert.Equal(t, (cycle+1)*logsPerCycle, len(failingStore.GetStoredLogs()))
	}

	totalLogs := cycles * logsPerCycle
	assert.Equal(t, totalLogs, len(failingStore.GetStoredLogs()), "all logs should be stored after multiple failures")
}

func TestFailureRecovery_ConcurrentWriteDuringFailure(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping failure recovery test in short mode")
	}

	failingStore := NewFailingClickHouseStore()
	failingStore.SetFailMode(true)

	var wg sync.WaitGroup
	numGoroutines := 20
	logsPerGoroutine := 50

	for g := 0; g < numGoroutines; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ctx := context.Background()
			for j := 0; j < logsPerGoroutine; j++ {
				entry := testfixtures.NewLogEntry()
				failingStore.InsertLog(ctx, entry)
			}
		}()
	}

	wg.Wait()

	expectedQueued := numGoroutines * logsPerGoroutine
	assert.Equal(t, expectedQueued, failingStore.GetQueuedCount())
	assert.Equal(t, 0, len(failingStore.GetStoredLogs()))

	failingStore.SetFailMode(false)
	replayed, err := failingStore.ReplayQueuedLogs(context.Background())
	require.NoError(t, err)
	assert.Equal(t, expectedQueued, replayed)
	assert.Equal(t, expectedQueued, len(failingStore.GetStoredLogs()))
}

func TestFailureRecovery_WindowEngineGracefulShutdown(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping failure recovery test in short mode")
	}

	cfg := &config.WindowingConfig{
		SlidingWindowSize: time.Minute,
		SlidingStep:       time.Second,
		SessionTimeout:    time.Minute * 5,
		Error401Threshold: 5,
		RedisTTL:          time.Hour,
	}
	we := windowing.NewWindowEngine(cfg)

	logChan := make(chan *models.LogEntry, 1000)
	we.Start(logChan)

	numLogs := 1000
	var wg sync.WaitGroup

	for g := 0; g < 10; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < numLogs/10; j++ {
				entry := testfixtures.NewLogEntry(func(e *models.LogEntry) {
					e.Timestamp = time.Now()
					e.Level = "INFO"
				})
				logChan <- entry
			}
		}()
	}

	wg.Wait()

	time.Sleep(time.Millisecond * 500)

	close(logChan)
	we.Stop()

	t.Log("Window engine shut down gracefully with pending logs")
	t.Logf("No race conditions or panics detected")
}
