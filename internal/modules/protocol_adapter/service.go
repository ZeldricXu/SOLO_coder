package protocol_adapter

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	"edgescheduler/internal/common/database"
	"edgescheduler/internal/common/eventbus"
	"edgescheduler/internal/common/logger"
	"edgescheduler/pkg/utils"
)

type ProtocolAdapterService interface {
	LoadDriver(ctx context.Context, req *DriverLoadRequest) (*ProtocolDriver, error)
	GetDriver(ctx context.Context, driverID string) (*ProtocolDriver, error)
	ListDrivers(ctx context.Context, protocol ProtocolType, offset, limit int) ([]ProtocolDriver, int64, error)
	UnloadDriver(ctx context.Context, driverID string) error

	CreateDeviceConfig(ctx context.Context, req *DeviceConfigRequest) (*DeviceProtocolConfig, error)
	GetDeviceConfig(ctx context.Context, deviceID string) (*DeviceProtocolConfig, error)
	ListDeviceConfigs(ctx context.Context, offset, limit int) ([]DeviceProtocolConfig, int64, error)
	UpdateDeviceConfig(ctx context.Context, configID string, updates map[string]interface{}) error
	DeleteDeviceConfig(ctx context.Context, configID string) error

	StartConnection(ctx context.Context, configID string) error
	StopConnection(ctx context.Context, configID string) error

	CreateForwardRule(ctx context.Context, rule *ForwardRule) (*ForwardRule, error)
	ListForwardRules(ctx context.Context, offset, limit int) ([]ForwardRule, int64, error)
	DeleteForwardRule(ctx context.Context, ruleID string) error

	NormalizeData(ctx context.Context, data *ProtocolData) (map[string]interface{}, error)
	ForwardData(ctx context.Context, data *ProtocolData) error

	StartAdapter(ctx context.Context, workerCount int)
}

type protocolAdapterServiceImpl struct {
	db         *gorm.DB
	eventBus   eventbus.EventBus
	driverMgr  *DriverManager
	dataCh     chan *ProtocolData
	configs    map[string]*DeviceProtocolConfig
	configsMu  sync.RWMutex
}

type DriverManager struct {
	drivers map[string]*ProtocolDriver
	mu      sync.RWMutex
}

func NewProtocolAdapterService() ProtocolAdapterService {
	return &protocolAdapterServiceImpl{
		db:        database.GetDB(),
		eventBus:  eventbus.GetEventBus(),
		driverMgr: &DriverManager{drivers: make(map[string]*ProtocolDriver)},
		dataCh:    make(chan *ProtocolData, 10000),
		configs:   make(map[string]*DeviceProtocolConfig),
	}
}

func (s *protocolAdapterServiceImpl) LoadDriver(ctx context.Context, req *DriverLoadRequest) (*ProtocolDriver, error) {
	logger.Info("Loading protocol driver",
		zap.String("name", req.Name),
		zap.String("protocol", string(req.Protocol)),
	)

	driver := &ProtocolDriver{
		DriverID: utils.GenerateID("drv"),
		Name:     req.Name,
		Protocol: req.Protocol,
		Version:  req.Version,
		Status:   DriverStatusLoaded,
		Config:   req.Config,
		Enabled:  true,
	}

	now := time.Now().UTC()
	driver.LoadedAt = &now

	if err := s.db.Create(driver).Error; err != nil {
		return nil, fmt.Errorf("failed to load driver: %w", err)
	}

	s.driverMgr.mu.Lock()
	s.driverMgr.drivers[driver.DriverID] = driver
	s.driverMgr.mu.Unlock()

	logger.Info("Protocol driver loaded successfully",
		zap.String("driver_id", driver.DriverID),
	)

	return driver, nil
}

func (s *protocolAdapterServiceImpl) GetDriver(ctx context.Context, driverID string) (*ProtocolDriver, error) {
	var driver ProtocolDriver
	if err := s.db.Where("driver_id = ?", driverID).First(&driver).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("driver not found")
		}
		return nil, err
	}
	return &driver, nil
}

