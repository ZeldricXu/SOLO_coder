package api

import (
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"session187/internal/billing"
	billingConfig "session187/internal/billing/config"
	billingSvc "session187/internal/billing/service"
	"session187/internal/common"
	"session187/internal/doccompare"
	"session187/internal/logger"
	"session187/internal/monitor"
	schedulerSvc "session187/internal/scheduler/service"
	"session187/internal/skillgraph"
	storageSvc "session187/internal/storage/service"
	"session187/internal/tenant"
	"session187/internal/ticket"
	"session187/pkg/errors"
	"session187/pkg/middleware"
)

type Handler struct {
	tenantMgr     *tenant.Manager
	storageSvc    storageSvc.StorageService
	usageSvc      billingSvc.UsageService
	billingSvc    billingSvc.BillingService
	schedulerSvc  schedulerSvc.SchedulerService
	loggerMgr     *logger.Manager
	docComparer   *doccompare.Comparer
	skillMgr      *skillgraph.Manager
	ticketAlloc   *ticket.Allocator
	monitor       *monitor.Monitor
}

func NewHandler(
	tenantMgr *tenant.Manager,
	storageSvc storageSvc.StorageService,
	usageSvc billingSvc.UsageService,
	billingSvc billingSvc.BillingService,
	schedulerSvc schedulerSvc.SchedulerService,
	loggerMgr *logger.Manager,
	docComparer *doccompare.Comparer,
	skillMgr *skillgraph.Manager,
	ticketAlloc *ticket.Allocator,
	monitor *monitor.Monitor,
) *Handler {
	return &Handler{
		tenantMgr:     tenantMgr,
		storageSvc:    storageSvc,
		usageSvc:      usageSvc,
		billingSvc:    billingSvc,
		schedulerSvc:  schedulerSvc,
		loggerMgr:     loggerMgr,
		docComparer:   docComparer,
		skillMgr:      skillMgr,
		ticketAlloc:   ticketAlloc,
		monitor:       monitor,
	}
}

