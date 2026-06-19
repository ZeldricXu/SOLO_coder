package handler

import (
	"strconv"

	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/middleware"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/pkg/response"
	"github.com/enterprise/knowledgebase/internal/service"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type I18nHandler struct {
	db             *gorm.DB
	i18nSvc        *service.I18nService
	permissionRepo service.PermissionRepository
}

func NewI18nHandler(db *gorm.DB, i18nSvc *service.I18nService, permRepo service.PermissionRepository) *I18nHandler {
	return &I18nHandler{
		db:             db,
		i18nSvc:        i18nSvc,
		permissionRepo: permRepo,
	}
}

func (h *I18nHandler) RegisterRoutes(r *gin.RouterGroup, authMiddleware gin.HandlerFunc, permMiddleware func(model.ResourceType, model.PermissionAction, service.PermissionRepository) gin.HandlerFunc) {
	docs := r.Group("/documents")
	docs.Use(authMiddleware)
	{
		docs.POST("/:id/fork-translation", permMiddleware(model.ResourceTypeDocument, model.ActionEdit, h.permissionRepo), h.ForkTranslation)
		docs.GET("/:id/variants", permMiddleware(model.ResourceTypeDocument, model.ActionView, h.permissionRepo), h.GetDocumentVariants)
		docs.POST("/:id/translation-progress", permMiddleware(model.ResourceTypeDocument, model.ActionEdit, h.permissionRepo), h.UpdateTranslationProgress)
		docs.POST("/:id/approve-translation", permMiddleware(model.ResourceTypeDocument, model.ActionReview, h.permissionRepo), h.ApproveTranslation)
		docs.POST("/:id/batch-translate", permMiddleware(model.ResourceTypeDocument, model.ActionView, h.permissionRepo), h.BatchTranslateWithTM)
	}

	tm := r.Group("/translation-memory")
	tm.Use(authMiddleware)
	{
		tm.GET("/suggest", h.GetTranslationSuggestions)
		tm.POST("", h.StoreTranslationMemory)
	}
}

type ForkTranslationRequest struct {
	TargetLang string `json:"target_lang" binding:"required"`
}

func (h *I18nHandler) ForkTranslation(c *gin.Context) {
	userIDStr, exists := c.Get(string(middleware.UserIDKey))
	if !exists {
		response.Unauthorized(c, "user not authenticated")
		return
	}
	userID, err := uuid.Parse(userIDStr.(string))
	if err != nil {
		response.BadRequest(c, "invalid user id")
		return
	}

	idStr := c.Param("id")
	baseDocID, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid document id")
		return
	}

	var req ForkTranslationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "invalid request body")
		return
	}

	doc, i18nDoc, err := h.i18nSvc.ForkTranslation(c.Request.Context(), baseDocID, req.TargetLang, userID)
	if err != nil {
		if err.Error() == "base document not found" || err.Error() == "source document is not a base language version" || err.Error() == "target language is same as base language" {
			response.BadRequest(c, err.Error())
			return
		}
		response.InternalError(c, err.Error())
		return
	}

	response.Success(c, gin.H{
		"document": doc,
		"i18n_doc": i18nDoc,
	})
}

func (h *I18nHandler) GetDocumentVariants(c *gin.Context) {
	idStr := c.Param("id")
	docID, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid document id")
		return
	}

	variants, err := h.i18nSvc.GetDocumentVariants(c.Request.Context(), docID)
	if err != nil {
		if err.Error() == "document not found" {
			response.NotFound(c, err.Error())
			return
		}
		response.InternalError(c, err.Error())
		return
	}

	response.Success(c, variants)
}

type UpdateTranslationProgressRequest struct {
	Progress int `json:"progress" binding:"required,min=0,max=100"`
}

func (h *I18nHandler) UpdateTranslationProgress(c *gin.Context) {
	userIDStr, exists := c.Get(string(middleware.UserIDKey))
	if !exists {
		response.Unauthorized(c, "user not authenticated")
		return
	}
	userID, err := uuid.Parse(userIDStr.(string))
	if err != nil {
		response.BadRequest(c, "invalid user id")
		return
	}

	idStr := c.Param("id")
	docID, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid document id")
		return
	}

	var req UpdateTranslationProgressRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "invalid request body")
		return
	}

	if err := h.i18nSvc.UpdateTranslationProgress(c.Request.Context(), docID, req.Progress, userID); err != nil {
		if err.Error() == "progress must be between 0 and 100" {
			response.BadRequest(c, err.Error())
			return
		}
		if err.Error() == "document not found" {
			response.NotFound(c, err.Error())
			return
		}
		response.InternalError(c, err.Error())
		return
	}

	response.Success(c, gin.H{
		"status":   "ok",
		"progress": req.Progress,
	})
}

