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

type DocumentRepository struct {
	db *gorm.DB
}

func NewDocumentRepository(db *gorm.DB) *DocumentRepository {
	return &DocumentRepository{db: db}
}

func (r *DocumentRepository) Create(ctx context.Context, doc *model.Document) error {
	doc.CreatedAt = time.Now().UTC()
	doc.UpdatedAt = time.Now().UTC()
	if doc.ID == uuid.Nil {
		doc.ID = uuid.New()
	}
	if doc.Slug == "" {
		doc.Slug = doc.ID.String()[:8]
	}
	err := r.db.WithContext(ctx).Create(doc).Error
	if err != nil {
		return err
	}
	return nil
}

func (r *DocumentRepository) GetByID(ctx context.Context, id uuid.UUID) (*model.Document, error) {
	var doc model.Document
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Preload("Attachments").
		First(&doc, id).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &doc, nil
}

func (r *DocumentRepository) GetBySlug(ctx context.Context, spaceID uuid.UUID, slug string) (*model.Document, error) {
	var doc model.Document
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Where("space_id = ? AND slug = ?", spaceID, slug).
		First(&doc).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &doc, nil
}

func (r *DocumentRepository) Update(ctx context.Context, doc *model.Document) error {
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

func (r *DocumentRepository) Delete(ctx context.Context, id uuid.UUID) error {
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Delete(&model.Document{}, id)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *DocumentRepository) List(ctx context.Context, q *model.DocumentQuery) (*database.PaginatedResult, error) {
	query := r.db.WithContext(ctx).Model(&model.Document{}).Scopes(database.TenantScope(ctx))

	if q.SpaceID != uuid.Nil {
		query = query.Where("space_id = ?", q.SpaceID)
	}
	if q.DirectoryID != nil {
		if *q.DirectoryID == uuid.Nil {
			query = query.Where("directory_id IS NULL")
		} else {
			query = query.Where("directory_id = ?", *q.DirectoryID)
		}
	}
	if q.Status != "" {
		query = query.Where("status = ?", q.Status)
	}
	if q.Tag != "" {
		query = query.Where("tags @> ?", `["`+q.Tag+`"]`)
	}
	if q.Keyword != "" {
		query = query.Where("(title ILIKE ? OR summary ILIKE ?)",
			"%"+q.Keyword+"%", "%"+q.Keyword+"%")
	}
	if q.AuthorID != uuid.Nil {
		query = query.Where("author_id = ?", q.AuthorID)
	}
	if q.Language != "" {
		query = query.Where("language = ?", q.Language)
	}
	if q.IsPinned != nil {
		query = query.Where("is_pinned = ?", *q.IsPinned)
	}

	orderClause := "created_at DESC"
	if q.SortBy != "" {
		order := "DESC"
		if q.SortOrder == "asc" {
			order = "ASC"
		}
		orderClause = q.SortBy + " " + order
	}
	if q.IsPinned != nil && *q.IsPinned {
		orderClause = "is_pinned DESC, " + orderClause
	}

	var docs []model.Document
	pr, err := database.Paginate(query.Preload("Attachments").Preload("Author").
		Preload("Directory").Order(orderClause),
		q.Page, q.PageSize, &docs)
	if err != nil {
		return nil, err
	}
	pr.Data = docs
	return pr, nil
}

func (r *DocumentRepository) UpdateStatus(ctx context.Context, id uuid.UUID, status model.DocumentStatus, editorID uuid.UUID) error {
	now := time.Now().UTC()
	updates := map[string]interface{}{
		"status":         status,
		"updated_at":     now,
		"last_editor_id": editorID,
	}
	if status == model.DocumentStatusPublished {
		updates["published_at"] = &now
	}
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Model(&model.Document{}).
		Where("id = ?", id).
		UpdateColumns(updates)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *DocumentRepository) IncrementViewCount(ctx context.Context, id uuid.UUID) error {
	return r.db.WithContext(ctx).Model(&model.Document{}).
		Where("id = ?", id).
		UpdateColumn("view_count", gorm.Expr("view_count + 1")).Error
}

func (r *DocumentRepository) IncrementLikeCount(ctx context.Context, id uuid.UUID) error {
	return r.db.WithContext(ctx).Model(&model.Document{}).
		Where("id = ?", id).
		UpdateColumn("like_count", gorm.Expr("like_count + 1")).Error
}

func (r *DocumentRepository) CreateVersion(ctx context.Context, version *model.DocumentVersion) error {
	version.CreatedAt = time.Now().UTC()
	version.UpdatedAt = time.Now().UTC()
	if version.ID == uuid.Nil {
		version.ID = uuid.New()
	}
	return r.db.WithContext(ctx).Create(version).Error
}

func (r *DocumentRepository) GetVersions(ctx context.Context, docID uuid.UUID, page, pageSize int) (*database.PaginatedResult, error) {
	query := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Model(&model.DocumentVersion{}).
		Where("document_id = ?", docID)

	var versions []model.DocumentVersion
	pr, err := database.Paginate(query.Preload("Editor").Order("version DESC"),
		page, pageSize, &versions)
	if err != nil {
		return nil, err
	}
	pr.Data = versions
	return pr, nil
}

func (r *DocumentRepository) GetVersion(ctx context.Context, docID uuid.UUID, version int) (*model.DocumentVersion, error) {
	var v model.DocumentVersion
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Where("document_id = ? AND version = ?", docID, version).
		First(&v).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &v, nil
}

func (r *DocumentRepository) GetLatestVersion(ctx context.Context, docID uuid.UUID) (*model.DocumentVersion, error) {
	var v model.DocumentVersion
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Where("document_id = ?", docID).
		Order("version DESC").
		First(&v).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &v, nil
}

func (r *DocumentRepository) GetNextVersionNumber(ctx context.Context, docID uuid.UUID) (int, error) {
	var maxVersion int
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Model(&model.DocumentVersion{}).
		Where("document_id = ?", docID).
		Select("COALESCE(MAX(version), 0)").
		Scan(&maxVersion).Error
	return maxVersion + 1, err
}

func (r *DocumentRepository) CreateAttachment(ctx context.Context, att *model.Attachment) error {
	att.CreatedAt = time.Now().UTC()
	att.UpdatedAt = time.Now().UTC()
	if att.ID == uuid.Nil {
		att.ID = uuid.New()
	}
	return r.db.WithContext(ctx).Create(att).Error
}

func (r *DocumentRepository) GetAttachment(ctx context.Context, id uuid.UUID) (*model.Attachment, error) {
	var att model.Attachment
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).First(&att, id).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &att, nil
}

