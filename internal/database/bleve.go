package database

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"

	"github.com/enterprise/knowledgebase/internal/config"
	"github.com/blevesearch/bleve/v2"
	"github.com/blevesearch/bleve/v2/analysis/analyzer/standard"
	"github.com/blevesearch/bleve/v2/mapping"
)

type BleveManager struct {
	basePath string
	indexes  map[string]bleve.Index
	mu       sync.RWMutex
}

var globalBleve *BleveManager

type IndexDocument struct {
	ID       string                 `json:"id"`
	Type     string                 `json:"type"`
	Title    string                 `json:"title"`
	Content  string                 `json:"content"`
	Summary  string                 `json:"summary"`
	Tags     []string               `json:"tags"`
	AuthorID string                 `json:"author_id"`
	SpaceID  string                 `json:"space_id"`
	DocID    string                 `json:"doc_id"`
	Extra    map[string]interface{} `json:"extra"`
}

type SearchResult struct {
	Total      uint64                  `json:"total"`
	MaxScore   float64                 `json:"max_score"`
	Took       float64                 `json:"took"`
	Hits       []*SearchHit            `json:"hits"`
	Facets     map[string]*FacetResult `json:"facets,omitempty"`
}

type SearchHit struct {
	ID          string                 `json:"id"`
	Score       float64                `json:"score"`
	Index       string                 `json:"index"`
	Type        string                 `json:"type,omitempty"`
	Title       string                 `json:"title,omitempty"`
	Content     string                 `json:"content,omitempty"`
	Summary     string                 `json:"summary,omitempty"`
	Tags        []string               `json:"tags,omitempty"`
	AuthorID    string                 `json:"author_id,omitempty"`
	SpaceID     string                 `json:"space_id,omitempty"`
	DocID       string                 `json:"doc_id,omitempty"`
	Extra       map[string]interface{} `json:"extra,omitempty"`
	Fragments   map[string][]string    `json:"fragments,omitempty"`
	Sort        []string               `json:"sort,omitempty"`
}

type FacetResult struct {
	Field   string         `json:"field"`
	Total   uint64         `json:"total"`
	Missing uint64         `json:"missing"`
	Other   uint64         `json:"other"`
	Terms   []*FacetTerm   `json:"terms,omitempty"`
	Ranges  []*FacetRange  `json:"ranges,omitempty"`
}

type FacetTerm struct {
	Term  string `json:"term"`
	Count uint64 `json:"count"`
}

type FacetRange struct {
	Name  string  `json:"name"`
	Min   *float64 `json:"min,omitempty"`
	Max   *float64 `json:"max,omitempty"`
	Count uint64  `json:"count"`
}

type SearchQuery struct {
	Query       string                 `json:"query"`
	Types       []string               `json:"types,omitempty"`
	SpaceID     string                 `json:"space_id,omitempty"`
	AuthorID    string                 `json:"author_id,omitempty"`
	Tags        []string               `json:"tags,omitempty"`
	Page        int                    `json:"page"`
	PageSize    int                    `json:"page_size"`
	Facets      map[string]interface{} `json:"facets,omitempty"`
	IncludeFields []string             `json:"include_fields,omitempty"`
	Highlight   bool                   `json:"highlight"`
	SortBy      string                 `json:"sort_by,omitempty"`
	SortOrder   string                 `json:"sort_order,omitempty"`
}

func InitBleve(cfg config.BleveConfig) (*BleveManager, error) {
	if cfg.IndexPath == "" {
		cfg.IndexPath = "./data/bleve"
	}

	if err := os.MkdirAll(cfg.IndexPath, 0755); err != nil {
		return nil, fmt.Errorf("create bleve index path: %w", err)
	}

	m := &BleveManager{
		basePath: cfg.IndexPath,
		indexes:  make(map[string]bleve.Index),
	}

	globalBleve = m
	return m, nil
}

func GetBleve() *BleveManager {
	return globalBleve
}

func (m *BleveManager) getIndexPath(tenantID string) string {
	return filepath.Join(m.basePath, fmt.Sprintf("tenant_%s", tenantID))
}

func (m *BleveManager) createMapping() mapping.IndexMapping {
	indexMapping := bleve.NewIndexMapping()

	docMapping := bleve.NewDocumentMapping()

	textFieldMapping := bleve.NewTextFieldMapping()
	textFieldMapping.Analyzer = standard.Name
	textFieldMapping.Store = true
	textFieldMapping.IncludeInAll = true
	textFieldMapping.Index = true

	keywordFieldMapping := bleve.NewKeywordFieldMapping()
	keywordFieldMapping.Store = true
	keywordFieldMapping.Index = true

	docMapping.AddFieldMappingsAt("id", keywordFieldMapping)
	docMapping.AddFieldMappingsAt("type", keywordFieldMapping)
	docMapping.AddFieldMappingsAt("title", textFieldMapping)
	docMapping.AddFieldMappingsAt("content", textFieldMapping)
	docMapping.AddFieldMappingsAt("summary", textFieldMapping)
	docMapping.AddFieldMappingsAt("author_id", keywordFieldMapping)
	docMapping.AddFieldMappingsAt("space_id", keywordFieldMapping)
	docMapping.AddFieldMappingsAt("doc_id", keywordFieldMapping)

	tagsFieldMapping := bleve.NewTextFieldMapping()
	tagsFieldMapping.Store = true
	tagsFieldMapping.Index = true
	tagsFieldMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("tags", tagsFieldMapping)

	indexMapping.DefaultMapping = docMapping
	indexMapping.DefaultAnalyzer = standard.Name
	indexMapping.TypeField = "type"

	return indexMapping
}

