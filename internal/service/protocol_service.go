package service

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/edgevision/edgevision/internal/domain/model"
	"github.com/edgevision/edgevision/internal/infrastructure/cache"
	"github.com/edgevision/edgevision/internal/infrastructure/database"
	"github.com/edgevision/edgevision/internal/infrastructure/logger"
	"github.com/edgevision/edgevision/pkg/utils"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type ProtocolService struct {
	db        *gorm.DB
	drivers   map[string]ProtocolDriver
	adapters  map[string]*ProtocolAdapterWorker
}

type ProtocolDriver interface {
	Connect(config map[string]interface{}) error
	Disconnect() error
	Read(address string, length int) (interface{}, error)
	Write(address string, value interface{}) error
	Subscribe(address string, callback func(value interface{})) error
	Unsubscribe(address string) error
	IsConnected() bool
}

type ProtocolAdapterWorker struct {
	adapter *model.ProtocolAdapter
	driver  ProtocolDriver
	stopCh  chan struct{}
}

func NewProtocolService() *ProtocolService {
	service := &ProtocolService{
		db:       database.GetDB(),
		drivers:  make(map[string]ProtocolDriver),
		adapters: make(map[string]*ProtocolAdapterWorker),
	}

	service.registerBuiltinDrivers()

	return service
}

func (s *ProtocolService) registerBuiltinDrivers() {
	s.drivers[model.ProtocolModbus] = &ModbusDriver{}
	s.drivers[model.ProtocolMQTT] = &MQTTDriver{}
	s.drivers[model.ProtocolHTTP] = &HTTPDriver{}
}

type RegisterDriverRequest struct {
	Name                 string                 `json:"name"`
	Protocol             string                 `json:"protocol"`
	Version              string                 `json:"version"`
	Description          string                 `json:"description"`
	DriverType           string                 `json:"driver_type"`
	LibraryPath          string                 `json:"library_path"`
	ConfigSchema         map[string]interface{} `json:"config_schema"`
	DataFormat           map[string]interface{} `json:"data_format"`
	SupportedDevices     []string               `json:"supported_devices"`
	Parameters           map[string]interface{} `json:"parameters"`
}

func (s *ProtocolService) RegisterDriver(ctx context.Context, req *RegisterDriverRequest) (*model.ProtocolDriver, error) {
	driver := &model.ProtocolDriver{
		ID:               utils.GenerateID("drv"),
		Name:             req.Name,
		Protocol:         req.Protocol,
		Version:          req.Version,
		Description:      req.Description,
		DriverType:       req.DriverType,
		LibraryPath:      req.LibraryPath,
		ConfigSchema:     req.ConfigSchema,
		DataFormat:       req.DataFormat,
		SupportedDevices: req.SupportedDevices,
		Parameters:       req.Parameters,
		IsEnabled:        true,
		CreatedAt:        utils.Now(),
		UpdatedAt:        utils.Now(),
	}

	if err := s.db.Create(driver).Error; err != nil {
		logger.Get().Error("failed to register protocol driver", zap.Error(err))
		return nil, err
	}

	return driver, nil
}

func (s *ProtocolService) GetDriver(ctx context.Context, driverID string) (*model.ProtocolDriver, error) {
	var driver model.ProtocolDriver
	if err := s.db.First(&driver, "id = ?", driverID).Error; err != nil {
		return nil, err
	}
	return &driver, nil
}

func (s *ProtocolService) ListDrivers(ctx context.Context, page, pageSize int, protocol string) ([]model.ProtocolDriver, int64, error) {
	var drivers []model.ProtocolDriver
	var total int64

	query := s.db.Model(&model.ProtocolDriver{})
	if protocol != "" {
		query = query.Where("protocol = ?", protocol)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&drivers).Error; err != nil {
		return nil, 0, err
	}

	return drivers, total, nil
}

type CreateAdapterRequest struct {
	Name             string                 `json:"name"`
	DriverID         string                 `json:"driver_id"`
	DeviceID         string                 `json:"device_id"`
	ConnectionConfig map[string]interface{} `json:"connection_config"`
	DataMapping      map[string]string      `json:"data_mapping"`
	PollingInterval  int                    `json:"polling_interval"`
	Timeout          int                    `json:"timeout"`
	RetryCount       int                    `json:"retry_count"`
	Metadata         map[string]interface{} `json:"metadata"`
}

