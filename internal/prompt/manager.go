package prompt

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"math"
	"math/rand"
	"sort"
	"sync"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	"github.com/solocoder/task-scheduler/internal/database"
	"github.com/solocoder/task-scheduler/internal/logging"
)

type PromptStatus string

const (
	PromptStatusDraft     PromptStatus = "draft"
	PromptStatusTesting   PromptStatus = "testing"
	PromptStatusActive    PromptStatus = "active"
	PromptStatusArchived  PromptStatus = "archived"
	PromptStatusDeprecated PromptStatus = "deprecated"
)

type ExperimentStatus string

const (
	ExperimentStatusCreated   ExperimentStatus = "created"
	ExperimentStatusRunning   ExperimentStatus = "running"
	ExperimentStatusPaused    ExperimentStatus = "paused"
	ExperimentStatusCompleted ExperimentStatus = "completed"
	ExperimentStatusStopped   ExperimentStatus = "stopped"
)

type TrafficAllocationMode string

const (
	TrafficModeWeighted TrafficAllocationMode = "weighted"
	TrafficModeRoundRobin TrafficAllocationMode = "round_robin"
	TrafficModeSticky    TrafficAllocationMode = "sticky"
)

type PromptVersion struct {
	ID           string                 `json:"id" gorm:"primaryKey;size:64"`
	PromptID     string                 `json:"prompt_id" gorm:"size:64;index:idx_prompt_version,unique"`
	Version      int                    `json:"version" gorm:"index:idx_prompt_version,unique"`
	Content      string                 `json:"content" gorm:"type:text"`
	SystemPrompt string                 `json:"system_prompt" gorm:"type:text"`
	Variables    map[string]string      `json:"variables" gorm:"type:jsonb"`
	Parameters   map[string]interface{} `json:"parameters" gorm:"type:jsonb"`
	Checksum     string                 `json:"checksum" gorm:"size:64"`
	Status       PromptStatus           `json:"status" gorm:"size:32;index"`
	Description  string                 `json:"description" gorm:"size:512"`
	CreatedBy    string                 `json:"created_by" gorm:"size:64"`
	ApprovedBy   *string                `json:"approved_by,omitempty" gorm:"size:64"`
	CreatedAt    time.Time              `json:"created_at" gorm:"index"`
	UpdatedAt    time.Time              `json:"updated_at"`
}

type Prompt struct {
	ID              string          `json:"id" gorm:"primaryKey;size:64"`
	Name            string          `json:"name" gorm:"size:256"`
	Description     string          `json:"description" gorm:"size:1024"`
	Category        string          `json:"category" gorm:"size:64;index"`
	Tags            []string        `json:"tags" gorm:"type:jsonb"`
	LatestVersion   int             `json:"latest_version"`
	ActiveVersionID *string         `json:"active_version_id,omitempty" gorm:"size:64"`
	Versions        []PromptVersion `json:"versions,omitempty" gorm:"foreignKey:PromptID;references:ID"`
	CreatedAt       time.Time       `json:"created_at" gorm:"index"`
	UpdatedAt       time.Time       `json:"updated_at"`
}

type ABExperiment struct {
	ID               string                 `json:"id" gorm:"primaryKey;size:64"`
	Name             string                 `json:"name" gorm:"size:256"`
	Description      string                 `json:"description" gorm:"size:1024"`
	PromptID         string                 `json:"prompt_id" gorm:"size:64;index"`
	ControlVersionID string                 `json:"control_version_id" gorm:"size:64"`
	TestVersionIDs   []string               `json:"test_version_ids" gorm:"type:jsonb"`
	TrafficWeights   map[string]float64     `json:"traffic_weights" gorm:"type:jsonb"`
	TrafficMode      TrafficAllocationMode  `json:"traffic_mode" gorm:"size:32"`
	Status           ExperimentStatus       `json:"status" gorm:"size:32;index"`
	EvaluationMetric string                 `json:"evaluation_metric" gorm:"size:128"`
	TargetSampleSize int                    `json:"target_sample_size"`
	CurrentSampleSize int                   `json:"current_sample_size"`
	SignificanceLevel float64               `json:"significance_level" gorm:"default:0.05"`
	StartedAt        *time.Time             `json:"started_at"`
	CompletedAt      *time.Time             `json:"completed_at"`
	CreatedBy        string                 `json:"created_by" gorm:"size:64"`
	CreatedAt        time.Time              `json:"created_at" gorm:"index"`
	UpdatedAt        time.Time              `json:"updated_at"`
}

