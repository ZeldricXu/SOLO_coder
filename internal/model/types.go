package model

import (
	"time"
)

type ModelFormat string

const (
	FormatTensorFlow ModelFormat = "tensorflow_savedmodel"
	FormatPyTorch    ModelFormat = "pytorch_torchscript"
	FormatONNX       ModelFormat = "onnx"
)

type ModelStatus string

const (
	StatusPending   ModelStatus = "pending"
	StatusReady     ModelStatus = "ready"
	StatusDeploying ModelStatus = "deploying"
	StatusFailed    ModelStatus = "failed"
	StatusArchived  ModelStatus = "archived"
)

type TensorDType string

const (
	DTypeFloat32 TensorDType = "FP32"
	DTypeFloat64 TensorDType = "FP64"
	DTypeInt32   TensorDType = "INT32"
	DTypeInt64   TensorDType = "INT64"
	DTypeBool    TensorDType = "BOOL"
	DTypeString  TensorDType = "STRING"
)

type TensorSpec struct {
	Name     string      `json:"name"`
	Shape    []int64     `json:"shape"`
	DType    TensorDType `json:"dtype"`
	IsInput  bool        `json:"is_input"`
}

type Model struct {
	ID          string      `json:"id"`
	Name        string      `json:"name"`
	Namespace   string      `json:"namespace"`
	Description string      `json:"description"`
	CreatedAt   time.Time   `json:"created_at"`
	UpdatedAt   time.Time   `json:"updated_at"`
	Labels      map[string]string `json:"labels"`
}

type ModelVersion struct {
	ID            string       `json:"id"`
	ModelID       string       `json:"model_id"`
	Version       string       `json:"version"`
	Format        ModelFormat  `json:"format"`
	Status        ModelStatus  `json:"status"`
	Signature     []TensorSpec `json:"signature"`
	FilePath      string       `json:"-"`
	GPUMemoryMB   int64        `json:"gpu_memory_mb"`
	CreatedAt     time.Time    `json:"created_at"`
	CreatedBy     string       `json:"created_by"`
	Checksum      string       `json:"checksum"`
	Metadata      map[string]interface{} `json:"metadata"`
}

type InferenceRequest struct {
	RequestID   string                 `json:"request_id"`
	TraceID     string                 `json:"trace_id,omitempty"`
	ModelName   string                 `json:"model_name"`
	Version     string                 `json:"version"`
	Namespace   string                 `json:"namespace"`
	Inputs      map[string]interface{} `json:"inputs"`
	Parameters  map[string]interface{} `json:"parameters,omitempty"`
	Timestamp   time.Time              `json:"timestamp"`
}

type InferenceResponse struct {
	RequestID   string                 `json:"request_id"`
	ModelName   string                 `json:"model_name"`
	Version     string                 `json:"version"`
	Outputs     map[string]interface{} `json:"outputs"`
	LatencyMs   int64                  `json:"latency_ms"`
	Error       string                 `json:"error,omitempty"`
	InstanceID  string                 `json:"instance_id,omitempty"`
}
