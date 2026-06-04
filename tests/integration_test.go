//go:build integration

package tests

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"model-inference-platform/internal/batcher"
	"model-inference-platform/internal/model"
	"model-inference-platform/internal/orchestrator"
	"model-inference-platform/internal/pkg/config"
	"model-inference-platform/internal/pkg/database"
	redisclient "model-inference-platform/internal/pkg/redis"
	"model-inference-platform/internal/pkg/triton"
	"model-inference-platform/internal/router"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/modules/redis"
	"github.com/testcontainers/testcontainers-go/wait"
	"go.uber.org/zap"
)

type TestInfrastructure struct {
	PostgresContainer *postgres.PostgresContainer
	RedisContainer    *redis.RedisContainer
	PostgresDSN       string
	RedisURL          string
	DB                *database.Database
	RedisClient       *redisclient.Client
	Logger            *zap.Logger
}

func setupTestInfrastructure(ctx context.Context, t *testing.T) *TestInfrastructure {
	logger, _ := zap.NewDevelopment()

	pgContainer, err := postgres.Run(ctx,
		"postgres:15-alpine",
		postgres.WithDatabase("inference_test"),
		postgres.WithUsername("postgres"),
		postgres.WithPassword("postgres"),
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").
				WithOccurrence(2).
				WithStartupTimeout(60*time.Second),
		),
	)
	require.NoError(t, err)

	pgDSN, err := pgContainer.ConnectionString(ctx, "sslmode=disable")
	require.NoError(t, err)

	redisContainer, err := redis.Run(ctx,
		"redis:7-alpine",
		testcontainers.WithWaitStrategy(
			wait.ForLog("Ready to accept connections").
				WithStartupTimeout(30*time.Second),
		),
	)
	require.NoError(t, err)

	redisEndpoint, err := redisContainer.Endpoint(ctx, "")
	require.NoError(t, err)

	db, err := database.NewDatabase(pgDSN)
	require.NoError(t, err)

	initDBSchema(ctx, t, db)

	redisOpts, err := redis.ParseURL("redis://" + redisEndpoint)
	require.NoError(t, err)
	rc := redisclient.NewClient(redisOpts)

	return &TestInfrastructure{
		PostgresContainer: pgContainer,
		RedisContainer:    redisContainer,
		PostgresDSN:       pgDSN,
		RedisURL:          "redis://" + redisEndpoint,
		DB:                db,
		RedisClient:       rc,
		Logger:            logger,
	}
}

func initDBSchema(ctx context.Context, t *testing.T, db *database.Database) {
	sqlFile, err := os.ReadFile("../scripts/init_db.sql")
	require.NoError(t, err)

	statements := strings.Split(string(sqlFile), ";")
	for _, stmt := range statements {
		stmt = strings.TrimSpace(stmt)
		if stmt == "" {
			continue
		}
		_, err := db.Exec(ctx, stmt)
		require.NoError(t, err)
	}
}

func (ti *TestInfrastructure) Teardown(ctx context.Context) {
	ti.DB.Close()
	ti.RedisClient.Close()
	ti.PostgresContainer.Terminate(ctx)
	ti.RedisContainer.Terminate(ctx)
}

func TestIntegration_ModelRegistrationAndDeployment(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test in short mode")
	}

	ctx := context.Background()
	infra := setupTestInfrastructure(ctx, t)
	defer infra.Teardown(ctx)

	tmpDir := t.TempDir()
	modelStorePath := filepath.Join(tmpDir, "model-store")
	os.MkdirAll(modelStorePath, 0755)

	mockTriton := newMockTritonClient()

	repo := model.NewRepository(infra.DB, mockTriton, modelStorePath)

	testModel, err := repo.CreateModel(ctx, "test-ns", "integration-model",
		"Integration test model", map[string]string{"team": "ml-test"})
	require.NoError(t, err)
	require.NotEmpty(t, testModel.ID)

	mockModelContent := bytes.NewBufferString("fake-onnx-model-content")
	modelVersion, err := repo.CreateModelVersion(ctx, testModel.ID, "v1",
		model.FormatONNX, mockModelContent, "test-user",
		map[string]interface{}{"accuracy": 0.95})
	require.NoError(t, err)
	require.NotEmpty(t, modelVersion.ID)
	assert.Equal(t, model.StatusReady, modelVersion.Status)
	assert.NotEmpty(t, modelVersion.Signature)

	gotModel, err := repo.GetModel(ctx, "test-ns", "integration-model")
	require.NoError(t, err)
	assert.Equal(t, testModel.ID, gotModel.ID)
	assert.Equal(t, "integration-model", gotModel.Name)

	versions, err := repo.ListModelVersions(ctx, testModel.ID)
	require.NoError(t, err)
	assert.Len(t, versions, 1)
	assert.Equal(t, "v1", versions[0].Version)
}

