package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"time"

	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/pkg/utils"
	"github.com/enterprise/knowledgebase/internal/repository"
	"github.com/google/uuid"
	"github.com/sergi/go-diff/diffmatchpatch"
)

type DocumentService struct {
	docRepo     *repository.DocumentRepository
	spaceRepo   *repository.SpaceRepository
	permRepo    *repository.PermissionRepository
	tenantRepo  *repository.TenantRepository
	searchSvc   *SearchService
	minioClient *database.MinIOClient
}

func NewDocumentService(
	docRepo *repository.DocumentRepository,
	spaceRepo *repository.SpaceRepository,
	permRepo *repository.PermissionRepository,
	tenantRepo *repository.TenantRepository,
	searchSvc *SearchService,
	minioClient *database.MinIOClient,
) *DocumentService {
	return &DocumentService{
		docRepo:     docRepo,
		spaceRepo:   spaceRepo,
		permRepo:    permRepo,
		tenantRepo:  tenantRepo,
		searchSvc:   searchSvc,
		minioClient: minioClient,
	}
}

type CreateDocumentRequest struct {
	TenantID    uuid.UUID
	SpaceID     uuid.UUID
	DirectoryID *uuid.UUID
	Title       string
	Content     model.ProseMirrorDoc
	Summary     string
	Tags        []string
	Language    string
	AuthorID    uuid.UUID
	TemplateID  *uuid.UUID
	IsPinned    bool
}

func (s *DocumentService) Create(ctx context.Context, req *CreateDocumentRequest) (*model.Document, error) {
	allowed, err := s.permRepo.CheckPermission(ctx, req.AuthorID, nil, nil,
		model.ResourceTypeSpace, req.SpaceID, model.ActionCreate)
	if err != nil {
		return nil, err
	}
	if !allowed {
		return nil, repository.ErrForbidden
	}

	ok, err := s.tenantRepo.CheckQuotaAndIncrement(ctx, req.TenantID, "documents", 1)
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, errors.New("document quota exceeded")
	}

	plainText := extractPlainText(req.Content)
	wordCount := int(utils.CountWords(plainText))

	doc := &model.Document{
		TenantScoped:  model.TenantScoped{TenantID: req.TenantID},
		SpaceID:       req.SpaceID,
		DirectoryID:   req.DirectoryID,
		Title:         req.Title,
		Slug:          utils.GenerateSlug(req.Title) + "-" + uuid.New().String()[:6],
		Summary:       req.Summary,
		Content:       req.Content,
		PlainText:     plainText,
		Status:        model.DocumentStatusDraft,
		Visibility:    "private",
		Tags:          model.StringArray(req.Tags),
		Language:      req.Language,
		FormatVersion: 1,
		CurrentVersion: 1,
		WordCount:     wordCount,
		AuthorID:      req.AuthorID,
		LastEditorID:  req.AuthorID,
		IsPinned:      req.IsPinned,
		TemplateID:    req.TemplateID,
	}

	if err := s.docRepo.Create(ctx, doc); err != nil {
		return nil, err
	}

	version := &model.DocumentVersion{
		TenantScoped: model.TenantScoped{TenantID: req.TenantID},
		DocumentID:   doc.ID,
		Version:      1,
		Title:        doc.Title,
		Content:      doc.Content,
		PlainText:    plainText,
		Summary:      doc.Summary,
		Tags:         doc.Tags,
		EditorID:     req.AuthorID,
		ChangeLog:    "Initial version",
		WordCount:    wordCount,
		SizeBytes:    int64(len(plainText)),
	}
	if err := s.docRepo.CreateVersion(ctx, version); err != nil {
		return nil, err
	}

	if err := s.spaceRepo.IncrementDocCount(ctx, req.SpaceID, 1); err != nil {
		_ = err
	}
	if req.DirectoryID != nil {
		if err := s.spaceRepo.IncrementDirectoryDocCount(ctx, *req.DirectoryID, 1); err != nil {
			_ = err
		}
	}

	if req.TemplateID != nil {
		_ = s.docRepo.IncrementTemplateUseCount(ctx, *req.TemplateID)
	}

	_ = s.searchSvc.IndexDocument(ctx, doc)

	return doc, nil
}

func (s *DocumentService) GetByID(ctx context.Context, userID uuid.UUID, groupIDs, deptIDs []uuid.UUID, id uuid.UUID) (*model.Document, error) {
	doc, err := s.docRepo.GetByID(ctx, id)
	if err != nil {
		return nil, err
	}

	allowed, err := s.permRepo.CheckPermission(ctx, userID, groupIDs, deptIDs,
		model.ResourceTypeDocument, id, model.ActionRead)
	if err != nil {
		return nil, err
	}
	if !allowed {
		allowed, err = s.permRepo.CheckPermission(ctx, userID, groupIDs, deptIDs,
			model.ResourceTypeSpace, doc.SpaceID, model.ActionRead)
		if err != nil || !allowed {
			return nil, repository.ErrForbidden
		}
	}

	_ = s.docRepo.IncrementViewCount(ctx, id)

	return doc, nil
}

