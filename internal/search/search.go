package search

import (
	"context"
	"math"
	"sort"
	"strings"
	"time"

	"github.com/blevesearch/bleve/v2"
	"github.com/blevesearch/bleve/v2/search"
	"github.com/blevesearch/bleve/v2/search/query"
)

const (
	DefaultPageSize        = 20
	MaxPageSize            = 100
	DefaultFuzziness       = 1
	MaxFuzziness           = 2
	DefaultTimeDecayK      = 0.0000001
	DefaultBoostTitle      = 3.0
	DefaultBoostContent    = 1.0
	DefaultBoostTags       = 2.5
	DefaultBoostAttachment = 0.8
)

type SearchService struct {
	indexManager *IndexManager
	tikaClient   *TikaClient
}

type SearchQuery struct {
	QueryText         string
	TenantID          string
	SpaceIDs          []string
	AuthorIDs         []string
	Tags              []string
	Categories        []string
	Statuses          []string
	LangCodes         []string
	DateFrom          *time.Time
	DateTo            *time.Time
	MinViewCount      *int64
	MinLikeCount      *int64
	SearchAttachments bool
	Fuzzy             bool
	Fuzziness         int
	CrossLanguage     bool
	IncludePinyin     bool
	BM25Params        *BM25Parameters
	TimeDecayK        float64
}

type BM25Parameters struct {
	K1 float64
	B  float64
}

type SearchOptions struct {
	Page          int
	PageSize      int
	SortBy        string
	SortOrder     string
	IncludeFields []string
	ExcludeFields []string
	Highlight     bool
	HighlightTags [2]string
	MaxHighlights int
}

type SearchResult struct {
	TotalHits  uint64
	Hits       []*SearchHit
	Facets     map[string]*FacetResult
	Page       int
	PageSize   int
	TotalPages int
	QueryTime  time.Duration
	SearchTime time.Duration
	Highlights []string
}

type SearchHit struct {
	DocID         string
	TenantID      string
	SpaceID       string
	Score         float64
	RawScore      float64
	Title         string
	Summary       string
	Content       string
	Tags          []string
	AuthorID      string
	AuthorName    string
	LangCode      string
	Category      string
	Status        string
	ViewCount     int64
	LikeCount     int64
	CreatedAt     int64
	UpdatedAt     int64
	PublishedAt   int64
	Highlights    map[string][]string
	Fields        map[string]interface{}
	MatchedFields []string
	IsAttachment  bool
	AttachmentID  string
	FileName      string
}

type FacetResult struct {
	Field   string
	Total   int
	Missing int
	Other   int
	Terms   []*FacetTerm
}

type FacetTerm struct {
	Term  string
	Count int
}

type SuggestQuery struct {
	QueryText string
	TenantID  string
	SpaceIDs  []string
	Size      int
}

type Suggestion struct {
	Text  string
	Score float64
	Type  string
}

func NewSearchService(indexManager *IndexManager, tikaClient *TikaClient) *SearchService {
	return &SearchService{
		indexManager: indexManager,
		tikaClient:   tikaClient,
	}
}

