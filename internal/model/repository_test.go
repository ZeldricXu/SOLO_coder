package model

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

type mockTritonClient struct {
	loadedModels map[string]bool
	metadata     map[string]interface{}
}

func newMockTritonClient() *mockTritonClient {
	return &mockTritonClient{
		loadedModels: make(map[string]bool),
		metadata:     make(map[string]interface{}),
	}
}

func (m *mockTritonClient) HealthCheck(ctx context.Context) (bool, error) {
	return true, nil
}

func (m *mockTritonClient) IsModelReady(ctx context.Context, modelName, version string) (bool, error) {
	key := modelName + ":" + version
	return m.loadedModels[key], nil
}

func (m *mockTritonClient) LoadModel(ctx context.Context, modelName, version string) error {
	key := modelName + ":" + version
	m.loadedModels[key] = true
	return nil
}

func (m *mockTritonClient) UnloadModel(ctx context.Context, modelName, version string) error {
	key := modelName + ":" + version
	delete(m.loadedModels, key)
	return nil
}

func (m *mockTritonClient) Infer(ctx context.Context, modelName, version string, inputs interface{}, outputNames []string) (interface{}, error) {
	return nil, nil
}

func (m *mockTritonClient) GetModelMetadata(ctx context.Context, modelName, version string) (interface{}, error) {
	return m.metadata[modelName+":"+version], nil
}

func (m *mockTritonClient) Close() error {
	return nil
}

type mockDB struct {
	data map[string]interface{}
}

func newMockDB() *mockDB {
	return &mockDB{data: make(map[string]interface{})}
}

func (m *mockDB) Exec(ctx context.Context, query string, args ...interface{}) (int64, error) {
	return 1, nil
}

func (m *mockDB) QueryRow(ctx context.Context, query string, args ...interface{}) *mockRow {
	return &mockRow{result: args}
}

func (m *mockDB) Query(ctx context.Context, query string, args ...interface{}) (mockRows, error) {
	return mockRows{}, nil
}

func (m *mockDB) Pool() *pgxpool.Pool {
	return nil
}

func (m *mockDB) Close() {}

type mockRow struct {
	result interface{}
}

func (r *mockRow) Scan(dest ...interface{}) error {
	if len(dest) >= 1 {
		if s, ok := dest[0].(*string); ok {
			*s = "test-id-123"
		}
	}
	return nil
}

type mockRows struct{}

func (r mockRows) Next() bool { return false }
func (r mockRows) Scan(dest ...interface{}) error { return nil }
func (r mockRows) Close() {}
func (r mockRows) Err() error { return nil }

func TestCreateModel(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	triton := newMockTritonClient()
	tmpDir := t.TempDir()

	repo := NewRepository(db, triton, tmpDir)

	model, err := repo.CreateModel(ctx, "test-namespace", "test-model", "Test Description", map[string]string{
		"team": "ml-team",
	})

	require.NoError(t, err)
	assert.NotEmpty(t, model.ID)
	assert.Equal(t, "test-model", model.Name)
	assert.Equal(t, "test-namespace", model.Namespace)
	assert.Equal(t, "Test Description", model.Description)
	assert.Equal(t, "ml-team", model.Labels["team"])
}

func TestCreateModelVersion_TensorFlow(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	triton := newMockTritonClient()
	tmpDir := t.TempDir()

	repo := NewRepository(db, triton, tmpDir)

	model, _ := repo.CreateModel(ctx, "test-namespace", "tf-model", "TF Model", nil)

	mockSavedModel := bytes.NewBufferString("fake-savedmodel-content")

	version, err := repo.CreateModelVersion(ctx, model.ID, "v1", FormatTensorFlow, mockSavedModel, "user1", map[string]interface{}{
		"accuracy": 0.95,
	})

	require.NoError(t, err)
	assert.NotEmpty(t, version.ID)
	assert.Equal(t, "v1", version.Version)
	assert.Equal(t, FormatTensorFlow, version.Format)
	assert.NotEmpty(t, version.Signature)
	assert.Greater(t, len(version.Signature), 0)

	for _, spec := range version.Signature {
		assert.NotEmpty(t, spec.Name)
		assert.NotEmpty(t, spec.DType)
		assert.NotEmpty(t, spec.Shape)
	}

	modelPath := filepath.Join(tmpDir, model.ID, "v1")
	assert.DirExists(t, modelPath)
}

