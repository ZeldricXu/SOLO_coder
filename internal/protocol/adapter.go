package protocol

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/edgevision/edgevision/internal/common/eventbus"
	"github.com/edgevision/edgevision/internal/common/logger"
	"github.com/edgevision/edgevision/internal/common/utils"
	"go.uber.org/zap"
)

type ProtocolType string

const (
	ProtocolModbus   ProtocolType = "modbus"
	ProtocolMQTT   ProtocolType = "mqtt"
	ProtocolOPCUA   ProtocolType = "opcua"
	ProtocolHTTP    ProtocolType = "http"
	ProtocolCustom  ProtocolType = "custom"
)

type DataFormat string

const (
	FormatJSON   DataFormat = "json"
	FormatXML    DataFormat = "xml"
	FormatCSV    DataFormat = "csv"
	FormatBinary DataFormat = "binary"
)

type ProtocolDriver interface {
	Name() string
	Protocol() ProtocolType
	Connect(ctx context.Context, config map[string]interface{}) error
	Disconnect() error
	Read(ctx context.Context, address string) (interface{}, error)
	Write(ctx context.Context, address string, value interface{}) error
	IsConnected() bool
}

type RawData struct {
	DataID       string                 `json:"data_id"`
	Protocol     ProtocolType           `json:"protocol"`
	SourceDevice string                 `json:"source_device"`
	RawPayload   []byte                 `json:"raw_payload"`
	ReceivedAt   time.Time              `json:"received_at"`
	Metadata     map[string]interface{} `json:"metadata"`
}

type StandardizedData struct {
	DataID       string                 `json:"data_id"`
	Protocol     ProtocolType           `json:"protocol"`
	SourceDevice string                 `json:"source_device"`
	Format       DataFormat             `json:"format"`
	Payload      map[string]interface{} `json:"payload"`
	ReceivedAt   time.Time              `json:"received_at"`
	ProcessedAt  time.Time              `json:"processed_at"`
	Tags         map[string]string      `json:"tags"`
}

type ConversionRule struct {
	SourceProtocol ProtocolType `json:"source_protocol"`
	TargetFormat DataFormat   `json:"target_format"`
	FieldMapping map[string]string `json:"field_mapping"`
	Transform    string                 `json:"transform"`
}

type AsyncTask struct {
	TaskID      string
	RawData       RawData
	Callback      func(StandardizedData, error)
	Status        string
	CreatedAt     time.Time
	CompletedAt   *time.Time
	Result        *StandardizedData
	Error         error
}

type ModbusDriver struct {
	connected bool
	config    map[string]interface{}
}

func (d *ModbusDriver) Name() string     { return "modbus-driver" }
func (d *ModbusDriver) Protocol() ProtocolType { return ProtocolModbus }

func (d *ModbusDriver) Connect(ctx context.Context, config map[string]interface{}) error {
	d.config = config
	d.connected = true
	logger.Get().Info("Modbus driver connected")
	return nil
}

func (d *ModbusDriver) Disconnect() error {
	d.connected = false
	return nil
}

func (d *ModbusDriver) Read(ctx context.Context, address string) (interface{}, error) {
	return map[string]interface{}{
		"register": address,
		"value":    12345,
		"timestamp": time.Now().Unix(),
	}, nil
}

func (d *ModbusDriver) Write(ctx context.Context, address string, value interface{}) error {
	return nil
}

func (d *ModbusDriver) IsConnected() bool { return d.connected }

type MQTTDriver struct {
	connected bool
	config    map[string]interface{}
}

func (d *MQTTDriver) Name() string     { return "mqtt-driver" }
func (d *MQTTDriver) Protocol() ProtocolType { return ProtocolMQTT }

func (d *MQTTDriver) Connect(ctx context.Context, config map[string]interface{}) error {
	d.config = config
	d.connected = true
	logger.Get().Info("MQTT driver connected")
	return nil
}

func (d *MQTTDriver) Disconnect() error {
	d.connected = false
	return nil
}

