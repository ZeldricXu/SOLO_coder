package config

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	appErr "session133/pkg/errors"
	"session133/pkg/utils"
)

type ConfigService struct {
	db     *gorm.DB
	logger *zap.Logger
}

func NewConfigService(db *gorm.DB, logger *zap.Logger) *ConfigService {
	return &ConfigService{
		db:     db,
		logger: logger,
	}
}

func (s *ConfigService) CreateConfig(ctx context.Context, req *CreateConfigRequest, userID string) (*Config, error) {
	var maxVersion int
	err := s.db.Model(&Config{}).
		Where("config_key = ? AND namespace = ?", req.ConfigKey, req.Namespace).
		Select("COALESCE(MAX(version), 0)").
		Scan(&maxVersion).Error
	if err != nil {
		return nil, appErr.Internal(err.Error())
	}

	now := time.Now()
	config := &Config{
		ID:          utils.GenerateID("cfg"),
		ConfigKey:   req.ConfigKey,
		Namespace:   req.Namespace,
		Version:     maxVersion + 1,
		Value:       req.Value,
		Description: req.Description,
		Status:      ConfigStatusDraft,
		Labels:      req.Labels,
		CreatedBy:   userID,
		CreatedAt:   now,
		UpdatedAt:   now,
	}

	if err := s.db.WithContext(ctx).Create(config).Error; err != nil {
		return nil, appErr.Internal(err.Error())
	}

	return config, nil
}

func (s *ConfigService) GetConfig(ctx context.Context, configID string) (*Config, error) {
	config := &Config{}
	if err := s.db.WithContext(ctx).Where("id = ?", configID).First(config).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, appErr.NotFound("配置")
		}
		return nil, appErr.Internal(err.Error())
	}
	return config, nil
}

func (s *ConfigService) GetPublishedConfig(ctx context.Context, configKey, namespace string) (*Config, error) {
	config := &Config{}
	err := s.db.WithContext(ctx).
		Where("config_key = ? AND namespace = ? AND status = ?", configKey, namespace, ConfigStatusPublished).
		Order("version DESC").
		First(config).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, appErr.NotFound("已发布的配置")
		}
		return nil, appErr.Internal(err.Error())
	}
	return config, nil
}

func (s *ConfigService) GetConfigByVersion(ctx context.Context, configKey, namespace string, version int) (*Config, error) {
	config := &Config{}
	err := s.db.WithContext(ctx).
		Where("config_key = ? AND namespace = ? AND version = ?", configKey, namespace, version).
		First(config).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, appErr.NotFound(fmt.Sprintf("配置版本 %d", version))
		}
		return nil, appErr.Internal(err.Error())
	}
	return config, nil
}

func (s *ConfigService) ListConfigs(ctx context.Context, namespace string, page, pageSize int) ([]Config, int64, error) {
	var configs []Config
	var total int64

	subQuery := s.db.Model(&Config{}).
		Select("MAX(version) as max_version, config_key, namespace").
		Group("config_key, namespace")

	if namespace != "" {
		subQuery = subQuery.Where("namespace = ?", namespace)
	}

	query := s.db.WithContext(ctx).Model(&Config{}).
		Joins("JOIN (?) AS latest ON configs.config_key = latest.config_key AND configs.namespace = latest.namespace AND configs.version = latest.max_version", subQuery)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&configs).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	return configs, total, nil
}

func (s *ConfigService) ListConfigVersions(ctx context.Context, configKey, namespace string, page, pageSize int) ([]Config, int64, error) {
	var configs []Config
	var total int64

	query := s.db.WithContext(ctx).Model(&Config{}).
		Where("config_key = ? AND namespace = ?", configKey, namespace)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("version DESC").Find(&configs).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	return configs, total, nil
}

func (s *ConfigService) UpdateConfig(ctx context.Context, configID string, req *UpdateConfigRequest, userID string) (*Config, error) {
	existing, err := s.GetConfig(ctx, configID)
	if err != nil {
		return nil, err
	}

	now := time.Now()
	newConfig := &Config{
		ID:          utils.GenerateID("cfg"),
		ConfigKey:   existing.ConfigKey,
		Namespace:   existing.Namespace,
		Version:     existing.Version + 1,
		Value:       req.Value,
		Description: req.Description,
		Status:      ConfigStatusDraft,
		Labels:      req.Labels,
		CreatedBy:   userID,
		CreatedAt:   now,
		UpdatedAt:   now,
	}

	if err := s.db.WithContext(ctx).Create(newConfig).Error; err != nil {
		return nil, appErr.Internal(err.Error())
	}

	return newConfig, nil
}

