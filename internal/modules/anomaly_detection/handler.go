package anomaly_detection

import (
	"net/http"
	"strconv"
	"time"

	"loglevelplatform/internal/common/logger"

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

func (h *Handler) RegisterConfig(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	var config DetectionConfig
	if err := c.ShouldBindJSON(&config); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	h.service.RegisterConfig(&config)

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "detection config registered",
	})
}

func (h *Handler) GetConfig(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	metricName := c.Param("metric")
	config, exists := h.service.GetConfig(metricName)
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{
			"code":  404,
			"error": "config not found",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": config,
	})
}

func (h *Handler) RecordDataPoint(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	var req struct {
		MetricName string            `json:"metric_name" binding:"required"`
		Value      float64           `json:"value" binding:"required"`
		Tags       map[string]string `json:"tags"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	h.service.RecordDataPoint(ctx, req.MetricName, req.Value, req.Tags)

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "data point recorded",
	})
}

func (h *Handler) GetBaseline(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	metricName := c.Param("metric")
	baseline, err := h.service.GetBaseline(ctx, metricName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": baseline,
	})
}

func (h *Handler) ListBaselines(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	baselines := h.service.ListBaselines(ctx)

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": baselines,
	})
}

func (h *Handler) GetDetectionResults(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	metricName := c.Query("metric")
	severity := c.Query("severity")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "100"))

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

	results, err := h.service.GetDetectionResults(ctx, metricName, severity, startTime, endTime, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": results,
	})
}

func (h *Handler) TestDetection(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	metricName := c.Param("metric")
	valueStr := c.Query("value")
	value, err := strconv.ParseFloat(valueStr, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": "invalid value",
		})
		return
	}

	result, err := h.service.TestDetection(ctx, metricName, value)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": result,
	})
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	anomaly := r.Group("/anomaly-detection")
	{
		anomaly.POST("/config", h.RegisterConfig)
		anomaly.GET("/config/:metric", h.GetConfig)
		anomaly.POST("/datapoint", h.RecordDataPoint)
		anomaly.GET("/baselines", h.ListBaselines)
		anomaly.GET("/baselines/:metric", h.GetBaseline)
		anomaly.GET("/results", h.GetDetectionResults)
		anomaly.GET("/test/:metric", h.TestDetection)
	}
}
