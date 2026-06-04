package orchestrator

import (
	"context"
	"encoding/json"
	"model-inference-platform/internal/pkg/config"
	"model-inference-platform/internal/pkg/container"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

type mockDB struct {
	execCount int
	queryRow  func(query string, args ...interface{}) *mockRow
}

func newMockDB() *mockDB {
	return &mockDB{
		queryRow: func(query string, args ...interface{}) *mockRow {
			return &mockRow{}
		},
	}
}

func (m *mockDB) Exec(ctx context.Context, query string, args ...interface{}) (int64, error) {
	m.execCount++
	return 1, nil
}

func (m *mockDB) QueryRow(ctx context.Context, query string, args ...interface{}) pgx.Row {
	return m.queryRow(query, args...)
}

func (m *mockDB) Query(ctx context.Context, query string, args ...interface{}) (pgx.Rows, error) {
	return &mockRows{}, nil
}

func (m *mockDB) Close() {}

type mockRow struct{}

func (r *mockRow) Scan(dest ...interface{}) error {
	return nil
}

type mockRows struct{}

func (r *mockRows) Next() bool                  { return false }
func (r *mockRows) Scan(dest ...interface{}) error { return nil }
func (r *mockRows) Close()                      {}
func (r *mockRows) Err() error                  { return nil }
func (r *mockRows) CommandTag() pgconn.CommandTag  { return pgconn.CommandTag{} }
func (r *mockRows) Values() ([]interface{}, error) { return nil, nil }
func (r *mockRows) RawValues() [][]byte         { return nil }
func (r *mockRows) FieldDescriptions() []pgconn.FieldDescription { return nil }
func (r *mockRows) Conn() *pgx.Conn            { return nil }

type mockRedis struct {
	data     map[string]string
	hashData map[string]map[string]string
	mu       sync.RWMutex
}

func newMockRedis() *mockRedis {
	return &mockRedis{
		data:     make(map[string]string),
		hashData: make(map[string]map[string]string),
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
		delete(m.hashData, k)
	}
	return nil
}

func (m *mockRedis) LPush(ctx context.Context, key string, values ...interface{}) error {
	return nil
}

func (m *mockRedis) RPop(ctx context.Context, key string) (string, error) {
	return "", nil
}

func (m *mockRedis) LLen(ctx context.Context, key string) (int64, error) {
	return 0, nil
}

func (m *mockRedis) LRange(ctx context.Context, key string, start, stop int64) ([]string, error) {
	return nil, nil
}

func (m *mockRedis) LTrim(ctx context.Context, key string, start, stop int64) error {
	return nil
}

func (m *mockRedis) Exists(ctx context.Context, keys ...string) (int64, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	count := int64(0)
	for _, k := range keys {
		if _, ok := m.data[k]; ok {
			count++
		}
	}
	return count, nil
}

func (m *mockRedis) HSet(ctx context.Context, key string, values ...interface{}) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.hashData[key]; !ok {
		m.hashData[key] = make(map[string]string)
	}
	for i := 0; i < len(values); i += 2 {
		field := values[i].(string)
		var value string
		if s, ok := values[i+1].(string); ok {
			value = s
		} else {
			data, _ := json.Marshal(values[i+1])
			value = string(data)
		}
		m.hashData[key][field] = value
	}
	return nil
}

func (m *mockRedis) HGet(ctx context.Context, key, field string) (string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if h, ok := m.hashData[key]; ok {
		return h[field], nil
	}
	return "", nil
}