func SetupRoutes(r *gin.Engine, h *Handler) {
	r.Use(middleware.CORSMiddleware())
	api := r.Group("/api/v1")
	api.Use(middleware.TenantMiddleware())
	api.Use(middleware.TraceIDMiddleware())

	tenantGroup := api.Group("/tenants")
	{
		tenantGroup.POST("", h.CreateTenant)
		tenantGroup.GET("/:id", h.GetTenant)
		tenantGroup.PUT("/:id", h.UpdateTenant)
		tenantGroup.DELETE("/:id", h.DeleteTenant)
		tenantGroup.GET("/:id/usage", h.GetTenantUsage)
	}

	storageGroup := api.Group("/storage")
	{
		storageGroup.POST("/buckets", h.CreateBucket)
		storageGroup.GET("/buckets", h.ListBuckets)
		storageGroup.POST("/objects", h.PutObject)
		storageGroup.GET("/objects/:bucket/:key", h.GetObject)
		storageGroup.DELETE("/objects/:bucket/:key", h.DeleteObject)
		storageGroup.GET("/objects/:bucket", h.ListObjects)
		storageGroup.POST("/objects/search", h.SearchObjects)
		storageGroup.GET("/policies", h.ListStoragePolicies)
		storageGroup.POST("/policies/:name/enable", h.EnableStoragePolicy)
		storageGroup.POST("/policies/:name/disable", h.DisableStoragePolicy)
	}

	billingGroup := api.Group("/billing")
	{
		billingGroup.POST("/usage", h.RecordUsage)
		billingGroup.GET("/usage", h.GetUsage)
		billingGroup.POST("/invoices/generate", h.GenerateInvoice)
		billingGroup.GET("/invoices", h.ListInvoices)
		billingGroup.GET("/invoices/:id", h.GetInvoice)
		billingGroup.POST("/invoices/:id/pay", h.PayInvoice)
		billingGroup.GET("/configs", h.ListBillingConfigs)
		billingGroup.POST("/configs", h.AddBillingConfig)
		billingGroup.PUT("/configs/:id", h.UpdateBillingConfig)
		billingGroup.DELETE("/configs/:id", h.DeleteBillingConfig)
		billingGroup.GET("/configs/:id/history", h.GetBillingConfigHistory)
	}

	schedulerGroup := api.Group("/scheduler")
	{
		schedulerGroup.POST("/tasks", h.CreateTask)
		schedulerGroup.GET("/tasks", h.ListTasks)
		schedulerGroup.GET("/tasks/:id", h.GetTask)
		schedulerGroup.POST("/tasks/:id/start", h.StartTask)
		schedulerGroup.POST("/tasks/:id/stop", h.StopTask)
		schedulerGroup.DELETE("/tasks/:id", h.DeleteTask)
		schedulerGroup.GET("/tasks/:id/executions", h.GetTaskExecutions)
		schedulerGroup.POST("/tasks/:id/execute", h.ExecuteTaskAsync)
		schedulerGroup.POST("/execute/:handler", h.ExecuteHandlerAsync)
		schedulerGroup.GET("/executions/:id", h.GetExecutionResult)
		schedulerGroup.POST("/executions/:id/wait", h.WaitForExecutionResult)
	}

	loggerGroup := api.Group("/logger")
	{
		loggerGroup.POST("/level", h.SetLogLevel)
		loggerGroup.GET("/configs", h.GetLogConfigs)
		loggerGroup.GET("/logs", h.QueryLogs)
	}

	docGroup := api.Group("/documents")
	{
		docGroup.POST("", h.SaveDocument)
		docGroup.GET("/:id", h.GetDocument)
		docGroup.POST("/compare", h.CompareDocuments)
		docGroup.GET("/compare/:id", h.GetCompareResult)
		docGroup.GET("/compare", h.ListCompareResults)
	}

	skillGroup := api.Group("/skills")
	{
		skillGroup.POST("", h.CreateSkill)
		skillGroup.GET("", h.ListSkills)
		skillGroup.GET("/tree", h.GetSkillTree)
		skillGroup.POST("/employees", h.AddEmployeeSkill)
		skillGroup.GET("/employees/:employeeId", h.GetEmployeeSkills)
		skillGroup.GET("/employees/:employeeId/gap", h.GetSkillGap)
		skillGroup.GET("/employees/:employeeId/recommend", h.RecommendLearningPath)
		skillGroup.POST("/learning-paths", h.CreateLearningPath)
		skillGroup.GET("/learning-paths", h.GetLearningPaths)
	}

	ticketGroup := api.Group("/tickets")
	{
		ticketGroup.POST("", h.CreateTicket)
		ticketGroup.GET("", h.ListTickets)
		ticketGroup.GET("/:id", h.GetTicket)
		ticketGroup.POST("/:id/assign", h.AssignTicket)
		ticketGroup.PUT("/:id/status", h.UpdateTicketStatus)
		ticketGroup.POST("/agents", h.CreateAgent)
		ticketGroup.GET("/agents", h.ListAgents)
		ticketGroup.GET("/agents/:id/load", h.GetAgentLoad)
	}

	monitorGroup := api.Group("/monitor")
	{
		monitorGroup.POST("/metrics", h.RegisterMetric)
		monitorGroup.GET("/metrics", h.ListMetrics)
		monitorGroup.POST("/metrics/query", h.QueryMetrics)
		monitorGroup.GET("/prometheus", gin.WrapH(promhttp.Handler()))
	}

	resources := api.Group("/resources")
	{
		resources.POST("", h.CreateResource)
		resources.GET("/:id/status", h.GetResourceStatus)
		resources.POST("/batch", h.BatchOperation)
	}
}

func (h *Handler) CreateTenant(c *gin.Context) {
	var req struct {
		Name   string                 `json:"name" binding:"required"`
		Plan   string                 `json:"plan" binding:"required"`
		Config map[string]interface{} `json:"config"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	tenant, err := h.tenantMgr.CreateTenant(req.Name, req.Plan, req.Config)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusCreated, common.BaseResponse{Code: 201, Data: tenant})
}

func (h *Handler) GetTenant(c *gin.Context) {
	tenantID := c.Param("id")
	tenant, err := h.tenantMgr.GetTenant(tenantID)
	if err != nil {
		c.JSON(http.StatusNotFound, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: tenant})
}

func (h *Handler) UpdateTenant(c *gin.Context) {
	tenantID := c.Param("id")
	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	tenant, err := h.tenantMgr.UpdateTenant(tenantID, updates)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: tenant})
}

func (h *Handler) DeleteTenant(c *gin.Context) {
	tenantID := c.Param("id")
	if err := h.tenantMgr.DeleteTenant(tenantID); err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Message: "删除成功"})
}

func (h *Handler) GetTenantUsage(c *gin.Context) {
	tenantID := c.Param("id")
	usage, err := h.tenantMgr.GetCurrentUsage(tenantID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: usage})
}

func (h *Handler) CreateBucket(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		Name   string `json:"name" binding:"required"`
		Region string `json:"region"`
		ACL    string `json:"acl"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	bucket, err := h.storageSvc.CreateBucket(tenantID, req.Name, req.Region, req.ACL)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusCreated, common.BaseResponse{Code: 201, Data: bucket})
}

