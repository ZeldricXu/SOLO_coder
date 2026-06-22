package api

import (
	"context"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/featureflag/platform/internal/dao"
	"github.com/featureflag/platform/internal/middleware"
	"github.com/featureflag/platform/internal/model"
	"github.com/featureflag/platform/internal/service"
	"github.com/featureflag/platform/pkg/logger"
)

type SwitchHandler struct {
	switchService    *service.SwitchService
	approvalService  *service.ApprovalService
	scheduleService  *service.ScheduleService
	statsService     *service.StatsService
}

func NewSwitchHandler() *SwitchHandler {
	return &SwitchHandler{
		switchService:   service.NewSwitchService(),
		approvalService: service.NewApprovalService(),
		scheduleService: service.NewScheduleService(),
		statsService:    service.NewStatsService(),
	}
}

func (h *SwitchHandler) Init(kafkaProducer *service.KafkaProducer) {
	h.switchService.SetKafkaProducer(kafkaProducer)
	h.approvalService.SetSwitchService(h.switchService)
	h.approvalService.SetKafkaProducer(kafkaProducer)
	h.scheduleService.SetSwitchService(h.switchService)
}

func (h *SwitchHandler) RegisterRoutes(r *gin.RouterGroup) {
	switches := r.Group("/switches")
	{
		switches.GET("", h.List)
		switches.POST("", h.Create)
		switches.GET("/:id", h.GetByID)
		switches.PUT("/:id", h.Update)
		switches.DELETE("/:id", h.Delete)
		switches.POST("/:id/enable", h.Enable)
		switches.POST("/:id/disable", h.Disable)
		switches.GET("/key/:key", h.GetByKey)
		switches.POST("/batch/enable", h.BatchEnable)
		switches.POST("/batch/disable", h.BatchDisable)
		switches.POST("/batch/service/enable", h.BatchEnableByService)
		switches.POST("/batch/service/disable", h.BatchDisableByService)
		switches.POST("/evaluate", h.Evaluate)
		switches.POST("/evaluate/batch", h.BatchEvaluate)
		switches.GET("/:id/history", h.GetHistory)
		switches.GET("/:id/stats", h.GetStats)
		switches.GET("/:id/stats/summary", h.GetStatsSummary)
		switches.GET("/:id/integrations", h.GetIntegrations)
		switches.POST("/:id/strategies", h.SaveStrategies)
		switches.POST("/:id/schedule", h.CreateSchedule)
		switches.GET("/:id/schedule", h.ListSchedules)
		switches.POST("/stats/report", h.ReportStats)
	}

	sdk := r.Group("/sdk")
	{
		sdk.GET("/config", h.GetSDKConfig)
		sdk.POST("/evaluate", h.Evaluate)
		sdk.POST("/stats/report", h.ReportStats)
	}

	approvals := r.Group("/approvals")
	{
		approvals.GET("", h.ListApprovals)
		approvals.POST("", h.CreateApproval)
		approvals.GET("/:id", h.GetApproval)
		approvals.POST("/:id/approve", h.Approve)
		approvals.POST("/:id/reject", h.Reject)
	}

	services := r.Group("/services")
	{
		services.GET("", h.ListServices)
	}
}

func (h *SwitchHandler) List(c *gin.Context) {
	var req model.ListRequest
	if err := c.ShouldBindQuery(&req); err != nil {
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	resp, err := h.switchService.List(c.Request.Context(), &req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(resp))
}

func (h *SwitchHandler) Create(c *gin.Context) {
	var req model.CreateSwitchRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	operator, _ := middleware.CurrentUser(c)
	sw, err := h.switchService.Create(c.Request.Context(), &req, operator)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	if len(req.Strategies) > 0 {
		err = h.switchService.SaveStrategies(c.Request.Context(), sw.ID, req.Strategies, operator)
		if err != nil {
			logger.Warnf("save strategies error: %v", err)
		}
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(sw))
}

func (h *SwitchHandler) GetByID(c *gin.Context) {
	id := c.Param("id")
	sw, err := h.switchService.GetByID(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}
	if sw == nil {
		c.JSON(http.StatusNotFound, model.NewErrorResponse(404, "Switch not found"))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(sw))
}

func (h *SwitchHandler) GetByKey(c *gin.Context) {
	key := c.Param("key")
	sw, err := h.switchService.GetByKey(c.Request.Context(), key)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}
	if sw == nil {
		c.JSON(http.StatusNotFound, model.NewErrorResponse(404, "Switch not found"))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(sw))
}

