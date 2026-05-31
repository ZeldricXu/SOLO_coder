package service

import (
	"context"
	"fmt"
	"strings"
	"time"

	"projectservice/internal/infrastructure/logger"
	"projectservice/internal/infrastructure/monitor"
	"projectservice/internal/model"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

type DocumentService struct {
	db      *gorm.DB
	logger  *logger.Logger
	metrics *monitor.Metrics
}

func NewDocumentService(db *gorm.DB, log *logger.Logger, metrics *monitor.Metrics) *DocumentService {
	return &DocumentService{
		db:      db,
		logger:  log,
		metrics: metrics,
	}
}

func (s *DocumentService) IndexDocument(ctx context.Context, req *model.IndexDocumentRequest) (*model.DocumentIndex, error) {
	start := time.Now()
	defer func() {
		s.metrics.ObserveTaskDuration("document", "index", "success", time.Since(start))
	}()

	doc := &model.DocumentIndex{
		ID:             uuid.New().String(),
		Title:          req.Title,
		Source:         req.Source,
		SourceURL:      req.SourceURL,
		Category:       req.Category,
		Tags:           req.Tags,
		Content:        req.Content,
		IndexedContent: s.buildIndexContent(req.Content),
		Permissions:    req.Permissions,
		Owner:          req.Owner,
		LastModified:   req.LastModified,
		LastIndexed:    time.Now(),
		Version:        1,
		Metadata:       req.Metadata,
		CreatedAt:      time.Now(),
		UpdatedAt:      time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(doc).Error; err != nil {
		s.metrics.ObserveError("document", "db_error")
		return nil, fmt.Errorf("failed to index document: %w", err)
	}

	return doc, nil
}

func (s *DocumentService) buildIndexContent(content string) string {
	content = strings.ToLower(content)
	content = strings.TrimSpace(content)
	return content
}

func (s *DocumentService) GetDocument(ctx context.Context, docID string) (*model.DocumentIndex, error) {
	var doc model.DocumentIndex
	if err := s.db.WithContext(ctx).Where("id = ?", docID).First(&doc).Error; err != nil {
		return nil, fmt.Errorf("document not found: %w", err)
	}
	return &doc, nil
}

func (s *DocumentService) SearchDocuments(ctx context.Context, req *model.SearchDocumentRequest) (*model.SearchDocumentResponse, error) {
	start := time.Now()
	defer func() {
		s.metrics.ObserveTaskDuration("document", "search", "success", time.Since(start))
	}()

	var docs []model.DocumentIndex
	var total int64

	query := s.db.WithContext(ctx).Model(&model.DocumentIndex{})

	if req.Query != "" {
		pattern := fmt.Sprintf("%%%s%%", strings.ToLower(req.Query))
		query = query.Where("indexed_content ILIKE ? OR title ILIKE ?", pattern, pattern)
	}
	if req.Source != "" {
		query = query.Where("source = ?", req.Source)
	}
	if req.Category != "" {
		query = query.Where("category = ?", req.Category)
	}
	if len(req.Tags) > 0 {
		query = query.Where("tags @> ?", req.Tags)
	}

	if req.UserID != "" {
		query = query.Where(
			"permissions @> ? OR owner = ? OR permissions @> ?",
			[]string{req.UserID},
			req.UserID,
			[]string{"public"},
		)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, err
	}

	offset := (req.Page - 1) * req.PageSize
	if err := query.Offset(offset).Limit(req.PageSize).Order("last_indexed DESC").Find(&docs).Error; err != nil {
		return nil, err
	}

	results := make([]model.SearchDocumentResult, 0, len(docs))
	for _, doc := range docs {
		snippet := s.buildSnippet(doc.Content, req.Query)
		results = append(results, model.SearchDocumentResult{
			ID:           doc.ID,
			Title:        doc.Title,
			Source:       doc.Source,
			SourceURL:    doc.SourceURL,
			Category:     doc.Category,
			Tags:         doc.Tags,
			Snippet:      snippet,
			Score:        1.0,
			LastModified: doc.LastModified,
		})
	}

	facets := s.buildFacets(docs)

	return &model.SearchDocumentResponse{
		Total:  total,
		Docs:   results,
		Facets: facets,
	}, nil
}

func (s *DocumentService) buildSnippet(content, query string) string {
	if query == "" {
		if len(content) > 200 {
			return content[:200] + "..."
		}
		return content
	}

	lowerContent := strings.ToLower(content)
	lowerQuery := strings.ToLower(query)

	idx := strings.Index(lowerContent, lowerQuery)
	if idx == -1 {
		if len(content) > 200 {
			return content[:200] + "..."
		}
		return content
	}

	start := idx - 50
	if start < 0 {
		start = 0
	}
	end := idx + len(query) + 50
	if end > len(content) {
		end = len(content)
	}

	snippet := content[start:end]
	if start > 0 {
		snippet = "..." + snippet
	}
	if end < len(content) {
		snippet = snippet + "..."
	}

	return snippet
}

func (s *DocumentService) buildFacets(docs []model.DocumentIndex) map[string]interface{} {
	facets := make(map[string]interface{})

	sourceCounts := make(map[string]int)
	categoryCounts := make(map[string]int)

	for _, doc := range docs {
		sourceCounts[doc.Source]++
		categoryCounts[doc.Category]++
	}

	facets["sources"] = sourceCounts
	facets["categories"] = categoryCounts

	return facets
}

func (s *DocumentService) SyncDocuments(ctx context.Context, req *model.SyncDocumentsRequest) ([]model.SyncResult, error) {
	start := time.Now()
	defer func() {
		s.metrics.ObserveTaskDuration("document", "sync", "success", time.Since(start))
	}()

	var results []model.SyncResult

	sources := req.Sources
	if len(sources) == 0 {
		sources = []string{"wiki", "git", "api"}
	}

	for _, source := range sources {
		result := model.SyncResult{
			Source: source,
		}

		var docs []model.DocumentIndex
		query := s.db.WithContext(ctx).Where("source = ?", source)
		if !req.Force {
			query = query.Where("last_indexed < ?", time.Now().Add(-24*time.Hour))
		}

		if err := query.Find(&docs).Error; err != nil {
			result.Failed = len(docs)
			results = append(results, result)
			continue
		}

		for _, doc := range docs {
			doc.IndexedContent = s.buildIndexContent(doc.Content)
			doc.LastIndexed = time.Now()
			doc.Version++

			if err := s.db.WithContext(ctx).Save(&doc).Error; err != nil {
				result.Failed++
			} else {
				result.Updated++
			}
		}

		results = append(results, result)
	}

	return results, nil
}

func (s *DocumentService) UpdateDocument(ctx context.Context, docID string, updates map[string]interface{}) error {
	if content, ok := updates["content"]; ok {
		if str, ok := content.(string); ok {
			updates["indexed_content"] = s.buildIndexContent(str)
		}
	}
	updates["last_indexed"] = time.Now()
	updates["updated_at"] = time.Now()

	result := s.db.WithContext(ctx).
		Model(&model.DocumentIndex{}).
		Where("id = ?", docID).
		Updates(updates)

	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("document not found")
	}
	return nil
}

func (s *DocumentService) DeleteDocument(ctx context.Context, docID string) error {
	result := s.db.WithContext(ctx).Delete(&model.DocumentIndex{}, "id = ?", docID)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("document not found")
	}
	return nil
}

func (s *DocumentService) ListDocuments(ctx context.Context, source, category, owner string, page, pageSize int) ([]model.DocumentIndex, int64, error) {
	var docs []model.DocumentIndex
	var total int64

	query := s.db.WithContext(ctx).Model(&model.DocumentIndex{})

	if source != "" {
		query = query.Where("source = ?", source)
	}
	if category != "" {
		query = query.Where("category = ?", category)
	}
	if owner != "" {
		query = query.Where("owner = ?", owner)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&docs).Error; err != nil {
		return nil, 0, err
	}

	return docs, total, nil
}