func (r *DocumentRepository) ListAttachments(ctx context.Context, docID uuid.UUID) ([]model.Attachment, error) {
	var atts []model.Attachment
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Where("document_id = ?", docID).
		Order("created_at ASC").
		Find(&atts).Error
	return atts, err
}

func (r *DocumentRepository) DeleteAttachment(ctx context.Context, id uuid.UUID) error {
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Delete(&model.Attachment{}, id)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *DocumentRepository) IncrementAttachmentDownload(ctx context.Context, id uuid.UUID) error {
	return r.db.WithContext(ctx).Model(&model.Attachment{}).
		Where("id = ?", id).
		UpdateColumn("download_count", gorm.Expr("download_count + 1")).Error
}

func (r *DocumentRepository) CreateTemplate(ctx context.Context, tpl *model.DocumentTemplate) error {
	tpl.CreatedAt = time.Now().UTC()
	tpl.UpdatedAt = time.Now().UTC()
	if tpl.ID == uuid.Nil {
		tpl.ID = uuid.New()
	}
	return r.db.WithContext(ctx).Create(tpl).Error
}

func (r *DocumentRepository) GetTemplate(ctx context.Context, id uuid.UUID) (*model.DocumentTemplate, error) {
	var tpl model.DocumentTemplate
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).First(&tpl, id).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &tpl, nil
}

func (r *DocumentRepository) ListTemplates(ctx context.Context, tenantID uuid.UUID, spaceID uuid.UUID, category string, page, pageSize int) (*database.PaginatedResult, error) {
	query := r.db.WithContext(ctx).Model(&model.DocumentTemplate{}).Where("tenant_id = ?", tenantID)
	if spaceID != uuid.Nil {
		query = query.Where("(space_id = ? OR is_system = ? OR is_public = ?)", spaceID, true, true)
	} else {
		query = query.Where("(is_system = ? OR is_public = ?)", true, true)
	}
	if category != "" {
		query = query.Where("category = ?", category)
	}

	var tpls []model.DocumentTemplate
	pr, err := database.Paginate(query.Order("use_count DESC, created_at DESC"),
		page, pageSize, &tpls)
	if err != nil {
		return nil, err
	}
	pr.Data = tpls
	return pr, nil
}

func (r *DocumentRepository) UpdateTemplate(ctx context.Context, tpl *model.DocumentTemplate) error {
	tpl.UpdatedAt = time.Now().UTC()
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Save(tpl)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *DocumentRepository) DeleteTemplate(ctx context.Context, id uuid.UUID) error {
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Delete(&model.DocumentTemplate{}, id)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *DocumentRepository) IncrementTemplateUseCount(ctx context.Context, id uuid.UUID) error {
	return r.db.WithContext(ctx).Model(&model.DocumentTemplate{}).
		Where("id = ?", id).
		UpdateColumn("use_count", gorm.Expr("use_count + 1")).Error
}
