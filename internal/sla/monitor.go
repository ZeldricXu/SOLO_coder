package sla

import (
	"fmt"
	"sync"
	"time"
)

type SLAStatus string

const (
	SLAStatusActive    SLAStatus = "active"
	SLAStatusWarning   SLAStatus = "warning"
	SLAStatusCritical  SLAStatus = "critical"
	SLAStatusBreached  SLAStatus = "breached"
	SLAStatusCompleted SLAStatus = "completed"
)

type EscalationAction struct {
	Level       int               `json:"level"`
	Threshold   float64           `json:"threshold"`
	NotifyUsers []string          `json:"notify_users"`
	Action      string            `json:"action"`
	ReassignTo  string            `json:"reassign_to,omitempty"`
	Params      map[string]string `json:"params,omitempty"`
}

type SLAPolicy struct {
	ID                string             `json:"id"`
	Name              string             `json:"name"`
	Category          string             `json:"category"`
	TargetDuration    time.Duration      `json:"target_duration"`
	WarningThreshold  float64            `json:"warning_threshold"`
	CriticalThreshold float64            `json:"critical_threshold"`
	EscalationActions []EscalationAction `json:"escalation_actions"`
	Priority          string             `json:"priority"`
	TenantID          string             `json:"tenant_id"`
}

type SLATracker struct {
	ID            string    `json:"id"`
	EntityType    string    `json:"entity_type"`
	EntityID      string    `json:"entity_id"`
	PolicyID      string    `json:"policy_id"`
	StartTime     time.Time `json:"start_time"`
	Deadline      time.Time `json:"deadline"`
	Status        SLAStatus `json:"status"`
	CurrentLevel  int       `json:"current_escalation_level"`
	TenantID      string    `json:"tenant_id"`
	lastChecked   time.Time
}

type Notification struct {
	ID        string    `json:"id"`
	TrackerID string    `json:"tracker_id"`
	Level     int       `json:"level"`
	Message   string    `json:"message"`
	Users     []string  `json:"users"`
	SentAt    time.Time `json:"sent_at"`
	Type      string    `json:"type"`
}

type SLAMonitor struct {
	mu          sync.RWMutex
	policies    map[string]*SLAPolicy
	trackers    map[string]*SLATracker
	notifications []Notification
	notifiers   map[string]NotificationHandler
}

type NotificationHandler func(notification Notification)

func NewSLAMonitor() *SLAMonitor {
	return &SLAMonitor{
		policies:    make(map[string]*SLAPolicy),
		trackers:    make(map[string]*SLATracker),
		notifiers:   make(map[string]NotificationHandler),
	}
}

func (sm *SLAMonitor) RegisterNotifier(channel string, handler NotificationHandler) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.notifiers[channel] = handler
}

func (sm *SLAMonitor) AddPolicy(policy SLAPolicy) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.policies[policy.ID] = &policy
}

func (sm *SLAMonitor) GetPolicy(id string) (*SLAPolicy, bool) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	p, ok := sm.policies[id]
	return p, ok
}

func (sm *SLAMonitor) StartTracking(id, entityType, entityID, policyID, tenantID string) (*SLATracker, error) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	policy, ok := sm.policies[policyID]
	if !ok {
		return nil, fmt.Errorf("SLA policy %s not found", policyID)
	}
	now := time.Now()
	tracker := &SLATracker{
		ID:           id,
		EntityType:   entityType,
		EntityID:     entityID,
		PolicyID:     policyID,
		StartTime:    now,
		Deadline:     now.Add(policy.TargetDuration),
		Status:       SLAStatusActive,
		CurrentLevel: 0,
		TenantID:     tenantID,
		lastChecked:  now,
	}
	sm.trackers[id] = tracker
	return tracker, nil
}

func (sm *SLAMonitor) StopTracking(id string) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	tracker, ok := sm.trackers[id]
	if !ok {
		return fmt.Errorf("tracker %s not found", id)
	}
	tracker.Status = SLAStatusCompleted
	return nil
}

func (sm *SLAMonitor) GetRemainingTime(id string) (time.Duration, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	tracker, ok := sm.trackers[id]
	if !ok {
		return 0, fmt.Errorf("tracker %s not found", id)
	}
	remaining := time.Until(tracker.Deadline)
	if remaining < 0 {
		return 0, nil
	}
	return remaining, nil
}

func (sm *SLAMonitor) GetElapsedTime(id string) (time.Duration, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	tracker, ok := sm.trackers[id]
	if !ok {
		return 0, fmt.Errorf("tracker %s not found", id)
	}
	return time.Since(tracker.StartTime), nil
}

func (sm *SLAMonitor) CheckAndEscalate() []Notification {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	now := time.Now()
	var newNotifications []Notification

	for _, tracker := range sm.trackers {
		if tracker.Status == SLAStatusCompleted || tracker.Status == SLAStatusBreached {
			continue
		}

		elapsed := now.Sub(tracker.StartTime)
		policy := sm.policies[tracker.PolicyID]
		if policy == nil {
			continue
		}

		totalDuration := policy.TargetDuration.Seconds()
		if totalDuration == 0 {
			continue
		}
		consumedPercent := elapsed.Seconds() / totalDuration * 100

		var newStatus SLAStatus
		switch {
		case consumedPercent >= 100:
			newStatus = SLAStatusBreached
		case consumedPercent >= policy.CriticalThreshold:
			newStatus = SLAStatusCritical
		case consumedPercent >= policy.WarningThreshold:
			newStatus = SLAStatusWarning
		default:
			newStatus = SLAStatusActive
		}

		if newStatus != tracker.Status {
			tracker.Status = newStatus
			tracker.lastChecked = now

			for _, action := range policy.EscalationActions {
				if consumedPercent >= action.Threshold && action.Level > tracker.CurrentLevel {
					tracker.CurrentLevel = action.Level
					notification := Notification{
						ID:        fmt.Sprintf("notif_%s_%d", tracker.ID, action.Level),
						TrackerID: tracker.ID,
						Level:     action.Level,
						Message:   fmt.Sprintf("SLA %s: %s escalation at %.1f%% consumed", tracker.ID, newStatus, consumedPercent),
						Users:     action.NotifyUsers,
						SentAt:    now,
						Type:      action.Action,
					}
					sm.notifications = append(sm.notifications, notification)
					newNotifications = append(newNotifications, notification)
					for _, handler := range sm.notifiers {
						handler(notification)
					}
				}
			}
		}
	}
	return newNotifications
}

func (sm *SLAMonitor) GetTracker(id string) (*SLATracker, bool) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	t, ok := sm.trackers[id]
	return t, ok
}

func (sm *SLAMonitor) GetTrackersByEntity(entityType, entityID string) []*SLATracker {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	var result []*SLATracker
	for _, t := range sm.trackers {
		if t.EntityType == entityType && t.EntityID == entityID {
			result = append(result, t)
		}
	}
	return result
}

func (sm *SLAMonitor) GetActiveBreaches(tenantID string) []*SLATracker {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	var result []*SLATracker
	for _, t := range sm.trackers {
		if t.Status == SLAStatusBreached && (tenantID == "" || t.TenantID == tenantID) {
			result = append(result, t)
		}
	}
	return result
}

func (t *SLATracker) Format() string {
	remaining := time.Until(t.Deadline)
	if remaining < 0 {
		remaining = 0
	}
	return fmt.Sprintf("SLA[%s] %s/%s | Status: %s | Remaining: %s | Escalation Level: %d",
		t.ID, t.EntityType, t.EntityID, t.Status, remaining.Round(time.Minute), t.CurrentLevel)
}
