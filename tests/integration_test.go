package tests

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/modules/clickhouse"
	"github.com/testcontainers/testcontainers-go/modules/redis"

	"log-pipeline/internal/alert"
	"log-pipeline/internal/anomaly"
	"log-pipeline/internal/ingestor"
	"log-pipeline/internal/query"
	"log-pipeline/internal/storage"
	"log-pipeline/internal/windowing"
	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
	"log-pipeline/testfixtures"
)

const (
	clickhouseUser     = "default"
	clickhousePassword = ""
	clickhouseDB       = "logs"
	redisDB            = 0
)

func setupClickHouseContainer(ctx context.Context, t *testing.T) (*clickhouse.ClickHouseContainer, *storage.ClickHouseStore) {
	t.Helper()

	clickhouseContainer, err := clickhouse.Run(
		ctx,
		"clickhouse/clickhouse-server:23.8",
		clickhouse.WithUsername(clickhouseUser),
		clickhouse.WithPassword(clickhousePassword),
		clickhouse.WithDatabase(clickhouseDB),
	)
	require.NoError(t, err, "failed to start clickhouse container")

	host, err := clickhouseContainer.ConnectionHost(ctx)
	require.NoError(t, err)

	port, err := clickhouseContainer.MappedPort(ctx, "9000/tcp")
	require.NoError(t, err)

	chConfig := &config.ClickHouseConfig{
		Address:  fmt.Sprintf("%s:%s", host, port.Port()),
		Database: clickhouseDB,
		Username: clickhouseUser,
		Password: clickhousePassword,
	}

	chStore, err := storage.NewClickHouseStore(chConfig)
	require.NoError(t, err, "failed to connect to clickhouse")

	return clickhouseContainer, chStore
}

func setupRedisContainer(ctx context.Context, t *testing.T) (*redis.RedisContainer, *storage.RedisStore) {
	t.Helper()

	redisContainer, err := redis.Run(
		ctx,
		"redis:7.2-alpine",
		redis.WithLogLevel(redis.LogLevelVerbose),
	)
	require.NoError(t, err, "failed to start redis container")

	host, err := redisContainer.ConnectionHost(ctx)
	require.NoError(t, err)

	port, err := redisContainer.MappedPort(ctx, "6379/tcp")
	require.NoError(t, err)

	redisConfig := &config.RedisConfig{
		Address:  fmt.Sprintf("%s:%s", host, port.Port()),
		Password: "",
		DB:       redisDB,
	}

	redisStore, err := storage.NewRedisStore(redisConfig)
	require.NoError(t, err, "failed to connect to redis")

	return redisContainer, redisStore
}

func TestIntegration_FullPipeline(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test in short mode")
	}

	ctx := context.Background()

	chContainer, chStore := setupClickHouseContainer(ctx, t)
	defer func() {
		if err := chStore.Close(); err != nil {
			t.Logf("error closing clickhouse: %v", err)
		}
		if err := chContainer.Terminate(ctx); err != nil {
			t.Logf("error terminating clickhouse container: %v", err)
		}
	}()

	redisContainer, redisStore := setupRedisContainer(ctx, t)
	defer func() {
		if err := redisStore.Close(); err != nil {
			t.Logf("error closing redis: %v", err)
		}
		if err := redisContainer.Terminate(ctx); err != nil {
			t.Logf("error terminating redis container: %v", err)
		}
	}()

	ingestorConfig := &config.IngestorConfig{
		TCPPort:    0,
		UDPPort:    0,
		HTTPPort:   0,
		BufferSize: 1000,
		MaxWorkers: 10,
		Sources:    []string{"http"},
	}
	ing := ingestor.NewIngestor(ingestorConfig)

	windowConfig := &config.WindowingConfig{
		SlidingWindowSize: time.Minute,
		SlidingStep:       time.Second * 10,
		SessionTimeout:    time.Minute * 5,
		Error401Threshold: 5,
		RedisTTL:          time.Hour,
	}
	we := windowing.NewWindowEngine(windowConfig)

	anomalyConfig := &config.AnomalyConfig{
		MovingAverageWindow: 10,
		StdDevThreshold:     3.0,
	}
	ad := anomaly.NewAnomalyDetector(anomalyConfig)

	alertConfig := &config.AlertManagerConfig{
		SilentPeriod: time.Minute * 5,
	}
	am := alert.NewAlertManager(alertConfig, nil, redisStore)

	queryAPI := query.NewQueryAPI(chStore, nil, &config.QueryAPIConfig{Port: 0})

	go ing.Start()
	defer ing.Stop()

	go we.Start(ing.Logs())
	defer we.Stop()

	go ad.Start(we.Aggregates())
	defer ad.Stop()

	go am.Start(ad.Anomalies(), we.Alerts())
	defer am.Stop()

	go queryAPI.Start()
	defer queryAPI.Stop()

	time.Sleep(time.Millisecond * 100)

	ip := "10.0.0.1"
	numLogs := 10
	for i := 0; i < numLogs; i++ {
		entry := testfixtures.New401LogEntry(ip, func(e *models.LogEntry) {
			e.Timestamp = time.Now().Add(time.Duration(i) * time.Millisecond * 10)
		})
		raw, _ := json.Marshal(entry)
		ing.ProcessLog(string(raw), "http", fmt.Sprintf("%s:12345", ip))
	}

	time.Sleep(time.Second * 2)

	endTime := time.Now()
	startTime := endTime.Add(-time.Minute)
	logs, err := chStore.QueryLogs(ctx, startTime, endTime, "", 100)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(logs), numLogs)

	aggRows, err := chStore.QueryAggregate(ctx, "SELECT COUNT(*) FROM aggregates")
	require.NoError(t, err)
	assert.NotNil(t, aggRows)
	if aggRows != nil {
		aggRows.Close()
	}

	anomRows, err := chStore.QueryAggregate(ctx, "SELECT COUNT(*) FROM anomalies")
	require.NoError(t, err)
	assert.NotNil(t, anomRows)
	if anomRows != nil {
		anomRows.Close()
	}
}