func (h *Handler) ListBuckets(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	buckets, err := h.storageSvc.ListBuckets(tenantID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: buckets})
}

func (h *Handler) PutObject(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		Bucket      string            `json:"bucket" binding:"required"`
		Key         string            `json:"key" binding:"required"`
		FileName    string            `json:"file_name"`
		ContentType string            `json:"content_type"`
		Data        string            `json:"data" binding:"required"`
		Tags        map[string]string `json:"tags"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	meta, err := h.storageSvc.PutObject(tenantID, req.Bucket, req.Key, req.FileName, req.ContentType, []byte(req.Data), req.Tags)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusCreated, common.BaseResponse{Code: 201, Data: meta})
}

func (h *Handler) GetObject(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	bucket := c.Param("bucket")
	key := c.Param("key")
	data, meta, err := h.storageSvc.GetObject(tenantID, bucket, key)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: gin.H{
		"data": string(data),
		"meta": meta,
	}})
}

func (h *Handler) DeleteObject(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	bucket := c.Param("bucket")
	key := c.Param("key")
	if err := h.storageSvc.DeleteObject(tenantID, bucket, key); err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Message: "删除成功"})
}

func (h *Handler) ListObjects(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	bucket := c.Param("bucket")
	prefix := c.Query("prefix")
	objs, err := h.storageSvc.ListObjects(tenantID, bucket, prefix)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: objs})
}

func (h *Handler) SearchObjects(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		Tags map[string]string `json:"tags" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	objs, err := h.storageSvc.SearchByTags(tenantID, req.Tags)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: objs})
}

func (h *Handler) RecordUsage(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		ResourceType string                 `json:"resource_type" binding:"required"`
		Quantity     float64                `json:"quantity" binding:"required"`
		Unit         string                 `json:"unit" binding:"required"`
		Attributes   map[string]interface{} `json:"attributes"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	record, err := h.usageSvc.RecordUsage(tenantID, req.ResourceType, req.Quantity, req.Unit, req.Attributes)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusCreated, common.BaseResponse{Code: 201, Data: record})
}

func (h *Handler) GetUsage(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	startStr := c.Query("start")
	endStr := c.Query("end")
	start, _ := time.Parse(time.RFC3339, startStr)
	end, _ := time.Parse(time.RFC3339, endStr)
	if end.IsZero() {
		end = time.Now()
	}
	if start.IsZero() {
		start = end.AddDate(0, 0, -30)
	}
	usage, err := h.usageSvc.GetUsage(tenantID, start, end)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: usage})
}

func (h *Handler) GenerateInvoice(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		PeriodStart time.Time `json:"period_start"`
		PeriodEnd   time.Time `json:"period_end"`
		Scenario    string    `json:"scenario"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		now := time.Now()
		req.PeriodStart = time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, time.UTC)
		req.PeriodEnd = now
	}
	invoice, err := h.billingSvc.GenerateInvoice(tenantID, req.PeriodStart, req.PeriodEnd, req.Scenario)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusCreated, common.BaseResponse{Code: 201, Data: invoice})
}

func (h *Handler) ListInvoices(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	invoices, total, err := h.billingSvc.ListInvoices(tenantID, page, pageSize)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: gin.H{"invoices": invoices, "total": total}})
}

func (h *Handler) GetInvoice(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	invoiceID := c.Param("id")
	invoice, err := h.billingSvc.GetInvoice(tenantID, invoiceID)
	if err != nil {
		c.JSON(http.StatusNotFound, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: invoice})
}

