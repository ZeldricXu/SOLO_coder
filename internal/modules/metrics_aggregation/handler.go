package metrics_aggregation

import (
	"net/http"
	"strconv"
	"time"

	"loglevelplatform/internal/common/logger"
	"loglevelplatform/internal/common/models"
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

func (h *Handler) Ingest(c *gin.Context) {
	ctx := c.Request.Context()
	traceID := utils.NewTraceID()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", traceID)))

	var metric models.MetricPoint
	if err := c.ShouldBindJSON(&metric); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	if err := h.service.Ingest(ctx, &metric); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "metric ingested successfully",
	})
}

func (h *Handler) IngestBatch(c *gin.Context) {
	ctx := c.Request.Context()
	traceID := utils.NewTraceID()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", traceID)))

	var metrics []*models.MetricPoint
	if err := c.ShouldBindJSON(&metrics); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	if err := h.service.IngestBatch(ctx, metrics); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "metrics ingested successfully",
		"count":   len(metrics),
	})
}

func (h *Handler) Query(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	metricName := c.Query("name")
	if metricName == "" {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": "metric name is required",
		})
		return
	}

	tags := make(map[string]string)
	for _, key := range c.QueryArray("tag_key") {
		values := c.QueryArray("tag_value")
		if len(values) > 0 {
			tags[key] = values[0]
		}
	}

	var startTime, endTime time.Time
	if startStr := c.Query("start_time"); startStr != "" {
		if t, err := time.Parse(time.RFC3339, startStr); err == nil {
			startTime = t
		}
	}
	if endStr := c.Query("end_time"); endStr != "" {
		if t, err := time.Parse(time.RFC3339, endStr); err == nil {
			endTime = t
		}
	}

	results, err := h.service.Query(ctx, metricName, tags, startTime, endTime)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":  200,
		"data":  results,
		"count": len(results),
	})
}

func (h *Handler) ListMetrics(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	metrics := h.service.GetMetricsList(ctx)
	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": metrics,
	})
}

func (h *Handler) CreateAggregator(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	var req struct {
		Name     string `json:"name" binding:"required"`
		Type     string `json:"type" binding:"required"`
		WindowSec int    `json:"window_sec" binding:"required,min=1"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	h.service.RegisterPreAggregator(req.Name, AggregationType(req.Type), time.Duration(req.WindowSec)*time.Second)

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "pre-aggregator registered",
	})
}

func (h *Handler) SaveSnapshot(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	var req struct {
		Dimensions map[string]string `json:"dimensions"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	snapshot, err := h.service.SaveSnapshot(ctx, req.Dimensions)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": snapshot,
	})
}

func (h *Handler) ListSnapshots(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	var startTime, endTime time.Time
	limit := 100

	if startStr := c.Query("start_time"); startStr != "" {
		if t, err := time.Parse(time.RFC3339, startStr); err == nil {
			startTime = t
		}
	}
	if endStr := c.Query("end_time"); endStr != "" {
		if t, err := time.Parse(time.RFC3339, endStr); err == nil {
			endTime = t
		}
	}
	if limitStr := c.Query("limit"); limitStr != "" {
		if l, err := strconv.Atoi(limitStr); err == nil {
			limit = l
		}
	}

	snapshots, err := h.service.ListSnapshots(ctx, startTime, endTime, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": snapshots,
	})
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	metrics := r.Group("/metrics")
	{
		metrics.POST("/ingest", h.Ingest)
		metrics.POST("/ingest/batch", h.IngestBatch)
		metrics.GET("/query", h.Query)
		metrics.GET("/list", h.ListMetrics)
		metrics.POST("/aggregator", h.CreateAggregator)
		metrics.POST("/snapshot", h.SaveSnapshot)
		metrics.GET("/snapshots", h.ListSnapshots)
	}
}
