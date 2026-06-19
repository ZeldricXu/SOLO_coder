package service

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"errors"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type TranslationMemoryRepository interface {
	Create(ctx context.Context, tm *model.TranslationMemory) error
	Update(ctx context.Context, tm *model.TranslationMemory) error
	GetBySourceMD5(ctx context.Context, tenantID, sourceMD5, srcLang, tgtLang string) (*model.TranslationMemory, error)
	SearchByLang(ctx context.Context, tenantID uuid.UUID, srcLang, tgtLang string) ([]*model.TranslationMemory, error)
}

type TranslationMemoryRepoImpl struct {
	db *gorm.DB
}

func NewTranslationMemoryRepo(db *gorm.DB) *TranslationMemoryRepoImpl {
	return &TranslationMemoryRepoImpl{db: db}
}

func (r *TranslationMemoryRepoImpl) Create(ctx context.Context, tm *model.TranslationMemory) error {
	return r.db.WithContext(ctx).Create(tm).Error
}

func (r *TranslationMemoryRepoImpl) Update(ctx context.Context, tm *model.TranslationMemory) error {
	return r.db.WithContext(ctx).Save(tm).Error
}

func (r *TranslationMemoryRepoImpl) GetBySourceMD5(ctx context.Context, tenantID, sourceMD5, srcLang, tgtLang string) (*model.TranslationMemory, error) {
	var tm model.TranslationMemory
	err := r.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("source_md5 = ? AND source_lang = ? AND target_lang = ? AND tenant_id = ?", sourceMD5, srcLang, tgtLang, tenantID).
		First(&tm).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &tm, nil
}

func (r *TranslationMemoryRepoImpl) SearchByLang(ctx context.Context, tenantID uuid.UUID, srcLang, tgtLang string) ([]*model.TranslationMemory, error) {
	var tms []*model.TranslationMemory
	err := r.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("source_lang = ? AND target_lang = ?", srcLang, tgtLang).
		Find(&tms).Error
	if err != nil {
		return nil, err
	}
	return tms, nil
}

type I18nService struct {
	db     *gorm.DB
	tmRepo TranslationMemoryRepository
}

func NewI18nService(db *gorm.DB) *I18nService {
	return &I18nService{
		db:     db,
		tmRepo: NewTranslationMemoryRepo(db),
	}
}

func (s *I18nService) ForkTranslation(ctx context.Context, baseDocID uuid.UUID, targetLang string, translatorID uuid.UUID) (*model.Document, *model.I18nDoc, error) {
	tenantIDStr, ok := database.GetTenantID(ctx)
	if !ok || tenantIDStr == "" {
		return nil, nil, errors.New("tenant context missing")
	}

	var baseDoc model.Document
	err := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("id = ?", baseDocID.String()).
		First(&baseDoc).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil, errors.New("base document not found")
		}
		return nil, nil, fmt.Errorf("get base document: %w", err)
	}

	if !baseDoc.IsBaseLang {
		return nil, nil, errors.New("source document is not a base language version")
	}

	if baseDoc.LangCode == targetLang {
		return nil, nil, errors.New("target language is same as base language")
	}

	now := time.Now().UTC()
	translatedDoc := &model.Document{
		TenantScoped:        model.TenantScoped{TenantID: tenantIDStr},
		SpaceID:             baseDoc.SpaceID,
		DirectoryID:         baseDoc.DirectoryID,
		Title:               baseDoc.Title,
		Slug:                baseDoc.Slug,
		Summary:             baseDoc.Summary,
		Content:             baseDoc.Content,
		ContentText:         baseDoc.ContentText,
		ContentType:         baseDoc.ContentType,
		LangCode:            targetLang,
		Category:            baseDoc.Category,
		Tags:                baseDoc.Tags,
		Status:              "draft",
		Priority:            baseDoc.Priority,
		Version:             1,
		IsPublic:            baseDoc.IsPublic,
		IsPinned:            baseDoc.IsPinned,
		CreatedBy:           translatorID.String(),
		UpdatedBy:           translatorID.String(),
		ParentDocID:         baseDoc.ID,
		OriginalID:          baseDoc.OriginalID,
		Metadata:            baseDoc.Metadata,
		IsBaseLang:          false,
		BaseDocID:           baseDoc.ID,
		BaseVersion:         baseDoc.Version,
		NeedsReTranslation:  false,
		LastTranslationAt:   &now,
		TranslationProgress: 0,
	}

	i18nDoc := &model.I18nDoc{
		TenantScoped:         model.TenantScoped{TenantID: tenantIDStr},
		SourceDocID:          baseDoc.ID,
		SourceLang:           baseDoc.LangCode,
		TargetLang:           targetLang,
		Status:               "draft",
		Progress:             0,
		TranslatedBy:         translatorID.String(),
		BaseVersionForked:    baseDoc.Version,
		LastBaseVersionSynced: baseDoc.Version,
	}

	err = s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(translatedDoc).Error; err != nil {
			return err
		}
		i18nDoc.TargetDocID = translatedDoc.ID
		if err := tx.Create(i18nDoc).Error; err != nil {
			return err
		}
		return nil
	})
	if err != nil {
		return nil, nil, fmt.Errorf("fork translation: %w", err)
	}

	return translatedDoc, i18nDoc, nil
}

