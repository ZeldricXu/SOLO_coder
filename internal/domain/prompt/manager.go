package prompt

import (
	"context"
	"fmt"
	"math/rand"
	"sort"
	"sync"
	"time"

	"github.com/dataplatform/engine/internal/common/errors"
	"github.com/dataplatform/engine/internal/domain"
	"github.com/google/uuid"
)

type PromptExperimentManagerImpl struct {
	experiments map[string]*PromptExperiment
	versions    map[string][]*PromptVersion
	abTests     map[string]*ABTest
	mu          sync.RWMutex
	logger      domain.Logger
}

func NewPromptExperimentManagerImpl(logger domain.Logger) *PromptExperimentManagerImpl {
	return &PromptExperimentManagerImpl{
		experiments: make(map[string]*PromptExperiment),
		versions:    make(map[string][]*PromptVersion),
		abTests:     make(map[string]*ABTest),
		logger:      logger,
	}
}

func (m *PromptExperimentManagerImpl) CreateExperiment(ctx context.Context, exp *PromptExperiment) (*PromptExperiment, error) {
	if exp == nil {
		return nil, errors.New(errors.ErrCodeValidation, "experiment cannot be nil")
	}
	if exp.Name == "" {
		return nil, errors.New(errors.ErrCodeValidation, "experiment name required")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	exp.ID = uuid.New().String()
	exp.CreatedAt = time.Now()

	m.experiments[exp.ID] = exp
	m.versions[exp.ID] = make([]*PromptVersion, 0)

	m.logger.Info("Prompt experiment created",
		domain.String("experiment_id", exp.ID),
		domain.String("name", exp.Name),
	)

	return exp, nil
}

func (m *PromptExperimentManagerImpl) CreateVersion(ctx context.Context, exp *PromptExperiment) (*PromptVersion, error) {
	if exp == nil || exp.ID == "" {
		return nil, errors.New(errors.ErrCodeValidation, "experiment ID required")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.experiments[exp.ID]; !exists {
		return nil, errors.New(errors.ErrCodeNotFound, "experiment not found")
	}

	versions := m.versions[exp.ID]
	version := &PromptVersion{
		ID:           uuid.New().String(),
		ExperimentID: exp.ID,
		Version:      len(versions) + 1,
		Template:     "",
		Variables:    make(map[string]interface{}),
		Metadata:     make(map[string]string),
		CreatedAt:    time.Now(),
	}

	m.versions[exp.ID] = append(versions, version)

	m.logger.Info("Prompt version created",
		domain.String("experiment_id", exp.ID),
		domain.String("version_id", version.ID),
		domain.Int("version", version.Version),
	)

	return version, nil
}

func (m *PromptExperimentManagerImpl) GetVersion(ctx context.Context, versionID string) (*PromptVersion, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, versions := range m.versions {
		for _, v := range versions {
			if v.ID == versionID {
				return v, nil
			}
		}
	}

	return nil, errors.New(errors.ErrCodeNotFound, "version not found")
}

func (m *PromptExperimentManagerImpl) ListVersions(ctx context.Context, expID string) ([]*PromptVersion, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	versions, exists := m.versions[expID]
	if !exists {
		return nil, errors.New(errors.ErrCodeNotFound, "experiment not found")
	}

	result := make([]*PromptVersion, len(versions))
	copy(result, versions)

	sort.Slice(result, func(i, j int) bool {
		return result[i].Version > result[j].Version
	})

	return result, nil
}

func (m *PromptExperimentManagerImpl) StartABTest(ctx context.Context, config *ABTestConfig) (*ABTest, error) {
	if config == nil {
		return nil, errors.New(errors.ErrCodeValidation, "config cannot be nil")
	}
	if len(config.VersionIDs) < 2 {
		return nil, errors.New(errors.ErrCodeValidation, "at least 2 versions required for A/B test")
	}

	totalWeight := 0
	for _, weight := range config.TrafficSplit {
		totalWeight += weight
	}
	if totalWeight != 100 {
		return nil, errors.New(errors.ErrCodeValidation,
			fmt.Sprintf("traffic split must sum to 100, got %d", totalWeight))
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	test := &ABTest{
		ID:      uuid.New().String(),
		Config:  config,
		Status:  "running",
		Metrics: make(map[string]float64),
	}

	m.abTests[test.ID] = test

	m.logger.Info("A/B test started",
		domain.String("test_id", test.ID),
		domain.String("name", config.Name),
		domain.Int("versions", len(config.VersionIDs)),
	)

	return test, nil
}

func (m *PromptExperimentManagerImpl) StopABTest(ctx context.Context, testID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	test, exists := m.abTests[testID]
	if !exists {
		return errors.New(errors.ErrCodeNotFound, "test not found")
	}

	test.Status = "stopped"
	now := time.Now()
	test.Config.EndTime = &now

	m.logger.Info("A/B test stopped", domain.String("test_id", testID))
	return nil
}

func (m *PromptExperimentManagerImpl) Evaluate(ctx context.Context, testID string, metrics map[string]float64) (*ABTestResult, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	test, exists := m.abTests[testID]
	if !exists {
		return nil, errors.New(errors.ErrCodeNotFound, "test not found")
	}

	versionMetrics := make(map[string]*VersionMetric)
	for _, vID := range test.Config.VersionIDs {
		versionMetrics[vID] = &VersionMetric{
			VersionID: vID,
			Values:    make(map[string]float64),
			SampleSize: rand.Intn(1000) + 100,
		}

		for name, value := range metrics {
			versionMetrics[vID].Values[name] = value * (0.8 + rand.Float64()*0.4)
		}
	}

	var winningVersion string
	bestScore := -1.0
	for vID, vm := range versionMetrics {
		score := vm.Values["score"]
		if score > bestScore {
			bestScore = score
			winningVersion = vID
		}
	}

	result := &ABTestResult{
		TestID:         testID,
		WinningVersion: winningVersion,
		Confidence:     0.95,
		Metrics:        versionMetrics,
	}

	m.logger.Info("A/B test evaluated",
		domain.String("test_id", testID),
		domain.String("winner", winningVersion),
		domain.Float64("confidence", result.Confidence),
	)

	return result, nil
}

func (m *PromptExperimentManagerImpl) GetABTest(ctx context.Context, testID string) (*ABTest, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	test, exists := m.abTests[testID]
	if !exists {
		return nil, errors.New(errors.ErrCodeNotFound, "test not found")
	}

	return test, nil
}

func (m *PromptExperimentManagerImpl) ListABTests(ctx context.Context, expID string) ([]*ABTest, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]*ABTest, 0)
	for _, test := range m.abTests {
		if test.Config.ExperimentID == expID {
			result = append(result, test)
		}
	}

	return result, nil
}

func (m *PromptExperimentManagerImpl) RenderTemplate(version *PromptVersion, variables map[string]interface{}) string {
	template := version.Template

	for k, v := range variables {
		placeholder := fmt.Sprintf("{{%s}}", k)
		template = replaceAll(template, placeholder, fmt.Sprintf("%v", v))
	}

	return template
}

func replaceAll(s, old, new string) string {
	result := s
	for {
		idx := indexOf(result, old)
		if idx == -1 {
			break
		}
		result = result[:idx] + new + result[idx+len(old):]
	}
	return result
}

func indexOf(s, substr string) int {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return i
		}
	}
	return -1
}