func (ss *SearchService) Search(ctx context.Context, sq SearchQuery, opts SearchOptions) (*SearchResult, error) {
	startTime := time.Now()

	if opts.PageSize <= 0 {
		opts.PageSize = DefaultPageSize
	}
	if opts.PageSize > MaxPageSize {
		opts.PageSize = MaxPageSize
	}
	if opts.Page < 1 {
		opts.Page = 1
	}
	if sq.TimeDecayK <= 0 {
		sq.TimeDecayK = DefaultTimeDecayK
	}
	if sq.Fuzziness <= 0 {
		sq.Fuzziness = DefaultFuzziness
	}
	if sq.Fuzziness > MaxFuzziness {
		sq.Fuzziness = MaxFuzziness
	}
	if sq.BM25Params == nil {
		sq.BM25Params = &BM25Parameters{
			K1: 1.2,
			B:  0.75,
		}
	}

	result := &SearchResult{
		Page:     opts.Page,
		PageSize: opts.PageSize,
	}

	queryStartTime := time.Now()
	mainQuery := ss.buildQuery(sq)
	result.QueryTime = time.Since(queryStartTime)

	spaceIDs := sq.SpaceIDs
	if len(spaceIDs) == 0 {
		spaceIDs = []string{"default"}
	}

	allHits := make([]*SearchHit, 0)
	var totalHits uint64

	for _, spaceID := range spaceIDs {
		docKey := IndexKey{
			TenantID:  sq.TenantID,
			SpaceID:   spaceID,
			IndexType: IndexTypeDocument,
		}

		idx, err := ss.indexManager.GetOrCreateIndex(docKey)
		if err != nil {
			continue
		}

		searchReq := bleve.NewSearchRequest(mainQuery)
		searchReq.From = (opts.Page - 1) * opts.PageSize
		searchReq.Size = opts.PageSize
		searchReq.Explain = false

		searchReq.Fields = []string{"*"}

		if opts.Highlight {
			hl := bleve.NewHighlight()
			htmlStyle := "html"
			hl.Style = &htmlStyle
			hl.Fields = []string{"title", "content", "summary", "attachment_texts"}
			searchReq.Highlight = hl
		}

		sortFields := ss.buildSortFields(opts.SortBy, opts.SortOrder)
		if len(sortFields) > 0 {
			searchReq.SortByCustom(sortFields)
		}

		searchResult, err := idx.SearchInContext(ctx, searchReq)
		if err != nil {
			continue
		}

		totalHits += searchResult.Total

		for _, hit := range searchResult.Hits {
			searchHit := ss.convertHit(hit, spaceID, sq, opts)
			allHits = append(allHits, searchHit)
		}
	}

	if sq.SearchAttachments {
		for _, spaceID := range spaceIDs {
			attachKey := IndexKey{
				TenantID:  sq.TenantID,
				SpaceID:   spaceID,
				IndexType: IndexTypeAttachment,
			}

			idx, err := ss.indexManager.GetOrCreateIndex(attachKey)
			if err != nil {
				continue
			}

			searchReq := bleve.NewSearchRequest(mainQuery)
			searchReq.Size = opts.PageSize
			searchReq.Fields = []string{"*"}

			searchResult, err := idx.SearchInContext(ctx, searchReq)
			if err != nil {
				continue
			}

			totalHits += searchResult.Total

			for _, hit := range searchResult.Hits {
				attachHit := ss.convertAttachmentHit(hit, spaceID)
				allHits = append(allHits, attachHit)
			}
		}
	}

	ss.applyTimeDecay(allHits, sq.TimeDecayK)
	ss.applyBM25FBoost(allHits, sq)

	sort.Slice(allHits, func(i, j int) bool {
		return allHits[i].Score > allHits[j].Score
	})

	totalHitsUint := uint64(len(allHits))
	result.TotalHits = totalHitsUint
	result.TotalPages = int(math.Ceil(float64(totalHitsUint) / float64(opts.PageSize)))

	startIdx := (opts.Page - 1) * opts.PageSize
	endIdx := startIdx + opts.PageSize
	if startIdx > len(allHits) {
		result.Hits = []*SearchHit{}
	} else {
		if endIdx > len(allHits) {
			endIdx = len(allHits)
		}
		result.Hits = allHits[startIdx:endIdx]
	}

	result.SearchTime = time.Since(startTime)

	return result, nil
}