type ExperimentResult struct {
	ID            string                 `json:"id" gorm:"primaryKey;size:64"`
	ExperimentID  string                 `json:"experiment_id" gorm:"size:64;index"`
	VersionID     string                 `json:"version_id" gorm:"size:64;index"`
	UserID        string                 `json:"user_id" gorm:"size:64;index"`
	SessionID     string                 `json:"session_id" gorm:"size:64"`
	Input         string                 `json:"input" gorm:"type:text"`
	Output        string                 `json:"output" gorm:"type:text"`
	LatencyMs     int64                  `json:"latency_ms"`
	Score         float64                `json:"score"`
	Feedback      map[string]interface{} `json:"feedback" gorm:"type:jsonb"`
	IsControl     bool                   `json:"is_control"`
	CreatedAt     time.Time              `json:"created_at" gorm:"index"`
}

type EvaluationResult struct {
	ExperimentID      string                 `json:"experiment_id"`
	ControlVersionID  string                 `json:"control_version_id"`
	TestVersionID     string                 `json:"test_version_id"`
	ControlMetrics    map[string]interface{} `json:"control_metrics"`
	TestMetrics       map[string]interface{} `json:"test_metrics"`
	Improvement       float64                `json:"improvement"`
	Confidence        float64                `json:"confidence"`
	PValue            float64                `json:"p_value"`
	IsStatisticallySignificant bool          `json:"is_statistically_significant"`
	Recommendation    string                 `json:"recommendation"`
}

type PromptManager struct {
	db              *database.Database
	cache           map[string]*PromptVersion
	cacheMu         sync.RWMutex
	experimentCache map[string]*ABExperiment
	experimentCacheMu sync.RWMutex
}

func NewPromptManager(db *database.Database) *PromptManager {
	return &PromptManager{
		db:              db,
		cache:           make(map[string]*PromptVersion),
		experimentCache: make(map[string]*ABExperiment),
	}
}

func (pm *PromptManager) CreatePrompt(ctx context.Context, name, description, category string, tags []string, createdBy string) (*Prompt, error) {
	promptID := "prompt_" + time.Now().Format("20060102150405")

	prompt := &Prompt{
		ID:            promptID,
		Name:          name,
		Description:   description,
		Category:      category,
		Tags:          tags,
		LatestVersion: 0,
		CreatedAt:     time.Now(),
		UpdatedAt:     time.Now(),
	}

	if err := pm.db.DB.WithContext(ctx).Create(prompt).Error; err != nil {
		return nil, err
	}

	return prompt, nil
}

func (pm *PromptManager) CreateVersion(ctx context.Context, promptID string, content, systemPrompt string, variables map[string]string, parameters map[string]interface{}, description, createdBy string) (*PromptVersion, error) {
	var prompt Prompt
	if err := pm.db.DB.WithContext(ctx).Where("id = ?", promptID).First(&prompt).Error; err != nil {
		return nil, err
	}

	versionNum := prompt.LatestVersion + 1
	versionID := fmt.Sprintf("%s_v%d", promptID, versionNum)
	checksum := calculateChecksum(content, systemPrompt)

	version := &PromptVersion{
		ID:           versionID,
		PromptID:     promptID,
		Version:      versionNum,
		Content:      content,
		SystemPrompt: systemPrompt,
		Variables:    variables,
		Parameters:   parameters,
		Checksum:     checksum,
		Status:       PromptStatusDraft,
		Description:  description,
		CreatedBy:    createdBy,
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
	}

	if err := pm.db.Transaction(ctx, func(tx *gorm.DB) error {
		if err := tx.Create(version).Error; err != nil {
			return err
		}
		return tx.Model(&prompt).Updates(map[string]interface{}{
			"latest_version": versionNum,
			"updated_at":     time.Now(),
		}).Error
	}); err != nil {
		return nil, err
	}

	pm.cacheMu.Lock()
	pm.cache[versionID] = version
	pm.cacheMu.Unlock()

	return version, nil
}

