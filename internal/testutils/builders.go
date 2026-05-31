package testutils

import (
	"time"

	"edgescheduler/internal/modules/device_lifecycle"
	"edgescheduler/internal/modules/edge_inference"
	"edgescheduler/internal/modules/offline_cache"
)

type DeviceBuilder struct {
	device *device_lifecycle.Device
}

func NewDeviceBuilder() *DeviceBuilder {
	now := time.Now().UTC()
	return &DeviceBuilder{
		device: &device_lifecycle.Device{
			DeviceID:     "dev_default_001",
			Name:         "Default Device",
			Type:         "sensor",
			Status:       device_lifecycle.DeviceStatusRegistered,
			Model:        "Model-A",
			Manufacturer: "EdgeTech",
			FirmwareVersion: "1.0.0",
			IPAddress:    "192.168.1.100",
			Location:     "factory-1",
			AuthToken:    "token_abc123",
			LastHeartbeatAt: &now,
			Metadata:     map[string]interface{}{"zone": "production"},
			Labels:       map[string]string{"env": "prod"},
		},
	}
}

func (b *DeviceBuilder) WithDeviceID(id string) *DeviceBuilder {
	b.device.DeviceID = id
	return b
}

func (b *DeviceBuilder) WithName(name string) *DeviceBuilder {
	b.device.Name = name
	return b
}

func (b *DeviceBuilder) WithType(deviceType string) *DeviceBuilder {
	b.device.Type = deviceType
	return b
}

func (b *DeviceBuilder) WithStatus(status device_lifecycle.DeviceStatus) *DeviceBuilder {
	b.device.Status = status
	return b
}

func (b *DeviceBuilder) WithModel(model string) *DeviceBuilder {
	b.device.Model = model
	return b
}

func (b *DeviceBuilder) WithManufacturer(manufacturer string) *DeviceBuilder {
	b.device.Manufacturer = manufacturer
	return b
}

func (b *DeviceBuilder) WithFirmwareVersion(version string) *DeviceBuilder {
	b.device.FirmwareVersion = version
	return b
}

func (b *DeviceBuilder) WithIPAddress(ip string) *DeviceBuilder {
	b.device.IPAddress = ip
	return b
}

func (b *DeviceBuilder) WithLocation(location string) *DeviceBuilder {
	b.device.Location = location
	return b
}

func (b *DeviceBuilder) WithMetadata(metadata map[string]interface{}) *DeviceBuilder {
	b.device.Metadata = metadata
	return b
}

func (b *DeviceBuilder) WithLabels(labels map[string]string) *DeviceBuilder {
	b.device.Labels = labels
	return b
}

func (b *DeviceBuilder) Build() *device_lifecycle.Device {
	return b.device
}

type DeviceRegistrationRequestBuilder struct {
	req *device_lifecycle.DeviceRegistrationRequest
}

func NewDeviceRegistrationRequestBuilder() *DeviceRegistrationRequestBuilder {
	return &DeviceRegistrationRequestBuilder{
		req: &device_lifecycle.DeviceRegistrationRequest{
			DeviceID:     "dev_test_001",
			Name:         "Test Sensor",
			Type:         "temperature_sensor",
			Model:        "TS-2000",
			Manufacturer: "SensorCorp",
			IPAddress:    "192.168.1.101",
			Location:     "building-a",
			Metadata:     map[string]interface{}{"calibration": "standard"},
			Labels:       map[string]string{"department": "iot"},
		},
	}
}

func (b *DeviceRegistrationRequestBuilder) WithDeviceID(id string) *DeviceRegistrationRequestBuilder {
	b.req.DeviceID = id
	return b
}

func (b *DeviceRegistrationRequestBuilder) WithName(name string) *DeviceRegistrationRequestBuilder {
	b.req.Name = name
	return b
}

func (b *DeviceRegistrationRequestBuilder) WithType(deviceType string) *DeviceRegistrationRequestBuilder {
	b.req.Type = deviceType
	return b
}

func (b *DeviceRegistrationRequestBuilder) WithEmptyDeviceID() *DeviceRegistrationRequestBuilder {
	b.req.DeviceID = ""
	return b
}

func (b *DeviceRegistrationRequestBuilder) WithEmptyName() *DeviceRegistrationRequestBuilder {
	b.req.Name = ""
	return b
}

func (b *DeviceRegistrationRequestBuilder) WithEmptyType() *DeviceRegistrationRequestBuilder {
	b.req.Type = ""
	return b
}

func (b *DeviceRegistrationRequestBuilder) WithLongStringFields(length int) *DeviceRegistrationRequestBuilder {
	longStr := string(make([]byte, length))
	b.req.DeviceID = longStr
	b.req.Name = longStr
	b.req.Type = longStr
	return b
}

