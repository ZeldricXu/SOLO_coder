package api

import (
	"io"
	"net/http"
	"strconv"
	"time"

	"techplatform/internal/catalog"
	"techplatform/internal/config"
	"techplatform/internal/docindex"
	"techplatform/internal/environment"
	"techplatform/internal/monitor"
	"techplatform/internal/notification"
	"techplatform/internal/scheduler"
	"techplatform/internal/scaffold"
	"techplatform/internal/vulnerability"
	"techplatform/pkg/common"

	"github.com/gin-gonic/gin"
)

type Handler struct {
	configManager     *config.Manager
	docIndexManager   *docindex.IndexManager
	scheduler         *scheduler.Scheduler
	catalogManager    *catalog.CatalogManager
	scaffoldManager   *scaffold.ScaffoldManager
	envManager        *environment.EnvironmentManager
	notifManager      *notification.NotificationManager
	monitorManager    *monitor.MonitorManager
	vulnManager       *vulnerability.VulnerabilityManager
}

func NewHandler(
	configMgr *config.Manager,
	docIdxMgr *docindex.IndexManager,
	sched *scheduler.Scheduler,
	catMgr *catalog.CatalogManager,
	scaffoldMgr *scaffold.ScaffoldManager,
	envMgr *environment.EnvironmentManager,
	notifMgr *notification.NotificationManager,
	monMgr *monitor.MonitorManager,
	vulnMgr *vulnerability.VulnerabilityManager,
) *Handler {
	return &Handler{
		configManager:    configMgr,
		docIndexManager:  docIdxMgr,
		scheduler:        sched,
		catalogManager:   catMgr,
		scaffoldManager:  scaffoldMgr,
		envManager:       envMgr,
		notifManager:     notifMgr,
		monitorManager:   monMgr,
		vulnManager:      vulnMgr,
	}
}

