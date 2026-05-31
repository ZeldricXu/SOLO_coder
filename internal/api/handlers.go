package api

import (
	"context"
	"net/http"

	"github.com/gin-gonic/gin"
	"streamsql/internal/engine"
	"streamsql/internal/gateway"
	"streamsql/internal/lineage"
	"streamsql/internal/metacrawler"
	"streamsql/internal/quality"
	"streamsql/internal/streamparser"
	"streamsql/internal/vectorindex"
)

type APIHandler struct {
	engine *engine.CoreEngine
}

func NewAPIHandler(engine *engine.CoreEngine) *APIHandler {
	return &APIHandler{
		engine: engine,
	}
}

type HealthResponse struct {
	Status  string                 `json:"status"`
	Version string                 `json:"version"`
	Stats   map[string]interface{} `json:"stats"`
}

func (h *APIHandler) Health(c *gin.Context) {
	stats := h.engine.GetStats()
	c.JSON(http.StatusOK, HealthResponse{
		Status:  "ok",
		Version: "1.0.0",
		Stats:   stats,
	})
}

type QueryRequest struct {
	SQL string `json:"sql" binding:"required"`
}

func (h *APIHandler) ExecuteQuery(c *gin.Context) {
	var req QueryRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	ctx := context.Background()
	result, err := h.engine.ExecuteQuery(ctx, req.SQL)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"error":  err.Error(),
			"result": result,
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    result,
	})
}

func (h *APIHandler) ListQueries(c *gin.Context) {
	queries := h.engine.GetParserService().ListQueries()
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    queries,
	})
}

func (h *APIHandler) GetQuery(c *gin.Context) {
	id := c.Param("id")
	query, err := h.engine.GetParserService().GetQuery(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    query,
	})
}

func (h *APIHandler) DeleteQuery(c *gin.Context) {
	id := c.Param("id")
	err := h.engine.GetParserService().DeleteQuery(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "deleted",
	})
}

func (h *APIHandler) ParseSQL(c *gin.Context) {
	var req QueryRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	plan, err := h.engine.GetParserService().Parse(req.SQL)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    plan,
	})
}

func (h *APIHandler) OptimizePlan(c *gin.Context) {
	var plan streamparser.LogicalPlan
	if err := c.ShouldBindJSON(&plan); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	optimized, err := h.engine.GetParserService().Optimize(&plan)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    optimized,
	})
}

func (h *APIHandler) GeneratePhysicalPlan(c *gin.Context) {
	var plan streamparser.LogicalPlan
	if err := c.ShouldBindJSON(&plan); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	physical, err := h.engine.GetParserService().GeneratePhysicalPlan(&plan)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    physical,
	})
}

func (h *APIHandler) ListQualityRules(c *gin.Context) {
	rules := h.engine.GetQualityService().ListRules()
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    rules,
	})
}

func (h *APIHandler) CreateQualityRule(c *gin.Context) {
	var rule quality.QualityRule
	if err := c.ShouldBindJSON(&rule); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	created, err := h.engine.GetQualityService().CreateRule(&rule)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"code":    201,
		"message": "created",
		"data":    created,
	})
}

func (h *APIHandler) GetQualityRule(c *gin.Context) {
	id := c.Param("id")
	rule, err := h.engine.GetQualityService().GetRule(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    rule,
	})
}

func (h *APIHandler) UpdateQualityRule(c *gin.Context) {
	id := c.Param("id")
	var rule quality.QualityRule
	if err := c.ShouldBindJSON(&rule); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	updated, err := h.engine.GetQualityService().UpdateRule(id, &rule)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "updated",
		"data":    updated,
	})
}

func (h *APIHandler) DeleteQualityRule(c *gin.Context) {
	id := c.Param("id")
	err := h.engine.GetQualityService().DeleteRule(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "deleted",
	})
}

func (h *APIHandler) ListQualityAnomalies(c *gin.Context) {
	anomalies := h.engine.GetQualityService().ListAnomalies()
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    anomalies,
	})
}

func (h *APIHandler) StartQualityRule(c *gin.Context) {
	id := c.Param("id")
	err := h.engine.GetQualityService().StartRule(id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "started",
	})
}

func (h *APIHandler) StopQualityRule(c *gin.Context) {
	id := c.Param("id")
	err := h.engine.GetQualityService().StopRule(id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "stopped",
	})
}

func (h *APIHandler) ExecuteQualityRule(c *gin.Context) {
	id := c.Param("id")
	results, err := h.engine.GetQualityService().ExecuteRule(id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "executed",
		"data":    results,
	})
}

func (h *APIHandler) ListDataSources(c *gin.Context) {
	sources := h.engine.GetCrawlerService().ListSources()
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    sources,
	})
}

func (h *APIHandler) CreateDataSource(c *gin.Context) {
	var source metacrawler.DataSource
	if err := c.ShouldBindJSON(&source); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	created, err := h.engine.GetCrawlerService().CreateSource(&source)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"code":    201,
		"message": "created",
		"data":    created,
	})
}

