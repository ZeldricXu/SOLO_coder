package storage

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"log-pipeline/pkg/models"
	"log-pipeline/testfixtures"
)

func TestMockClickHouseStore_InsertLog(t *testing.T) {
	store := NewMockClickHouseStore()
	ctx := context.Background()

	entry := testfixtures.NewLogEntry()
	err := store.InsertLog(ctx, entry)
	require.NoError(t, err)

	logs := store.GetLogs()
	assert.Len(t, logs, 1)
	assert.Equal(t, entry.ID, logs[0].ID)
}

func TestMockClickHouseStore_InsertLogs(t *testing.T) {
	store := NewMockClickHouseStore()
	ctx := context.Background()

	entries := make([]*models.LogEntry, 10)
	for i := 0; i < 10; i++ {
		entries[i] = testfixtures.NewLogEntry()
	}

	err := store.InsertLogs(ctx, entries)
	require.NoError(t, err)

	logs := store.GetLogs()
	assert.Len(t, logs, 10)
}

func TestMockClickHouseStore_InsertAggregate(t *testing.T) {
	store := NewMockClickHouseStore()
	ctx := context.Background()

	agg := &models.WindowAggregate{
		WindowID:    "agg-1",
		WindowStart: time.Now().Add(-time.Minute),
		WindowEnd:   time.Now(),
		WindowType:  "sliding",
		Key:         "10.0.0.1",
		Count:       10,
		LevelCounts: map[string]int64{"ERROR": 5, "WARN": 5},
	}

	err := store.InsertAggregate(ctx, agg)
	require.NoError(t, err)

	aggs := store.GetAggregates()
	assert.Len(t, aggs, 1)
	assert.Equal(t, "agg-1", aggs[0].WindowID)
}

func TestMockClickHouseStore_InsertAnomaly(t *testing.T) {
	store := NewMockClickHouseStore()
	ctx := context.Background()

	anomaly := &models.AnomalyResult{
		ID:           "anomaly-1",
		Timestamp:    time.Now(),
		MetricName:   "error_rate",
		AnomalyScore: 0.95,
		IsAnomaly:    true,
		Method:       "moving_average",
		Threshold:    3.0,
		Value:        5.0,
		Features:     map[string]float64{"error_count": 100},
	}

	err := store.InsertAnomaly(ctx, anomaly)
	require.NoError(t, err)

	anomalies := store.GetAnomalies()
	assert.Len(t, anomalies, 1)
	assert.Equal(t, "anomaly-1", anomalies[0].ID)
}

func TestMockClickHouseStore_InsertAlert(t *testing.T) {
	store := NewMockClickHouseStore()
	ctx := context.Background()

	alert := &models.AlertEvent{
		ID:          "alert-1",
		Timestamp:   time.Now(),
		AlertType:   "401_error",
		Severity:    "critical",
		Title:       "High 401 Error Rate",
		Description: "More than 10 401 errors in 1 minute",
		SourceIP:    "10.0.0.1",
		Count:       15,
	}

	err := store.InsertAlert(ctx, alert)
	require.NoError(t, err)

	alerts := store.GetAlerts()
	assert.Len(t, alerts, 1)
	assert.Equal(t, "alert-1", alerts[0].ID)
}

func TestMockClickHouseStore_QueryLogs(t *testing.T) {
	store := NewMockClickHouseStore()
	ctx := context.Background()

	now := time.Now()
	entry1 := testfixtures.NewLogEntry(func(e *models.LogEntry) {
		e.Timestamp = now.Add(-time.Minute)
		e.Message = "error message"
	})
	entry2 := testfixtures.NewLogEntry(func(e *models.LogEntry) {
		e.Timestamp = now.Add(-time.Hour)
		e.Message = "normal message"
	})

	store.InsertLog(ctx, entry1)
	store.InsertLog(ctx, entry2)

	logs, err := store.QueryLogs(ctx, now.Add(-2*time.Minute), now, "", 10)
	require.NoError(t, err)
	assert.Len(t, logs, 1)
	assert.Equal(t, entry1.ID, logs[0].ID)
}