func (m *mockRedis) HGetAll(ctx context.Context, key string) (map[string]string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if h, ok := m.hashData[key]; ok {
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
	if h, ok := m.hashData[key]; ok {
		for _, f := range fields {
			delete(h, f)
		}
	}
	return nil
}

func (m *mockRedis) SAdd(ctx context.Context, key string, members ...interface{}) error {
	return nil
}

func (m *mockRedis) SMembers(ctx context.Context, key string) ([]string, error) {
	return nil, nil
}

func (m *mockRedis) ZAdd(ctx context.Context, key string, score float64, member interface{}) error {
	return nil
}

func (m *mockRedis) ZRangeByScore(ctx context.Context, key string, min, max string) ([]string, error) {
	return nil, nil
}

func (m *mockRedis) Publish(ctx context.Context, channel string, message interface{}) error {
	return nil
}

func (m *mockRedis) Subscribe(ctx context.Context, channels ...string) *redis.PubSub {
	return nil
}

func (m *mockRedis) Keys(ctx context.Context, pattern string) ([]string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	var keys []string
	for k := range m.hashData {
		keys = append(keys, k)
	}
	return keys, nil
}

func (m *mockRedis) Expire(ctx context.Context, key string, expiration time.Duration) error {
	return nil
}

func (m *mockRedis) Incr(ctx context.Context, key string) (int64, error) {
	return 0, nil
}

func (m *mockRedis) Decr(ctx context.Context, key string) (int64, error) {
	return 0, nil
}

func (m *mockRedis) Close() error {
	return nil
}

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
	m.mu.Lock()
	defer m.mu.Unlock()

	containerID := "mock-" + instanceID
	grpcPort := 8001 + len(m.containers)
	httpPort := 8000 + len(m.containers)

	info := &container.ContainerInfo{
		ID:          containerID,
		Name:        "triton-" + modelName + "-" + instanceID[:8],
		Image:       "triton:latest",
		Status:      container.ContainerStatusCreated,
		Address:     "localhost:" + string(rune(grpcPort)),
		GRPCPort:    grpcPort,
		HTTPPort:    httpPort,
		GPUDeviceID: gpuDeviceID,
		Labels:      labels,
		StartedAt:   time.Now(),
	}
	m.containers[containerID] = info
	return info, nil
}

func (m *mockContainerManager) StartContainer(ctx context.Context, containerID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if c, ok := m.containers[containerID]; ok {
		c.Status = container.ContainerStatusRunning
	}
	return nil
}

func (m *mockContainerManager) StopContainer(ctx context.Context, containerID string, timeout time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if c, ok := m.containers[containerID]; ok {
		c.Status = container.ContainerStatusStopped
	}
	return nil
}

func (m *mockContainerManager) RemoveContainer(ctx context.Context, containerID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.containers, containerID)
	return nil
}

func (m *mockContainerManager) GetContainerStatus(ctx context.Context, containerID string) (container.ContainerStatus, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if c, ok := m.containers[containerID]; ok {
		return c.Status, nil
	}
	return container.ContainerStatusUnknown, nil
}

func (m *mockContainerManager) ListContainers(ctx context.Context, labels map[string]string) ([]*container.ContainerInfo, error) {
	return nil, nil
}

func (m *mockContainerManager) GetContainerLogs(ctx context.Context, containerID string, tail int) (string, error) {
	return "", nil
}

func (m *mockContainerManager) SetExitHandler(handler container.ExitHandler) {
	m.exitHandler = handler
}

func (m *mockContainerManager) Close() error {
	return nil
}

func TestOrchestrator_CreateInstance(t *testing.T) {
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

	o := New(cfg, db, rdb, cm, logger)
	err := o.Start(ctx)
	require.NoError(t, err)
	defer o.Stop()

	instance, err := o.CreateInstance(ctx, "test-model", "model-id-123", "v1", "test-ns", 1024)

	require.NoError(t, err)
	assert.NotEmpty(t, instance.ID)
	assert.Equal(t, "test-model", instance.ModelName)
	assert.Equal(t, "v1", instance.Version)
	assert.Equal(t, "test-ns", instance.Namespace)
	assert.Equal(t, InstanceStarting, instance.Status)
	assert.NotEmpty(t, instance.Address)
	assert.NotEmpty(t, instance.ContainerID)
	assert.NotEmpty(t, instance.ContainerName)
	assert.Equal(t, "docker", instance.RuntimeMode)

	time.Sleep(500 * time.Millisecond)

	o.instancesMu.RLock()
	savedInstance := o.instances[instance.ID]
	o.instancesMu.RUnlock()

	assert.NotNil(t, savedInstance)
	assert.Equal(t, InstanceReady, savedInstance.Status)
}