func (h *Handler) PayInvoice(c *gin.Context) {
	invoiceID := c.Param("id")
	if err := h.billingSvc.MarkInvoicePaid(invoiceID); err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Message: "支付成功"})
}

func (h *Handler) CreateTask(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		Name     string                 `json:"name" binding:"required"`
		Type     string                 `json:"type" binding:"required"`
		CronExpr string                 `json:"cron_expr"`
		Interval int                    `json:"interval_seconds"`
		Handler  string                 `json:"handler" binding:"required"`
		Params   map[string]interface{} `json:"params"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	task, err := h.schedulerSvc.CreateTask(tenantID, req.Name, scheduler.TaskType(req.Type), req.CronExpr, req.Interval, req.Handler, req.Params)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusCreated, common.BaseResponse{Code: 201, Data: task})
}

func (h *Handler) ListTasks(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	tasks, err := h.schedulerSvc.ListTasks(tenantID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: tasks})
}

func (h *Handler) GetTask(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	taskID := c.Param("id")
	task, err := h.schedulerSvc.GetTask(tenantID, taskID)
	if err != nil {
		c.JSON(http.StatusNotFound, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: task})
}

func (h *Handler) StartTask(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	taskID := c.Param("id")
	if err := h.schedulerSvc.StartTask(tenantID, taskID); err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Message: "任务已启动"})
}

func (h *Handler) StopTask(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	taskID := c.Param("id")
	if err := h.schedulerSvc.StopTask(tenantID, taskID); err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Message: "任务已停止"})
}

func (h *Handler) DeleteTask(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	taskID := c.Param("id")
	if err := h.schedulerSvc.DeleteTask(tenantID, taskID); err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Message: "任务已删除"})
}

func (h *Handler) GetTaskExecutions(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	taskID := c.Param("id")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	executions, err := h.schedulerSvc.GetTaskExecutions(tenantID, taskID, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: executions})
}

func (h *Handler) SetLogLevel(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		Module string `json:"module" binding:"required"`
		Level  string `json:"level" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	if err := h.loggerMgr.SetLevel(tenantID, req.Module, logger.LogLevel(req.Level)); err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Message: "日志级别已更新"})
}

func (h *Handler) GetLogConfigs(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	configs, err := h.loggerMgr.GetLogConfigs(tenantID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: configs})
}

func (h *Handler) QueryLogs(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	level := c.Query("level")
	module := c.Query("module")
	keyword := c.Query("keyword")
	startStr := c.Query("start_time")
	endStr := c.Query("end_time")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	start, _ := time.Parse(time.RFC3339, startStr)
	end, _ := time.Parse(time.RFC3339, endStr)
	logs, total, err := h.loggerMgr.QueryLogs(tenantID, level, module, keyword, start, end, page, pageSize)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: gin.H{"logs": logs, "total": total}})
}

func (h *Handler) SaveDocument(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		Title    string                 `json:"title" binding:"required"`
		Content  string                 `json:"content" binding:"required"`
		Version  string                 `json:"version"`
		Category string                 `json:"category"`
		Tags     []string               `json:"tags"`
		Metadata map[string]interface{} `json:"metadata"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	doc, err := h.docComparer.SaveDocument(tenantID, req.Title, req.Content, req.Version, req.Category, req.Tags, req.Metadata)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusCreated, common.BaseResponse{Code: 201, Data: doc})
}

func (h *Handler) GetDocument(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	docID := c.Param("id")
	doc, err := h.docComparer.GetDocument(tenantID, docID)
	if err != nil {
		c.JSON(http.StatusNotFound, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: doc})
}

func (h *Handler) CompareDocuments(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		OldDocID string `json:"old_doc_id" binding:"required"`
		NewDocID string `json:"new_doc_id" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	result, err := h.docComparer.Compare(tenantID, req.OldDocID, req.NewDocID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusCreated, common.BaseResponse{Code: 201, Data: result})
}

func (h *Handler) GetCompareResult(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	resultID := c.Param("id")
	result, err := h.docComparer.GetCompareResult(tenantID, resultID)
	if err != nil {
		c.JSON(http.StatusNotFound, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: result})
}

func (h *Handler) ListCompareResults(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	results, total, err := h.docComparer.ListCompareResults(tenantID, page, pageSize)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: gin.H{"results": results, "total": total}})
}