func (s *ProtocolService) CreateAdapter(ctx context.Context, req *CreateAdapterRequest) (*model.ProtocolAdapter, error) {
	var driver model.ProtocolDriver
	if err := s.db.First(&driver, "id = ?", req.DriverID).Error; err != nil {
		return nil, errors.New("driver not found")
	}

	adapter := &model.ProtocolAdapter{
		ID:               utils.GenerateID("adp"),
		Name:             req.Name,
		DriverID:         req.DriverID,
		DeviceID:         req.DeviceID,
		Protocol:         driver.Protocol,
		Status:           model.AdapterStatusDisconnected,
		ConnectionConfig: req.ConnectionConfig,
		DataMapping:      req.DataMapping,
		PollingInterval:  req.PollingInterval,
		Timeout:          req.Timeout,
		RetryCount:       req.RetryCount,
		Metadata:         req.Metadata,
		CreatedAt:        utils.Now(),
		UpdatedAt:        utils.Now(),
	}

	if err := s.db.Create(adapter).Error; err != nil {
		return nil, err
	}

	return adapter, nil
}

func (s *ProtocolService) StartAdapter(ctx context.Context, adapterID string) (*model.ProtocolAdapter, error) {
	var adapter model.ProtocolAdapter
	if err := s.db.First(&adapter, "id = ?", adapterID).Error; err != nil {
		return nil, err
	}

	var driver model.ProtocolDriver
	if err := s.db.First(&driver, "id = ?", adapter.DriverID).Error; err != nil {
		return nil, errors.New("driver not found")
	}

	protocolDriver, ok := s.drivers[driver.Protocol]
	if !ok {
		return nil, fmt.Errorf("protocol driver '%s' not found", driver.Protocol)
	}

	adapter.Status = model.AdapterStatusConnecting
	adapter.UpdatedAt = utils.Now()
	_ = s.db.Save(&adapter)

	if err := protocolDriver.Connect(adapter.ConnectionConfig); err != nil {
		adapter.Status = model.AdapterStatusError
		adapter.UpdatedAt = utils.Now()
		_ = s.db.Save(&adapter)
		return nil, err
	}

	adapter.Status = model.AdapterStatusConnected
	now := utils.Now()
	adapter.LastConnected = &now
	adapter.UpdatedAt = now
	_ = s.db.Save(&adapter)

	worker := &ProtocolAdapterWorker{
		adapter: &adapter,
		driver:  protocolDriver,
		stopCh:  make(chan struct{}),
	}

	s.adapters[adapterID] = worker

	if adapter.PollingInterval > 0 {
		go s.startPolling(worker)
	}

	logger.Get().Info("protocol adapter started",
		zap.String("adapter_id", adapterID),
		zap.String("protocol", adapter.Protocol))

	return &adapter, nil
}

func (s *ProtocolService) startPolling(worker *ProtocolAdapterWorker) {
	interval := time.Duration(worker.adapter.PollingInterval) * time.Second
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-worker.stopCh:
			return
		case <-ticker.C:
			s.pollData(worker)
		}
	}
}

func (s *ProtocolService) pollData(worker *ProtocolAdapterWorker) {
	ctx := context.Background()
	adapter := worker.adapter

	for address, mapping := range adapter.DataMapping {
		value, err := worker.driver.Read(address, 1)
		if err != nil {
			logger.Get().Error("failed to read data",
				zap.String("adapter_id", adapter.ID),
				zap.String("address", address),
				zap.Error(err))
			continue
		}

		normalizedData := s.normalizeData(mapping, value)

		record := &model.ProtocolDataRecord{
			ID:             utils.GenerateID("pdr"),
			AdapterID:      adapter.ID,
			DeviceID:       adapter.DeviceID,
			Protocol:       adapter.Protocol,
			RawData:        fmt.Sprintf("%v", value),
			NormalizedData: normalizedData,
			DataPoints: map[string]interface{}{
				mapping: value,
			},
			Quality:   100,
			Timestamp: utils.Now(),
			CreatedAt: utils.Now(),
		}

		if err := s.db.Create(record).Error; err != nil {
			logger.Get().Error("failed to create data record", zap.Error(err))
			continue
		}

		cacheKey := fmt.Sprintf("protocol:data:%s", adapter.DeviceID)
		_ = cache.Publish(ctx, cacheKey, utils.ToJSON(record))
	}
}

func (s *ProtocolService) normalizeData(mapping string, value interface{}) map[string]interface{} {
	return map[string]interface{}{
		mapping: value,
		"_raw":  value,
	}
}

