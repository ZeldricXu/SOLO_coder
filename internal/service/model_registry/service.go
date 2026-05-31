package model_registry

import (
	"errors"
	"fmt"

	"gorm.io/gorm"

	"llmgateway/internal/domain/entity"
	"llmgateway/internal/infrastructure/database"
	"llmgateway/internal/infrastructure/logger"
	"llmgateway/pkg/utils"
)

type Service struct {
	db *gorm.DB
}

func NewService() *Service {
	return &Service{
		db: database.DB(),
	}
}

type CreateModelRequest struct {
	Name         string                 `json:"name" binding:"required"`
	Description  string                 `json:"description"`
	Provider     string                 `json:"provider" binding:"required"`
	ModelType    string                 `json:"model_type"`
	MaxTokens    int                    `json:"max_tokens"`
	Capabilities []string               `json:"capabilities"`
	Metadata     map[string]interface{} `json:"metadata"`
}

func (s *Service) CreateModel(req *CreateModelRequest, createdBy string) (*entity.Model, error) {
	var existing entity.Model
	err := s.db.Where("name = ?", req.Name).First(&existing).Error
	if err == nil {
		return nil, errors.New("model with this name already exists")
	}
	if !errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, fmt.Errorf("failed to check existing model: %w", err)
	}

	now := utils.Now()
	model := &entity.Model{
		BaseEntity: entity.BaseEntity{
			ID:        utils.GenerateID("model"),
			Type:      "model",
			Status:    string(entity.StatusReady),
			CreatedAt: now,
			UpdatedAt: now,
		},
		Name:         req.Name,
		Description:  req.Description,
		Provider:     req.Provider,
		ModelType:    req.ModelType,
		MaxTokens:    req.MaxTokens,
		Capabilities: req.Capabilities,
		Metadata:     req.Metadata,
	}

	if err := s.db.Create(model).Error; err != nil {
		return nil, fmt.Errorf("failed to create model: %w", err)
	}

	logger.Info("model created", "model_id", model.ID, "name", model.Name)
	return model, nil
}

func (s *Service) GetModel(id string) (*entity.Model, error) {
	var model entity.Model
	if err := s.db.Where("id = ?", id).First(&model).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("model not found")
		}
		return nil, fmt.Errorf("failed to get model: %w", err)
	}
	return &model, nil
}

func (s *Service) ListModels(page, pageSize int, provider, modelType string) ([]entity.Model, int64, error) {
	var models []entity.Model
	var total int64

	query := s.db.Model(&entity.Model{})

	if provider != "" {
		query = query.Where("provider = ?", provider)
	}
	if modelType != "" {
		query = query.Where("model_type = ?", modelType)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to count models: %w", err)
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Find(&models).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to list models: %w", err)
	}

	return models, total, nil
}

type UpdateModelRequest struct {
	Description  *string                `json:"description"`
	Provider     *string                `json:"provider"`
	ModelType    *string                `json:"model_type"`
	MaxTokens    *int                   `json:"max_tokens"`
	Capabilities []string               `json:"capabilities"`
	Metadata     map[string]interface{} `json:"metadata"`
	Status       *string                `json:"status"`
}

func (s *Service) UpdateModel(id string, req *UpdateModelRequest) (*entity.Model, error) {
	model, err := s.GetModel(id)
	if err != nil {
		return nil, err
	}

	updates := make(map[string]interface{})
	if req.Description != nil {
		updates["description"] = *req.Description
	}
	if req.Provider != nil {
		updates["provider"] = *req.Provider
	}
	if req.ModelType != nil {
		updates["model_type"] = *req.ModelType
	}
	if req.MaxTokens != nil {
		updates["max_tokens"] = *req.MaxTokens
	}
	if req.Capabilities != nil {
		updates["capabilities"] = req.Capabilities
	}
	if req.Metadata != nil {
		updates["metadata"] = req.Metadata
	}
	if req.Status != nil {
		updates["status"] = *req.Status
	}
	updates["updated_at"] = utils.Now()

	if err := s.db.Model(model).Updates(updates).Error; err != nil {
		return nil, fmt.Errorf("failed to update model: %w", err)
	}

	return s.GetModel(id)
}

func (s *Service) DeleteModel(id string) error {
	result := s.db.Delete(&entity.Model{}, "id = ?", id)
	if result.Error != nil {
		return fmt.Errorf("failed to delete model: %w", result.Error)
	}
	if result.RowsAffected == 0 {
		return errors.New("model not found")
	}
	return nil
}