func (h *Handler) RegisterRoutes(router *gin.Engine) {
	api := router.Group("/api/v1")

	api.GET("/health", h.HealthCheck)
	api.GET("/config", h.GetConfig)
	api.GET("/stats", h.GetStats)

	config := api.Group("/config")
	{
		config.GET("", h.ListConfig)
		config.GET("/:key", h.GetConfigItem)
		config.PUT("/:key", h.UpdateConfig)
		config.POST("/reload", h.ReloadConfig)
		config.GET("/export", h.ExportConfig)
	}

	docs := api.Group("/docs")
	{
		docs.GET("/search", h.SearchDocuments)
		docs.GET("/:id", h.GetDocument)
		docs.DELETE("/:id", h.DeleteDocument)
		docs.GET("", h.ListDocuments)
		docs.POST("/sync", h.SyncDocuments)
		docs.GET("/stats", h.GetDocStats)
		docs.POST("/index/rebuild", h.RebuildIndex)
		docs.PUT("/:id/permissions", h.UpdateDocPermissions)
	}

	tasks := api.Group("/tasks")
	{
		tasks.GET("", h.ListTasks)
		tasks.POST("", h.CreateTask)
		tasks.GET("/:id", h.GetTask)
		tasks.PUT("/:id", h.UpdateTask)
		tasks.DELETE("/:id", h.DeleteTask)
		tasks.POST("/:id/run", h.RunTask)
		tasks.POST("/:id/pause", h.PauseTask)
		tasks.POST("/:id/resume", h.ResumeTask)
		tasks.GET("/:id/executions", h.GetTaskExecutions)
		tasks.GET("/stats", h.GetTaskStats)
	}

	svc := api.Group("/catalog")
	{
		svc.GET("", h.ListServices)
		svc.POST("", h.RegisterService)
		svc.GET("/search", h.SearchServices)
		svc.GET("/:id", h.GetService)
		svc.PUT("/:id", h.UpdateService)
		svc.DELETE("/:id", h.DeleteService)
		svc.GET("/:id/dependencies", h.GetServiceDependencies)
		svc.GET("/:id/dependents", h.GetServiceDependents)
		svc.GET("/:id/graph", h.GetDependencyGraph)
		svc.POST("/dependencies", h.AddDependency)
		svc.DELETE("/dependencies", h.RemoveDependency)
		svc.GET("/stats", h.GetCatalogStats)
		svc.GET("/dependencies/top", h.GetTopDependencies)
	}

	scaffold := api.Group("/scaffold")
	{
		scaffold.GET("/templates", h.ListTemplates)
		scaffold.GET("/templates/:id", h.GetTemplate)
		scaffold.GET("/templates/:id/params", h.GetTemplateParams)
		scaffold.GET("/templates/:id/questions", h.GetInteractiveQuestions)
		scaffold.POST("/generate", h.GenerateProject)
		scaffold.POST("/generate/interactive", h.GenerateInteractive)
		scaffold.GET("/history", h.GetScaffoldHistory)
		scaffold.GET("/stats", h.GetScaffoldStats)
	}

	env := api.Group("/environments")
	{
		env.GET("", h.ListEnvironments)
		env.POST("", h.CreateEnvironment)
		env.GET("/:id", h.GetEnvironment)
		env.POST("/:id/stop", h.StopEnvironment)
		env.POST("/:id/start", h.StartEnvironment)
		env.DELETE("/:id", h.DestroyEnvironment)
		env.POST("/:id/extend", h.ExtendEnvironmentTTL)
		env.GET("/stats", h.GetEnvStats)
		env.GET("/stats/users", h.GetEnvStatsByUser)
		env.GET("/stats/projects", h.GetEnvStatsByProject)
	}

	notif := api.Group("/notifications")
	{
		notif.GET("", h.ListNotifications)
		notif.POST("", h.SendNotification)
		notif.POST("/template/:code", h.SendNotificationWithTemplate)
		notif.GET("/:id", h.GetNotification)
		notif.POST("/:id/read", h.MarkNotificationRead)
		notif.POST("/read-all", h.MarkAllNotificationsRead)
		notif.GET("/templates", h.ListNotificationTemplates)
		notif.GET("/templates/:code", h.GetNotificationTemplate)
		notif.GET("/rules/suppression", h.GetSuppressionRules)
		notif.GET("/stats", h.GetNotificationStats)
	}

	alert := api.Group("/alerts")
	{
		alert.GET("", h.ListAlerts)
		alert.GET("/rules", h.ListAlertRules)
		alert.POST("/rules", h.CreateAlertRule)
		alert.GET("/rules/:id", h.GetAlertRule)
		alert.PUT("/rules/:id", h.UpdateAlertRule)
		alert.DELETE("/rules/:id", h.DeleteAlertRule)
		alert.POST("/rules/:id/silence", h.SilenceAlertRule)
		alert.GET("/active", h.GetActiveAlerts)
		alert.POST("/metrics", h.RecordMetric)
		alert.GET("/metrics/:name", h.GetMetricHistory)
		alert.GET("/stats", h.GetAlertStats)
	}

	vuln := api.Group("/vulnerabilities")
	{
		vuln.GET("", h.ListVulnerabilities)
		vuln.POST("/sbom/upload", h.UploadSBOM)
		vuln.GET("/sbom", h.ListSBOMs)
		vuln.GET("/sbom/:id", h.GetSBOM)
		vuln.GET("/sbom/:id/report", h.GetFullReport)
		vuln.POST("/:id/patch", h.MarkPatched)
		vuln.GET("/stats", h.GetVulnStats)
	}
}

func (h *Handler) HealthCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status": "ok",
		"timestamp": common.FormatTime(time.Now()),
	})
}

func (h *Handler) GetConfig(c *gin.Context) {
	c.JSON(http.StatusOK, h.configManager.GetConfig())
}

func (h *Handler) GetStats(c *gin.Context) {
	stats := make(map[string]interface{})

	docStats := h.docIndexManager.GetStats()
	stats["documents"] = docStats

	taskStats := h.scheduler.GetStats()
	stats["scheduler"] = taskStats

	catalogStats := h.catalogManager.GetStats()
	stats["catalog"] = catalogStats

	scaffoldStats := h.scaffoldManager.GetStats()
	stats["scaffold"] = scaffoldStats

	envStats := h.envManager.GetStats()
	stats["environments"] = envStats

	notifStats := h.notifManager.GetStats()
	stats["notifications"] = notifStats

	alertStats := h.monitorManager.GetStats()
	stats["alerts"] = alertStats

	vulnStats := h.vulnManager.GetStats()
	stats["vulnerabilities"] = vulnStats

	c.JSON(http.StatusOK, stats)
}

