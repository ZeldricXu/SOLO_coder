package handler

import (
	"net/http"
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

type DocumentHandler struct {
	db             *gorm.DB
	docSvc         *service.DocumentService
	permissionRepo service.PermissionRepository
}

func NewDocumentHandler(db *gorm.DB, docSvc *service.DocumentService, permRepo service.PermissionRepository) *DocumentHandler {
	return &DocumentHandler{
		db:             db,
		docSvc:         docSvc,
		permissionRepo: permRepo,
	}
}

func (h *DocumentHandler) RegisterRoutes(r *gin.RouterGroup, authMiddleware gin.HandlerFunc, permMiddleware func(model.ResourceType, model.PermissionAction, service.PermissionRepository) gin.HandlerFunc) {
	spaces := r.Group("/spaces")
	spaces.Use(authMiddleware)
	{
		spaces.POST("/:space_id/documents", permMiddleware(model.ResourceTypeSpace, model.ActionCreate, h.permissionRepo), h.CreateDocument)
		spaces.GET("/:space_id/documents", permMiddleware(model.ResourceTypeSpace, model.ActionView, h.permissionRepo), h.ListDocuments)
	}

	docs := r.Group("/documents")
	docs.Use(authMiddleware)
	{
		docs.GET("/:id", permMiddleware(model.ResourceTypeDocument, model.ActionView, h.permissionRepo), h.GetDocument)
		docs.PUT("/:id", permMiddleware(model.ResourceTypeDocument, model.ActionEdit, h.permissionRepo), h.UpdateDocument)
		docs.DELETE("/:id", permMiddleware(model.ResourceTypeDocument, model.ActionDelete, h.permissionRepo), h.DeleteDocument)
		docs.GET("/:id/versions", permMiddleware(model.ResourceTypeDocument, model.ActionView, h.permissionRepo), h.ListVersions)
		docs.GET("/:id/versions/:version", permMiddleware(model.ResourceTypeDocument, model.ActionView, h.permissionRepo), h.GetVersion)
		docs.POST("/:id/rollback", permMiddleware(model.ResourceTypeDocument, model.ActionEdit, h.permissionRepo), h.RollbackVersion)
	}
}

func (h *DocumentHandler) CreateDocument(c *gin.Context) {
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

	spaceIDStr := c.Param("space_id")

	var req service.CreateDocRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "invalid request body")
		return
	}
	req.SpaceID = spaceIDStr

	doc, err := h.docSvc.CreateDocument(c.Request.Context(), userID, req)
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}

	response.Success(c, doc)
}

func (h *DocumentHandler) GetDocument(c *gin.Context) {
	idStr := c.Param("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid document id")
		return
	}

	doc, err := h.docSvc.GetDocument(c.Request.Context(), id)
	if err != nil {
		response.InternalError(c, "failed to get document")
		return
	}
	if doc == nil {
		response.NotFound(c, "document not found")
		return
	}

	response.Success(c, doc)
}

func (h *DocumentHandler) UpdateDocument(c *gin.Context) {
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
	id, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid document id")
		return
	}

	var req service.UpdateDocRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "invalid request body")
		return
	}

	doc, err := h.docSvc.UpdateDocument(c.Request.Context(), userID, id, req)
	if err != nil {
		if err.Error() == "document not found" {
			response.NotFound(c, err.Error())
			return
		}
		response.InternalError(c, err.Error())
		return
	}

	response.Success(c, doc)
}

func (h *DocumentHandler) DeleteDocument(c *gin.Context) {
	idStr := c.Param("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid document id")
		return
	}

	if err := h.docSvc.DeleteDocument(c.Request.Context(), id); err != nil {
		response.InternalError(c, "failed to delete document")
		return
	}

	c.JSON(http.StatusNoContent, nil)
}

func (h *DocumentHandler) GetVersion(c *gin.Context) {
	idStr := c.Param("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid document id")
		return
	}

	versionStr := c.Param("version")
	version, err := strconv.Atoi(versionStr)
	if err != nil {
		response.BadRequest(c, "invalid version number")
		return
	}

	dv, err := h.docSvc.GetDocumentVersion(c.Request.Context(), id, version)
	if err != nil {
		response.InternalError(c, "failed to get document version")
		return
	}
	if dv == nil {
		response.NotFound(c, "version not found")
		return
	}

	response.Success(c, dv)
}

func (h *DocumentHandler) RollbackVersion(c *gin.Context) {
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
	id, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid document id")
		return
	}

	var req struct {
		Version int `json:"version"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "invalid request body")
		return
	}
	if req.Version <= 0 {
		response.BadRequest(c, "version must be positive")
		return
	}

	if err := h.docSvc.RollbackToVersion(c.Request.Context(), userID, id, req.Version); err != nil {
		if err.Error() == "version not found" {
			response.NotFound(c, err.Error())
			return
		}
		response.InternalError(c, err.Error())
		return
	}

	doc, err := h.docSvc.GetDocument(c.Request.Context(), id)
	if err != nil {
		response.InternalError(c, "failed to get updated document")
		return
	}

	response.Success(c, doc)
}

func (h *DocumentHandler) ListVersions(c *gin.Context) {
	idStr := c.Param("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid document id")
		return
	}

	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 {
		pageSize = 20
	}
	if pageSize > 100 {
		pageSize = 100
	}

	var versions []*model.DocumentVersion
	var total int64

	db := h.db.Scopes(database.TenantScope(c.Request.Context())).WithContext(c.Request.Context()).
		Model(&model.DocumentVersion{}).
		Where("doc_id = ?", id.String())

	if err := db.Count(&total).Error; err != nil {
		response.InternalError(c, "failed to count versions")
		return
	}

	offset := (page - 1) * pageSize
	if err := db.Order("version DESC").Offset(offset).Limit(pageSize).Find(&versions).Error; err != nil {
		response.InternalError(c, "failed to list versions")
		return
	}

	response.PageSuccess(c, versions, total, page, pageSize)
}

func (h *DocumentHandler) ListDocuments(c *gin.Context) {
	spaceIDStr := c.Param("space_id")
	spaceID, err := uuid.Parse(spaceIDStr)
	if err != nil {
		response.BadRequest(c, "invalid space id")
		return
	}

	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	query := model.DocumentQuery{
		Keyword:     c.Query("keyword"),
		Category:    c.Query("category"),
		Status:      c.Query("status"),
		CreatedBy:   c.Query("created_by"),
		DirectoryID: c.Query("directory_id"),
		SortBy:      c.Query("sort_by"),
		SortOrder:   c.Query("sort_order"),
	}

	if tags := c.QueryArray("tags"); len(tags) > 0 {
		query.Tags = tags
	}

	if isPublicStr := c.Query("is_public"); isPublicStr != "" {
		isPublic := isPublicStr == "true"
		query.IsPublic = &isPublic
	}

	docs, total, err := h.docSvc.ListDocuments(c.Request.Context(), spaceID, query)
	if err != nil {
		response.InternalError(c, "failed to list documents")
		return
	}

	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 {
		pageSize = 20
	}

	response.PageSuccess(c, docs, total, page, pageSize)
}
