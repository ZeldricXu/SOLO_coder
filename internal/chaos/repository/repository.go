package repository

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
)

type InMemoryScenarioRepository struct {
	mu        sync.RWMutex
	scenarios map[string]*domain.ChaosScenario
}

func NewScenarioRepository() ports.ScenarioRepository {
	return &InMemoryScenarioRepository{
		scenarios: make(map[string]*domain.ChaosScenario),
	}
}

func (r *InMemoryScenarioRepository) Save(ctx context.Context, scenario *domain.ChaosScenario) error {
	if scenario == nil {
		return fmt.Errorf("scenario cannot be nil")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.scenarios[scenario.ScenarioID] = scenario
	return nil
}

func (r *InMemoryScenarioRepository) FindByID(ctx context.Context, scenarioID string) (*domain.ChaosScenario, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	scenario, exists := r.scenarios[scenarioID]
	if !exists {
		return nil, fmt.Errorf("scenario %s not found", scenarioID)
	}
	return scenario, nil
}

func (r *InMemoryScenarioRepository) ListByNamespace(ctx context.Context, namespace string) ([]*domain.ChaosScenario, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	list := make([]*domain.ChaosScenario, 0)
	for _, s := range r.scenarios {
		if namespace == "" || s.Namespace == namespace {
			list = append(list, s)
		}
	}
	return list, nil
}

func (r *InMemoryScenarioRepository) Delete(ctx context.Context, scenarioID string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, exists := r.scenarios[scenarioID]; !exists {
		return fmt.Errorf("scenario %s not found", scenarioID)
	}
	delete(r.scenarios, scenarioID)
	return nil
}

type InMemoryRunRepository struct {
	mu   sync.RWMutex
	runs map[string]*domain.RunInstance
}

func NewRunRepository() ports.RunInstanceRepository {
	return &InMemoryRunRepository{
		runs: make(map[string]*domain.RunInstance),
	}
}

func (r *InMemoryRunRepository) Save(ctx context.Context, run *domain.RunInstance) error {
	if run == nil {
		return fmt.Errorf("run cannot be nil")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.runs[run.RunID] = run
	return nil
}

func (r *InMemoryRunRepository) FindByID(ctx context.Context, runID string) (*domain.RunInstance, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	run, exists := r.runs[runID]
	if !exists {
		return nil, fmt.Errorf("run %s not found", runID)
	}
	return run, nil
}

func (r *InMemoryRunRepository) Update(ctx context.Context, run *domain.RunInstance) error {
	if run == nil {
		return fmt.Errorf("run cannot be nil")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, exists := r.runs[run.RunID]; !exists {
		return fmt.Errorf("run %s not found", run.RunID)
	}
	r.runs[run.RunID] = run
	return nil
}

func (r *InMemoryRunRepository) UpdatePhase(runID string, phase string, progress float64) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if run, exists := r.runs[runID]; exists {
		run.Phase = phase
		run.Progress = progress
		if progress >= 1.0 {
			now := time.Now()
			run.CompletedAt = &now
		}
	}
}

func (r *InMemoryRunRepository) UpdateError(runID string, errMsg string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if run, exists := r.runs[runID]; exists {
		run.Phase = "failed"
		run.ErrorDetail = errMsg
		run.Progress = 1.0
		now := time.Now()
		run.CompletedAt = &now
	}
}