type CreateVersionRequest struct {
	ModelID        string                 `json:"model_id" binding:"required"`
	Version        string                 `json:"version" binding:"required"`
	Checksum       string                 `json:"checksum"`
	Size           int64                  `json:"size"`
	TrainingParams map[string]interface{} `json:"training_params"`
	Artifacts      map[string]string      `json:"artifacts"`
	CreatedBy      string                 `json:"-"`
}

func (s *Service) CreateModelVersion(req *CreateVersionRequest) (*entity.ModelVersion, error) {
	if _, err := s.GetModel(req.ModelID); err != nil {
		return nil, err
	}

	var existing entity.ModelVersion
	err := s.db.Where("model_id = ? AND version = ?", req.ModelID, req.Version).First(&existing).Error
	if err == nil {
		return nil, errors.New("version already exists for this model")
	}
	if !errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, fmt.Errorf("failed to check existing version: %w", err)
	}

	now := utils.Now()
	version := &entity.ModelVersion{
		ID:             utils.GenerateID("mv"),
		ModelID:        req.ModelID,
		Version:        req.Version,
		Stage:          string(entity.StageStaging),
		Status:         string(entity.StatusReady),
		Checksum:       req.Checksum,
		Size:           req.Size,
		TrainingParams: req.TrainingParams,
		Artifacts:      req.Artifacts,
		CreatedBy:      req.CreatedBy,
		CreatedAt:      now,
		UpdatedAt:      now,
	}

	if err := s.db.Create(version).Error; err != nil {
		return nil, fmt.Errorf("failed to create model version: %w", err)
	}

	logger.Info("model version created", "version_id", version.ID, "model_id", req.ModelID, "version", req.Version)
	return version, nil
}

func (s *Service) GetModelVersion(id string) (*entity.ModelVersion, error) {
	var version entity.ModelVersion
	if err := s.db.Where("id = ?", id).First(&version).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("model version not found")
		}
		return nil, fmt.Errorf("failed to get model version: %w", err)
	}
	return &version, nil
}

func (s *Service) ListModelVersions(modelID string, page, pageSize int, stage string) ([]entity.ModelVersion, int64, error) {
	var versions []entity.ModelVersion
	var total int64

	query := s.db.Model(&entity.ModelVersion{}).Where("model_id = ?", modelID)

	if stage != "" {
		query = query.Where("stage = ?", stage)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to count versions: %w", err)
	}

	offset := (page - 1) * pageSize
	if err := query.Order("created_at DESC").Offset(offset).Limit(pageSize).Find(&versions).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to list versions: %w", err)
	}

	return versions, total, nil
}

func (s *Service) PromoteVersion(id string, targetStage entity.ModelStage) (*entity.ModelVersion, error) {
	version, err := s.GetModelVersion(id)
	if err != nil {
		return nil, err
	}

	now := utils.Now()
	updates := map[string]interface{}{
		"stage":      string(targetStage),
		"updated_at": now,
	}

	if targetStage == entity.StageProduction {
		updates["promoted_at"] = now
	} else if targetStage == entity.StageArchived {
		updates["retired_at"] = now
	}

	if err := s.db.Model(version).Updates(updates).Error; err != nil {
		return nil, fmt.Errorf("failed to promote version: %w", err)
	}

	logger.Info("model version stage updated", "version_id", id, "stage", targetStage)
	return s.GetModelVersion(id)
}

func (s *Service) TransitionStage(id string, fromStage, toStage entity.ModelStage) (*entity.ModelVersion, error) {
	version, err := s.GetModelVersion(id)
	if err != nil {
		return nil, err
	}

	if version.Stage != string(fromStage) {
		return nil, fmt.Errorf("invalid stage transition: current stage is %s, expected %s", version.Stage, fromStage)
	}

	if !isValidTransition(fromStage, toStage) {
		return nil, fmt.Errorf("invalid stage transition from %s to %s", fromStage, toStage)
	}

	return s.PromoteVersion(id, toStage)
}

func isValidTransition(from, to entity.ModelStage) bool {
	transitions := map[entity.ModelStage][]entity.ModelStage{
		entity.StageStaging:    {entity.StageProduction, entity.StageArchived},
		entity.StageProduction: {entity.StageStaging, entity.StageArchived},
		entity.StageArchived:   {},
	}

	for _, valid := range transitions[from] {
		if valid == to {
			return true
		}
	}
	return false
}
