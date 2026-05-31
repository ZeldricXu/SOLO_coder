package softwarecatalog

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
	services := r.Group("/services")
	{
		services.GET("", h.ListServices)
		services.POST("", h.RegisterService)
		services.GET("/search", h.Search)
		services.GET("/:id", h.GetService)
		services.PUT("/:id", h.UpdateService)
		services.DELETE("/:id", h.DeleteService)
		services.GET("/:id/dependencies", h.GetDependencies)
		services.GET("/:id/dependents", h.GetDependents)
		services.POST("/:id/versions", h.AddVersion)
		services.GET("/:id/versions", h.ListVersions)
		services.GET("/:id/health", h.GetHealth)
		services.PUT("/:id/health", h.UpdateHealth)
	}

	libs := r.Group("/libraries")
	{
		libs.GET("", h.ListLibraries)
		libs.POST("", h.RegisterLibrary)
		libs.GET("/:id", h.GetLibrary)
		libs.GET("/:id/dependencies", h.GetDependencies)
		libs.GET("/:id/dependents", h.GetDependents)
	}

	deps := r.Group("/dependencies")
	{
		deps.POST("", h.AddDependency)
	}
}

func (h *Handler) ListServices(c *gin.Context) {
	var q SearchQuery
	if err := c.ShouldBindQuery(&q); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	services, total, err := h.service.ListServices(c.Request.Context(), &q)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(map[string]interface{}{
		"items": services,
		"total": total,
		"page":  q.Page,
		"size":  q.Size,
	}))
}

func (h *Handler) RegisterService(c *gin.Context) {
	var svc Service
	if err := c.ShouldBindJSON(&svc); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	created, err := h.service.RegisterService(c.Request.Context(), &svc)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusCreated, models.CreatedResponse(created))
}

func (h *Handler) GetService(c *gin.Context) {
	id := c.Param("id")
	svc, err := h.service.GetService(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusNotFound, models.ErrorResponse(404, "Service not found"))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(svc))
}

func (h *Handler) UpdateService(c *gin.Context) {
	id := c.Param("id")
	var svc Service
	if err := c.ShouldBindJSON(&svc); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	updated, err := h.service.UpdateService(c.Request.Context(), id, &svc)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(updated))
}

func (h *Handler) DeleteService(c *gin.Context) {
	id := c.Param("id")
	if err := h.service.DeleteService(c.Request.Context(), id); err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(map[string]string{"id": id}))
}

func (h *Handler) ListLibraries(c *gin.Context) {
	var q SearchQuery
	if err := c.ShouldBindQuery(&q); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	libs, total, err := h.service.ListLibraries(c.Request.Context(), &q)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(map[string]interface{}{
		"items": libs,
		"total": total,
		"page":  q.Page,
		"size":  q.Size,
	}))
}

func (h *Handler) RegisterLibrary(c *gin.Context) {
	var lib Library
	if err := c.ShouldBindJSON(&lib); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	created, err := h.service.RegisterLibrary(c.Request.Context(), &lib)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusCreated, models.CreatedResponse(created))
}

func (h *Handler) GetLibrary(c *gin.Context) {
	id := c.Param("id")
	lib, err := h.service.GetLibrary(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusNotFound, models.ErrorResponse(404, "Library not found"))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(lib))
}

func (h *Handler) AddDependency(c *gin.Context) {
	var req struct {
		FromID   string `json:"from_id" binding:"required"`
		FromType string `json:"from_type" binding:"required"`
		ToID     string `json:"to_id" binding:"required"`
		ToType   string `json:"to_type" binding:"required"`
		Version  string `json:"version"`
		Scope    string `json:"scope"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	dep, err := h.service.AddDependency(c.Request.Context(),
		req.FromID, req.FromType, req.ToID, req.ToType, req.Version, req.Scope)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusCreated, models.CreatedResponse(dep))
}

func (h *Handler) GetDependencies(c *gin.Context) {
	id := c.Param("id")
	graph, err := h.service.GetDependencies(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(graph))
}

func (h *Handler) GetDependents(c *gin.Context) {
	id := c.Param("id")
	nodes, err := h.service.GetDependents(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(nodes))
}

func (h *Handler) AddVersion(c *gin.Context) {
	serviceID := c.Param("id")
	var ver ServiceVersion
	if err := c.ShouldBindJSON(&ver); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}
	ver.ServiceID = serviceID

	created, err := h.service.AddServiceVersion(c.Request.Context(), &ver)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusCreated, models.CreatedResponse(created))
}

func (h *Handler) ListVersions(c *gin.Context) {
	serviceID := c.Param("id")
	versions, err := h.service.ListServiceVersions(c.Request.Context(), serviceID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(versions))
}

func (h *Handler) UpdateHealth(c *gin.Context) {
	serviceID := c.Param("id")
	var health ServiceHealth
	if err := c.ShouldBindJSON(&health); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}
	health.ServiceID = serviceID

	if err := h.service.UpdateHealth(c.Request.Context(), &health); err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(health))
}

func (h *Handler) GetHealth(c *gin.Context) {
	serviceID := c.Param("id")
	health, err := h.service.GetHealth(c.Request.Context(), serviceID)
	if err != nil {
		c.JSON(http.StatusNotFound, models.ErrorResponse(404, "Health record not found"))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(health))
}

func (h *Handler) Search(c *gin.Context) {
	query := c.Query("q")
	if query == "" {
		c.JSON(http.StatusOK, models.SuccessResponse([]interface{}{}))
		return
	}

	results, err := h.service.Search(c.Request.Context(), query)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(results))
}
