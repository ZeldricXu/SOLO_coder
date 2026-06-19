package repository

import (
	"context"

	"github.com/enterprise/knowledgebase/internal/database"
	"gorm.io/gorm"
)

type BaseRepo struct {
	DB *gorm.DB
}

func NewBaseRepo(db *gorm.DB) *BaseRepo {
	return &BaseRepo{DB: db}
}

func (r *BaseRepo) WithTenant(ctx context.Context) *gorm.DB {
	return r.DB.Scopes(database.TenantScope(ctx)).WithContext(ctx)
}

func (r *BaseRepo) Paginate(page, pageSize int) func(db *gorm.DB) *gorm.DB {
	return func(db *gorm.DB) *gorm.DB {
		if page <= 0 {
			page = 1
		}
		if pageSize <= 0 {
			pageSize = 20
		}
		if pageSize > 100 {
			pageSize = 100
		}
		offset := (page - 1) * pageSize
		return db.Offset(offset).Limit(pageSize)
	}
}
