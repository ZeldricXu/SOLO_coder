package router

import (
	"context"
	"encoding/json"
	"model-inference-platform/internal/orchestrator"
	"model-inference-platform/internal/pkg/config"
	"model-inference-platform/internal/pkg/container"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	goredis "github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

type mockDB struct{}

func newMockDB() *mockDB { return &mockDB{} }

func (m *mockDB) Exec(ctx context.Context, query string, args ...interface{}) (int64, error) {
	return 1, nil
}
func (m *mockDB) QueryRow(ctx context.Context, query string, args ...interface{}) pgx.Row { return nil }
func (m *mockDB) Query(ctx context.Context, query string, args ...interface{}) (pgx.Rows, error) {
	return nil, nil
}
func (m *mockDB) Close() {}

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

func (m *mockRedis) SAdd(ctx context.Context, key string, members ...interface{}) error { return nil }
func (m *mockRedis) SMembers(ctx context.Context, key string) ([]string, error) { return nil, nil }
func (m *mockRedis) ZAdd(ctx context.Context, key string, score float64, member interface{}) error {
	return nil
}
func (m *mockRedis) ZRangeByScore(ctx context.Context, key string, min, max string) ([]string, error) {
	return nil, nil
}
func (m *mockRedis) Publish(ctx context.Context, channel string, message interface{}) error { return nil }
func (m *mockRedis) Subscribe(ctx context.Context, channels ...string) *goredis.PubSub { return nil }
func (m *mockRedis) Incr(ctx context.Context, key string) (int64, error) { return 0, nil }
func (m *mockRedis) Decr(ctx context.Context, key string) (int64, error) { return 0, nil }
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
func (m *mockRedis) Close() error { return nil }

type mockContainerManager struct {
	containers map[string]*container.ContainerInfo
	exitHandler container.ExitHandler
	mu         sync.RWMutex
}

func newMockContainerManager() *mockContainerManager {
	return &mockContainerManager{
		containers: make(map[string]*container.ContainerInfo),
	}
}

func (m *mockContainerManager) CreateContainer(ctx context.Context, modelName, version, namespace, instanceID string, gpuDeviceID int, labels map[string]string) (*container.ContainerInfo, error) {
	return &container.ContainerInfo{
		ID:          "mock-" + instanceID,
		Name:        "triton-" + modelName,
		Image:       "triton:latest",
		Status:      container.ContainerStatusRunning,
		Address:     "localhost",
		GRPCPort:    8001,
		HTTPPort:    8000,
		GPUDeviceID: gpuDeviceID,
	}, nil
}
func (m *mockContainerManager) StartContainer(ctx context.Context, containerID string) error { return nil }
func (m *mockContainerManager) StopContainer(ctx context.Context, containerID string, timeout time.Duration) error {
	return nil
}
func (m *mockContainerManager) RemoveContainer(ctx context.Context, containerID string) error { return nil }
func (m *mockContainerManager) GetContainerStatus(ctx context.Context, containerID string) (container.ContainerStatus, error) {
	return container.ContainerStatusRunning, nil
}
func (m *mockContainerManager) ListContainers(ctx context.Context, labels map[string]string) ([]*container.ContainerInfo, error) {
	return nil, nil
}
func (m *mockContainerManager) GetContainerLogs(ctx context.Context, containerID string, tail int) (string, error) {
	return "", nil
}
func (m *mockContainerManager) SetExitHandler(handler container.ExitHandler) {}
func (m *mockContainerManager) Close() error { return nil }

func setupTestOrchestrator(t *testing.T) (*orchestrator.Orchestrator, *mockRedis) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	rdb := newMockRedis()
	cm := newMockContainerManager()

	cfg := config.OrchestratorConfig{
		MinReplicas:          1,
		MaxReplicas:          5,
		ScaleUpDelay:         100 * time.Millisecond,
		ScaleDownDelay:       500 * time.Millisecond,
		QueueDepthThreshold:  10,
		HealthCheckInterval:  50 * time.Millisecond,
		HealthCheckInference: false,
		RuntimeMode:          "docker",
		ProcessGRPCPortStart: 9001,
		ProcessHTTPPortStart: 9000,
	}

	o := orchestrator.New(cfg, db, rdb, cm, logger)
	err := o.Start(ctx)
	require.NoError(t, err)

	t.Cleanup(func() {
		o.Stop()
	})

	return o, rdb
}

