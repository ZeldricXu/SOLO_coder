package handler

import (
	"net/http"
	"strconv"

	"projectservice/internal/model"
	"projectservice/internal/service"

	"github.com/gin-gonic/gin"
)

type CatalogHandler struct {
	*Handler
	service *service.CatalogService
}

func NewCatalogHandler(h *Handler, svc *service.CatalogService) *CatalogHandler {
	return &CatalogHandler{
		Handler: h,
		service: svc,
	}
}

func (h *CatalogHandler) RegisterService(c *gin.Context) {
	var req model.RegisterServiceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	svc, err := h.service.RegisterService(c.Request.Context(), &req)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "REGISTER_ERROR", "Failed to register service", err.Error())
		return
	}

	h.CreatedResponse(c, svc)
}

func (h *CatalogHandler) GetService(c *gin.Context) {
	serviceID := c.Param("service_id")

	svc, err := h.service.GetService(c.Request.Context(), serviceID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Service not found", err.Error())
		return
	}

	h.SuccessResponse(c, svc)
}

func (h *CatalogHandler) SearchCatalog(c *gin.Context) {
	query := c.Query("q")
	svcType := c.Query("type")
	tags := c.QueryArray("tags")
	owner := c.Query("owner")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	req := &model.SearchCatalogRequest{
		Query:    query,
		Type:     svcType,
		Tags:     tags,
		Owner:    owner,
		Page:     page,
		PageSize: pageSize,
	}

	services, total, err := h.service.SearchCatalog(c.Request.Context(), req)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "SEARCH_ERROR", "Search failed", err.Error())
		return
	}

	h.PaginatedResponse(c, services, page, pageSize, total)
}

func (h *CatalogHandler) UpdateService(c *gin.Context) {
	serviceID := c.Param("service_id")

	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid update data", err.Error())
		return
	}

	if err := h.service.UpdateService(c.Request.Context(), serviceID, updates); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Service not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Service updated successfully"})
}

func (h *CatalogHandler) DeleteService(c *gin.Context) {
	serviceID := c.Param("service_id")

	if err := h.service.DeleteService(c.Request.Context(), serviceID); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Service not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Service deleted successfully"})
}

func (h *CatalogHandler) AddDependency(c *gin.Context) {
	var dep model.ServiceDependency
	if err := c.ShouldBindJSON(&dep); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid dependency data", err.Error())
		return
	}

	created, err := h.service.AddDependency(c.Request.Context(), &dep)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "CREATE_ERROR", "Failed to add dependency", err.Error())
		return
	}

	h.CreatedResponse(c, created)
}

func (h *CatalogHandler) RemoveDependency(c *gin.Context) {
	dependencyID := c.Param("dependency_id")

	if err := h.service.RemoveDependency(c.Request.Context(), dependencyID); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Dependency not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Dependency removed successfully"})
}

func (h *CatalogHandler) GetDependencyGraph(c *gin.Context) {
	serviceID := c.Param("service_id")
	depth, _ := strconv.Atoi(c.DefaultQuery("depth", "3"))

	graph, err := h.service.GetDependencyGraph(c.Request.Context(), serviceID, depth)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "GRAPH_ERROR", "Failed to build dependency graph", err.Error())
		return
	}

	h.SuccessResponse(c, graph)
}

func (h *CatalogHandler) ListServices(c *gin.Context) {
	svcType := c.Query("type")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	services, total, err := h.service.ListServices(c.Request.Context(), svcType, page, pageSize)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "QUERY_ERROR", "Failed to list services", err.Error())
		return
	}

	h.PaginatedResponse(c, services, page, pageSize, total)
}