func (b *DeviceRegistrationRequestBuilder) Build() *device_lifecycle.DeviceRegistrationRequest {
	return b.req
}

type DeviceActivationRequestBuilder struct {
	req *device_lifecycle.DeviceActivationRequest
}

func NewDeviceActivationRequestBuilder() *DeviceActivationRequestBuilder {
	return &DeviceActivationRequestBuilder{
		req: &device_lifecycle.DeviceActivationRequest{
			DeviceID: "dev_test_001",
			Secret:   "secret_key_123",
		},
	}
}

func (b *DeviceActivationRequestBuilder) WithDeviceID(id string) *DeviceActivationRequestBuilder {
	b.req.DeviceID = id
	return b
}

func (b *DeviceActivationRequestBuilder) WithSecret(secret string) *DeviceActivationRequestBuilder {
	b.req.Secret = secret
	return b
}

func (b *DeviceActivationRequestBuilder) Build() *device_lifecycle.DeviceActivationRequest {
	return b.req
}

type DeviceHeartbeatRequestBuilder struct {
	req *device_lifecycle.DeviceHeartbeatRequest
}

func NewDeviceHeartbeatRequestBuilder() *DeviceHeartbeatRequestBuilder {
	return &DeviceHeartbeatRequestBuilder{
		req: &device_lifecycle.DeviceHeartbeatRequest{
			DeviceID:        "dev_test_001",
			FirmwareVersion: "1.0.0",
			Status:          "healthy",
			Metrics: map[string]interface{}{
				"cpu_usage": 45.5,
				"memory_usage": 62.3,
			},
		},
	}
}

func (b *DeviceHeartbeatRequestBuilder) WithDeviceID(id string) *DeviceHeartbeatRequestBuilder {
	b.req.DeviceID = id
	return b
}

func (b *DeviceHeartbeatRequestBuilder) WithFirmwareVersion(version string) *DeviceHeartbeatRequestBuilder {
	b.req.FirmwareVersion = version
	return b
}

func (b *DeviceHeartbeatRequestBuilder) WithMetrics(metrics map[string]interface{}) *DeviceHeartbeatRequestBuilder {
	b.req.Metrics = metrics
	return b
}

func (b *DeviceHeartbeatRequestBuilder) Build() *device_lifecycle.DeviceHeartbeatRequest {
	return b.req
}

type AIModelBuilder struct {
	model *edge_inference.AIModel
}

func NewAIModelBuilder() *AIModelBuilder {
	return &AIModelBuilder{
		model: &edge_inference.AIModel{
			ModelID:      "model_default_001",
			Name:         "Object Detection",
			Version:      "v1.0",
			Type:         "computer_vision",
			Format:       "onnx",
			SizeBytes:    1024 * 1024 * 50,
			Checksum:     "sha256:abc123def456",
			DownloadURL:  "https://models.example.com/obj_det_v1.onnx",
			TargetDevice: "edge-gpu",
			Status:       edge_inference.ModelStatusPending,
			Metadata:     map[string]interface{}{"precision": "fp16"},
		},
	}
}

func (b *AIModelBuilder) WithModelID(id string) *AIModelBuilder {
	b.model.ModelID = id
	return b
}

func (b *AIModelBuilder) WithName(name string) *AIModelBuilder {
	b.model.Name = name
	return b
}

func (b *AIModelBuilder) WithVersion(version string) *AIModelBuilder {
	b.model.Version = version
	return b
}

func (b *AIModelBuilder) WithType(modelType string) *AIModelBuilder {
	b.model.Type = modelType
	return b
}

func (b *AIModelBuilder) WithFormat(format string) *AIModelBuilder {
	b.model.Format = format
	return b
}

func (b *AIModelBuilder) WithStatus(status edge_inference.ModelStatus) *AIModelBuilder {
	b.model.Status = status
	return b
}

func (b *AIModelBuilder) WithSizeBytes(size int64) *AIModelBuilder {
	b.model.SizeBytes = size
	return b
}

func (b *AIModelBuilder) Build() *edge_inference.AIModel {
	return b.model
}

type InferenceRequestBuilder struct {
	req *edge_inference.InferenceRequest
}

func NewInferenceRequestBuilder() *InferenceRequestBuilder {
	return &InferenceRequestBuilder{
		req: &edge_inference.InferenceRequest{
			ModelID:  "model_default_001",
			DeviceID: "dev_test_001",
			InputData: map[string]interface{}{
				"image_url": "s3://images/test.jpg",
				"threshold": 0.8,
			},
			Priority:    1,
			CallbackURL: "https://api.example.com/callback",
		},
	}
}

func (b *InferenceRequestBuilder) WithModelID(id string) *InferenceRequestBuilder {
	b.req.ModelID = id
	return b
}