func (ss *SearchService) buildQuery(sq SearchQuery) query.Query {
	queries := make([]query.Query, 0)

	queryText := strings.TrimSpace(sq.QueryText)
	if queryText != "" {
		textQueries := ss.buildTextQueries(queryText, sq)
		queries = append(queries, textQueries...)
	}

	if len(sq.AuthorIDs) > 0 {
		authorQuery := bleve.NewDocIDQuery(sq.AuthorIDs)
		queries = append(queries, authorQuery)
	}

	if len(sq.Tags) > 0 {
		tagQueries := make([]query.Query, 0, len(sq.Tags))
		for _, tag := range sq.Tags {
			tq := bleve.NewTermQuery(tag)
			tq.SetField("tags")
			tagQueries = append(tagQueries, tq)
		}
		tagBool := bleve.NewBooleanQuery()
		for _, tq := range tagQueries {
			tagBool.AddShould(tq)
		}
		tagBool.SetMinShould(1)
		queries = append(queries, tagBool)
	}

	if len(sq.Categories) > 0 {
		catQueries := make([]query.Query, 0, len(sq.Categories))
		for _, cat := range sq.Categories {
			cq := bleve.NewTermQuery(cat)
			cq.SetField("category")
			catQueries = append(catQueries, cq)
		}
		catBool := bleve.NewBooleanQuery()
		for _, cq := range catQueries {
			catBool.AddShould(cq)
		}
		catBool.SetMinShould(1)
		queries = append(queries, catBool)
	}

	if len(sq.Statuses) > 0 {
		statusQueries := make([]query.Query, 0, len(sq.Statuses))
		for _, status := range sq.Statuses {
			sq2 := bleve.NewTermQuery(status)
			sq2.SetField("status")
			statusQueries = append(statusQueries, sq2)
		}
		statusBool := bleve.NewBooleanQuery()
		for _, s := range statusQueries {
			statusBool.AddShould(s)
		}
		statusBool.SetMinShould(1)
		queries = append(queries, statusBool)
	}

	if len(sq.LangCodes) > 0 {
		langQueries := make([]query.Query, 0, len(sq.LangCodes))
		for _, lang := range sq.LangCodes {
			lq := bleve.NewTermQuery(lang)
			lq.SetField("lang_code")
			langQueries = append(langQueries, lq)
		}
		langBool := bleve.NewBooleanQuery()
		for _, lq := range langQueries {
			langBool.AddShould(lq)
		}
		langBool.SetMinShould(1)
		queries = append(queries, langBool)
	}

	if sq.DateFrom != nil || sq.DateTo != nil {
		var start, end time.Time
		if sq.DateFrom != nil {
			start = *sq.DateFrom
		}
		if sq.DateTo != nil {
			end = *sq.DateTo
		}
		dateRange := bleve.NewDateRangeQuery(start, end)
		dateRange.SetField("updated_at")
		queries = append(queries, dateRange)
	}

	if len(queries) == 0 {
		return bleve.NewMatchAllQuery()
	}

	if len(queries) == 1 {
		return queries[0]
	}

	boolQuery := bleve.NewBooleanQuery()
	for _, q := range queries {
		boolQuery.AddMust(q)
	}

	return boolQuery
}

func (ss *SearchService) buildTextQueries(queryText string, sq SearchQuery) []query.Query {
	queries := make([]query.Query, 0)

	isZh := IsChinese(queryText)
	useFuzzy := sq.Fuzzy && !isZh

	fields := []struct {
		name  string
		boost float64
	}{
		{"title", DefaultBoostTitle},
		{"summary", 2.0},
		{"content", DefaultBoostContent},
		{"tags", DefaultBoostTags},
		{"author_name", 1.5},
		{"category", 1.0},
		{"attachment_texts", DefaultBoostAttachment},
	}

	for _, field := range fields {
		var fieldQuery query.Query

		if useFuzzy {
			fq := bleve.NewFuzzyQuery(queryText)
			fq.SetField(field.name)
			fq.SetFuzziness(sq.Fuzziness)
			fq.SetBoost(field.boost)
			fieldQuery = fq
		} else {
			mq := bleve.NewMatchQuery(queryText)
			mq.SetField(field.name)
			mq.SetBoost(field.boost)
			fieldQuery = mq
		}

		queries = append(queries, fieldQuery)

		if sq.IncludePinyin && isZh {
			pyMQ := bleve.NewMatchQuery(queryText)
			pyMQ.SetField(field.name)
			pyMQ.SetBoost(field.boost * 0.5)
			queries = append(queries, pyMQ)
		}
	}

	if sq.CrossLanguage {
		enQuery := queryText
		if isZh {
			enQuery = TextToPinyin(queryText)
		}
		for _, field := range fields {
			crossMQ := bleve.NewMatchQuery(enQuery)
			crossMQ.SetField(field.name)
			crossMQ.SetBoost(field.boost * 0.3)
			queries = append(queries, crossMQ)
		}
	}

	prefixQuery := bleve.NewPrefixQuery(queryText)
	prefixQuery.SetField("title")
	prefixQuery.SetBoost(1.0)
	queries = append(queries, prefixQuery)

	wildcardText := "*" + strings.ToLower(queryText) + "*"
	wildcardQuery := bleve.NewWildcardQuery(wildcardText)
	wildcardQuery.SetField("title")
	wildcardQuery.SetBoost(0.5)
	queries = append(queries, wildcardQuery)

	boolQuery := bleve.NewBooleanQuery()
	for _, q := range queries {
		boolQuery.AddShould(q)
	}
	boolQuery.SetMinShould(1)

	return []query.Query{boolQuery}
}

