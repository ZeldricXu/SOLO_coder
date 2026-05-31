package monitoring

import (
	"net/http"
	"strconv"
	"time"

	"loglevelplatform/internal/common/logger"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.uber.org/zap"
)

type Handler struct {
	service *Service
}

func NewHandler(service *Service) *Handler {
	return &Handler{service: service}
}

func (h *Handler) GetMetrics(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	metrics := h.service.GetMetrics(ctx)
	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": metrics,
	})
}

func (h *Handler) PrometheusMetrics(c *gin.Context) {
	handler := promhttp.HandlerFor(h.service.GetRegistry(), promhttp.HandlerOpts{})
	handler.ServeHTTP(c.Writer, c.Request)
}

func (h *Handler) TakeSnapshot(c *gin.Context) {
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

	snapshot := h.service.TakeSnapshot(ctx, req.Dimensions)
	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": snapshot,
	})
}

func (h *Handler) GetSnapshots(c *gin.Context) {
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

	snapshots := h.service.GetSnapshots(ctx, startTime, endTime, limit)
	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": snapshots,
	})
}

func (h *Handler) RecordMetric(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	var req struct {
		Type   string            `json:"type" binding:"required"`
		Name   string            `json:"name" binding:"required"`
		Value  float64           `json:"value"`
		Labels map[string]string `json:"labels"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	switch req.Type {
	case "counter":
		h.service.IncrementCounter(ctx, req.Name, req.Labels)
	case "gauge":
		h.service.SetGauge(ctx, req.Name, req.Value, req.Labels)
	case "histogram":
		h.service.ObserveHistogram(ctx, req.Name, req.Value, req.Labels)
	case "summary":
		h.service.ObserveSummary(ctx, req.Name, req.Value, req.Labels)
	default:
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": "invalid metric type",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "metric recorded",
	})
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	monitoring := r.Group("/monitoring")
	{
		monitoring.GET("", h.GetMetrics)
		monitoring.GET("/prometheus", h.PrometheusMetrics)
		monitoring.POST("/snapshot", h.TakeSnapshot)
		monitoring.GET("/snapshots", h.GetSnapshots)
		monitoring.POST("/metric", h.RecordMetric)
	}
}