func (s *protocolAdapterServiceImpl) ListDrivers(ctx context.Context, protocol ProtocolType, offset, limit int) ([]ProtocolDriver, int64, error) {
	var drivers []ProtocolDriver
	var total int64

	query := s.db.Model(&ProtocolDriver{})
	if protocol != "" {
		query = query.Where("protocol = ?", protocol)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Offset(offset).Limit(limit).Find(&drivers).Error; err != nil {
		return nil, 0, err
	}

	return drivers, total, nil
}

func (s *protocolAdapterServiceImpl) UnloadDriver(ctx context.Context, driverID string) error {
	result := s.db.Where("driver_id = ?", driverID).Delete(&ProtocolDriver{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return errors.New("driver not found")
	}

	s.driverMgr.mu.Lock()
	delete(s.driverMgr.drivers, driverID)
	s.driverMgr.mu.Unlock()

	return nil
}

func (s *protocolAdapterServiceImpl) CreateDeviceConfig(ctx context.Context, req *DeviceConfigRequest) (*DeviceProtocolConfig, error) {
	var driver ProtocolDriver
	if err := s.db.Where("driver_id = ?", req.DriverID).First(&driver).Error; err != nil {
		return nil, errors.New("driver not found")
	}

	config := &DeviceProtocolConfig{
		ConfigID:         utils.GenerateID("pdc"),
		DeviceID:         req.DeviceID,
		DriverID:         req.DriverID,
		Protocol:         driver.Protocol,
		Endpoint:         req.Endpoint,
		Parameters:       req.Parameters,
		PollInterval:     req.PollInterval,
		ConnectionStatus: ConnectionStatusDisconnected,
		Enabled:          true,
	}

	if config.PollInterval == 0 {
		config.PollInterval = 5000
	}

	if err := s.db.Create(config).Error; err != nil {
		return nil, fmt.Errorf("failed to create device config: %w", err)
	}

	s.configsMu.Lock()
	s.configs[config.ConfigID] = config
	s.configsMu.Unlock()

	logger.Info("Device protocol config created",
		zap.String("config_id", config.ConfigID),
		zap.String("device_id", config.DeviceID),
	)

	return config, nil
}

func (s *protocolAdapterServiceImpl) GetDeviceConfig(ctx context.Context, deviceID string) (*DeviceProtocolConfig, error) {
	var config DeviceProtocolConfig
	if err := s.db.Where("device_id = ?", deviceID).First(&config).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("device config not found")
		}
		return nil, err
	}
	return &config, nil
}

func (s *protocolAdapterServiceImpl) ListDeviceConfigs(ctx context.Context, offset, limit int) ([]DeviceProtocolConfig, int64, error) {
	var configs []DeviceProtocolConfig
	var total int64

	if err := s.db.Model(&DeviceProtocolConfig{}).Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := s.db.Offset(offset).Limit(limit).Find(&configs).Error; err != nil {
		return nil, 0, err
	}

	return configs, total, nil
}

func (s *protocolAdapterServiceImpl) UpdateDeviceConfig(ctx context.Context, configID string, updates map[string]interface{}) error {
	result := s.db.Model(&DeviceProtocolConfig{}).Where("config_id = ?", configID).Updates(updates)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return errors.New("device config not found")
	}
	return nil
}

func (s *protocolAdapterServiceImpl) DeleteDeviceConfig(ctx context.Context, configID string) error {
	result := s.db.Where("config_id = ?", configID).Delete(&DeviceProtocolConfig{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return errors.New("device config not found")
	}

	s.configsMu.Lock()
	delete(s.configs, configID)
	s.configsMu.Unlock()

	return nil
}

func (s *protocolAdapterServiceImpl) StartConnection(ctx context.Context, configID string) error {
	var config DeviceProtocolConfig
	if err := s.db.Where("config_id = ?", configID).First(&config).Error; err != nil {
		return errors.New("config not found")
	}

	config.ConnectionStatus = ConnectionStatusConnecting
	s.db.Save(&config)

	go s.simulateConnection(ctx, &config)

	return nil
}

func (s *protocolAdapterServiceImpl) simulateConnection(ctx context.Context, config *DeviceProtocolConfig) {
	time.Sleep(500 * time.Millisecond)

	now := time.Now().UTC()
	config.ConnectionStatus = ConnectionStatusConnected
	config.LastConnected = &now
	s.db.Save(config)

	logger.Info("Protocol connection established",
		zap.String("config_id", config.ConfigID),
		zap.String("device_id", config.DeviceID),
		zap.String("protocol", string(config.Protocol)),
	)

	go s.startPolling(ctx, config)
}

func (s *protocolAdapterServiceImpl) startPolling(ctx context.Context, config *DeviceProtocolConfig) {
	ticker := time.NewTicker(time.Duration(config.PollInterval) * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			config.ConnectionStatus = ConnectionStatusDisconnected
			now := time.Now().UTC()
			config.LastDisconnected = &now
			s.db.Save(config)
			return
		case <-ticker.C:
			if config.ConnectionStatus != ConnectionStatusConnected {
				continue
			}

			data := &ProtocolData{
				ID:        utils.GenerateID("pd"),
				DeviceID:  config.DeviceID,
				Protocol:  config.Protocol,
				Timestamp: time.Now().UTC(),
				RawData: map[string]interface{}{
					"register_1": 12345,
					"register_2": 67890,
					"timestamp":  time.Now().Unix(),
				},
			}

			normalized, _ := s.NormalizeData(ctx, data)
			data.NormalizedData = normalized

			s.dataCh <- data

			s.eventBus.Publish(ctx, eventbus.EventProtocolDataReceived, map[string]interface{}{
				"device_id": data.DeviceID,
				"protocol":  data.Protocol,
			}, "protocol_adapter")
		}
	}
}

