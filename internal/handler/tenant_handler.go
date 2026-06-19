package handler

import (
	"net/http"

	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/pkg/response"
	"github.com/enterprise/knowledgebase/internal/service"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type TenantHandler struct {
	tenantSvc  *service.TenantService
	tenantRepo service.TenantRepository
}

func NewTenantHandler(svc *service.TenantService, tenantRepo service.TenantRepository) *TenantHandler {
	return &TenantHandler{
		tenantSvc:  svc,
		tenantRepo: tenantRepo,
	}
}

func (h *TenantHandler) RegisterRoutes(r *gin.RouterGroup) {
	tenants := r.Group("/tenants")
	{
		tenants.POST("", h.CreateTenant)
		tenants.GET("/:id", h.GetTenant)
		tenants.PUT("/:id", h.UpdateTenant)
		tenants.DELETE("/:id", h.DeleteTenant)
		tenants.GET("/:id/quota", h.GetQuota)
	}
}

func (h *TenantHandler) CreateTenant(c *gin.Context) {
	var req service.CreateTenantRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "invalid request body")
		return
	}

	tenant, err := h.tenantSvc.CreateTenant(c.Request.Context(), req)
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}

	response.Success(c, tenant)
}

func (h *TenantHandler) GetTenant(c *gin.Context) {
	idStr := c.Param("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid tenant id")
		return
	}

	tenant, err := h.tenantRepo.GetByID(c.Request.Context(), id)
	if err != nil {
		response.InternalError(c, "failed to get tenant")
		return
	}
	if tenant == nil {
		response.NotFound(c, "tenant not found")
		return
	}

	response.Success(c, tenant)
}

func (h *TenantHandler) UpdateTenant(c *gin.Context) {
	idStr := c.Param("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid tenant id")
		return
	}

	var req struct {
		Name        string                 `json:"name"`
		Domain      string                 `json:"domain"`
		Namespace   string                 `json:"namespace"`
		Description string                 `json:"description"`
		LogoURL     string                 `json:"logo_url"`
		Status      string                 `json:"status"`
		Settings    map[string]interface{} `json:"settings"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "invalid request body")
		return
	}

	tenant, err := h.tenantRepo.GetByID(c.Request.Context(), id)
	if err != nil {
		response.InternalError(c, "failed to get tenant")
		return
	}
	if tenant == nil {
		response.NotFound(c, "tenant not found")
		return
	}

	if req.Name != "" {
		tenant.Name = req.Name
	}
	if req.Domain != "" {
		tenant.Domain = req.Domain
	}
	if req.Namespace != "" {
		tenant.Namespace = req.Namespace
	}
	if req.Description != "" {
		tenant.Description = req.Description
	}
	if req.LogoURL != "" {
		tenant.LogoURL = req.LogoURL
	}
	if req.Status != "" {
		tenant.Status = req.Status
	}
	if req.Settings != nil {
		tenant.Settings = model.JSONB(req.Settings)
	}

	if err := h.tenantRepo.Update(c.Request.Context(), tenant); err != nil {
		response.InternalError(c, "failed to update tenant")
		return
	}

	response.Success(c, tenant)
}

func (h *TenantHandler) DeleteTenant(c *gin.Context) {
	idStr := c.Param("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid tenant id")
		return
	}

	if err := h.tenantSvc.DeleteTenant(c.Request.Context(), id); err != nil {
		if err.Error() == "tenant not found" {
			response.NotFound(c, err.Error())
			return
		}
		response.InternalError(c, err.Error())
		return
	}

	c.JSON(http.StatusNoContent, nil)
}

func (h *TenantHandler) GetQuota(c *gin.Context) {
	idStr := c.Param("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid tenant id")
		return
	}

	quota, err := h.tenantRepo.GetQuota(c.Request.Context(), id, "")
	if err != nil {
		response.InternalError(c, "failed to get quota")
		return
	}
	if quota == nil {
		quota = &model.Quota{
			TenantScoped: model.TenantScoped{TenantID: idStr},
			PlanType:     "free",
		}
	}

	response.Success(c, quota)
}

var _ = gorm.ErrRecordNotFound