func (pm *PromptManager) GetVersion(ctx context.Context, versionID string) (*PromptVersion, error) {
	pm.cacheMu.RLock()
	if v, exists := pm.cache[versionID]; exists {
		pm.cacheMu.RUnlock()
		return v, nil
	}
	pm.cacheMu.RUnlock()

	var version PromptVersion
	err := pm.db.DB.WithContext(ctx).Where("id = ?", versionID).First(&version).Error
	if err != nil {
		return nil, err
	}

	pm.cacheMu.Lock()
	pm.cache[versionID] = &version
	pm.cacheMu.Unlock()

	return &version, nil
}

func (pm *PromptManager) GetLatestVersion(ctx context.Context, promptID string) (*PromptVersion, error) {
	var version PromptVersion
	err := pm.db.DB.WithContext(ctx).
		Where("prompt_id = ?", promptID).
		Order("version DESC").
		First(&version).Error
	return &version, err
}

func (pm *PromptManager) GetActiveVersion(ctx context.Context, promptID string) (*PromptVersion, error) {
	var prompt Prompt
	if err := pm.db.DB.WithContext(ctx).Where("id = ?", promptID).First(&prompt).Error; err != nil {
		return nil, err
	}

	if prompt.ActiveVersionID == nil {
		return nil, errors.New("no active version set")
	}

	return pm.GetVersion(ctx, *prompt.ActiveVersionID)
}

func (pm *PromptManager) SetActiveVersion(ctx context.Context, promptID, versionID string) error {
	return pm.db.Transaction(ctx, func(tx *gorm.DB) error {
		if err := tx.Model(&PromptVersion{}).
			Where("prompt_id = ? AND status = ?", promptID, PromptStatusActive).
			Update("status", PromptStatusArchived).Error; err != nil {
			return err
		}

		if err := tx.Model(&PromptVersion{}).
			Where("id = ?", versionID).
			Updates(map[string]interface{}{
				"status":     PromptStatusActive,
				"updated_at": time.Now(),
			}).Error; err != nil {
			return err
		}

		return tx.Model(&Prompt{}).
			Where("id = ?", promptID).
			Updates(map[string]interface{}{
				"active_version_id": versionID,
				"updated_at":        time.Now(),
			}).Error
	})
}

func (pm *PromptManager) UpdateVersionStatus(ctx context.Context, versionID string, status PromptStatus, approvedBy string) error {
	return pm.db.DB.WithContext(ctx).
		Model(&PromptVersion{}).
		Where("id = ?", versionID).
		Updates(map[string]interface{}{
			"status":      status,
			"approved_by": approvedBy,
			"updated_at":  time.Now(),
		}).Error
}

func (pm *PromptManager) ListVersions(ctx context.Context, promptID string, limit, offset int) ([]PromptVersion, int64, error) {
	var versions []PromptVersion
	var total int64

	query := pm.db.DB.WithContext(ctx).Model(&PromptVersion{}).Where("prompt_id = ?", promptID)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	err := query.Order("version DESC").
		Limit(limit).
		Offset(offset).
		Find(&versions).Error

	return versions, total, err
}