func (s *protocolAdapterServiceImpl) StopConnection(ctx context.Context, configID string) error {
	var config DeviceProtocolConfig
	if err := s.db.Where("config_id = ?", configID).First(&config).Error; err != nil {
		return errors.New("config not found")
	}

	now := time.Now().UTC()
	config.ConnectionStatus = ConnectionStatusDisconnected
	config.LastDisconnected = &now
	s.db.Save(&config)

	logger.Info("Protocol connection stopped",
		zap.String("config_id", configID),
	)

	return nil
}

func (s *protocolAdapterServiceImpl) CreateForwardRule(ctx context.Context, rule *ForwardRule) (*ForwardRule, error) {
	rule.RuleID = utils.GenerateID("fwd")

	if err := s.db.Create(rule).Error; err != nil {
		return nil, fmt.Errorf("failed to create forward rule: %w", err)
	}

	logger.Info("Forward rule created",
		zap.String("rule_id", rule.RuleID),
		zap.String("name", rule.Name),
	)

	return rule, nil
}

func (s *protocolAdapterServiceImpl) ListForwardRules(ctx context.Context, offset, limit int) ([]ForwardRule, int64, error) {
	var rules []ForwardRule
	var total int64

	if err := s.db.Model(&ForwardRule{}).Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := s.db.Offset(offset).Limit(limit).Find(&rules).Error; err != nil {
		return nil, 0, err
	}

	return rules, total, nil
}

func (s *protocolAdapterServiceImpl) DeleteForwardRule(ctx context.Context, ruleID string) error {
	result := s.db.Where("rule_id = ?", ruleID).Delete(&ForwardRule{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return errors.New("forward rule not found")
	}
	return nil
}

func (s *protocolAdapterServiceImpl) NormalizeData(ctx context.Context, data *ProtocolData) (map[string]interface{}, error) {
	normalized := make(map[string]interface{})

	switch data.Protocol {
	case ProtocolModbus:
		if rawMap, ok := data.RawData.(map[string]interface{}); ok {
			for k, v := range rawMap {
				normalized[k] = v
			}
		}
	case ProtocolMQTT:
		normalized["topic"] = "data"
		normalized["payload"] = data.RawData
	default:
		normalized["value"] = data.RawData
	}

	normalized["device_id"] = data.DeviceID
	normalized["protocol"] = data.Protocol
	normalized["timestamp"] = data.Timestamp

	return normalized, nil
}

func (s *protocolAdapterServiceImpl) ForwardData(ctx context.Context, data *ProtocolData) error {
	s.eventBus.Publish(ctx, eventbus.EventProtocolDataSent, map[string]interface{}{
		"device_id": data.DeviceID,
		"protocol":  data.Protocol,
		"endpoint":  "cloud",
	}, "protocol_adapter")

	return nil
}

func (s *protocolAdapterServiceImpl) StartAdapter(ctx context.Context, workerCount int) {
	logger.Info("Starting protocol adapter workers", zap.Int("count", workerCount))

	s.loadConfigs()

	for i := 0; i < workerCount; i++ {
		go func(workerID int) {
			for {
				select {
				case <-ctx.Done():
					return
				case data := <-s.dataCh:
					s.ForwardData(ctx, data)
				}
			}
		}(i)
	}
}

func (s *protocolAdapterServiceImpl) loadConfigs() {
	var configs []DeviceProtocolConfig
	s.db.Where("enabled = ?", true).Find(&configs)

	s.configsMu.Lock()
	defer s.configsMu.Unlock()

	for i := range configs {
		s.configs[configs[i].ConfigID] = &configs[i]
	}

	logger.Info("Loaded device protocol configs", zap.Int("count", len(configs)))
}
