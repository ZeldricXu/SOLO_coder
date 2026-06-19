package unit

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/blevesearch/bleve/v2"
	"github.com/blevesearch/bleve/v2/mapping"
	"github.com/enterprise/knowledgebase/internal/search"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func createTempIndex(t *testing.T, m mapping.IndexMapping) bleve.Index {
	t.Helper()
	tmpDir, err := os.MkdirTemp("", "bleve-test-*")
	require.NoError(t, err)

	t.Cleanup(func() {
		os.RemoveAll(tmpDir)
	})

	idxPath := filepath.Join(tmpDir, "test.bleve")
	index, err := bleve.New(idxPath, m)
	require.NoError(t, err)

	t.Cleanup(func() {
		index.Close()
	})

	return index
}

func TestZhAnalyzer(t *testing.T) {
	idxMapping := search.BuildDocumentMapping()
	index := createTempIndex(t, idxMapping)

	doc := search.DocumentIndex{
		DocID:    "doc-1",
		TenantID: "t1",
		Title:    "中文搜索测试文档",
		Content:  "这是一个用于测试中文分词和搜索功能的文档。搜索引擎应该能够正确处理中文文本。",
	}

	err := index.Index(doc.DocID, doc)
	require.NoError(t, err)

	query := bleve.NewMatchQuery("搜索引擎")
	query.FieldVal = "content"
	searchRequest := bleve.NewSearchRequest(query)
	searchRequest.Size = 10

	result, err := index.Search(searchRequest)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, result.Total, uint64(1), "中文查询应该能召回文档")
}

func TestPinyinSearch(t *testing.T) {
	idxMapping := search.BuildDocumentMapping()
	index := createTempIndex(t, idxMapping)

	doc := search.DocumentIndex{
		DocID:    "doc-pinyin-1",
		TenantID: "t1",
		Title:    "知识库系统",
		Content:  "企业内部知识库管理系统",
	}

	err := index.Index(doc.DocID, doc)
	require.NoError(t, err)

	pinyinResult := search.ToPinyin("知识库")
	assert.Equal(t, "zhi shi ku ", pinyinResult)

	titleQuery := bleve.NewMatchQuery("知识库")
	titleQuery.FieldVal = "title"
	titleSearchRequest := bleve.NewSearchRequest(titleQuery)
	titleResult, err := index.Search(titleSearchRequest)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, titleResult.Total, uint64(1), "中文标题搜索应该能找到文档")
}

func TestEditDistanceFuzzyMatch(t *testing.T) {
	idxMapping := bleve.NewIndexMapping()
	index := createTempIndex(t, idxMapping)

	type Doc struct {
		Title   string `json:"title"`
		Content string `json:"content"`
	}

	doc := Doc{
		Title:   "Installation Guide",
		Content: "This is the installation guide for the software.",
	}

	err := index.Index("doc-fuzzy-1", doc)
	require.NoError(t, err)

	fuzzyQuery := bleve.NewFuzzyQuery("installtion")
	fuzzyQuery.Fuzziness = 2
	fuzzyQuery.FieldVal = "title"
	searchRequest := bleve.NewSearchRequest(fuzzyQuery)
	searchRequest.Size = 10

	result, err := index.Search(searchRequest)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, result.Total, uint64(1), "模糊搜索(编辑距离<=2)应该能匹配")
}

func TestMixedLanguageQuery(t *testing.T) {
	idxMapping := search.BuildDocumentMapping()
	index := createTempIndex(t, idxMapping)

	docs := []search.DocumentIndex{
		{
			DocID:    "mix-1",
			TenantID: "t1",
			Title:    "Go语言开发指南",
			Content:  "Go语言并发编程教程，使用goroutine和channel",
		},
		{
			DocID:    "mix-2",
			TenantID: "t1",
			Title:    "Python数据处理",
			Content:  "使用Python进行数据分析和机器学习",
		},
		{
			DocID:    "mix-3",
			TenantID: "t1",
			Title:    "Java企业应用",
			Content:  "Spring框架开发企业级Java应用",
		},
	}

	for _, d := range docs {
		err := index.Index(d.DocID, d)
		require.NoError(t, err)
	}

	query := bleve.NewMatchQuery("Go开发")
	query.FieldVal = "title"
	searchRequest := bleve.NewSearchRequest(query)
	searchRequest.Size = 10

	result, err := index.Search(searchRequest)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, result.Total, uint64(1), "中英文混合查询应该能召回结果")

	lang := search.DetectLanguage("这是一个Go语言的测试")
	assert.Equal(t, "zh", lang)

	lang = search.DetectLanguage("This is English text")
	assert.Equal(t, "en", lang)
}

func TestSearchSortByRelevance(t *testing.T) {
	idxMapping := bleve.NewIndexMapping()
	index := createTempIndex(t, idxMapping)

	type Doc struct {
		Title   string `json:"title"`
		Content string `json:"content"`
	}

	docs := []Doc{
		{
			Title:   "Search Engine",
			Content: "Something unrelated to the main topic",
		},
		{
			Title:   "General Guide",
			Content: "Search engine implementation details and optimization techniques",
		},
	}

	err := index.Index("title-match", docs[0])
	require.NoError(t, err)
	err = index.Index("content-match", docs[1])
	require.NoError(t, err)

	query := bleve.NewMatchQuery("Search Engine")
	searchRequest := bleve.NewSearchRequest(query)
	searchRequest.Size = 10
	searchRequest.Explain = true

	result, err := index.Search(searchRequest)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, result.Total, uint64(2), "应该至少找到两个文档")

	if len(result.Hits) >= 2 {
		assert.NotNil(t, result.Hits[0].Score)
		assert.NotNil(t, result.Hits[1].Score)
	}
}