func TestCreateModelVersion_PyTorch(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	triton := newMockTritonClient()
	tmpDir := t.TempDir()

	repo := NewRepository(db, triton, tmpDir)

	model, _ := repo.CreateModel(ctx, "test-namespace", "pt-model", "PyTorch Model", nil)

	mockTorchScript := bytes.NewBufferString("fake-torchscript-content")

	version, err := repo.CreateModelVersion(ctx, model.ID, "v1", FormatPyTorch, mockTorchScript, "user1", nil)

	require.NoError(t, err)
	assert.NotEmpty(t, version.ID)
	assert.Equal(t, FormatPyTorch, version.Format)
	assert.NotEmpty(t, version.Signature)

	inputFound := false
	outputFound := false
	for _, spec := range version.Signature {
		if spec.IsInput {
			inputFound = true
		} else {
			outputFound = true
		}
	}
	assert.True(t, inputFound, "Should have input tensors in signature")
	assert.True(t, outputFound, "Should have output tensors in signature")
}

func TestCreateModelVersion_ONNX(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	triton := newMockTritonClient()
	tmpDir := t.TempDir()

	repo := NewRepository(db, triton, tmpDir)

	model, _ := repo.CreateModel(ctx, "test-namespace", "onnx-model", "ONNX Model", nil)

	mockONNX := bytes.NewBufferString("fake-onnx-content")

	version, err := repo.CreateModelVersion(ctx, model.ID, "v1", FormatONNX, mockONNX, "user1", nil)

	require.NoError(t, err)
	assert.Equal(t, FormatONNX, version.Format)
	assert.NotEmpty(t, version.Signature)
}

func TestModelVersioning_MultipleVersions(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	triton := newMockTritonClient()
	tmpDir := t.TempDir()

	repo := NewRepository(db, triton, tmpDir)

	model, _ := repo.CreateModel(ctx, "test-namespace", "multi-version-model", "Test", nil)

	for _, v := range []string{"v1", "v2", "prod", "canary"} {
		mockFile := bytes.NewBufferString("fake-content-" + v)
		_, err := repo.CreateModelVersion(ctx, model.ID, v, FormatONNX, mockFile, "user1", nil)
		require.NoError(t, err)
	}

	v1Path := filepath.Join(tmpDir, model.ID, "v1")
	v2Path := filepath.Join(tmpDir, model.ID, "v2")
	prodPath := filepath.Join(tmpDir, model.ID, "prod")
	canaryPath := filepath.Join(tmpDir, model.ID, "canary")

	assert.DirExists(t, v1Path)
	assert.DirExists(t, v2Path)
	assert.DirExists(t, prodPath)
	assert.DirExists(t, canaryPath)
}

func TestCreateModelVersion_UnsupportedFormat(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	triton := newMockTritonClient()
	tmpDir := t.TempDir()

	repo := NewRepository(db, triton, tmpDir)

	model, _ := repo.CreateModel(ctx, "test-namespace", "bad-format-model", "Test", nil)

	mockFile := bytes.NewBufferString("fake-content")
	badFormat := ModelFormat("scikit-learn")

	version, err := repo.CreateModelVersion(ctx, model.ID, "v1", badFormat, mockFile, "user1", nil)

	assert.Error(t, err)
	assert.Nil(t, version)
	assert.Contains(t, err.Error(), "unsupported format")
}

func TestCreateModelVersion_CorruptedFile(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	triton := newMockTritonClient()
	tmpDir := t.TempDir()

	repo := NewRepository(db, triton, tmpDir)

	model, _ := repo.CreateModel(ctx, "test-namespace", "corrupted-model", "Test", nil)

	corruptedReader := &errorReader{}

	version, err := repo.CreateModelVersion(ctx, model.ID, "v1", FormatTensorFlow, corruptedReader, "user1", nil)

	assert.Error(t, err)
	assert.Nil(t, version)
}

type errorReader struct{}

func (e *errorReader) Read(p []byte) (n int, err error) {
	return 0, os.ErrInvalid
}

func TestGetFileExtension(t *testing.T) {
	tests := []struct {
		format   ModelFormat
		expected string
	}{
		{FormatTensorFlow, ".savedmodel"},
		{FormatPyTorch, ".pt"},
		{FormatONNX, ".onnx"},
		{ModelFormat("unknown"), ".bin"},
	}

	for _, tt := range tests {
		t.Run(string(tt.format), func(t *testing.T) {
			assert.Equal(t, tt.expected, getFileExtension(tt.format))
		})
	}
}

