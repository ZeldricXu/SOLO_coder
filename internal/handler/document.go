package handler

import (
	"net/http"
	"strconv"

	"projectservice/internal/model"
	"projectservice/internal/service"

	"github.com/gin-gonic/gin"
)

type DocumentHandler struct {
	*Handler
	service *service.DocumentService
}

func NewDocumentHandler(h *Handler, svc *service.DocumentService) *DocumentHandler {
	return &DocumentHandler{
		Handler: h,
		service: svc,
	}
}

func (h *DocumentHandler) IndexDocument(c *gin.Context) {
	var req model.IndexDocumentRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	doc, err := h.service.IndexDocument(c.Request.Context(), &req)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "INDEX_ERROR", "Document indexing failed", err.Error())
		return
	}

	h.CreatedResponse(c, doc)
}

func (h *DocumentHandler) GetDocument(c *gin.Context) {
	docID := c.Param("doc_id")

	doc, err := h.service.GetDocument(c.Request.Context(), docID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Document not found", err.Error())
		return
	}

	h.SuccessResponse(c, doc)
}

func (h *DocumentHandler) SearchDocuments(c *gin.Context) {
	query := c.Query("q")
	source := c.Query("source")
	category := c.Query("category")
	tags := c.QueryArray("tags")
	userID := c.Query("user_id")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	req := &model.SearchDocumentRequest{
		Query:    query,
		Source:   source,
		Category: category,
		Tags:     tags,
		UserID:   userID,
		Page:     page,
		PageSize: pageSize,
	}

	result, err := h.service.SearchDocuments(c.Request.Context(), req)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "SEARCH_ERROR", "Search failed", err.Error())
		return
	}

	h.SuccessResponse(c, result)
}

func (h *DocumentHandler) SyncDocuments(c *gin.Context) {
	var req model.SyncDocumentsRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	results, err := h.service.SyncDocuments(c.Request.Context(), &req)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "SYNC_ERROR", "Document sync failed", err.Error())
		return
	}

	h.SuccessResponse(c, results)
}

func (h *DocumentHandler) UpdateDocument(c *gin.Context) {
	docID := c.Param("doc_id")

	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid update data", err.Error())
		return
	}

	if err := h.service.UpdateDocument(c.Request.Context(), docID, updates); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Document not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Document updated successfully"})
}

func (h *DocumentHandler) DeleteDocument(c *gin.Context) {
	docID := c.Param("doc_id")

	if err := h.service.DeleteDocument(c.Request.Context(), docID); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Document not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Document deleted successfully"})
}

func (h *DocumentHandler) ListDocuments(c *gin.Context) {
	source := c.Query("source")
	category := c.Query("category")
	owner := c.Query("owner")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	docs, total, err := h.service.ListDocuments(c.Request.Context(), source, category, owner, page, pageSize)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "QUERY_ERROR", "Failed to list documents", err.Error())
		return
	}

	h.PaginatedResponse(c, docs, page, pageSize, total)
}