func (s *ConfigService) PublishConfig(ctx context.Context, configID string, req *PublishConfigRequest) (*Config, error) {
	config, err := s.GetConfig(ctx, configID)
	if err != nil {
		return nil, err
	}

	if config.Status == ConfigStatusPublished {
		return nil, appErr.Conflict("配置已发布")
	}

	now := time.Now()
	err = s.db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&Config{}).
			Where("config_key = ? AND namespace = ? AND status = ?", config.ConfigKey, config.Namespace, ConfigStatusPublished).
			Updates(map[string]interface{}{
				"status":     ConfigStatusArchived,
				"updated_at": now,
			}).Error; err != nil {
			return err
		}

		if err := tx.Model(config).Updates(map[string]interface{}{
			"status":       ConfigStatusPublished,
			"approved_by":  req.ApprovedBy,
			"published_at": now,
			"updated_at":   now,
		}).Error; err != nil {
			return err
		}

		snapshot := &ConfigSnapshot{
			ID:         utils.GenerateID("snap"),
			ConfigID:   config.ID,
			ConfigKey:  config.ConfigKey,
			Namespace:  config.Namespace,
			Version:    config.Version,
			Value:      config.Value,
			Labels:     config.Labels,
			SnapshotAt: now,
			CreatedBy:  req.ApprovedBy,
		}
		return tx.Create(snapshot).Error
	})

	if err != nil {
		return nil, appErr.Internal(err.Error())
	}

	return s.GetConfig(ctx, configID)
}

func (s *ConfigService) RollbackConfig(ctx context.Context, configID string, req *RollbackConfigRequest, userID string) (*Config, error) {
	current, err := s.GetConfig(ctx, configID)
	if err != nil {
		return nil, err
	}

	target, err := s.GetConfigByVersion(ctx, current.ConfigKey, current.Namespace, req.TargetVersion)
	if err != nil {
		return nil, err
	}

	now := time.Now()
	var newConfig *Config

	err = s.db.Transaction(func(tx *gorm.DB) error {
		rollbackHistory := &RollbackHistory{
			ID:           utils.GenerateID("rb"),
			ConfigID:     configID,
			FromVersion:  current.Version,
			ToVersion:    req.TargetVersion,
			Reason:       req.Reason,
			RolledBackBy: userID,
			RolledBackAt: now,
		}
		if err := tx.Create(rollbackHistory).Error; err != nil {
			return err
		}

		newConfig = &Config{
			ID:          utils.GenerateID("cfg"),
			ConfigKey:   current.ConfigKey,
			Namespace:   current.Namespace,
			Version:     current.Version + 1,
			Value:       target.Value,
			Description: fmt.Sprintf("回滚到版本 %d: %s", req.TargetVersion, req.Reason),
			Status:      ConfigStatusRolledBack,
			Labels:      target.Labels,
			CreatedBy:   userID,
			CreatedAt:   now,
			UpdatedAt:   now,
		}
		if err := tx.Create(newConfig).Error; err != nil {
			return err
		}

		return tx.Model(&Config{}).
			Where("config_key = ? AND namespace = ? AND status = ?", current.ConfigKey, current.Namespace, ConfigStatusPublished).
			Updates(map[string]interface{}{
				"status":     ConfigStatusArchived,
				"updated_at": now,
			}).Error
	})

	if err != nil {
		return nil, appErr.Internal(err.Error())
	}

	return newConfig, nil
}

func (s *ConfigService) DeleteConfig(ctx context.Context, configID string) error {
	_, err := s.GetConfig(ctx, configID)
	if err != nil {
		return err
	}

	if err := s.db.WithContext(ctx).Where("id = ?", configID).Delete(&Config{}).Error; err != nil {
		return appErr.Internal(err.Error())
	}
	return nil
}

func (s *ConfigService) DiffConfigs(ctx context.Context, configKey, namespace string, version1, version2 int) (*DiffResult, error) {
	cfg1, err := s.GetConfigByVersion(ctx, configKey, namespace, version1)
	if err != nil {
		return nil, err
	}

	cfg2, err := s.GetConfigByVersion(ctx, configKey, namespace, version2)
	if err != nil {
		return nil, err
	}

	return s.diffMaps(cfg1.Value, cfg2.Value), nil
}

func (s *ConfigService) diffMaps(oldMap, newMap map[string]interface{}) *DiffResult {
	result := &DiffResult{
		Added:    make(map[string]interface{}),
		Removed:  make(map[string]interface{}),
		Modified: make(map[string]DiffItem),
	}

	for key, oldVal := range oldMap {
		if newVal, exists := newMap[key]; exists {
			if !reflect.DeepEqual(oldVal, newVal) {
				result.Modified[key] = DiffItem{
					OldValue: oldVal,
					NewValue: newVal,
				}
			}
		} else {
			result.Removed[key] = oldVal
		}
	}

	for key, newVal := range newMap {
		if _, exists := oldMap[key]; !exists {
			result.Added[key] = newVal
		}
	}

	return result
}

func (s *ConfigService) GetRollbackHistory(ctx context.Context, configID string) ([]RollbackHistory, error) {
	var history []RollbackHistory
	if err := s.db.WithContext(ctx).Where("config_id = ?", configID).Order("rolled_back_at DESC").Find(&history).Error; err != nil {
		return nil, appErr.Internal(err.Error())
	}
	return history, nil
}

func (s *ConfigService) GetConfigValue(ctx context.Context, configKey, namespace string, key string) (interface{}, error) {
	config, err := s.GetPublishedConfig(ctx, configKey, namespace)
	if err != nil {
		return nil, err
	}

	value, exists := config.Value[key]
	if !exists {
		return nil, appErr.NotFound(fmt.Sprintf("配置项 %s", key))
	}

	return value, nil
}
