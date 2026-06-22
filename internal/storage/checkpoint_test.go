package storage

import (
	"encoding/gob"
	"testing"
	"time"

	"github.com/df1-96/experiment/internal/models"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func init() {
	gob.Register([]interface{}{})
	gob.Register(map[string]interface{}{})
}

func TestCheckpoint_SerializeDeserialize_JSON(t *testing.T) {
	original := models.Params{
		"iteration":     float64(42),
		"loss":          0.123456,
		"learning_rate": 0.001,
		"weights":       []interface{}{1.0, 2.0, 3.0, 4.0},
		"model_state":   map[string]interface{}{"layer1": 0.5, "layer2": 0.8},
		"name":          "test_model",
		"enabled":       true,
	}

	data, err := SerializeCheckpointData(original, "json")
	require.NoError(t, err)
	require.NotNil(t, data)
	assert.True(t, len(data) > 0)

	restored, err := DeserializeCheckpointData(data, "json")
	require.NoError(t, err)
	require.NotNil(t, restored)

	assert.Equal(t, original["iteration"], restored["iteration"])
	assert.Equal(t, original["loss"], restored["loss"])
	assert.Equal(t, original["learning_rate"], restored["learning_rate"])
	assert.Equal(t, original["name"], restored["name"])
	assert.Equal(t, original["enabled"], restored["enabled"])

	origWeights := original["weights"].([]interface{})
	restWeights := restored["weights"].([]interface{})
	require.Len(t, restWeights, len(origWeights))
	for i := range origWeights {
		assert.Equal(t, origWeights[i], restWeights[i])
	}

	origState := original["model_state"].(map[string]interface{})
	restState := restored["model_state"].(map[string]interface{})
	assert.Equal(t, origState["layer1"], restState["layer1"])
	assert.Equal(t, origState["layer2"], restState["layer2"])
}

func TestCheckpoint_SerializeDeserialize_Gob(t *testing.T) {
	original := models.Params{
		"iteration":     float64(100),
		"loss":          0.045678,
		"learning_rate": 0.0005,
		"weights":       []interface{}{1.5, 2.5, 3.5, 4.5, 5.5},
		"model_state":   map[string]interface{}{"layer1": 0.25, "layer2": 0.75, "layer3": 0.9},
		"name":          "gob_test_model",
		"enabled":       false,
	}

	data, err := SerializeCheckpointData(original, "gob")
	require.NoError(t, err)
	require.NotNil(t, data)
	assert.True(t, len(data) > 0)

	restored, err := DeserializeCheckpointData(data, "gob")
	require.NoError(t, err)
	require.NotNil(t, restored)

	assert.Equal(t, original["iteration"], restored["iteration"])
	assert.Equal(t, original["loss"], restored["loss"])
	assert.Equal(t, original["learning_rate"], restored["learning_rate"])
	assert.Equal(t, original["name"], restored["name"])
	assert.Equal(t, original["enabled"], restored["enabled"])

	origWeights := original["weights"].([]interface{})
	restWeights := restored["weights"].([]interface{})
	require.Len(t, restWeights, len(origWeights))
	for i := range origWeights {
		assert.Equal(t, origWeights[i], restWeights[i])
	}
}

func TestCheckpoint_SerializeDeserialize_UnsupportedFormat(t *testing.T) {
	data := models.Params{"key": "value"}

	_, err := SerializeCheckpointData(data, "xml")
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "unsupported serialization format")

	_, err = DeserializeCheckpointData([]byte("{}"), "xml")
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "unsupported serialization format")
}

func TestCheckpoint_ChecksumIntegrity(t *testing.T) {
	data := models.Params{
		"iteration": float64(50),
		"weights":   []interface{}{1.0, 2.0, 3.0},
		"loss":      0.5,
	}

	checksum := calculateChecksum(data)
	require.NotEmpty(t, checksum)

	repo := &CheckpointRepo{}
	cp := &models.Checkpoint{
		TaskID:   1,
		WorkerID: 1,
		Step:     50,
		Data:     data,
		Checksum: checksum,
	}

	actualChecksum := calculateChecksum(cp.Data)
	assert.Equal(t, cp.Checksum, actualChecksum)

	sameData := models.Params{
		"iteration": float64(50),
		"loss":      0.5,
		"weights":   []interface{}{1.0, 2.0, 3.0},
	}
	sameChecksum := calculateChecksum(sameData)
	assert.Equal(t, checksum, sameChecksum)

	differentData := models.Params{
		"iteration": float64(51),
		"weights":   []interface{}{1.0, 2.0, 3.0},
		"loss":      0.5,
	}
	differentChecksum := calculateChecksum(differentData)
	assert.NotEqual(t, checksum, differentChecksum)
	_ = repo
}