func (ss *SearchService) buildSortFields(sortBy, sortOrder string) search.SortOrder {
	desc := strings.ToLower(sortOrder) == "desc"

	switch strings.ToLower(sortBy) {
	case "score", "relevance", "":
		return nil
	case "created_at":
		return search.SortOrder{
			&search.SortField{
				Field: "created_at",
				Desc:  desc,
				Type:  search.SortFieldAuto,
			},
		}
	case "updated_at":
		return search.SortOrder{
			&search.SortField{
				Field: "updated_at",
				Desc:  desc,
				Type:  search.SortFieldAuto,
			},
		}
	case "published_at":
		return search.SortOrder{
			&search.SortField{
				Field: "published_at",
				Desc:  desc,
				Type:  search.SortFieldAuto,
			},
		}
	case "view_count":
		return search.SortOrder{
			&search.SortField{
				Field: "view_count",
				Desc:  desc,
				Type:  search.SortFieldAuto,
			},
		}
	case "like_count":
		return search.SortOrder{
			&search.SortField{
				Field: "like_count",
				Desc:  desc,
				Type:  search.SortFieldAuto,
			},
		}
	case "title":
		return search.SortOrder{
			&search.SortField{
				Field: "title",
				Desc:  desc,
				Type:  search.SortFieldAuto,
			},
		}
	case "priority":
		return search.SortOrder{
			&search.SortField{
				Field: "priority",
				Desc:  desc,
				Type:  search.SortFieldAuto,
			},
		}
	default:
		return nil
	}
}

func (ss *SearchService) convertHit(hit *search.DocumentMatch, spaceID string, sq SearchQuery, opts SearchOptions) *SearchHit {
	result := &SearchHit{
		DocID:    hit.ID,
		SpaceID:  spaceID,
		RawScore: hit.Score,
		Score:    hit.Score,
		Fields:   make(map[string]interface{}),
	}

	if len(hit.Fields) > 0 {
		if v, ok := hit.Fields["tenant_id"].(string); ok {
			result.TenantID = v
		}
		if v, ok := hit.Fields["title"].(string); ok {
			result.Title = v
		}
		if v, ok := hit.Fields["summary"].(string); ok {
			result.Summary = v
		}
		if v, ok := hit.Fields["content"].(string); ok {
			result.Content = v
		}
		if v, ok := hit.Fields["author_id"].(string); ok {
			result.AuthorID = v
		}
		if v, ok := hit.Fields["author_name"].(string); ok {
			result.AuthorName = v
		}
		if v, ok := hit.Fields["lang_code"].(string); ok {
			result.LangCode = v
		}
		if v, ok := hit.Fields["category"].(string); ok {
			result.Category = v
		}
		if v, ok := hit.Fields["status"].(string); ok {
			result.Status = v
		}
		if v, ok := hit.Fields["view_count"].(float64); ok {
			result.ViewCount = int64(v)
		}
		if v, ok := hit.Fields["like_count"].(float64); ok {
			result.LikeCount = int64(v)
		}
		if v, ok := hit.Fields["created_at"].(float64); ok {
			result.CreatedAt = int64(v)
		}
		if v, ok := hit.Fields["updated_at"].(float64); ok {
			result.UpdatedAt = int64(v)
		}
		if v, ok := hit.Fields["published_at"].(float64); ok {
			result.PublishedAt = int64(v)
		}
		if v, ok := hit.Fields["tags"].([]interface{}); ok {
			tags := make([]string, 0, len(v))
			for _, t := range v {
				if s, ok := t.(string); ok {
					tags = append(tags, s)
				}
			}
			result.Tags = tags
		}

		for k, v := range hit.Fields {
			result.Fields[k] = v
		}
	}

	if opts.Highlight && hit.Fragments != nil {
		result.Highlights = make(map[string][]string)
		for field, fragments := range hit.Fragments {
			result.Highlights[field] = fragments
			result.MatchedFields = append(result.MatchedFields, field)
		}
	}

	return result
}

