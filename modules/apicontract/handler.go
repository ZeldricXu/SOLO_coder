package apicontract

import (
	"depguard/models"
	"github.com/gin-gonic/gin"
	"net/http"
)

type Handler struct {
	service *Service
}

func NewHandler() *Handler {
	return &Handler{service: NewService()}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	schemas := r.Group("/schemas")
	{
		schemas.GET("", h.ListSchemas)
		schemas.POST("", h.RegisterSchema)
		schemas.GET("/:id", h.GetSchema)
		schemas.POST("/validate", h.Validate)
	}

	mocks := r.Group("/mock-servers")
	{
		mocks.GET("", h.ListMockServers)
		mocks.POST("", h.CreateMockServer)
		mocks.POST("/:id/start", h.StartMockServer)
		mocks.POST("/:id/stop", h.StopMockServer)
		mocks.GET("/:id", h.GetMockServer)
	}

	contracts := r.Group("/contracts")
	{
		contracts.GET("", h.ListContracts)
		contracts.POST("", h.CreateContract)
		contracts.POST("/:id/run", h.RunContractTest)
	}
}

func (h *Handler) RegisterSchema(c *gin.Context) {
	var schema APISchema
	if err := c.ShouldBindJSON(&schema); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	created, err := h.service.RegisterSchema(c.Request.Context(), &schema)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusCreated, models.CreatedResponse(created))
}

func (h *Handler) ListSchemas(c *gin.Context) {
	schemaType := c.Query("type")
	serviceID := c.Query("service_id")

	schemas, err := h.service.ListSchemas(c.Request.Context(), schemaType, serviceID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(schemas))
}

func (h *Handler) GetSchema(c *gin.Context) {
	id := c.Param("id")
	schema, err := h.service.GetSchema(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusNotFound, models.ErrorResponse(404, "Schema not found"))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(schema))
}

func (h *Handler) Validate(c *gin.Context) {
	var req ValidateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	result, err := h.service.ValidateSchema(c.Request.Context(), &req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(result))
}

func (h *Handler) CreateMockServer(c *gin.Context) {
	var req CreateMockRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	mock, err := h.service.CreateMockServer(c.Request.Context(), &req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusCreated, models.CreatedResponse(mock))
}

func (h *Handler) StartMockServer(c *gin.Context) {
	id := c.Param("id")
	mock, err := h.service.StartMockServer(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(mock))
}

func (h *Handler) StopMockServer(c *gin.Context) {
	id := c.Param("id")
	if err := h.service.StopMockServer(c.Request.Context(), id); err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(map[string]string{"id": id, "status": "stopped"}))
}

func (h *Handler) ListMockServers(c *gin.Context) {
	servers, err := h.service.ListMockServers(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(servers))
}

func (h *Handler) GetMockServer(c *gin.Context) {
	id := c.Param("id")
	var mock MockServer
	if err := h.service.db.WithContext(c.Request.Context()).First(&mock, "id = ?", id).Error; err != nil {
		c.JSON(http.StatusNotFound, models.ErrorResponse(404, "Mock server not found"))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(mock))
}

func (h *Handler) CreateContract(c *gin.Context) {
	var contract ContractTest
	if err := c.ShouldBindJSON(&contract); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	created, err := h.service.CreateContract(c.Request.Context(), &contract)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusCreated, models.CreatedResponse(created))
}

func (h *Handler) ListContracts(c *gin.Context) {
	contracts, err := h.service.ListContracts(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(contracts))
}

func (h *Handler) RunContractTest(c *gin.Context) {
	id := c.Param("id")
	run, err := h.service.RunContractTest(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(run))
}