func (h *APIHandler) GetDataSource(c *gin.Context) {
	id := c.Param("id")
	source, err := h.engine.GetCrawlerService().GetSource(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    source,
	})
}

func (h *APIHandler) UpdateDataSource(c *gin.Context) {
	id := c.Param("id")
	var source metacrawler.DataSource
	if err := c.ShouldBindJSON(&source); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	updated, err := h.engine.GetCrawlerService().UpdateSource(id, &source)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "updated",
		"data":    updated,
	})
}

func (h *APIHandler) DeleteDataSource(c *gin.Context) {
	id := c.Param("id")
	err := h.engine.GetCrawlerService().DeleteSource(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "deleted",
	})
}

func (h *APIHandler) TestDataSource(c *gin.Context) {
	id := c.Param("id")
	err := h.engine.GetCrawlerService().TestConnection(id)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"success": false,
			"error":   err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"success": true,
		"message": "connection ok",
	})
}

func (h *APIHandler) StartCrawl(c *gin.Context) {
	id := c.Param("id")
	task, err := h.engine.GetCrawlerService().StartCrawl(id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusAccepted, gin.H{
		"code":    202,
		"message": "crawling",
		"data":    task,
	})
}

func (h *APIHandler) GetCrawlTask(c *gin.Context) {
	id := c.Param("id")
	task, err := h.engine.GetCrawlerService().GetTask(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    task,
	})
}

func (h *APIHandler) ListCrawlTasks(c *gin.Context) {
	tasks := h.engine.GetCrawlerService().ListTasks()
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    tasks,
	})
}

func (h *APIHandler) ListSchemas(c *gin.Context) {
	schemas := h.engine.GetCrawlerService().ListSchemas()
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    schemas,
	})
}

func (h *APIHandler) GetSchema(c *gin.Context) {
	id := c.Param("id")
	schema, err := h.engine.GetCrawlerService().GetSchema(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    schema,
	})
}

func (h *APIHandler) SearchTables(c *gin.Context) {
	keyword := c.Query("keyword")
	tables := h.engine.GetCrawlerService().SearchTables(keyword)
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    tables,
	})
}

func (h *APIHandler) ParseLineage(c *gin.Context) {
	var req QueryRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	parsed, err := h.engine.GetLineageService().ParseSQL(req.SQL, "api")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    parsed,
	})
}

func (h *APIHandler) ListLineage(c *gin.Context) {
	parsedList := h.engine.GetLineageService().ListParsed()
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    parsedList,
	})
}

func (h *APIHandler) GetLineageDAG(c *gin.Context) {
	dag := h.engine.GetLineageService().GetDAG()
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    dag,
	})
}

func (h *APIHandler) GetUpstream(c *gin.Context) {
	node := c.Param("node")
	upstream := h.engine.GetLineageService().GetUpstream(node)
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    upstream,
	})
}

func (h *APIHandler) GetDownstream(c *gin.Context) {
	node := c.Param("node")
	downstream := h.engine.GetLineageService().GetDownstream(node)
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    downstream,
	})
}

func (h *APIHandler) GetLineageStats(c *gin.Context) {
	stats := h.engine.GetLineageService().GetStats()
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    stats,
	})
}

type VectorIndexRequest struct {
	Name string `json:"name" binding:"required"`
	Dim  int    `json:"dim" binding:"required,min=1"`
	Type string `json:"type"`
}

func (h *APIHandler) CreateVectorIndex(c *gin.Context) {
	var req VectorIndexRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	index, err := h.engine.GetVectorService().CreateIndex(req.Name, req.Dim, req.Type)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"code":    201,
		"message": "created",
		"data":    index,
	})
}

func (h *APIHandler) ListVectorIndexes(c *gin.Context) {
	indexes := h.engine.GetVectorService().ListIndexes()
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    indexes,
	})
}

type VectorAddRequest struct {
	ID     string    `json:"id" binding:"required"`
	Vector []float64 `json:"vector" binding:"required"`
	Label  string    `json:"label"`
}

func (h *APIHandler) AddToVectorIndex(c *gin.Context) {
	name := c.Param("name")
	var req VectorAddRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	err := h.engine.GetVectorService().Add(name, req.ID, req.Vector, req.Label)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "added",
	})
}

type VectorSearchRequest struct {
	Vector []float64 `json:"vector" binding:"required"`
	TopK   int       `json:"top_k" binding:"min=1,max=100"`
}

func (h *APIHandler) SearchVectorIndex(c *gin.Context) {
	name := c.Param("name")
	var req VectorSearchRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if req.TopK == 0 {
		req.TopK = 10
	}

	results, err := h.engine.GetVectorService().Search(name, req.Vector, req.TopK)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    results,
	})
}

func (h *APIHandler) BuildVectorIndex(c *gin.Context) {
	name := c.Param("name")
	err := h.engine.GetVectorService().Build(name)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "built",
	})
}

func (h *APIHandler) GetGatewayStats(c *gin.Context) {
	gateway := c.MustGet("gateway").(*gateway.APIGateway)
	stats := gateway.GetStats()
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data":    stats,
	})
}