type UpdateDocumentRequest struct {
	DocID       uuid.UUID
	Title       *string
	Content     *model.ProseMirrorDoc
	Summary     *string
	Tags        *[]string
	DirectoryID *uuid.UUID
	Status      *model.DocumentStatus
	IsPinned    *bool
	EditorID    uuid.UUID
	ChangeLog   string
}

func (s *DocumentService) Update(ctx context.Context, req *UpdateDocumentRequest) (*model.Document, error) {
	doc, err := s.docRepo.GetByID(ctx, req.DocID)
	if err != nil {
		return nil, err
	}

	allowed, err := s.permRepo.CheckPermission(ctx, req.EditorID, nil, nil,
		model.ResourceTypeDocument, req.DocID, model.ActionUpdate)
	if err != nil {
		return nil, err
	}
	if !allowed {
		allowed, err = s.permRepo.CheckPermission(ctx, req.EditorID, nil, nil,
			model.ResourceTypeSpace, doc.SpaceID, model.ActionUpdate)
		if err != nil || !allowed {
			return nil, repository.ErrForbidden
		}
	}

	changed := false

	if req.Title != nil && *req.Title != doc.Title {
		doc.Title = *req.Title
		doc.Slug = utils.GenerateSlug(*req.Title) + "-" + uuid.New().String()[:6]
		changed = true
	}
	if req.Summary != nil {
		doc.Summary = *req.Summary
		changed = true
	}
	if req.Tags != nil {
		doc.Tags = model.StringArray(*req.Tags)
		changed = true
	}
	if req.DirectoryID != nil && *req.DirectoryID != uuid.Nil {
		if doc.DirectoryID != nil && *doc.DirectoryID != uuid.Nil {
			_ = s.spaceRepo.IncrementDirectoryDocCount(ctx, *doc.DirectoryID, -1)
		}
		doc.DirectoryID = req.DirectoryID
		_ = s.spaceRepo.IncrementDirectoryDocCount(ctx, *req.DirectoryID, 1)
		changed = true
	}
	if req.Status != nil {
		doc.Status = *req.Status
		if *req.Status == model.DocumentStatusPublished {
			now := time.Now().UTC()
			doc.PublishedAt = &now
		}
		changed = true
	}
	if req.IsPinned != nil {
		doc.IsPinned = *req.IsPinned
		changed = true
	}
	if req.Content != nil {
		doc.Content = *req.Content
		plainText := extractPlainText(*req.Content)
		doc.PlainText = plainText
		doc.WordCount = int(utils.CountWords(plainText))
		changed = true
	}

	if !changed {
		return doc, nil
	}

	doc.LastEditorID = req.EditorID
	if err := s.docRepo.Update(ctx, doc); err != nil {
		return nil, err
	}

	nextVer, err := s.docRepo.GetNextVersionNumber(ctx, doc.ID)
	if err != nil {
		return nil, err
	}

	changeLog := req.ChangeLog
	if changeLog == "" {
		changeLog = "Updated document"
	}

	version := &model.DocumentVersion{
		TenantScoped: model.TenantScoped{TenantID: doc.TenantID},
		DocumentID:   doc.ID,
		Version:      nextVer,
		Title:        doc.Title,
		Content:      doc.Content,
		PlainText:    doc.PlainText,
		Summary:      doc.Summary,
		Tags:         doc.Tags,
		EditorID:     req.EditorID,
		ChangeLog:    changeLog,
		WordCount:    doc.WordCount,
		SizeBytes:    int64(len(doc.PlainText)),
	}
	if err := s.docRepo.CreateVersion(ctx, version); err != nil {
		_ = err
	}
	doc.CurrentVersion = nextVer

	_ = s.searchSvc.IndexDocument(ctx, doc)

	return doc, nil
}

func (s *DocumentService) Delete(ctx context.Context, userID, docID uuid.UUID) error {
	doc, err := s.docRepo.GetByID(ctx, docID)
	if err != nil {
		return err
	}

	allowed, err := s.permRepo.CheckPermission(ctx, userID, nil, nil,
		model.ResourceTypeDocument, docID, model.ActionDelete)
	if err != nil {
		return err
	}
	if !allowed {
		allowed, err = s.permRepo.CheckPermission(ctx, userID, nil, nil,
			model.ResourceTypeSpace, doc.SpaceID, model.ActionDelete)
		if err != nil || !allowed {
			return repository.ErrForbidden
		}
	}

	if doc.DirectoryID != nil {
		_ = s.spaceRepo.IncrementDirectoryDocCount(ctx, *doc.DirectoryID, -1)
	}
	_ = s.spaceRepo.IncrementDocCount(ctx, doc.SpaceID, -1)

	_ = s.searchSvc.RemoveDocument(ctx, doc.TenantID, docID)

	atts, _ := s.docRepo.ListAttachments(ctx, docID)
	for _, att := range atts {
		_ = s.minioClient.Delete(ctx, att.FilePath)
		_ = s.searchSvc.RemoveAttachment(ctx, doc.TenantID, att.ID)
	}

	return s.docRepo.Delete(ctx, docID)
}