func TestIntegration_OrchestratorAndRouter(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test in short mode")
	}

	ctx := context.Background()
	infra := setupTestInfrastructure(ctx, t)
	defer infra.Teardown(ctx)

	mockTriton := newMockTritonClient()

	orchCfg := config.OrchestratorConfig{
		MinInstances:         1,
		MaxInstances:         3,
		ScalingInterval:      100 * time.Millisecond,
		CooldownPeriod:       200 * time.Millisecond,
		GPUUtilizationHigh:   80,
		GPUUtilizationLow:    30,
		QueueDepthThreshold:  5,
		HealthCheckInterval:  100 * time.Millisecond,
	}

	orch := orchestrator.New(orchCfg, infra.DB, infra.RedisClient, mockTriton, infra.Logger)
	err := orch.Start(ctx)
	require.NoError(t, err)
	defer orch.Stop()

	instance, err := orch.CreateInstance(ctx, "router-model", "model-id", "v1", "test-ns", 1024)
	require.NoError(t, err)
	require.NotEmpty(t, instance.ID)

	time.Sleep(200 * time.Millisecond)

	instances := orch.GetInstancesForModel("router-model", "v1")
	assert.Len(t, instances, 1)
	assert.Equal(t, instance.ID, instances[0].ID)

	routerCfg := config.Config{}
	r := router.New(routerCfg, orch, infra.RedisClient, mockTriton, infra.Logger)
	r.SetStrategy(router.StrategyRoundRobin)
	err = r.Start(ctx)
	require.NoError(t, err)
	defer r.Stop()

	for i := 0; i < 5; i++ {
		req := &router.RouteRequest{
			RequestID:  fmt.Sprintf("int-req-%d", i),
			ModelName:  "router-model",
			Version:    "v1",
			Namespace:  "test-ns",
			Inputs:     map[string]interface{}{"input": []float32{1.0}},
			Timeout:    5 * time.Second,
			MaxRetries: 1,
		}

		resp, err := r.Route(ctx, req)
		require.NoError(t, err)
		assert.NotEmpty(t, resp.InstanceID)
		assert.Empty(t, resp.Error)
	}
}

func TestIntegration_BatchingEndToEnd(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test in short mode")
	}

	ctx := context.Background()
	infra := setupTestInfrastructure(ctx, t)
	defer infra.Teardown(ctx)

	mockTriton := newMockTritonClient()

	batcherCfg := config.BatcherConfig{
		MaxBatchSize: 10,
		BatchWindow:  50 * time.Millisecond,
	}

	b := batcher.New(batcherCfg, infra.RedisClient, mockTriton, infra.Logger)
	err := b.Start(ctx)
	require.NoError(t, err)
	defer b.Stop()

	numRequests := 8
	var wg sync.WaitGroup
	results := make([]*batcher.BatchResponse, numRequests)

	for i := 0; i < numRequests; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			req := &batcher.BatchRequest{
				RequestID:  fmt.Sprintf("batch-req-%d", idx),
				ModelName:  "batch-model",
				Version:    "v1",
				Namespace:  "test-ns",
				Inputs:     map[string]interface{}{"input": []float32{float32(idx)}},
				ResponseCh: make(chan *batcher.BatchResponse, 1),
				Timestamp:  time.Now(),
			}
			resp, _ := b.Submit(ctx, req)
			results[idx] = resp
		}(i)
	}

	wg.Wait()

	for _, resp := range results {
		require.NotNil(t, resp)
		assert.Empty(t, resp.Error)
		assert.Greater(t, resp.BatchSize, 1)
	}

	infra.Logger.Info("Batch test completed",
		zap.Int("total_requests", numRequests),
		zap.Int64("inference_calls", mockTriton.getInferenceCount()))
}

