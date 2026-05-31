package modelregistry

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"sync"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	"github.com/solocoder/task-scheduler/internal/database"
	"github.com/solocoder/task-scheduler/internal/logging"
)

type ModelStage string

const (
	StageStaging    ModelStage = "staging"
	StageTesting    ModelStage = "testing"
	StageProduction ModelStage = "production"
	StageArchived   ModelStage = "archived"
	StageDeprecated ModelStage = "deprecated"
)

type ModelStatus string

const (
	StatusPending   ModelStatus = "pending"
	StatusActive    ModelStatus = "active"
	StatusPaused    ModelStatus = "paused"
	StatusSuspended ModelStatus = "suspended"
)

type ModelType string

const (
	ModelTypeLLM        ModelType = "llm"
	ModelTypeEmbedding  ModelType = "embedding"
	ModelTypeVision     ModelType = "vision"
	ModelTypeAudio      ModelType = "audio"
	ModelTypeMultimodal ModelType = "multimodal"
)

type Model struct {
	ID            string                 `json:"id" gorm:"primaryKey;size:64"`
	Name          string                 `json:"name" gorm:"size:256;index"`
	Description   string                 `json:"description" gorm:"size:1024"`
	ModelType     ModelType              `json:"model_type" gorm:"size:32;index"`
	Provider      string                 `json:"provider" gorm:"size:64;index"`
	BaseModel     string                 `json:"base_model" gorm:"size:256"`
	Architecture  string                 `json:"architecture" gorm:"size:128"`
	Parameters    string                 `json:"parameters" gorm:"size:64"`
	ContextWindow int                    `json:"context_window"`
	MaxOutputTokens int                 `json:"max_output_tokens"`
	License       string                 `json:"license" gorm:"size:128"`
	Tags          []string               `json:"tags" gorm:"type:jsonb"`
	Metadata      map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	CreatedBy     string                 `json:"created_by" gorm:"size:64"`
	Owner         string                 `json:"owner" gorm:"size:64;index"`
	CreatedAt     time.Time              `json:"created_at" gorm:"index"`
	UpdatedAt     time.Time              `json:"updated_at"`
	Versions      []ModelVersion         `json:"versions,omitempty" gorm:"foreignKey:ModelID;references:ID"`
}

type ModelVersion struct {
	ID            string                 `json:"id" gorm:"primaryKey;size:64"`
	ModelID       string                 `json:"model_id" gorm:"size:64;index:idx_model_version,unique"`
	Version       string                 `json:"version" gorm:"size:32;index:idx_model_version,unique"`
	VersionNumber int                    `json:"version_number"`
	Stage         ModelStage             `json:"stage" gorm:"size:32;index"`
	Status        ModelStatus            `json:"status" gorm:"size:32;index"`
	Checkpoint    string                 `json:"checkpoint" gorm:"size:512"`
	WeightsPath   string                 `json:"weights_path" gorm:"size:512"`
	Checksum      string                 `json:"checksum" gorm:"size:128"`
	SizeBytes     int64                  `json:"size_bytes"`
	TrainingData  string                 `json:"training_data" gorm:"size:512"`
	TrainingDate  *time.Time             `json:"training_date"`
	EvalResults   map[string]interface{} `json:"eval_results" gorm:"type:jsonb"`
	ReleaseNotes  string                 `json:"release_notes" gorm:"type:text"`
	DeployedAt    *time.Time             `json:"deployed_at"`
	DeprecatedAt  *time.Time             `json:"deprecated_at"`
	CreatedBy     string                 `json:"created_by" gorm:"size:64"`
	ApprovedBy    *string                `json:"approved_by,omitempty" gorm:"size:64"`
	CreatedAt     time.Time              `json:"created_at" gorm:"index"`
	UpdatedAt     time.Time              `json:"updated_at"`
	Endpoints     []ModelEndpointRef     `json:"endpoints,omitempty" gorm:"foreignKey:ModelVersionID;references:ID"`
}

type ModelEndpointRef struct {
	ID            string    `json:"id" gorm:"primaryKey;size:64"`
	ModelVersionID string   `json:"model_version_id" gorm:"size:64;index"`
	EndpointURL   string    `json:"endpoint_url" gorm:"size:512"`
	Environment   string    `json:"environment" gorm:"size:32;index"`
	Region        string    `json:"region" gorm:"size:64"`
	IsPrimary     bool      `json:"is_primary"`
	Enabled       bool      `json:"enabled"`
	TrafficWeight int       `json:"traffic_weight"`
	CreatedAt     time.Time `json:"created_at"`
	UpdatedAt     time.Time `json:"updated_at"`
}

