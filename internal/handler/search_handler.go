package handler

import (
	"github.com/blevesearch/bleve/v2"
	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/pkg/response"
	"github.com/gin-gonic/gin"
)

type SearchHandler struct {
	index *bleve.Index
}

func NewSearchHandler(index *bleve.Index) *SearchHandler {
	return &SearchHandler{index: index}
}

func (h *SearchHandler) RegisterRoutes(r *gin.RouterGroup) {
	r.GET("/search", h.Search)
	r.POST("/search", h.Search)
}

func (h *SearchHandler) Search(c *gin.Context) {
	tenantIDStr, ok := database.GetTenantID(c.Request.Context())

	var query struct {
		Query       string   `json:"q" form:"q"`
		Keyword     string   `json:"keyword" form:"keyword"`
		SpaceID     string   `json:"space_id" form:"space_id"`
		Category    string   `json:"category" form:"category"`
		Tags        []string `json:"tags" form:"tags"`
		LangCode    string   `json:"lang_code" form:"lang_code"`
		Status      string   `json:"status" form:"status"`
		SortBy      string   `json:"sort_by" form:"sort_by"`
		SortOrder   string   `json:"sort_order" form:"sort_order"`
		Page        int      `json:"page" form:"page"`
		PageSize    int      `json:"page_size" form:"page_size"`
	}

	if err := c.ShouldBind(&query); err != nil {
		response.BadRequest(c, "invalid query parameters")
		return
	}

	q := query.Query
	if q == "" {
		q = query.Keyword
	}
	if q == "" {
		response.BadRequest(c, "query keyword is required")
		return
	}

	if query.Page <= 0 {
		query.Page = 1
	}
	if query.PageSize <= 0 {
		query.PageSize = 20
	}
	if query.PageSize > 100 {
		query.PageSize = 100
	}

	if h.index == nil {
		response.Success(c, gin.H{
			"results":     []interface{}{},
			"total":       0,
			"page":        query.Page,
			"page_size":   query.PageSize,
			"total_pages": 0,
		})
		return
	}

	from := (query.Page - 1) * query.PageSize

	searchQuery := bleve.NewMatchQuery(q)
	searchQuery.Fuzziness = 1

	conjunctionQuery := bleve.NewConjunctionQuery(searchQuery)

	if ok && tenantIDStr != "" {
		tenantQuery := bleve.NewTermQuery(tenantIDStr)
		tenantQuery.SetField("tenant_id")
		conjunctionQuery.AddQuery(tenantQuery)
	}

	if query.SpaceID != "" {
		spaceQuery := bleve.NewTermQuery(query.SpaceID)
		spaceQuery.SetField("space_id")
		conjunctionQuery.AddQuery(spaceQuery)
	}

	if query.Category != "" {
		categoryQuery := bleve.NewTermQuery(query.Category)
		categoryQuery.SetField("category")
		conjunctionQuery.AddQuery(categoryQuery)
	}

	if query.Status != "" {
		statusQuery := bleve.NewTermQuery(query.Status)
		statusQuery.SetField("status")
		conjunctionQuery.AddQuery(statusQuery)
	}

	if query.LangCode != "" {
		langQuery := bleve.NewTermQuery(query.LangCode)
		langQuery.SetField("lang_code")
		conjunctionQuery.AddQuery(langQuery)
	}

	for _, tag := range query.Tags {
		tagQuery := bleve.NewTermQuery(tag)
		tagQuery.SetField("tags")
		conjunctionQuery.AddQuery(tagQuery)
	}

	searchRequest := bleve.NewSearchRequestOptions(conjunctionQuery, query.PageSize, from, false)

	sortBy := query.SortBy
	if sortBy == "" {
		sortBy = "_score"
	}
	sortOrder := query.SortOrder
	if sortOrder == "" {
		sortOrder = "desc"
	}
	if sortOrder == "desc" {
		searchRequest.SortBy([]string{"-" + sortBy})
	} else {
		searchRequest.SortBy([]string{sortBy})
	}

	searchRequest.Fields = []string{"*"}

	searchResult, err := (*h.index).SearchInContext(c.Request.Context(), searchRequest)
	if err != nil {
		response.InternalError(c, "search failed: "+err.Error())
		return
	}

	results := make([]map[string]interface{}, 0, len(searchResult.Hits))
	for _, hit := range searchResult.Hits {
		item := make(map[string]interface{})
		item["id"] = hit.ID
		item["score"] = hit.Score
		for k, v := range hit.Fields {
			item[k] = v
		}
		results = append(results, item)
	}

	total := int64(searchResult.Total)
	totalPages := int(total) / query.PageSize
	if int(total)%query.PageSize > 0 {
		totalPages++
	}

	response.Success(c, gin.H{
		"results":     results,
		"total":       total,
		"page":        query.Page,
		"page_size":   query.PageSize,
		"total_pages": totalPages,
		"max_score":   searchResult.MaxScore,
		"took":        searchResult.Took.String(),
	})
}
