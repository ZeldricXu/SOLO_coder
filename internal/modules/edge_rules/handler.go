package edge_rules

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"edgescheduler/pkg/utils"
)

type EdgeRulesHandler struct {
	engine EdgeRulesEngine
}

func NewEdgeRulesHandler(engine EdgeRulesEngine) *EdgeRulesHandler {
	return &EdgeRulesHandler{
		engine: engine,
	}
}

func (h *EdgeRulesHandler) RegisterRoutes(router *gin.RouterGroup) {
	rules := router.Group("/rules")
	{
		rules.POST("", h.CreateRule)
		rules.GET("", h.ListRules)
		rules.GET("/:rule_id", h.GetRule)
		rules.PUT("/:rule_id", h.UpdateRule)
		rules.DELETE("/:rule_id", h.DeleteRule)
		rules.POST("/:rule_id/enable", h.EnableRule)
		rules.POST("/:rule_id/disable", h.DisableRule)
		rules.POST("/:rule_id/execute", h.ExecuteRule)
		rules.POST("/evaluate", h.EvaluateRules)
		rules.GET("/:rule_id/logs", h.GetExecutionLogs)
	}
}

func (h *EdgeRulesHandler) CreateRule(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var req RuleCreateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	rule, err := h.engine.CreateRule(ctx, &req)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.CreatedResponse(c, rule)
}

func (h *EdgeRulesHandler) GetRule(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	ruleID := c.Param("rule_id")

	rule, err := h.engine.GetRule(ctx, ruleID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, rule)
}

func (h *EdgeRulesHandler) ListRules(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	ruleType := RuleType(c.Query("type"))
	status := RuleStatus(c.Query("status"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	rules, total, err := h.engine.ListRules(ctx, ruleType, status, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"rules":  rules,
		"total":  total,
		"offset": offset,
		"limit":  limit,
	})
}

func (h *EdgeRulesHandler) UpdateRule(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	ruleID := c.Param("rule_id")

	var req RuleUpdateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	rule, err := h.engine.UpdateRule(ctx, ruleID, &req)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, rule)
}

func (h *EdgeRulesHandler) DeleteRule(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	ruleID := c.Param("rule_id")

	err := h.engine.DeleteRule(ctx, ruleID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"rule_id": ruleID,
		"deleted": true,
	})
}

func (h *EdgeRulesHandler) EnableRule(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	ruleID := c.Param("rule_id")

	rule, err := h.engine.EnableRule(ctx, ruleID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, rule)
}

func (h *EdgeRulesHandler) DisableRule(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	ruleID := c.Param("rule_id")

	rule, err := h.engine.DisableRule(ctx, ruleID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, rule)
}

func (h *EdgeRulesHandler) ExecuteRule(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	ruleID := c.Param("rule_id")

	var req RuleExecutionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		req.TriggerData = make(map[string]interface{})
	}

	log, err := h.engine.ExecuteRule(ctx, ruleID, &req)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, log)
}

func (h *EdgeRulesHandler) EvaluateRules(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var body struct {
		Event string                 `json:"event" binding:"required"`
		Data  map[string]interface{} `json:"data"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	matchedRules := h.engine.EvaluateRules(ctx, body.Event, body.Data)

	utils.SuccessResponse(c, gin.H{
		"matched_rules": matchedRules,
		"count":         len(matchedRules),
	})
}

func (h *EdgeRulesHandler) GetExecutionLogs(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	ruleID := c.Param("rule_id")

	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "50"))

	logs, total, err := h.engine.GetExecutionLogs(ctx, ruleID, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"logs":   logs,
		"total":  total,
		"offset": offset,
		"limit":  limit,
	})
}