type StageTransition struct {
	ID            string                 `json:"id" gorm:"primaryKey;size:64"`
	ModelID       string                 `json:"model_id" gorm:"size:64;index"`
	VersionID     string                 `json:"version_id" gorm:"size:64;index"`
	FromStage     ModelStage             `json:"from_stage" gorm:"size:32"`
	ToStage       ModelStage             `json:"to_stage" gorm:"size:32"`
	Reason        string                 `json:"reason" gorm:"type:text"`
	Metadata      map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	TransitionedBy string                `json:"transitioned_by" gorm:"size:64"`
	TransitionedAt time.Time             `json:"transitioned_at" gorm:"index"`
}

type ModelRegistry struct {
	db              *database.Database
	cache           map[string]*Model
	versionCache    map[string]*ModelVersion
	cacheMu         sync.RWMutex
	stageListeners  map[ModelStage][]StageChangeListener
	listenersMu     sync.RWMutex
}

type StageChangeListener func(ctx context.Context, version *ModelVersion, from, to ModelStage)

func NewModelRegistry(db *database.Database) *ModelRegistry {
	return &ModelRegistry{
		db:             db,
		cache:          make(map[string]*Model),
		versionCache:   make(map[string]*ModelVersion),
		stageListeners: make(map[ModelStage][]StageChangeListener),
	}
}

func (r *ModelRegistry) RegisterModel(ctx context.Context, model *Model) (string, error) {
	if model.ID == "" {
		model.ID = "model_" + time.Now().Format("20060102150405")
	}
	model.CreatedAt = time.Now()
	model.UpdatedAt = time.Now()

	if err := r.db.DB.WithContext(ctx).Create(model).Error; err != nil {
		return "", err
	}

	r.cacheMu.Lock()
	r.cache[model.ID] = model
	r.cacheMu.Unlock()

	logging.Info(ctx, "Model registered",
		zap.String("model_id", model.ID),
		zap.String("name", model.Name),
		zap.String("type", string(model.ModelType)))

	return model.ID, nil
}

func (r *ModelRegistry) RegisterVersion(ctx context.Context, modelID string, version *ModelVersion) (string, error) {
	var model Model
	if err := r.db.DB.WithContext(ctx).Where("id = ?", modelID).First(&model).Error; err != nil {
		return "", fmt.Errorf("model not found: %w", err)
	}

	var maxVersion int
	r.db.DB.WithContext(ctx).Model(&ModelVersion{}).
		Where("model_id = ?", modelID).
		Select("COALESCE(MAX(version_number), 0)").
		Scan(&maxVersion)

	version.ModelID = modelID
	version.ID = fmt.Sprintf("%s_v%d", modelID, maxVersion+1)
	version.VersionNumber = maxVersion + 1
	if version.Version == "" {
		version.Version = fmt.Sprintf("v%d.%d.0", maxVersion+1, 0)
	}
	if version.Stage == "" {
		version.Stage = StageStaging
	}
	if version.Status == "" {
		version.Status = StatusPending
	}
	version.CreatedAt = time.Now()
	version.UpdatedAt = time.Now()

	if err := r.db.DB.WithContext(ctx).Create(version).Error; err != nil {
		return "", err
	}

	r.cacheMu.Lock()
	r.versionCache[version.ID] = version
	r.cacheMu.Unlock()

	transition := &StageTransition{
		ID:             "trans_" + time.Now().Format("20060102150405"),
		ModelID:        modelID,
		VersionID:      version.ID,
		FromStage:      "",
		ToStage:        version.Stage,
		Reason:         "Initial version creation",
		TransitionedBy: version.CreatedBy,
		TransitionedAt: time.Now(),
	}
	_ = r.db.DB.Create(transition).Error

	r.notifyStageChange(ctx, version, "", version.Stage)

	logging.Info(ctx, "Model version registered",
		zap.String("version_id", version.ID),
		zap.String("model_id", modelID),
		zap.String("version", version.Version),
		zap.String("stage", string(version.Stage)))

	return version.ID, nil
}