func (s *ProtocolService) StopAdapter(ctx context.Context, adapterID string) error {
	worker, ok := s.adapters[adapterID]
	if !ok {
		return errors.New("adapter not running")
	}

	close(worker.stopCh)

	if worker.driver != nil {
		_ = worker.driver.Disconnect()
	}

	delete(s.adapters, adapterID)

	var adapter model.ProtocolAdapter
	if err := s.db.First(&adapter, "id = ?", adapterID).Error; err == nil {
		adapter.Status = model.AdapterStatusDisconnected
		now := utils.Now()
		adapter.LastDisconnected = &now
		adapter.UpdatedAt = now
		_ = s.db.Save(&adapter)
	}

	return nil
}

func (s *ProtocolService) GetAdapter(ctx context.Context, adapterID string) (*model.ProtocolAdapter, error) {
	var adapter model.ProtocolAdapter
	if err := s.db.First(&adapter, "id = ?", adapterID).Error; err != nil {
		return nil, err
	}
	return &adapter, nil
}

func (s *ProtocolService) ListAdapters(ctx context.Context, deviceID, status string, page, pageSize int) ([]model.ProtocolAdapter, int64, error) {
	var adapters []model.ProtocolAdapter
	var total int64

	query := s.db.Model(&model.ProtocolAdapter{})
	if deviceID != "" {
		query = query.Where("device_id = ?", deviceID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&adapters).Error; err != nil {
		return nil, 0, err
	}

	return adapters, total, nil
}

func (s *ProtocolService) ReadData(ctx context.Context, adapterID, address string, length int) (interface{}, error) {
	worker, ok := s.adapters[adapterID]
	if !ok {
		return nil, errors.New("adapter not running")
	}

	return worker.driver.Read(address, length)
}

func (s *ProtocolService) WriteData(ctx context.Context, adapterID, address string, value interface{}) error {
	worker, ok := s.adapters[adapterID]
	if !ok {
		return errors.New("adapter not running")
	}

	return worker.driver.Write(address, value)
}

func (s *ProtocolService) GetDataRecords(ctx context.Context, adapterID string, startTime, endTime time.Time, page, pageSize int) ([]model.ProtocolDataRecord, int64, error) {
	var records []model.ProtocolDataRecord
	var total int64

	query := s.db.Model(&model.ProtocolDataRecord{}).Where("adapter_id = ?", adapterID)

	if !startTime.IsZero() {
		query = query.Where("timestamp >= ?", startTime)
	}
	if !endTime.IsZero() {
		query = query.Where("timestamp <= ?", endTime)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("timestamp DESC").Find(&records).Error; err != nil {
		return nil, 0, err
	}

	return records, total, nil
}

type ModbusDriver struct {
	connected bool
}

func (d *ModbusDriver) Connect(config map[string]interface{}) error    { d.connected = true; return nil }
func (d *ModbusDriver) Disconnect() error                             { d.connected = false; return nil }
func (d *ModbusDriver) Read(address string, length int) (interface{}, error) { return 123, nil }
func (d *ModbusDriver) Write(address string, value interface{}) error { return nil }
func (d *ModbusDriver) Subscribe(address string, callback func(value interface{})) error {
	return nil
}
func (d *ModbusDriver) Unsubscribe(address string) error              { return nil }
func (d *ModbusDriver) IsConnected() bool                             { return d.connected }

type MQTTDriver struct {
	connected bool
}

func (d *MQTTDriver) Connect(config map[string]interface{}) error    { d.connected = true; return nil }
func (d *MQTTDriver) Disconnect() error                             { d.connected = false; return nil }
func (d *MQTTDriver) Read(address string, length int) (interface{}, error) { return "data", nil }
func (d *MQTTDriver) Write(address string, value interface{}) error { return nil }
func (d *MQTTDriver) Subscribe(address string, callback func(value interface{})) error {
	return nil
}
func (d *MQTTDriver) Unsubscribe(address string) error              { return nil }
func (d *MQTTDriver) IsConnected() bool                             { return d.connected }

type HTTPDriver struct {
	connected bool
}

func (d *HTTPDriver) Connect(config map[string]interface{}) error    { d.connected = true; return nil }
func (d *HTTPDriver) Disconnect() error                             { d.connected = false; return nil }
func (d *HTTPDriver) Read(address string, length int) (interface{}, error) { return "{}", nil }
func (d *HTTPDriver) Write(address string, value interface{}) error { return nil }
func (d *HTTPDriver) Subscribe(address string, callback func(value interface{})) error {
	return nil
}
func (d *HTTPDriver) Unsubscribe(address string) error              { return nil }
func (d *HTTPDriver) IsConnected() bool                             { return d.connected }
