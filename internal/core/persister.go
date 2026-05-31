package core

import (
	"context"
	"errors"
	"time"

	"gorm.io/gorm"

	"github.com/solocoder/task-scheduler/internal/database"
	"github.com/solocoder/task-scheduler/internal/models"
)

type ResultPersister struct {
	db *database.Database
}

func NewResultPersister(db *database.Database) *ResultPersister {
	return &ResultPersister{db: db}
}

func (p *ResultPersister) Persist(ctx context.Context, result map[string]interface{}, entityID string) error {
	return p.db.Transaction(ctx, func(tx *gorm.DB) error {
		var entity models.CoreEntity
		if err := tx.Where("id = ?", entityID).First(&entity).Error; err != nil {
			if errors.Is(err, gorm.ErrRecordNotFound) {
				entity = models.CoreEntity{
					ID:         entityID,
					Type:       "task",
					Status:     models.EntityStatusCompleted,
					Attributes: result,
					CreatedAt:  time.Now(),
					UpdatedAt:  time.Now(),
				}
				return tx.Create(&entity).Error
			}
			return err
		}

		entity.Status = models.EntityStatusCompleted
		entity.Attributes = result
		entity.UpdatedAt = time.Now()
		return tx.Save(&entity).Error
	})
}
