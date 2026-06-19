package search

import (
	"github.com/blevesearch/bleve/v2"
	"github.com/blevesearch/bleve/v2/mapping"
)

type DocumentIndex struct {
	DocID         string                 `json:"doc_id"`
	TenantID      string                 `json:"tenant_id"`
	SpaceID       string                 `json:"space_id"`
	Title         string                 `json:"title"`
	Content       string                 `json:"content"`
	Summary       string                 `json:"summary"`
	Tags          []string               `json:"tags"`
	AuthorID      string                 `json:"author_id"`
	AuthorName    string                 `json:"author_name"`
	LangCode      string                 `json:"lang_code"`
	Category      string                 `json:"category"`
	Status        string                 `json:"status"`
	Priority      int                    `json:"priority"`
	ViewCount     int64                  `json:"view_count"`
	LikeCount     int64                  `json:"like_count"`
	AttachmentIDs []string               `json:"attachment_ids"`
	AttachmentTexts []string             `json:"attachment_texts"`
	Metadata      map[string]interface{} `json:"metadata"`
	CreatedAt     int64                  `json:"created_at"`
	UpdatedAt     int64                  `json:"updated_at"`
	PublishedAt   int64                  `json:"published_at"`
}

func BuildDocumentMapping() mapping.IndexMapping {
	indexMapping := bleve.NewIndexMapping()

	if err := RegisterCustomAnalyzers(); err != nil {
		_ = err
	}

	zhFieldMapping := bleve.NewTextFieldMapping()
	zhFieldMapping.Analyzer = ZhAnalyzerName
	zhFieldMapping.Store = true
	zhFieldMapping.IncludeInAll = true
	zhFieldMapping.IncludeTermVectors = true

	zhPinyinFieldMapping := bleve.NewTextFieldMapping()
	zhPinyinFieldMapping.Analyzer = ZhPinyinAnalyzer
	zhPinyinFieldMapping.Store = true
	zhPinyinFieldMapping.IncludeInAll = true
	zhPinyinFieldMapping.IncludeTermVectors = true

	keywordFieldMapping := bleve.NewKeywordFieldMapping()
	keywordFieldMapping.Store = true
	keywordFieldMapping.IncludeInAll = false

	dateFieldMapping := bleve.NewDateTimeFieldMapping()
	dateFieldMapping.Store = true
	dateFieldMapping.IncludeInAll = false

	numericFieldMapping := bleve.NewNumericFieldMapping()
	numericFieldMapping.Store = true
	numericFieldMapping.IncludeInAll = false

	booleanFieldMapping := bleve.NewBooleanFieldMapping()
	booleanFieldMapping.Store = true
	booleanFieldMapping.IncludeInAll = false

	docMapping := bleve.NewDocumentMapping()

	docIDMapping := bleve.NewKeywordFieldMapping()
	docIDMapping.Store = true
	docIDMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("doc_id", docIDMapping)

	tenantIDMapping := bleve.NewKeywordFieldMapping()
	tenantIDMapping.Store = true
	tenantIDMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("tenant_id", tenantIDMapping)

	spaceIDMapping := bleve.NewKeywordFieldMapping()
	spaceIDMapping.Store = true
	spaceIDMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("space_id", spaceIDMapping)

	titleMapping := bleve.NewTextFieldMapping()
	titleMapping.Analyzer = ZhAnalyzerName
	titleMapping.Store = true
	titleMapping.IncludeInAll = true
	titleMapping.IncludeTermVectors = true
	docMapping.AddFieldMappingsAt("title", titleMapping)

	titlePinyinMapping := bleve.NewTextFieldMapping()
	titlePinyinMapping.Analyzer = ZhPinyinAnalyzer
	titlePinyinMapping.Store = true
	titlePinyinMapping.IncludeInAll = true
	titlePinyinMapping.IncludeTermVectors = true
	docMapping.AddFieldMappingsAt("title", titlePinyinMapping)

	contentMapping := bleve.NewTextFieldMapping()
	contentMapping.Analyzer = ZhAnalyzerName
	contentMapping.Store = true
	contentMapping.IncludeInAll = true
	contentMapping.IncludeTermVectors = true
	docMapping.AddFieldMappingsAt("content", contentMapping)

	summaryMapping := bleve.NewTextFieldMapping()
	summaryMapping.Analyzer = ZhAnalyzerName
	summaryMapping.Store = true
	summaryMapping.IncludeInAll = true
	summaryMapping.IncludeTermVectors = true
	docMapping.AddFieldMappingsAt("summary", summaryMapping)

	tagsMapping := bleve.NewTextFieldMapping()
	tagsMapping.Analyzer = "keyword"
	tagsMapping.Store = true
	tagsMapping.IncludeInAll = true
	docMapping.AddFieldMappingsAt("tags", tagsMapping)

	authorIDMapping := bleve.NewKeywordFieldMapping()
	authorIDMapping.Store = true
	authorIDMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("author_id", authorIDMapping)

	authorNameMapping := bleve.NewTextFieldMapping()
	authorNameMapping.Analyzer = ZhAnalyzerName
	authorNameMapping.Store = true
	authorNameMapping.IncludeInAll = true
	docMapping.AddFieldMappingsAt("author_name", authorNameMapping)

	langCodeMapping := bleve.NewKeywordFieldMapping()
	langCodeMapping.Store = true
	langCodeMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("lang_code", langCodeMapping)

	categoryMapping := bleve.NewKeywordFieldMapping()
	categoryMapping.Store = true
	categoryMapping.IncludeInAll = true
	docMapping.AddFieldMappingsAt("category", categoryMapping)

	statusMapping := bleve.NewKeywordFieldMapping()
	statusMapping.Store = true
	statusMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("status", statusMapping)

	priorityMapping := bleve.NewNumericFieldMapping()
	priorityMapping.Store = true
	priorityMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("priority", priorityMapping)

	viewCountMapping := bleve.NewNumericFieldMapping()
	viewCountMapping.Store = true
	viewCountMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("view_count", viewCountMapping)

	likeCountMapping := bleve.NewNumericFieldMapping()
	likeCountMapping.Store = true
	likeCountMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("like_count", likeCountMapping)

	attachmentIDsMapping := bleve.NewKeywordFieldMapping()
	attachmentIDsMapping.Store = true
	attachmentIDsMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("attachment_ids", attachmentIDsMapping)

	attachmentTextsMapping := bleve.NewTextFieldMapping()
	attachmentTextsMapping.Analyzer = ZhAnalyzerName
	attachmentTextsMapping.Store = true
	attachmentTextsMapping.IncludeInAll = true
	attachmentTextsMapping.IncludeTermVectors = true
	docMapping.AddFieldMappingsAt("attachment_texts", attachmentTextsMapping)

	metadataMapping := bleve.NewDocumentMapping()
	metadataMapping.Dynamic = true
	metadataMapping.DefaultAnalyzer = ZhAnalyzerName
	docMapping.AddSubDocumentMapping("metadata", metadataMapping)

	createdAtMapping := bleve.NewDateTimeFieldMapping()
	createdAtMapping.Store = true
	createdAtMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("created_at", createdAtMapping)

	updatedAtMapping := bleve.NewDateTimeFieldMapping()
	updatedAtMapping.Store = true
	updatedAtMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("updated_at", updatedAtMapping)

	publishedAtMapping := bleve.NewDateTimeFieldMapping()
	publishedAtMapping.Store = true
	publishedAtMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("published_at", publishedAtMapping)

	_ = zhFieldMapping
	_ = zhPinyinFieldMapping
	_ = keywordFieldMapping
	_ = dateFieldMapping
	_ = numericFieldMapping
	_ = booleanFieldMapping

	indexMapping.DefaultMapping = docMapping
	indexMapping.DefaultAnalyzer = ZhAnalyzerName
	indexMapping.TypeField = "_type"

	return indexMapping
}

