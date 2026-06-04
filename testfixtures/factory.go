package testfixtures

import (
	"fmt"
	"time"

	"log-pipeline/pkg/models"
)

func NewLogEntry(overrides ...func(*models.LogEntry)) *models.LogEntry {
	entry := &models.LogEntry{
		ID:        "test-id-" + fmt.Sprintf("%d", time.Now().UnixNano()),
		Timestamp: time.Date(2025, 6, 2, 12, 0, 0, 0, time.UTC),
		Source:    "tcp",
		Host:      "127.0.0.1",
		Level:     "INFO",
		Message:   "test message",
		Fields:    map[string]string{},
		Raw:       "test message",
	}
	for _, o := range overrides {
		o(entry)
	}
	return entry
}

func NewErrorLogEntry(overrides ...func(*models.LogEntry)) *models.LogEntry {
	return NewLogEntry(func(e *models.LogEntry) {
		e.Level = "ERROR"
		e.Message = "something went wrong"
	})
}

func NewWarnLogEntry(overrides ...func(*models.LogEntry)) *models.LogEntry {
	return NewLogEntry(func(e *models.LogEntry) {
		e.Level = "WARN"
		e.Message = "warning condition"
	})
}

func New401LogEntry(ip string, overrides ...func(*models.LogEntry)) *models.LogEntry {
	opts := []func(*models.LogEntry){
		func(e *models.LogEntry) {
			e.Level = "WARN"
			e.Message = fmt.Sprintf("401 Unauthorized from %s", ip)
			e.Fields = map[string]string{"client_ip": ip}
		},
	}
	opts = append(opts, overrides...)
	return NewLogEntry(opts...)
}

func NewWindowAggregate(overrides ...func(*models.WindowAggregate)) *models.WindowAggregate {
	agg := &models.WindowAggregate{
		WindowID:    "test-window-" + fmt.Sprintf("%d", time.Now().UnixNano()),
		WindowStart: time.Date(2025, 6, 2, 12, 0, 0, 0, time.UTC),
		WindowEnd:   time.Date(2025, 6, 2, 12, 1, 0, 0, time.UTC),
		WindowType:  "sliding",
		Key:         "127.0.0.1",
		Count:       100,
		LevelCounts: map[string]int64{"INFO": 80, "ERROR": 10, "WARN": 10},
		Fields:      map[string]interface{}{},
	}
	for _, o := range overrides {
		o(agg)
	}
	return agg
}

func NewSteadyWindowAggregate(overrides ...func(*models.WindowAggregate)) *models.WindowAggregate {
	return NewWindowAggregate(func(a *models.WindowAggregate) {
		a.Count = 100
		a.LevelCounts = map[string]int64{"INFO": 95, "ERROR": 3, "WARN": 2}
	})
}

func NewSpikeWindowAggregate(overrides ...func(*models.WindowAggregate)) *models.WindowAggregate {
	return NewWindowAggregate(func(a *models.WindowAggregate) {
		a.Count = 5000
		a.LevelCounts = map[string]int64{"INFO": 100, "ERROR": 4800, "WARN": 100}
	})
}

func NewAlertEvent(overrides ...func(*models.AlertEvent)) *models.AlertEvent {
	alert := &models.AlertEvent{
		ID:          "test-alert-" + fmt.Sprintf("%d", time.Now().UnixNano()),
		Timestamp:   time.Date(2025, 6, 2, 12, 0, 0, 0, time.UTC),
		AlertType:   "auth_failure_401",
		Severity:    "warning",
		Title:       "Multiple 401 errors from IP 10.0.0.1",
		Description: "Detected 10 401 errors within 1 minute from IP 10.0.0.1",
		SourceIP:    "10.0.0.1",
		Count:       10,
		Details:     map[string]interface{}{"threshold": 5},
	}
	for _, o := range overrides {
		o(alert)
	}
	return alert
}

func NewCriticalAlertEvent(overrides ...func(*models.AlertEvent)) *models.AlertEvent {
	return NewAlertEvent(func(a *models.AlertEvent) {
		a.Severity = "critical"
		a.AlertType = "anomaly_detected"
		a.Title = "Anomaly detected in log patterns"
	})
}

func GenerateBatchLogs(count int, level string) []*models.LogEntry {
	logs := make([]*models.LogEntry, count)
	for i := 0; i < count; i++ {
		logs[i] = NewLogEntry(func(e *models.LogEntry) {
			e.Level = level
			e.Message = fmt.Sprintf("batch log entry %d", i)
		})
	}
	return logs
}

func GenerateMixedLevelLogs(total int, errorPct float64) []*models.LogEntry {
	logs := make([]*models.LogEntry, total)
	errorCount := int(float64(total) * errorPct)
	for i := 0; i < total; i++ {
		if i < errorCount {
			logs[i] = NewErrorLogEntry()
		} else {
			logs[i] = NewLogEntry()
		}
	}
	return logs
}
