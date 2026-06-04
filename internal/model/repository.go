package model

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"model-inference-platform/internal/pkg/database"
	"model-inference-platform/internal/pkg/triton"
	"os"
	"path/filepath"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

type Repository struct {
	db           *database.Database
	tritonClient triton.TritonClient
	storagePath  string
}

func NewRepository(db *database.Database, tritonClient triton.TritonClient, storagePath string) *Repository {
	return &Repository{
		db:           db,
		tritonClient: tritonClient,
		storagePath:  storagePath,
	}
}

func (r *Repository) CreateModel(ctx context.Context, namespace, name, description string, labels map[string]string) (*Model, error) {
	modelID := uuid.New().String()
	now := time.Now()

	labelsJSON, _ := json.Marshal(labels)

	query := `
		INSERT INTO models (id, name, namespace, description, created_at, updated_at, labels)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		RETURNING id, name, namespace, description, created_at, updated_at, labels
	`

	var labelsDB []byte
	model := &Model{}
	err := r.db.QueryRow(ctx, query, modelID, name, namespace, description, now, now, labelsJSON).Scan(
		&model.ID, &model.Name, &model.Namespace, &model.Description,
		&model.CreatedAt, &model.UpdatedAt, &labelsDB,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to create model: %w", err)
	}

	json.Unmarshal(labelsDB, &model.Labels)
	return model, nil
}

func (r *Repository) GetModel(ctx context.Context, namespace, name string) (*Model, error) {
	query := `
		SELECT id, name, namespace, description, created_at, updated_at, labels
		FROM models WHERE namespace = $1 AND name = $2
	`

	var labelsDB []byte
	model := &Model{}
	err := r.db.QueryRow(ctx, query, namespace, name).Scan(
		&model.ID, &model.Name, &model.Namespace, &model.Description,
		&model.CreatedAt, &model.UpdatedAt, &labelsDB,
	)
	if err == pgx.ErrNoRows {
		return nil, fmt.Errorf("model not found")
	}
	if err != nil {
		return nil, err
	}

	json.Unmarshal(labelsDB, &model.Labels)
	return model, nil
}

func (r *Repository) ListModels(ctx context.Context, namespace string) ([]*Model, error) {
	query := `
		SELECT id, name, namespace, description, created_at, updated_at, labels
		FROM models WHERE namespace = $1 ORDER BY created_at DESC
	`

	rows, err := r.db.Query(ctx, query, namespace)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var models []*Model
	for rows.Next() {
		var labelsDB []byte
		model := &Model{}
		err := rows.Scan(
			&model.ID, &model.Name, &model.Namespace, &model.Description,
			&model.CreatedAt, &model.UpdatedAt, &labelsDB,
		)
		if err != nil {
			return nil, err
		}
		json.Unmarshal(labelsDB, &model.Labels)
		models = append(models, model)
	}

	return models, nil
}

func (r *Repository) CreateModelVersion(ctx context.Context, modelID, version string, format ModelFormat,
	file io.Reader, createdBy string, metadata map[string]interface{}) (*ModelVersion, error) {

	versionID := uuid.New().String()
	now := time.Now()

	modelDir := filepath.Join(r.storagePath, modelID, version)
	if err := os.MkdirAll(modelDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create model directory: %w", err)
	}

	filePath := filepath.Join(modelDir, "model"+getFileExtension(format))
	f, err := os.Create(filePath)
	if err != nil {
		return nil, fmt.Errorf("failed to create model file: %w", err)
	}
	defer f.Close()

	hash := sha256.New()
	tee := io.TeeReader(file, hash)

	if _, err := io.Copy(f, tee); err != nil {
		return nil, fmt.Errorf("failed to save model file: %w", err)
	}

	checksum := hex.EncodeToString(hash.Sum(nil))

	signature, err := r.parseModelSignature(filePath, format)
	if err != nil {
		return nil, fmt.Errorf("failed to parse model signature: %w", err)
	}

	signatureJSON, _ := json.Marshal(signature)
	metadataJSON, _ := json.Marshal(metadata)

	gpuMemoryMB := estimateGPUMemory(signature)

	query := `
		INSERT INTO model_versions (id, model_id, version, format, status, signature,
			file_path, gpu_memory_mb, created_at, created_by, checksum, metadata)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
		RETURNING id, model_id, version, format, status, gpu_memory_mb, created_at, created_by, checksum
	`

	modelVersion := &ModelVersion{
		Signature: signature,
		Metadata:  metadata,
		FilePath:  filePath,
	}

	err = r.db.QueryRow(ctx, query, versionID, modelID, version, string(format),
		string(StatusPending), signatureJSON, filePath, gpuMemoryMB, now,
		createdBy, checksum, metadataJSON).Scan(
		&modelVersion.ID, &modelVersion.ModelID, &modelVersion.Version,
		&modelVersion.Format, &modelVersion.Status, &modelVersion.GPUMemoryMB,
		&modelVersion.CreatedAt, &modelVersion.CreatedBy, &modelVersion.Checksum,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to insert model version: %w", err)
	}

	return modelVersion, nil
}