func (ss *SearchService) convertAttachmentHit(hit *search.DocumentMatch, spaceID string) *SearchHit {
	result := &SearchHit{
		DocID:        hit.ID,
		SpaceID:      spaceID,
		RawScore:     hit.Score,
		Score:        hit.Score,
		IsAttachment: true,
		Fields:       make(map[string]interface{}),
	}

	if len(hit.Fields) > 0 {
		if v, ok := hit.Fields["attachment_id"].(string); ok {
			result.AttachmentID = v
		}
		if v, ok := hit.Fields["tenant_id"].(string); ok {
			result.TenantID = v
		}
		if v, ok := hit.Fields["doc_id"].(string); ok {
			result.DocID = v
		}
		if v, ok := hit.Fields["file_name"].(string); ok {
			result.FileName = v
			result.Title = v
		}
		if v, ok := hit.Fields["content"].(string); ok {
			result.Content = v
		}
		if v, ok := hit.Fields["lang_code"].(string); ok {
			result.LangCode = v
		}
		if v, ok := hit.Fields["created_at"].(float64); ok {
			result.CreatedAt = int64(v)
		}
		if v, ok := hit.Fields["updated_at"].(float64); ok {
			result.UpdatedAt = int64(v)
		}

		for k, v := range hit.Fields {
			result.Fields[k] = v
		}
	}

	return result
}

func (ss *SearchService) applyTimeDecay(hits []*SearchHit, k float64) {
	now := time.Now().Unix()
	for _, hit := range hits {
		var updatedAt int64
		if hit.UpdatedAt > 0 {
			updatedAt = hit.UpdatedAt
		} else if hit.CreatedAt > 0 {
			updatedAt = hit.CreatedAt
		} else {
			continue
		}

		ageDays := float64(now-updatedAt) / 86400.0
		if ageDays < 0 {
			ageDays = 0
		}

		decayFactor := math.Exp(-k * ageDays)
		hit.Score = hit.RawScore * (0.5 + 0.5*decayFactor)
	}
}

func (ss *SearchService) applyBM25FBoost(hits []*SearchHit, sq SearchQuery) {
	queryText := strings.ToLower(strings.TrimSpace(sq.QueryText))
	if queryText == "" {
		return
	}

	queryTerms := strings.Fields(NormalizeText(queryText))
	if len(queryTerms) == 0 {
		return
	}

	for _, hit := range hits {
		boost := 1.0

		fieldWeights := map[string]float64{
			"title":   3.0,
			"summary": 2.0,
			"tags":    2.5,
			"content": 1.0,
		}

		totalMatchCount := 0
		for field, weight := range fieldWeights {
			fieldText := ""
			switch field {
			case "title":
				fieldText = strings.ToLower(hit.Title)
			case "summary":
				fieldText = strings.ToLower(hit.Summary)
			case "content":
				fieldText = strings.ToLower(hit.Content)
			case "tags":
				fieldText = strings.ToLower(strings.Join(hit.Tags, " "))
			}

			if fieldText == "" {
				continue
			}

			matchCount := 0
			for _, term := range queryTerms {
				if strings.Contains(fieldText, term) {
					matchCount++
					totalMatchCount++
				}
			}

			if matchCount > 0 {
				boost += weight * float64(matchCount) / float64(len(queryTerms))
			}
		}

		if totalMatchCount == len(queryTerms) && len(queryTerms) > 1 {
			boost *= 1.5
		}

		if hit.ViewCount > 100 {
			boost *= 1.0 + 0.1*math.Log10(float64(hit.ViewCount)/100.0)
		}
		if hit.LikeCount > 10 {
			boost *= 1.0 + 0.05*math.Log10(float64(hit.LikeCount)/10.0)
		}

		hit.Score *= boost
	}
}