func TestOrchestrator_TerminateInstance(t *testing.T) {
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
		HealthCheckInterval:  50 * time.Millisecond,
		HealthCheckInference: false,
		ProcessGRPCPortStart: 9001,
		ProcessHTTPPortStart: 9000,
	}

	o := New(cfg, db, rdb, cm, logger)
	err := o.Start(ctx)
	require.NoError(t, err)
	defer o.Stop()

	instance, _ := o.CreateInstance(ctx, "delete-model", "id", "v1", "test-ns", 512)
	time.Sleep(100 * time.Millisecond)

	err = o.TerminateInstance(ctx, instance.ID)
	assert.NoError(t, err)

	time.Sleep(100 * time.Millisecond)

	o.instancesMu.RLock()
	_, exists := o.instances[instance.ID]
	o.instancesMu.RUnlock()

	assert.False(t, exists, "Instance should be removed from map")
}

func TestOrchestrator_GetReadyInstances(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	rdb := newMockRedis()
	cm := newMockContainerManager()

	cfg := config.OrchestratorConfig{
		MinReplicas:          1,
		MaxReplicas:          5,
		HealthCheckInterval:  50 * time.Millisecond,
		HealthCheckInference: false,
		ProcessGRPCPortStart: 9001,
		ProcessHTTPPortStart: 9000,
	}

	o := New(cfg, db, rdb, cm, logger)
	err := o.Start(ctx)
	require.NoError(t, err)
	defer o.Stop()

	o.CreateInstance(ctx, "multi-inst-model", "id1", "v1", "test-ns", 512)
	o.CreateInstance(ctx, "multi-inst-model", "id1", "v1", "test-ns", 512)
	o.CreateInstance(ctx, "other-model", "id2", "v1", "test-ns", 512)

	time.Sleep(600 * time.Millisecond)

	instances := o.GetReadyInstances("multi-inst-model", "v1")

	assert.Len(t, instances, 2)
	for _, inst := range instances {
		assert.Equal(t, "multi-inst-model", inst.ModelName)
		assert.Equal(t, "v1", inst.Version)
		assert.Equal(t, InstanceReady, inst.Status)
	}
}

func TestOrchestrator_UpdateInstanceLoad(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	rdb := newMockRedis()
	cm := newMockContainerManager()

	cfg := config.OrchestratorConfig{
		MinReplicas:          1,
		MaxReplicas:          5,
		HealthCheckInterval:  50 * time.Millisecond,
		HealthCheckInference: false,
		ProcessGRPCPortStart: 9001,
		ProcessHTTPPortStart: 9000,
	}

	o := New(cfg, db, rdb, cm, logger)
	err := o.Start(ctx)
	require.NoError(t, err)
	defer o.Stop()

	instance, _ := o.CreateInstance(ctx, "load-model", "id", "v1", "test-ns", 512)
	time.Sleep(100 * time.Millisecond)

	o.UpdateInstanceLoad(instance.ID, 1)
	o.UpdateInstanceLoad(instance.ID, 1)
	o.UpdateInstanceLoad(instance.ID, 1)

	o.instancesMu.RLock()
	count := o.instances[instance.ID].ActiveRequests
	load := o.instances[instance.ID].CurrentLoad
	o.instancesMu.RUnlock()

	assert.Equal(t, int64(3), count)
	assert.Equal(t, 3, load)

	o.UpdateInstanceLoad(instance.ID, -1)

	o.instancesMu.RLock()
	count = o.instances[instance.ID].ActiveRequests
	o.instancesMu.RUnlock()

	assert.Equal(t, int64(2), count)
}