func TestIntegration_LogInsertAndQuery(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test in short mode")
	}

	ctx := context.Background()

	chContainer, chStore := setupClickHouseContainer(ctx, t)
	defer func() {
		if err := chStore.Close(); err != nil {
			t.Logf("error closing clickhouse: %v", err)
		}
		if err := chContainer.Terminate(ctx); err != nil {
			t.Logf("error terminating clickhouse container: %v", err)
		}
	}()

	entries := make([]*models.LogEntry, 100)
	for i := 0; i < 100; i++ {
		entries[i] = testfixtures.NewLogEntry(func(e *models.LogEntry) {
			e.Timestamp = time.Now().Add(time.Duration(i) * time.Millisecond)
			if i%2 == 0 {
				e.Level = "ERROR"
				e.Message = fmt.Sprintf("Error message %d", i)
			} else {
				e.Level = "INFO"
				e.Message = fmt.Sprintf("Info message %d", i)
			}
		})
	}

	err := chStore.InsertLogs(ctx, entries)
	require.NoError(t, err)

	time.Sleep(time.Millisecond * 500)

	endTime := time.Now().Add(time.Second)
	startTime := time.Now().Add(-time.Minute)

	allLogs, err := chStore.QueryLogs(ctx, startTime, endTime, "", 200)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(allLogs), 100)

	errorLogs, err := chStore.QueryLogs(ctx, startTime, endTime, "Error", 100)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(errorLogs), 50)
}

func TestIntegration_RedisDeduplication(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test in short mode")
	}

	ctx := context.Background()

	redisContainer, redisStore := setupRedisContainer(ctx, t)
	defer func() {
		if err := redisStore.Close(); err != nil {
			t.Logf("error closing redis: %v", err)
		}
		if err := redisContainer.Terminate(ctx); err != nil {
			t.Logf("error terminating redis container: %v", err)
		}
	}()

	key := "test-dedup-key"

	ok, err := redisStore.Deduplicate(key, "value1", time.Minute)
	require.NoError(t, err)
	assert.True(t, ok, "first deduplication should succeed")

	ok, err = redisStore.Deduplicate(key, "value2", time.Minute)
	require.NoError(t, err)
	assert.False(t, ok, "second deduplication should fail")

	exists, err := redisStore.IsDuplicate(key)
	require.NoError(t, err)
	assert.True(t, exists, "key should exist")
}

func TestIntegration_RedisWindowState(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test in short mode")
	}

	ctx := context.Background()

	redisContainer, redisStore := setupRedisContainer(ctx, t)
	defer func() {
		if err := redisStore.Close(); err != nil {
			t.Logf("error closing redis: %v", err)
		}
		if err := redisContainer.Terminate(ctx); err != nil {
			t.Logf("error terminating redis container: %v", err)
		}
	}()

	type TestState struct {
		Count int64             `json:"count"`
		Logs  []string          `json:"logs"`
		Meta  map[string]string `json:"meta"`
	}

	state := &TestState{
		Count: 42,
		Logs:  []string{"log1", "log2", "log3"},
		Meta:  map[string]string{"key": "value"},
	}

	key := "test-window-state"
	err := redisStore.SetWindowState(key, state, time.Minute)
	require.NoError(t, err)

	var result TestState
	err = redisStore.GetWindowState(key, &result)
	require.NoError(t, err)
	assert.Equal(t, state.Count, result.Count)
	assert.Equal(t, state.Logs, result.Logs)
	assert.Equal(t, state.Meta, result.Meta)

	err = redisStore.DeleteWindowState(key)
	require.NoError(t, err)

	err = redisStore.GetWindowState(key, &result)
	assert.Error(t, err, "should get error after delete")
}

func TestIntegration_RedisCounters(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test in short mode")
	}

	ctx := context.Background()

	redisContainer, redisStore := setupRedisContainer(ctx, t)
	defer func() {
		if err := redisStore.Close(); err != nil {
			t.Logf("error closing redis: %v", err)
		}
		if err := redisContainer.Terminate(ctx); err != nil {
			t.Logf("error terminating redis container: %v", err)
		}
	}()

	key := "test-counter"

	for i := 0; i < 100; i++ {
		count, err := redisStore.IncrementCounter(key)
		require.NoError(t, err)
		assert.Equal(t, int64(i+1), count)
	}

	count, err := redisStore.GetCounter(key)
	require.NoError(t, err)
	assert.Equal(t, int64(100), count)

	err = redisStore.SetCounter(key, 0, time.Minute)
	require.NoError(t, err)

	count, err = redisStore.GetCounter(key)
	require.NoError(t, err)
	assert.Equal(t, int64(0), count)
}
