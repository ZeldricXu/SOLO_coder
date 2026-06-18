package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"time"

	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/pkg/utils"
	"github.com/enterprise/knowledgebase/internal/repository"
	"github.com/enterprise/knowledgebase/internal/search"
	"github.com/google/uuid"
)

type SearchService struct {
	indexManager  *search.IndexManager
	tikaClient    *search.TikaClient
	docRepo       *repository.DocumentRepository
	spaceRepo     *repository.SpaceRepository
	permRepo      *repository.PermissionRepository
	bleveMgr      *database.BleveManager
}

func NewSearchService(
	indexManager *search.IndexManager,
	tikaClient *search.TikaClient,
	docRepo *repository.DocumentRepository,
	spaceRepo *repository.SpaceRepository,
	permRepo *repository.PermissionRepository,
	bleveMgr *database.BleveManager,
) *SearchService {
	return &SearchService{
		indexManager: indexManager,
		tikaClient:   tikaClient,
		docRepo:      docRepo,
		spaceRepo:    spaceRepo,
		permRepo:     permRepo,
		bleveMgr:     bleveMgr,
	}
}

type SearchRequest struct {
	Query          string
	TenantID       uuid.UUID
	UserID         uuid.UUID
	GroupIDs       []uuid.UUID
	DeptIDs        []uuid.UUID
	SpaceID        uuid.UUID
	Types          []string
	Tags           []string
	LangCode       string
	Page           int
	PageSize       int
	SortBy         string
	SortOrder      string
	IncludeAttachments bool
	TimeDecay      bool
}

type SearchHitItem struct {
	ID             string                 `json:"id"`
	Score          float64                `json:"score"`
	Type           string                 `json:"type"`
	Title          string                 `json:"title"`
	Summary        string                 `json:"summary,omitempty"`
	Tags           []string               `json:"tags,omitempty"`
	SpaceID        string                 `json:"space_id"`
	DocID          string                 `json:"doc_id,omitempty"`
	AuthorID       string                 `json:"author_id,omitempty"`
	AuthorName     string                 `json:"author_name,omitempty"`
	ViewCount      int64                  `json:"view_count,omitempty"`
	LikeCount      int64                  `json:"like_count,omitempty"`
	CreatedAt      time.Time              `json:"created_at"`
	UpdatedAt      time.Time              `json:"updated_at"`
	Highlights     map[string][]string    `json:"highlights,omitempty"`
	Extra          map[string]interface{} `json:"extra,omitempty"`
}

type SearchResponse struct {
	Total      uint64          `json:"total"`
	MaxScore   float64         `json:"max_score"`
	TookMs     float64         `json:"took_ms"`
	Hits       []SearchHitItem `json:"hits"`
	Page       int             `json:"page"`
	PageSize   int             `json:"page_size"`
	TotalPages int             `json:"total_pages"`
}

func (s *SearchService) Search(ctx context.Context, req *SearchRequest) (*SearchResponse, error) {
	if req.Query == "" {
		return &SearchResponse{Hits: []SearchHitItem{}}, nil
	}

	allowedSpaceIDs, err := s.permRepo.GetAccessibleResources(
		ctx, req.UserID, req.GroupIDs, req.DeptIDs,
		model.ResourceTypeSpace, model.RoleViewer,
	)
	if err != nil {
		return nil, err
	}

	tenantIDStr := req.TenantID.String()

	searchReq := &database.SearchQuery{
		Query:    req.Query,
		Types:    req.Types,
		Tags:     req.Tags,
		Page:     req.Page,
		PageSize: req.PageSize,
		Highlight: true,
		SortBy:    req.SortBy,
		SortOrder: req.SortOrder,
	}

	if req.SpaceID != uuid.Nil {
		searchReq.SpaceID = req.SpaceID.String()
	} else if allowedSpaceIDs != nil {
		_ = allowedSpaceIDs
	}

	bleveResult, err := s.bleveMgr.Search(tenantIDStr, searchReq)
	if err != nil {
		return nil, fmt.Errorf("search failed: %w", err)
	}

	results := make([]SearchHitItem, 0, len(bleveResult.Hits))
	now := time.Now()

	for _, hit := range bleveResult.Hits {
		score := hit.Score
		if req.TimeDecay {
			if updatedAt, ok := getUpdatedAtFromHit(hit); ok {
				ageHours := now.Sub(updatedAt).Hours()
				decayFactor := math.Exp(-ageHours / (24 * 30))
				score = score * (0.3 + 0.7*decayFactor)
			}
		}

		item := SearchHitItem{
			ID:         hit.ID,
			Score:      score,
			Type:       hit.Type,
			Title:      hit.Title,
			Summary:    hit.Summary,
			Tags:       hit.Tags,
			SpaceID:    hit.SpaceID,
			DocID:      hit.DocID,
			AuthorID:   hit.AuthorID,
			Highlights: hit.Fragments,
		}
		results = append(results, item)
	}

	totalPages := int(bleveResult.Total) / req.PageSize
	if int(bleveResult.Total)%req.PageSize > 0 {
		totalPages++
	}

	return &SearchResponse{
		Total:      bleveResult.Total,
		MaxScore:   bleveResult.MaxScore,
		TookMs:     bleveResult.Took,
		Hits:       results,
		Page:       req.Page,
		PageSize:   req.PageSize,
		TotalPages: totalPages,
	}, nil
}