func (s *I18nService) MarkBaseUpdated(ctx context.Context, baseDocID uuid.UUID) error {
	var baseDoc model.Document
	err := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("id = ?", baseDocID.String()).
		First(&baseDoc).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return errors.New("base document not found")
		}
		return fmt.Errorf("get base document: %w", err)
	}

	if !baseDoc.IsBaseLang {
		return errors.New("document is not a base language version")
	}

	result := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Model(&model.Document{}).
		Where("base_doc_id = ? AND needs_retranslation = ? AND is_base_lang = ?", baseDocID.String(), false, false).
		Updates(map[string]interface{}{
			"needs_retranslation": true,
		})
	if result.Error != nil {
		return fmt.Errorf("mark translations outdated: %w", result.Error)
	}

	if result.RowsAffected > 0 {
		log.Printf("Marked %d translations as needing re-translation for base doc %s", result.RowsAffected, baseDocID.String())
	}

	return nil
}

func (s *I18nService) GetDocumentVariants(ctx context.Context, docID uuid.UUID) ([]*model.Document, error) {
	var doc model.Document
	err := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("id = ?", docID.String()).
		First(&doc).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("document not found")
		}
		return nil, fmt.Errorf("get document: %w", err)
	}

	var variants []*model.Document

	if doc.IsBaseLang {
		err = s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
			Where("id = ? OR base_doc_id = ?", doc.ID, doc.ID).
			Find(&variants).Error
	} else {
		if doc.BaseDocID != "" {
			err = s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
				Where("id = ? OR base_doc_id = ? OR id = ?", doc.BaseDocID, doc.BaseDocID, doc.ID).
				Find(&variants).Error
		} else {
			variants = []*model.Document{&doc}
			return variants, nil
		}
	}

	if err != nil {
		return nil, fmt.Errorf("get document variants: %w", err)
	}

	return variants, nil
}

func (s *I18nService) GetTranslationSuggestions(ctx context.Context, tenantID uuid.UUID, sourceLang, targetLang, sourceText string, threshold float64) ([]*model.TranslationMemory, error) {
	if sourceText == "" {
		return nil, errors.New("source text is required")
	}
	if threshold < 0 || threshold > 1 {
		threshold = 0.7
	}

	tenantIDStr := tenantID.String()
	sourceMD5 := computeMD5(sourceText)

	exact, err := s.tmRepo.GetBySourceMD5(ctx, tenantIDStr, sourceMD5, sourceLang, targetLang)
	if err != nil {
		return nil, fmt.Errorf("get exact match: %w", err)
	}
	if exact != nil {
		return []*model.TranslationMemory{exact}, nil
	}

	allTMs, err := s.tmRepo.SearchByLang(ctx, tenantID, sourceLang, targetLang)
	if err != nil {
		return nil, fmt.Errorf("search translation memory: %w", err)
	}

	matcher := &TranslationMatcher{}
	var results []*model.TranslationMemory
	for _, tm := range allTMs {
		sim := matcher.ComputeSimilarity(sourceText, tm.SourceText)
		if sim >= threshold {
			results = append(results, tm)
		}
	}

	sortBySimilarityDesc(results, sourceText, matcher)

	return results, nil
}

func (s *I18nService) UpdateTranslationProgress(ctx context.Context, docID uuid.UUID, progress int, translatorID uuid.UUID) error {
	if progress < 0 || progress > 100 {
		return errors.New("progress must be between 0 and 100")
	}

	tenantIDStr, _ := database.GetTenantID(ctx)
	now := time.Now().UTC()

	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		result := tx.Scopes(database.TenantScope(ctx)).
			Model(&model.Document{}).
			Where("id = ?", docID.String()).
			Updates(map[string]interface{}{
				"translation_progress": progress,
				"last_translation_at":  &now,
				"updated_by":           translatorID.String(),
			})
		if result.Error != nil {
			return result.Error
		}
		if result.RowsAffected == 0 {
			return errors.New("document not found")
		}

		result = tx.Scopes(database.TenantScope(ctx)).
			Model(&model.I18nDoc{}).
			Where("target_doc_id = ?", docID.String()).
			Updates(map[string]interface{}{
				"progress":      progress,
				"translated_by": translatorID.String(),
			})
		if result.Error != nil {
			return result.Error
		}

		_ = tenantIDStr
		return nil
	})
	if err != nil {
		return fmt.Errorf("update translation progress: %w", err)
	}

	return nil
}