func (pm *PromptManager) CreateExperiment(ctx context.Context, promptID, name, description, controlVersionID string, testVersionIDs []string, trafficWeights map[string]float64, trafficMode TrafficAllocationMode, evaluationMetric string, targetSampleSize int, createdBy string) (*ABExperiment, error) {
	experimentID := "exp_" + time.Now().Format("20060102150405")

	if trafficWeights == nil {
		trafficWeights = make(map[string]float64)
		weight := 1.0 / float64(len(testVersionIDs)+1)
		trafficWeights[controlVersionID] = weight
		for _, v := range testVersionIDs {
			trafficWeights[v] = weight
		}
	}

	experiment := &ABExperiment{
		ID:               experimentID,
		Name:             name,
		Description:      description,
		PromptID:         promptID,
		ControlVersionID: controlVersionID,
		TestVersionIDs:   testVersionIDs,
		TrafficWeights:   trafficWeights,
		TrafficMode:      trafficMode,
		Status:           ExperimentStatusCreated,
		EvaluationMetric: evaluationMetric,
		TargetSampleSize: targetSampleSize,
		SignificanceLevel: 0.05,
		CreatedBy:        createdBy,
		CreatedAt:        time.Now(),
		UpdatedAt:        time.Now(),
	}

	if err := pm.db.DB.WithContext(ctx).Create(experiment).Error; err != nil {
		return nil, err
	}

	pm.experimentCacheMu.Lock()
	pm.experimentCache[experimentID] = experiment
	pm.experimentCacheMu.Unlock()

	return experiment, nil
}

func (pm *PromptManager) StartExperiment(ctx context.Context, experimentID string) error {
	now := time.Now()
	err := pm.db.DB.WithContext(ctx).
		Model(&ABExperiment{}).
		Where("id = ?", experimentID).
		Updates(map[string]interface{}{
			"status":     ExperimentStatusRunning,
			"started_at": now,
			"updated_at": now,
		}).Error

	if err == nil {
		pm.experimentCacheMu.Lock()
		if exp, exists := pm.experimentCache[experimentID]; exists {
			exp.Status = ExperimentStatusRunning
			exp.StartedAt = &now
		}
		pm.experimentCacheMu.Unlock()
	}

	return err
}

func (pm *PromptManager) GetVersionForExperiment(ctx context.Context, experimentID, userID, sessionID string) (string, bool, error) {
	var experiment ABExperiment
	if err := pm.db.DB.WithContext(ctx).Where("id = ?", experimentID).First(&experiment).Error; err != nil {
		return "", false, err
	}

	if experiment.Status != ExperimentStatusRunning {
		return experiment.ControlVersionID, true, nil
	}

	versionID, isControl, err := pm.allocateTraffic(&experiment, userID, sessionID)
	if err != nil {
		return experiment.ControlVersionID, true, err
	}

	return versionID, isControl, nil
}

func (pm *PromptManager) allocateTraffic(experiment *ABExperiment, userID, sessionID string) (string, bool, error) {
	if experiment.TrafficMode == TrafficModeSticky && userID != "" {
		hash := sha256.Sum256([]byte(experiment.ID + userID))
		hashInt := int(hash[0]) + int(hash[1])<<8
		normalized := float64(hashInt%10000) / 10000.0

		cumulative := 0.0
		for vid, weight := range experiment.TrafficWeights {
			cumulative += weight
			if normalized < cumulative {
				return vid, vid == experiment.ControlVersionID, nil
			}
		}
	}

	r := rand.New(rand.NewSource(time.Now().UnixNano()))
	random := r.Float64()

	cumulative := 0.0
	versionIDs := make([]string, 0, len(experiment.TrafficWeights))
	for vid := range experiment.TrafficWeights {
		versionIDs = append(versionIDs, vid)
	}
	sort.Strings(versionIDs)

	for _, vid := range versionIDs {
		weight := experiment.TrafficWeights[vid]
		cumulative += weight
		if random < cumulative {
			return vid, vid == experiment.ControlVersionID, nil
		}
	}

	return experiment.ControlVersionID, true, nil
}

