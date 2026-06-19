package handler

import (
	"io"
	"net/http"

	"github.com/enterprise/knowledgebase/internal/middleware"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/pkg/response"
	"github.com/enterprise/knowledgebase/internal/service"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type AttachmentHandler struct {
	db             *gorm.DB
	attSvc         *service.AttachmentService
	permissionRepo service.PermissionRepository
}

func NewAttachmentHandler(db *gorm.DB, attSvc *service.AttachmentService, permRepo service.PermissionRepository) *AttachmentHandler {
	return &AttachmentHandler{
		db:             db,
		attSvc:         attSvc,
		permissionRepo: permRepo,
	}
}

func (h *AttachmentHandler) RegisterRoutes(r *gin.RouterGroup, authMiddleware gin.HandlerFunc, permMiddleware func(model.ResourceType, model.PermissionAction, service.PermissionRepository) gin.HandlerFunc) {
	spaces := r.Group("/spaces")
	spaces.Use(authMiddleware)
	{
		spaces.POST("/:space_id/documents/:doc_id/attachments",
			permMiddleware(model.ResourceTypeDocument, model.ActionEdit, h.permissionRepo),
			h.UploadAttachments)
	}

	atts := r.Group("/attachments")
	atts.Use(authMiddleware)
	{
		atts.GET("/:id", h.GetAttachment)
		atts.GET("/:id/download", h.DownloadAttachment)
		atts.DELETE("/:id", h.DeleteAttachment)
		atts.POST("/:id/reparse", h.ReparseAttachment)
		atts.POST("/reindex-all", h.ReindexAll)
	}
}

func (h *AttachmentHandler) UploadAttachments(c *gin.Context) {
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
	spaceID, err := uuid.Parse(spaceIDStr)
	if err != nil {
		response.BadRequest(c, "invalid space id")
		return
	}

	docIDStr := c.Param("doc_id")
	docID, err := uuid.Parse(docIDStr)
	if err != nil {
		response.BadRequest(c, "invalid document id")
		return
	}

	form, err := c.MultipartForm()
	if err != nil {
		response.BadRequest(c, "invalid multipart form")
		return
	}

	files := form.File["files"]
	if len(files) == 0 {
		files = form.File["file"]
	}
	if len(files) == 0 {
		response.BadRequest(c, "no files uploaded")
		return
	}

	attachments := make([]*model.Attachment, 0, len(files))
	for _, fh := range files {
		f, openErr := fh.Open()
		if openErr != nil {
			continue
		}
		fileData, readErr := io.ReadAll(f)
		f.Close()
		if readErr != nil {
			continue
		}

		att, uploadErr := h.attSvc.UploadAndParse(
			c.Request.Context(),
			userID,
			spaceID,
			docID,
			fh.Filename,
			fileData,
		)
		if uploadErr != nil {
			continue
		}
		attachments = append(attachments, att)
	}

	response.Success(c, gin.H{
		"uploaded": len(attachments),
		"total":    len(files),
		"items":    attachments,
	})
}

func (h *AttachmentHandler) GetAttachment(c *gin.Context) {
	idStr := c.Param("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid attachment id")
		return
	}

	att, err := h.attSvc.GetAttachment(c.Request.Context(), id)
	if err != nil {
		response.InternalError(c, "failed to get attachment")
		return
	}
	if att == nil {
		response.NotFound(c, "attachment not found")
		return
	}

	response.Success(c, att)
}

func (h *AttachmentHandler) DownloadAttachment(c *gin.Context) {
	idStr := c.Param("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid attachment id")
		return
	}

	data, fileName, fileType, err := h.attSvc.DownloadAttachment(c.Request.Context(), id)
	if err != nil {
		if err.Error() == "attachment not found" {
			response.NotFound(c, err.Error())
			return
		}
		response.InternalError(c, "failed to download attachment")
		return
	}

	contentType := "application/octet-stream"
	if fileType != "" {
		contentType = "application/" + fileType
	}

	c.Header("Content-Disposition", "attachment; filename=\""+fileName+"\"")
	c.Header("Content-Type", contentType)
	c.Data(http.StatusOK, contentType, data)
}

func (h *AttachmentHandler) DeleteAttachment(c *gin.Context) {
	idStr := c.Param("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid attachment id")
		return
	}

	if err := h.attSvc.DeleteAttachment(c.Request.Context(), id); err != nil {
		response.InternalError(c, "failed to delete attachment")
		return
	}

	c.JSON(http.StatusNoContent, nil)
}

func (h *AttachmentHandler) ReparseAttachment(c *gin.Context) {
	idStr := c.Param("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid attachment id")
		return
	}

	if err := h.attSvc.ParseAndIndex(c.Request.Context(), id); err != nil {
		response.InternalError(c, err.Error())
		return
	}

	att, getErr := h.attSvc.GetAttachment(c.Request.Context(), id)
	if getErr != nil {
		response.InternalError(c, "failed to get updated attachment")
		return
	}

	response.Success(c, att)
}

func (h *AttachmentHandler) ReindexAll(c *gin.Context) {
	tenantIDStr, exists := c.Get(string(middleware.TenantIDKey))
	if !exists {
		response.Unauthorized(c, "tenant context missing")
		return
	}
	tenantID, err := uuid.Parse(tenantIDStr.(string))
	if err != nil {
		response.BadRequest(c, "invalid tenant id")
		return
	}

	success, failed, err := h.attSvc.ReindexAllAttachments(c.Request.Context(), tenantID)
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}

	response.Success(c, gin.H{
		"success": success,
		"failed":  failed,
		"total":   success + failed,
	})
}
