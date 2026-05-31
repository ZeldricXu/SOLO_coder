package notification

import (
	"net/http"
	"strconv"
	"time"

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

func (h *Handler) Send(c *gin.Context) {
	ctx := c.Request.Context()
	traceID := utils.NewTraceID()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", traceID)))

	var req SendRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}
	req.TraceID = traceID

	record, err := h.service.Send(ctx, &req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusAccepted, gin.H{
		"code": 202,
		"data": record,
	})
}

func (h *Handler) GetNotification(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	id := c.Param("id")
	record, err := h.service.GetNotification(ctx, id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{
			"code":  404,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": record,
	})
}

func (h *Handler) ListNotifications(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	status := c.Query("status")
	channel := c.Query("channel")
	traceID := c.Query("trace_id")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	records, total, err := h.service.ListNotifications(ctx, status, channel, traceID, limit, offset)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"items":  records,
			"total":  total,
			"limit":  limit,
			"offset": offset,
		},
	})
}

func (h *Handler) GetDeliveryStatus(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	id := c.Param("id")
	status, err := h.service.GetDeliveryStatus(ctx, id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{
			"code":  404,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": status,
	})
}

func (h *Handler) RetryNotification(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	id := c.Param("id")
	record, err := h.service.RetryNotification(ctx, id)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": record,
	})
}

func (h *Handler) GetStatistics(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

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

	stats, err := h.service.GetStatistics(ctx, startTime, endTime)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": stats,
	})
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	notification := r.Group("/notification")
	{
		notification.POST("/send", h.Send)
		notification.GET("", h.ListNotifications)
		notification.GET("/stats", h.GetStatistics)
		notification.GET("/:id", h.GetNotification)
		notification.GET("/:id/status", h.GetDeliveryStatus)
		notification.POST("/:id/retry", h.RetryNotification)
	}
}
