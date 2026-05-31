package sla

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/datamigration/platform/internal/logger"
	"github.com/datamigration/platform/pkg/models"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type Monitor struct {
	db        *gorm.DB
	tickers   map[string]*time.Ticker
	stopChans map[string]chan struct{}
	mu        sync.RWMutex
	notifyCh  chan Notification
}

type Notification struct {
	InstanceID  string
	Type        string
	Message     string
	SLAConfigID string
}

func NewMonitor(db *gorm.DB, notifyBuffer int) *Monitor {
	if notifyBuffer <= 0 {
		notifyBuffer = 100
	}
	return &Monitor{
		db:        db,
		tickers:   make(map[string]*time.Ticker),
		stopChans: make(map[string]chan struct{}),
		notifyCh:  make(chan Notification, notifyBuffer),
	}
}

func (m *Monitor) Notifications() <-chan Notification {
	return m.notifyCh
}

func (m *Monitor) CreateSLAConfig(ctx context.Context, tenantID, name, workflowType string, responseTime, resolutionTime, escalationTime int) (*models.SLAConfiguration, error) {
	cfg := &models.SLAConfiguration{
		ID:             fmt.Sprintf("sla_%s", uuid.New().String()[:8]),
		Name:           name,
		WorkflowType:   workflowType,
		ResponseTime:   responseTime,
		ResolutionTime: resolutionTime,
		EscalationTime: escalationTime,
		Enabled:        true,
		CreatedAt:      time.Now(),
		UpdatedAt:      time.Now(),
		TenantID:       tenantID,
	}

	if err := m.db.WithContext(ctx).Create(cfg).Error; err != nil {
		logger.Error("failed to create SLA config", zap.Error(err))
		return nil, err
	}

	return cfg, nil
}

func (m *Monitor) StartTracking(ctx context.Context, tenantID, instanceID, slaConfigID string) (*models.SLATracking, error) {
	var cfg models.SLAConfiguration
	if err := m.db.WithContext(ctx).Where("id = ?", slaConfigID).First(&cfg).Error; err != nil {
		return nil, err
	}

	now := time.Now()
	tracking := &models.SLATracking{
		ID:            fmt.Sprintf("slat_%s", uuid.New().String()[:8]),
		InstanceID:    instanceID,
		SLAConfigID:   slaConfigID,
		ResponseDue:   now.Add(time.Duration(cfg.ResponseTime) * time.Minute),
		ResolutionDue: now.Add(time.Duration(cfg.ResolutionTime) * time.Minute),
		EscalationDue: now.Add(time.Duration(cfg.EscalationTime) * time.Minute),
		Status:        "active",
		BreachCount:   0,
		CreatedAt:     now,
		UpdatedAt:     now,
		TenantID:      tenantID,
	}

	if err := m.db.WithContext(ctx).Create(tracking).Error; err != nil {
		logger.Error("failed to create SLA tracking", zap.Error(err))
		return nil, err
	}

	m.startMonitoring(tracking.ID)
	return tracking, nil
}

func (m *Monitor) startMonitoring(trackingID string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.tickers[trackingID]; exists {
		return
	}

	ticker := time.NewTicker(1 * time.Minute)
	stopChan := make(chan struct{})
	m.tickers[trackingID] = ticker
	m.stopChans[trackingID] = stopChan

	go func() {
		for {
			select {
			case <-ticker.C:
				m.checkAndNotify(trackingID)
			case <-stopChan:
				ticker.Stop()
				return
			}
		}
	}()
}

func (m *Monitor) StopMonitoring(trackingID string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if stopChan, exists := m.stopChans[trackingID]; exists {
		close(stopChan)
		delete(m.stopChans, trackingID)
		delete(m.tickers, trackingID)
	}
}

func (m *Monitor) checkAndNotify(trackingID string) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	var tracking models.SLATracking
	if err := m.db.WithContext(ctx).Where("id = ?", trackingID).First(&tracking).Error; err != nil {
		logger.Error("failed to fetch SLA tracking", zap.Error(err))
		return
	}

	if tracking.Status == "completed" || tracking.Status == "resolved" {
		m.StopMonitoring(trackingID)
		return
	}

	now := time.Now()
	var notifications []Notification

	if tracking.ResponseAt == nil && now.After(tracking.ResponseDue) {
		notifications = append(notifications, Notification{
			InstanceID:  tracking.InstanceID,
			Type:        "response_breach",
			Message:     fmt.Sprintf("Response SLA breached for instance %s", tracking.InstanceID),
			SLAConfigID: tracking.SLAConfigID,
		})
	}

	if tracking.EscalatedAt == nil && now.After(tracking.EscalationDue) {
		notifications = append(notifications, Notification{
			InstanceID:  tracking.InstanceID,
			Type:        "escalation",
			Message:     fmt.Sprintf("Escalation triggered for instance %s", tracking.InstanceID),
			SLAConfigID: tracking.SLAConfigID,
		})

		nowTime := now
		m.db.WithContext(ctx).Model(&tracking).Updates(map[string]interface{}{
			"escalated_at": &nowTime,
			"updated_at":   nowTime,
		})
	}

	if tracking.ResolutionAt == nil && now.After(tracking.ResolutionDue) {
		notifications = append(notifications, Notification{
			InstanceID:  tracking.InstanceID,
			Type:        "resolution_breach",
			Message:     fmt.Sprintf("Resolution SLA breached for instance %s", tracking.InstanceID),
			SLAConfigID: tracking.SLAConfigID,
		})
	}

	if len(notifications) > 0 {
		m.db.WithContext(ctx).Model(&tracking).Updates(map[string]interface{}{
			"breach_count": tracking.BreachCount + len(notifications),
			"updated_at":   now,
		})

		for _, n := range notifications {
			select {
			case m.notifyCh <- n:
				logger.Info("SLA notification sent",
					zap.String("instance_id", n.InstanceID),
					zap.String("type", n.Type),
				)
			default:
				logger.Warn("notification buffer full, dropping",
					zap.String("instance_id", n.InstanceID),
				)
			}
		}
	}
}

