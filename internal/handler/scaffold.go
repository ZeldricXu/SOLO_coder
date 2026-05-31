package handler

import (
	"net/http"
	"strconv"

	"projectservice/internal/model"
	"projectservice/internal/service"

	"github.com/gin-gonic/gin"
)

type ScaffoldHandler struct {
	*Handler
	service *service.ScaffoldService
}

func NewScaffoldHandler(h *Handler, svc *service.ScaffoldService) *ScaffoldHandler {
	return &ScaffoldHandler{
		Handler: h,
		service: svc,
	}
}

func (h *ScaffoldHandler) ListTemplates(c *gin.Context) {
	language := c.Query("language")
	tags := c.QueryArray("tags")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	templates, total, err := h.service.ListTemplates(c.Request.Context(), language, tags, page, pageSize)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "QUERY_ERROR", "Failed to list templates", err.Error())
		return
	}

	h.PaginatedResponse(c, templates, page, pageSize, total)
}

func (h *ScaffoldHandler) GetTemplate(c *gin.Context) {
	templateID := c.Param("template_id")

	template, err := h.service.GetTemplate(c.Request.Context(), templateID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Template not found", err.Error())
		return
	}

	h.SuccessResponse(c, template)
}

func (h *ScaffoldHandler) GetInteractiveQuestions(c *gin.Context) {
	templateID := c.Param("template_id")

	questions, err := h.service.GetInteractiveQuestions(c.Request.Context(), templateID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Template not found", err.Error())
		return
	}

	h.SuccessResponse(c, questions)
}

func (h *ScaffoldHandler) GenerateProject(c *gin.Context) {
	var req model.GenerateProjectRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	if err := h.service.ValidateName(req.ProjectName); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid project name", err.Error())
		return
	}

	project, err := h.service.GenerateProject(c.Request.Context(), &req)
	if err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "GENERATION_ERROR", "Project generation failed", err.Error())
		return
	}

	h.CreatedResponse(c, project)
}

func (h *ScaffoldHandler) BatchGenerateProjects(c *gin.Context) {
	var req model.BatchGenerateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	if len(req.Projects) > 100 {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Batch size exceeds limit", "Maximum 100 projects per batch")
		return
	}

	result, err := h.service.BatchGenerateProjects(c.Request.Context(), &req)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "BATCH_ERROR", "Batch generation failed", err.Error())
		return
	}

	h.SuccessResponse(c, result)
}

func (h *ScaffoldHandler) CreateTemplate(c *gin.Context) {
	var template model.ProjectTemplate
	if err := c.ShouldBindJSON(&template); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid template data", err.Error())
		return
	}

	created, err := h.service.CreateTemplate(c.Request.Context(), &template)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "CREATE_ERROR", "Failed to create template", err.Error())
		return
	}

	h.CreatedResponse(c, created)
}

func (h *ScaffoldHandler) GetGeneratedProject(c *gin.Context) {
	projectID := c.Param("project_id")

	project, err := h.service.GetGeneratedProject(c.Request.Context(), projectID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Project not found", err.Error())
		return
	}

	h.SuccessResponse(c, project)
}

func (h *ScaffoldHandler) ListGeneratedProjects(c *gin.Context) {
	templateID := c.Query("template_id")
	status := c.Query("status")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	projects, total, err := h.service.ListGeneratedProjects(c.Request.Context(), templateID, status, page, pageSize)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "QUERY_ERROR", "Failed to list projects", err.Error())
		return
	}

	h.PaginatedResponse(c, projects, page, pageSize, total)
}

func (h *ScaffoldHandler) DeleteTemplate(c *gin.Context) {
	templateID := c.Param("template_id")

	if err := h.service.DeleteTemplate(c.Request.Context(), templateID); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Template not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Template deleted successfully"})
}

// ===== 批量操作增强接口

func (h *ScaffoldHandler) GetBatchProgress(c *gin.Context) {
	batchID := c.Param("batch_id")

	progress, err := h.service.GetBatchProgress(batchID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Batch not found", err.Error())
		return
	}

	h.SuccessResponse(c, progress)
}

func (h *ScaffoldHandler) GetBatchStatus(c *gin.Context) {
	batchID := c.Param("batch_id")

	status, err := h.service.GetBatchStatus(batchID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Batch not found", err.Error())
		return
	}

	h.SuccessResponse(c, status)
}

func (h *ScaffoldHandler) BatchGenerateWithTimeout(c *gin.Context) {
	var req model.BatchGenerateTimeoutRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	if len(req.Projects) > 100 {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Batch size exceeds limit", "Maximum 100 projects per batch")
		return
	}

	result, err := h.service.BatchGenerateWithTimeout(c.Request.Context(), &req)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "BATCH_ERROR", "Batch generation failed", err.Error())
		return
	}

	h.SuccessResponse(c, result)
}

func (h *ScaffoldHandler) CoalesceAndGenerate(c *gin.Context) {
	var req model.CoalescedGenerateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	result, err := h.service.CoalesceAndGenerate(c.Request.Context(), &req)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "COALESCE_ERROR", "Coalesced generation failed", err.Error())
		return
	}

	h.SuccessResponse(c, result)
}
