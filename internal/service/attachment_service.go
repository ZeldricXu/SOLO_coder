package service

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log"
	"path/filepath"
	"strings"

	"github.com/blevesearch/bleve/v2"
	"github.com/enterprise/knowledgebase/internal/config"
	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/pkg/utils"
	"github.com/enterprise/knowledgebase/internal/search"
	"github.com/google/uuid"
	"github.com/minio/minio-go/v7"
	"gorm.io/gorm"
)

type AttachmentService struct {
	db          *gorm.DB
	tikaClient  *search.TikaClient
	ocrRegistry *search.OCRServiceRegistry
	minioClient *minio.Client
	config      *config.Config
	bleveIndex  bleve.Index
}

func NewAttachmentService(
	db *gorm.DB,
	tikaClient *search.TikaClient,
	ocrRegistry *search.OCRServiceRegistry,
	minioClient *minio.Client,
	cfg *config.Config,
	bleveIndex bleve.Index,
) *AttachmentService {
	return &AttachmentService{
		db:          db,
		tikaClient:  tikaClient,
		ocrRegistry: ocrRegistry,
		minioClient: minioClient,
		config:      cfg,
		bleveIndex:  bleveIndex,
	}
}

func (s *AttachmentService) UploadAndParse(
	ctx context.Context,
	userID uuid.UUID,
	spaceID uuid.UUID,
	docID uuid.UUID,
	fileName string,
	fileData []byte,
) (*model.Attachment, error) {
	if fileName == "" {
		return nil, errors.New("file name is required")
	}
	if len(fileData) == 0 {
		return nil, errors.New("file data is empty")
	}

	tenantIDStr, ok := database.GetTenantID(ctx)
	if !ok || tenantIDStr == "" {
		return nil, errors.New("tenant context missing")
	}
	tenantID, err := uuid.Parse(tenantIDStr)
	if err != nil {
		return nil, fmt.Errorf("invalid tenant id: %w", err)
	}

	safeFileName := utils.SanitizeFilename(fileName)
	fileExt := strings.ToLower(filepath.Ext(safeFileName))
	fileType := strings.TrimPrefix(fileExt, ".")

	hash := md5.Sum(fileData)
	md5Hash := hex.EncodeToString(hash[:])

	storagePath := fmt.Sprintf("%s/%s/%s%s",
		tenantIDStr,
		docID.String(),
		uuid.New().String(),
		fileExt,
	)

	bucket := s.config.MinIO.Bucket
	exists, err := s.minioClient.BucketExists(ctx, bucket)
	if err != nil {
		return nil, fmt.Errorf("check bucket exists: %w", err)
	}
	if !exists {
		if err := s.minioClient.MakeBucket(ctx, bucket, minio.MakeBucketOptions{}); err != nil {
			return nil, fmt.Errorf("create bucket: %w", err)
		}
	}

	_, err = s.minioClient.PutObject(ctx, bucket, storagePath,
		bytes.NewReader(fileData), int64(len(fileData)), minio.PutObjectOptions{
			ContentType: "application/octet-stream",
		})
	if err != nil {
		return nil, fmt.Errorf("upload to minio: %w", err)
	}

	att := &model.Attachment{
		TenantScoped: model.TenantScoped{TenantID: tenantIDStr},
		SpaceID:      spaceID.String(),
		DocID:        docID.String(),
		FileName:     safeFileName,
		OriginalName: fileName,
		FileType:     fileType,
		FileSize:     int64(len(fileData)),
		StoragePath:  storagePath,
		StorageType:  "minio",
		Md5Hash:      md5Hash,
		UploadedBy:   userID.String(),
		IsParsed:     false,
		ParseStatus:  "pending",
	}

	if err := s.db.WithContext(ctx).Create(att).Error; err != nil {
		return nil, fmt.Errorf("create attachment record: %w", err)
	}

	go func(attachmentID uuid.UUID) {
		parseCtx := database.WithTenant(context.Background(), tenantIDStr)
		if err := s.ParseAndIndex(parseCtx, attachmentID); err != nil {
			log.Printf("async parse attachment %s failed: %v", attachmentID, err)
		}
	}(uuid.MustParse(att.ID))

	return att, nil
}

