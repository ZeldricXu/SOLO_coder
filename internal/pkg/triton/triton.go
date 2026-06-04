package triton

import (
	"context"
	"fmt"
	"model-inference-platform/internal/pkg/config"
	"sync"
	"time"
)

type InferenceTensor struct {
	Name     string
	Shape    []int64
	DType    string
	Data     interface{}
}

type InferenceResult struct {
	ModelName    string
	ModelVersion string
	Outputs      []*InferenceTensor
	Latency      time.Duration
}

type TritonClient interface {
	HealthCheck(ctx context.Context) (bool, error)
	IsModelReady(ctx context.Context, modelName, version string) (bool, error)
	LoadModel(ctx context.Context, modelName, version string) error
	UnloadModel(ctx context.Context, modelName, version string) error
	Infer(ctx context.Context, modelName, version string, inputs []*InferenceTensor, outputNames []string) (*InferenceResult, error)
	GetModelMetadata(ctx context.Context, modelName, version string) (*ModelMetadata, error)
	GetModelStats(ctx context.Context, modelName, version string) (*ModelStats, error)
	Close() error
}

type ModelMetadata struct {
	Name     string
	Versions []string
	Platform string
	Inputs   []TensorInfo
	Outputs  []TensorInfo
}

type TensorInfo struct {
	Name     string
	DType    string
	Shape    []int64
	Reshape  []int64
	IsOptional bool
}

type ModelStats struct {
	InferenceCount int64
	ExecutionCount int64
	QueueDuration  time.Duration
	ComputeDuration time.Duration
}

type MockTritonClient struct {
	mu       sync.RWMutex
	models   map[string]*modelState
	metadata map[string]*ModelMetadata
}

type modelState struct {
	loaded    bool
	loadedAt  time.Time
	stats     *ModelStats
}

func NewClient(cfg config.TritonConfig) (TritonClient, error) {
	return &MockTritonClient{
		models:   make(map[string]*modelState),
		metadata: make(map[string]*ModelMetadata),
	}, nil
}

func (m *MockTritonClient) HealthCheck(ctx context.Context) (bool, error) {
	return true, nil
}

func (m *MockTritonClient) IsModelReady(ctx context.Context, modelName, version string) (bool, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	key := fmt.Sprintf("%s:%s", modelName, version)
	if state, ok := m.models[key]; ok {
		return state.loaded, nil
	}
	return false, nil
}

func (m *MockTritonClient) LoadModel(ctx context.Context, modelName, version string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	key := fmt.Sprintf("%s:%s", modelName, version)
	m.models[key] = &modelState{
		loaded:   true,
		loadedAt: time.Now(),
		stats:    &ModelStats{},
	}
	return nil
}

func (m *MockTritonClient) UnloadModel(ctx context.Context, modelName, version string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	key := fmt.Sprintf("%s:%s", modelName, version)
	delete(m.models, key)
	return nil
}

func (m *MockTritonClient) Infer(ctx context.Context, modelName, version string, inputs []*InferenceTensor, outputNames []string) (*InferenceResult, error) {
	start := time.Now()

	m.mu.Lock()
	key := fmt.Sprintf("%s:%s", modelName, version)
	if state, ok := m.models[key]; ok && state.loaded {
		state.stats.InferenceCount++
	}
	m.mu.Unlock()

	outputs := make([]*InferenceTensor, len(outputNames))
	for i, name := range outputNames {
		outputs[i] = &InferenceTensor{
			Name:  name,
			Shape: []int64{1, 1000},
			DType: "FP32",
			Data:  make([]float32, 1000),
		}
	}

	latency := time.Since(start)

	return &InferenceResult{
		ModelName:    modelName,
		ModelVersion: version,
		Outputs:      outputs,
		Latency:      latency,
	}, nil
}

func (m *MockTritonClient) GetModelMetadata(ctx context.Context, modelName, version string) (*ModelMetadata, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	key := fmt.Sprintf("%s:%s", modelName, version)
	if meta, ok := m.metadata[key]; ok {
		return meta, nil
	}
	return &ModelMetadata{
		Name:     modelName,
		Versions: []string{version},
		Platform: "tensorrt_plan",
		Inputs: []TensorInfo{
			{Name: "input", DType: "FP32", Shape: []int64{-1, 3, 224, 224}},
		},
		Outputs: []TensorInfo{
			{Name: "output", DType: "FP32", Shape: []int64{-1, 1000}},
		},
	}, nil
}

func (m *MockTritonClient) GetModelStats(ctx context.Context, modelName, version string) (*ModelStats, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	key := fmt.Sprintf("%s:%s", modelName, version)
	if state, ok := m.models[key]; ok {
		return state.stats, nil
	}
	return &ModelStats{}, nil
}

func (m *MockTritonClient) Close() error {
	return nil
}

func (m *MockTritonClient) SetModelMetadata(modelName, version string, meta *ModelMetadata) {
	m.mu.Lock()
	defer m.mu.Unlock()
	key := fmt.Sprintf("%s:%s", modelName, version)
	m.metadata[key] = meta
}