func (d *MQTTDriver) Read(ctx context.Context, topic string) (interface{}, error) {
	return map[string]interface{}{
		"topic":     topic,
		"payload":   "sensor_data",
		"qos":       1,
		"timestamp": time.Now().Unix(),
	}, nil
}

func (d *MQTTDriver) Write(ctx context.Context, topic string, value interface{}) error {
	return nil
}

func (d *MQTTDriver) IsConnected() bool { return d.connected }

type OPCUADriver struct {
	connected bool
	config    map[string]interface{}
}

func (d *OPCUADriver) Name() string     { return "opcua-driver" }
func (d *OPCUADriver) Protocol() ProtocolType { return ProtocolOPCUA }

func (d *OPCUADriver) Connect(ctx context.Context, config map[string]interface{}) error {
	d.config = config
	d.connected = true
	logger.Get().Info("OPC UA driver connected")
	return nil
}

func (d *OPCUADriver) Disconnect() error {
	d.connected = false
	return nil
}

func (d *OPCUADriver) Read(ctx context.Context, nodeID string) (interface{}, error) {
	return map[string]interface{}{
		"node_id":   nodeID,
		"value":     78.5,
		"quality":   "Good",
		"timestamp": time.Now().Unix(),
	}, nil
}

func (d *OPCUADriver) Write(ctx context.Context, nodeID string, value interface{}) error {
	return nil
}

func (d *OPCUADriver) IsConnected() bool { return d.connected }

type Adapter struct {
	drivers        map[ProtocolType]ProtocolDriver
	conversionRules map[string]ConversionRule
	taskQueue       chan AsyncTask
	workerPool      int
	results         map[string]*AsyncTask
	mu              sync.RWMutex
	ctx             context.Context
	cancel          context.CancelFunc
	wg              sync.WaitGroup
}

func NewAdapter(workerPool int) *Adapter {
	ctx, cancel := context.WithCancel(context.Background())
	adapter := &Adapter{
		drivers:        make(map[ProtocolType]ProtocolDriver),
		conversionRules: make(map[string]ConversionRule),
		taskQueue:      make(chan AsyncTask, 1000),
		workerPool:     workerPool,
		results:        make(map[string]*AsyncTask),
		ctx:            ctx,
		cancel:         cancel,
	}
	adapter.RegisterDriver(&ModbusDriver{})
	adapter.RegisterDriver(&MQTTDriver{})
	adapter.RegisterDriver(&OPCUADriver{})
	return adapter
}

func (a *Adapter) RegisterDriver(driver ProtocolDriver) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.drivers[driver.Protocol()] = driver
	logger.Get().Info("Protocol driver registered",
		zap.String("name", driver.Name()),
		zap.String("protocol", string(driver.Protocol())))
}

func (a *Adapter) GetDriver(protocol ProtocolType) (ProtocolDriver, bool) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	driver, exists := a.drivers[protocol]
	return driver, exists
}

func (a *Adapter) ListDrivers() []ProtocolType {
	a.mu.RLock()
	defer a.mu.RUnlock()
	protocols := make([]ProtocolType, 0, len(a.drivers))
	for p := range a.drivers {
		protocols = append(protocols, p)
	}
	return protocols
}

func (a *Adapter) ConnectDriver(protocol ProtocolType, config map[string]interface{}) error {
	driver, exists := a.GetDriver(protocol)
	if !exists {
		return fmt.Errorf("driver not found for protocol: %s", protocol)
	}
	return driver.Connect(a.ctx, config)
}

func (a *Adapter) AddConversionRule(name string, rule ConversionRule) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.conversionRules[name] = rule
}

func (a *Adapter) GetConversionRule(name string) (ConversionRule, bool) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	rule, exists := a.conversionRules[name]
	return rule, exists
}

func (a *Adapter) ConvertAsync(rawData RawData, callback func(StandardizedData, error)) string {
	taskID := utils.GenerateID("conv")
	task := AsyncTask{
		TaskID:    taskID,
		RawData:   rawData,
		Callback:  callback,
		Status:    "queued",
		CreatedAt: time.Now().UTC(),
	}
	a.mu.Lock()
	a.results[taskID] = &task
	a.mu.Unlock()
	select {
	case a.taskQueue <- task:
		logger.Get().Debug("Conversion task queued", zap.String("task_id", taskID))
	case <-a.ctx.Done():
	}
	return taskID
}