func (m *Monitor) RecordResponse(ctx context.Context, trackingID string) error {
	now := time.Now()
	return m.db.WithContext(ctx).Model(&models.SLATracking{}).
		Where("id = ?", trackingID).
		Updates(map[string]interface{}{
			"response_at": &now,
			"updated_at":  now,
		}).Error
}

func (m *Monitor) RecordResolution(ctx context.Context, trackingID string) error {
	now := time.Now()
	return m.db.WithContext(ctx).Model(&models.SLATracking{}).
		Where("id = ?", trackingID).
		Updates(map[string]interface{}{
			"resolution_at": &now,
			"status":        "resolved",
			"updated_at":    now,
		}).Error
}

func (m *Monitor) GetRemainingTime(ctx context.Context, trackingID string) (map[string]interface{}, error) {
	var tracking models.SLATracking
	if err := m.db.WithContext(ctx).Where("id = ?", trackingID).First(&tracking).Error; err != nil {
		return nil, err
	}

	now := time.Now()
	result := make(map[string]interface{})

	if tracking.ResponseAt == nil {
		remaining := tracking.ResponseDue.Sub(now)
		result["response_remaining_ms"] = remaining.Milliseconds()
		result["response_breached"] = remaining < 0
	} else {
		result["response_completed_at"] = tracking.ResponseAt
	}

	if tracking.ResolutionAt == nil {
		remaining := tracking.ResolutionDue.Sub(now)
		result["resolution_remaining_ms"] = remaining.Milliseconds()
		result["resolution_breached"] = remaining < 0
	} else {
		result["resolution_completed_at"] = tracking.ResolutionAt
	}

	if tracking.EscalatedAt == nil {
		remaining := tracking.EscalationDue.Sub(now)
		result["escalation_remaining_ms"] = remaining.Milliseconds()
		result["escalation_due"] = remaining < 0
	} else {
		result["escalated_at"] = tracking.EscalatedAt
	}

	result["breach_count"] = tracking.BreachCount
	result["status"] = tracking.Status

	return result, nil
}

func (m *Monitor) GetActiveTrackings(ctx context.Context, tenantID string) ([]*models.SLATracking, error) {
	var trackings []*models.SLATracking
	if err := m.db.WithContext(ctx).
		Where("tenant_id = ? AND status = ?", tenantID, "active").
		Order("created_at DESC").
		Find(&trackings).Error; err != nil {
		return nil, err
	}
	return trackings, nil
}

func (m *Monitor) GetBreachSummary(ctx context.Context, tenantID string, start, end time.Time) (map[string]int, error) {
	var trackings []*models.SLATracking
	if err := m.db.WithContext(ctx).
		Where("tenant_id = ? AND created_at BETWEEN ? AND ?", tenantID, start, end).
		Find(&trackings).Error; err != nil {
		return nil, err
	}

	summary := map[string]int{
		"response_breaches":   0,
		"resolution_breaches": 0,
		"escalations":         0,
		"total":               len(trackings),
	}

	for _, t := range trackings {
		if t.ResponseAt == nil && time.Now().After(t.ResponseDue) {
			summary["response_breaches"]++
		}
		if t.ResolutionAt == nil && time.Now().After(t.ResolutionDue) {
			summary["resolution_breaches"]++
		}
		if t.EscalatedAt != nil {
			summary["escalations"]++
		}
	}

	return summary, nil
}

func (m *Monitor) StopAll() {
	m.mu.Lock()
	defer m.mu.Unlock()

	for id, stopChan := range m.stopChans {
		close(stopChan)
		if ticker, ok := m.tickers[id]; ok {
			ticker.Stop()
		}
	}

	m.tickers = make(map[string]*time.Ticker)
	m.stopChans = make(map[string]chan struct{})
}