func (ss *SearchService) Suggest(ctx context.Context, sq SuggestQuery) ([]*Suggestion, error) {
	if sq.Size <= 0 {
		sq.Size = 10
	}

	suggestions := make([]*Suggestion, 0, sq.Size)
	queryText := strings.TrimSpace(sq.QueryText)

	if queryText == "" {
		return suggestions, nil
	}

	lowerQuery := strings.ToLower(queryText)
	pinyinQuery := TextToPinyinInitials(queryText)

	spaceIDs := sq.SpaceIDs
	if len(spaceIDs) == 0 {
		spaceIDs = []string{"default"}
	}

	seen := make(map[string]struct{})

	for _, spaceID := range spaceIDs {
		docKey := IndexKey{
			TenantID:  sq.TenantID,
			SpaceID:   spaceID,
			IndexType: IndexTypeDocument,
		}

		idx, err := ss.indexManager.GetOrCreateIndex(docKey)
		if err != nil {
			continue
		}

		prefixQuery := bleve.NewPrefixQuery(queryText)
		prefixQuery.SetField("title")
		prefixQuery.SetBoost(2.0)

		titlePrefixReq := bleve.NewSearchRequest(prefixQuery)
		titlePrefixReq.Size = sq.Size
		titlePrefixReq.Fields = []string{"title"}

		result, err := idx.SearchInContext(ctx, titlePrefixReq)
		if err == nil {
			for _, hit := range result.Hits {
				if v, ok := hit.Fields["title"].(string); ok && v != "" {
					key := strings.ToLower(v)
					if _, exists := seen[key]; !exists {
						seen[key] = struct{}{}
						score := hit.Score
						if strings.HasPrefix(strings.ToLower(v), lowerQuery) {
							score *= 2.0
						}
						suggestions = append(suggestions, &Suggestion{
							Text:  v,
							Score: score,
							Type:  "title_prefix",
						})
					}
				}
			}
		}

		if len(pinyinQuery) > 1 {
			pyMatchQuery := bleve.NewMatchQuery(queryText)
			pyMatchQuery.SetField("title")
			pyMatchQuery.SetBoost(1.5)

			pyReq := bleve.NewSearchRequest(pyMatchQuery)
			pyReq.Size = sq.Size
			pyReq.Fields = []string{"title"}

			pyResult, err := idx.SearchInContext(ctx, pyReq)
			if err == nil {
				for _, hit := range pyResult.Hits {
					if v, ok := hit.Fields["title"].(string); ok && v != "" {
						key := strings.ToLower(v)
						if _, exists := seen[key]; !exists {
							seen[key] = struct{}{}
							suggestions = append(suggestions, &Suggestion{
								Text:  v,
								Score: hit.Score,
								Type:  "pinyin",
							})
						}
					}
				}
			}
		}

		fuzzyQuery := bleve.NewFuzzyQuery(queryText)
		fuzzyQuery.SetField("title")
		fuzzyQuery.SetFuzziness(1)
		fuzzyQuery.SetBoost(0.8)

		fuzzyReq := bleve.NewSearchRequest(fuzzyQuery)
		fuzzyReq.Size = sq.Size
		fuzzyReq.Fields = []string{"title"}

		fuzzyResult, err := idx.SearchInContext(ctx, fuzzyReq)
		if err == nil {
			for _, hit := range fuzzyResult.Hits {
				if v, ok := hit.Fields["title"].(string); ok && v != "" {
					key := strings.ToLower(v)
					if _, exists := seen[key]; !exists {
						seen[key] = struct{}{}
						suggestions = append(suggestions, &Suggestion{
							Text:  v,
							Score: hit.Score,
							Type:  "fuzzy",
						})
					}
				}
			}
		}
	}

	sort.Slice(suggestions, func(i, j int) bool {
		return suggestions[i].Score > suggestions[j].Score
	})

	if len(suggestions) > sq.Size {
		suggestions = suggestions[:sq.Size]
	}

	return suggestions, nil
}

