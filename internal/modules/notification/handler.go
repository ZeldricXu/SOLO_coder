package notification

import (
	"net/http"
	"strconv"

	"notificationplatform/internal/common/errors"
	"notificationplatform/internal/common/logger"
	"notificationplatform/internal/common/models"
	"notificationplatform/pkg/utils"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

type Handler struct {
	service *Service
}

func NewHandler() *Handler {
	return &Handler{
		service: NewService(),
	}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	notif := r.Group("/notifications")
	{
		notif.POST("", h.Send)
		notif.GET("", h.List)
		notif.GET("/:id", h.Get)
		notif.POST("/batch", h.BatchSend)
		notif.GET("/stats", h.GetStats)
	}

	templates := r.Group("/templates")
	{
		templates.POST("", h.CreateTemplate)
		templates.GET("", h.ListTemplates)
		templates.GET("/:id", h.GetTemplate)
		templates.PUT("/:id", h.UpdateTemplate)
		templates.DELETE("/:id", h.DeleteTemplate)
	}

	suppression := r.Group("/suppression-rules")
	{
		suppression.POST("", h.CreateSuppressionRule)
		suppression.GET("", h.ListSuppressionRules)
		suppression.GET("/:id", h.GetSuppressionRule)
		suppression.PUT("/:id", h.UpdateSuppressionRule)
		suppression.DELETE("/:id", h.DeleteSuppressionRule)
	}

	routes := r.Group("/routes")
	{
		routes.POST("", h.CreateRoute)
		routes.GET("", h.ListRoutes)
		routes.GET("/:id", h.GetRoute)
		routes.PUT("/:id", h.UpdateRoute)
		routes.DELETE("/:id", h.DeleteRoute)
		routes.POST("/:id/test", h.TestRoute)
		routes.POST("/reload", h.ReloadRoutes)
		routes.GET("/strategies", h.ListStrategies)
	}

	r.GET("/channels", h.ListChannels)
	r.GET("/priorities", h.ListPriorities)
}

func (h *Handler) Send(c *gin.Context) {
	var req SendRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		logger.FromContext(c.Request.Context()).Warn("invalid send request", zap.Error(err))
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	ctx := c.Request.Context()
	record, err := h.service.Send(ctx, &req)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to send notification",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusCreated, models.APIResponse{
		Code:    http.StatusCreated,
		Message: "Notification queued successfully",
		Data:    record,
	})
}

func (h *Handler) List(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	filters := make(map[string]interface{})
	if status := c.Query("status"); status != "" {
		filters["status"] = status
	}
	if channel := c.Query("channel"); channel != "" {
		filters["channel"] = channel
	}
	if nType := c.Query("type"); nType != "" {
		filters["type"] = nType
	}

	ctx := c.Request.Context()
	records, total, err := h.service.ListNotifications(ctx, page, pageSize, filters)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to list notifications",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data: gin.H{
			"items": records,
			"total": total,
			"page":  page,
			"page_size": pageSize,
		},
	})
}

func (h *Handler) Get(c *gin.Context) {
	id := c.Param("id")

	ctx := c.Request.Context()
	record, err := h.service.GetNotification(ctx, id)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to get notification",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    record,
	})
}

type BatchSendRequest struct {
	Notifications []SendRequest `json:"notifications" binding:"required"`
}

func (h *Handler) BatchSend(c *gin.Context) {
	var req BatchSendRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	ctx := c.Request.Context()
	batchID := utils.NewID("batch")
	results := make([]models.BatchResult, 0, len(req.Notifications))

	for i, n := range req.Notifications {
		notifReq := n
		record, err := h.service.Send(ctx, &notifReq)
		result := models.BatchResult{
			ID:     strconv.Itoa(i),
			Status: "success",
		}
		if err != nil {
			result.Status = "failed"
			result.Error = err.Error()
		} else {
			result.ID = record.ID
		}
		results = append(results, result)
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Batch operation completed",
		Data: models.BatchResponse{
			BatchID: batchID,
			Results: results,
		},
	})
}

func (h *Handler) GetStats(c *gin.Context) {
	ctx := c.Request.Context()
	stats := h.service.GetStats(ctx)

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    stats,
	})
}

func (h *Handler) CreateTemplate(c *gin.Context) {
	var req TemplateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	ctx := c.Request.Context()
	tmpl, err := h.service.CreateTemplate(ctx, &req)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to create template",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusCreated, models.APIResponse{
		Code:    http.StatusCreated,
		Message: "Template created successfully",
		Data:    tmpl,
	})
}

func (h *Handler) ListTemplates(c *gin.Context) {
	ctx := c.Request.Context()
	templates := h.service.ListTemplates(ctx)

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    templates,
	})
}

func (h *Handler) GetTemplate(c *gin.Context) {
	id := c.Param("id")

	ctx := c.Request.Context()
	tmpl, err := h.service.GetTemplate(ctx, id)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to get template",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    tmpl,
	})
}