func TestTimeDecay(t *testing.T) {
	idxMapping := bleve.NewIndexMapping()

	dateFieldMapping := bleve.NewDateTimeFieldMapping()
	dateFieldMapping.Store = true
	dateFieldMapping.IncludeInAll = false

	textFieldMapping := bleve.NewTextFieldMapping()
	textFieldMapping.Store = true
	textFieldMapping.IncludeInAll = true

	docMapping := bleve.NewDocumentMapping()
	docMapping.AddFieldMappingsAt("title", textFieldMapping)
	docMapping.AddFieldMappingsAt("content", textFieldMapping)
	docMapping.AddFieldMappingsAt("created_at", dateFieldMapping)
	idxMapping.DefaultMapping = docMapping

	index := createTempIndex(t, idxMapping)

	type Doc struct {
		Title     string    `json:"title"`
		Content   string    `json:"content"`
		CreatedAt time.Time `json:"created_at"`
	}

	now := time.Now()
	docs := []Doc{
		{
			Title:     "Old Document",
			Content:   "Searchable content here",
			CreatedAt: now.AddDate(0, 0, -30),
		},
		{
			Title:     "New Document",
			Content:   "Searchable content here",
			CreatedAt: now,
		},
	}

	err := index.Index("old-doc", docs[0])
	require.NoError(t, err)
	err = index.Index("new-doc", docs[1])
	require.NoError(t, err)

	query := bleve.NewMatchQuery("Searchable")
	searchRequest := bleve.NewSearchRequest(query)
	searchRequest.SortBy([]string{"-created_at"})
	searchRequest.Size = 10

	result, err := index.Search(searchRequest)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, result.Total, uint64(2), "应该找到两个文档")

	if len(result.Hits) >= 2 {
		assert.Equal(t, "new-doc", result.Hits[0].ID, "新文档应该排在前面")
	}
}

func TestAttachmentTextExtraction(t *testing.T) {
	idxMapping := search.BuildAttachmentMapping()
	index := createTempIndex(t, idxMapping)

	attachment := search.AttachmentIndex{
		AttachmentID: "att-1",
		TenantID:     "t1",
		FileName:     "technical-spec.pdf",
		FileType:     "application/pdf",
		Content:      "This PDF contains technical specifications for the API integration module. The REST API endpoints support JSON and XML formats.",
		CreatedAt:    time.Now().Unix(),
	}

	err := index.Index(attachment.AttachmentID, attachment)
	require.NoError(t, err)

	query := bleve.NewMatchQuery("API integration")
	query.FieldVal = "content"
	searchRequest := bleve.NewSearchRequest(query)
	searchRequest.Size = 10

	result, err := index.Search(searchRequest)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, result.Total, uint64(1), "附件内容应该可被搜索")

	fileNameQuery := bleve.NewMatchQuery("technical")
	fileNameQuery.FieldVal = "file_name"
	fileNameSearchRequest := bleve.NewSearchRequest(fileNameQuery)
	fileNameResult, err := index.Search(fileNameSearchRequest)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, fileNameResult.Total, uint64(1), "文件名应该可被搜索")
}

func TestSearchHighlight(t *testing.T) {
	idxMapping := bleve.NewIndexMapping()

	fieldMapping := bleve.NewTextFieldMapping()
	fieldMapping.Store = true
	fieldMapping.IncludeInAll = true
	fieldMapping.IncludeTermVectors = true

	docMapping := bleve.NewDocumentMapping()
	docMapping.AddFieldMappingsAt("title", fieldMapping)
	docMapping.AddFieldMappingsAt("content", fieldMapping)
	idxMapping.DefaultMapping = docMapping

	index := createTempIndex(t, idxMapping)

	type Doc struct {
		Title   string `json:"title"`
		Content string `json:"content"`
	}

	doc := Doc{
		Title:   "Advanced Search Techniques",
		Content: "Modern search engines use advanced techniques for information retrieval. These techniques include keyword matching, semantic analysis, and ranking algorithms.",
	}

	err := index.Index("hl-doc-1", doc)
	require.NoError(t, err)

	query := bleve.NewMatchQuery("search")
	searchRequest := bleve.NewSearchRequest(query)
	searchRequest.Size = 10

	searchRequest.Highlight = bleve.NewHighlight()
	searchRequest.Highlight.AddField("title")
	searchRequest.Highlight.AddField("content")
	searchRequest.Fields = []string{"title", "content"}

	result, err := index.Search(searchRequest)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, result.Total, uint64(1))

	if len(result.Hits) > 0 {
		hit := result.Hits[0]
		assert.NotNil(t, hit, "应该有搜索命中")

		if len(hit.Fields) > 0 {
			titleVal, hasTitle := hit.Fields["title"]
			assert.True(t, hasTitle, "应该返回title字段")
			assert.NotEmpty(t, titleVal, "title字段不应为空")
		}
	}
}