func (pm *PromptManager) RecordExperimentResult(ctx context.Context, experimentID, versionID, userID, sessionID, input, output string, latencyMs int64, score float64, feedback map[string]interface{}) (*ExperimentResult, error) {
	var experiment ABExperiment
	if err := pm.db.DB.WithContext(ctx).Where("id = ?", experimentID).First(&experiment).Error; err != nil {
		return nil, err
	}

	resultID := "result_" + time.Now().Format("20060102150405")

	result := &ExperimentResult{
		ID:           resultID,
		ExperimentID: experimentID,
		VersionID:    versionID,
		UserID:       userID,
		SessionID:    sessionID,
		Input:        input,
		Output:       output,
		LatencyMs:    latencyMs,
		Score:        score,
		Feedback:     feedback,
		IsControl:    versionID == experiment.ControlVersionID,
		CreatedAt:    time.Now(),
	}

	if err := pm.db.Transaction(ctx, func(tx *gorm.DB) error {
		if err := tx.Create(result).Error; err != nil {
			return err
		}
		return tx.Model(&experiment).
			Where("id = ?", experimentID).
			Update("current_sample_size", gorm.Expr("current_sample_size + 1")).Error
	}); err != nil {
		return nil, err
	}

	return result, nil
}

func (pm *PromptManager) EvaluateExperiment(ctx context.Context, experimentID string) (*EvaluationResult, error) {
	var experiment ABExperiment
	if err := pm.db.DB.WithContext(ctx).Where("id = ?", experimentID).First(&experiment).Error; err != nil {
		return nil, err
	}

	controlResults, err := pm.getResultsByVersion(ctx, experimentID, experiment.ControlVersionID)
	if err != nil {
		return nil, err
	}

	if len(experiment.TestVersionIDs) == 0 {
		return nil, errors.New("no test versions in experiment")
	}

	testVersionID := experiment.TestVersionIDs[0]
	testResults, err := pm.getResultsByVersion(ctx, experimentID, testVersionID)
	if err != nil {
		return nil, err
	}

	if len(controlResults) == 0 || len(testResults) == 0 {
		return nil, errors.New("insufficient data for evaluation")
	}

	controlMetrics := pm.calculateMetrics(controlResults)
	testMetrics := pm.calculateMetrics(testResults)

	controlAvgScore := controlMetrics["avg_score"].(float64)
	testAvgScore := testMetrics["avg_score"].(float64)

	improvement := 0.0
	if controlAvgScore > 0 {
		improvement = (testAvgScore - controlAvgScore) / controlAvgScore * 100
	}

	pValue := calculatePValue(controlResults, testResults)
	isSignificant := pValue < experiment.SignificanceLevel

	recommendation := "continue_testing"
	if isSignificant {
		if improvement > 0 {
			recommendation = "promote_test_version"
		} else {
			recommendation = "reject_test_version"
		}
	} else if experiment.CurrentSampleSize >= experiment.TargetSampleSize {
		recommendation = "stop_insignificant"
	}

	return &EvaluationResult{
		ExperimentID:              experimentID,
		ControlVersionID:          experiment.ControlVersionID,
		TestVersionID:             testVersionID,
		ControlMetrics:            controlMetrics,
		TestMetrics:               testMetrics,
		Improvement:               improvement,
		Confidence:                1 - pValue,
		PValue:                    pValue,
		IsStatisticallySignificant: isSignificant,
		Recommendation:            recommendation,
	}, nil
}

func (pm *PromptManager) getResultsByVersion(ctx context.Context, experimentID, versionID string) ([]ExperimentResult, error) {
	var results []ExperimentResult
	err := pm.db.DB.WithContext(ctx).
		Where("experiment_id = ? AND version_id = ?", experimentID, versionID).
		Find(&results).Error
	return results, err
}