func (h *SwitchHandler) Update(c *gin.Context) {
	id := c.Param("id")
	var req model.UpdateSwitchRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	operator, _ := middleware.CurrentUser(c)
	sw, err := h.switchService.Update(c.Request.Context(), id, &req, operator)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(sw))
}

func (h *SwitchHandler) Delete(c *gin.Context) {
	id := c.Param("id")
	operator, _ := middleware.CurrentUser(c)

	err := h.switchService.Delete(c.Request.Context(), id, operator)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(nil))
}

func (h *SwitchHandler) Enable(c *gin.Context) {
	id := c.Param("id")
	operator, _ := middleware.CurrentUser(c)

	sw, err := h.switchService.Enable(c.Request.Context(), id, operator)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(sw))
}

func (h *SwitchHandler) Disable(c *gin.Context) {
	id := c.Param("id")
	operator, _ := middleware.CurrentUser(c)

	sw, err := h.switchService.Disable(c.Request.Context(), id, operator)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(sw))
}

func (h *SwitchHandler) BatchEnable(c *gin.Context) {
	var req model.BatchOperationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	operator, _ := middleware.CurrentUser(c)
	count, err := h.switchService.BatchEnable(c.Request.Context(), req.IDs, operator)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(gin.H{"updated_count": count}))
}

func (h *SwitchHandler) BatchDisable(c *gin.Context) {
	var req model.BatchOperationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	operator, _ := middleware.CurrentUser(c)
	count, err := h.switchService.BatchDisable(c.Request.Context(), req.IDs, operator)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(gin.H{"updated_count": count}))
}

func (h *SwitchHandler) BatchEnableByService(c *gin.Context) {
	var req model.BatchServiceOperationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	operator, _ := middleware.CurrentUser(c)
	count, err := h.switchService.BatchEnableByService(c.Request.Context(), req.ServiceID, operator)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(gin.H{"updated_count": count}))
}

func (h *SwitchHandler) BatchDisableByService(c *gin.Context) {
	var req model.BatchServiceOperationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	operator, _ := middleware.CurrentUser(c)
	count, err := h.switchService.BatchDisableByService(c.Request.Context(), req.ServiceID, operator)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(gin.H{"updated_count": count}))
}

func (h *SwitchHandler) Evaluate(c *gin.Context) {
	var req model.EvaluateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	evalCtx := &model.EvaluationContext{
		UserID:      req.UserID,
		Department:  req.Department,
		Tags:        req.Tags,
		Environment: req.Environment,
		TenantID:    req.TenantID,
		Attributes:  req.Attributes,
	}

	result, err := h.switchService.Evaluate(c.Request.Context(), req.Key, evalCtx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(result))
}

func (h *SwitchHandler) BatchEvaluate(c *gin.Context) {
	var req model.BatchEvaluateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	evalCtx := &model.EvaluationContext{
		UserID:      req.UserID,
		Department:  req.Department,
		Tags:        req.Tags,
		Environment: req.Environment,
		TenantID:    req.TenantID,
		Attributes:  req.Attributes,
	}

	results, err := h.switchService.BatchEvaluate(c.Request.Context(), evalCtx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	if len(req.Keys) > 0 {
		filtered := make(map[string]*model.EvaluationResult)
		for _, key := range req.Keys {
			if r, ok := results[key]; ok {
				filtered[key] = r
			}
		}
		results = filtered
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(results))
}

func (h *SwitchHandler) GetSDKConfig(c *gin.Context) {
	version, _ := strconv.ParseInt(c.Query("version"), 10, 64)

	config, err := h.switchService.GetSDKConfig(context.Background(), version)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(config))
}

