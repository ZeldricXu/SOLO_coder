package log_pipeline

import (
	"net/http"

	"loglevelplatform/internal/common/logger"
	"loglevelplatform/pkg/utils"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"
)

type Handler struct {
	service *Service
}

func NewHandler(service *Service) *Handler {
	return &Handler{service: service}
}

func (h *Handler) Collect(c *gin.Context) {
	ctx := c.Request.Context()
	traceID := utils.NewTraceID()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", traceID)))

	var entry LogEntry
	if err := c.ShouldBindJSON(&entry); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	if entry.TraceID == "" {
		entry.TraceID = traceID
	}

	if err := h.service.Collect(ctx, &entry); err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{
			"code":  503,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusAccepted, gin.H{
		"code":    202,
		"message": "log accepted for processing",
		"id":      entry.ID,
	})
}

func (h *Handler) CollectRaw(c *gin.Context) {
	ctx := c.Request.Context()
	traceID := utils.NewTraceID()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", traceID)))

	var req struct {
		Raw     string `json:"raw" binding:"required"`
		Service string `json:"service"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	if err := h.service.CollectRaw(ctx, req.Raw, req.Service); err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{
			"code":  503,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusAccepted, gin.H{
		"code":    202,
		"message": "raw log accepted for processing",
	})
}

func (h *Handler) CollectBatch(c *gin.Context) {
	ctx := c.Request.Context()
	traceID := utils.NewTraceID()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", traceID)))

	var entries []*LogEntry
	if err := c.ShouldBindJSON(&entries); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	for _, entry := range entries {
		if entry.TraceID == "" {
			entry.TraceID = traceID
		}
	}

	if err := h.service.CollectBatch(ctx, entries); err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{
			"code":  503,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusAccepted, gin.H{
		"code":    202,
		"message": "batch logs accepted for processing",
		"count":   len(entries),
	})
}

func (h *Handler) AddFilter(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	var rule FilterRule
	if err := c.ShouldBindJSON(&rule); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	h.service.AddFilter(rule)

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "filter added",
	})
}

func (h *Handler) AddRouter(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	var rule RouterRule
	if err := c.ShouldBindJSON(&rule); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	h.service.AddRouter(rule)

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "router added",
	})
}

func (h *Handler) GetStats(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	stats := h.service.GetStats(ctx)

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": stats,
	})
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	pipeline := r.Group("/log-pipeline")
	{
		pipeline.POST("/collect", h.Collect)
		pipeline.POST("/collect/raw", h.CollectRaw)
		pipeline.POST("/collect/batch", h.CollectBatch)
		pipeline.POST("/filters", h.AddFilter)
		pipeline.POST("/routers", h.AddRouter)
		pipeline.GET("/stats", h.GetStats)
	}
}