func (h *Handler) ListConfig(c *gin.Context) {
	items := h.configManager.GetAllItems()
	c.JSON(http.StatusOK, items)
}

func (h *Handler) GetConfigItem(c *gin.Context) {
	key := c.Param("key")
	item, err := h.configManager.GetItem(key)
	if err != nil {
		c.JSON(http.StatusNotFound, common.ErrorResponse{Code: 404, Message: "Config not found", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, item)
}

func (h *Handler) UpdateConfig(c *gin.Context) {
	key := c.Param("key")
	var req struct {
		Value interface{} `json:"value"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid request", Detail: err.Error()})
		return
	}

	if err := h.configManager.Set(key, req.Value, config.SourceRemote); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to update config", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) ReloadConfig(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"status": "success", "message": "Config reloaded"})
}

func (h *Handler) ExportConfig(c *gin.Context) {
	yaml, err := h.configManager.Export()
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to export config", Detail: err.Error()})
		return
	}
	c.String(http.StatusOK, yaml)
}

func (h *Handler) SearchDocuments(c *gin.Context) {
	var query docindex.SearchQuery
	if err := c.ShouldBindQuery(&query); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid query", Detail: err.Error()})
		return
	}

	userPerms := &docindex.DocumentPermission{
		UserID:   c.GetHeader("X-User-ID"),
		Role:     c.GetHeader("X-User-Role"),
		Groups:   []string{},
		CanView:  true,
	}

	result, err := h.docIndexManager.Search(c.Request.Context(), query, userPerms)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Search failed", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) GetDocument(c *gin.Context) {
	id := c.Param("id")
	userPerms := &docindex.DocumentPermission{
		UserID:  c.GetHeader("X-User-ID"),
		Role:    c.GetHeader("X-User-Role"),
		CanView: true,
	}
	doc, err := h.docIndexManager.GetDocument(id, userPerms)
	if err != nil {
		c.JSON(http.StatusNotFound, common.ErrorResponse{Code: 404, Message: "Document not found", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, doc)
}

func (h *Handler) DeleteDocument(c *gin.Context) {
	id := c.Param("id")
	if err := h.docIndexManager.DeleteDocument(id); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to delete document", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) ListDocuments(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	source := c.Query("source")

	result, err := h.docIndexManager.ListAll(page, pageSize, source)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to list documents", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) SyncDocuments(c *gin.Context) {
	var req struct {
		Source string                 `json:"source"`
		Config map[string]interface{} `json:"config"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid request", Detail: err.Error()})
		return
	}

	count, err := h.docIndexManager.SyncFromSource(c.Request.Context(), req.Source, req.Config)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Sync failed", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success", "synced": count})
}

func (h *Handler) GetDocStats(c *gin.Context) {
	c.JSON(http.StatusOK, h.docIndexManager.GetStats())
}

func (h *Handler) RebuildIndex(c *gin.Context) {
	if err := h.docIndexManager.RebuildIndex(); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to rebuild index", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) UpdateDocPermissions(c *gin.Context) {
	id := c.Param("id")
	var perms docindex.DocumentPermission
	if err := c.ShouldBindJSON(&perms); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid permissions", Detail: err.Error()})
		return
	}
	if err := h.docIndexManager.UpdateDocumentPermission(id, perms); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to update permissions", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) ListTasks(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	status := scheduler.TaskStatus(c.Query("status"))

	result, err := h.scheduler.ListTasks(page, pageSize, status)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to list tasks", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) CreateTask(c *gin.Context) {
	var task scheduler.Task
	if err := c.ShouldBindJSON(&task); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid task", Detail: err.Error()})
		return
	}
	task.CreatedBy = c.GetHeader("X-User-ID")
	created, err := h.scheduler.CreateTask(&task)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to create task", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusCreated, created)
}