func (m *BleveManager) GetOrCreateIndex(tenantID string) (bleve.Index, error) {
	m.mu.RLock()
	idx, ok := m.indexes[tenantID]
	m.mu.RUnlock()
	if ok {
		return idx, nil
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if idx, ok := m.indexes[tenantID]; ok {
		return idx, nil
	}

	indexPath := m.getIndexPath(tenantID)

	idx, err := bleve.Open(indexPath)
	if err == nil {
		m.indexes[tenantID] = idx
		return idx, nil
	}

	idxMapping := m.createMapping()
	idx, err = bleve.New(indexPath, idxMapping)
	if err != nil {
		return nil, fmt.Errorf("create bleve index for tenant %s: %w", tenantID, err)
	}

	m.indexes[tenantID] = idx
	return idx, nil
}

func (m *BleveManager) Index(tenantID string, doc *IndexDocument) error {
	if doc.ID == "" {
		return fmt.Errorf("document id is required")
	}

	idx, err := m.GetOrCreateIndex(tenantID)
	if err != nil {
		return err
	}

	if err := idx.Index(doc.ID, doc); err != nil {
		return fmt.Errorf("index document: %w", err)
	}
	return nil
}

func (m *BleveManager) BatchIndex(tenantID string, docs []*IndexDocument) error {
	if len(docs) == 0 {
		return nil
	}

	idx, err := m.GetOrCreateIndex(tenantID)
	if err != nil {
		return err
	}

	batch := idx.NewBatch()
	for _, doc := range docs {
		if doc.ID == "" {
			continue
		}
		if err := batch.Index(doc.ID, doc); err != nil {
			return fmt.Errorf("batch index document %s: %w", doc.ID, err)
		}
	}

	if err := idx.Batch(batch); err != nil {
		return fmt.Errorf("execute batch: %w", err)
	}
	return nil
}

func (m *BleveManager) Delete(tenantID, docID string) error {
	idx, err := m.GetOrCreateIndex(tenantID)
	if err != nil {
		return err
	}

	if err := idx.Delete(docID); err != nil {
		return fmt.Errorf("delete document %s: %w", docID, err)
	}
	return nil
}

func (m *BleveManager) DeleteIndex(tenantID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if idx, ok := m.indexes[tenantID]; ok {
		if err := idx.Close(); err != nil {
			return fmt.Errorf("close index: %w", err)
		}
		delete(m.indexes, tenantID)
	}

	indexPath := m.getIndexPath(tenantID)
	if _, err := os.Stat(indexPath); err == nil {
		if err := os.RemoveAll(indexPath); err != nil {
			return fmt.Errorf("remove index path: %w", err)
		}
	}
	return nil
}

func (m *BleveManager) Search(tenantID string, q *SearchQuery) (*SearchResult, error) {
	idx, err := m.GetOrCreateIndex(tenantID)
	if err != nil {
		return nil, err
	}

	if q.Page <= 0 {
		q.Page = 1
	}
	if q.PageSize <= 0 {
		q.PageSize = 20
	}
	if q.PageSize > 200 {
		q.PageSize = 200
	}

	query := bleve.NewBooleanQuery()

	if q.Query != "" {
		multiQuery := bleve.NewBooleanQuery()
		fields := []string{"title^3", "content", "summary^2", "tags^2.5"}
		_ = fields
		matchTitle := bleve.NewMatchQuery(q.Query)
		matchTitle.SetField("title")
		matchTitle.SetBoost(3.0)
		matchContent := bleve.NewMatchQuery(q.Query)
		matchContent.SetField("content")
		matchSummary := bleve.NewMatchQuery(q.Query)
		matchSummary.SetField("summary")
		matchSummary.SetBoost(2.0)
		matchTags := bleve.NewMatchQuery(q.Query)
		matchTags.SetField("tags")
		matchTags.SetBoost(2.5)
		multiQuery.AddShould(matchTitle)
		multiQuery.AddShould(matchContent)
		multiQuery.AddShould(matchSummary)
		multiQuery.AddShould(matchTags)
		query.AddMust(multiQuery)
	}

	if len(q.Types) > 0 {
		typeQuery := bleve.NewBooleanQuery()
		for _, t := range q.Types {
			tq := bleve.NewTermQuery(t)
			tq.SetField("type")
			typeQuery.AddShould(tq)
		}
		query.AddMust(typeQuery)
	}

	if q.SpaceID != "" {
		sq := bleve.NewTermQuery(q.SpaceID)
		sq.SetField("space_id")
		query.AddMust(sq)
	}

	if q.AuthorID != "" {
		aq := bleve.NewTermQuery(q.AuthorID)
		aq.SetField("author_id")
		query.AddMust(aq)
	}

	if len(q.Tags) > 0 {
		tagsQuery := bleve.NewBooleanQuery()
		for _, tag := range q.Tags {
			tq := bleve.NewMatchQuery(tag)
			tq.SetField("tags")
			tagsQuery.AddShould(tq)
		}
		query.AddMust(tagsQuery)
	}

	searchRequest := bleve.NewSearchRequest(query)
	searchRequest.From = (q.Page - 1) * q.PageSize
	searchRequest.Size = q.PageSize

	if len(q.IncludeFields) > 0 {
		searchRequest.Fields = q.IncludeFields
	} else {
		searchRequest.Fields = []string{"*"}
	}

	if q.Highlight {
		searchRequest.Highlight = bleve.NewHighlight()
		searchRequest.Highlight.AddField("title")
		searchRequest.Highlight.AddField("content")
		searchRequest.Highlight.AddField("summary")
	}

	if q.SortBy != "" {
		desc := false
		if q.SortOrder == "desc" {
			desc = true
		}
		if desc {
			searchRequest.SortBy([]string{"-" + q.SortBy})
		} else {
			searchRequest.SortBy([]string{q.SortBy})
		}
	}

	if len(q.Facets) > 0 {
		for facetName, facetCfg := range q.Facets {
			if field, ok := facetCfg.(string); ok {
				facet := bleve.NewFacetRequest(field, 10)
				searchRequest.AddFacet(facetName, facet)
			}
		}
	}

	results, err := idx.Search(searchRequest)
	if err != nil {
		return nil, fmt.Errorf("search index: %w", err)
	}

	return m.convertResults(results), nil
}

func (m *BleveManager) convertResults(results *bleve.SearchResult) *SearchResult {
	hits := make([]*SearchHit, 0, len(results.Hits))
	for _, hit := range results.Hits {
		sh := &SearchHit{
			ID:        hit.ID,
			Score:     hit.Score,
			Index:     hit.Index,
			Fragments: hit.Fragments,
			Sort:      hit.Sort,
		}

		if hit.Fields != nil {
			if typeVal, ok := hit.Fields["type"].(string); ok {
				sh.Type = typeVal
			}
			if title, ok := hit.Fields["title"].(string); ok {
				sh.Title = title
			}
			if content, ok := hit.Fields["content"].(string); ok {
				sh.Content = content
			}
			if summary, ok := hit.Fields["summary"].(string); ok {
				sh.Summary = summary
			}
			if authorID, ok := hit.Fields["author_id"].(string); ok {
				sh.AuthorID = authorID
			}
			if spaceID, ok := hit.Fields["space_id"].(string); ok {
				sh.SpaceID = spaceID
			}
			if docID, ok := hit.Fields["doc_id"].(string); ok {
				sh.DocID = docID
			}
			if tags, ok := hit.Fields["tags"].([]string); ok {
				sh.Tags = tags
			}
			if extra, ok := hit.Fields["extra"].(map[string]interface{}); ok {
				sh.Extra = extra
			}
		}

		hits = append(hits, sh)
	}

	facets := make(map[string]*FacetResult)
	if results.Facets != nil {
		for name, f := range results.Facets {
			fr := &FacetResult{
				Field:   f.Field,
				Total:   uint64(f.Total),
				Missing: uint64(f.Missing),
				Other:   uint64(f.Other),
			}

			for _, t := range f.Terms.Terms() {
				fr.Terms = append(fr.Terms, &FacetTerm{
					Term:  t.Term,
					Count: uint64(t.Count),
				})
			}

			facets[name] = fr
		}
	}

	return &SearchResult{
		Total:    results.Total,
		MaxScore: results.MaxScore,
		Took:     float64(results.Took.Milliseconds()),
		Hits:     hits,
		Facets:   facets,
	}
}

func (m *BleveManager) Close() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	for tenantID, idx := range m.indexes {
		if err := idx.Close(); err != nil {
			return fmt.Errorf("close index for tenant %s: %w", tenantID, err)
		}
	}
	m.indexes = make(map[string]bleve.Index)
	return nil
}

func (m *BleveManager) DocumentCount(tenantID string) (uint64, error) {
	idx, err := m.GetOrCreateIndex(tenantID)
	if err != nil {
		return 0, err
	}
	return idx.DocCount()
}