func TestIntegration_FailureRecovery(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test in short mode")
	}

	ctx := context.Background()
	infra := setupTestInfrastructure(ctx, t)
	defer infra.Teardown(ctx)

	mockTriton := newMockTritonClient()

	orchCfg := config.OrchestratorConfig{
		MinInstances:        1,
		MaxInstances:        3,
		ScalingInterval:     50 * time.Millisecond,
		CooldownPeriod:      100 * time.Millisecond,
		QueueDepthThreshold: 5,
	}

	orch := orchestrator.New(orchCfg, infra.DB, infra.RedisClient, mockTriton, infra.Logger)
	err := orch.Start(ctx)
	require.NoError(t, err)
	defer orch.Stop()

	instance, _ := orch.CreateInstance(ctx, "fail-model", "model-id", "v1", "test-ns", 512)
	time.Sleep(100 * time.Millisecond)

	err = orch.DeleteInstance(ctx, instance.ID)
	require.NoError(t, err)

	time.Sleep(150 * time.Millisecond)

	newInstance, err := orch.CreateInstance(ctx, "fail-model", "model-id", "v1", "test-ns", 512)
	require.NoError(t, err)
	require.NotEmpty(t, newInstance.ID)

	assert.NotEqual(t, instance.ID, newInstance.ID, "New instance should have different ID")
}

func TestIntegration_ConcurrentModelOperations(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test in short mode")
	}

	ctx := context.Background()
	infra := setupTestInfrastructure(ctx, t)
	defer infra.Teardown(ctx)

	tmpDir := t.TempDir()
	modelStorePath := filepath.Join(tmpDir, "model-store")
	os.MkdirAll(modelStorePath, 0755)

	mockTriton := newMockTritonClient()
	repo := model.NewRepository(infra.DB, mockTriton, modelStorePath)

	numModels := 5
	var wg sync.WaitGroup
	errors := make(chan error, numModels)

	for i := 0; i < numModels; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()

			modelName := fmt.Sprintf("conc-model-%d", idx)
			m, err := repo.CreateModel(ctx, "conc-ns", modelName,
				"Concurrent model", map[string]string{"idx": string(rune(idx))})
			if err != nil {
				errors <- err
				return
			}

			content := bytes.NewBufferString(fmt.Sprintf("model-content-%d", idx))
			_, err = repo.CreateModelVersion(ctx, m.ID, "v1",
				model.FormatONNX, content, "test-user", nil)
			if err != nil {
				errors <- err
				return
			}
		}(i)
	}

	wg.Wait()
	close(errors)

	for err := range errors {
		assert.NoError(t, err)
	}

	var count int
	row := infra.DB.QueryRow(ctx, "SELECT COUNT(*) FROM models WHERE namespace = 'conc-ns'")
	err := row.Scan(&count)
	require.NoError(t, err)
	assert.Equal(t, numModels, count)
}

type mockTritonClient struct {
	loadedModels   map[string]bool
	inferenceCount int64
	mu             sync.RWMutex
}

func newMockTritonClient() *mockTritonClient {
	return &mockTritonClient{
		loadedModels: make(map[string]bool),
	}
}

func (m *mockTritonClient) getInferenceCount() int64 {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.inferenceCount
}

func (m *mockTritonClient) HealthCheck(ctx context.Context) (bool, error) {
	return true, nil
}

func (m *mockTritonClient) IsModelReady(ctx context.Context, modelName, version string) (bool, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	key := modelName + ":" + version
	return m.loadedModels[key], nil
}

func (m *mockTritonClient) LoadModel(ctx context.Context, modelName, version string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	key := modelName + ":" + version
	m.loadedModels[key] = true
	return nil
}

func (m *mockTritonClient) UnloadModel(ctx context.Context, modelName, version string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	key := modelName + ":" + version
	delete(m.loadedModels, key)
	return nil
}

type mockInferenceOutput struct {
	Name string      `json:"name"`
	Data interface{} `json:"data"`
}

type mockInferenceResult struct {
	Outputs []mockInferenceOutput
}

func (m *mockTritonClient) Infer(ctx context.Context, modelName, version string, inputs interface{}, outputNames []string) (interface{}, error) {
	m.mu.Lock()
	m.inferenceCount++
	m.mu.Unlock()

	return &mockInferenceResult{
		Outputs: []mockInferenceOutput{
			{Name: "output", Data: []float32{0.1, 0.8, 0.1}},
		},
	}, nil
}

func (m *mockTritonClient) GetModelMetadata(ctx context.Context, modelName, version string) (interface{}, error) {
	return map[string]interface{}{
		"inputs": []map[string]interface{}{
			{"name": "input", "shape": []int64{-1, 3, 224, 224}, "datatype": "FP32"},
		},
		"outputs": []map[string]interface{}{
			{"name": "output", "shape": []int64{-1, 1000}, "datatype": "FP32"},
		},
	}, nil
}

func (m *mockTritonClient) Close() error {
	return nil
}

type mockRow struct {
	values []interface{}
}