func (h *Handler) GetTask(c *gin.Context) {
	id := c.Param("id")
	task, err := h.scheduler.GetTask(id)
	if err != nil {
		c.JSON(http.StatusNotFound, common.ErrorResponse{Code: 404, Message: "Task not found", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, task)
}

func (h *Handler) UpdateTask(c *gin.Context) {
	id := c.Param("id")
	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid updates", Detail: err.Error()})
		return
	}
	task, err := h.scheduler.UpdateTask(id, updates)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to update task", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, task)
}

func (h *Handler) DeleteTask(c *gin.Context) {
	id := c.Param("id")
	if err := h.scheduler.DeleteTask(id); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to delete task", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) RunTask(c *gin.Context) {
	id := c.Param("id")
	if err := h.scheduler.RunTaskNow(id); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to run task", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) PauseTask(c *gin.Context) {
	id := c.Param("id")
	if err := h.scheduler.PauseTask(id); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to pause task", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) ResumeTask(c *gin.Context) {
	id := c.Param("id")
	if err := h.scheduler.ResumeTask(id); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to resume task", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) GetTaskExecutions(c *gin.Context) {
	id := c.Param("id")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	result, err := h.scheduler.GetTaskExecutions(id, page, pageSize)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to get executions", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) GetTaskStats(c *gin.Context) {
	c.JSON(http.StatusOK, h.scheduler.GetStats())
}

func (h *Handler) ListServices(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	namespace := c.Query("namespace")

	result, err := h.catalogManager.ListAll(page, pageSize, namespace)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to list services", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) RegisterService(c *gin.Context) {
	var svc catalog.Service
	if err := c.ShouldBindJSON(&svc); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid service", Detail: err.Error()})
		return
	}
	created, err := h.catalogManager.RegisterService(&svc)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to register service", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusCreated, created)
}

func (h *Handler) SearchServices(c *gin.Context) {
	var query catalog.ServiceQuery
	if err := c.ShouldBindQuery(&query); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid query", Detail: err.Error()})
		return
	}
	result, err := h.catalogManager.SearchServices(query)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Search failed", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) GetService(c *gin.Context) {
	id := c.Param("id")
	svc, err := h.catalogManager.GetService(id)
	if err != nil {
		c.JSON(http.StatusNotFound, common.ErrorResponse{Code: 404, Message: "Service not found", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, svc)
}

func (h *Handler) UpdateService(c *gin.Context) {
	id := c.Param("id")
	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid updates", Detail: err.Error()})
		return
	}
	svc, err := h.catalogManager.UpdateService(id, updates)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to update service", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, svc)
}

func (h *Handler) DeleteService(c *gin.Context) {
	id := c.Param("id")
	if err := h.catalogManager.DeleteService(id); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to delete service", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) GetServiceDependencies(c *gin.Context) {
	id := c.Param("id")
	deps, err := h.catalogManager.GetDependencies(id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to get dependencies", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, deps)
}

func (h *Handler) GetServiceDependents(c *gin.Context) {
	id := c.Param("id")
	deps, err := h.catalogManager.GetDependents(id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to get dependents", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, deps)
}

func (h *Handler) GetDependencyGraph(c *gin.Context) {
	id := c.Param("id")
	depth, _ := strconv.Atoi(c.DefaultQuery("depth", "3"))
	graph, err := h.catalogManager.GetDependencyGraph(id, depth)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to get dependency graph", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, graph)
}

func (h *Handler) AddDependency(c *gin.Context) {
	var req struct {
		ServiceID    string `json:"service_id"`
		DependencyID string `json:"dependency_id"`
		VersionRange string `json:"version_range"`
		Optional     bool   `json:"optional"`
		Critical     bool   `json:"critical"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid request", Detail: err.Error()})
		return
	}
	dep, err := h.catalogManager.AddDependency(req.ServiceID, req.DependencyID, req.VersionRange, req.Optional, req.Critical)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to add dependency", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusCreated, dep)
}

func (h *Handler) RemoveDependency(c *gin.Context) {
	var req struct {
		ServiceID    string `json:"service_id"`
		DependencyID string `json:"dependency_id"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid request", Detail: err.Error()})
		return
	}
	if err := h.catalogManager.RemoveDependency(req.ServiceID, req.DependencyID); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to remove dependency", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) GetCatalogStats(c *gin.Context) {
	c.JSON(http.StatusOK, h.catalogManager.GetStats())
}

func (h *Handler) GetTopDependencies(c *gin.Context) {
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "10"))
	c.JSON(http.StatusOK, h.catalogManager.GetTopDependencies(limit))
}