func TestRouter_SyncRouteTable(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	orch, rdb := setupTestOrchestrator(t)

	cfg := config.Config{}

	router := New(cfg, orch, rdb, logger)
	router.Start(ctx)
	defer router.Stop()

	_, err := orch.CreateInstance(ctx, "test-model", "model-id", "v1", "default", 1024)
	require.NoError(t, err)
	_, err = orch.CreateInstance(ctx, "test-model", "model-id", "v1", "default", 1024)
	require.NoError(t, err)
	_, err = orch.CreateInstance(ctx, "test-model", "model-id", "v1", "default", 1024)
	require.NoError(t, err)

	time.Sleep(600 * time.Millisecond)

	router.syncRouteTable(ctx)

	entries, err := router.GetRouteTable(ctx, "test-model", "v1")
	require.NoError(t, err)
	assert.Len(t, entries, 3)

	instanceIDs := make(map[string]bool)
	for _, entry := range entries {
		instanceIDs[entry.InstanceID] = true
		assert.Equal(t, "localhost", entry.Address)
		assert.Equal(t, 8001, entry.GRPCPort)
		assert.Equal(t, 8000, entry.HTTPPort)
	}

	assert.Equal(t, 3, len(instanceIDs))
}

func TestRouter_InstanceStatusFiltering(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	orch, rdb := setupTestOrchestrator(t)

	cfg := config.Config{}

	router := New(cfg, orch, rdb, logger)
	router.Start(ctx)
	defer router.Stop()

	inst1, err := orch.CreateInstance(ctx, "filter-model", "model-id", "v1", "default", 1024)
	require.NoError(t, err)

	time.Sleep(600 * time.Millisecond)

	inst2, err := orch.CreateInstance(ctx, "filter-model", "model-id", "v1", "default", 1024)
	require.NoError(t, err)

	orch.UpdateInstanceStatus(ctx, inst2.ID, orchestrator.InstanceStarting)

	inst3, err := orch.CreateInstance(ctx, "filter-model", "model-id", "v1", "default", 1024)
	require.NoError(t, err)

	orch.UpdateInstanceStatus(ctx, inst3.ID, orchestrator.InstanceUnhealthy)

	router.syncRouteTable(ctx)

	entries, err := router.GetRouteTable(ctx, "filter-model", "v1")
	require.NoError(t, err)
	assert.Len(t, entries, 1)
	assert.Equal(t, inst1.ID, entries[0].InstanceID)
}

func TestRouter_InstanceRemoval(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	orch, rdb := setupTestOrchestrator(t)

	cfg := config.Config{}

	router := New(cfg, orch, rdb, logger)
	router.Start(ctx)
	defer router.Stop()

	inst1, err := orch.CreateInstance(ctx, "removal-model", "model-id", "v1", "default", 1024)
	require.NoError(t, err)
	inst2, err := orch.CreateInstance(ctx, "removal-model", "model-id", "v1", "default", 1024)
	require.NoError(t, err)

	time.Sleep(600 * time.Millisecond)

	router.syncRouteTable(ctx)
	entries, _ := router.GetRouteTable(ctx, "removal-model", "v1")
	assert.Len(t, entries, 2)

	orch.TerminateInstance(ctx, inst1.ID)
	time.Sleep(1200 * time.Millisecond)
	router.syncRouteTable(ctx)

	entries, _ = router.GetRouteTable(ctx, "removal-model", "v1")
	assert.Len(t, entries, 1)
	assert.Equal(t, inst2.ID, entries[0].InstanceID)
}

func TestRouter_LoadUpdates(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	orch, rdb := setupTestOrchestrator(t)

	cfg := config.Config{}

	router := New(cfg, orch, rdb, logger)
	router.Start(ctx)
	defer router.Stop()

	inst, err := orch.CreateInstance(ctx, "load-model", "model-id", "v1", "default", 1024)
	require.NoError(t, err)

	time.Sleep(600 * time.Millisecond)

	router.syncRouteTable(ctx)

	entries, _ := router.GetRouteTable(ctx, "load-model", "v1")
	assert.Len(t, entries, 1)
	assert.Equal(t, 0, entries[0].CurrentLoad)

	orch.UpdateInstanceLoad(inst.ID, 5)
	router.syncRouteTable(ctx)

	entries, _ = router.GetRouteTable(ctx, "load-model", "v1")
	assert.Len(t, entries, 1)
	assert.Equal(t, 5, entries[0].CurrentLoad)
	assert.Equal(t, int64(5), entries[0].ActiveRequests)
}

func TestRouter_GetRouteTable_NoInstances(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	orch, rdb := setupTestOrchestrator(t)

	cfg := config.Config{}

	router := New(cfg, orch, rdb, logger)
	router.Start(ctx)
	defer router.Stop()

	entries, err := router.GetRouteTable(ctx, "nonexistent", "v1")
	require.NoError(t, err)
	assert.Len(t, entries, 0)
}

