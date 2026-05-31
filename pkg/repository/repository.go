package repository

import (
	"context"
	"fmt"

	"gorm.io/gorm"
)

type Repository[T any] struct {
	db *gorm.DB
}

func New[T any](db *gorm.DB) *Repository[T] {
	return &Repository[T]{db: db}
}

func (r *Repository[T]) Create(ctx context.Context, entity *T) error {
	if err := r.db.WithContext(ctx).Create(entity).Error; err != nil {
		return fmt.Errorf("create failed: %w", err)
	}
	return nil
}

func (r *Repository[T]) GetByID(ctx context.Context, idField string, idValue string) (*T, error) {
	var entity T
	if err := r.db.WithContext(ctx).Where(fmt.Sprintf("%s = ?", idField), idValue).First(&entity).Error; err != nil {
		return nil, fmt.Errorf("get by id failed: %w", err)
	}
	return &entity, nil
}

func (r *Repository[T]) Update(ctx context.Context, idField string, idValue string, updates map[string]interface{}) error {
	if err := r.db.WithContext(ctx).Model(new(T)).Where(fmt.Sprintf("%s = ?", idField), idValue).Updates(updates).Error; err != nil {
		return fmt.Errorf("update failed: %w", err)
	}
	return nil
}

func (r *Repository[T]) Delete(ctx context.Context, idField string, idValue string) error {
	if err := r.db.WithContext(ctx).Where(fmt.Sprintf("%s = ?", idField), idValue).Delete(new(T)).Error; err != nil {
		return fmt.Errorf("delete failed: %w", err)
	}
	return nil
}

func (r *Repository[T]) List(ctx context.Context, offset, limit int, orderBy string, filters ...Filter) ([]T, int64, error) {
	var entities []T
	var total int64

	query := r.db.WithContext(ctx).Model(new(T))
	for _, f := range filters {
		query = query.Where(fmt.Sprintf("%s %s ?", f.Field, f.Op), f.Value)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count failed: %w", err)
	}

	if orderBy != "" {
		query = query.Order(orderBy)
	}
	if offset > 0 {
		query = query.Offset(offset)
	}
	if limit > 0 {
		query = query.Limit(limit)
	}

	if err := query.Find(&entities).Error; err != nil {
		return nil, 0, fmt.Errorf("list failed: %w", err)
	}

	return entities, total, nil
}

type Filter struct {
	Field string
	Op    string
	Value interface{}
}

func NewFilter(field string, value interface{}) Filter {
	return Filter{Field: field, Op: "=", Value: value}
}