func (h *Handler) UpdateTemplate(c *gin.Context) {
	id := c.Param("id")

	var req TemplateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	ctx := c.Request.Context()
	tmpl, err := h.service.UpdateTemplate(ctx, id, &req)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to update template",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Template updated successfully",
		Data:    tmpl,
	})
}

func (h *Handler) DeleteTemplate(c *gin.Context) {
	id := c.Param("id")

	ctx := c.Request.Context()
	if err := h.service.DeleteTemplate(ctx, id); err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to delete template",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Template deleted successfully",
	})
}

type SuppressionRuleRequest struct {
	Name             string `json:"name" binding:"required"`
	Type             string `json:"type" binding:"required"`
	NotificationType string `json:"notification_type" binding:"required"`
	Channel          string `json:"channel" binding:"required"`
	DedupKeyPattern  string `json:"dedup_key_pattern"`
	WindowSeconds    int    `json:"window_seconds" binding:"min=1"`
	MaxCount         int    `json:"max_count" binding:"min=1"`
	Enabled          bool   `json:"enabled"`
}

func (h *Handler) CreateSuppressionRule(c *gin.Context) {
	var req SuppressionRuleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	ctx := c.Request.Context()
	mgr := h.service.GetSuppressionManager()

	rule := &models.SuppressionRule{
		Name:             req.Name,
		Type:             req.Type,
		NotificationType: req.NotificationType,
		Channel:          req.Channel,
		DedupKeyPattern:  req.DedupKeyPattern,
		WindowSeconds:    req.WindowSeconds,
		MaxCount:         req.MaxCount,
		Enabled:          req.Enabled,
	}

	if err := mgr.AddRule(ctx, rule); err != nil {
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to create suppression rule",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusCreated, models.APIResponse{
		Code:    http.StatusCreated,
		Message: "Suppression rule created successfully",
		Data:    rule,
	})
}

func (h *Handler) ListSuppressionRules(c *gin.Context) {
	ctx := c.Request.Context()
	mgr := h.service.GetSuppressionManager()
	rules := mgr.GetRules(ctx)

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    rules,
	})
}

func (h *Handler) GetSuppressionRule(c *gin.Context) {
	id := c.Param("id")

	ctx := c.Request.Context()
	mgr := h.service.GetSuppressionManager()
	rule, err := mgr.GetRule(ctx, id)
	if err != nil {
		c.JSON(http.StatusNotFound, models.APIResponse{
			Code:    http.StatusNotFound,
			Message: "Suppression rule not found",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    rule,
	})
}

func (h *Handler) UpdateSuppressionRule(c *gin.Context) {
	id := c.Param("id")

	var req SuppressionRuleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	ctx := c.Request.Context()
	mgr := h.service.GetSuppressionManager()

	rule := &models.SuppressionRule{
		Name:             req.Name,
		Type:             req.Type,
		NotificationType: req.NotificationType,
		Channel:          req.Channel,
		DedupKeyPattern:  req.DedupKeyPattern,
		WindowSeconds:    req.WindowSeconds,
		MaxCount:         req.MaxCount,
		Enabled:          req.Enabled,
	}

	if err := mgr.UpdateRule(ctx, id, rule); err != nil {
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to update suppression rule",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Suppression rule updated successfully",
		Data:    rule,
	})
}

func (h *Handler) DeleteSuppressionRule(c *gin.Context) {
	id := c.Param("id")

	ctx := c.Request.Context()
	mgr := h.service.GetSuppressionManager()
	if err := mgr.DeleteRule(ctx, id); err != nil {
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to delete suppression rule",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Suppression rule deleted successfully",
	})
}

func (h *Handler) ListChannels(c *gin.Context) {
	channels := []map[string]interface{}{
		{"name": models.ChannelEmail, "description": "Email notification channel"},
		{"name": models.ChannelSMS, "description": "SMS notification channel"},
		{"name": models.ChannelWebhook, "description": "Webhook notification channel"},
		{"name": models.ChannelDingtalk, "description": "DingTalk notification channel"},
		{"name": models.ChannelWechat, "description": "WeChat notification channel"},
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    channels,
	})
}

func (h *Handler) ListPriorities(c *gin.Context) {
	priorities := []map[string]interface{}{
		{"level": models.PriorityLow, "name": "LOW", "description": "Low priority, no urgency"},
		{"level": models.PriorityNormal, "name": "NORMAL", "description": "Normal priority"},
		{"level": models.PriorityMedium, "name": "MEDIUM", "description": "Medium priority"},
		{"level": models.PriorityHigh, "name": "HIGH", "description": "High priority, needs attention"},
		{"level": models.PriorityCritical, "name": "CRITICAL", "description": "Critical priority, immediate action required"},
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    priorities,
	})
}