func (s *I18nService) ApproveTranslation(ctx context.Context, docID uuid.UUID, reviewerID uuid.UUID) error {
	tenantIDStr, _ := database.GetTenantID(ctx)
	now := time.Now().UTC()

	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		result := tx.Scopes(database.TenantScope(ctx)).
			Model(&model.Document{}).
			Where("id = ?", docID.String()).
			Updates(map[string]interface{}{
				"status":               "published",
				"translation_progress": 100,
				"needs_retranslation":  false,
				"last_translation_at":  &now,
				"updated_by":           reviewerID.String(),
			})
		if result.Error != nil {
			return result.Error
		}
		if result.RowsAffected == 0 {
			return errors.New("document not found")
		}

		var doc model.Document
		if err := tx.Scopes(database.TenantScope(ctx)).
			Where("id = ?", docID.String()).
			First(&doc).Error; err != nil {
			return err
		}

		result = tx.Scopes(database.TenantScope(ctx)).
			Model(&model.I18nDoc{}).
			Where("target_doc_id = ?", docID.String()).
			Updates(map[string]interface{}{
				"status":                "approved",
				"progress":              100,
				"reviewed_by":           reviewerID.String(),
				"last_base_version_synced": doc.BaseVersion,
			})
		if result.Error != nil {
			return result.Error
		}

		_ = tenantIDStr
		return nil
	})
	if err != nil {
		return fmt.Errorf("approve translation: %w", err)
	}

	return nil
}

func (s *I18nService) StoreTranslationMemory(ctx context.Context, tenantID uuid.UUID, srcLang, tgtLang, srcText, tgtText string, sourceDocID uuid.UUID, domain string, quality float64) (*model.TranslationMemory, error) {
	if srcText == "" || tgtText == "" {
		return nil, errors.New("source and target text are required")
	}

	tenantIDStr := tenantID.String()
	sourceMD5 := computeMD5(srcText)

	existing, err := s.tmRepo.GetBySourceMD5(ctx, tenantIDStr, sourceMD5, srcLang, tgtLang)
	if err != nil {
		return nil, fmt.Errorf("check existing tm: %w", err)
	}

	if existing != nil {
		existing.UsageCount++
		existing.TargetText = tgtText
		if quality > 0 {
			existing.Quality = quality
		}
		if err := s.tmRepo.Update(ctx, existing); err != nil {
			return nil, fmt.Errorf("update tm: %w", err)
		}
		return existing, nil
	}

	tm := &model.TranslationMemory{
		TenantScoped: model.TenantScoped{TenantID: tenantIDStr},
		SourceLang:   srcLang,
		TargetLang:   tgtLang,
		SourceText:   srcText,
		TargetText:   tgtText,
		SourceDocID:  sourceDocID.String(),
		Domain:       domain,
		UsageCount:   1,
		Quality:      quality,
		SourceMD5:    sourceMD5,
	}

	if err := s.tmRepo.Create(ctx, tm); err != nil {
		return nil, fmt.Errorf("create tm: %w", err)
	}

	return tm, nil
}

func (s *I18nService) BatchTranslateWithTM(ctx context.Context, docID uuid.UUID, targetLang string) (map[string]string, error) {
	tenantIDStr, ok := database.GetTenantID(ctx)
	if !ok || tenantIDStr == "" {
		return nil, errors.New("tenant context missing")
	}
	tenantID, err := uuid.Parse(tenantIDStr)
	if err != nil {
		return nil, fmt.Errorf("invalid tenant id: %w", err)
	}

	var doc model.Document
	err = s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("id = ?", docID.String()).
		First(&doc).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("document not found")
		}
		return nil, fmt.Errorf("get document: %w", err)
	}

	matcher := &TranslationMatcher{}
	paragraphs := matcher.ExtractParagraphs(doc.Content)

	allTMs, err := s.tmRepo.SearchByLang(ctx, tenantID, doc.LangCode, targetLang)
	if err != nil {
		return nil, fmt.Errorf("search translation memory: %w", err)
	}

	results := make(map[string]string)
	for _, p := range paragraphs {
		if strings.TrimSpace(p) == "" {
			continue
		}
		translated, _, hit := matcher.MatchParagraph(ctx, tenantID, doc.LangCode, targetLang, p, allTMs, 0.7)
		if hit {
			results[p] = translated
		}
	}

	return results, nil
}

func computeMD5(text string) string {
	hash := md5.Sum([]byte(text))
	return hex.EncodeToString(hash[:])
}

func sortBySimilarityDesc(tms []*model.TranslationMemory, source string, matcher *TranslationMatcher) {
	for i := 0; i < len(tms); i++ {
		for j := i + 1; j < len(tms); j++ {
			simI := matcher.ComputeSimilarity(source, tms[i].SourceText)
			simJ := matcher.ComputeSimilarity(source, tms[j].SourceText)
			if simJ > simI {
				tms[i], tms[j] = tms[j], tms[i]
			}
		}
	}
}
