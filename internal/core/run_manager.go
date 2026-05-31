package core

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/internal/contracts"
	"github.com/solocoder/task-scheduler/internal/database"
	"github.com/solocoder/task-scheduler/internal/events"
	"github.com/solocoder/task-scheduler/internal/logging"
	"github.com/solocoder/task-scheduler/internal/models"
)

type RunInstanceManager struct {
	db       *database.Database
	eventBus events.EventBus
}

func NewRunInstanceManager(db *database.Database, eventBus events.EventBus) *RunInstanceManager {
	return &RunInstanceManager{
		db:       db,
		eventBus: eventBus,
	}
}

func (m *RunInstanceManager) Create(ctx context.Context, req *contracts.ProcessRequest, config *models.ConfigDefinition) string {
	runID := "run_" + time.Now().Format("20060102150405")

	run := &models.RunInstance{
		RunID:     runID,
		EntityID:  req.EntityID,
		ConfigID:  config.ConfigID,
		Phase:     models.PhaseExecuting,
		Progress:  0,
		StartedAt: time.Now(),
		TraceID:   req.TraceID,
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}

	if err := m.db.DB.WithContext(ctx).Create(run).Error; err != nil {
		logging.Error(ctx, "Failed to create run instance", zap.Error(err), zap.String("run_id", runID))
	}

	event := events.NewEvent(events.EventTaskStarted, req.EntityID, map[string]interface{}{
		"run_id": runID,
	}, models.NewTraceContext(req.TraceID))
	_ = m.eventBus.Publish(ctx, event)

	return runID
}

func (m *RunInstanceManager) UpdatePhase(ctx context.Context, runID string, phase models.RunPhase, errorDetail string) {
	updates := map[string]interface{}{
		"phase":      phase,
		"updated_at": time.Now(),
	}

	if phase == models.PhaseCompleted {
		updates["progress"] = 1.0
		updates["completed_at"] = time.Now()
	}

	if errorDetail != "" {
		updates["error_detail"] = errorDetail
	}

	if err := m.db.DB.WithContext(ctx).
		Model(&models.RunInstance{}).
		Where("run_id = ?", runID).
		Updates(updates).Error; err != nil {
		logging.Error(ctx, "Failed to update run phase", zap.Error(err), zap.String("run_id", runID))
	}
}

func (m *RunInstanceManager) UpdateProgress(ctx context.Context, runID string, progress float64) error {
	event := events.NewEvent(events.EventProgressUpdate, "", map[string]interface{}{
		"run_id":   runID,
		"progress": progress,
	}, nil)
	_ = m.eventBus.Publish(ctx, event)

	return m.db.DB.WithContext(ctx).
		Model(&models.RunInstance{}).
		Where("run_id = ?", runID).
		Updates(map[string]interface{}{
			"progress":   progress,
			"updated_at": time.Now(),
		}).Error
}