func (h *SwitchHandler) GetHistory(c *gin.Context) {
	id := c.Param("id")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	resp, err := h.switchService.GetHistory(c.Request.Context(), id, page, pageSize)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(resp))
}

func (h *SwitchHandler) SaveStrategies(c *gin.Context) {
	id := c.Param("id")
	var strategies []*model.Strategy
	if err := c.ShouldBindJSON(&strategies); err != nil {
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	operator, _ := middleware.CurrentUser(c)
	err := h.switchService.SaveStrategies(c.Request.Context(), id, strategies, operator)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(nil))
}

func (h *SwitchHandler) CreateSchedule(c *gin.Context) {
	id := c.Param("id")
	var req model.ScheduleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}
	req.SwitchID = id

	operator, _ := middleware.CurrentUser(c)
	task, err := h.scheduleService.CreateTask(c.Request.Context(), &req, operator)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(task))
}

func (h *SwitchHandler) ListSchedules(c *gin.Context) {
	id := c.Param("id")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	resp, err := h.scheduleService.ListTasks(c.Request.Context(), id, page, pageSize)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(resp))
}

func (h *SwitchHandler) GetStats(c *gin.Context) {
	id := c.Param("id")
	startDate := c.DefaultQuery("start_date", "")
	endDate := c.DefaultQuery("end_date", "")

	stats, err := h.statsService.GetSwitchStats(c.Request.Context(), id, startDate, endDate)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(stats))
}

func (h *SwitchHandler) GetStatsSummary(c *gin.Context) {
	id := c.Param("id")
	summary, err := h.statsService.GetStatsSummary(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(summary))
}

func (h *SwitchHandler) GetIntegrations(c *gin.Context) {
	id := c.Param("id")
	integrations, err := h.statsService.GetIntegrations(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(integrations))
}

func (h *SwitchHandler) ReportStats(c *gin.Context) {
	var req model.StatsReportRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	err := h.statsService.ReportStats(c.Request.Context(), &req)
	if err != nil {
		logger.Warnf("report stats error: %v", err)
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(nil))
}

func (h *SwitchHandler) ListApprovals(c *gin.Context) {
	status := c.Query("status")
	requester := c.Query("requester")
	approver := c.Query("approver")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	resp, err := h.approvalService.List(c.Request.Context(), status, requester, approver, page, pageSize)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(resp))
}

func (h *SwitchHandler) CreateApproval(c *gin.Context) {
	var req model.ApprovalRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	requester, _ := middleware.CurrentUser(c)
	approval, err := h.approvalService.CreateRequest(c.Request.Context(), &req, requester)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(approval))
}

func (h *SwitchHandler) GetApproval(c *gin.Context) {
	id := c.Param("id")
	approval, err := h.approvalService.GetByID(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}
	if approval == nil {
		c.JSON(http.StatusNotFound, model.NewErrorResponse(404, "Approval not found"))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(approval))
}

func (h *SwitchHandler) Approve(c *gin.Context) {
	id := c.Param("id")
	operator, _ := middleware.CurrentUser(c)

	approval, err := h.approvalService.Approve(c.Request.Context(), id, operator)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(approval))
}

func (h *SwitchHandler) Reject(c *gin.Context) {
	id := c.Param("id")
	var req model.ApprovalProcessRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	operator, _ := middleware.CurrentUser(c)
	approval, err := h.approvalService.Reject(c.Request.Context(), id, req.RejectReason, operator)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(approval))
}

func (h *SwitchHandler) ListServices(c *gin.Context) {
	serviceDAO := dao.NewServiceDAO()
	services, err := serviceDAO.List(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(services))
}