func (h *Handler) CreateSkill(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		Name          string   `json:"name" binding:"required"`
		Description   string   `json:"description"`
		Category      string   `json:"category" binding:"required"`
		Level         int      `json:"level"`
		ParentID      string   `json:"parent_id"`
		Prerequisites []string `json:"prerequisites"`
		Weight        float64  `json:"weight"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	skill, err := h.skillMgr.CreateSkill(tenantID, req.Name, req.Description, req.Category, req.Level, req.ParentID, req.Prerequisites, req.Weight)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusCreated, common.BaseResponse{Code: 201, Data: skill})
}

func (h *Handler) ListSkills(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	category := c.Query("category")
	skills, err := h.skillMgr.ListSkills(tenantID, category)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: skills})
}

func (h *Handler) GetSkillTree(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	tree, err := h.skillMgr.GetSkillTree(tenantID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: tree})
}

func (h *Handler) AddEmployeeSkill(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		EmployeeID      string  `json:"employee_id" binding:"required"`
		EmployeeName    string  `json:"employee_name" binding:"required"`
		SkillID         string  `json:"skill_id" binding:"required"`
		SkillName       string  `json:"skill_name" binding:"required"`
		Proficiency     int     `json:"proficiency" binding:"required"`
		Certified       bool    `json:"certified"`
		ExperienceYears float64 `json:"experience_years"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	empSkill, err := h.skillMgr.AddEmployeeSkill(tenantID, req.EmployeeID, req.EmployeeName, req.SkillID, req.SkillName, req.Proficiency, req.Certified, req.ExperienceYears)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusCreated, common.BaseResponse{Code: 201, Data: empSkill})
}

func (h *Handler) GetEmployeeSkills(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	employeeID := c.Param("employeeId")
	skills, err := h.skillMgr.GetEmployeeSkills(tenantID, employeeID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: skills})
}

func (h *Handler) GetSkillGap(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	employeeID := c.Param("employeeId")
	gap, err := h.skillMgr.GetSkillGapAnalysis(tenantID, employeeID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: gap})
}

func (h *Handler) RecommendLearningPath(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	employeeID := c.Param("employeeId")
	targetRole := c.Query("target_role")
	recommendations, err := h.skillMgr.RecommendLearningPath(tenantID, employeeID, targetRole)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: recommendations})
}

func (h *Handler) CreateLearningPath(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		Name           string   `json:"name" binding:"required"`
		Description    string   `json:"description"`
		TargetRole     string   `json:"target_role" binding:"required"`
		SkillNodes     []string `json:"skill_nodes" binding:"required"`
		EstimatedHours int      `json:"estimated_hours"`
		Difficulty     string   `json:"difficulty"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	path, err := h.skillMgr.CreateLearningPath(tenantID, req.Name, req.Description, req.TargetRole, req.SkillNodes, req.EstimatedHours, req.Difficulty)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusCreated, common.BaseResponse{Code: 201, Data: path})
}

func (h *Handler) GetLearningPaths(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	targetRole := c.Query("target_role")
	paths, err := h.skillMgr.GetLearningPaths(tenantID, targetRole)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: paths})
}

func (h *Handler) CreateTicket(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		Title          string   `json:"title" binding:"required"`
		Description    string   `json:"description"`
		Priority       string   `json:"priority"`
		Category       string   `json:"category"`
		Tags           []string `json:"tags"`
		RequiredSkills []string `json:"required_skills"`
		ReporterID     string   `json:"reporter_id"`
		ReporterName   string   `json:"reporter_name"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	ticketObj, err := h.ticketAlloc.CreateTicket(tenantID, req.Title, req.Description, ticket.TicketPriority(req.Priority), req.Category, req.Tags, req.RequiredSkills, req.ReporterID, req.ReporterName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusCreated, common.BaseResponse{Code: 201, Data: ticketObj})
}

func (h *Handler) ListTickets(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	status := c.Query("status")
	assigneeID := c.Query("assignee_id")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	tickets, total, err := h.ticketAlloc.ListTickets(tenantID, status, assigneeID, page, pageSize)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: gin.H{"tickets": tickets, "total": total}})
}

func (h *Handler) GetTicket(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	ticketID := c.Param("id")
	ticketObj, err := h.ticketAlloc.GetTicket(tenantID, ticketID)
	if err != nil {
		c.JSON(http.StatusNotFound, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: ticketObj})
}