func TestMockClickHouseStore_ConcurrentInsertLog(t *testing.T) {
	store := NewMockClickHouseStore()
	ctx := context.Background()

	var wg sync.WaitGroup
	numGoroutines := 50
	numLogs := 20

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < numLogs; j++ {
				entry := testfixtures.NewLogEntry()
				store.InsertLog(ctx, entry)
			}
		}()
	}

	wg.Wait()

	logs := store.GetLogs()
	assert.Len(t, logs, numGoroutines*numLogs)
}

func TestMockClickHouseStore_ConcurrentInsertLogs(t *testing.T) {
	store := NewMockClickHouseStore()
	ctx := context.Background()

	var wg sync.WaitGroup
	numGoroutines := 20
	batchSize := 10

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			batch := make([]*models.LogEntry, batchSize)
			for j := 0; j < batchSize; j++ {
				batch[j] = testfixtures.NewLogEntry()
			}
			store.InsertLogs(ctx, batch)
		}()
	}

	wg.Wait()

	logs := store.GetLogs()
	assert.Len(t, logs, numGoroutines*batchSize)
}

func TestMockClickHouseStore_Clear(t *testing.T) {
	store := NewMockClickHouseStore()
	ctx := context.Background()

	entry := testfixtures.NewLogEntry()
	store.InsertLog(ctx, entry)

	assert.Len(t, store.GetLogs(), 1)

	store.Clear()

	assert.Len(t, store.GetLogs(), 0)
}

func TestMockClickHouseStore_ConcurrentReadWrite(t *testing.T) {
	store := NewMockClickHouseStore()
	ctx := context.Background()

	var wg sync.WaitGroup
	numWriters := 20
	numReaders := 20
	numOps := 50

	for i := 0; i < numWriters; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < numOps; j++ {
				entry := testfixtures.NewLogEntry()
				store.InsertLog(ctx, entry)
			}
		}()
	}

	for i := 0; i < numReaders; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < numOps; j++ {
				store.GetLogs()
				store.QueryLogs(ctx, time.Now().Add(-time.Hour), time.Now(), "", 100)
			}
		}()
	}

	wg.Wait()

	logs := store.GetLogs()
	assert.Len(t, logs, numWriters*numOps)
}

func TestMockClickHouseStore_InsertAggregate_Concurrent(t *testing.T) {
	store := NewMockClickHouseStore()
	ctx := context.Background()

	var wg sync.WaitGroup
	numGoroutines := 30

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			agg := &models.WindowAggregate{
				WindowID:    "agg-" + string(rune(idx)),
				WindowStart: time.Now().Add(-time.Minute),
				WindowEnd:   time.Now(),
				WindowType:  "sliding",
				Key:         "10.0.0.1",
				Count:       int64(idx),
			}
			store.InsertAggregate(ctx, agg)
		}(i)
	}

	wg.Wait()

	aggs := store.GetAggregates()
	assert.Len(t, aggs, numGoroutines)
}

func TestMockClickHouseStore_InsertAnomaly_Concurrent(t *testing.T) {
	store := NewMockClickHouseStore()
	ctx := context.Background()

	var wg sync.WaitGroup
	numGoroutines := 30

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			anomaly := &models.AnomalyResult{
				ID:           "anomaly-" + string(rune(idx)),
				Timestamp:    time.Now(),
				MetricName:   "error_rate",
				AnomalyScore: float64(idx) * 0.01,
				IsAnomaly:    idx%2 == 0,
			}
			store.InsertAnomaly(ctx, anomaly)
		}(i)
	}

	wg.Wait()

	anomalies := store.GetAnomalies()
	assert.Len(t, anomalies, numGoroutines)
}

func TestMockClickHouseStore_InsertAlert_Concurrent(t *testing.T) {
	store := NewMockClickHouseStore()
	ctx := context.Background()

	var wg sync.WaitGroup
	numGoroutines := 30

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			alert := &models.AlertEvent{
				ID:        "alert-" + string(rune(idx)),
				Timestamp: time.Now(),
				AlertType: "401_error",
				Severity:  "critical",
			}
			store.InsertAlert(ctx, alert)
		}(i)
	}

	wg.Wait()

	alerts := store.GetAlerts()
	assert.Len(t, alerts, numGoroutines)
}
