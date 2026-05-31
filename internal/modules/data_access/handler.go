package data_access

import (
	"net/http"
	"strconv"
	"time"

	"loglevelplatform/internal/common/logger"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

type Handler struct {
	service *Service
}

func NewHandler(service *Service) *Handler {
	return &Handler{
		service: service,
	}
}

type SetCacheRequest struct {
	Key   string      `json:"key" binding:"required"`
	Value interface{} `json:"value" binding:"required"`
	TTL   int         `json:"ttl_seconds"`
}

type GetCacheResponse struct {
	Key   string      `json:"key"`
	Value interface{} `json:"value"`
	Found bool        `json:"found"`
	TTL   int         `json:"ttl_seconds,omitempty"`
}

type InvalidateRequest struct {
	Pattern string   `json:"pattern"`
	Tags    []string `json:"tags"`
}

func (h *Handler) Get(c *gin.Context) {
	ctx := logger.WithContext(c.Request.Context(), logger.GetComponentLogger("data_access"))
	log := logger.FromContext(ctx)

	key := c.Param("key")
	if key == "" {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "key is required"})
		return
	}

	value, found, err := h.service.Get(ctx, key)
	if err != nil {
		log.Error("failed to get cache", zap.String("key", key), zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": GetCacheResponse{
			Key:   key,
			Value: value,
			Found: found,
		},
	})
}

func (h *Handler) Set(c *gin.Context) {
	ctx := logger.WithContext(c.Request.Context(), logger.GetComponentLogger("data_access"))
	log := logger.FromContext(ctx)

	var req SetCacheRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}

	var ttl time.Duration
	if req.TTL > 0 {
		ttl = time.Duration(req.TTL) * time.Second
	}

	if err := h.service.Set(ctx, req.Key, req.Value, ttl); err != nil {
		log.Error("failed to set cache", zap.String("key", req.Key), zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
		return
	}

	log.Info("cache set", zap.String("key", req.Key), zap.Int("ttl", req.TTL))
	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{"key": req.Key, "status": "set"},
	})
}

func (h *Handler) Delete(c *gin.Context) {
	ctx := logger.WithContext(c.Request.Context(), logger.GetComponentLogger("data_access"))
	log := logger.FromContext(ctx)

	key := c.Param("key")
	if key == "" {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "key is required"})
		return
	}

	if err := h.service.Delete(ctx, key); err != nil {
		log.Error("failed to delete cache", zap.String("key", key), zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
		return
	}

	log.Info("cache deleted", zap.String("key", key))
	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{"key": key, "status": "deleted"},
	})
}

func (h *Handler) Invalidate(c *gin.Context) {
	ctx := logger.WithContext(c.Request.Context(), logger.GetComponentLogger("data_access"))
	log := logger.FromContext(ctx)

	var req InvalidateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}

	count := 0
	var err error

	if req.Pattern != "" {
		count, err = h.service.InvalidateByPattern(ctx, req.Pattern)
		if err != nil {
			log.Error("failed to invalidate by pattern", zap.String("pattern", req.Pattern), zap.Error(err))
			c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
			return
		}
	}

	if len(req.Tags) > 0 {
		tagCount, _ := h.service.InvalidateByTag(ctx, req.Tags)
		count += tagCount
	}

	log.Info("cache invalidation completed", zap.Int("count", count))
	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{"invalidated_count": count},
	})
}

func (h *Handler) GetStats(c *gin.Context) {
	ctx := logger.WithContext(c.Request.Context(), logger.GetComponentLogger("data_access"))

	stats := h.service.GetStats(ctx)

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": stats,
	})
}

func (h *Handler) ResetStats(c *gin.Context) {
	ctx := logger.WithContext(c.Request.Context(), logger.GetComponentLogger("data_access"))

	h.service.ResetStats(ctx)

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{"status": "reset"},
	})
}

func (h *Handler) ListEntries(c *gin.Context) {
	ctx := logger.WithContext(c.Request.Context(), logger.GetComponentLogger("data_access"))
	log := logger.FromContext(ctx)

	prefix := c.Query("prefix")
	limitStr := c.Query("limit")
	limit := 100
	if limitStr != "" {
		if l, err := strconv.Atoi(limitStr); err == nil {
			limit = l
		}
	}

	entries, err := h.service.GetEntries(ctx, prefix, limit)
	if err != nil {
		log.Error("failed to list cache entries", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"entries": entries,
			"count":   len(entries),
		},
	})
}

func (h *Handler) CleanupExpired(c *gin.Context) {
	ctx := logger.WithContext(c.Request.Context(), logger.GetComponentLogger("data_access"))
	log := logger.FromContext(ctx)

	count, err := h.service.CleanupExpired(ctx)
	if err != nil {
		log.Error("failed to cleanup expired entries", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{"cleaned_count": count},
	})
}

func (h *Handler) Warmup(c *gin.Context) {
	ctx := logger.WithContext(c.Request.Context(), logger.GetComponentLogger("data_access"))
	log := logger.FromContext(ctx)

	var req struct {
		Keys []string `json:"keys" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}

	loaded, err := h.service.Warmup(ctx, req.Keys)
	if err != nil {
		log.Error("failed to warmup cache", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"loaded_count":    loaded,
			"requested_count": len(req.Keys),
		},
	})
}

func (h *Handler) BatchGet(c *gin.Context) {
	ctx := logger.WithContext(c.Request.Context(), logger.GetComponentLogger("data_access"))
	log := logger.FromContext(ctx)

	var req struct {
		Keys []string `json:"keys" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}

	result, err := h.service.BatchGet(ctx, req.Keys)
	if err != nil {
		log.Error("failed to batch get cache", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"items": result,
			"count": len(result),
		},
	})
}

func (h *Handler) BatchSet(c *gin.Context) {
	ctx := logger.WithContext(c.Request.Context(), logger.GetComponentLogger("data_access"))
	log := logger.FromContext(ctx)

	var req struct {
		Items map[string]interface{} `json:"items" binding:"required"`
		TTL   int                    `json:"ttl_seconds"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}

	ttl := time.Duration(req.TTL) * time.Second
	if err := h.service.BatchSet(ctx, req.Items, ttl); err != nil {
		log.Error("failed to batch set cache", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"status": "set",
			"count":  len(req.Items),
		},
	})
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	cache := r.Group("/cache")
	{
		cache.GET("/:key", h.Get)
		cache.POST("", h.Set)
		cache.DELETE("/:key", h.Delete)
		cache.POST("/invalidate", h.Invalidate)
		cache.GET("/stats", h.GetStats)
		cache.POST("/stats/reset", h.ResetStats)
		cache.GET("", h.ListEntries)
		cache.POST("/cleanup", h.CleanupExpired)
		cache.POST("/warmup", h.Warmup)
		cache.POST("/batch/get", h.BatchGet)
		cache.POST("/batch/set", h.BatchSet)
	}
}
