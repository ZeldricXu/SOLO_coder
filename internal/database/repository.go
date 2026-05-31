package database

import (
	"context"
	"time"

	"gorm.io/gorm"
)

type BaseRepository struct {
	db *gorm.DB
}

func NewBaseRepository(db *gorm.DB) *BaseRepository {
	return &BaseRepository{db: db}
}

func (r *BaseRepository) DB() *gorm.DB {
	return r.db
}

func (r *BaseRepository) Create(ctx context.Context, model interface{}) error {
	return r.db.WithContext(ctx).Create(model).Error
}

func (r *BaseRepository) CreateWithID(ctx context.Context, model interface{}, id string) error {
	return r.db.WithContext(ctx).Create(model).Error
}

func (r *BaseRepository) GetByID(ctx context.Context, id string, model interface{}) error {
	return r.db.WithContext(ctx).Where("id = ?", id).First(model).Error
}

func (r *BaseRepository) Update(ctx context.Context, model interface{}) error {
	return r.db.WithContext(ctx).Save(model).Error
}

func (r *BaseRepository) UpdateFields(ctx context.Context, model interface{}, id string, fields map[string]interface{}) error {
	fields["updated_at"] = time.Now()
	return r.db.WithContext(ctx).Model(model).Where("id = ?", id).Updates(fields).Error
}

func (r *BaseRepository) Delete(ctx context.Context, id string, model interface{}) error {
	return r.db.WithContext(ctx).Where("id = ?", id).Delete(model).Error
}

func (r *BaseRepository) SoftDelete(ctx context.Context, id string, model interface{}) error {
	return r.db.WithContext(ctx).Model(model).Where("id = ?", id).Update("deleted_at", time.Now()).Error
}

func (r *BaseRepository) Exists(ctx context.Context, id string, model interface{}) (bool, error) {
	var count int64
	err := r.db.WithContext(ctx).Model(model).Where("id = ?", id).Count(&count).Error
	return count > 0, err
}

func (r *BaseRepository) Count(ctx context.Context, model interface{}, filters map[string]interface{}) (int64, error) {
	var count int64
	query := r.db.WithContext(ctx).Model(model)
	for field, value := range filters {
		query = query.Where(field+" = ?", value)
	}
	err := query.Count(&count).Error
	return count, err
}

func (r *BaseRepository) List(ctx context.Context, model interface{}, result interface{}, filters map[string]interface{}, opts QueryOptions) (*QueryResult, error) {
	query := BuildQuery(r.db.WithContext(ctx), model, filters, opts)
	return Paginate(query, result, opts)
}

func (r *BaseRepository) FindOne(ctx context.Context, model interface{}, result interface{}, filters map[string]interface{}) error {
	query := r.db.WithContext(ctx).Model(model)
	for field, value := range filters {
		query = query.Where(field+" = ?", value)
	}
	return query.First(result).Error
}

func (r *BaseRepository) Transaction(ctx context.Context, fn func(tx *gorm.DB) error) error {
	return r.db.WithContext(ctx).Transaction(fn)
}

func (r *BaseRepository) BatchCreate(ctx context.Context, models interface{}, batchSize int) error {
	return r.db.WithContext(ctx).CreateInBatches(models, batchSize).Error
}

func (r *BaseRepository) BatchUpdate(ctx context.Context, model interface{}, ids []string, fields map[string]interface{}) error {
	fields["updated_at"] = time.Now()
	return r.db.WithContext(ctx).Model(model).Where("id IN ?", ids).Updates(fields).Error
}
