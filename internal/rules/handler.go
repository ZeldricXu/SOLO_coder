package rules

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

type RuleHandler struct {
	engine *Engine
}

func NewRuleHandler(engine *Engine) *RuleHandler {
	return &RuleHandler{engine: engine}
}

type CreateRuleRequest struct {
	Name        string                 `json:"name" binding:"required"`
	Description string                 `json:"description"`
	Enabled     bool                   `json:"enabled"`
	Condition   RuleCondition          `json:"condition" binding:"required"`
	Actions     []RuleAction           `json:"actions" binding:"required,min=1"`
	Strategy    string                 `json:"strategy"`
}

func (h *RuleHandler) CreateRule(c *gin.Context) {
	var req CreateRuleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	rule := &Rule{
		Name:        req.Name,
		Description: req.Description,
		Enabled:     req.Enabled,
		Condition: req.Condition,
		Actions:   req.Actions,
		Strategy:  req.Strategy,
	}
	ruleID := h.engine.AddRule(rule)
	c.JSON(http.StatusCreated, gin.H{
		"id":      ruleID,
		"message": "rule created successfully",
	})
}

func (h *RuleHandler) GetRule(c *gin.Context) {
	id := c.Param("id")
	rule, exists := h.engine.GetRule(id)
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "rule not found"})
		return
	}
	c.JSON(http.StatusOK, rule)
}

func (h *RuleHandler) ListRules(c *gin.Context) {
	rules := h.engine.ListRules()
	c.JSON(http.StatusOK, rules)
}

func (h *RuleHandler) UpdateRule(c *gin.Context) {
	id := c.Param("id")
	var rule Rule
	if err := c.ShouldBindJSON(&rule); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if !h.engine.UpdateRule(id, &rule) {
		c.JSON(http.StatusNotFound, gin.H{"error": "rule not found"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "rule updated successfully"})
}

func (h *RuleHandler) DeleteRule(c *gin.Context) {
	id := c.Param("id")
	if !h.engine.DeleteRule(id) {
		c.JSON(http.StatusNotFound, gin.H{"error": "rule not found"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "rule deleted successfully"})
}

func (h *RuleHandler) ListStrategies(c *gin.Context) {
	strategies := h.engine.ListStrategies()
	c.JSON(http.StatusOK, gin.H{
		"strategies": strategies,
		"default":    h.engine.defaultStrategy,
	})
}

type SetDefaultStrategyRequest struct {
	Strategy string `json:"strategy" binding:"required"`
}

func (h *RuleHandler) SetDefaultStrategy(c *gin.Context) {
	var req SetDefaultStrategyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	h.engine.SetDefaultStrategy(req.Strategy)
	c.JSON(http.StatusOK, gin.H{
		"message": "default strategy updated",
		"strategy": req.Strategy,
	})
}

type TriggerEventRequest struct {
	EventType string                 `json:"event_type" binding:"required"`
	Source    string                 `json:"source"`
	Payload   map[string]interface{} `json:"payload" binding:"required"`
}

func (h *RuleHandler) TriggerEvent(c *gin.Context) {
	var req TriggerEventRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	event := Event{
		EventID:   "evt_" + c.GetString("request_id"),
		EventType: req.EventType,
		Source:    req.Source,
		Payload:   req.Payload,
	}
	h.engine.ProcessEvent(event)
	c.JSON(http.StatusAccepted, gin.H{
		"message": "event accepted for processing",
	})
}