func (h *Handler) ListTemplates(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	category := c.Query("category")
	language := c.Query("language")
	keyword := c.Query("keyword")

	result, err := h.scaffoldManager.ListTemplates(page, pageSize, category, language, keyword)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to list templates", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) GetTemplate(c *gin.Context) {
	id := c.Param("id")
	tpl, err := h.scaffoldManager.GetTemplate(id)
	if err != nil {
		c.JSON(http.StatusNotFound, common.ErrorResponse{Code: 404, Message: "Template not found", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, tpl)
}

func (h *Handler) GetTemplateParams(c *gin.Context) {
	id := c.Param("id")
	params, err := h.scaffoldManager.GetTemplateParams(id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to get params", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, params)
}

func (h *Handler) GetInteractiveQuestions(c *gin.Context) {
	id := c.Param("id")
	questions, err := h.scaffoldManager.GetInteractiveQuestions(id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to get questions", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, questions)
}

func (h *Handler) GenerateProject(c *gin.Context) {
	var req scaffold.GenerationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid request", Detail: err.Error()})
		return
	}

	userID := c.GetHeader("X-User-ID")
	result, err := h.scaffoldManager.Generate(c.Request.Context(), req, userID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Generation failed", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) GenerateInteractive(c *gin.Context) {
	var req struct {
		TemplateID string                 `json:"template_id"`
		Answers    map[string]interface{} `json:"answers"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid request", Detail: err.Error()})
		return
	}

	projectName, _ := req.Answers["project_name"].(string)

	genReq := scaffold.GenerationRequest{
		TemplateID:  req.TemplateID,
		ProjectName: projectName,
		Params:      req.Answers,
	}

	userID := c.GetHeader("X-User-ID")
	result, err := h.scaffoldManager.Generate(c.Request.Context(), genReq, userID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Generation failed", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) GetScaffoldHistory(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	templateID := c.Query("template_id")
	createdBy := c.Query("created_by")

	result, err := h.scaffoldManager.GetHistory(page, pageSize, templateID, createdBy)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to get history", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) GetScaffoldStats(c *gin.Context) {
	c.JSON(http.StatusOK, h.scaffoldManager.GetStats())
}

func (h *Handler) ListEnvironments(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	ownerID := c.Query("owner_id")
	projectID := c.Query("project_id")
	status := c.Query("status")

	result, err := h.envManager.List(page, pageSize, ownerID, projectID, status)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to list environments", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) CreateEnvironment(c *gin.Context) {
	var req environment.EnvironmentRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid request", Detail: err.Error()})
		return
	}

	if err := h.envManager.ValidateRequest(&req); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Validation failed", Detail: err.Error()})
		return
	}

	userID := c.GetHeader("X-User-ID")
	userName := c.GetHeader("X-User-Name")
	if userName == "" {
		userName = userID
	}

	env, err := h.envManager.Create(c.Request.Context(), req, userID, userName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to create environment", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusCreated, env)
}

func (h *Handler) GetEnvironment(c *gin.Context) {
	id := c.Param("id")
	env, err := h.envManager.Get(id)
	if err != nil {
		c.JSON(http.StatusNotFound, common.ErrorResponse{Code: 404, Message: "Environment not found", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, env)
}

func (h *Handler) StopEnvironment(c *gin.Context) {
	id := c.Param("id")
	if err := h.envManager.Stop(id); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to stop environment", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) StartEnvironment(c *gin.Context) {
	id := c.Param("id")
	if err := h.envManager.Start(id); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to start environment", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) DestroyEnvironment(c *gin.Context) {
	id := c.Param("id")
	if err := h.envManager.Destroy(id); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to destroy environment", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) ExtendEnvironmentTTL(c *gin.Context) {
	id := c.Param("id")
	var req struct {
		Minutes int `json:"minutes"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid request", Detail: err.Error()})
		return
	}
	if err := h.envManager.ExtendTTL(id, req.Minutes); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to extend TTL", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) GetEnvStats(c *gin.Context) {
	c.JSON(http.StatusOK, h.envManager.GetStats())
}

func (h *Handler) GetEnvStatsByUser(c *gin.Context) {
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "10"))
	c.JSON(http.StatusOK, h.envManager.GetUsageByUser(limit))
}

func (h *Handler) GetEnvStatsByProject(c *gin.Context) {
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "10"))
	c.JSON(http.StatusOK, h.envManager.GetUsageByProject(limit))
}