type RouteRequest struct {
	Name             string                  `json:"name" binding:"required"`
	Description      string                  `json:"description"`
	NotificationType string                  `json:"notification_type" binding:"required"`
	Conditions       []models.RoutingCondition `json:"conditions"`
	ConditionLogic   string                  `json:"condition_logic" binding:"oneof=AND OR"`
	Strategy         string                  `json:"strategy" binding:"required"`
	Targets          []models.RouteTarget    `json:"targets" binding:"required,min=1"`
	DefaultChannel   string                  `json:"default_channel" binding:"required"`
	Enabled          bool                    `json:"enabled"`
	Priority         int                     `json:"priority"`
}

func (h *Handler) CreateRoute(c *gin.Context) {
	var req RouteRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	ctx := c.Request.Context()
	mgr := h.service.GetRouterManager()

	route := &models.NotificationRoute{
		Name:             req.Name,
		Description:      req.Description,
		NotificationType: req.NotificationType,
		Conditions:       req.Conditions,
		ConditionLogic:   req.ConditionLogic,
		Strategy:         req.Strategy,
		Targets:          req.Targets,
		DefaultChannel:   req.DefaultChannel,
		Enabled:          req.Enabled,
		Priority:         req.Priority,
	}

	if err := mgr.AddRoute(ctx, route); err != nil {
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to create route",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusCreated, models.APIResponse{
		Code:    http.StatusCreated,
		Message: "Route created successfully",
		Data:    route,
	})
}

func (h *Handler) ListRoutes(c *gin.Context) {
	ctx := c.Request.Context()
	mgr := h.service.GetRouterManager()
	routes := mgr.GetRoutes(ctx)

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    routes,
	})
}

func (h *Handler) GetRoute(c *gin.Context) {
	id := c.Param("id")

	ctx := c.Request.Context()
	mgr := h.service.GetRouterManager()
	route, err := mgr.GetRoute(ctx, id)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusNotFound, models.APIResponse{
			Code:    http.StatusNotFound,
			Message: "Route not found",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    route,
	})
}

func (h *Handler) UpdateRoute(c *gin.Context) {
	id := c.Param("id")

	var req RouteRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	ctx := c.Request.Context()
	mgr := h.service.GetRouterManager()

	route := &models.NotificationRoute{
		Name:             req.Name,
		Description:      req.Description,
		NotificationType: req.NotificationType,
		Conditions:       req.Conditions,
		ConditionLogic:   req.ConditionLogic,
		Strategy:         req.Strategy,
		Targets:          req.Targets,
		DefaultChannel:   req.DefaultChannel,
		Enabled:          req.Enabled,
		Priority:         req.Priority,
	}

	if err := mgr.UpdateRoute(ctx, id, route); err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to update route",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Route updated successfully",
		Data:    route,
	})
}

func (h *Handler) DeleteRoute(c *gin.Context) {
	id := c.Param("id")

	ctx := c.Request.Context()
	mgr := h.service.GetRouterManager()
	if err := mgr.DeleteRoute(ctx, id); err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to delete route",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Route deleted successfully",
	})
}

type TestRouteRequest struct {
	Type       string            `json:"type" binding:"required"`
	Title      string            `json:"title"`
	Content    string            `json:"content"`
	Channel    string            `json:"channel"`
	Recipient  string            `json:"recipient"`
	Priority   int               `json:"priority"`
	Metadata   map[string]string `json:"metadata"`
}

func (h *Handler) TestRoute(c *gin.Context) {
	id := c.Param("id")

	var req TestRouteRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "Invalid request parameters",
			Data:    err.Error(),
		})
		return
	}

	ctx := c.Request.Context()
	mgr := h.service.GetRouterManager()

	notification := &models.NotificationRecord{
		Type:      req.Type,
		Title:     req.Title,
		Content:   req.Content,
		Channel:   req.Channel,
		Recipient: req.Recipient,
		Priority:  req.Priority,
		Metadata:  req.Metadata,
	}

	result, err := mgr.TestRoute(ctx, id, notification)
	if err != nil {
		if appErr, ok := err.(*errors.AppError); ok {
			c.JSON(appErr.HTTPStatus(), models.APIResponse{
				Code:    int(appErr.Code),
				Message: appErr.Message,
				Data:    appErr.Detail,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, models.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "Failed to test route",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Route test completed",
		Data:    result,
	})
}

func (h *Handler) ReloadRoutes(c *gin.Context) {
	mgr := h.service.GetRouterManager()
	mgr.ReloadRoutes()

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Routes reloaded successfully",
	})
}

func (h *Handler) ListStrategies(c *gin.Context) {
	strategies := []map[string]interface{}{
		{"name": models.StrategySingle, "description": "Single channel, select highest priority target"},
		{"name": models.StrategyMultiAll, "description": "Multi-channel, send to all enabled targets"},
		{"name": models.StrategyMultiAny, "description": "Multi-channel, randomly select one target"},
		{"name": models.StrategyFailover, "description": "Failover, try targets in priority order until success"},
		{"name": models.StrategyLoadBalance, "description": "Load balance, round-robin across targets"},
		{"name": models.StrategyWeighted, "description": "Weighted random selection based on target weights"},
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Success",
		Data:    strategies,
	})
}
