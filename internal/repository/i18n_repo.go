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

type I18nRepository struct {
	db *gorm.DB
}

func NewI18nRepository(db *gorm.DB) *I18nRepository {
	return &I18nRepository{db: db}
}

func (r *I18nRepository) CreateDocVariant(ctx context.Context, doc *model.I18nDoc) error {
	doc.CreatedAt = time.Now().UTC()
	doc.UpdatedAt = time.Now().UTC()
	if doc.ID == uuid.Nil {
		doc.ID = uuid.New()
	}
	err := r.db.WithContext(ctx).Create(doc).Error
	if err != nil {
		if IsUniqueViolation(err) {
			return ErrAlreadyExists
		}
		return err
	}
	return nil
}

func (r *I18nRepository) GetDocVariant(ctx context.Context, sourceDocID uuid.UUID, language string) (*model.I18nDoc, error) {
	var doc model.I18nDoc
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Where("source_doc_id = ? AND language = ?", sourceDocID, language).
		First(&doc).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &doc, nil
}

func (r *I18nRepository) GetDocVariantByID(ctx context.Context, id uuid.UUID) (*model.I18nDoc, error) {
	var doc model.I18nDoc
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).First(&doc, id).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &doc, nil
}

func (r *I18nRepository) UpdateDocVariant(ctx context.Context, doc *model.I18nDoc) error {
	doc.UpdatedAt = time.Now().UTC()
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Save(doc)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *I18nRepository) DeleteDocVariant(ctx context.Context, id uuid.UUID) error {
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Delete(&model.I18nDoc{}, id)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *I18nRepository) ListDocVariants(ctx context.Context, sourceDocID uuid.UUID) ([]model.I18nDoc, error) {
	var docs []model.I18nDoc
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Where("source_doc_id = ?", sourceDocID).
		Order("language ASC").
		Find(&docs).Error
	return docs, err
}

func (r *I18nRepository) AddToMemory(ctx context.Context, tm *model.TranslationMemory) error {
	tm.CreatedAt = time.Now().UTC()
	tm.UpdatedAt = time.Now().UTC()
	if tm.ID == uuid.Nil {
		tm.ID = uuid.New()
	}
	return r.db.WithContext(ctx).Create(tm).Error
}

func (r *I18nRepository) SearchMemory(ctx context.Context, tenantID uuid.UUID, sourceLang, targetLang, sourceText string, limit int) ([]model.TranslationMemory, error) {
	var results []model.TranslationMemory
	err := r.db.WithContext(ctx).
		Where(`tenant_id = ? AND source_language = ? AND target_language = ? AND source_text ILIKE ?`,
			tenantID, sourceLang, targetLang, "%"+sourceText+"%").
		Order("quality_score DESC, usage_count DESC").
		Limit(limit).
		Find(&results).Error
	return results, err
}

func (r *I18nRepository) ExactMatchFromMemory(ctx context.Context, tenantID uuid.UUID, sourceLang, targetLang, sourceHash string) (*model.TranslationMemory, error) {
	var tm model.TranslationMemory
	err := r.db.WithContext(ctx).
		Where("tenant_id = ? AND source_language = ? AND target_language = ? AND source_hash = ?",
			tenantID, sourceLang, targetLang, sourceHash).
		Order("quality_score DESC").
		First(&tm).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &tm, nil
}

func (r *I18nRepository) IncrementMemoryUsage(ctx context.Context, id uuid.UUID) error {
	now := time.Now().UTC()
	return r.db.WithContext(ctx).Model(&model.TranslationMemory{}).
		Where("id = ?", id).
		UpdateColumns(map[string]interface{}{
			"usage_count":  gorm.Expr("usage_count + 1"),
			"last_used_at": &now,
			"updated_at":   now,
		}).Error
}

func (r *I18nRepository) ListTranslationMemory(ctx context.Context, tenantID uuid.UUID, sourceLang, targetLang, domain string, page, pageSize int) (*database.PaginatedResult, error) {
	query := r.db.WithContext(ctx).Model(&model.TranslationMemory{}).Where("tenant_id = ?", tenantID)
	if sourceLang != "" {
		query = query.Where("source_language = ?", sourceLang)
	}
	if targetLang != "" {
		query = query.Where("target_language = ?", targetLang)
	}
	if domain != "" {
		query = query.Where("domain = ?", domain)
	}

	var tms []model.TranslationMemory
	pr, err := database.Paginate(query.Order("quality_score DESC, usage_count DESC"),
		page, pageSize, &tms)
	if err != nil {
		return nil, err
	}
	pr.Data = tms
	return pr, nil
}

func (r *I18nRepository) DeleteFromMemory(ctx context.Context, id uuid.UUID) error {
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Delete(&model.TranslationMemory{}, id)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *I18nRepository) ApproveMemory(ctx context.Context, id uuid.UUID, approved bool) error {
	return r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Model(&model.TranslationMemory{}).
		Where("id = ?", id).
		UpdateColumns(map[string]interface{}{
			"is_approved": approved,
			"updated_at":  time.Now().UTC(),
		}).Error
}
