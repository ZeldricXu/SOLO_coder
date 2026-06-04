package batcher

import (
	"context"
	"encoding/json"
	"model-inference-platform/internal/pkg/config"
	"model-inference-platform/internal/pkg/triton"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	goredis "github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

type mockRedis struct {
	data map[string]string
	hash map[string]map[string]string
	mu   sync.RWMutex
}

func newMockRedis() *mockRedis {
	return &mockRedis{
		data: make(map[string]string),
		hash: make(map[string]map[string]string),
	}
}

func (m *mockRedis) Set(ctx context.Context, key string, value interface{}, expiration time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if b, ok := value.([]byte); ok {
		m.data[key] = string(b)
	} else {
		data, _ := json.Marshal(value)
		m.data[key] = string(data)
	}
	return nil
}

func (m *mockRedis) Get(ctx context.Context, key string) (string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.data[key], nil
}

func (m *mockRedis) Del(ctx context.Context, keys ...string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, k := range keys {
		delete(m.data, k)
		delete(m.hash, k)
	}
	return nil
}

func (m *mockRedis) LPush(ctx context.Context, key string, values ...interface{}) error { return nil }
func (m *mockRedis) RPop(ctx context.Context, key string) (string, error)             { return "", nil }
func (m *mockRedis) LLen(ctx context.Context, key string) (int64, error)              { return 0, nil }
func (m *mockRedis) LRange(ctx context.Context, key string, start, stop int64) ([]string, error) {
	return nil, nil
}
func (m *mockRedis) LTrim(ctx context.Context, key string, start, stop int64) error { return nil }
func (m *mockRedis) Exists(ctx context.Context, keys ...string) (int64, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	count := int64(0)
	for _, k := range keys {
		if _, ok := m.data[k]; ok {
			count++
		}
		if _, ok := m.hash[k]; ok {
			count++
		}
	}
	return count, nil
}
func (m *mockRedis) HSet(ctx context.Context, key string, values ...interface{}) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.hash[key]; !ok {
		m.hash[key] = make(map[string]string)
	}
	for i := 0; i < len(values); i += 2 {
		field := values[i].(string)
		var value string
		if v, ok := values[i+1].(string); ok {
			value = v
		} else {
			data, _ := json.Marshal(values[i+1])
			value = string(data)
		}
		m.hash[key][field] = value
	}
	return nil
}
func (m *mockRedis) HGet(ctx context.Context, key, field string) (string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if h, ok := m.hash[key]; ok {
		return h[field], nil
	}
	return "", nil
}
func (m *mockRedis) HGetAll(ctx context.Context, key string) (map[string]string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if h, ok := m.hash[key]; ok {
		result := make(map[string]string)
		for k, v := range h {
			result[k] = v
		}
		return result, nil
	}
	return make(map[string]string), nil
}
func (m *mockRedis) HDel(ctx context.Context, key string, fields ...string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if h, ok := m.hash[key]; ok {
		for _, f := range fields {
			delete(h, f)
		}
	}
	return nil
}
func (m *mockRedis) SAdd(ctx context.Context, key string, members ...interface{}) error  { return nil }
func (m *mockRedis) SMembers(ctx context.Context, key string) ([]string, error)          { return nil, nil }
func (m *mockRedis) ZAdd(ctx context.Context, key string, score float64, member interface{}) error {
	return nil
}
func (m *mockRedis) ZRangeByScore(ctx context.Context, key string, min, max string) ([]string, error) {
	return nil, nil
}
func (m *mockRedis) Publish(ctx context.Context, channel string, message interface{}) error { return nil }
func (m *mockRedis) Subscribe(ctx context.Context, channels ...string) *goredis.PubSub       { return nil }
func (m *mockRedis) Incr(ctx context.Context, key string) (int64, error)                   { return 0, nil }
func (m *mockRedis) Decr(ctx context.Context, key string) (int64, error)                   { return 0, nil }
func (m *mockRedis) Keys(ctx context.Context, pattern string) ([]string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	var keys []string
	for k := range m.data {
		keys = append(keys, k)
	}
	for k := range m.hash {
		keys = append(keys, k)
	}
	return keys, nil
}
func (m *mockRedis) Expire(ctx context.Context, key string, expiration time.Duration) error { return nil }
func (m *mockRedis) Close() error                                                           { return nil }