func (r *mockRow) Scan(dest ...interface{}) error {
	for i := range dest {
		if i < len(r.values) {
			switch d := dest[i].(type) {
			case *int:
				if v, ok := r.values[i].(int); ok {
					*d = v
				}
			case *string:
				if v, ok := r.values[i].(string); ok {
					*d = v
				}
			}
		}
	}
	return nil
}

type mockRows struct{}

func (r mockRows) Next() bool                  { return false }
func (r mockRows) Scan(dest ...interface{}) error { return nil }
func (r mockRows) Close()                      {}
func (r mockRows) Err() error                  { return nil }

func TestIntegration_ModelSignatureParsing(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test in short mode")
	}

	ctx := context.Background()
	infra := setupTestInfrastructure(ctx, t)
	defer infra.Teardown(ctx)

	tmpDir := t.TempDir()
	modelStorePath := filepath.Join(tmpDir, "model-store")
	os.MkdirAll(modelStorePath, 0755)

	mockTriton := newMockTritonClient()
	repo := model.NewRepository(infra.DB, mockTriton, modelStorePath)

	testCases := []struct {
		name   string
		format model.ModelFormat
	}{
		{"TensorFlow model", model.FormatTensorFlow},
		{"PyTorch model", model.FormatPyTorch},
		{"ONNX model", model.FormatONNX},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			testModel, err := repo.CreateModel(ctx, "sig-ns",
				fmt.Sprintf("sig-%s-model", string(tc.format)),
				"Signature test model", nil)
			require.NoError(t, err)

			content := bytes.NewBufferString("fake-model-content")
			version, err := repo.CreateModelVersion(ctx, testModel.ID, "v1",
				tc.format, content, "test-user", nil)
			require.NoError(t, err)

			assert.NotEmpty(t, version.Signature)

			hasInput := false
			hasOutput := false
			for _, spec := range version.Signature {
				if spec.IsInput {
					hasInput = true
					assert.NotEmpty(t, spec.Name)
					assert.NotEmpty(t, spec.DType)
					assert.NotEmpty(t, spec.Shape)
				} else {
					hasOutput = true
				}
			}
			assert.True(t, hasInput, "Should have input tensors")
			assert.True(t, hasOutput, "Should have output tensors")
		})
	}
}

func TestIntegration_ABTestRouting(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test in short mode")
	}

	ctx := context.Background()
	infra := setupTestInfrastructure(ctx, t)
	defer infra.Teardown(ctx)

	mockTriton := newMockTritonClient()

	orchCfg := config.OrchestratorConfig{
		MinInstances:    1,
		MaxInstances:    5,
		ScalingInterval: 100 * time.Millisecond,
		CooldownPeriod:  200 * time.Millisecond,
	}

	orch := orchestrator.New(orchCfg, infra.DB, infra.RedisClient, mockTriton, infra.Logger)
	err := orch.Start(ctx)
	require.NoError(t, err)
	defer orch.Stop()

	orch.CreateInstance(ctx, "ab-model", "id-v1", "v1", "test-ns", 512)
	orch.CreateInstance(ctx, "ab-model", "id-v2", "v2", "test-ns", 512)

	time.Sleep(150 * time.Millisecond)

	routerCfg := config.Config{}
	r := router.New(routerCfg, orch, infra.RedisClient, mockTriton, infra.Logger)
	r.SetStrategy(router.StrategyLeastRequests)
	err = r.Start(ctx)
	require.NoError(t, err)
	defer r.Stop()

	abTestKey := "abtest:test-ns:ab-model"
	abConfig := map[string]interface{}{
		"enabled":     true,
		"version_a":   "v1",
		"version_b":   "v2",
		"traffic_pct": 30,
	}
	abConfigJSON, _ := json.Marshal(abConfig)
	infra.RedisClient.Set(ctx, abTestKey, abConfigJSON, 10*time.Minute)

	time.Sleep(100 * time.Millisecond)

	v1Count := 0
	v2Count := 0

	for i := 0; i < 100; i++ {
		req := &router.RouteRequest{
			RequestID:  fmt.Sprintf("ab-req-%d", i),
			ModelName:  "ab-model",
			Version:    "v1",
			Namespace:  "test-ns",
			Inputs:     map[string]interface{}{},
			Timeout:    5 * time.Second,
			MaxRetries: 0,
		}

		resp, _ := r.Route(ctx, req)
		if strings.Contains(resp.InstanceID, "v1") || strings.Contains(resp.InstanceID, "id-v1") {
			v1Count++
		} else {
			v2Count++
		}
	}

	t.Logf("A/B test routing: v1=%d, v2=%d", v1Count, v2Count)
}