func (s *DocumentService) List(ctx context.Context, userID uuid.UUID, groupIDs, deptIDs []uuid.UUID, q *model.DocumentQuery) (*database.PaginatedResult, error) {
	allowedSpaceIDs, err := s.permRepo.GetAccessibleResources(
		ctx, userID, groupIDs, deptIDs,
		model.ResourceTypeSpace, model.RoleViewer,
	)
	if err != nil {
		return nil, err
	}

	if allowedSpaceIDs != nil {
		if q.SpaceID == uuid.Nil {
			if len(allowedSpaceIDs) == 0 {
				return &database.PaginatedResult{
					Total:      0,
					Page:       q.Page,
					PageSize:   q.PageSize,
					TotalPages: 0,
					Data:       []model.Document{},
				}, nil
			}
			q.SpaceID = uuid.Nil
		} else {
			found := false
			for _, id := range allowedSpaceIDs {
				if id == q.SpaceID {
					found = true
					break
				}
			}
			if !found {
				return nil, repository.ErrForbidden
			}
		}
	}

	return s.docRepo.List(ctx, q)
}

func (s *DocumentService) UploadAttachment(ctx context.Context,
	tenantID, spaceID uuid.UUID, docID *uuid.UUID, uploaderID uuid.UUID,
	fileName string, fileSize int64, contentType string, reader io.Reader,
) (*model.Attachment, error) {
	ok, err := s.tenantRepo.CheckQuotaAndIncrement(ctx, tenantID, "storage", fileSize)
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, errors.New("storage quota exceeded")
	}

	uploadResult, err := s.minioClient.Upload(ctx, tenantID.String(), "attachments", fileName, reader, fileSize, contentType)
	if err != nil {
		return nil, fmt.Errorf("upload to minio: %w", err)
	}

	isImage := false
	ext := ""
	lowerName := fileName
	for i := len(lowerName) - 1; i >= 0; i-- {
		if lowerName[i] == '.' {
			ext = lowerName[i:]
			break
		}
	}
	imageExts := map[string]bool{".jpg": true, ".jpeg": true, ".png": true, ".gif": true, ".bmp": true, ".webp": true, ".svg": true}
	if imageExts[ext] {
		isImage = true
	}

	att := &model.Attachment{
		TenantScoped:  model.TenantScoped{TenantID: tenantID},
		SpaceID:       spaceID,
		DocumentID:    docID,
		FileName:      fileName,
		OriginalName:  fileName,
		FilePath:      uploadResult.ObjectName,
		MimeType:      contentType,
		FileSize:      fileSize,
		FileExtension: ext,
		StorageType:   "minio",
		ETag:          uploadResult.ETag,
		IsImage:       isImage,
		UploaderID:    uploaderID,
	}

	if err := s.docRepo.CreateAttachment(ctx, att); err != nil {
		_ = s.minioClient.Delete(ctx, uploadResult.ObjectName)
		return nil, err
	}

	_ = s.spaceRepo.IncrementStorage(ctx, spaceID, fileSize)

	return att, nil
}

func (s *DocumentService) GetDiff(ctx context.Context, docID uuid.UUID, v1, v2 int) (string, error) {
	ver1, err := s.docRepo.GetVersion(ctx, docID, v1)
	if err != nil {
		return "", err
	}
	ver2, err := s.docRepo.GetVersion(ctx, docID, v2)
	if err != nil {
		return "", err
	}

	text1 := extractPlainText(ver1.Content)
	text2 := extractPlainText(ver2.Content)

	dmp := diffmatchpatch.New()
	diffs := dmp.DiffMain(text1, text2, true)

	return dmp.DiffPrettyText(diffs), nil
}

func extractPlainText(doc model.ProseMirrorDoc) string {
	if doc.Content == nil {
		return ""
	}
	contentBytes, err := json.Marshal(doc.Content)
	if err != nil {
		return ""
	}

	var builder []byte
	var walk func(interface{})
	walk = func(v interface{}) {
		switch val := v.(type) {
		case map[string]interface{}:
			if text, ok := val["text"].(string); ok {
				builder = append(builder, []byte(text)...)
				builder = append(builder, ' ')
			}
			if children, ok := val["content"].([]interface{}); ok {
				for _, c := range children {
					walk(c)
				}
			}
		case []interface{}:
			for _, item := range val {
				walk(item)
			}
		}
	}
	var arr []interface{}
	_ = json.Unmarshal(contentBytes, &arr)
	for _, item := range arr {
		walk(item)
	}
	return string(builder)
}