func getUpdatedAtFromHit(hit *database.SearchHit) (time.Time, bool) {
	if hit.Extra != nil {
		if ts, ok := hit.Extra["updated_at"]; ok {
			switch v := ts.(type) {
			case float64:
				return time.Unix(int64(v), 0), true
			case string:
				if t, err := time.Parse(time.RFC3339, v); err == nil {
					return t, true
				}
			}
		}
	}
	return time.Time{}, false
}

func (s *SearchService) IndexDocument(ctx context.Context, doc *model.Document) error {
	plainText := extractTextFromProseMirror(doc.Content)

	idxDoc := &database.IndexDocument{
		ID:       doc.ID.String(),
		Type:     "document",
		Title:    doc.Title,
		Content:  plainText,
		Summary:  doc.Summary,
		Tags:     []string(doc.Tags),
		AuthorID: doc.AuthorID.String(),
		SpaceID:  doc.SpaceID.String(),
		DocID:    doc.ID.String(),
		Extra: map[string]interface{}{
			"status":      doc.Status,
			"language":    doc.Language,
			"word_count":  doc.WordCount,
			"view_count":  doc.ViewCount,
			"like_count":  doc.LikeCount,
			"is_pinned":   doc.IsPinned,
			"published_at": doc.PublishedAt,
			"created_at":  doc.CreatedAt.Unix(),
			"updated_at":  doc.UpdatedAt.Unix(),
		},
	}

	return s.bleveMgr.Index(doc.TenantID.String(), idxDoc)
}

func (s *SearchService) RemoveDocument(ctx context.Context, tenantID, docID uuid.UUID) error {
	return s.bleveMgr.Delete(tenantID.String(), docID.String())
}

func (s *SearchService) IndexAttachment(ctx context.Context, att *model.Attachment, fileContent []byte) error {
	extractedText := ""
	if s.tikaClient != nil && len(fileContent) > 0 {
		tikaCtx, cancel := context.WithTimeout(ctx, 60*time.Second)
		defer cancel()
		parsed, err := s.tikaClient.ParseFile(tikaCtx, att.OriginalName, fileContent)
		if err == nil && parsed.Success {
			extractedText = parsed.Content
		}
	}

	if extractedText == "" && att.ExtractedText != "" {
		extractedText = att.ExtractedText
	}

	idxDoc := &database.IndexDocument{
		ID:       "att_" + att.ID.String(),
		Type:     "attachment",
		Title:    att.FileName,
		Content:  extractedText,
		Summary:  fmt.Sprintf("Attachment: %s, Size: %d bytes", att.FileName, att.FileSize),
		AuthorID: att.UploaderID.String(),
		SpaceID:  att.SpaceID.String(),
		DocID:    "",
		Extra: map[string]interface{}{
			"file_name":     att.FileName,
			"file_size":     att.FileSize,
			"mime_type":     att.MimeType,
			"is_image":      att.IsImage,
			"document_id":   att.DocumentID,
			"download_count": att.DownloadCount,
			"created_at":    att.CreatedAt.Unix(),
			"updated_at":    att.UpdatedAt.Unix(),
		},
	}
	if att.DocumentID != nil {
		idxDoc.DocID = att.DocumentID.String()
	}

	return s.bleveMgr.Index(att.TenantID.String(), idxDoc)
}