func TestOrchestrator_HealthCheck(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	rdb := newMockRedis()
	cm := newMockContainerManager()

	cfg := config.OrchestratorConfig{
		MinReplicas:          1,
		MaxReplicas:          5,
		HealthCheckInterval:  50 * time.Millisecond,
		HealthCheckInference: false,
		ProcessGRPCPortStart: 9001,
		ProcessHTTPPortStart: 9000,
	}

	o := New(cfg, db, rdb, cm, logger)
	err := o.Start(ctx)
	require.NoError(t, err)
	defer o.Stop()

	instance, _ := o.CreateInstance(ctx, "health-model", "id", "v1", "test-ns", 512)
	time.Sleep(500 * time.Millisecond)

	o.instancesMu.Lock()
	oldHeartbeat := instance.LastHeartbeat
	o.instances[instance.ID].LastHeartbeat = time.Now().Add(-5 * time.Minute)
	o.instancesMu.Unlock()

	time.Sleep(200 * time.Millisecond)

	o.instancesMu.RLock()
	updatedInstance := o.instances[instance.ID]
	o.instancesMu.RUnlock()

	assert.True(t, updatedInstance.LastHeartbeat.After(oldHeartbeat), "Heartbeat should be updated during health check")
}

func TestOrchestrator_GracefulShutdown(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	rdb := newMockRedis()
	cm := newMockContainerManager()

	cfg := config.OrchestratorConfig{
		MinReplicas:          1,
		MaxReplicas:          5,
		HealthCheckInterval:  50 * time.Millisecond,
		HealthCheckInference: false,
		ProcessGRPCPortStart: 9001,
		ProcessHTTPPortStart: 9000,
	}

	o := New(cfg, db, rdb, cm, logger)
	err := o.Start(ctx)
	require.NoError(t, err)

	instance, _ := o.CreateInstance(ctx, "shutdown-model", "id", "v1", "test-ns", 512)
	time.Sleep(100 * time.Millisecond)

	o.instancesMu.Lock()
	o.instances[instance.ID].ActiveRequests = 5
	o.instancesMu.Unlock()

	terminateDone := make(chan bool)
	go func() {
		o.TerminateInstance(ctx, instance.ID)
		terminateDone <- true
	}()

	time.Sleep(50 * time.Millisecond)

	o.instancesMu.RLock()
	status := o.instances[instance.ID].Status
	o.instancesMu.RUnlock()

	assert.Equal(t, InstanceDraining, status, "Instance should be in draining state while waiting for requests")

	o.instancesMu.Lock()
	o.instances[instance.ID].ActiveRequests = 0
	o.instancesMu.Unlock()

	select {
	case <-terminateDone:
	case <-time.After(5 * time.Second):
		t.Fatal("TerminateInstance should complete after requests finish")
	}

	o.Stop()
}

func TestInferenceInstance_Serialization(t *testing.T) {
	instance := &InferenceInstance{
		ID:             "test-id-123",
		ModelName:      "test-model",
		ModelID:        "model-id",
		Version:        "v1",
		Namespace:      "test-ns",
		Address:        "localhost:8001",
		GRPCPort:       8001,
		HTTPPort:       8000,
		GPUDeviceID:    0,
		Status:         InstanceReady,
		CurrentLoad:    5,
		GPUMemoryMB:    2048,
		StartedAt:      time.Now(),
		ActiveRequests: 3,
		ContainerID:    "container-123",
		ContainerName:  "triton-test-model-12345678",
		RuntimeMode:    "docker",
	}

	data, err := json.Marshal(instance)
	require.NoError(t, err)

	var deserialized InferenceInstance
	err = json.Unmarshal(data, &deserialized)
	require.NoError(t, err)

	assert.Equal(t, instance.ID, deserialized.ID)
	assert.Equal(t, instance.ModelName, deserialized.ModelName)
	assert.Equal(t, instance.Version, deserialized.Version)
	assert.Equal(t, instance.Status, deserialized.Status)
	assert.Equal(t, instance.GPUMemoryMB, deserialized.GPUMemoryMB)
	assert.Equal(t, instance.ContainerID, deserialized.ContainerID)
	assert.Equal(t, instance.ContainerName, deserialized.ContainerName)
	assert.Equal(t, instance.RuntimeMode, deserialized.RuntimeMode)
}

func TestInstanceStatus_String(t *testing.T) {
	statuses := []InstanceStatus{
		InstanceStarting,
		InstanceReady,
		InstanceDraining,
		InstanceStopping,
		InstanceStopped,
	}

	for _, status := range statuses {
		assert.NotEmpty(t, string(status))
	}
}
