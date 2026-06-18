package repository

import (
	"context"
	"errors"
	"time"

	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type SpaceRepository struct {
	db *gorm.DB
}

func NewSpaceRepository(db *gorm.DB) *SpaceRepository {
	return &SpaceRepository{db: db}
}

func (r *SpaceRepository) Create(ctx context.Context, space *model.Space) error {
	space.CreatedAt = time.Now().UTC()
	space.UpdatedAt = time.Now().UTC()
	if space.ID == uuid.Nil {
		space.ID = uuid.New()
	}
	err := r.db.WithContext(ctx).Create(space).Error
	if err != nil {
		if IsUniqueViolation(err) {
			return ErrAlreadyExists
		}
		return err
	}
	return nil
}

func (r *SpaceRepository) GetByID(ctx context.Context, id uuid.UUID) (*model.Space, error) {
	var space model.Space
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).First(&space, id).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &space, nil
}

func (r *SpaceRepository) GetByNamespace(ctx context.Context, tenantID uuid.UUID, namespace string) (*model.Space, error) {
	var space model.Space
	err := r.db.WithContext(ctx).
		Where("tenant_id = ? AND namespace = ?", tenantID, namespace).
		First(&space).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &space, nil
}

func (r *SpaceRepository) Update(ctx context.Context, space *model.Space) error {
	space.UpdatedAt = time.Now().UTC()
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Save(space)
	if result.Error != nil {
		if IsUniqueViolation(result.Error) {
			return ErrAlreadyExists
		}
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *SpaceRepository) Delete(ctx context.Context, id uuid.UUID) error {
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Delete(&model.Space{}, id)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *SpaceRepository) List(ctx context.Context, tenantID uuid.UUID, ids []uuid.UUID, status model.SpaceStatus, keyword string, page, pageSize int) (*database.PaginatedResult, error) {
	query := r.db.WithContext(ctx).Model(&model.Space{}).Where("tenant_id = ?", tenantID)
	if len(ids) > 0 {
		query = query.Where("id IN ?", ids)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}
	if keyword != "" {
		query = query.Where("(name ILIKE ? OR description ILIKE ? OR namespace ILIKE ?)",
			"%"+keyword+"%", "%"+keyword+"%", "%"+keyword+"%")
	}

	var spaces []model.Space
	pr, err := database.Paginate(query.Order("sort_order ASC, created_at DESC"),
		page, pageSize, &spaces)
	if err != nil {
		return nil, err
	}
	pr.Data = spaces
	return pr, nil
}

func (r *SpaceRepository) UpdateStatus(ctx context.Context, id uuid.UUID, status model.SpaceStatus) error {
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Model(&model.Space{}).
		Where("id = ?", id).
		UpdateColumns(map[string]interface{}{
			"status":     status,
			"updated_at": time.Now().UTC(),
		})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *SpaceRepository) IncrementDocCount(ctx context.Context, id uuid.UUID, delta int) error {
	return r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Model(&model.Space{}).
		Where("id = ?", id).
		UpdateColumn("document_count", gorm.Expr("document_count + ?", delta)).Error
}

func (r *SpaceRepository) IncrementStorage(ctx context.Context, id uuid.UUID, delta int64) error {
	return r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Model(&model.Space{}).
		Where("id = ?", id).
		UpdateColumn("storage_used", gorm.Expr("storage_used + ?", delta)).Error
}

func (r *SpaceRepository) CreateDirectory(ctx context.Context, dir *model.Directory) error {
	dir.CreatedAt = time.Now().UTC()
	dir.UpdatedAt = time.Now().UTC()
	if dir.ID == uuid.Nil {
		dir.ID = uuid.New()
	}
	return r.db.WithContext(ctx).Create(dir).Error
}

func (r *SpaceRepository) GetDirectory(ctx context.Context, id uuid.UUID) (*model.Directory, error) {
	var dir model.Directory
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).First(&dir, id).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &dir, nil
}

func (r *SpaceRepository) ListDirectories(ctx context.Context, spaceID uuid.UUID, parentID *uuid.UUID) ([]model.Directory, error) {
	var dirs []model.Directory
	query := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Where("space_id = ?", spaceID).
		Order("sort_order ASC, created_at ASC")
	if parentID == nil {
		query = query.Where("parent_id IS NULL")
	} else {
		query = query.Where("parent_id = ?", *parentID)
	}
	err := query.Find(&dirs).Error
	return dirs, err
}

func (r *SpaceRepository) ListAllDirectories(ctx context.Context, spaceID uuid.UUID) ([]model.Directory, error) {
	var dirs []model.Directory
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Where("space_id = ?", spaceID).
		Order("sort_order ASC, created_at ASC").
		Find(&dirs).Error
	return dirs, err
}

func (r *SpaceRepository) UpdateDirectory(ctx context.Context, dir *model.Directory) error {
	dir.UpdatedAt = time.Now().UTC()
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Save(dir)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *SpaceRepository) DeleteDirectory(ctx context.Context, id uuid.UUID) error {
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Delete(&model.Directory{}, id)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *SpaceRepository) IncrementDirectoryDocCount(ctx context.Context, id uuid.UUID, delta int) error {
	return r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Model(&model.Directory{}).
		Where("id = ?", id).
		UpdateColumn("document_count", gorm.Expr("document_count + ?", delta)).Error
}