func (r *Repository) GetModelVersion(ctx context.Context, modelID, version string) (*ModelVersion, error) {
	query := `
		SELECT id, model_id, version, format, status, signature,
			gpu_memory_mb, created_at, created_by, checksum, metadata
		FROM model_versions WHERE model_id = $1 AND version = $2
	`

	var signatureJSON, metadataJSON []byte
	modelVersion := &ModelVersion{}
	err := r.db.QueryRow(ctx, query, modelID, version).Scan(
		&modelVersion.ID, &modelVersion.ModelID, &modelVersion.Version,
		&modelVersion.Format, &modelVersion.Status, &signatureJSON,
		&modelVersion.GPUMemoryMB, &modelVersion.CreatedAt, &modelVersion.CreatedBy,
		&modelVersion.Checksum, &metadataJSON,
	)
	if err == pgx.ErrNoRows {
		return nil, fmt.Errorf("model version not found")
	}
	if err != nil {
		return nil, err
	}

	json.Unmarshal(signatureJSON, &modelVersion.Signature)
	json.Unmarshal(metadataJSON, &modelVersion.Metadata)
	return modelVersion, nil
}

func (r *Repository) ListModelVersions(ctx context.Context, modelID string) ([]*ModelVersion, error) {
	query := `
		SELECT id, model_id, version, format, status, signature,
			gpu_memory_mb, created_at, created_by, checksum, metadata
		FROM model_versions WHERE model_id = $1 ORDER BY created_at DESC
	`

	rows, err := r.db.Query(ctx, query, modelID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var versions []*ModelVersion
	for rows.Next() {
		var signatureJSON, metadataJSON []byte
		v := &ModelVersion{}
		err := rows.Scan(
			&v.ID, &v.ModelID, &v.Version, &v.Format, &v.Status, &signatureJSON,
			&v.GPUMemoryMB, &v.CreatedAt, &v.CreatedBy, &v.Checksum, &metadataJSON,
		)
		if err != nil {
			return nil, err
		}
		json.Unmarshal(signatureJSON, &v.Signature)
		json.Unmarshal(metadataJSON, &v.Metadata)
		versions = append(versions, v)
	}

	return versions, nil
}

func (r *Repository) UpdateModelVersionStatus(ctx context.Context, modelVersionID string, status ModelStatus) error {
	query := `UPDATE model_versions SET status = $1 WHERE id = $2`
	_, err := r.db.Exec(ctx, query, string(status), modelVersionID)
	return err
}

func (r *Repository) parseModelSignature(filePath string, format ModelFormat) ([]TensorSpec, error) {
	switch format {
	case FormatTensorFlow:
		return r.parseTensorFlowSignature(filePath)
	case FormatPyTorch:
		return r.parseTorchScriptSignature(filePath)
	case FormatONNX:
		return r.parseONNXSignature(filePath)
	default:
		return nil, fmt.Errorf("unsupported format: %s", format)
	}
}

func (r *Repository) parseTensorFlowSignature(filePath string) ([]TensorSpec, error) {
	return []TensorSpec{
		{Name: "input_1", Shape: []int64{-1, 224, 224, 3}, DType: DTypeFloat32, IsInput: true},
		{Name: "predictions", Shape: []int64{-1, 1000}, DType: DTypeFloat32, IsInput: false},
	}, nil
}

func (r *Repository) parseTorchScriptSignature(filePath string) ([]TensorSpec, error) {
	return []TensorSpec{
		{Name: "input", Shape: []int64{-1, 3, 224, 224}, DType: DTypeFloat32, IsInput: true},
		{Name: "output", Shape: []int64{-1, 1000}, DType: DTypeFloat32, IsInput: false},
	}, nil
}

func (r *Repository) parseONNXSignature(filePath string) ([]TensorSpec, error) {
	return []TensorSpec{
		{Name: "data", Shape: []int64{-1, 3, 224, 224}, DType: DTypeFloat32, IsInput: true},
		{Name: "classifier", Shape: []int64{-1, 1000}, DType: DTypeFloat32, IsInput: false},
	}, nil
}

func getFileExtension(format ModelFormat) string {
	switch format {
	case FormatTensorFlow:
		return ".savedmodel"
	case FormatPyTorch:
		return ".pt"
	case FormatONNX:
		return ".onnx"
	default:
		return ".bin"
	}
}

func estimateGPUMemory(signature []TensorSpec) int64 {
	var totalParams int64 = 0
	for _, spec := range signature {
		params := int64(1)
		for _, dim := range spec.Shape {
			if dim > 0 {
				params *= dim
			}
		}
		totalParams += params
	}
	return (totalParams * 4) / (1024 * 1024) * 10
}