type AttachmentIndex struct {
	AttachmentID string `json:"attachment_id"`
	TenantID     string `json:"tenant_id"`
	SpaceID      string `json:"space_id"`
	DocID        string `json:"doc_id"`
	FileName     string `json:"file_name"`
	FileType     string `json:"file_type"`
	FileSize     int64  `json:"file_size"`
	Content      string `json:"content"`
	LangCode     string `json:"lang_code"`
	CreatedAt    int64  `json:"created_at"`
	UpdatedAt    int64  `json:"updated_at"`
}

func BuildAttachmentMapping() mapping.IndexMapping {
	indexMapping := bleve.NewIndexMapping()

	if err := RegisterCustomAnalyzers(); err != nil {
		_ = err
	}

	docMapping := bleve.NewDocumentMapping()

	attachmentIDMapping := bleve.NewKeywordFieldMapping()
	attachmentIDMapping.Store = true
	attachmentIDMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("attachment_id", attachmentIDMapping)

	tenantIDMapping := bleve.NewKeywordFieldMapping()
	tenantIDMapping.Store = true
	tenantIDMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("tenant_id", tenantIDMapping)

	spaceIDMapping := bleve.NewKeywordFieldMapping()
	spaceIDMapping.Store = true
	spaceIDMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("space_id", spaceIDMapping)

	docIDMapping := bleve.NewKeywordFieldMapping()
	docIDMapping.Store = true
	docIDMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("doc_id", docIDMapping)

	fileNameMapping := bleve.NewTextFieldMapping()
	fileNameMapping.Analyzer = ZhAnalyzerName
	fileNameMapping.Store = true
	fileNameMapping.IncludeInAll = true
	fileNameMapping.IncludeTermVectors = true
	docMapping.AddFieldMappingsAt("file_name", fileNameMapping)

	fileTypeMapping := bleve.NewKeywordFieldMapping()
	fileTypeMapping.Store = true
	fileTypeMapping.IncludeInAll = true
	docMapping.AddFieldMappingsAt("file_type", fileTypeMapping)

	fileSizeMapping := bleve.NewNumericFieldMapping()
	fileSizeMapping.Store = true
	fileSizeMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("file_size", fileSizeMapping)

	attachmentContentMapping := bleve.NewTextFieldMapping()
	attachmentContentMapping.Analyzer = ZhAnalyzerName
	attachmentContentMapping.Store = true
	attachmentContentMapping.IncludeInAll = true
	attachmentContentMapping.IncludeTermVectors = true
	docMapping.AddFieldMappingsAt("content", attachmentContentMapping)

	langCodeMapping := bleve.NewKeywordFieldMapping()
	langCodeMapping.Store = true
	langCodeMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("lang_code", langCodeMapping)

	createdAtMapping := bleve.NewDateTimeFieldMapping()
	createdAtMapping.Store = true
	createdAtMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("created_at", createdAtMapping)

	updatedAtMapping := bleve.NewDateTimeFieldMapping()
	updatedAtMapping.Store = true
	updatedAtMapping.IncludeInAll = false
	docMapping.AddFieldMappingsAt("updated_at", updatedAtMapping)

	indexMapping.DefaultMapping = docMapping
	indexMapping.DefaultAnalyzer = ZhAnalyzerName

	return indexMapping
}
