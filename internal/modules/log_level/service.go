package log_level

import (
	"context"
	"time"

	"loglevelplatform/internal/common/database"
	"loglevelplatform/internal/common/logger"
	"loglevelplatform/internal/common/models"
	"loglevelplatform/pkg/utils"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

type Service struct {
	db *gorm.DB
}

func NewService() *Service {
	return &Service{
		db: database.GetDB(),
	}
}

type SetLevelRequest struct {
	Namespace string `json:"namespace" binding:"required"`
	Component string `json:"component" binding:"required"`
	Level     string `json:"level" binding:"required,oneof=debug info warn error dpanic panic fatal"`
	UpdatedBy string `json:"updated_by"`
}

type GetLevelRequest struct {
	Namespace string `form:"namespace"`
	Component string `form:"component"`
}

func (s *Service) SetLogLevel(ctx context.Context, req *SetLevelRequest) (*models.LogLevelConfig, error) {
	log := logger.FromContext(ctx)

	if err := logger.SetComponentLevel(req.Component, req.Level); err != nil {
		log.Error("failed to set log level", zap.Error(err), zap.String("component", req.Component))
		return nil, err
	}

	config := &models.LogLevelConfig{
		ID:        utils.NewID("log"),
		Namespace: req.Namespace,
		Component: req.Component,
		Level:     req.Level,
		UpdatedAt: time.Now(),
		UpdatedBy: req.UpdatedBy,
	}

	var existing models.LogLevelConfig
	err := s.db.Where("namespace = ? AND component = ?", req.Namespace, req.Component).First(&existing).Error
	if err == nil {
		config.ID = existing.ID
		if err := s.db.Save(config).Error; err != nil {
			log.Error("failed to update log level config", zap.Error(err))
			return nil, err
		}
	} else {
		if err := s.db.Create(config).Error; err != nil {
			log.Error("failed to create log level config", zap.Error(err))
			return nil, err
		}
	}

	log.Info("log level updated",
		zap.String("namespace", req.Namespace),
		zap.String("component", req.Component),
		zap.String("level", req.Level),
	)

	return config, nil
}

func (s *Service) GetLogLevel(ctx context.Context, req *GetLevelRequest) (interface{}, error) {
	log := logger.FromContext(ctx)

	if req.Component != "" {
		level := logger.GetComponentLevel(req.Component)
		return map[string]interface{}{
			"component": req.Component,
			"level":     level.String(),
		}, nil
	}

	levels := logger.GetAllComponentLevels()

	if req.Namespace != "" {
		var configs []models.LogLevelConfig
		if err := s.db.Where("namespace = ?", req.Namespace).Find(&configs).Error; err != nil {
			log.Error("failed to query log level configs", zap.Error(err))
			return nil, err
		}
		return configs, nil
	}

	return levels, nil
}

func (s *Service) GetAllConfigs(ctx context.Context) ([]models.LogLevelConfig, error) {
	var configs []models.LogLevelConfig
	if err := s.db.Find(&configs).Error; err != nil {
		return nil, err
	}
	return configs, nil
}

func (s *Service) DeleteConfig(ctx context.Context, id string) error {
	log := logger.FromContext(ctx)

	var config models.LogLevelConfig
	if err := s.db.Where("id = ?", id).First(&config).Error; err != nil {
		log.Error("log level config not found", zap.String("id", id))
		return err
	}

	if err := s.db.Delete(&config).Error; err != nil {
		log.Error("failed to delete log level config", zap.Error(err))
		return err
	}

	log.Info("log level config deleted", zap.String("id", id))
	return nil
}

func (s *Service) LoadConfigs(ctx context.Context) error {
	log := logger.FromContext(ctx)

	var configs []models.LogLevelConfig
	if err := s.db.Find(&configs).Error; err != nil {
		log.Error("failed to load log level configs", zap.Error(err))
		return err
	}

	for _, cfg := range configs {
		if err := logger.SetComponentLevel(cfg.Component, cfg.Level); err != nil {
			log.Warn("failed to set log level", zap.String("component", cfg.Component), zap.Error(err))
		}
	}

	log.Info("loaded log level configs", zap.Int("count", len(configs)))
	return nil
}
