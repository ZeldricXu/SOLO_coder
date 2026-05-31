package model

import (
	"context"
	"errors"
	"fmt"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	appErr "session133/pkg/errors"
	"session133/pkg/utils"
)

type ModelService struct {
	db     *gorm.DB
	logger *zap.Logger
}

func NewModelService(db *gorm.DB, logger *zap.Logger) *ModelService {
	return &ModelService{
		db:     db,
		logger: logger,
	}
}

func (s *ModelService) CreateModel(ctx context.Context, req *CreateModelRequest, userID string) (*Model, error) {
	existing := &Model{}
	err := s.db.Where("name = ? AND namespace = ?", req.Name, req.Namespace).First(existing).Error
	if err == nil {
		return nil, appErr.Conflict(fmt.Sprintf("模型 %s 已存在于命名空间 %s", req.Name, req.Namespace))
	}
	if !errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, appErr.Internal(err.Error())
	}

	now := time.Now()
	model := &Model{
		BaseEntity: BaseEntity{
			ID:         utils.GenerateID("model"),
			Type:       "model",
			Status:     "active",
			Attributes: map[string]string{"owner": userID},
			CreatedAt:  now,
			UpdatedAt:  now,
		},
		Name:        req.Name,
		Namespace:   req.Namespace,
		Description: req.Description,
		Framework:   req.Framework,
		Tags:        req.Tags,
		Owner:       userID,
	}

	if err := s.db.WithContext(ctx).Create(model).Error; err != nil {
		return nil, appErr.Internal(err.Error())
	}

	return model, nil
}

func (s *ModelService) GetModel(ctx context.Context, modelID string) (*Model, error) {
	model := &Model{}
	if err := s.db.WithContext(ctx).Where("id = ?", modelID).First(model).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, appErr.NotFound("模型")
		}
		return nil, appErr.Internal(err.Error())
	}
	return model, nil
}

func (s *ModelService) ListModels(ctx context.Context, namespace string, page, pageSize int) ([]Model, int64, error) {
	var models []Model
	var total int64

	query := s.db.WithContext(ctx).Model(&Model{})
	if namespace != "" {
		query = query.Where("namespace = ?", namespace)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&models).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	return models, total, nil
}

func (s *ModelService) CreateVersion(ctx context.Context, modelID string, req *CreateVersionRequest, userID string) (*ModelVersion, error) {
	if _, err := s.GetModel(ctx, modelID); err != nil {
		return nil, err
	}

	existing := &ModelVersion{}
	err := s.db.Where("model_id = ? AND version = ?", modelID, req.Version).First(existing).Error
	if err == nil {
		return nil, appErr.Conflict(fmt.Sprintf("版本 %s 已存在", req.Version))
	}
	if !errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, appErr.Internal(err.Error())
	}

	var latestVersion *ModelVersion
	var previousVersionID string
	err = s.db.Where("model_id = ?", modelID).Order("created_at DESC").First(&latestVersion).Error
	if err == nil {
		previousVersionID = latestVersion.ID
	}

	now := time.Now()
	version := &ModelVersion{
		ID:                utils.GenerateID("ver"),
		ModelID:           modelID,
		Version:           req.Version,
		Stage:             StageDevelopment,
		Checksum:          req.Checksum,
		SizeBytes:         req.SizeBytes,
		Metadata:          req.Metadata,
		TrainingData:      req.TrainingData,
		Metrics:           req.Metrics,
		ArtifactsURI:      req.ArtifactsURI,
		Description:       req.Description,
		CreatedBy:         userID,
		PreviousVersionID: previousVersionID,
		CreatedAt:         now,
		UpdatedAt:         now,
	}

	err = s.db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(version).Error; err != nil {
			return err
		}

		if previousVersionID != "" {
			if err := tx.Model(&ModelVersion{}).Where("id = ?", previousVersionID).Update("next_version_id", version.ID).Error; err != nil {
				return err
			}
		}

		return tx.Model(&Model{}).Where("id = ?", modelID).Updates(map[string]interface{}{
			"latest_version_id": version.ID,
			"updated_at":        now,
		}).Error
	})

	if err != nil {
		return nil, appErr.Internal(err.Error())
	}

	return version, nil
}

func (s *ModelService) GetVersion(ctx context.Context, versionID string) (*ModelVersion, error) {
	version := &ModelVersion{}
	if err := s.db.WithContext(ctx).Where("id = ?", versionID).First(version).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, appErr.NotFound("模型版本")
		}
		return nil, appErr.Internal(err.Error())
	}
	return version, nil
}

func (s *ModelService) ListVersions(ctx context.Context, modelID string, page, pageSize int) ([]ModelVersion, int64, error) {
	var versions []ModelVersion
	var total int64

	query := s.db.WithContext(ctx).Model(&ModelVersion{}).Where("model_id = ?", modelID)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&versions).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	return versions, total, nil
}

func (s *ModelService) TransitionStage(ctx context.Context, versionID string, req *StageTransitionRequest) (*ModelVersion, error) {
	version, err := s.GetVersion(ctx, versionID)
	if err != nil {
		return nil, err
	}

	if !isValidStageTransition(version.Stage, req.TargetStage) {
		return nil, appErr.InvalidParams(fmt.Sprintf("不支持从 %s 转换到 %s", version.Stage, req.TargetStage))
	}

	now := time.Now()
	transition := &StageTransition{
		ID:              utils.GenerateID("trans"),
		VersionID:       versionID,
		FromStage:       version.Stage,
		ToStage:         req.TargetStage,
		Reason:          req.Reason,
		ApprovedBy:      req.ApprovedBy,
		RollbackAllowed: true,
		CreatedAt:       now,
	}

	err = s.db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(transition).Error; err != nil {
			return err
		}
		return tx.Model(version).Updates(map[string]interface{}{
			"stage":      req.TargetStage,
			"updated_at": now,
		}).Error
	})

	if err != nil {
		return nil, appErr.Internal(err.Error())
	}

	version.Stage = req.TargetStage
	return version, nil
}

func isValidStageTransition(from, to ModelStage) bool {
	validTransitions := map[ModelStage][]ModelStage{
		StageDevelopment: {StageStaging, StageArchived},
		StageStaging:     {StageDevelopment, StageProduction, StageArchived},
		StageProduction:  {StageStaging, StageArchived},
		StageArchived:    {StageDevelopment},
	}

	validTargets, ok := validTransitions[from]
	if !ok {
		return false
	}

	for _, target := range validTargets {
		if target == to {
			return true
		}
	}
	return false
}

func (s *ModelService) GetVersionHistory(ctx context.Context, versionID string) ([]StageTransition, error) {
	var transitions []StageTransition
	if err := s.db.WithContext(ctx).Where("version_id = ?", versionID).Order("created_at ASC").Find(&transitions).Error; err != nil {
		return nil, appErr.Internal(err.Error())
	}
	return transitions, nil
}

func (s *ModelService) DeleteModel(ctx context.Context, modelID string) error {
	_, err := s.GetModel(ctx, modelID)
	if err != nil {
		return err
	}

	err = s.db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Where("model_id = ?", modelID).Delete(&ModelVersion{}).Error; err != nil {
			return err
		}
		if err := tx.Where("id = ?", modelID).Delete(&Model{}).Error; err != nil {
			return err
		}
		return nil
	})

	if err != nil {
		return appErr.Internal(err.Error())
	}
	return nil
}