func (r *ModelRegistry) GetModel(ctx context.Context, modelID string) (*Model, error) {
	r.cacheMu.RLock()
	if m, exists := r.cache[modelID]; exists {
		r.cacheMu.RUnlock()
		return m, nil
	}
	r.cacheMu.RUnlock()

	var model Model
	err := r.db.DB.WithContext(ctx).
		Preload("Versions").
		Where("id = ?", modelID).
		First(&model).Error
	if err != nil {
		return nil, err
	}

	r.cacheMu.Lock()
	r.cache[modelID] = &model
	r.cacheMu.Unlock()

	return &model, nil
}

func (r *ModelRegistry) GetVersion(ctx context.Context, versionID string) (*ModelVersion, error) {
	r.cacheMu.RLock()
	if v, exists := r.versionCache[versionID]; exists {
		r.cacheMu.RUnlock()
		return v, nil
	}
	r.cacheMu.RUnlock()

	var version ModelVersion
	err := r.db.DB.WithContext(ctx).
		Preload("Endpoints").
		Where("id = ?", versionID).
		First(&version).Error
	if err != nil {
		return nil, err
	}

	r.cacheMu.Lock()
	r.versionCache[versionID] = &version
	r.cacheMu.Unlock()

	return &version, nil
}

func (r *ModelRegistry) GetActiveVersion(ctx context.Context, modelID string, stage ModelStage) (*ModelVersion, error) {
	var version ModelVersion
	err := r.db.DB.WithContext(ctx).
		Where("model_id = ? AND stage = ? AND status = ?", modelID, stage, StatusActive).
		Order("version_number DESC").
		First(&version).Error
	if err != nil {
		return nil, err
	}
	return &version, nil
}