func (b *InferenceRequestBuilder) WithDeviceID(id string) *InferenceRequestBuilder {
	b.req.DeviceID = id
	return b
}

func (b *InferenceRequestBuilder) WithInputData(data map[string]interface{}) *InferenceRequestBuilder {
	b.req.InputData = data
	return b
}

func (b *InferenceRequestBuilder) WithPriority(priority int) *InferenceRequestBuilder {
	b.req.Priority = priority
	return b
}

func (b *InferenceRequestBuilder) WithEmptyModelID() *InferenceRequestBuilder {
	b.req.ModelID = ""
	return b
}

func (b *InferenceRequestBuilder) WithEmptyDeviceID() *InferenceRequestBuilder {
	b.req.DeviceID = ""
	return b
}

func (b *InferenceRequestBuilder) WithEmptyInputData() *InferenceRequestBuilder {
	b.req.InputData = nil
	return b
}

func (b *InferenceRequestBuilder) Build() *edge_inference.InferenceRequest {
	return b.req
}

type CacheRequestBuilder struct {
	req *offline_cache.CacheRequest
}

func NewCacheRequestBuilder() *CacheRequestBuilder {
	return &CacheRequestBuilder{
		req: &offline_cache.CacheRequest{
			DeviceID: "dev_test_001",
			DataType: "sensor_reading",
			Payload: map[string]interface{}{
				"temperature": 25.5,
				"humidity":    65.0,
				"timestamp":   time.Now().Unix(),
			},
			TTLSeconds: 3600,
		},
	}
}

func (b *CacheRequestBuilder) WithDeviceID(id string) *CacheRequestBuilder {
	b.req.DeviceID = id
	return b
}

func (b *CacheRequestBuilder) WithDataType(dataType string) *CacheRequestBuilder {
	b.req.DataType = dataType
	return b
}

func (b *CacheRequestBuilder) WithPayload(payload map[string]interface{}) *CacheRequestBuilder {
	b.req.Payload = payload
	return b
}

func (b *CacheRequestBuilder) WithTTL(ttl int) *CacheRequestBuilder {
	b.req.TTLSeconds = ttl
	return b
}

func (b *CacheRequestBuilder) WithEmptyDeviceID() *CacheRequestBuilder {
	b.req.DeviceID = ""
	return b
}

func (b *CacheRequestBuilder) WithEmptyDataType() *CacheRequestBuilder {
	b.req.DataType = ""
	return b
}

func (b *CacheRequestBuilder) WithEmptyPayload() *CacheRequestBuilder {
	b.req.Payload = nil
	return b
}

func (b *CacheRequestBuilder) WithZeroTTL() *CacheRequestBuilder {
	b.req.TTLSeconds = 0
	return b
}

func (b *CacheRequestBuilder) WithLargePayload(sizeKB int) *CacheRequestBuilder {
	largeData := make(map[string]interface{})
	for i := 0; i < sizeKB*10; i++ {
		key := "field_" + string(rune(i))
		largeData[key] = string(make([]byte, 100))
	}
	b.req.Payload = largeData
	return b
}

func (b *CacheRequestBuilder) Build() *offline_cache.CacheRequest {
	return b.req
}

type CachedDataBuilder struct {
	data *offline_cache.CachedData
}

func NewCachedDataBuilder() *CachedDataBuilder {
	now := time.Now().UTC()
	return &CachedDataBuilder{
		data: &offline_cache.CachedData{
			CacheKey:   "cache_default_001",
			DeviceID:   "dev_test_001",
			DataType:   "sensor_reading",
			Payload:    map[string]interface{}{"value": 42},
			Status:     offline_cache.CacheStatusPending,
			RetryCount: 0,
			SizeBytes:  256,
			CreatedAt:  now,
		},
	}
}

func (b *CachedDataBuilder) WithCacheKey(key string) *CachedDataBuilder {
	b.data.CacheKey = key
	return b
}

func (b *CachedDataBuilder) WithDeviceID(id string) *CachedDataBuilder {
	b.data.DeviceID = id
	return b
}

func (b *CachedDataBuilder) WithStatus(status offline_cache.CacheStatus) *CachedDataBuilder {
	b.data.Status = status
	return b
}

func (b *CachedDataBuilder) WithRetryCount(count int) *CachedDataBuilder {
	b.data.RetryCount = count
	return b
}

func (b *CachedDataBuilder) WithExpiresAt(t time.Time) *CachedDataBuilder {
	b.data.ExpiresAt = &t
	return b
}

func (b *CachedDataBuilder) WithSyncedAt(t time.Time) *CachedDataBuilder {
	b.data.SyncedAt = &t
	return b
}

func (b *CachedDataBuilder) Build() *offline_cache.CachedData {
	return b.data
}