func (h *Handler) ListNotifications(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	level := c.Query("level")
	status := c.Query("status")
	channel := c.Query("channel")
	recipient := c.Query("recipient")
	unreadOnly, _ := strconv.ParseBool(c.DefaultQuery("unread_only", "false"))

	result, err := h.notifManager.List(page, pageSize, level, status, channel, recipient, unreadOnly)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to list notifications", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) SendNotification(c *gin.Context) {
	var notif notification.Notification
	if err := c.ShouldBindJSON(&notif); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid notification", Detail: err.Error()})
		return
	}
	if err := h.notifManager.Send(c.Request.Context(), &notif); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to send notification", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success", "id": notif.ID})
}

func (h *Handler) SendNotificationWithTemplate(c *gin.Context) {
	code := c.Param("code")
	var req struct {
		Params     map[string]interface{} `json:"params"`
		Recipients []string               `json:"recipients"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid request", Detail: err.Error()})
		return
	}

	notif, err := h.notifManager.SendWithTemplate(c.Request.Context(), code, req.Params, req.Recipients)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to send notification", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success", "id": notif.ID})
}

func (h *Handler) GetNotification(c *gin.Context) {
	id := c.Param("id")
	notif, err := h.notifManager.Get(id)
	if err != nil {
		c.JSON(http.StatusNotFound, common.ErrorResponse{Code: 404, Message: "Notification not found", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, notif)
}

func (h *Handler) MarkNotificationRead(c *gin.Context) {
	id := c.Param("id")
	userID := c.GetHeader("X-User-ID")
	if err := h.notifManager.MarkAsRead(id, userID); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to mark as read", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) MarkAllNotificationsRead(c *gin.Context) {
	userID := c.GetHeader("X-User-ID")
	count, err := h.notifManager.MarkAllAsRead(userID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to mark all as read", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success", "marked": count})
}

func (h *Handler) ListNotificationTemplates(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	channel := notification.NotifyChannel(c.Query("channel"))
	enabledOnly, _ := strconv.ParseBool(c.DefaultQuery("enabled_only", "true"))

	result, err := h.notifManager.ListTemplates(page, pageSize, channel, enabledOnly)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to list templates", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) GetNotificationTemplate(c *gin.Context) {
	code := c.Param("code")
	tpl, err := h.notifManager.GetTemplate(code)
	if err != nil {
		c.JSON(http.StatusNotFound, common.ErrorResponse{Code: 404, Message: "Template not found", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, tpl)
}

func (h *Handler) GetSuppressionRules(c *gin.Context) {
	c.JSON(http.StatusOK, h.notifManager.GetSuppressionRules())
}

func (h *Handler) GetNotificationStats(c *gin.Context) {
	c.JSON(http.StatusOK, h.notifManager.GetStats())
}

func (h *Handler) ListAlerts(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	level := c.Query("level")
	status := c.Query("status")
	ruleID := c.Query("rule_id")

	result, err := h.monitorManager.ListAlerts(page, pageSize, level, status, ruleID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to list alerts", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) ListAlertRules(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	metric := c.Query("metric")
	level := c.Query("level")
	enabledOnly, _ := strconv.ParseBool(c.DefaultQuery("enabled_only", "true"))

	result, err := h.monitorManager.ListRules(page, pageSize, metric, level, enabledOnly)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to list rules", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) CreateAlertRule(c *gin.Context) {
	var rule monitor.AlertRule
	if err := c.ShouldBindJSON(&rule); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid rule", Detail: err.Error()})
		return
	}
	rule.CreatedBy = c.GetHeader("X-User-ID")
	created, err := h.monitorManager.CreateRule(&rule)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to create rule", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusCreated, created)
}

func (h *Handler) GetAlertRule(c *gin.Context) {
	id := c.Param("id")
	rule, err := h.monitorManager.GetRule(id)
	if err != nil {
		c.JSON(http.StatusNotFound, common.ErrorResponse{Code: 404, Message: "Rule not found", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, rule)
}

func (h *Handler) UpdateAlertRule(c *gin.Context) {
	id := c.Param("id")
	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid updates", Detail: err.Error()})
		return
	}
	rule, err := h.monitorManager.UpdateRule(id, updates)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to update rule", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, rule)
}

func (h *Handler) DeleteAlertRule(c *gin.Context) {
	id := c.Param("id")
	if err := h.monitorManager.DeleteRule(id); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to delete rule", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) SilenceAlertRule(c *gin.Context) {
	id := c.Param("id")
	var req struct {
		Minutes int `json:"minutes"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid request", Detail: err.Error()})
		return
	}
	if err := h.monitorManager.SilenceRule(id, time.Duration(req.Minutes)*time.Minute); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to silence rule", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) GetActiveAlerts(c *gin.Context) {
	c.JSON(http.StatusOK, h.monitorManager.GetActiveAlerts())
}