func (s *AttachmentService) ParseAndIndex(ctx context.Context, attachmentID uuid.UUID) error {
	att, err := s.GetAttachment(ctx, attachmentID)
	if err != nil {
		return fmt.Errorf("get attachment: %w", err)
	}
	if att == nil {
		return errors.New("attachment not found")
	}

	if err := s.db.WithContext(ctx).Model(att).
		Updates(map[string]interface{}{"parse_status": "processing"}).Error; err != nil {
		return fmt.Errorf("update parse status: %w", err)
	}

	bucket := s.config.MinIO.Bucket
	obj, err := s.minioClient.GetObject(ctx, bucket, att.StoragePath, minio.GetObjectOptions{})
	if err != nil {
		s.markParseFailed(ctx, att, err.Error())
		return fmt.Errorf("get object from minio: %w", err)
	}
	defer obj.Close()

	fileData, err := io.ReadAll(obj)
	if err != nil {
		s.markParseFailed(ctx, att, err.Error())
		return fmt.Errorf("read object data: %w", err)
	}

	var extractedText string
	var langCode string

	if search.IsSupportedFileType(att.FileName) {
		if s.isImageFile(att.FileName) {
			if s.config.Tika.EnableOCR && s.ocrRegistry != nil {
				ocrText, ocrErr := s.ocrRegistry.ExtractText(ctx, fileData, att.FileName, s.config.Tika.OCRService)
				if ocrErr != nil {
					log.Printf("OCR extract failed: %v", ocrErr)
				}
				extractedText = ocrText
			}
		} else {
			parsed, parseErr := s.tikaClient.ParseFile(ctx, att.FileName, fileData)
			if parseErr != nil {
				s.markParseFailed(ctx, att, parseErr.Error())
				return fmt.Errorf("tika parse: %w", parseErr)
			}
			if !parsed.Success {
				s.markParseFailed(ctx, att, parsed.Error)
				return fmt.Errorf("tika parse failed: %s", parsed.Error)
			}
			extractedText = parsed.Content
			langCode = parsed.Language
		}
	}

	if langCode == "" {
		langCode = search.DetectLanguage(extractedText)
	}

	updates := map[string]interface{}{
		"extracted_text": extractedText,
		"is_parsed":      true,
		"parse_status":   "done",
	}
	if err := s.db.WithContext(ctx).Model(att).Updates(updates).Error; err != nil {
		return fmt.Errorf("update attachment parsed data: %w", err)
	}
	att.ExtractedText = extractedText
	att.IsParsed = true
	att.ParseStatus = "done"

	attIndexData := s.BuildAttachmentIndexData(att)
	if attIndexData != nil && s.bleveIndex != nil {
		if err := s.bleveIndex.Index(att.ID, attIndexData); err != nil {
			log.Printf("index attachment %s failed: %v", att.ID, err)
		}
	}

	if att.DocID != "" {
		if err := s.UpdateDocIndexWithAttachments(ctx, att.DocID); err != nil {
			log.Printf("update doc index with attachments failed: %v", err)
		}
	}

	return nil
}

func (s *AttachmentService) markParseFailed(ctx context.Context, att *model.Attachment, errMsg string) {
	_ = s.db.WithContext(ctx).Model(att).
		Updates(map[string]interface{}{
			"is_parsed":    false,
			"parse_status": "failed",
			"extracted_text": errMsg,
		})
}

func (s *AttachmentService) isImageFile(fileName string) bool {
	imageExts := map[string]struct{}{
		".png": {}, ".jpg": {}, ".jpeg": {}, ".gif": {},
		".bmp": {}, ".tiff": {}, ".webp": {},
	}
	lowerName := strings.ToLower(fileName)
	for ext := range imageExts {
		if strings.HasSuffix(lowerName, ext) {
			return true
		}
	}
	return false
}

func (s *AttachmentService) GetAttachment(ctx context.Context, attachmentID uuid.UUID) (*model.Attachment, error) {
	var att model.Attachment
	err := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("id = ?", attachmentID.String()).
		First(&att).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &att, nil
}

func (s *AttachmentService) DeleteAttachment(ctx context.Context, attachmentID uuid.UUID) error {
	att, err := s.GetAttachment(ctx, attachmentID)
	if err != nil {
		return err
	}
	if att == nil {
		return nil
	}

	bucket := s.config.MinIO.Bucket
	if att.StoragePath != "" {
		if err := s.minioClient.RemoveObject(ctx, bucket, att.StoragePath, minio.RemoveObjectOptions{}); err != nil {
			log.Printf("remove object from minio failed: %v", err)
		}
	}

	if s.bleveIndex != nil {
		if err := s.bleveIndex.Delete(att.ID); err != nil {
			log.Printf("delete attachment from bleve index failed: %v", err)
		}
	}

	docID := att.DocID

	if err := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("id = ?", attachmentID.String()).
		Delete(&model.Attachment{}).Error; err != nil {
		return fmt.Errorf("delete attachment record: %w", err)
	}

	if docID != "" {
		if err := s.UpdateDocIndexWithAttachments(ctx, docID); err != nil {
			log.Printf("update doc index after delete failed: %v", err)
		}
	}

	return nil
}