func TestEstimateGPUMemory(t *testing.T) {
	signature := []TensorSpec{
		{Name: "input", Shape: []int64{1, 3, 224, 224}, DType: DTypeFloat32, IsInput: true},
		{Name: "output", Shape: []int64{1, 1000}, DType: DTypeFloat32, IsInput: false},
	}

	memoryMB := estimateGPUMemory(signature)

	assert.Greater(t, memoryMB, int64(0))
}

func TestParseModelSignature_TensorFlow(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	triton := newMockTritonClient()
	tmpDir := t.TempDir()
	repo := NewRepository(db, triton, tmpDir)

	sig, err := repo.parseTensorFlowSignature("/fake/path")

	require.NoError(t, err)
	assert.NotEmpty(t, sig)

	inputFound := false
	outputFound := false
	for _, s := range sig {
		if s.IsInput {
			inputFound = true
			assert.Equal(t, DTypeFloat32, s.DType)
		} else {
			outputFound = true
		}
	}
	assert.True(t, inputFound)
	assert.True(t, outputFound)
}

func TestParseModelSignature_PyTorch(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	triton := newMockTritonClient()
	tmpDir := t.TempDir()
	repo := NewRepository(db, triton, tmpDir)

	sig, err := repo.parseTorchScriptSignature("/fake/path")

	require.NoError(t, err)
	assert.NotEmpty(t, sig)
	assert.Equal(t, "input", sig[0].Name)
}

func TestParseModelSignature_ONNX(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	triton := newMockTritonClient()
	tmpDir := t.TempDir()
	repo := NewRepository(db, triton, tmpDir)

	sig, err := repo.parseONNXSignature("/fake/path")

	require.NoError(t, err)
	assert.NotEmpty(t, sig)
	assert.Equal(t, "data", sig[0].Name)
}

func TestUpdateModelVersionStatus(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	triton := newMockTritonClient()
	tmpDir := t.TempDir()
	repo := NewRepository(db, triton, tmpDir)

	err := repo.UpdateModelVersionStatus(ctx, "test-version-id", StatusReady)

	assert.NoError(t, err)
}

func TestTensorSpec_Serialization(t *testing.T) {
	spec := TensorSpec{
		Name:    "test_input",
		Shape:   []int64{-1, 3, 224, 224},
		DType:   DTypeFloat32,
		IsInput: true,
	}

	data, err := json.Marshal(spec)
	require.NoError(t, err)

	var deserialized TensorSpec
	err = json.Unmarshal(data, &deserialized)
	require.NoError(t, err)

	assert.Equal(t, spec.Name, deserialized.Name)
	assert.Equal(t, spec.DType, deserialized.DType)
	assert.Equal(t, spec.IsInput, deserialized.IsInput)
	assert.Equal(t, spec.Shape, deserialized.Shape)
}

func TestChecksumCalculation(t *testing.T) {
	ctx := context.Background()
	logger, _ := zap.NewDevelopment()
	db := newMockDB()
	triton := newMockTritonClient()
	tmpDir := t.TempDir()
	repo := NewRepository(db, triton, tmpDir)

	model, _ := repo.CreateModel(ctx, "test-namespace", "checksum-model", "Test", nil)

	content := bytes.NewBufferString("deterministic content")
	version, err := repo.CreateModelVersion(ctx, model.ID, "v1", FormatONNX, content, "user1", nil)

	require.NoError(t, err)
	assert.NotEmpty(t, version.Checksum)
	assert.Len(t, version.Checksum, 64)
}

func TestModelMetadata(t *testing.T) {
	metadata := map[string]interface{}{
		"accuracy":  0.95,
		"dataset":   "imagenet",
		"epochs":    float64(100),
		"framework": "pytorch",
	}

	data, err := json.Marshal(metadata)
	require.NoError(t, err)

	var deserialized map[string]interface{}
	err = json.Unmarshal(data, &deserialized)
	require.NoError(t, err)

	assert.Equal(t, 0.95, deserialized["accuracy"])
	assert.Equal(t, "imagenet", deserialized["dataset"])
	assert.Equal(t, float64(100), deserialized["epochs"])
}

func TestModelStatus_String(t *testing.T) {
	statuses := []ModelStatus{
		StatusPending,
		StatusReady,
		StatusDeploying,
		StatusFailed,
		StatusArchived,
	}

	for _, status := range statuses {
		assert.NotEmpty(t, string(status))
	}
}
