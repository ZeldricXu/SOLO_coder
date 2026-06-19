package handler

import (
	"net/http"
	"strconv"

	"github.com/enterprise/knowledgebase/internal/middleware"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/pkg/response"
	"github.com/enterprise/knowledgebase/internal/service"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type SnapshotHandler struct {
	snapshotSvc  *service.SnapshotService
	scheduler    *service.SnapshotScheduler
	permissionRepo service.PermissionRepository
}

func NewSnapshotHandler(snapshotSvc *service.SnapshotService, scheduler *service.SnapshotScheduler, permRepo service.PermissionRepository) *SnapshotHandler {
	return &SnapshotHandler{
		snapshotSvc:    snapshotSvc,
		scheduler:      scheduler,
		permissionRepo: permRepo,
	}
}

func (h *SnapshotHandler) RegisterRoutes(r *gin.RouterGroup, authMiddleware gin.HandlerFunc, permMiddleware func(model.ResourceType, model.PermissionAction, service.PermissionRepository) gin.HandlerFunc) {
	spaces := r.Group("/spaces")
	spaces.Use(authMiddleware)
	{
		spaces.GET("/:space_id/snapshot-policies", permMiddleware(model.ResourceTypeSpace, model.ActionView, h.permissionRepo), h.ListSnapshotPolicies)
		spaces.POST("/:space_id/snapshot-policies", permMiddleware(model.ResourceTypeSpace, model.ActionEdit, h.permissionRepo), h.CreateSnapshotPolicy)

		spaces.POST("/:space_id/snapshots", permMiddleware(model.ResourceTypeSpace, model.ActionEdit, h.permissionRepo), h.CreateManualSnapshot)
		spaces.GET("/:space_id/snapshots", permMiddleware(model.ResourceTypeSpace, model.ActionView, h.permissionRepo), h.ListSnapshots)
	}

	policies := r.Group("/snapshot-policies")
	policies.Use(authMiddleware)
	{
		policies.PUT("/:id", permMiddleware(model.ResourceTypeSpace, model.ActionEdit, h.permissionRepo), h.UpdateSnapshotPolicy)
		policies.DELETE("/:id", permMiddleware(model.ResourceTypeSpace, model.ActionDelete, h.permissionRepo), h.DeleteSnapshotPolicy)
		policies.POST("/:id/trigger", permMiddleware(model.ResourceTypeSpace, model.ActionEdit, h.permissionRepo), h.TriggerSnapshotPolicy)
	}

	snaps := r.Group("/snapshots")
	snaps.Use(authMiddleware)
	{
		snaps.GET("/:id", permMiddleware(model.ResourceTypeSpace, model.ActionView, h.permissionRepo), h.GetSnapshot)
		snaps.GET("/:id/download", permMiddleware(model.ResourceTypeSpace, model.ActionView, h.permissionRepo), h.DownloadSnapshot)
		snaps.GET("/:id/download-url", permMiddleware(model.ResourceTypeSpace, model.ActionView, h.permissionRepo), h.GetSnapshotDownloadURL)
		snaps.DELETE("/:id", permMiddleware(model.ResourceTypeSpace, model.ActionDelete, h.permissionRepo), h.DeleteSnapshot)
	}
}

func (h *SnapshotHandler) getUserID(c *gin.Context) (uuid.UUID, bool) {
	userIDStr, exists := c.Get(string(middleware.UserIDKey))
	if !exists {
		response.Unauthorized(c, "user not authenticated")
		return uuid.Nil, false
	}
	userID, err := uuid.Parse(userIDStr.(string))
	if err != nil {
		response.BadRequest(c, "invalid user id")
		return uuid.Nil, false
	}
	return userID, true
}

func (h *SnapshotHandler) parseUUIDParam(c *gin.Context, param string) (uuid.UUID, bool) {
	idStr := c.Param(param)
	id, err := uuid.Parse(idStr)
	if err != nil {
		response.BadRequest(c, "invalid "+param)
		return uuid.Nil, false
	}
	return id, true
}

func (h *SnapshotHandler) CreateSnapshotPolicy(c *gin.Context) {
	userID, ok := h.getUserID(c)
	if !ok {
		return
	}

	spaceID, ok := h.parseUUIDParam(c, "space_id")
	if !ok {
		return
	}

	var req service.CreateSnapshotPolicyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "invalid request body")
		return
	}

	policy, err := h.snapshotSvc.CreateSnapshotPolicy(c.Request.Context(), userID, spaceID, req)
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}

	response.Success(c, policy)
}

func (h *SnapshotHandler) UpdateSnapshotPolicy(c *gin.Context) {
	_, ok := h.getUserID(c)
	if !ok {
		return
	}

	policyID, ok := h.parseUUIDParam(c, "id")
	if !ok {
		return
	}

	var req service.UpdateSnapshotPolicyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "invalid request body")
		return
	}

	policy, err := h.snapshotSvc.UpdateSnapshotPolicy(c.Request.Context(), policyID, req)
	if err != nil {
		if err.Error() == "snapshot policy not found" {
			response.NotFound(c, err.Error())
			return
		}
		response.InternalError(c, err.Error())
		return
	}

	response.Success(c, policy)
}

