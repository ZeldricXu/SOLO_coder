package service

import (
	"context"
	"errors"
	"fmt"

	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type CreateDocRequest struct {
	SpaceID     string                 `json:"space_id"`
	DirectoryID string                 `json:"directory_id"`
	Title       string                 `json:"title"`
	Slug        string                 `json:"slug"`
	Summary     string                 `json:"summary"`
	Content     model.ProseMirrorDoc   `json:"content"`
	ContentText string                 `json:"content_text"`
	ContentType string                 `json:"content_type"`
	LangCode    string                 `json:"lang_code"`
	Category    string                 `json:"category"`
	Tags        []string               `json:"tags"`
	IsPublic    bool                   `json:"is_public"`
	Metadata    map[string]interface{} `json:"metadata"`
}

type UpdateDocRequest struct {
	Title       string                 `json:"title"`
	Slug        string                 `json:"slug"`
	Summary     string                 `json:"summary"`
	Content     model.ProseMirrorDoc   `json:"content"`
	ContentText string                 `json:"content_text"`
	ContentType string                 `json:"content_type"`
	Category    string                 `json:"category"`
	Tags        []string               `json:"tags"`
	Status      string                 `json:"status"`
	IsPublic    *bool                  `json:"is_public"`
	IsPinned    *bool                  `json:"is_pinned"`
	ChangeLog   string                 `json:"change_log"`
	Metadata    map[string]interface{} `json:"metadata"`
}

type DocumentService struct {
	db             *gorm.DB
	tenantRepo     TenantRepository
	tenantSvc      *TenantService
	permissionRepo PermissionRepository
}

func NewDocumentService(db *gorm.DB, tenantRepo TenantRepository, tenantSvc *TenantService, permissionRepo PermissionRepository) *DocumentService {
	return &DocumentService{
		db:             db,
		tenantRepo:     tenantRepo,
		tenantSvc:      tenantSvc,
		permissionRepo: permissionRepo,
	}
}

func (s *DocumentService) CreateDocument(ctx context.Context, userID uuid.UUID, req CreateDocRequest) (*model.Document, error) {
	if req.Title == "" {
		return nil, errors.New("document title is required")
	}
	if req.SpaceID == "" {
		return nil, errors.New("space_id is required")
	}

	tenantIDStr, ok := database.GetTenantID(ctx)
	if !ok || tenantIDStr == "" {
		return nil, errors.New("tenant context missing")
	}
	tenantID, err := uuid.Parse(tenantIDStr)
	if err != nil {
		return nil, fmt.Errorf("invalid tenant id: %w", err)
	}

	ok, err = s.tenantSvc.CheckQuota(ctx, tenantID, "documents", 1)
	if err != nil {
		return nil, fmt.Errorf("check quota: %w", err)
	}
	if !ok {
		return nil, errors.New("document quota exceeded")
	}

	doc := &model.Document{
		TenantScoped:  model.TenantScoped{TenantID: tenantIDStr},
		SpaceID:       req.SpaceID,
		DirectoryID:   req.DirectoryID,
		Title:         req.Title,
		Slug:          req.Slug,
		Summary:       req.Summary,
		Content:       req.Content,
		ContentText:   req.ContentText,
		ContentType:   req.ContentType,
		LangCode:      req.LangCode,
		Category:      req.Category,
		Tags:          req.Tags,
		Status:        "draft",
		Version:       1,
		IsPublic:      req.IsPublic,
		CreatedBy:     userID.String(),
		UpdatedBy:     userID.String(),
		Metadata:      model.JSONB(req.Metadata),
	}

	err = s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(doc).Error; err != nil {
			return err
		}

		version := &model.DocumentVersion{
			TenantScoped: model.TenantScoped{TenantID: tenantIDStr},
			DocID:        doc.ID,
			SpaceID:      doc.SpaceID,
			Title:        doc.Title,
			Content:      doc.Content,
			ContentText:  doc.ContentText,
			Version:      1,
			ChangeLog:    "initial version",
			CreatedBy:    userID.String(),
		}
		if err := tx.Create(version).Error; err != nil {
			return err
		}

		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("create document: %w", err)
	}

	if err := s.tenantRepo.UpdateQuotaUsed(ctx, tenantID, "documents", 1); err != nil {
		return nil, fmt.Errorf("update quota: %w", err)
	}

	return doc, nil
}

func (s *DocumentService) GetDocument(ctx context.Context, docID uuid.UUID) (*model.Document, error) {
	var doc model.Document
	err := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("id = ?", docID.String()).
		First(&doc).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &doc, nil
}