func (pm *PromptManager) calculateMetrics(results []ExperimentResult) map[string]interface{} {
	if len(results) == 0 {
		return nil
	}

	var totalScore float64
	var totalLatency int64
	scores := make([]float64, len(results))

	for i, r := range results {
		totalScore += r.Score
		totalLatency += r.LatencyMs
		scores[i] = r.Score
	}

	avgScore := totalScore / float64(len(results))
	avgLatency := float64(totalLatency) / float64(len(results))

	variance := 0.0
	for _, s := range scores {
		variance += math.Pow(s-avgScore, 2)
	}
	variance /= float64(len(results))
	stdDev := math.Sqrt(variance)

	sort.Float64s(scores)
	median := scores[len(scores)/2]

	return map[string]interface{}{
		"sample_size": len(results),
		"avg_score":   avgScore,
		"avg_latency_ms": avgLatency,
		"median_score": median,
		"std_dev":     stdDev,
		"min_score":   scores[0],
		"max_score":   scores[len(scores)-1],
	}
}

func (pm *PromptManager) CompleteExperiment(ctx context.Context, experimentID string) error {
	now := time.Now()
	return pm.db.DB.WithContext(ctx).
		Model(&ABExperiment{}).
		Where("id = ?", experimentID).
		Updates(map[string]interface{}{
			"status":       ExperimentStatusCompleted,
			"completed_at": now,
			"updated_at":   now,
		}).Error
}

func (pm *PromptManager) ListExperiments(ctx context.Context, promptID string, status ExperimentStatus, limit, offset int) ([]ABExperiment, int64, error) {
	var experiments []ABExperiment
	var total int64

	query := pm.db.DB.WithContext(ctx).Model(&ABExperiment{})
	if promptID != "" {
		query = query.Where("prompt_id = ?", promptID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	err := query.Order("created_at DESC").
		Limit(limit).
		Offset(offset).
		Find(&experiments).Error

	return experiments, total, err
}

func (pm *PromptManager) RenderPrompt(ctx context.Context, versionID string, variables map[string]interface{}) (string, string, error) {
	version, err := pm.GetVersion(ctx, versionID)
	if err != nil {
		return "", "", err
	}

	renderedContent := version.Content
	renderedSystem := version.SystemPrompt

	for key, value := range variables {
		strValue := fmt.Sprintf("%v", value)
		placeholder := "{{" + key + "}}"
		renderedContent = replaceAll(renderedContent, placeholder, strValue)
		renderedSystem = replaceAll(renderedSystem, placeholder, strValue)
	}

	return renderedSystem, renderedContent, nil
}

func calculateChecksum(contents ...string) string {
	h := sha256.New()
	for _, c := range contents {
		h.Write([]byte(c))
	}
	return hex.EncodeToString(h.Sum(nil))
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

func calculatePValue(control, test []ExperimentResult) float64 {
	if len(control) == 0 || len(test) == 0 {
		return 1.0
	}

	controlScores := make([]float64, len(control))
	testScores := make([]float64, len(test))

	for i, r := range control {
		controlScores[i] = r.Score
	}
	for i, r := range test {
		testScores[i] = r.Score
	}

	meanControl := mean(controlScores)
	meanTest := mean(testScores)
	varControl := variance(controlScores, meanControl)
	varTest := variance(testScores, meanTest)

	nControl := float64(len(controlScores))
	nTest := float64(len(testScores))

	se := math.Sqrt(varControl/nControl + varTest/nTest)
	if se == 0 {
		return 1.0
	}

	tStatistic := (meanTest - meanControl) / se

	df := nControl + nTest - 2

	pValue := 2 * (1 - normalCDF(math.Abs(tStatistic)))

	logging.Debug(context.Background(), "Calculated p-value",
		zap.Float64("p_value", pValue),
		zap.Float64("t_stat", tStatistic),
		zap.Int("control_size", len(control)),
		zap.Int("test_size", len(test)))

	return pValue
}

func mean(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sum := 0.0
	for _, v := range values {
		sum += v
	}
	return sum / float64(len(values))
}

func variance(values []float64, mean float64) float64 {
	if len(values) <= 1 {
		return 0
	}
	sum := 0.0
	for _, v := range values {
		sum += math.Pow(v-mean, 2)
	}
	return sum / float64(len(values)-1)
}

func normalCDF(x float64) float64 {
	return 0.5 * (1 + math.Erf(x/math.Sqrt2))
}
