package monitoring

import (
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	errors "session133/pkg/errors"
	"session133/pkg/utils"
)

type Handler struct {
	service *MonitoringService
}

func NewHandler(service *MonitoringService) *Handler {
	return &Handler{service: service}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	monitoring := r.Group("/monitoring")
	{
		rules := monitoring.Group("/rules")
		{
			rules.POST("", h.CreateAlertRule)
			rules.GET("", h.ListAlertRules)
			rules.GET("/:id", h.GetAlertRule)
			rules.PUT("/:id", h.UpdateAlertRule)
			rules.DELETE("/:id", h.DeleteAlertRule)
		}

		alerts := monitoring.Group("/alerts")
		{
			alerts.GET("", h.ListAlerts)
			alerts.GET("/:id", h.GetAlert)
		}

		channels := monitoring.Group("/notifications")
		{
			channels.POST("", h.AddNotificationChannel)
			channels.GET("", h.ListNotificationChannels)
			channels.DELETE("/:id", h.DeleteNotificationChannel)
		}

		metrics := monitoring.Group("/metrics")
		{
			metrics.GET("/query", h.QueryMetric)
			metrics.GET("/summary", h.GetMetricsSummary)
			metrics.POST("", h.RecordMetric)
		}
	}
}

func (h *Handler) CreateAlertRule(c *gin.Context) {
	var req CreateAlertRuleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	userID := c.GetString("user_id")
	rule, err := h.service.CreateAlertRule(c.Request.Context(), &req, userID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessCreated(c, rule)
}

func (h *Handler) GetAlertRule(c *gin.Context) {
	ruleID := c.Param("id")
	rule, err := h.service.GetAlertRule(c.Request.Context(), ruleID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, rule)
}

func (h *Handler) ListAlertRules(c *gin.Context) {
	namespace := c.Query("namespace")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	rules, total, err := h.service.ListAlertRules(c.Request.Context(), namespace, page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, rules, total, page, pageSize)
}

func (h *Handler) UpdateAlertRule(c *gin.Context) {
	ruleID := c.Param("id")
	var req UpdateAlertRuleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	rule, err := h.service.UpdateAlertRule(c.Request.Context(), ruleID, &req)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, rule)
}

func (h *Handler) DeleteAlertRule(c *gin.Context) {
	ruleID := c.Param("id")
	if err := h.service.DeleteAlertRule(c.Request.Context(), ruleID); err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{"message": "告警规则删除成功"})
}

func (h *Handler) ListAlerts(c *gin.Context) {
	namespace := c.Query("namespace")
	status := AlertStatus(c.Query("status"))
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	alerts, total, err := h.service.ListAlerts(c.Request.Context(), namespace, status, page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, alerts, total, page, pageSize)
}

func (h *Handler) GetAlert(c *gin.Context) {
	alertID := c.Param("id")
	alert, err := h.service.GetAlert(c.Request.Context(), alertID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, alert)
}

func (h *Handler) AddNotificationChannel(c *gin.Context) {
	var req NotificationConfig
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	userID := c.GetString("user_id")
	config, err := h.service.AddNotificationChannel(c.Request.Context(), &req, userID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessCreated(c, config)
}

func (h *Handler) ListNotificationChannels(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	channels, total, err := h.service.ListNotificationChannels(c.Request.Context(), page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, channels, total, page, pageSize)
}

func (h *Handler) DeleteNotificationChannel(c *gin.Context) {
	channelID := c.Param("id")
	if err := h.service.DeleteNotificationChannel(c.Request.Context(), channelID); err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{"message": "通知渠道删除成功"})
}

func (h *Handler) RecordMetric(c *gin.Context) {
	var req struct {
		MetricName string            `json:"metric_name" binding:"required"`
		Value      float64           `json:"value" binding:"required"`
		Labels     map[string]string `json:"labels"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	h.service.RecordMetric(c.Request.Context(), req.MetricName, req.Value, req.Labels)
	utils.Success(c, gin.H{"message": "指标记录成功"})
}

func (h *Handler) QueryMetric(c *gin.Context) {
	metricName := c.Query("metric_name")
	startTimeStr := c.Query("start_time")
	endTimeStr := c.Query("end_time")

	if metricName == "" {
		utils.Error(c, errors.InvalidParams("metric_name 是必填参数"))
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

	data, err := h.service.QueryMetric(c.Request.Context(), metricName, startTime, endTime)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{
		"metric_name": metricName,
		"start_time":  startTime,
		"end_time":    endTime,
		"data":        data,
	})
}

func (h *Handler) GetMetricsSummary(c *gin.Context) {
	startTimeStr := c.Query("start_time")
	endTimeStr := c.Query("end_time")

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

	summary := h.service.GetMetricsSummary(c.Request.Context(), startTime, endTime)
	utils.Success(c, gin.H{
		"start_time": startTime,
		"end_time":   endTime,
		"summary":    summary,
	})
}
