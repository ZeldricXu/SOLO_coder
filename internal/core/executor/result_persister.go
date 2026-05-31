package executor

import (
	"context"
	"fmt"

	"github.com/solocoder/task-scheduler/v2/internal/core/ports"
)

type ResultPersister struct {
	repo ports.Repository
}

func NewResultPersister(repo ports.Repository) *ResultPersister {
	return &ResultPersister{
		repo: repo,
	}
}

func (p *ResultPersister) PersistResult(
	ctx context.Context,
	result map[string]interface{},
	entityID string,
) error {
	if err := p.repo.PersistResult(ctx, result, entityID); err != nil {
		return fmt.Errorf("persist failed: %w", err)
	}
	return nil
}

func (p *ResultPersister) CreateRunInstance(
	ctx context.Context,
	req *ports.ProcessRequest,
	config *ports.ConfigDefinition,
) (string, error) {
	runID := "run_" + req.TraceID

	run := &ports.RunInstance{
		RunID:     runID,
		EntityID:  req.EntityID,
		ConfigID:  config.ConfigID,
		Phase:     "executing",
		Progress:  0,
		TraceID:   req.TraceID,
	}

	if err := p.repo.CreateRunInstance(ctx, run); err != nil {
		return "", fmt.Errorf("failed to create run instance: %w", err)
	}

	return runID, nil
}

func (p *ResultPersister) UpdateRunPhase(
	ctx context.Context,
	runID string,
	phase string,
	errorDetail string,
) error {
	return p.repo.UpdateRunPhase(ctx, runID, phase, errorDetail)
}

func (p *ResultPersister) RecordMetrics(
	ctx context.Context,
	metrics map[string]interface{},
	dimensions map[string]string,
) error {
	return p.repo.RecordMetricsSnapshot(ctx, metrics, dimensions)
}
