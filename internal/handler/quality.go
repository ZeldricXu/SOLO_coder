package handler

import (
	"net/http"
	"strconv"

	"projectservice/internal/model"
	"projectservice/internal/service"

	"github.com/gin-gonic/gin"
)

type QualityHandler struct {
	*Handler
	service *service.QualityService
}

func NewQualityHandler(h *Handler, svc *service.QualityService) *QualityHandler {
	return &QualityHandler{
		Handler: h,
		service: svc,
	}
}

func (h *QualityHandler) CreateRule(c *gin.Context) {
	var rule model.QualityRule
	if err := c.ShouldBindJSON(&rule); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid rule data", err.Error())
		return
	}

	created, err := h.service.CreateRule(c.Request.Context(), &rule)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "CREATE_ERROR", "Failed to create rule", err.Error())
		return
	}

	h.CreatedResponse(c, created)
}

func (h *QualityHandler) GetRule(c *gin.Context) {
	ruleID := c.Param("rule_id")

	rule, err := h.service.GetRule(c.Request.Context(), ruleID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Rule not found", err.Error())
		return
	}

	h.SuccessResponse(c, rule)
}

func (h *QualityHandler) ListRules(c *gin.Context) {
	language := c.Query("language")
	severity := c.Query("severity")
	category := c.Query("category")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	rules, total, err := h.service.ListRules(c.Request.Context(), language, severity, category, page, pageSize)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "QUERY_ERROR", "Failed to list rules", err.Error())
		return
	}

	h.PaginatedResponse(c, rules, page, pageSize, total)
}

func (h *QualityHandler) UpdateRule(c *gin.Context) {
	ruleID := c.Param("rule_id")

	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid update data", err.Error())
		return
	}

	if err := h.service.UpdateRule(c.Request.Context(), ruleID, updates); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Rule not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Rule updated successfully"})
}

func (h *QualityHandler) DeleteRule(c *gin.Context) {
	ruleID := c.Param("rule_id")

	if err := h.service.DeleteRule(c.Request.Context(), ruleID); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Rule not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Rule deleted successfully"})
}

func (h *QualityHandler) RunQualityCheck(c *gin.Context) {
	var req model.QualityCheckRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	result, err := h.service.RunQualityCheck(c.Request.Context(), &req)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "CHECK_ERROR", "Quality check failed", err.Error())
		return
	}

	h.SuccessResponse(c, result)
}

func (h *QualityHandler) GetReport(c *gin.Context) {
	reportID := c.Param("report_id")

	report, err := h.service.GetReport(c.Request.Context(), reportID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Report not found", err.Error())
		return
	}

	h.SuccessResponse(c, report)
}

func (h *QualityHandler) ListReports(c *gin.Context) {
	projectID := c.Query("project_id")
	passedStr := c.Query("passed")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	var passed *bool
	if passedStr != "" {
		p := passedStr == "true"
		passed = &p
	}

	reports, total, err := h.service.ListReports(c.Request.Context(), projectID, passed, page, pageSize)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "QUERY_ERROR", "Failed to list reports", err.Error())
		return
	}

	h.PaginatedResponse(c, reports, page, pageSize, total)
}

func (h *QualityHandler) GetGateConfig(c *gin.Context) {
	configID := c.Param("config_id")

	config, err := h.service.GetGateConfig(c.Request.Context(), configID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Gate config not found", err.Error())
		return
	}

	h.SuccessResponse(c, config)
}

func (h *QualityHandler) UpdateGateConfig(c *gin.Context) {
	configID := c.Param("config_id")

	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid update data", err.Error())
		return
	}

	if err := h.service.UpdateGateConfig(c.Request.Context(), configID, updates); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Gate config not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Gate config updated successfully"})
}