func (ss *SearchService) IndexDocument(ctx context.Context, tenantID, spaceID, docID string, doc *DocumentIndex) error {
	key := IndexKey{
		TenantID:  tenantID,
		SpaceID:   spaceID,
		IndexType: IndexTypeDocument,
	}

	if doc.TenantID == "" {
		doc.TenantID = tenantID
	}
	if doc.SpaceID == "" {
		doc.SpaceID = spaceID
	}
	if doc.LangCode == "" {
		doc.LangCode = DetectLanguage(doc.Title + " " + doc.Content)
	}
	if doc.UpdatedAt == 0 {
		doc.UpdatedAt = time.Now().Unix()
	}
	if doc.CreatedAt == 0 {
		doc.CreatedAt = doc.UpdatedAt
	}

	return ss.indexManager.IndexDocument(key, docID, doc)
}

func (ss *SearchService) DeleteDocument(ctx context.Context, tenantID, spaceID, docID string) error {
	key := IndexKey{
		TenantID:  tenantID,
		SpaceID:   spaceID,
		IndexType: IndexTypeDocument,
	}

	return ss.indexManager.DeleteDocument(key, docID)
}

func (ss *SearchService) IndexAttachment(ctx context.Context, tenantID, spaceID string, attach *AttachmentIndex) error {
	key := IndexKey{
		TenantID:  tenantID,
		SpaceID:   spaceID,
		IndexType: IndexTypeAttachment,
	}

	if attach.TenantID == "" {
		attach.TenantID = tenantID
	}
	if attach.SpaceID == "" {
		attach.SpaceID = spaceID
	}
	if attach.LangCode == "" {
		attach.LangCode = DetectLanguage(attach.Content)
	}
	if attach.UpdatedAt == 0 {
		attach.UpdatedAt = time.Now().Unix()
	}
	if attach.CreatedAt == 0 {
		attach.CreatedAt = attach.UpdatedAt
	}

	return ss.indexManager.IndexDocument(key, attach.AttachmentID, attach)
}

func (ss *SearchService) GetTikaClient() *TikaClient {
	return ss.tikaClient
}

func (ss *SearchService) GetIndexManager() *IndexManager {
	return ss.indexManager
}

func (ss *SearchService) BatchIndexDocuments(ctx context.Context, tenantID, spaceID string, docs map[string]*DocumentIndex) error {
	if len(docs) == 0 {
		return nil
	}

	key := IndexKey{
		TenantID:  tenantID,
		SpaceID:   spaceID,
		IndexType: IndexTypeDocument,
	}

	genericDocs := make(map[string]interface{}, len(docs))
	now := time.Now().Unix()

	for docID, doc := range docs {
		if doc.TenantID == "" {
			doc.TenantID = tenantID
		}
		if doc.SpaceID == "" {
			doc.SpaceID = spaceID
		}
		if doc.LangCode == "" {
			doc.LangCode = DetectLanguage(doc.Title + " " + doc.Content)
		}
		if doc.UpdatedAt == 0 {
			doc.UpdatedAt = now
		}
		if doc.CreatedAt == 0 {
			doc.CreatedAt = now
		}
		genericDocs[docID] = doc
	}

	return ss.indexManager.IndexDocumentsBatch(key, genericDocs)
}