func (h *Handler) AssignTicket(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	ticketID := c.Param("id")
	result, err := h.ticketAlloc.AssignTicket(tenantID, ticketID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: result})
}

func (h *Handler) UpdateTicketStatus(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	ticketID := c.Param("id")
	var req struct {
		Status     string `json:"status" binding:"required"`
		Resolution string `json:"resolution"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	ticketObj, err := h.ticketAlloc.UpdateTicketStatus(tenantID, ticketID, ticket.TicketStatus(req.Status), req.Resolution)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: ticketObj})
}

func (h *Handler) CreateAgent(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		Name        string            `json:"name" binding:"required"`
		Email       string            `json:"email"`
		Skills      []string          `json:"skills"`
		SkillLevels map[string]int    `json:"skill_levels"`
		MaxLoad     int               `json:"max_load"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	agent, err := h.ticketAlloc.CreateAgent(tenantID, req.Name, req.Email, req.Skills, req.SkillLevels, req.MaxLoad)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusCreated, common.BaseResponse{Code: 201, Data: agent})
}

func (h *Handler) ListAgents(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	agents, err := h.ticketAlloc.ListAgents(tenantID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: agents})
}

func (h *Handler) GetAgentLoad(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	agentID := c.Param("id")
	load, err := h.ticketAlloc.GetAgentLoad(tenantID, agentID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: load})
}

func (h *Handler) RegisterMetric(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		Name        string    `json:"name" binding:"required"`
		Type        string    `json:"type" binding:"required"`
		Description string    `json:"description"`
		Labels      []string  `json:"labels"`
		Buckets     []float64 `json:"buckets"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	def := &monitor.MetricDefinition{
		TenantID:    tenantID,
		Name:        req.Name,
		Type:        monitor.MetricType(req.Type),
		Description: req.Description,
		Labels:      req.Labels,
		Buckets:     req.Buckets,
		Enabled:     true,
	}
	if err := h.monitor.RegisterMetric(def); err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusCreated, common.BaseResponse{Code: 201, Data: def})
}

func (h *Handler) ListMetrics(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	metrics, err := h.monitor.GetMetrics(tenantID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: metrics})
}

func (h *Handler) QueryMetrics(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		MetricName  string            `json:"metric_name" binding:"required"`
		StartTime   time.Time         `json:"start_time"`
		EndTime     time.Time         `json:"end_time"`
		Labels      map[string]string `json:"labels"`
		Aggregation string            `json:"aggregation"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	data, avg, err := h.monitor.QueryMetrics(tenantID, req.MetricName, req.StartTime, req.EndTime, req.Labels, req.Aggregation)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: gin.H{"data": data, "avg": avg}})
}

func (h *Handler) CreateResource(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	var req struct {
		Type   string                 `json:"type" binding:"required"`
		Config map[string]interface{} `json:"config"`
		Labels map[string]string      `json:"labels"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	entity := common.NewEntity(common.GenerateID("rsc"), req.Type, tenantID, req.Config)
	entity.Attributes["labels"] = req.Labels
	c.JSON(http.StatusCreated, common.BaseResponse{
		Code: 201,
		Data: gin.H{"id": entity.ID, "status": "provisioning"},
	})
}

func (h *Handler) GetResourceStatus(c *gin.Context) {
	id := c.Param("id")
	c.JSON(http.StatusOK, common.BaseResponse{
		Code: 200,
		Data: gin.H{"id": id, "status": "completed", "progress": 0.8},
	})
}

func (h *Handler) BatchOperation(c *gin.Context) {
	var req struct {
		Operations []struct {
			Action string `json:"action" binding:"required"`
			ID     string `json:"id" binding:"required"`
		} `json:"operations" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	results := make([]gin.H, len(req.Operations))
	for i, op := range req.Operations {
		results[i] = gin.H{"id": op.ID, "action": op.Action, "status": "success"}
	}
	c.JSON(http.StatusOK, common.BaseResponse{
		Code: 200,
		Data: gin.H{"batch_id": common.GenerateID("batch"), "results": results},
	})
}

func (h *Handler) ListBillingConfigs(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	scenario := c.Query("scenario")
	configs, err := h.billingSvc.GetConfigManager().ListConfigs(scenario)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: configs})
}