type mockTritonClient struct {
	inferenceCount int64
	lastBatchSize  int
	mu             sync.Mutex
	inferenceDelay time.Duration
	batchHandler   func(batchSize int) *triton.InferenceResult
}

func newMockTritonClient() *mockTritonClient {
	return &mockTritonClient{
		batchHandler: func(batchSize int) *triton.InferenceResult {
			outputs := make([]*triton.InferenceTensor, 1)
			data := make([]float32, batchSize*1000)
			for i := 0; i < batchSize*1000; i++ {
				data[i] = float32(i) / float32(batchSize*1000)
			}
			outputs[0] = &triton.InferenceTensor{
				Name:  "output",
				Shape: []int64{int64(batchSize), 1000},
				DType: "FP32",
				Data:  data,
			}
			return &triton.InferenceResult{
				Outputs: outputs,
				Latency: 10 * time.Millisecond,
			}
		},
	}
}

func (m *mockTritonClient) HealthCheck(ctx context.Context) (bool, error) { return true, nil }
func (m *mockTritonClient) IsModelReady(ctx context.Context, modelName, version string) (bool, error) {
	return true, nil
}
func (m *mockTritonClient) LoadModel(ctx context.Context, modelName, version string) error { return nil }
func (m *mockTritonClient) UnloadModel(ctx context.Context, modelName, version string) error { return nil }

func (m *mockTritonClient) Infer(ctx context.Context, modelName, version string, inputs []*triton.InferenceTensor, outputNames []string) (*triton.InferenceResult, error) {
	atomic.AddInt64(&m.inferenceCount, 1)

	m.mu.Lock()
	var batchSize int
	if len(inputs) > 0 && inputs[0] != nil {
		if len(inputs[0].Shape) > 0 {
			batchSize = int(inputs[0].Shape[0])
		}
	}
	m.lastBatchSize = batchSize
	delay := m.inferenceDelay
	handler := m.batchHandler
	m.mu.Unlock()

	if delay > 0 {
		time.Sleep(delay)
	}

	if batchSize == 0 {
		batchSize = 1
	}

	return handler(batchSize), nil
}

func (m *mockTritonClient) GetModelMetadata(ctx context.Context, modelName, version string) (*triton.ModelMetadata, error) {
	return nil, nil
}
func (m *mockTritonClient) GetModelStats(ctx context.Context, modelName, version string) (*triton.ModelStats, error) {
	return nil, nil
}
func (m *mockTritonClient) Close() error { return nil }

func TestBatcher_BatchWindowCollection(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	rdb := newMockRedis()
	triton := newMockTritonClient()

	cfg := config.BatcherConfig{
		MaxBatchSize: 32,
		BatchWindow:  50 * time.Millisecond,
	}

	batcher := New(cfg, rdb, triton, logger)
	err := batcher.Start(ctx)
	require.NoError(t, err)
	defer batcher.Stop()

	var wg sync.WaitGroup
	responses := make([]*BatchResponse, 10)

	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			req := &BatchRequest{
				RequestID:  "req-" + strconv.Itoa(idx),
				ModelName:  "test-model",
				Version:    "v1",
				Namespace:  "default",
				Inputs:     map[string]interface{}{"input": []float32{1.0, 2.0, 3.0}},
				ResponseCh: make(chan *BatchResponse, 1),
				Timestamp:  time.Now(),
			}
			resp, _ := batcher.Submit(ctx, req)
			responses[idx] = resp
		}(i)
	}

	wg.Wait()

	count := atomic.LoadInt64(&triton.inferenceCount)
	assert.Equal(t, int64(1), count, "Should have only 1 inference call for batched requests")

	for _, resp := range responses {
		require.NotNil(t, resp)
		assert.Empty(t, resp.Error)
		assert.Equal(t, 10, resp.BatchSize, "Each response should report batch size of 10")
	}
}