func (h *I18nHandler) GetTranslationSuggestions(c *gin.Context) {
	tenantIDStr, ok := database.GetTenantID(c.Request.Context())
	if !ok || tenantIDStr == "" {
		response.BadRequest(c, "tenant context missing")
		return
	}
	tenantID, err := uuid.Parse(tenantIDStr)
	if err != nil {
		response.BadRequest(c, "invalid tenant id")
		return
	}

	sourceLang := c.Query("source_lang")
	targetLang := c.Query("target_lang")
	sourceText := c.Query("source_text")
	thresholdStr := c.DefaultQuery("threshold", "0.7")

	if sourceLang == "" || targetLang == "" || sourceText == "" {
		response.BadRequest(c, "source_lang, target_lang and source_text are required")
		return
	}

	threshold, err := strconv.ParseFloat(thresholdStr, 64)
	if err != nil {
		threshold = 0.7
	}

	suggestions, err := h.i18nSvc.GetTranslationSuggestions(c.Request.Context(), tenantID, sourceLang, targetLang, sourceText, threshold)
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}

	response.Success(c, suggestions)
}

type StoreTranslationMemoryRequest struct {
	SourceLang   string  `json:"source_lang" binding:"required"`
	TargetLang   string  `json:"target_lang" binding:"required"`
	SourceText   string  `json:"source_text" binding:"required"`
	TargetText   string  `json:"target_text" binding:"required"`
	SourceDocID  string  `json:"source_doc_id"`
	Domain       string  `json:"domain"`
	Quality      float64 `json:"quality"`
}

func (h *I18nHandler) StoreTranslationMemory(c *gin.Context) {
	tenantIDStr, ok := database.GetTenantID(c.Request.Context())
	if !ok || tenantIDStr == "" {
		response.BadRequest(c, "tenant context missing")
		return
	}
	tenantID, err := uuid.Parse(tenantIDStr)
	if err != nil {
		response.BadRequest(c, "invalid tenant id")
		return
	}

	var req StoreTranslationMemoryRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "invalid request body")
		return
	}

	var sourceDocID uuid.UUID
	if req.SourceDocID != "" {
		sourceDocID, err = uuid.Parse(req.SourceDocID)
		if err != nil {
			response.BadRequest(c, "invalid source_doc_id")
			return
		}
	}

	quality := req.Quality
	if quality <= 0 {
		quality = 1.0
	}

	tm, err := h.i18nSvc.StoreTranslationMemory(c.Request.Context(), tenantID, req.SourceLang, req.TargetLang, req.SourceText, req.TargetText, sourceDocID, req.Domain, quality)
	if err != nil {
		if err.Error() == "source and target text are required" {
			response.BadRequest(c, err.Error())
			return
		}
		response.InternalError(c, err.Error())
		return
	}

	response.Success(c, tm)
}

func (h *I18nHandler) ApproveTranslation(c *gin.Context) {
	userIDStr, exists := c.Get(string(middleware.UserIDKey))
	if !exists {
		response.Unauthorized(c, "user not authenticated")
		return
	}
	userID, err := uuid.Parse(userIDStr.(string))
	if err != nil {
		response.BadRequest(c, "invalid user id")
		return
	}

	idStr := c.Param("id")
	docID, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid document id")
		return
	}

	if err := h.i18nSvc.ApproveTranslation(c.Request.Context(), docID, userID); err != nil {
		if err.Error() == "document not found" {
			response.NotFound(c, err.Error())
			return
		}
		response.InternalError(c, err.Error())
		return
	}

	response.Success(c, gin.H{
		"status":  "approved",
		"message": "translation approved successfully",
	})
}

type BatchTranslateRequest struct {
	TargetLang string `json:"target_lang" binding:"required"`
}

func (h *I18nHandler) BatchTranslateWithTM(c *gin.Context) {
	idStr := c.Param("id")
	docID, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid document id")
		return
	}

	var req BatchTranslateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "invalid request body")
		return
	}

	results, err := h.i18nSvc.BatchTranslateWithTM(c.Request.Context(), docID, req.TargetLang)
	if err != nil {
		if err.Error() == "document not found" {
			response.NotFound(c, err.Error())
			return
		}
		response.InternalError(c, err.Error())
		return
	}

	response.Success(c, results)
}