func (s *DocumentService) UpdateDocument(ctx context.Context, userID, docID uuid.UUID, req UpdateDocRequest) (*model.Document, error) {
	tenantIDStr, _ := database.GetTenantID(ctx)

	var doc model.Document
	err := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("id = ?", docID.String()).
		First(&doc).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("document not found")
		}
		return nil, err
	}

	newVersion := doc.Version + 1

	err = s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		version := &model.DocumentVersion{
			TenantScoped: model.TenantScoped{TenantID: tenantIDStr},
			DocID:        doc.ID,
			SpaceID:      doc.SpaceID,
			Title:        doc.Title,
			Content:      doc.Content,
			ContentText:  doc.ContentText,
			Version:      doc.Version,
			ChangeLog:    req.ChangeLog,
			CreatedBy:    userID.String(),
		}
		if err := tx.Create(version).Error; err != nil {
			return err
		}

		updates := map[string]interface{}{
			"version":    newVersion,
			"updated_by": userID.String(),
		}
		if req.Title != "" {
			updates["title"] = req.Title
		}
		if req.Slug != "" {
			updates["slug"] = req.Slug
		}
		if req.Summary != "" {
			updates["summary"] = req.Summary
		}
		if req.ContentText != "" {
			updates["content_text"] = req.ContentText
		}
		if req.ContentType != "" {
			updates["content_type"] = req.ContentType
		}
		if req.Category != "" {
			updates["category"] = req.Category
		}
		if len(req.Tags) > 0 {
			updates["tags"] = req.Tags
		}
		if req.Status != "" {
			updates["status"] = req.Status
		}
		if req.IsPublic != nil {
			updates["is_public"] = *req.IsPublic
		}
		if req.IsPinned != nil {
			updates["is_pinned"] = *req.IsPinned
		}
		if req.Metadata != nil {
			updates["metadata"] = model.JSONB(req.Metadata)
		}

		if req.Content.Type != "" || len(req.Content.Content) > 0 {
			updates["content"] = req.Content
		}

		if err := tx.Model(&doc).Updates(updates).Error; err != nil {
			return err
		}

		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("update document: %w", err)
	}

	return s.GetDocument(ctx, docID)
}

func (s *DocumentService) DeleteDocument(ctx context.Context, docID uuid.UUID) error {
	tenantIDStr, ok := database.GetTenantID(ctx)
	if ok && tenantIDStr != "" {
		tenantID, err := uuid.Parse(tenantIDStr)
		if err == nil {
			_ = s.tenantRepo.UpdateQuotaUsed(ctx, tenantID, "documents", -1)
		}
	}

	return s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Scopes(database.TenantScope(ctx)).
			Where("doc_id = ?", docID.String()).
			Delete(&model.DocumentVersion{}).Error; err != nil {
			return err
		}

		if err := tx.Scopes(database.TenantScope(ctx)).
			Where("doc_id = ?", docID.String()).
			Delete(&model.Attachment{}).Error; err != nil {
			return err
		}

		if err := tx.Scopes(database.TenantScope(ctx)).
			Where("id = ?", docID.String()).
			Delete(&model.Document{}).Error; err != nil {
			return err
		}

		return nil
	})
}

func (s *DocumentService) GetDocumentVersion(ctx context.Context, docID uuid.UUID, version int) (*model.DocumentVersion, error) {
	var dv model.DocumentVersion
	err := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("doc_id = ? AND version = ?", docID.String(), version).
		First(&dv).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &dv, nil
}

func (s *DocumentService) RollbackToVersion(ctx context.Context, userID, docID uuid.UUID, version int) error {
	dv, err := s.GetDocumentVersion(ctx, docID, version)
	if err != nil {
		return err
	}
	if dv == nil {
		return errors.New("version not found")
	}

	tenantIDStr, _ := database.GetTenantID(ctx)

	var doc model.Document
	err = s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("id = ?", docID.String()).
		First(&doc).Error
	if err != nil {
		return fmt.Errorf("get document: %w", err)
	}

	newVersion := doc.Version + 1

	return s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		archiveVersion := &model.DocumentVersion{
			TenantScoped: model.TenantScoped{TenantID: tenantIDStr},
			DocID:        doc.ID,
			SpaceID:      doc.SpaceID,
			Title:        doc.Title,
			Content:      doc.Content,
			ContentText:  doc.ContentText,
			Version:      doc.Version,
			ChangeLog:    fmt.Sprintf("archived before rollback to v%d", version),
			CreatedBy:    userID.String(),
		}
		if err := tx.Create(archiveVersion).Error; err != nil {
			return err
		}

		updates := map[string]interface{}{
			"title":        dv.Title,
			"content":      dv.Content,
			"content_text": dv.ContentText,
			"version":      newVersion,
			"updated_by":   userID.String(),
		}
		if err := tx.Model(&doc).Updates(updates).Error; err != nil {
			return err
		}

		return nil
	})
}

func (s *DocumentService) ListDocuments(ctx context.Context, spaceID uuid.UUID, query model.DocumentQuery) ([]*model.Document, int64, error) {
	var docs []*model.Document
	var total int64

	db := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Model(&model.Document{}).
		Where("space_id = ?", spaceID.String())

	if query.Keyword != "" {
		db = db.Where("title ILIKE ? OR content_text ILIKE ?", "%"+query.Keyword+"%", "%"+query.Keyword+"%")
	}
	if query.Category != "" {
		db = db.Where("category = ?", query.Category)
	}
	if len(query.Tags) > 0 {
		db = db.Where("tags && ?", query.Tags)
	}
	if query.Status != "" {
		db = db.Where("status = ?", query.Status)
	}
	if query.CreatedBy != "" {
		db = db.Where("created_by = ?", query.CreatedBy)
	}
	if query.DirectoryID != "" {
		db = db.Where("directory_id = ?", query.DirectoryID)
	}
	if query.IsPublic != nil {
		db = db.Where("is_public = ?", *query.IsPublic)
	}

	if err := db.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	sortBy := query.SortBy
	if sortBy == "" {
		sortBy = "created_at"
	}
	sortOrder := query.SortOrder
	if sortOrder == "" {
		sortOrder = "desc"
	}
	db = db.Order(fmt.Sprintf("%s %s", sortBy, sortOrder))

	if err := db.Find(&docs).Error; err != nil {
		return nil, 0, err
	}

	return docs, total, nil
}
