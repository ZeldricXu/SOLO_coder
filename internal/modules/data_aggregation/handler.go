package data_aggregation

import (
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"

	"edgescheduler/pkg/utils"
)

type AggregationHandler struct {
	service AggregationService
}

func NewAggregationHandler(service AggregationService) *AggregationHandler {
	return &AggregationHandler{
		service: service,
	}
}

func (h *AggregationHandler) RegisterRoutes(router *gin.RouterGroup) {
	agg := router.Group("/aggregation")
	{
		rules := agg.Group("/rules")
		{
			rules.POST("", h.CreateRule)
			rules.GET("", h.ListRules)
			rules.GET("/:rule_id", h.GetRule)
			rules.PUT("/:rule_id", h.UpdateRule)
			rules.DELETE("/:rule_id", h.DeleteRule)
			rules.GET("/:rule_id/results", h.GetResults)
		}

		agg.POST("/ingest", h.IngestData)
		agg.POST("/upload", h.UploadResults)
	}
}

func (h *AggregationHandler) CreateRule(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var rule AggregationRule
	if err := c.ShouldBindJSON(&rule); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	result, err := h.service.CreateRule(ctx, &rule)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.CreatedResponse(c, result)
}

func (h *AggregationHandler) GetRule(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	ruleID := c.Param("rule_id")

	rule, err := h.service.GetRule(ctx, ruleID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, rule)
}

func (h *AggregationHandler) ListRules(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	deviceID := c.Query("device_id")
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	rules, total, err := h.service.ListRules(ctx, deviceID, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"rules": rules,
		"total": total,
		"offset": offset,
		"limit":  limit,
	})
}

func (h *AggregationHandler) UpdateRule(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	ruleID := c.Param("rule_id")

	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	err := h.service.UpdateRule(ctx, ruleID, updates)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"rule_id": ruleID,
		"updated": true,
	})
}

func (h *AggregationHandler) DeleteRule(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	ruleID := c.Param("rule_id")

	err := h.service.DeleteRule(ctx, ruleID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"rule_id": ruleID,
		"deleted": true,
	})
}

func (h *AggregationHandler) GetResults(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	ruleID := c.Param("rule_id")

	var startTime, endTime time.Time
	startTimeStr := c.Query("start_time")
	endTimeStr := c.Query("end_time")
	if startTimeStr != "" {
		startTime, _ = utils.ParseTime(startTimeStr)
	}
	if endTimeStr != "" {
		endTime, _ = utils.ParseTime(endTimeStr)
	}

	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "100"))

	results, total, err := h.service.GetAggregationResults(ctx, ruleID, startTime, endTime, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"results": results,
		"total":   total,
		"offset":  offset,
		"limit":   limit,
	})
}

func (h *AggregationHandler) IngestData(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var data RawDataPoint
	if err := c.ShouldBindJSON(&data); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	if data.Timestamp.IsZero() {
		data.Timestamp = time.Now().UTC()
	}

	err := h.service.IngestData(ctx, &data)
	if err != nil {
		utils.ErrorResponse(c, http.StatusServiceUnavailable, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"received": true,
	})
}

func (h *AggregationHandler) UploadResults(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var body struct {
		DeviceID string `json:"device_id" binding:"required"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	count, err := h.service.UploadResults(ctx, body.DeviceID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"device_id":       body.DeviceID,
		"uploaded_count":  count,
	})
}
