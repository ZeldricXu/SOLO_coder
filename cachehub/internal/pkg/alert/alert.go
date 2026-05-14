package alert

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/cachehub/internal/pkg/cache_manager"
	"github.com/cachehub/internal/pkg/models"
	"github.com/cachehub/internal/pkg/monitoring"
	"github.com/sirupsen/logrus"
)

type AlertEvent struct {
	AlertID    string
	CacheID    string
	AlertType  string
	Threshold  float64
	CurrentValue float64
	Message    string
	Timestamp  time.Time
	Channels   []string
}

type AlertManager struct {
	cm        *cache_manager.CacheManager
	mm        *monitoring.MonitoringManager
	logger    *logrus.Logger
	configs   map[string]*models.AlertConfig
	events    map[string][]*AlertEvent
	handlers  map[string]func(*AlertEvent)
	mu        sync.RWMutex
	stopCh    chan struct{}
}

func NewAlertManager(cm *cache_manager.CacheManager, mm *monitoring.MonitoringManager, logger *logrus.Logger) *AlertManager {
	am := &AlertManager{
		cm:       cm,
		mm:       mm,
		logger:   logger,
		configs:  make(map[string]*models.AlertConfig),
		events:   make(map[string][]*AlertEvent),
		handlers: make(map[string]func(*AlertEvent)),
		stopCh:   make(chan struct{}),
	}

	am.registerDefaultHandlers()
	return am
}

func (am *AlertManager) registerDefaultHandlers() {
	am.handlers["email"] = func(event *AlertEvent) {
		am.logger.Infof("[EMAIL ALERT] %s: %s", event.CacheID, event.Message)
	}
	am.handlers["slack"] = func(event *AlertEvent) {
		am.logger.Infof("[SLACK ALERT] %s: %s", event.CacheID, event.Message)
	}
	am.handlers["webhook"] = func(event *AlertEvent) {
		am.logger.Infof("[WEBHOOK ALERT] %s: %s", event.CacheID, event.Message)
	}
}

func (am *AlertManager) Start(ctx context.Context, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	am.logger.Infof("Alert manager started, interval: %v", interval)

	for {
		select {
		case <-ctx.Done():
			am.logger.Info("Alert manager stopped")
			return
		case <-am.stopCh:
			am.logger.Info("Alert manager stopped via stop channel")
			return
		case <-ticker.C:
			am.CheckAlerts()
		}
	}
}

func (am *AlertManager) Stop() {
	close(am.stopCh)
}

func (am *AlertManager) SetConfig(config *models.AlertConfig) error {
	if config.AlertID == "" {
		return errors.New("alert_id is required")
	}
	if config.CacheID == "" {
		return errors.New("cache_id is required")
	}
	if config.Threshold <= 0 {
		return errors.New("threshold must be positive")
	}

	_, err := am.cm.GetInstance(config.CacheID)
	if err != nil {
		return err
	}

	am.mu.Lock()
	defer am.mu.Unlock()

	am.configs[config.AlertID] = config
	am.logger.Infof("Alert config set: %s for cache: %s", config.AlertID, config.CacheID)
	return nil
}

func (am *AlertManager) GetConfig(alertID string) (*models.AlertConfig, error) {
	am.mu.RLock()
	defer am.mu.RUnlock()

	config, exists := am.configs[alertID]
	if !exists {
		return nil, errors.New("alert config not found")
	}
	return config, nil
}

func (am *AlertManager) RemoveConfig(alertID string) error {
	am.mu.Lock()
	defer am.mu.Unlock()

	if _, exists := am.configs[alertID]; !exists {
		return errors.New("alert config not found")
	}

	delete(am.configs, alertID)
	am.logger.Infof("Alert config removed: %s", alertID)
	return nil
}

func (am *AlertManager) CheckAlerts() {
	am.mu.RLock()
	configs := make([]*models.AlertConfig, 0, len(am.configs))
	for _, config := range am.configs {
		if config.Enabled {
			configs = append(configs, config)
		}
	}
	am.mu.RUnlock()

	for _, config := range configs {
		am.checkAlert(config)
	}
}

func (am *AlertManager) checkAlert(config *models.AlertConfig) {
	var currentValue float64
	var message string

	switch config.AlertType {
	case "capacity_warning", "capacity_critical":
		currentValue = am.mm.GetCapacityUsage(config.CacheID)
		message = fmt.Sprintf("Cache %s capacity usage is %.2f%%, threshold: %.2f%%",
			config.CacheID, currentValue, config.Threshold)
	case "hit_rate_warning":
		currentValue = am.mm.GetHitRate(config.CacheID)
		message = fmt.Sprintf("Cache %s hit rate is %.2f%%, threshold: %.2f%%",
			config.CacheID, currentValue, config.Threshold)
	default:
		return
	}

	shouldTrigger := false
	if config.AlertType == "hit_rate_warning" {
		shouldTrigger = currentValue < config.Threshold
	} else {
		shouldTrigger = currentValue > config.Threshold
	}

	if shouldTrigger {
		now := time.Now()
		if config.LastTriggered != nil && now.Sub(*config.LastTriggered) < 5*time.Minute {
			return
		}

		event := &AlertEvent{
			AlertID:      config.AlertID,
			CacheID:      config.CacheID,
			AlertType:    config.AlertType,
			Threshold:    config.Threshold,
			CurrentValue: currentValue,
			Message:      message,
			Timestamp:    now,
			Channels:     config.NotifyChannels,
		}

		am.triggerAlert(event, config)
	}
}

func (am *AlertManager) triggerAlert(event *AlertEvent, config *models.AlertConfig) {
	am.logger.Warnf("ALERT TRIGGERED: %s", event.Message)

	am.mu.Lock()
	events, exists := am.events[config.CacheID]
	if !exists {
		events = make([]*AlertEvent, 0)
	}
	events = append(events, event)
	if len(events) > 100 {
		events = events[len(events)-100:]
	}
	am.events[config.CacheID] = events

	now := event.Timestamp
	config.LastTriggered = &now
	am.mu.Unlock()

	for _, channel := range event.Channels {
		if handler, exists := am.handlers[channel]; exists {
			handler(event)
		}
	}
}

func (am *AlertManager) RegisterHandler(channel string, handler func(*AlertEvent)) {
	am.mu.Lock()
	defer am.mu.Unlock()
	am.handlers[channel] = handler
}

func (am *AlertManager) GetAlertEvents(cacheID string, limit int) ([]*AlertEvent, error) {
	am.mu.RLock()
	defer am.mu.RUnlock()

	events, exists := am.events[cacheID]
	if !exists {
		return []*AlertEvent{}, nil
	}

	if limit <= 0 || limit > len(events) {
		limit = len(events)
	}

	result := make([]*AlertEvent, limit)
	copy(result, events[len(events)-limit:])
	return result, nil
}

func (am *AlertManager) TriggerManualAlert(cacheID, alertType string, message string) {
	event := &AlertEvent{
		AlertID:    fmt.Sprintf("manual_%s_%d", cacheID, time.Now().Unix()),
		CacheID:    cacheID,
		AlertType:  alertType,
		Message:    message,
		Timestamp:  time.Now(),
		Channels:   []string{"email"},
	}

	am.mu.Lock()
	events, exists := am.events[cacheID]
	if !exists {
		events = make([]*AlertEvent, 0)
	}
	events = append(events, event)
	am.events[cacheID] = events
	am.mu.Unlock()

	am.logger.Warnf("MANUAL ALERT: %s", message)
}