func (s *SearchService) RemoveAttachment(ctx context.Context, tenantID, attID uuid.UUID) error {
	return s.bleveMgr.Delete(tenantID.String(), "att_"+attID.String())
}

func (s *SearchService) ReindexSpace(ctx context.Context, tenantID, spaceID uuid.UUID) (int, error) {
	count := 0
	q := &model.DocumentQuery{
		TenantID: tenantID,
		SpaceID:  spaceID,
		Page:     1,
		PageSize: 100,
	}

	for {
		result, err := s.docRepo.List(ctx, q)
		if err != nil {
			return count, err
		}

		docs, ok := result.Data.([]model.Document)
		if !ok {
			return count, errors.New("invalid data type")
		}

		batch := make([]*database.IndexDocument, 0, len(docs))
		for _, doc := range docs {
			plainText := extractTextFromProseMirror(doc.Content)
			batch = append(batch, &database.IndexDocument{
				ID:       doc.ID.String(),
				Type:     "document",
				Title:    doc.Title,
				Content:  plainText,
				Summary:  doc.Summary,
				Tags:     []string(doc.Tags),
				AuthorID: doc.AuthorID.String(),
				SpaceID:  doc.SpaceID.String(),
				DocID:    doc.ID.String(),
			})
			count++
		}

		if err := s.bleveMgr.BatchIndex(tenantID.String(), batch); err != nil {
			return count, err
		}

		if q.Page >= result.TotalPages {
			break
		}
		q.Page++
	}

	return count, nil
}

func (s *SearchService) Suggest(ctx context.Context, tenantID uuid.UUID, query string, limit int) ([]string, error) {
	if len(query) < 2 {
		return []string{}, nil
	}

	sq := &database.SearchQuery{
		Query:     query,
		Page:      1,
		PageSize:  limit,
		Highlight: false,
		IncludeFields: []string{"title", "tags"},
	}

	result, err := s.bleveMgr.Search(tenantID.String(), sq)
	if err != nil {
		return nil, err
	}

	suggestions := make([]string, 0, len(result.Hits))
	seen := make(map[string]struct{})
	for _, hit := range result.Hits {
		if hit.Title != "" {
			truncated := utils.TruncateString(hit.Title, 100)
			if _, exists := seen[truncated]; !exists {
				suggestions = append(suggestions, truncated)
				seen[truncated] = struct{}{}
			}
		}
	}

	return suggestions, nil
}

func extractTextFromProseMirror(doc model.ProseMirrorDoc) string {
	contentBytes, err := json.Marshal(doc.Content)
	if err != nil {
		return ""
	}

	var textBuilder []string
	var extract func(content interface{})
	extract = func(content interface{}) {
		switch c := content.(type) {
		case map[string]interface{}:
			if t, ok := c["text"].(string); ok {
				textBuilder = append(textBuilder, t)
			}
			if children, ok := c["content"].([]interface{}); ok {
				for _, child := range children {
					extract(child)
				}
			}
		case []interface{}:
			for _, item := range c {
				extract(item)
			}
		}
	}

	var contentArr []interface{}
	_ = json.Unmarshal(contentBytes, &contentArr)
	for _, item := range contentArr {
		extract(item)
	}

	result := ""
	for _, t := range textBuilder {
		result += t + " "
	}
	return stringsTrimSpace(result)
}

func stringsTrimSpace(s string) string {
	start := 0
	end := len(s)
	for start < end && (s[start] == ' ' || s[start] == '\t' || s[start] == '\n') {
		start++
	}
	for end > start && (s[end-1] == ' ' || s[end-1] == '\t' || s[end-1] == '\n') {
		end--
	}
	return s[start:end]
}
