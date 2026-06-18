package repository

import (
	"context"
	"errors"
	"time"

	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

var (
	ErrDirectoryNotFound = errors.New("directory not found")
)

type DirectoryRepository interface {
	Create(ctx context.Context, dir *model.Directory) error
	Update(ctx context.Context, dir *model.Directory) error
	Delete(ctx context.Context, tenantID, id uuid.UUID) error
	GetByID(ctx context.Context, tenantID, id uuid.UUID) (*model.Directory, error)
	ListBySpace(ctx context.Context, tenantID, spaceID uuid.UUID) ([]*model.Directory, error)
	ListByParent(ctx context.Context, tenantID, spaceID uuid.UUID, parentID *uuid.UUID) ([]*model.Directory, error)
	GetTree(ctx context.Context, tenantID, spaceID uuid.UUID) ([]*model.Directory, error)
}

type gormDirectoryRepository struct {
	db *gorm.DB
}

func NewDirectoryRepository(db *gorm.DB) DirectoryRepository {
	return &gormDirectoryRepository{db: db}
}

func (r *gormDirectoryRepository) Create(ctx context.Context, dir *model.Directory) error {
	if dir.CreatedAt.IsZero() {
		dir.CreatedAt = time.Now()
	}
	dir.UpdatedAt = time.Now()
	return r.db.WithContext(ctx).Create(dir).Error
}

func (r *gormDirectoryRepository) Update(ctx context.Context, dir *model.Directory) error {
	dir.UpdatedAt = time.Now()
	result := r.db.WithContext(ctx).Model(&model.Directory{}).
		Where("id = ? AND tenant_id = ?", dir.ID, dir.TenantID).
		Updates(map[string]interface{}{
			"name":           dir.Name,
			"description":    dir.Description,
			"icon":           dir.Icon,
			"color":          dir.Color,
			"parent_id":      dir.ParentID,
			"sort_order":     dir.SortOrder,
			"document_count": dir.DocumentCount,
			"updated_at":     time.Now(),
		})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrDirectoryNotFound
	}
	return nil
}

func (r *gormDirectoryRepository) Delete(ctx context.Context, tenantID, id uuid.UUID) error {
	result := r.db.WithContext(ctx).Where("id = ? AND tenant_id = ?", id, tenantID).Delete(&model.Directory{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrDirectoryNotFound
	}
	return nil
}

func (r *gormDirectoryRepository) GetByID(ctx context.Context, tenantID, id uuid.UUID) (*model.Directory, error) {
	var dir model.Directory
	result := r.db.WithContext(ctx).Where("id = ? AND tenant_id = ?", id, tenantID).First(&dir)
	if result.Error != nil {
		if errors.Is(result.Error, gorm.ErrRecordNotFound) {
			return nil, ErrDirectoryNotFound
		}
		return nil, result.Error
	}
	return &dir, nil
}

func (r *gormDirectoryRepository) ListBySpace(ctx context.Context, tenantID, spaceID uuid.UUID) ([]*model.Directory, error) {
	var dirs []*model.Directory
	err := r.db.WithContext(ctx).
		Where("tenant_id = ? AND space_id = ?", tenantID, spaceID).
		Order("sort_order ASC, created_at DESC").
		Find(&dirs).Error
	return dirs, err
}

func (r *gormDirectoryRepository) ListByParent(ctx context.Context, tenantID, spaceID uuid.UUID, parentID *uuid.UUID) ([]*model.Directory, error) {
	var dirs []*model.Directory
	query := r.db.WithContext(ctx).
		Where("tenant_id = ? AND space_id = ?", tenantID, spaceID)

	if parentID == nil {
		query = query.Where("parent_id IS NULL")
	} else {
		query = query.Where("parent_id = ?", *parentID)
	}

	err := query.Order("sort_order ASC, created_at DESC").Find(&dirs).Error
	return dirs, err
}

func (r *gormDirectoryRepository) GetTree(ctx context.Context, tenantID, spaceID uuid.UUID) ([]*model.Directory, error) {
	var allDirs []*model.Directory
	err := r.db.WithContext(ctx).
		Where("tenant_id = ? AND space_id = ?", tenantID, spaceID).
		Order("sort_order ASC, created_at DESC").
		Find(&allDirs).Error
	if err != nil {
		return nil, err
	}

	dirMap := make(map[uuid.UUID]*model.Directory)
	for _, dir := range allDirs {
		dirMap[dir.ID] = dir
	}

	var roots []*model.Directory
	for _, dir := range allDirs {
		if dir.ParentID == nil {
			roots = append(roots, dir)
		} else if parent, ok := dirMap[*dir.ParentID]; ok {
			parent.Children = append(parent.Children, dir)
		}
	}

	return roots, nil
}
