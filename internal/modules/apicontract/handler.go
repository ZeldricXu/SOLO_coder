package apicontract

import (
	"depguard/internal/common/response"
	"strconv"

	"github.com/gin-gonic/gin"
)

type Handler struct {
	service *APIContractService
}

func NewHandler() *Handler {
	return &Handler{
		service: NewAPIContractService(),
	}
}

func (h *Handler) CreateSchema(c *gin.Context) {
	var req CreateSchemaRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	schema, err := h.service.CreateSchema(&req)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.SuccessCreated(c, schema)
}

func (h *Handler) UpdateSchema(c *gin.Context) {
	id := c.Param("id")
	var req UpdateSchemaRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	err := h.service.UpdateSchema(id, &req)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, nil)
}

func (h *Handler) DeleteSchema(c *gin.Context) {
	id := c.Param("id")
	err := h.service.DeleteSchema(id)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, nil)
}

func (h *Handler) GetSchema(c *gin.Context) {
	id := c.Param("id")
	schema, err := h.service.GetSchema(id)
	if err != nil {
		response.NotFound(c, "Schema not found")
		return
	}
	response.Success(c, schema)
}

func (h *Handler) ListSchemas(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	schemaType := c.Query("type")
	serviceName := c.Query("service")

	schemas, total, err := h.service.ListSchemas(page, pageSize, schemaType, serviceName)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, gin.H{
		"items": schemas,
		"total": total,
		"page":  page,
		"size":  pageSize,
	})
}

func (h *Handler) ValidateSchema(c *gin.Context) {
	var req ValidateSchemaRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	result, err := h.service.ValidateSchema(req.SchemaID)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, result)
}

func (h *Handler) GetValidationHistory(c *gin.Context) {
	schemaID := c.Param("schema_id")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	results, err := h.service.GetValidationHistory(schemaID, limit)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, results)
}

func (h *Handler) CreateMockServer(c *gin.Context) {
	var req CreateMockServerRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	server, err := h.service.CreateMockServer(&req)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.SuccessCreated(c, gin.H{
		"server_id": server.ServerID,
		"name":      server.Name,
		"status":    server.Status,
		"base_url":  server.BaseURL,
		"port":      server.Port,
		"endpoints": server.Endpoints,
	})
}

func (h *Handler) StopMockServer(c *gin.Context) {
	serverID := c.Param("server_id")
	err := h.service.StopMockServer(serverID)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, gin.H{"message": "Mock server stopped"})
}

func (h *Handler) GetMockServer(c *gin.Context) {
	serverID := c.Param("server_id")
	server, err := h.service.GetMockServer(serverID)
	if err != nil {
		response.NotFound(c, "Mock server not found")
		return
	}
	response.Success(c, server)
}

func (h *Handler) ListMockServers(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	status := c.Query("status")

	servers, total, err := h.service.ListMockServers(page, pageSize, status)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, gin.H{
		"items": servers,
		"total": total,
		"page":  page,
		"size":  pageSize,
	})
}

func (h *Handler) CreateContractTest(c *gin.Context) {
	var req CreateContractTestRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	test, err := h.service.CreateContractTest(&req)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.SuccessCreated(c, test)
}

func (h *Handler) RunContractTest(c *gin.Context) {
	var req RunContractTestRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	result, err := h.service.RunContractTest(req.TestID)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, result)
}

func (h *Handler) ListContractTests(c *gin.Context) {
	schemaID := c.Query("schema_id")
	tests, err := h.service.ListContractTests(schemaID)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, tests)
}

func (h *Handler) DeleteContractTest(c *gin.Context) {
	id := c.Param("id")
	err := h.service.DeleteContractTest(id)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, nil)
}

func (h *Handler) WarmupCache(c *gin.Context) {
	count, err := h.service.WarmupCache()
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, gin.H{
		"message":      "Cache warmup completed",
		"items_loaded": count,
	})
}

func (h *Handler) ClearCache(c *gin.Context) {
	h.service.ClearCache()
	response.Success(c, gin.H{"message": "Cache cleared"})
}

func (h *Handler) GetCacheStats(c *gin.Context) {
	stats := h.service.GetCacheStats()
	response.Success(c, stats)
}

func (h *Handler) ResetCacheStats(c *gin.Context) {
	h.service.ResetCacheStats()
	response.Success(c, gin.H{"message": "Cache stats reset"})
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	schemas := r.Group("/schemas")
	{
		schemas.POST("", h.CreateSchema)
		schemas.GET("", h.ListSchemas)
		schemas.GET("/:id", h.GetSchema)
		schemas.PUT("/:id", h.UpdateSchema)
		schemas.DELETE("/:id", h.DeleteSchema)
		schemas.POST("/validate", h.ValidateSchema)
		schemas.GET("/:schema_id/validations", h.GetValidationHistory)
	}

	mockServers := r.Group("/mock-servers")
	{
		mockServers.POST("", h.CreateMockServer)
		mockServers.GET("", h.ListMockServers)
		mockServers.GET("/:server_id", h.GetMockServer)
		mockServers.POST("/:server_id/stop", h.StopMockServer)
	}

	contractTests := r.Group("/contract-tests")
	{
		contractTests.POST("", h.CreateContractTest)
		contractTests.GET("", h.ListContractTests)
		contractTests.DELETE("/:id", h.DeleteContractTest)
		contractTests.POST("/run", h.RunContractTest)
	}

	cache := r.Group("/cache")
	{
		cache.POST("/warmup", h.WarmupCache)
		cache.POST("/clear", h.ClearCache)
		cache.GET("/stats", h.GetCacheStats)
		cache.POST("/stats/reset", h.ResetCacheStats)
	}
}