func (r *ModelRegistry) ListModels(ctx context.Context, modelType ModelType, provider string, limit, offset int) ([]Model, int64, error) {
	var models []Model
	var total int64

	query := r.db.DB.WithContext(ctx).Model(&Model{})
	if modelType != "" {
		query = query.Where("model_type = ?", modelType)
	}
	if provider != "" {
		query = query.Where("provider = ?", provider)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	err := query.Order("created_at DESC").
		Limit(limit).
		Offset(offset).
		Preload("Versions").
		Find(&models).Error

	return models, total, err
}

func (r *ModelRegistry) ListVersions(ctx context.Context, modelID string, stage ModelStage, limit, offset int) ([]ModelVersion, int64, error) {
	var versions []ModelVersion
	var total int64

	query := r.db.DB.WithContext(ctx).Model(&ModelVersion{}).Where("model_id = ?", modelID)
	if stage != "" {
		query = query.Where("stage = ?", stage)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	err := query.Order("version_number DESC").
		Limit(limit).
		Offset(offset).
		Preload("Endpoints").
		Find(&versions).Error

	return versions, total, err
}

func (r *ModelRegistry) TransitionStage(ctx context.Context, versionID string, toStage ModelStage, reason string, transitionedBy string) (*ModelVersion, error) {
	var version ModelVersion
	if err := r.db.DB.WithContext(ctx).Where("id = ?", versionID).First(&version).Error; err != nil {
		return nil, err
	}

	if !isValidStageTransition(version.Stage, toStage) {
		return nil, fmt.Errorf("invalid stage transition from %s to %s", version.Stage, toStage)
	}

	fromStage := version.Stage
	now := time.Now()

	updates := map[string]interface{}{
		"stage":      toStage,
		"updated_at": now,
	}

	if toStage == StageProduction {
		updates["deployed_at"] = now
		updates["status"] = StatusActive
	}
	if toStage == StageDeprecated {
		updates["deprecated_at"] = now
		updates["status"] = StatusSuspended
	}

	err := r.db.Transaction(ctx, func(tx *gorm.DB) error {
		if err := tx.Model(&version).Updates(updates).Error; err != nil {
			return err
		}

		transition := &StageTransition{
			ID:             "trans_" + time.Now().Format("20060102150405"),
			ModelID:        version.ModelID,
			VersionID:      versionID,
			FromStage:      fromStage,
			ToStage:        toStage,
			Reason:         reason,
			TransitionedBy: transitionedBy,
			TransitionedAt: now,
		}
		return tx.Create(transition).Error
	})

	if err != nil {
		return nil, err
	}

	version.Stage = toStage

	r.cacheMu.Lock()
	r.versionCache[versionID] = &version
	r.cacheMu.Unlock()

	r.notifyStageChange(ctx, &version, fromStage, toStage)

	logging.Info(ctx, "Model stage transitioned",
		zap.String("version_id", versionID),
		zap.String("from", string(fromStage)),
		zap.String("to", string(toStage)))

	return &version, nil
}

func (r *ModelRegistry) UpdateVersionStatus(ctx context.Context, versionID string, status ModelStatus, reason string) error {
	var version ModelVersion
	if err := r.db.DB.WithContext(ctx).Where("id = ?", versionID).First(&version).Error; err != nil {
		return err
	}

	now := time.Now()
	err := r.db.DB.Model(&version).Updates(map[string]interface{}{
		"status":     status,
		"updated_at": now,
	}).Error

	if err == nil {
		r.cacheMu.Lock()
		if v, exists := r.versionCache[versionID]; exists {
			v.Status = status
		}
		r.cacheMu.Unlock()
	}

	return err
}

func (r *ModelRegistry) AddEndpoint(ctx context.Context, versionID string, endpoint *ModelEndpointRef) (string, error) {
	endpoint.ID = "ep_" + time.Now().Format("20060102150405")
	endpoint.ModelVersionID = versionID
	endpoint.CreatedAt = time.Now()
	endpoint.UpdatedAt = time.Now()

	if err := r.db.DB.WithContext(ctx).Create(endpoint).Error; err != nil {
		return "", err
	}

	return endpoint.ID, nil
}

func (r *ModelRegistry) GetStageTransitions(ctx context.Context, versionID string, limit int) ([]StageTransition, error) {
	var transitions []StageTransition
	err := r.db.DB.WithContext(ctx).
		Where("version_id = ?", versionID).
		Order("transitioned_at DESC").
		Limit(limit).
		Find(&transitions).Error
	return transitions, err
}

func (r *ModelRegistry) GetModelByStage(ctx context.Context, stage ModelStage, modelType ModelType) ([]ModelVersion, error) {
	var versions []ModelVersion
	query := r.db.DB.WithContext(ctx).
		Where("stage = ? AND status = ?", stage, StatusActive).
		Preload("Endpoints")

	if modelType != "" {
		query = query.Joins("JOIN models ON models.id = model_versions.model_id").
			Where("models.model_type = ?", modelType)
	}

	err := query.Find(&versions).Error
	return versions, err
}

func (r *ModelRegistry) RegisterStageListener(stage ModelStage, listener StageChangeListener) {
	r.listenersMu.Lock()
	defer r.listenersMu.Unlock()
	r.stageListeners[stage] = append(r.stageListeners[stage], listener)
}

func (r *ModelRegistry) notifyStageChange(ctx context.Context, version *ModelVersion, from, to ModelStage) {
	r.listenersMu.RLock()
	defer r.listenersMu.RUnlock()

	if listeners, exists := r.stageListeners[to]; exists {
		for _, listener := range listeners {
			func(l StageChangeListener) {
				defer func() {
					if r := recover(); r != nil {
						logging.Error(ctx, "Stage listener panicked", zap.Any("panic", r))
					}
				}()
				l(ctx, version, from, to)
			}(listener)
		}
	}
}

func (r *ModelRegistry) UpdateModelMetadata(ctx context.Context, modelID string, metadata map[string]interface{}) error {
	return r.db.DB.WithContext(ctx).
		Model(&Model{}).
		Where("id = ?", modelID).
		Updates(map[string]interface{}{
			"metadata":   metadata,
			"updated_at": time.Now(),
		}).Error
}

func (r *ModelRegistry) UpdateEvalResults(ctx context.Context, versionID string, evalResults map[string]interface{}) error {
	return r.db.DB.WithContext(ctx).
		Model(&ModelVersion{}).
		Where("id = ?", versionID).
		Updates(map[string]interface{}{
			"eval_results": evalResults,
			"updated_at":   time.Now(),
		}).Error
}

func (r *ModelRegistry) GetVersionLifecycle(ctx context.Context, versionID string) (map[string]interface{}, error) {
	version, err := r.GetVersion(ctx, versionID)
	if err != nil {
		return nil, err
	}

	transitions, err := r.GetStageTransitions(ctx, versionID, 100)
	if err != nil {
		return nil, err
	}

	lifecycle := make(map[string]interface{})
	lifecycle["version"] = version
	lifecycle["transitions"] = transitions

	stageDurations := make(map[ModelStage]time.Duration)
	var lastTime time.Time
	var lastStage ModelStage

	sortedTransitions := make([]StageTransition, len(transitions))
	copy(sortedTransitions, transitions)
	sort.Slice(sortedTransitions, func(i, j int) bool {
		return sortedTransitions[i].TransitionedAt.Before(sortedTransitions[j].TransitionedAt)
	})

	for _, t := range sortedTransitions {
		if !lastTime.IsZero() && lastStage != "" {
			stageDurations[lastStage] = t.TransitionedAt.Sub(lastTime)
		}
		lastTime = t.TransitionedAt
		lastStage = t.ToStage
	}

	if !lastTime.IsZero() {
		stageDurations[lastStage] = time.Since(lastTime)
	}

	lifecycle["stage_durations"] = stageDurations
	lifecycle["total_age"] = time.Since(version.CreatedAt)

	return lifecycle, nil
}

func (r *ModelRegistry) DeleteModel(ctx context.Context, modelID string) error {
	return r.db.Transaction(ctx, func(tx *gorm.DB) error {
		if err := tx.Where("model_id = ?", modelID).Delete(&ModelEndpointRef{}).Error; err != nil {
			return err
		}
		if err := tx.Where("model_id = ?", modelID).Delete(&StageTransition{}).Error; err != nil {
			return err
		}
		if err := tx.Where("model_id = ?", modelID).Delete(&ModelVersion{}).Error; err != nil {
			return err
		}
		if err := tx.Where("id = ?", modelID).Delete(&Model{}).Error; err != nil {
			return err
		}
		return nil
	})
}

func (r *ModelRegistry) HealthCheck(ctx context.Context) error {
	var count int64
	if err := r.db.DB.WithContext(ctx).Model(&Model{}).Count(&count).Error; err != nil {
		return fmt.Errorf("model registry health check failed: %w", err)
	}
	return nil
}

func isValidStageTransition(from, to ModelStage) bool {
	validTransitions := map[ModelStage][]ModelStage{
		"": {StageStaging},
		StageStaging:     {StageTesting, StageArchived},
		StageTesting:     {StageProduction, StageStaging, StageArchived},
		StageProduction:  {StageTesting, StageDeprecated, StageArchived},
		StageDeprecated:  {StageProduction, StageArchived},
		StageArchived:    {},
	}

	validTargets, exists := validTransitions[from]
	if !exists {
		return false
	}

	for _, target := range validTargets {
		if target == to {
			return true
		}
	}
	return false
}

func (r *ModelRegistry) ValidateStageTransition(ctx context.Context, versionID string, toStage ModelStage) error {
	var version ModelVersion
	if err := r.db.DB.WithContext(ctx).Where("id = ?", versionID).First(&version).Error; err != nil {
		return err
	}

	if !isValidStageTransition(version.Stage, toStage) {
		return fmt.Errorf("invalid transition from %s to %s", version.Stage, toStage)
	}

	if toStage == StageProduction && version.EvalResults == nil {
		return errors.New("production deployment requires evaluation results")
	}

	return nil
}

func (r *ModelRegistry) GetStats(ctx context.Context) map[string]interface{} {
	var totalModels int64
	var totalVersions int64

	r.db.DB.Model(&Model{}).Count(&totalModels)
	r.db.DB.Model(&ModelVersion{}).Count(&totalVersions)

	type stageCount struct {
		Stage ModelStage `json:"stage"`
		Count int64      `json:"count"`
	}
	var stageCounts []stageCount
	r.db.DB.Model(&ModelVersion{}).
		Select("stage, COUNT(*) as count").
		Group("stage").
		Scan(&stageCounts)

	stats := make(map[string]interface{})
	stats["total_models"] = totalModels
	stats["total_versions"] = totalVersions

	stageStats := make(map[string]int64)
	for _, sc := range stageCounts {
		stageStats[string(sc.Stage)] = sc.Count
	}
	stats["versions_by_stage"] = stageStats

	return stats
}
