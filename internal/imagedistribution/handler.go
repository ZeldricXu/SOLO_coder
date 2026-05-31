package imagedistribution

import (
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/imagecdn/imagecdn/pkg/common"
)

type Handler struct {
	service Service
}

func NewHandler(service Service) *Handler {
	return &Handler{service: service}
}

func (h *Handler) PullImage(c *gin.Context) error {
	var req PullRequest
	if err := common.BindJSON(c, &req); err != nil {
		common.ErrorResponse(c, 400, "Invalid request: "+err.Error())
		return nil
	}

	resp, err := h.service.PullImage(c.Request.Context(), &req)
	if err != nil {
		return err
	}

	common.CreatedResponse(c, resp)
	return nil
}

func (h *Handler) GetPullStatus(c *gin.Context) error {
	manifestID := c.Param("id")
	if manifestID == "" {
		common.ErrorResponse(c, 400, "Manifest ID is required")
		return nil
	}

	resp, err := h.service.GetPullStatus(c.Request.Context(), manifestID)
	if err != nil {
		common.ErrorResponse(c, 404, "Manifest not found")
		return nil
	}

	common.SuccessResponse(c, resp)
	return nil
}

func (h *Handler) ListManifests(c *gin.Context) error {
	registry := c.Query("registry")
	repository := c.Query("repository")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	manifests, total, err := h.service.ListManifests(c.Request.Context(), registry, repository, page, pageSize)
	if err != nil {
		return err
	}

	common.SuccessResponse(c, gin.H{
		"items": manifests,
		"total": total,
		"page":  page,
		"size":  pageSize,
	})
	return nil
}

func (h *Handler) SyncRegistry(c *gin.Context) error {
	var task SyncTask
	if err := common.BindJSON(c, &task); err != nil {
		common.ErrorResponse(c, 400, "Invalid request: "+err.Error())
		return nil
	}

	sync, err := h.service.SyncRegistry(c.Request.Context(), &task)
	if err != nil {
		return err
	}

	common.CreatedResponse(c, sync)
	return nil
}

func (h *Handler) GetSyncStatus(c *gin.Context) error {
	syncID := c.Param("id")
	if syncID == "" {
		common.ErrorResponse(c, 400, "Sync ID is required")
		return nil
	}

	sync, err := h.service.GetSyncStatus(c.Request.Context(), syncID)
	if err != nil {
		common.ErrorResponse(c, 404, "Sync not found")
		return nil
	}

	common.SuccessResponse(c, sync)
	return nil
}

func (h *Handler) RegisterPeer(c *gin.Context) error {
	var peer P2PPeer
	if err := common.BindJSON(c, &peer); err != nil {
		common.ErrorResponse(c, 400, "Invalid request: "+err.Error())
		return nil
	}

	if err := h.service.RegisterPeer(c.Request.Context(), &peer); err != nil {
		return err
	}

	common.CreatedResponse(c, peer)
	return nil
}

func (h *Handler) Heartbeat(c *gin.Context) error {
	nodeID := c.Param("node_id")
	if nodeID == "" {
		common.ErrorResponse(c, 400, "Node ID is required")
		return nil
	}

	if err := h.service.Heartbeat(c.Request.Context(), nodeID); err != nil {
		return err
	}

	common.SuccessResponse(c, gin.H{"status": "ok"})
	return nil
}

func (h *Handler) GetPeers(c *gin.Context) error {
	region := c.Query("region")

	peers, err := h.service.GetPeers(c.Request.Context(), region)
	if err != nil {
		return err
	}

	common.SuccessResponse(c, peers)
	return nil
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	imageGroup := r.Group("/images")
	{
		imageGroup.POST("/pull", common.WrapHandler(h.PullImage))
		imageGroup.GET("/:id/status", common.WrapHandler(h.GetPullStatus))
		imageGroup.GET("", common.WrapHandler(h.ListManifests))
	}

	syncGroup := r.Group("/sync")
	{
		syncGroup.POST("", common.WrapHandler(h.SyncRegistry))
		syncGroup.GET("/:id", common.WrapHandler(h.GetSyncStatus))
	}

	peerGroup := r.Group("/peers")
	{
		peerGroup.POST("", common.WrapHandler(h.RegisterPeer))
		peerGroup.POST("/:node_id/heartbeat", common.WrapHandler(h.Heartbeat))
		peerGroup.GET("", common.WrapHandler(h.GetPeers))
	}
}