func (a *Adapter) Start() {
	for i := 0; i < a.workerPool; i++ {
		a.wg.Add(1)
		go a.worker(i)
	}
	logger.Get().Info("Protocol adapter started", zap.Int("workers", a.workerPool))
}

func (a *Adapter) worker(id int) {
	defer a.wg.Done()
	for {
		select {
		case task := <-a.taskQueue:
			a.processTask(&task)
		case <-a.ctx.Done():
			return
		}
	}
}

func (a *Adapter) processTask(task *AsyncTask) {
	a.mu.Lock()
	task.Status = "processing"
	a.mu.Unlock()
	logger.Get().Debug("Processing conversion task", zap.String("task_id", task.TaskID))
	time.Sleep(50 * time.Millisecond)
	result, err := a.convert(task.RawData)
	now := time.Now().UTC()
	a.mu.Lock()
	task.Status = "completed"
	task.CompletedAt = &now
	task.Result = &result
	task.Error = err
	a.results[task.TaskID] = task
	a.mu.Unlock()
	if task.Callback != nil {
		go task.Callback(result, err)
	}
	eventType := "protocol.convert.success"
	if err != nil {
		eventType = "protocol.convert.failed"
	}
	eventbus.GetBus().Publish(eventbus.Event{
		Type: eventType,
		Payload: map[string]interface{}{
			"task_id": task.TaskID,
			"protocol": string(task.RawData.Protocol),
			"error":   err,
		},
	})
	logger.Get().Info("Conversion task completed",
		zap.String("task_id", task.TaskID),
		zap.Bool("success", err == nil))
}

func (a *Adapter) convert(rawData RawData) (StandardizedData, error) {
	var payload map[string]interface{}
	switch rawData.Protocol {
	case ProtocolModbus:
		payload = map[string]interface{}{
			"register_value": string(rawData.RawPayload),
			"parsed":         true,
		}
	case ProtocolMQTT:
		_ = json.Unmarshal(rawData.RawPayload, &payload)
		if payload == nil {
			payload = map[string]interface{}{"raw": string(rawData.RawPayload)}
		}
	case ProtocolOPCUA:
		payload = map[string]interface{}{
			"node_data": string(rawData.RawPayload),
		}
	case ProtocolHTTP:
		payload = map[string]interface{}{
			"http_data": string(rawData.RawPayload),
		}
	default:
		payload = map[string]interface{}{
			"raw": string(rawData.RawPayload),
		}
	}
	return StandardizedData{
		DataID:       rawData.DataID,
		Protocol:     rawData.Protocol,
		SourceDevice: rawData.SourceDevice,
		Format:       FormatJSON,
		Payload:      payload,
		ReceivedAt:   rawData.ReceivedAt,
		ProcessedAt:  time.Now().UTC(),
		Tags: map[string]string{
			"source":    string(rawData.Protocol),
			"converted": "true",
		},
	}, nil
}

func (a *Adapter) GetTaskStatus(taskID string) (*AsyncTask, bool) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	task, exists := a.results[taskID]
	return task, exists
}

func (a *Adapter) ReadAsync(protocol ProtocolType, address string, callback func(interface{}, error)) string {
	taskID := utils.GenerateID("read")
	go func() {
		driver, exists := a.GetDriver(protocol)
		if !exists {
			callback(nil, fmt.Errorf("driver not found: %s", protocol))
			return
		}
		if !driver.IsConnected() {
			callback(nil, fmt.Errorf("driver not connected: %s", protocol))
			return
		}
		result, err := driver.Read(a.ctx, address)
		callback(result, err)
	}()
	return taskID
}

func (a *Adapter) Stop() {
	a.cancel()
	close(a.taskQueue)
	a.wg.Wait()
	for _, driver := range a.drivers {
		_ = driver.Disconnect()
	}
	logger.Get().Info("Protocol adapter stopped")
}