func (s *AttachmentService) ReindexAllAttachments(ctx context.Context, tenantID uuid.UUID) (int, int, error) {
	tenantIDStr := tenantID.String()
	reindexCtx := database.WithTenant(ctx, tenantIDStr)

	var attachments []*model.Attachment
	err := s.db.WithContext(reindexCtx).
		Where("tenant_id = ?", tenantIDStr).
		Find(&attachments).Error
	if err != nil {
		return 0, 0, fmt.Errorf("list attachments: %w", err)
	}

	successCount := 0
	failCount := 0

	for _, att := range attachments {
		attID, parseErr := uuid.Parse(att.ID)
		if parseErr != nil {
			failCount++
			continue
		}
		if err := s.ParseAndIndex(reindexCtx, attID); err != nil {
			failCount++
			log.Printf("reindex attachment %s failed: %v", att.ID, err)
			continue
		}
		successCount++
	}

	return successCount, failCount, nil
}

func (s *AttachmentService) BuildAttachmentIndexData(att *model.Attachment) *search.AttachmentIndex {
	if att == nil {
		return nil
	}

	return &search.AttachmentIndex{
		AttachmentID: att.ID,
		TenantID:     att.TenantID,
		SpaceID:      att.SpaceID,
		DocID:        att.DocID,
		FileName:     att.FileName,
		FileType:     att.FileType,
		FileSize:     att.FileSize,
		Content:      att.ExtractedText,
		LangCode:     search.DetectLanguage(att.ExtractedText),
		CreatedAt:    att.CreatedAt.Unix(),
		UpdatedAt:    att.UpdatedAt.Unix(),
	}
}

func (s *AttachmentService) UpdateDocIndexWithAttachments(ctx context.Context, docID string) error {
	if docID == "" || s.bleveIndex == nil {
		return nil
	}

	var attachments []*model.Attachment
	err := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("doc_id = ? AND is_parsed = ?", docID, true).
		Find(&attachments).Error
	if err != nil {
		return fmt.Errorf("list attachments for doc: %w", err)
	}

	attachmentTexts := make([]string, 0, len(attachments))
	attachmentIDs := make([]string, 0, len(attachments))
	for _, att := range attachments {
		attachmentIDs = append(attachmentIDs, att.ID)
		if att.ExtractedText != "" {
			attachmentTexts = append(attachmentTexts, att.ExtractedText)
		}
	}

	docQuery := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("id = ?", docID)
	var doc model.Document
	if err := docQuery.First(&doc).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil
		}
		return fmt.Errorf("get document: %w", err)
	}

	docIndex := &search.DocumentIndex{
		DocID:           doc.ID,
		TenantID:        doc.TenantID,
		SpaceID:         doc.SpaceID,
		Title:           doc.Title,
		Content:         doc.ContentText,
		Summary:         doc.Summary,
		Tags:            doc.Tags,
		AuthorID:        doc.CreatedBy,
		LangCode:        doc.LangCode,
		Category:        doc.Category,
		Status:          doc.Status,
		Priority:        doc.Priority,
		ViewCount:       doc.ViewCount,
		LikeCount:       doc.LikeCount,
		AttachmentIDs:   attachmentIDs,
		AttachmentTexts: attachmentTexts,
		CreatedAt:       doc.CreatedAt.Unix(),
		UpdatedAt:       doc.UpdatedAt.Unix(),
	}

	if doc.PublishedAt != nil {
		docIndex.PublishedAt = doc.PublishedAt.Unix()
	}

	if err := s.bleveIndex.Index(doc.ID, docIndex); err != nil {
		return fmt.Errorf("index document: %w", err)
	}

	return nil
}

func (s *AttachmentService) DownloadAttachment(ctx context.Context, attachmentID uuid.UUID) ([]byte, string, string, error) {
	att, err := s.GetAttachment(ctx, attachmentID)
	if err != nil {
		return nil, "", "", fmt.Errorf("get attachment: %w", err)
	}
	if att == nil {
		return nil, "", "", errors.New("attachment not found")
	}

	bucket := s.config.MinIO.Bucket
	obj, err := s.minioClient.GetObject(ctx, bucket, att.StoragePath, minio.GetObjectOptions{})
	if err != nil {
		return nil, "", "", fmt.Errorf("get object from minio: %w", err)
	}
	defer obj.Close()

	data, err := io.ReadAll(obj)
	if err != nil {
		return nil, "", "", fmt.Errorf("read object data: %w", err)
	}

	_, _ = s.db.WithContext(ctx).Model(att).
		UpdateColumn("download_count", gorm.Expr("download_count + ?", 1))

	return data, att.FileName, att.FileType, nil
}