func TestRouter_ABTestVersionSelection(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	orch, rdb := setupTestOrchestrator(t)

	cfg := config.Config{}

	router := New(cfg, orch, rdb, logger)

	router.AddABTestConfig(&ABTestConfig{
		ID:            "ab-test-1",
		ModelName:     "ab-model",
		Namespace:     "default",
		VersionA:      "v1",
		VersionB:      "v2",
		TrafficSplitA: 50,
		TrafficSplitB: 50,
		Active:        true,
	})

	versionCounts := make(map[string]int)
	for i := 0; i < 1000; i++ {
		version, _ := router.SelectABTestVersion(ctx, "default", "ab-model", "v1")
		versionCounts[version]++
	}

	assert.Greater(t, versionCounts["v1"], 400)
	assert.Greater(t, versionCounts["v2"], 400)
	assert.Less(t, versionCounts["v1"], 600)
	assert.Less(t, versionCounts["v2"], 600)
}

func TestRouter_ABTestInactiveConfig(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	orch, rdb := setupTestOrchestrator(t)

	cfg := config.Config{}

	router := New(cfg, orch, rdb, logger)

	router.AddABTestConfig(&ABTestConfig{
		ID:            "ab-test-inactive",
		ModelName:     "inactive-model",
		Namespace:     "default",
		VersionA:      "v1",
		VersionB:      "v2",
		TrafficSplitA: 50,
		TrafficSplitB: 50,
		Active:        false,
	})

	version, err := router.SelectABTestVersion(ctx, "default", "inactive-model", "v1")
	require.NoError(t, err)
	assert.Equal(t, "v1", version)
}

func TestRouter_ABTestNoConfig(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	orch, rdb := setupTestOrchestrator(t)

	cfg := config.Config{}

	router := New(cfg, orch, rdb, logger)

	version, err := router.SelectABTestVersion(ctx, "default", "no-config-model", "v1")
	require.NoError(t, err)
	assert.Equal(t, "v1", version)
}

func TestRouter_ABTestRemoveConfig(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	orch, rdb := setupTestOrchestrator(t)

	cfg := config.Config{}

	router := New(cfg, orch, rdb, logger)

	router.AddABTestConfig(&ABTestConfig{
		ID:            "ab-test-remove",
		ModelName:     "remove-model",
		Namespace:     "default",
		VersionA:      "v1",
		VersionB:      "v2",
		TrafficSplitA: 100,
		TrafficSplitB: 0,
		Active:        true,
	})

	version, _ := router.SelectABTestVersion(ctx, "default", "remove-model", "v1")
	assert.Equal(t, "v1", version)

	router.RemoveABTestConfig("default", "remove-model")

	version, _ = router.SelectABTestVersion(ctx, "default", "remove-model", "default-v")
	assert.Equal(t, "default-v", version)
}

func TestRouteTableEntry_Serialization(t *testing.T) {
	entry := &RouteTableEntry{
		InstanceID:     "inst-abc",
		Address:        "10.0.0.1",
		GRPCPort:       8001,
		HTTPPort:       8000,
		GPUDeviceID:    1,
		CurrentLoad:    42,
		ActiveRequests: 10,
		LastHeartbeat:  time.Now().Unix(),
	}

	data, err := json.Marshal(entry)
	require.NoError(t, err)

	var deserialized RouteTableEntry
	err = json.Unmarshal(data, &deserialized)
	require.NoError(t, err)

	assert.Equal(t, entry.InstanceID, deserialized.InstanceID)
	assert.Equal(t, entry.Address, deserialized.Address)
	assert.Equal(t, entry.GRPCPort, deserialized.GRPCPort)
	assert.Equal(t, entry.HTTPPort, deserialized.HTTPPort)
	assert.Equal(t, entry.GPUDeviceID, deserialized.GPUDeviceID)
	assert.Equal(t, entry.CurrentLoad, deserialized.CurrentLoad)
	assert.Equal(t, entry.ActiveRequests, deserialized.ActiveRequests)
	assert.Equal(t, entry.LastHeartbeat, deserialized.LastHeartbeat)
}

func TestRouter_MultipleModels(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	orch, rdb := setupTestOrchestrator(t)

	cfg := config.Config{}

	router := New(cfg, orch, rdb, logger)
	router.Start(ctx)
	defer router.Stop()

	_, err := orch.CreateInstance(ctx, "model-a", "model-id", "v1", "default", 1024)
	require.NoError(t, err)
	_, err = orch.CreateInstance(ctx, "model-a", "model-id", "v1", "default", 1024)
	require.NoError(t, err)
	_, err = orch.CreateInstance(ctx, "model-b", "model-id", "v1", "default", 1024)
	require.NoError(t, err)

	time.Sleep(600 * time.Millisecond)

	router.syncRouteTable(ctx)

	entriesA, _ := router.GetRouteTable(ctx, "model-a", "v1")
	assert.Len(t, entriesA, 2)

	entriesB, _ := router.GetRouteTable(ctx, "model-b", "v1")
	assert.Len(t, entriesB, 1)
}