func TestBatcher_MaxBatchSizeEarlySubmit(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	rdb := newMockRedis()
	triton := newMockTritonClient()

	cfg := config.BatcherConfig{
		MaxBatchSize: 5,
		BatchWindow:  500 * time.Millisecond,
	}

	batcher := New(cfg, rdb, triton, logger)
	err := batcher.Start(ctx)
	require.NoError(t, err)
	defer batcher.Stop()

	var wg sync.WaitGroup
	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			req := &BatchRequest{
				RequestID:  "early-" + strconv.Itoa(idx),
				ModelName:  "early-model",
				Version:    "v1",
				Namespace:  "default",
				Inputs:     map[string]interface{}{},
				ResponseCh: make(chan *BatchResponse, 1),
				Timestamp:  time.Now(),
			}
			batcher.Submit(ctx, req)
		}(i)
	}

	wg.Wait()

	count := atomic.LoadInt64(&triton.inferenceCount)
	assert.GreaterOrEqual(t, count, int64(1), "Should submit when batch reaches max size before window expires")
}

func TestBatcher_MultipleModels(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	rdb := newMockRedis()
	triton := newMockTritonClient()

	cfg := config.BatcherConfig{
		MaxBatchSize: 32,
		BatchWindow:  30 * time.Millisecond,
	}

	batcher := New(cfg, rdb, triton, logger)
	err := batcher.Start(ctx)
	require.NoError(t, err)
	defer batcher.Stop()

	var wg sync.WaitGroup

	for modelIdx := 0; modelIdx < 3; modelIdx++ {
		modelName := "model-" + strconv.Itoa(modelIdx)
		for i := 0; i < 5; i++ {
			wg.Add(1)
			go func(mName string, idx int) {
				defer wg.Done()
				req := &BatchRequest{
					RequestID:  mName + "-req-" + strconv.Itoa(idx),
					ModelName:  mName,
					Version:    "v1",
					Namespace:  "default",
					Inputs:     map[string]interface{}{},
					ResponseCh: make(chan *BatchResponse, 1),
					Timestamp:  time.Now(),
				}
				batcher.Submit(ctx, req)
			}(modelName, i)
		}
	}

	wg.Wait()

	count := atomic.LoadInt64(&triton.inferenceCount)
	assert.Equal(t, int64(3), count, "Should have 3 separate inferences for 3 different models")

	batcher.batchesMu.Lock()
	numBatchers := len(batcher.batches)
	batcher.batchesMu.Unlock()
	assert.Equal(t, 3, numBatchers, "Should have 3 PerModelBatchers for 3 different models")
}

func TestBatcher_ResultSplitting(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	rdb := newMockRedis()
	triton := newMockTritonClient()

	cfg := config.BatcherConfig{
		MaxBatchSize: 10,
		BatchWindow:  30 * time.Millisecond,
	}

	batcher := New(cfg, rdb, triton, logger)
	err := batcher.Start(ctx)
	require.NoError(t, err)
	defer batcher.Stop()

	numRequests := 5
	var wg sync.WaitGroup
	responses := make([]*BatchResponse, numRequests)

	for i := 0; i < numRequests; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			req := &BatchRequest{
				RequestID:  "split-req-" + strconv.Itoa(idx),
				ModelName:  "split-model",
				Version:    "v1",
				Namespace:  "default",
				Inputs:     map[string]interface{}{"idx": idx},
				ResponseCh: make(chan *BatchResponse, 1),
				Timestamp:  time.Now(),
			}
			resp, _ := batcher.Submit(ctx, req)
			responses[idx] = resp
		}(i)
	}

	wg.Wait()

	for i, resp := range responses {
		require.NotNil(t, resp)
		assert.Empty(t, resp.Error)
		assert.Contains(t, resp.Outputs, "output")
		assert.Greater(t, resp.LatencyMs, int64(0), "Response %d should have latency", i)
	}
}

func TestBatcher_RequestTimeout(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()

	logger, _ := zap.NewDevelopment()
	rdb := newMockRedis()
	triton := newMockTritonClient()
	triton.inferenceDelay = 100 * time.Millisecond

	cfg := config.BatcherConfig{
		MaxBatchSize: 32,
		BatchWindow:  50 * time.Millisecond,
	}

	batcher := New(cfg, rdb, triton, logger)
	err := batcher.Start(ctx)
	require.NoError(t, err)
	defer batcher.Stop()

	req := &BatchRequest{
		RequestID:  "timeout-req",
		ModelName:  "timeout-model",
		Version:    "v1",
		Namespace:  "default",
		Inputs:     map[string]interface{}{},
		ResponseCh: make(chan *BatchResponse, 1),
		Timestamp:  time.Now(),
	}

	resp, err := batcher.Submit(ctx, req)

	assert.Error(t, err)
	assert.Contains(t, resp.Error, "timeout")
}

