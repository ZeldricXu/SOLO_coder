package database

import (
	"context"
	"fmt"
	"time"

	"github.com/enterprise/knowledgebase/internal/config"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/google/uuid"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type DBContextKey string

const TenantIDKey DBContextKey = "tenant_id"

var globalDB *gorm.DB

func InitPostgreSQL(cfg config.PostgreSQLConfig) (*gorm.DB, error) {
	gormConfig := &gorm.Config{
		Logger: logger.Default.LogMode(logger.Warn),
		NowFunc: func() time.Time {
			return time.Now().UTC()
		},
		SkipDefaultTransaction: false,
	}

	db, err := gorm.Open(postgres.Open(cfg.DSN()), gormConfig)
	if err != nil {
		return nil, fmt.Errorf("connect postgresql: %w", err)
	}

	sqlDB, err := db.DB()
	if err != nil {
		return nil, fmt.Errorf("get sql DB: %w", err)
	}

	sqlDB.SetMaxOpenConns(cfg.MaxOpenConns)
	sqlDB.SetMaxIdleConns(cfg.MaxIdleConns)
	sqlDB.SetConnMaxLifetime(time.Hour)
	sqlDB.SetConnMaxIdleTime(10 * time.Minute)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := sqlDB.PingContext(ctx); err != nil {
		return nil, fmt.Errorf("ping postgresql: %w", err)
	}

	globalDB = db
	return db, nil
}

func AutoMigrate(db *gorm.DB) error {
	models := []interface{}{
		&model.Tenant{},
		&model.TenantTheme{},
		&model.TenantQuota{},
		&model.TenantCustomNav{},
		&model.Space{},
		&model.User{},
		&model.UserGroup{},
		&model.UserGroupMember{},
		&model.Department{},
		&model.Directory{},
		&model.Document{},
		&model.DocumentVersion{},
		&model.DocumentTemplate{},
		&model.Attachment{},
		&model.Permission{},
		&model.ApiToken{},
		&model.I18nDoc{},
		&model.TranslationMemory{},
	}

	for _, m := range models {
		if err := db.AutoMigrate(m); err != nil {
			return fmt.Errorf("auto migrate %T: %w", m, err)
		}
	}

	return nil
}

func GetDB() *gorm.DB {
	return globalDB
}

func WithTenant(ctx context.Context, tenantID uuid.UUID) context.Context {
	return context.WithValue(ctx, TenantIDKey, tenantID)
}

func GetTenantID(ctx context.Context) (uuid.UUID, bool) {
	id, ok := ctx.Value(TenantIDKey).(uuid.UUID)
	return id, ok
}

func TenantScope(ctx context.Context) func(db *gorm.DB) *gorm.DB {
	return func(db *gorm.DB) *gorm.DB {
		if tenantID, ok := GetTenantID(ctx); ok && tenantID != uuid.Nil {
			return db.Where("tenant_id = ?", tenantID)
		}
		return db
	}
}

func WithTenantDB(ctx context.Context) *gorm.DB {
	if globalDB == nil {
		return nil
	}
	return globalDB.Scopes(TenantScope(ctx)).WithContext(ctx)
}

type PaginatedResult struct {
	Total      int64       `json:"total"`
	Page       int         `json:"page"`
	PageSize   int         `json:"page_size"`
	TotalPages int         `json:"total_pages"`
	Data       interface{} `json:"data"`
}

func Paginate(db *gorm.DB, page, pageSize int, dest interface{}) (*PaginatedResult, error) {
	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 {
		pageSize = 20
	}
	if pageSize > 100 {
		pageSize = 100
	}

	var total int64
	if err := db.Count(&total).Error; err != nil {
		return nil, err
	}

	offset := (page - 1) * pageSize
	if err := db.Offset(offset).Limit(pageSize).Find(dest).Error; err != nil {
		return nil, err
	}

	totalPages := int(total) / pageSize
	if int(total)%pageSize > 0 {
		totalPages++
	}

	return &PaginatedResult{
		Total:      total,
		Page:       page,
		PageSize:   pageSize,
		TotalPages: totalPages,
		Data:       dest,
	}, nil
}
