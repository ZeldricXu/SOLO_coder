package offline_cache

import (
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"

	"edgescheduler/pkg/utils"
)

type OfflineCacheHandler struct {
	service OfflineCacheService
}

func NewOfflineCacheHandler(service OfflineCacheService) *OfflineCacheHandler {
	return &OfflineCacheHandler{
		service: service,
	}
}

func (h *OfflineCacheHandler) RegisterRoutes(router *gin.RouterGroup) {
	cache := router.Group("/cache")
	{
		cache.POST("", h.CacheData)
		cache.GET("", h.ListCachedData)
		cache.POST("/sync", h.StartSync)
		cache.GET("/sync/:job_id", h.GetSyncJob)
		cache.GET("/network/status", h.GetNetworkStatus)
		cache.PUT("/network/status", h.SetNetworkStatus)
		cache.GET("/pending/:device_id", h.GetPendingCount)
		cache.DELETE("/synced", h.DeleteSyncedData)
	}
}

func (h *OfflineCacheHandler) CacheData(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var req CacheRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	data, err := h.service.CacheData(ctx, &req)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.CreatedResponse(c, gin.H{
		"cache_key": data.CacheKey,
		"status":    data.Status,
	})
}

func (h *OfflineCacheHandler) ListCachedData(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	deviceID := c.Query("device_id")
	dataType := c.Query("data_type")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "50"))

	if deviceID == "" {
		utils.ValidationErrorResponse(c, "device_id is required")
		return
	}

	data, total, err := h.service.GetCachedData(ctx, deviceID, dataType, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"data":  data,
		"total": total,
	})
}

func (h *OfflineCacheHandler) StartSync(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var body struct {
		DeviceID string `json:"device_id" binding:"required"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	job, err := h.service.StartSync(ctx, body.DeviceID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, SyncResponse{
		JobID:       job.JobID,
		Status:      string(job.Status),
		TotalCount:  job.TotalCount,
		SyncedCount: job.SyncedCount,
		FailedCount: job.FailedCount,
	})
}

func (h *OfflineCacheHandler) GetSyncJob(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	jobID := c.Param("job_id")

	job, err := h.service.GetSyncJob(ctx, jobID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, SyncResponse{
		JobID:       job.JobID,
		Status:      string(job.Status),
		TotalCount:  job.TotalCount,
		SyncedCount: job.SyncedCount,
		FailedCount: job.FailedCount,
	})
}

func (h *OfflineCacheHandler) GetNetworkStatus(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	online := h.service.CheckNetworkStatus(ctx)

	utils.SuccessResponse(c, gin.H{
		"online": online,
	})
}

func (h *OfflineCacheHandler) SetNetworkStatus(c *gin.Context) {
	var body struct {
		Online bool `json:"online" binding:"required"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	h.service.SetNetworkStatus(body.Online)

	utils.SuccessResponse(c, gin.H{
		"online": body.Online,
	})
}

func (h *OfflineCacheHandler) GetPendingCount(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	deviceID := c.Param("device_id")

	count, err := h.service.GetPendingCount(ctx, deviceID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"device_id":     deviceID,
		"pending_count": count,
	})
}

func (h *OfflineCacheHandler) DeleteSyncedData(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	daysStr := c.DefaultQuery("before_days", "7")
	days, _ := strconv.Atoi(daysStr)
	beforeTime := time.Now().UTC().AddDate(0, 0, -days)

	deleted, err := h.service.DeleteSyncedData(ctx, beforeTime)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"deleted_count": deleted,
		"before":        beforeTime,
	})
}
