package docindex

import (
	"context"
	"depguard/database"
	"depguard/logger"
	"depguard/search"
	"depguard/utils"
	"errors"
	"fmt"
	"github.com/blevesearch/bleve/v2"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"strings"
	"time"
)

type SearchEngine interface {
	Index(id string, data map[string]interface{}) error
	Delete(id string) error
	Search(query string, page, size int) (*IndexSearchResult, error)
}

type IndexSearchResult struct {
	Total int64
	Hits  []IndexSearchHit
}

type IndexSearchHit struct {
	ID    string
	Score float64
}

type Service struct {
	db    *gorm.DB
	index SearchEngine
}

func NewService() *Service {
	return &Service{
		db:    database.Get(),
		index: &searchAdapter{engine: search.Get()},
	}
}

func NewServiceWithDeps(db *gorm.DB, index SearchEngine) *Service {
	return &Service{
		db:    db,
		index: index,
	}
}

type searchAdapter struct {
	engine *search.Engine
}

func (a *searchAdapter) Index(id string, data map[string]interface{}) error {
	return a.engine.Index(id, data)
}

func (a *searchAdapter) Delete(id string) error {
	return a.engine.Delete(id)
}

func (a *searchAdapter) Search(query string, page, size int) (*IndexSearchResult, error) {
	res, err := a.engine.Search(query, page, size)
	if err != nil {
		return nil, err
	}
	hits := make([]IndexSearchHit, len(res.Hits))
	for i, h := range res.Hits {
		hits[i] = IndexSearchHit{ID: h.ID, Score: h.Score}
	}
	return &IndexSearchResult{Total: res.Total, Hits: hits}, nil
}

func (s *Service) CreateDocument(ctx context.Context, doc *Document) (*Document, error) {
	doc.ID = utils.GenerateID("doc")
	doc.CreatedAt = time.Now()
	doc.UpdatedAt = time.Now()

	if err := s.db.WithContext(ctx).Create(doc).Error; err != nil {
		return nil, err
	}

	err := s.index.Index(doc.ID, map[string]interface{}{
		"title":   doc.Title,
		"content": doc.Content,
		"tags":    strings.Join(doc.Tags, " "),
		"source":  doc.Source,
	})
	if err != nil {
		logger.Get().Warn("failed to index document", zap.String("id", doc.ID), zap.Error(err))
	}

	return doc, nil
}