func TestBatcher_ConcurrentBatches(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	rdb := newMockRedis()
	triton := newMockTritonClient()

	cfg := config.BatcherConfig{
		MaxBatchSize: 10,
		BatchWindow:  20 * time.Millisecond,
	}

	batcher := New(cfg, rdb, triton, logger)
	err := batcher.Start(ctx)
	require.NoError(t, err)
	defer batcher.Stop()

	numRequests := 120
	var wg sync.WaitGroup
	successCount := int32(0)

	for i := 0; i < numRequests; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			req := &BatchRequest{
				RequestID:  "conc-" + strconv.Itoa(idx),
				ModelName:  "conc-model",
				Version:    "v1",
				Namespace:  "default",
				Inputs:     map[string]interface{}{},
				ResponseCh: make(chan *BatchResponse, 1),
				Timestamp:  time.Now(),
			}
			resp, err := batcher.Submit(ctx, req)
			if err == nil && resp != nil && resp.Error == "" {
				atomic.AddInt32(&successCount, 1)
			}
		}(i)
	}

	wg.Wait()

	assert.Equal(t, int32(numRequests), successCount, "All requests should succeed")
	assert.GreaterOrEqual(t, atomic.LoadInt64(&triton.inferenceCount), int64(5), "Should have at least 5 batches")
}

func TestBatcher_GracefulShutdown(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	rdb := newMockRedis()
	triton := newMockTritonClient()
	triton.inferenceDelay = 20 * time.Millisecond

	cfg := config.BatcherConfig{
		MaxBatchSize: 32,
		BatchWindow:  10 * time.Millisecond,
	}

	batcher := New(cfg, rdb, triton, logger)
	err := batcher.Start(ctx)
	require.NoError(t, err)

	var wg sync.WaitGroup
	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			req := &BatchRequest{
				RequestID:  "shutdown-" + strconv.Itoa(idx),
				ModelName:  "shutdown-model",
				Version:    "v1",
				Namespace:  "default",
				Inputs:     map[string]interface{}{},
				ResponseCh: make(chan *BatchResponse, 1),
				Timestamp:  time.Now(),
			}
			batcher.Submit(ctx, req)
		}(i)
	}

	time.Sleep(5 * time.Millisecond)
	batcher.Stop()

	wg.Wait()
}

func TestBatchRequest_Serialization(t *testing.T) {
	req := &BatchRequest{
		RequestID: "test-123",
		TraceID:   "trace-abc",
		ModelName: "test-model",
		Version:   "v1",
		Namespace: "default",
		Inputs:    map[string]interface{}{"features": []float32{1.0, 2.0}},
		Timestamp: time.Now(),
	}

	data, err := json.Marshal(req)
	require.NoError(t, err)

	var deserialized BatchRequest
	err = json.Unmarshal(data, &deserialized)
	require.NoError(t, err)

	assert.Equal(t, req.RequestID, deserialized.RequestID)
	assert.Equal(t, req.ModelName, deserialized.ModelName)
	assert.Equal(t, req.Version, deserialized.Version)
}

func TestBatchResponse_Serialization(t *testing.T) {
	resp := &BatchResponse{
		RequestID: "resp-123",
		Outputs:   map[string]interface{}{"probs": []float32{0.1, 0.9}},
		LatencyMs: 15,
		Error:     "",
		BatchSize: 8,
	}

	data, err := json.Marshal(resp)
	require.NoError(t, err)

	var deserialized BatchResponse
	err = json.Unmarshal(data, &deserialized)
	require.NoError(t, err)

	assert.Equal(t, resp.RequestID, deserialized.RequestID)
	assert.Equal(t, resp.LatencyMs, deserialized.LatencyMs)
	assert.Equal(t, resp.BatchSize, deserialized.BatchSize)
	assert.Empty(t, deserialized.Error)
}

