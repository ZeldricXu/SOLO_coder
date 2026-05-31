package database

import (
	"context"
	"fmt"
	"strings"
	"time"

	"gorm.io/gorm"
)

type QueryOptions struct {
	Page        int
	PageSize    int
	OrderBy     string
	OrderDir    string
	SelectFields []string
	Preload     []string
}

type QueryResult struct {
	Data       interface{}
	Total      int64
	Page       int
	PageSize   int
	TotalPages int
}

func DefaultQueryOptions() QueryOptions {
	return QueryOptions{
		Page:     1,
		PageSize: 20,
		OrderBy:  "created_at",
		OrderDir: "desc",
	}
}

func BuildQuery(db *gorm.DB, model interface{}, filters map[string]interface{}, opts QueryOptions) *gorm.DB {
	query := db.Model(model)

	if len(opts.SelectFields) > 0 {
		query = query.Select(opts.SelectFields)
	}

	for field, value := range filters {
		if strings.HasSuffix(field, "_like") {
			fieldName := strings.TrimSuffix(field, "_like")
			query = query.Where(fmt.Sprintf("%s LIKE ?", fieldName), fmt.Sprintf("%%%v%%", value))
		} else if strings.HasSuffix(field, "_gte") {
			fieldName := strings.TrimSuffix(field, "_gte")
			query = query.Where(fmt.Sprintf("%s >= ?", fieldName), value)
		} else if strings.HasSuffix(field, "_lte") {
			fieldName := strings.TrimSuffix(field, "_lte")
			query = query.Where(fmt.Sprintf("%s <= ?", fieldName), value)
		} else if strings.HasSuffix(field, "_in") {
			fieldName := strings.TrimSuffix(field, "_in")
			query = query.Where(fmt.Sprintf("%s IN ?", fieldName), value)
		} else {
			query = query.Where(fmt.Sprintf("%s = ?", field), value)
		}
	}

	for _, preload := range opts.Preload {
		query = query.Preload(preload)
	}

	if opts.OrderBy != "" {
		orderDir := "DESC"
		if strings.ToLower(opts.OrderDir) == "asc" {
			orderDir = "ASC"
		}
		query = query.Order(fmt.Sprintf("%s %s", opts.OrderBy, orderDir))
	}

	return query
}

func Paginate(query *gorm.DB, result interface{}, opts QueryOptions) (*QueryResult, error) {
	var total int64
	if err := query.Count(&total).Error; err != nil {
		return nil, err
	}

	offset := (opts.Page - 1) * opts.PageSize
	if err := query.Offset(offset).Limit(opts.PageSize).Find(result).Error; err != nil {
		return nil, err
	}

	totalPages := int(total) / opts.PageSize
	if int(total)%opts.PageSize > 0 {
		totalPages++
	}

	return &QueryResult{
		Data:       result,
		Total:      total,
		Page:       opts.Page,
		PageSize:   opts.PageSize,
		TotalPages: totalPages,
	}, nil
}

type CachedQuery struct {
	db          *gorm.DB
	cachePrefix string
	ttl         time.Duration
}

func NewCachedQuery(db *gorm.DB, cachePrefix string, ttl time.Duration) *CachedQuery {
	return &CachedQuery{
		db:          db,
		cachePrefix: cachePrefix,
		ttl:         ttl,
	}
}

func (cq *CachedQuery) FindWithCache(ctx context.Context, key string, result interface{}, queryFunc func(*gorm.DB) *gorm.DB) error {
	cacheKey := fmt.Sprintf("%s:%s", cq.cachePrefix, key)

	query := queryFunc(cq.db.WithContext(ctx))
	return query.Find(result).Error
}

func OptimizeForLargeDataset(db *gorm.DB, batchSize int) *gorm.DB {
	return db.Set("gorm:batch_size", batchSize)
}

func SoftDelete(db *gorm.DB, model interface{}, id string) error {
	return db.Model(model).Where("id = ?", id).Update("deleted_at", time.Now()).Error
}
