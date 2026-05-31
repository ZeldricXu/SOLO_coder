package aggregation

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

type AggregationHandler struct {
	aggregator *Aggregator
}

func NewAggregationHandler(aggregator *Aggregator) *AggregationHandler {
	return &AggregationHandler{aggregator: aggregator}
}

type AddRuleRequest struct {
	DeviceID       string                 `json:"device_id"`
	Metric         string                 `json:"metric" binding:"required"`
	AggregationType string                `json:"aggregation_type" binding:"required"`
	WindowSeconds  int                    `json:"window_seconds" binding:"required,min=1"`
	Parameters     map[string]interface{} `json:"parameters"`
	Enabled        bool                   `json:"enabled"`
}

func (h *AggregationHandler) AddRule(c *gin.Context) {
	var req AddRuleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	rule := &AggregationRule{
		DeviceID:        req.DeviceID,
		Metric:          req.Metric,
		AggregationType: AggregationType(req.AggregationType),
		WindowSeconds:   req.WindowSeconds,
		Parameters:      req.Parameters,
		Enabled:         req.Enabled,
	}
	ruleID := h.aggregator.AddRule(rule)
	c.JSON(http.StatusCreated, gin.H{
		"id":      ruleID,
		"message": "aggregation rule added",
	})
}

func (h *AggregationHandler) GetRule(c *gin.Context) {
	id := c.Param("id")
	rule, exists := h.aggregator.GetRule(id)
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "rule not found"})
		return
	}
	c.JSON(http.StatusOK, rule)
}

func (h *AggregationHandler) ListRules(c *gin.Context) {
	rules := h.aggregator.ListRules()
	c.JSON(http.StatusOK, rules)
}

func (h *AggregationHandler) UpdateRule(c *gin.Context) {
	id := c.Param("id")
	var rule AggregationRule
	if err := c.ShouldBindJSON(&rule); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if !h.aggregator.UpdateRule(id, &rule) {
		c.JSON(http.StatusNotFound, gin.H{"error": "rule not found"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "rule updated successfully"})
}

func (h *AggregationHandler) DeleteRule(c *gin.Context) {
	id := c.Param("id")
	if !h.aggregator.DeleteRule(id) {
		c.JSON(http.StatusNotFound, gin.H{"error": "rule not found"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "rule deleted successfully"})
}

type IngestDataRequest struct {
	DeviceID string                 `json:"device_id" binding:"required"`
	Metric   string                 `json:"metric" binding:"required"`
	Value    float64                `json:"value" binding:"required"`
	Tags     map[string]string      `json:"tags"`
	RawData  map[string]interface{} `json:"raw_data"`
}

func (h *AggregationHandler) Ingest(c *gin.Context) {
	var req IngestDataRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	dataPoint := DataPoint{
		DeviceID:  req.DeviceID,
		Metric:    req.Metric,
		Value:     req.Value,
		Tags:      req.Tags,
		RawData:   req.RawData,
	}
	h.aggregator.Ingest(dataPoint)
	c.JSON(http.StatusAccepted, gin.H{"message": "data accepted"})
}

func (h *AggregationHandler) GetResults(c *gin.Context) {
	deviceID := c.Query("device_id")
	metric := c.Query("metric")
	results := h.aggregator.GetResults(deviceID, metric)
	c.JSON(http.StatusOK, results)
}
