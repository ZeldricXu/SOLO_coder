package featurestore

import (
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	errors "session133/pkg/errors"
	"session133/pkg/utils"
)

type Handler struct {
	service *FeatureStoreService
}

func NewHandler(service *FeatureStoreService) *Handler {
	return &Handler{service: service}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	fs := r.Group("/featurestore")
	{
		groups := fs.Group("/feature-groups")
		{
			groups.POST("", h.CreateFeatureGroup)
			groups.GET("", h.ListFeatureGroups)
			groups.GET("/:id", h.GetFeatureGroup)
			groups.POST("/:id/values", h.InsertFeatureValues)
		}

		views := fs.Group("/feature-views")
		{
			views.POST("", h.CreateFeatureView)
			views.GET("", h.ListFeatureViews)
			views.GET("/:id", h.GetFeatureView)
		}

		online := fs.Group("/online")
		{
			online.POST("/features", h.GetOnlineFeatures)
		}

		offline := fs.Group("/offline")
		{
			offline.POST("/features", h.GetOfflineFeatures)
		}

		datasets := fs.Group("/datasets")
		{
			datasets.POST("", h.CreateTrainingDataset)
			datasets.GET("", h.ListTrainingDatasets)
			datasets.GET("/:id", h.GetTrainingDataset)
		}

		fs.GET("/statistics", h.GetFeatureStatistics)
	}
}

func (h *Handler) CreateFeatureGroup(c *gin.Context) {
	var req CreateFeatureGroupRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	userID := c.GetString("user_id")
	fg, err := h.service.CreateFeatureGroup(c.Request.Context(), &req, userID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessCreated(c, fg)
}

func (h *Handler) GetFeatureGroup(c *gin.Context) {
	id := c.Param("id")
	fg, err := h.service.GetFeatureGroup(c.Request.Context(), id)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, fg)
}

func (h *Handler) ListFeatureGroups(c *gin.Context) {
	entityType := c.Query("entity_type")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	groups, total, err := h.service.ListFeatureGroups(c.Request.Context(), entityType, page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, groups, total, page, pageSize)
}

func (h *Handler) InsertFeatureValues(c *gin.Context) {
	groupID := c.Param("id")
	var req InsertFeatureValuesRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	if err := h.service.InsertFeatureValues(c.Request.Context(), groupID, &req); err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{"message": "特征值插入成功"})
}

func (h *Handler) CreateFeatureView(c *gin.Context) {
	var req struct {
		Name          string            `json:"name" binding:"required"`
		Description   string            `json:"description"`
		FeatureGroups []string          `json:"feature_groups" binding:"required"`
		Features      []string          `json:"features"`
		Labels        map[string]string `json:"labels"`
		Online        bool              `json:"online"`
		TTL           string            `json:"ttl"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	var ttl time.Duration
	if req.TTL != "" {
		var err error
		ttl, err = time.ParseDuration(req.TTL)
		if err != nil {
			utils.Error(c, errors.InvalidParams("无效的TTL格式"))
			return
		}
	}

	userID := c.GetString("user_id")
	fv, err := h.service.CreateFeatureView(c.Request.Context(), req.Name, req.Description, req.FeatureGroups, req.Features, req.Labels, req.Online, ttl, userID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessCreated(c, fv)
}

func (h *Handler) GetFeatureView(c *gin.Context) {
	id := c.Param("id")
	fv, err := h.service.GetFeatureView(c.Request.Context(), id)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, fv)
}

func (h *Handler) ListFeatureViews(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	views, total, err := h.service.ListFeatureViews(c.Request.Context(), page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, views, total, page, pageSize)
}

func (h *Handler) GetOnlineFeatures(c *gin.Context) {
	var req GetOnlineFeaturesRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	result, err := h.service.GetOnlineFeatures(c.Request.Context(), &req)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, result)
}

func (h *Handler) GetOfflineFeatures(c *gin.Context) {
	var req struct {
		FeatureView string    `json:"feature_view" binding:"required"`
		EntityIDs   []string  `json:"entity_ids" binding:"required"`
		Features    []string  `json:"features"`
		StartTime   time.Time `json:"start_time" binding:"required"`
		EndTime     time.Time `json:"end_time" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	result, err := h.service.GetOfflineFeatures(c.Request.Context(), req.FeatureView, req.EntityIDs, req.StartTime, req.EndTime)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{
		"feature_view": req.FeatureView,
		"start_time":   req.StartTime,
		"end_time":     req.EndTime,
		"data":         result,
	})
}

func (h *Handler) CreateTrainingDataset(c *gin.Context) {
	var req struct {
		Name          string            `json:"name" binding:"required"`
		Description   string            `json:"description"`
		FeatureViewID string            `json:"feature_view_id" binding:"required"`
		StartTime     time.Time         `json:"start_time" binding:"required"`
		EndTime       time.Time         `json:"end_time" binding:"required"`
		EntityIDs     []string          `json:"entity_ids" binding:"required"`
		Labels        map[string]string `json:"labels"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	userID := c.GetString("user_id")
	ds, err := h.service.CreateTrainingDataset(c.Request.Context(), req.Name, req.Description, req.FeatureViewID, req.StartTime, req.EndTime, req.EntityIDs, req.Labels, userID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessCreated(c, ds)
}

func (h *Handler) GetTrainingDataset(c *gin.Context) {
	id := c.Param("id")
	ds, err := h.service.GetTrainingDataset(c.Request.Context(), id)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, ds)
}

func (h *Handler) ListTrainingDatasets(c *gin.Context) {
	featureViewID := c.Query("feature_view_id")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	datasets, total, err := h.service.ListTrainingDatasets(c.Request.Context(), featureViewID, page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, datasets, total, page, pageSize)
}

func (h *Handler) GetFeatureStatistics(c *gin.Context) {
	groupID := c.Query("group_id")
	featureName := c.Query("feature_name")
	startTimeStr := c.Query("start_time")
	endTimeStr := c.Query("end_time")

	if groupID == "" || featureName == "" {
		utils.Error(c, errors.InvalidParams("group_id 和 feature_name 是必填参数"))
		return
	}

	startTime := time.Now().Add(-24 * time.Hour)
	endTime := time.Now()

	if startTimeStr != "" {
		if t, err := time.Parse(time.RFC3339, startTimeStr); err == nil {
			startTime = t
		}
	}

	if endTimeStr != "" {
		if t, err := time.Parse(time.RFC3339, endTimeStr); err == nil {
			endTime = t
		}
	}

	stats, err := h.service.ComputeFeatureStatistics(c.Request.Context(), groupID, featureName, startTime, endTime)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, stats)
}