func TestBatcher_VariableBatchSizes(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	rdb := newMockRedis()
	triton := newMockTritonClient()

	cfg := config.BatcherConfig{
		MaxBatchSize: 10,
		BatchWindow:  30 * time.Millisecond,
	}

	batcher := New(cfg, rdb, triton, logger)
	err := batcher.Start(ctx)
	require.NoError(t, err)
	defer batcher.Stop()

	var wg sync.WaitGroup
	batchSizes := []int{3, 7, 10, 2}
	expectedInferences := len(batchSizes)

	for batchIdx, size := range batchSizes {
		modelName := "var-model-" + strconv.Itoa(batchIdx)
		for i := 0; i < size; i++ {
			wg.Add(1)
			go func(mName string, idx int) {
				defer wg.Done()
				req := &BatchRequest{
					RequestID:  mName + "-" + strconv.Itoa(idx),
					ModelName:  mName,
					Version:    "v1",
					Namespace:  "default",
					Inputs:     map[string]interface{}{},
					ResponseCh: make(chan *BatchResponse, 1),
					Timestamp:  time.Now(),
				}
				batcher.Submit(ctx, req)
			}(modelName, i)
		}
	}

	wg.Wait()

	count := atomic.LoadInt64(&triton.inferenceCount)
	assert.Equal(t, int64(expectedInferences), count, "Should have separate batches per model")

	batcher.batchesMu.Lock()
	numBatchers := len(batcher.batches)
	batcher.batchesMu.Unlock()
	assert.Equal(t, 4, numBatchers, "Should have 4 PerModelBatchers for 4 different models")
}

func TestBatcher_MergeInputs(t *testing.T) {
	logger, _ := zap.NewDevelopment()
	rdb := newMockRedis()
	triton := newMockTritonClient()

	cfg := config.BatcherConfig{
		MaxBatchSize: 32,
		BatchWindow:  50 * time.Millisecond,
	}

	batcher := New(cfg, rdb, triton, logger)

	requests := []*BatchRequest{
		{
			RequestID: "r1",
			Inputs:    map[string]interface{}{"input": []float32{1.0, 2.0, 3.0}},
		},
		{
			RequestID: "r2",
			Inputs:    map[string]interface{}{"input": []float32{4.0, 5.0, 6.0}},
		},
		{
			RequestID: "r3",
			Inputs:    map[string]interface{}{"input": []float32{7.0, 8.0, 9.0}},
		},
	}

	merged := batcher.mergeInputs(requests)

	assert.Contains(t, merged, "input")
	if data, ok := merged["input"].([]float32); ok {
		assert.Len(t, data, 9, "Merged inputs should have 9 elements")
	}
}

func TestBatcher_QueueStats(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	rdb := newMockRedis()
	triton := newMockTritonClient()
	triton.inferenceDelay = 50 * time.Millisecond

	cfg := config.BatcherConfig{
		MaxBatchSize: 32,
		BatchWindow:  200 * time.Millisecond,
	}

	batcher := New(cfg, rdb, triton, logger)
	err := batcher.Start(ctx)
	require.NoError(t, err)
	defer batcher.Stop()

	var wg sync.WaitGroup
	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			req := &BatchRequest{
				RequestID:  "stats-req-" + strconv.Itoa(idx),
				ModelName:  "stats-model",
				Version:    "v1",
				Namespace:  "default",
				Inputs:     map[string]interface{}{},
				ResponseCh: make(chan *BatchResponse, 1),
				Timestamp:  time.Now(),
			}
			batcher.Submit(ctx, req)
		}(i)
	}

	time.Sleep(50 * time.Millisecond)

	stats := batcher.GetQueueStats()
	assert.NotEmpty(t, stats)

	key := "default:stats-model:v1"
	modelStats, ok := stats[key].(map[string]interface{})
	assert.True(t, ok, "Should have stats for stats-model")
	assert.Contains(t, modelStats, "pending_requests")

	data, err := batcher.MarshalStats()
	require.NoError(t, err)
	assert.NotEmpty(t, data)

	wg.Wait()
}