func (h *Handler) AddBillingConfig(c *gin.Context) {
	var config billingConfig.BillingConfig
	if err := c.ShouldBindJSON(&config); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	operator := c.GetHeader("X-Operator")
	if operator == "" {
		operator = "system"
	}
	if err := h.billingSvc.GetConfigManager().AddConfig(&config, operator); err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusCreated, common.BaseResponse{Code: 201, Data: config})
}

func (h *Handler) UpdateBillingConfig(c *gin.Context) {
	configID := c.Param("id")
	var config billingConfig.BillingConfig
	if err := c.ShouldBindJSON(&config); err != nil {
		c.JSON(http.StatusBadRequest, errors.ErrBadRequest)
		return
	}
	config.ID = configID
	operator := c.GetHeader("X-Operator")
	if operator == "" {
		operator = "system"
	}
	if err := h.billingSvc.GetConfigManager().UpdateConfig(&config, operator); err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: config})
}

func (h *Handler) DeleteBillingConfig(c *gin.Context) {
	configID := c.Param("id")
	operator := c.GetHeader("X-Operator")
	if operator == "" {
		operator = "system"
	}
	if err := h.billingSvc.GetConfigManager().DeleteConfig(configID, operator); err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Message: "配置已删除"})
}

func (h *Handler) GetBillingConfigHistory(c *gin.Context) {
	configID := c.Param("id")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	history, err := h.billingSvc.GetConfigManager().GetChangeHistory(configID, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: history})
}

func (h *Handler) ListStoragePolicies(c *gin.Context) {
	policies := h.storageSvc.GetPolicyManager().ListPolicies()
	result := make([]gin.H, len(policies))
	for i, p := range policies {
		result[i] = gin.H{
			"name":     p.Name(),
			"enabled":  p.Enabled(),
			"priority": p.Priority(),
		}
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: result})
}

func (h *Handler) EnableStoragePolicy(c *gin.Context) {
	policyName := c.Param("name")
	if err := h.storageSvc.GetPolicyManager().SetPolicyEnabled(policyName, true); err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Message: "策略已启用"})
}

func (h *Handler) DisableStoragePolicy(c *gin.Context) {
	policyName := c.Param("name")
	if err := h.storageSvc.GetPolicyManager().SetPolicyEnabled(policyName, false); err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Message: "策略已禁用"})
}

func (h *Handler) ExecuteTaskAsync(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	taskID := c.Param("id")
	executionID, err := h.schedulerSvc.ExecuteTaskAsync(tenantID, taskID, nil)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusAccepted, common.BaseResponse{Code: 202, Data: gin.H{
		"execution_id": executionID,
		"status":       "queued",
	}})
}

func (h *Handler) ExecuteHandlerAsync(c *gin.Context) {
	tenantID := middleware.GetTenantID(c)
	handlerName := c.Param("handler")
	var req struct {
		Params  map[string]interface{} `json:"params"`
		Timeout int                    `json:"timeout_seconds"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		req.Params = make(map[string]interface{})
	}
	executionID, err := h.schedulerSvc.ExecuteHandlerAsync(tenantID, handlerName, req.Params, nil, time.Duration(req.Timeout)*time.Second)
	if err != nil {
		c.JSON(http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusAccepted, common.BaseResponse{Code: 202, Data: gin.H{
		"execution_id": executionID,
		"status":       "queued",
	}})
}

func (h *Handler) GetExecutionResult(c *gin.Context) {
	executionID := c.Param("id")
	result, ok := h.schedulerSvc.GetTaskResult(executionID)
	if !ok {
		c.JSON(http.StatusNotFound, common.BaseResponse{Code: 404, Message: "执行结果不存在或仍在处理中"})
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: result})
}

func (h *Handler) WaitForExecutionResult(c *gin.Context) {
	executionID := c.Param("id")
	var req struct {
		Timeout int `json:"timeout_seconds" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		req.Timeout = 30
	}
	result, err := h.schedulerSvc.WaitForTaskResult(executionID, time.Duration(req.Timeout)*time.Second)
	if err != nil {
		c.JSON(http.StatusRequestTimeout, common.BaseResponse{Code: 408, Message: "等待超时"})
		return
	}
	c.JSON(http.StatusOK, common.BaseResponse{Code: 200, Data: result})
}
