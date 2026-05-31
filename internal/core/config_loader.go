package core

import (
	"context"
	"errors"
	"sync"
	"time"

	"gorm.io/gorm"

	"github.com/solocoder/task-scheduler/internal/database"
	"github.com/solocoder/task-scheduler/internal/models"
)

type ConfigLoader struct {
	db          *database.Database
	configCache map[string]*models.ConfigDefinition
	cacheMu     sync.RWMutex
}

func NewConfigLoader(db *database.Database) *ConfigLoader {
	return &ConfigLoader{
		db:          db,
		configCache: make(map[string]*models.ConfigDefinition),
	}
}

func (l *ConfigLoader) LoadConfig(ctx context.Context, namespace string) (*models.ConfigDefinition, error) {
	l.cacheMu.RLock()
	if cfg, ok := l.configCache[namespace]; ok {
		l.cacheMu.RUnlock()
		return cfg, nil
	}
	l.cacheMu.RUnlock()

	var config models.ConfigDefinition
	err := l.db.DB.WithContext(ctx).
		Where("namespace = ? AND enabled = ?", namespace, true).
		Order("version DESC").
		First(&config).Error

	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			defaultCfg := &models.ConfigDefinition{
				ConfigID:  "cfg_default_" + namespace,
				Namespace: namespace,
				Version:   1,
				Parameters: map[string]interface{}{
					"timeout": 30,
					"retries": 3,
				},
				Enabled:   true,
				AppliedAt: time.Now(),
				CreatedAt: time.Now(),
				UpdatedAt: time.Now(),
			}
			l.cacheMu.Lock()
			l.configCache[namespace] = defaultCfg
			l.cacheMu.Unlock()
			return defaultCfg, nil
		}
		return nil, err
	}

	l.cacheMu.Lock()
	l.configCache[namespace] = &config
	l.cacheMu.Unlock()

	return &config, nil
}