func TestCheckpoint_ChecksumTampered_Fails(t *testing.T) {
	originalData := models.Params{
		"iteration": float64(50),
		"loss":      0.5,
		"weights":   []interface{}{1.0, 2.0, 3.0},
	}

	originalChecksum := calculateChecksum(originalData)

	tamperedData := models.Params{
		"iteration": float64(50),
		"loss":      0.999,
		"weights":   []interface{}{1.0, 2.0, 999.0},
	}

	tamperedChecksum := calculateChecksum(tamperedData)
	assert.NotEqual(t, originalChecksum, tamperedChecksum)

	cp := &models.Checkpoint{
		TaskID:   1,
		WorkerID: 1,
		Step:     50,
		Data:     tamperedData,
		Checksum: originalChecksum,
	}

	actualChecksum := calculateChecksum(cp.Data)
	assert.NotEqual(t, cp.Checksum, actualChecksum)
}

func TestCheckpoint_ShouldSave_ByStep(t *testing.T) {
	repo := &CheckpointRepo{}

	config := CheckpointConfig{
		StepInterval: 10,
		TimeInterval: 0,
	}

	now := time.Now()

	shouldSave, option := repo.ShouldSave(now, 0, 5, config)
	assert.False(t, shouldSave)
	assert.Equal(t, CheckpointSaveManual, option)

	shouldSave, option = repo.ShouldSave(now, 0, 10, config)
	assert.True(t, shouldSave)
	assert.Equal(t, CheckpointSaveByStep, option)

	shouldSave, option = repo.ShouldSave(now, 5, 14, config)
	assert.False(t, shouldSave)

	shouldSave, option = repo.ShouldSave(now, 5, 15, config)
	assert.True(t, shouldSave)
	assert.Equal(t, CheckpointSaveByStep, option)

	shouldSave, option = repo.ShouldSave(now, 100, 115, config)
	assert.True(t, shouldSave)
	assert.Equal(t, CheckpointSaveByStep, option)
}

func TestCheckpoint_ShouldSave_ByTime(t *testing.T) {
	repo := &CheckpointRepo{}

	config := CheckpointConfig{
		StepInterval: 0,
		TimeInterval: 100 * time.Millisecond,
	}

	now := time.Now()
	recentTime := now.Add(-50 * time.Millisecond)
	oldTime := now.Add(-200 * time.Millisecond)

	shouldSave, option := repo.ShouldSave(recentTime, 0, 1, config)
	assert.False(t, shouldSave)
	assert.Equal(t, CheckpointSaveManual, option)

	shouldSave, option = repo.ShouldSave(oldTime, 0, 1, config)
	assert.True(t, shouldSave)
	assert.Equal(t, CheckpointSaveByTime, option)

	shouldSave, option = repo.ShouldSave(now.Add(-100*time.Millisecond), 0, 1, config)
	assert.True(t, shouldSave)
	assert.Equal(t, CheckpointSaveByTime, option)
}

func TestCheckpoint_ShouldSave_BothConditions(t *testing.T) {
	repo := &CheckpointRepo{}

	config := CheckpointConfig{
		StepInterval: 10,
		TimeInterval: 100 * time.Millisecond,
	}

	now := time.Now()
	recentTime := now.Add(-50 * time.Millisecond)
	oldTime := now.Add(-200 * time.Millisecond)

	shouldSave, option := repo.ShouldSave(recentTime, 0, 5, config)
	assert.False(t, shouldSave)

	shouldSave, option = repo.ShouldSave(recentTime, 0, 15, config)
	assert.True(t, shouldSave)
	assert.Equal(t, CheckpointSaveByStep, option)

	shouldSave, option = repo.ShouldSave(oldTime, 0, 5, config)
	assert.True(t, shouldSave)
	assert.Equal(t, CheckpointSaveByTime, option)

	shouldSave, option = repo.ShouldSave(oldTime, 0, 15, config)
	assert.True(t, shouldSave)
	assert.Equal(t, CheckpointSaveByStep, option)
}

func TestCheckpoint_ShouldSave_DisabledConfig(t *testing.T) {
	repo := &CheckpointRepo{}

	config := CheckpointConfig{
		StepInterval: 0,
		TimeInterval: 0,
	}

	now := time.Now()
	shouldSave, option := repo.ShouldSave(now.Add(-time.Hour), 0, 10000, config)
	assert.False(t, shouldSave)
	assert.Equal(t, CheckpointSaveManual, option)
}