func (h *Handler) RecordMetric(c *gin.Context) {
	var req struct {
		Name   string                 `json:"name"`
		Value  float64                `json:"value"`
		Type   monitor.MetricType     `json:"type"`
		Labels map[string]string      `json:"labels"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Invalid metric", Detail: err.Error()})
		return
	}
	if err := h.monitorManager.RecordMetric(req.Name, req.Value, req.Type, req.Labels); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to record metric", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) GetMetricHistory(c *gin.Context) {
	name := c.Param("name")
	startTimeStr := c.Query("start")
	endTimeStr := c.Query("end")
	interval := c.DefaultQuery("interval", "5m")

	startTime, _ := time.Parse(time.RFC3339, startTimeStr)
	endTime, _ := time.Parse(time.RFC3339, endTimeStr)

	if startTime.IsZero() {
		startTime = time.Now().Add(-24 * time.Hour)
	}
	if endTime.IsZero() {
		endTime = time.Now()
	}

	metrics, err := h.monitorManager.GetMetricHistory(name, startTime, endTime, interval)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to get metric history", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, metrics)
}

func (h *Handler) GetAlertStats(c *gin.Context) {
	c.JSON(http.StatusOK, h.monitorManager.GetStats())
}

func (h *Handler) ListVulnerabilities(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	sbomID := c.Query("sbom_id")
	severity := c.Query("severity")
	packageName := c.Query("package")

	result, err := h.vulnManager.GetVulnerabilities(page, pageSize, sbomID, severity, packageName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to list vulnerabilities", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) UploadSBOM(c *gin.Context) {
	projectID := c.PostForm("project_id")
	format := vulnerability.SBOMFormat(c.DefaultPostForm("format", "cyclonedx"))
	userID := c.GetHeader("X-User-ID")

	file, _, err := c.Request.FormFile("file")
	if err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Failed to get file", Detail: err.Error()})
		return
	}
	defer file.Close()

	data, err := io.ReadAll(file)
	if err != nil {
		c.JSON(http.StatusBadRequest, common.ErrorResponse{Code: 400, Message: "Failed to read file", Detail: err.Error()})
		return
	}

	result, err := h.vulnManager.UploadSBOM(c.Request.Context(), projectID, userID, data, format)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to analyze SBOM", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) ListSBOMs(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	projectID := c.Query("project_id")

	result, err := h.vulnManager.ListSBOMs(page, pageSize, projectID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to list SBOMs", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *Handler) GetSBOM(c *gin.Context) {
	id := c.Param("id")
	sbom, err := h.vulnManager.GetSBOM(id)
	if err != nil {
		c.JSON(http.StatusNotFound, common.ErrorResponse{Code: 404, Message: "SBOM not found", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, sbom)
}

func (h *Handler) GetFullReport(c *gin.Context) {
	id := c.Param("id")
	report, err := h.vulnManager.GetFullReport(id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to get report", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, report)
}

func (h *Handler) MarkPatched(c *gin.Context) {
	id := c.Param("id")
	userID := c.GetHeader("X-User-ID")
	if err := h.vulnManager.MarkPatched(id, userID); err != nil {
		c.JSON(http.StatusInternalServerError, common.ErrorResponse{Code: 500, Message: "Failed to mark as patched", Detail: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "success"})
}

func (h *Handler) GetVulnStats(c *gin.Context) {
	c.JSON(http.StatusOK, h.vulnManager.GetStats())
}
