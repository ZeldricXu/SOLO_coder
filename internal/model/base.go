package model

import (
	"time"

	"gorm.io/gorm"
	"github.com/google/uuid"
)

type BaseModel struct {
	ID        string    `gorm:"type:uuid;primaryKey" json:"id"`
	CreatedAt time.Time `gorm:"not null;default:CURRENT_TIMESTAMP" json:"created_at"`
	UpdatedAt time.Time `gorm:"not null;default:CURRENT_TIMESTAMP" json:"updated_at"`
	DeletedAt gorm.DeletedAt `gorm:"index" json:"-"`
}

func (b *BaseModel) BeforeCreate(tx *gorm.DB) error {
	if b.ID == "" {
		b.ID = uuid.New().String()
	}
	return nil
}

type TenantScoped struct {
	TenantID string `gorm:"type:uuid;not null;index:idx_tenant_id" json:"tenant_id"`
}