func (s *Service) GetDocument(ctx context.Context, id string) (*Document, error) {
	var doc Document
	if err := s.db.WithContext(ctx).First(&doc, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &doc, nil
}

func (s *Service) UpdateDocument(ctx context.Context, doc *Document) (*Document, error) {
	doc.UpdatedAt = time.Now()
	if err := s.db.WithContext(ctx).Save(doc).Error; err != nil {
		return nil, err
	}

	err := s.index.Index(doc.ID, map[string]interface{}{
		"title":   doc.Title,
		"content": doc.Content,
		"tags":    strings.Join(doc.Tags, " "),
		"source":  doc.Source,
	})
	if err != nil {
		logger.Get().Warn("failed to reindex document", zap.String("id", doc.ID), zap.Error(err))
	}

	return doc, nil
}

func (s *Service) DeleteDocument(ctx context.Context, id string) error {
	if err := s.db.WithContext(ctx).Delete(&Document{}, "id = ?", id).Error; err != nil {
		return err
	}

	return s.index.Delete(id)
}

func (s *Service) Search(ctx context.Context, q *SearchQuery) ([]SearchResult, int64, error) {
	if q.Page < 0 {
		q.Page = 0
	}
	if q.Size <= 0 || q.Size > 100 {
		q.Size = 20
	}

	var queryParts []string
	if q.Query != "" {
		queryParts = append(queryParts, q.Query)
	}
	if q.Source != "" {
		queryParts = append(queryParts, fmt.Sprintf("source:%s", q.Source))
	}
	for _, tag := range q.Tags {
		queryParts = append(queryParts, fmt.Sprintf("tags:%s", tag))
	}

	searchQuery := strings.Join(queryParts, " AND ")
	if searchQuery == "" {
		searchQuery = "*"
	}

	results, err := s.index.Search(searchQuery, q.Page, q.Size)
	if err != nil {
		return nil, 0, err
	}

	var docIDs []string
	idToScore := make(map[string]float64)
	for _, hit := range results.Hits {
		docIDs = append(docIDs, hit.ID)
		idToScore[hit.ID] = hit.Score
	}

	if len(docIDs) == 0 {
		return []SearchResult{}, results.Total, nil
	}

	var docs []Document
	if err := s.db.WithContext(ctx).Where("id IN ?", docIDs).Find(&docs).Error; err != nil {
		return nil, 0, err
	}

	filteredDocs := s.filterByPermissions(docs, q.UserID, q.Roles)

	var searchResults []SearchResult
	for _, doc := range filteredDocs {
		searchResults = append(searchResults, SearchResult{
			ID:      doc.ID,
			Title:   doc.Title,
			Source:  doc.Source,
			Tags:    doc.Tags,
			Score:   idToScore[doc.ID],
			Snippet: extractSnippet(doc.Content, q.Query),
		})
	}

	return searchResults, int64(len(filteredDocs)), nil
}

func (s *Service) filterByPermissions(docs []Document, userID string, roles []string) []Document {
	var filtered []Document
	for _, doc := range docs {
		if s.hasAccess(&doc, userID, roles) {
			filtered = append(filtered, doc)
		}
	}
	return filtered
}

func (s *Service) hasAccess(doc *Document, userID string, roles []string) bool {
	if doc.Permissions.Public {
		return true
	}
	if doc.Permissions.OwnerID == userID {
		return true
	}
	for _, allowedUser := range doc.Permissions.ReadUsers {
		if allowedUser == userID {
			return true
		}
	}
	for _, role := range roles {
		for _, allowedRole := range doc.Permissions.ReadRoles {
			if allowedRole == role {
				return true
			}
		}
	}
	return false
}

func extractSnippet(content, query string) string {
	if query == "" || content == "" {
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
	end := idx + len(query) + 150
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

func (s *Service) ListSources(ctx context.Context) ([]DocumentSource, error) {
	var sources []DocumentSource
	if err := s.db.WithContext(ctx).Order("created_at DESC").Find(&sources).Error; err != nil {
		return nil, err
	}
	return sources, nil
}

func (s *Service) CreateSource(ctx context.Context, src *DocumentSource) (*DocumentSource, error) {
	src.ID = utils.GenerateID("src")
	src.CreatedAt = time.Now()
	src.UpdatedAt = time.Now()
	src.Enabled = true

	if err := s.db.WithContext(ctx).Create(src).Error; err != nil {
		return nil, err
	}
	return src, nil
}

func (s *Service) SyncSource(ctx context.Context, sourceID string) (*SyncJob, error) {
	var src DocumentSource
	if err := s.db.WithContext(ctx).First(&src, "id = ?", sourceID).Error; err != nil {
		return nil, err
	}
	if !src.Enabled {
		return nil, errors.New("source is disabled")
	}

	job := &SyncJob{
		ID:        utils.GenerateID("job"),
		SourceID:  sourceID,
		Status:    "running",
		StartedAt: time.Now(),
	}
	if err := s.db.WithContext(ctx).Create(job).Error; err != nil {
		return nil, err
	}

	go s.runSyncJob(ctx, job, &src)

	return job, nil
}

func (s *Service) runSyncJob(ctx context.Context, job *SyncJob, src *DocumentSource) {
	count := 0
	var err error

	defer func() {
		now := time.Now()
		job.CompletedAt = &now
		if err != nil {
			errStr := err.Error()
			job.Error = &errStr
			job.Status = "failed"
		} else {
			job.Status = "completed"
			job.DocumentCount = count
			src.LastSync = &now
			s.db.Save(src)
		}
		s.db.Save(job)
	}()

	docs, err := s.fetchFromSource(src)
	if err != nil {
		return
	}

	for _, doc := range docs {
		doc.Source = src.Type
		_, createErr := s.CreateDocument(ctx, doc)
		if createErr != nil {
			logger.Get().Warn("failed to create synced doc", zap.Error(createErr))
			continue
		}
		count++
	}
}

func (s *Service) fetchFromSource(src *DocumentSource) ([]*Document, error) {
	now := time.Now()
	return []*Document{
		{
			ID:      utils.GenerateID("doc"),
			Source:  src.Type,
			Title:   fmt.Sprintf("Doc from %s at %s", src.Name, now.Format(time.RFC3339)),
			Content: "This is an example document fetched from the source.",
			Tags:    []string{"synced", src.Type},
			Permissions: DocPermissions{
				Public:  true,
				OwnerID: "system",
			},
			Metadata:  map[string]string{"source_id": src.ID},
			CreatedAt: now,
			UpdatedAt: now,
		},
	}, nil
}

func (s *Service) ListDocuments(ctx context.Context, page, size int) ([]Document, int64, error) {
	if page < 0 {
		page = 0
	}
	if size <= 0 || size > 100 {
		size = 20
	}

	var docs []Document
	var total int64

	if err := s.db.WithContext(ctx).Model(&Document{}).Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := s.db.WithContext(ctx).
		Order("created_at DESC").
		Offset(page * size).
		Limit(size).
		Find(&docs).Error; err != nil {
		return nil, 0, err
	}

	return docs, total, nil
}