func (h *SnapshotHandler) ListSnapshotPolicies(c *gin.Context) {
	spaceID, ok := h.parseUUIDParam(c, "space_id")
	if !ok {
		return
	}

	policies, err := h.snapshotSvc.ListSnapshotPolicies(c.Request.Context(), spaceID)
	if err != nil {
		response.InternalError(c, "failed to list snapshot policies")
		return
	}

	response.Success(c, policies)
}

func (h *SnapshotHandler) DeleteSnapshotPolicy(c *gin.Context) {
	policyID, ok := h.parseUUIDParam(c, "id")
	if !ok {
		return
	}

	if err := h.snapshotSvc.DeleteSnapshotPolicy(c.Request.Context(), policyID); err != nil {
		if err.Error() == "snapshot policy not found" {
			response.NotFound(c, err.Error())
			return
		}
		response.InternalError(c, err.Error())
		return
	}

	c.JSON(http.StatusNoContent, nil)
}

func (h *SnapshotHandler) TriggerSnapshotPolicy(c *gin.Context) {
	policyID, ok := h.parseUUIDParam(c, "id")
	if !ok {
		return
	}

	if h.scheduler != nil {
		h.scheduler.EnqueuePolicy(policyID)
	}

	response.SuccessWithMessage(c, nil, "snapshot policy triggered")
}

func (h *SnapshotHandler) CreateManualSnapshot(c *gin.Context) {
	userID, ok := h.getUserID(c)
	if !ok {
		return
	}

	spaceID, ok := h.parseUUIDParam(c, "space_id")
	if !ok {
		return
	}

	var req struct {
		Name               string `json:"name"`
		Description        string `json:"description"`
		IncludeAttachments bool   `json:"include_attachments"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "invalid request body")
		return
	}

	snapshot, err := h.snapshotSvc.CreateManualSnapshot(c.Request.Context(), userID, spaceID, req.Name, req.Description, req.IncludeAttachments)
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}

	response.Success(c, snapshot)
}

func (h *SnapshotHandler) ListSnapshots(c *gin.Context) {
	spaceID, ok := h.parseUUIDParam(c, "space_id")
	if !ok {
		return
	}

	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	snapshots, total, err := h.snapshotSvc.ListSnapshots(c.Request.Context(), spaceID, page, pageSize)
	if err != nil {
		response.InternalError(c, "failed to list snapshots")
		return
	}

	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 {
		pageSize = 20
	}

	response.PageSuccess(c, snapshots, total, page, pageSize)
}

func (h *SnapshotHandler) GetSnapshot(c *gin.Context) {
	snapshotID, ok := h.parseUUIDParam(c, "id")
	if !ok {
		return
	}

	snapshot, err := h.snapshotSvc.GetSnapshot(c.Request.Context(), snapshotID)
	if err != nil {
		response.InternalError(c, "failed to get snapshot")
		return
	}
	if snapshot == nil {
		response.NotFound(c, "snapshot not found")
		return
	}

	response.Success(c, snapshot)
}

func (h *SnapshotHandler) GetSnapshotDownloadURL(c *gin.Context) {
	snapshotID, ok := h.parseUUIDParam(c, "id")
	if !ok {
		return
	}

	expireMinutes, _ := strconv.Atoi(c.DefaultQuery("expire_minutes", "60"))

	url, err := h.snapshotSvc.GetSnapshotDownloadURL(c.Request.Context(), snapshotID, expireMinutes)
	if err != nil {
		if err.Error() == "snapshot not found" {
			response.NotFound(c, err.Error())
			return
		}
		response.InternalError(c, err.Error())
		return
	}

	response.Success(c, gin.H{"download_url": url})
}

func (h *SnapshotHandler) DownloadSnapshot(c *gin.Context) {
	snapshotID, ok := h.parseUUIDParam(c, "id")
	if !ok {
		return
	}

	snapshot, err := h.snapshotSvc.GetSnapshot(c.Request.Context(), snapshotID)
	if err != nil {
		response.InternalError(c, "failed to get snapshot")
		return
	}
	if snapshot == nil {
		response.NotFound(c, "snapshot not found")
		return
	}

	fileName := snapshot.Name + ".zip"
	c.Header("Content-Type", "application/zip")
	c.Header("Content-Disposition", "attachment; filename=\""+fileName+"\"")
	c.Header("Content-Length", strconv.FormatInt(snapshot.ArchiveSize, 10))

	if err := h.snapshotSvc.DownloadSnapshotZIP(c.Request.Context(), snapshotID, c.Writer); err != nil {
		return
	}
}

func (h *SnapshotHandler) DeleteSnapshot(c *gin.Context) {
	snapshotID, ok := h.parseUUIDParam(c, "id")
	if !ok {
		return
	}

	if err := h.snapshotSvc.DeleteSnapshot(c.Request.Context(), snapshotID); err != nil {
		if err.Error() == "snapshot not found" {
			response.NotFound(c, err.Error())
			return
		}
		response.InternalError(c, err.Error())
		return
	}

	c.JSON(http.StatusNoContent, nil)
}
