package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/datamigration/platform/internal/approval"
	"github.com/datamigration/platform/internal/core"
	"github.com/datamigration/platform/internal/database"
	"github.com/datamigration/platform/internal/logger"
	"github.com/datamigration/platform/internal/monitor"
	"github.com/datamigration/platform/internal/scheduler"
	"github.com/datamigration/platform/internal/skills"
	"github.com/datamigration/platform/internal/sla"
	"github.com/datamigration/platform/internal/tenant"
	"github.com/datamigration/platform/internal/workflow"
	"github.com/datamigration/platform/pkg/config"
	"github.com/datamigration/platform/pkg/models"
	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.uber.org/zap"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

type App struct {
	db          *gorm.DB
	migrator    *database.Migrator
	repo        *database.Repository
	tenantSvc   *tenant.Service
	approvalEng *approval.RuleEngine
	skillsSvc   *skills.GraphService
	coreHandler *core.Handler
	resourceHdl *core.ResourceHandler
	slaMonitor  *sla.Monitor
	metrics     *monitor.Metrics
	designer    *workflow.Designer
	scheduler   *scheduler.Scheduler
	router      *gin.Engine
	server      *http.Server
}

func main() {
	configPath := flag.String("config", "configs/config.json", "Path to configuration file")
	migrateOnly := flag.Bool("migrate-only", false, "Only run migrations and exit")
	flag.Parse()

	cfg, err := config.LoadConfig(*configPath)
	if err != nil {
		fmt.Printf("Failed to load config: %v\n", err)
		os.Exit(1)
	}

	if err := logger.InitLogger(logger.Config{
		Level:      cfg.Log.Level,
		Encoding:   cfg.Log.Encoding,
		OutputPath: cfg.Log.OutputPath,
	}); err != nil {
		fmt.Printf("Failed to init logger: %v\n", err)
		os.Exit(1)
	}
	defer logger.Sync()

	app, err := NewApp(cfg)
	if err != nil {
		logger.Fatal("Failed to initialize app", zap.Error(err))
	}

	ctx := context.Background()
	if err := app.migrator.Up(ctx); err != nil {
		logger.Fatal("Failed to run migrations", zap.Error(err))
	}

	if *migrateOnly {
		logger.Info("Migrations completed, exiting")
		return
	}

	app.RegisterRoutes()

	go app.scheduler.Start()
	defer app.scheduler.Stop()

	go func() {
		for n := range app.slaMonitor.Notifications() {
			app.metrics.RecordSLABreach(n.Type, "")
			logger.Info("SLA notification received",
				zap.String("type", n.Type),
				zap.String("instance_id", n.InstanceID),
			)
		}
	}()

	app.scheduler.RegisterHandler("metrics_snapshot", func(ctx context.Context, payload map[string]interface{}) error {
		tenantID, _ := payload["tenant_id"].(string)
		dimensions := map[string]string{"host": "node-1", "region": "cn-east"}
		_, err := app.metrics.TakeSnapshot(ctx, tenantID, dimensions)
		return err
	})

	if err := app.Start(cfg); err != nil {
		logger.Fatal("Server error", zap.Error(err))
	}
}

func NewApp(cfg *config.AppConfig) (*App, error) {
	dsn := fmt.Sprintf(
		"host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
		cfg.Database.Host, cfg.Database.Port, cfg.Database.User,
		cfg.Database.Password, cfg.Database.DBName, cfg.Database.SSLMode,
	)

	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{})
	if err != nil {
		return nil, fmt.Errorf("failed to connect database: %w", err)
	}

	sqlDB, err := db.DB()
	if err != nil {
		return nil, err
	}
	sqlDB.SetMaxOpenConns(cfg.Database.MaxOpen)
	sqlDB.SetMaxIdleConns(cfg.Database.MaxIdle)
	sqlDB.SetConnMaxLifetime(time.Hour)

	migrator, err := database.NewMigrator(db)
	if err != nil {
		return nil, err
	}

	repo := database.NewRepository(db)

	app := &App{
		db:          db,
		migrator:    migrator,
		repo:        repo,
		tenantSvc:   tenant.NewService(db),
		approvalEng: approval.NewRuleEngine(db),
		skillsSvc:   skills.NewGraphService(db),
		slaMonitor:  sla.NewMonitor(db, 1000),
		metrics:     monitor.NewMetrics(db),
		designer:    workflow.NewDesigner(db),
		scheduler:   scheduler.NewScheduler(db),
	}

	app.coreHandler = core.NewHandler(repo, core.HandlerConfig{
		MaxConcurrent:  100,
		DefaultTimeout: 30 * time.Second,
	})
	app.resourceHdl = core.NewResourceHandler(app.coreHandler)

	return app, nil
}

func (a *App) RegisterRoutes() {
	r := gin.Default()

	r.Use(a.loggingMiddleware())
	r.Use(a.tenantMiddleware())

	r.GET("/health", a.healthCheck)

	metricsGroup := r.Group("/metrics")
	{
		metricsGroup.GET("/prometheus", gin.WrapH(promhttp.HandlerFor(a.metrics.Registry(), promhttp.HandlerOpts{})))
		metricsGroup.GET("/snapshot", a.getMetricsSnapshot)
		metricsGroup.GET("/query", a.queryMetrics)
		metricsGroup.GET("/health", a.getSystemHealth)
	}

	api := r.Group("/api/v1")
	{
		resources := api.Group("/resources")
		{
			resources.POST("", a.createResource)
			resources.GET("/:id/status", a.getResourceStatus)
			resources.POST("/batch", a.batchOperation)
		}

		tenants := api.Group("/tenants")
		{
			tenants.POST("", a.createTenant)
			tenants.GET("", a.listTenants)
			tenants.GET("/:id", a.getTenant)
			tenants.PUT("/:id", a.updateTenant)
			tenants.DELETE("/:id", a.deleteTenant)
			tenants.GET("/:id/config", a.getTenantConfig)
			tenants.PUT("/:id/config", a.updateTenantConfig)
			tenants.GET("/:id/quota", a.getTenantQuota)
			tenants.PUT("/:id/quota", a.updateTenantQuota)
		}

		approval := api.Group("/approval")
		{
			approval.POST("/rules", a.createApprovalRule)
			approval.GET("/rules", a.listApprovalRules)
			approval.POST("/tasks/:id/approve", a.approveTask)
			approval.POST("/tasks/:id/reject", a.rejectTask)
			approval.GET("/tasks/pending", a.getPendingTasks)
			approval.GET("/instances/:id/status", a.checkApprovalStatus)
		}

		skills := api.Group("/skills")
		{
			skills.POST("", a.createSkill)
			skills.GET("/tree", a.getSkillTree)
			skills.POST("/assess", a.assessEmployeeSkill)
			skills.GET("/employees/:id", a.getEmployeeSkills)
			skills.POST("/learning-path", a.recommendLearningPath)
			skills.GET("/learning-path/:id", a.getLearningPath)
			skills.PUT("/learning-path/:id/progress", a.updateLearningProgress)
		}

		sla := api.Group("/sla")
		{
			sla.POST("/configs", a.createSLAConfig)
			sla.POST("/tracking", a.startSLATracking)
			sla.POST("/tracking/:id/response", a.recordResponse)
			sla.POST("/tracking/:id/resolution", a.recordResolution)
			sla.GET("/tracking/:id/countdown", a.getSLACountdown)
			sla.GET("/active", a.getActiveSLATracking)
		}

		workflow := api.Group("/workflows")
		{
			workflow.POST("", a.createWorkflow)
			workflow.POST("/validate", a.validateWorkflow)
			workflow.GET("", a.listWorkflows)
			workflow.GET("/:id", a.getWorkflow)
			workflow.POST("/:id/start", a.startWorkflowInstance)
			workflow.POST("/instances/:id/advance", a.advanceWorkflow)
			workflow.GET("/instances/active", a.getActiveInstances)
		}

		scheduler := api.Group("/scheduler")
		{
			scheduler.POST("/tasks", a.createScheduledTask)
			scheduler.GET("/tasks", a.listScheduledTasks)
			scheduler.GET("/tasks/:id", a.getScheduledTask)
			scheduler.PUT("/tasks/:id", a.updateScheduledTask)
			scheduler.DELETE("/tasks/:id", a.deleteScheduledTask)
			scheduler.POST("/tasks/:id/pause", a.pauseScheduledTask)
			scheduler.POST("/tasks/:id/resume", a.resumeScheduledTask)
			scheduler.POST("/tasks/:id/trigger", a.triggerScheduledTask)
		}

		migrations := api.Group("/migrations")
		{
			migrations.GET("/status", a.getMigrationStatus)
			migrations.POST("/up", a.runMigrationsUp)
			migrations.POST("/down", a.runMigrationsDown)
		}
	}

	a.router = r
}

func (a *App) loggingMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path
		method := c.Request.Method

		c.Next()

		duration := time.Since(start)
		statusCode := c.Writer.Status()
		tenantID := c.GetString("tenant_id")

		a.metrics.RecordRequest(method, path, strconv.Itoa(statusCode), tenantID)
		a.metrics.RecordLatency(method, path, tenantID, duration)

		if statusCode >= 500 {
			a.metrics.RecordError("server_error", tenantID)
		}

		logger.Info("HTTP request",
			zap.String("method", method),
			zap.String("path", path),
			zap.Int("status", statusCode),
			zap.Duration("duration", duration),
			zap.String("tenant_id", tenantID),
		)
	}
}

func (a *App) tenantMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		tenantID := c.GetHeader("X-Tenant-ID")
		if tenantID == "" {
			tenantID = "default"
		}
		c.Set("tenant_id", tenantID)
		c.Next()
	}
}

func (a *App) Start(cfg *config.AppConfig) error {
	addr := fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port)
	a.server = &http.Server{
		Addr:    addr,
		Handler: a.router,
	}

	go func() {
		logger.Info("Server starting", zap.String("addr", addr))
		if err := a.server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("Server failed", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("Shutting down server...")
	a.slaMonitor.StopAll()

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := a.server.Shutdown(ctx); err != nil {
		logger.Fatal("Server shutdown error", zap.Error(err))
	}

	logger.Info("Server exited")
	return nil
}

func (a *App) healthCheck(c *gin.Context) {
	c.JSON(200, gin.H{
		"status": "healthy",
		"time":   time.Now().UTC().Format(time.RFC3339),
	})
}

func (a *App) createResource(c *gin.Context) {
	var req models.ResourceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	tenantID := c.GetString("tenant_id")
	resp := a.resourceHdl.CreateResource(c.Request.Context(), &req, tenantID)
	c.JSON(resp.Code, resp)
}

func (a *App) getResourceStatus(c *gin.Context) {
	id := c.Param("id")
	tenantID := c.GetString("tenant_id")
	resp := a.resourceHdl.GetResourceStatus(c.Request.Context(), id, tenantID)
	c.JSON(resp.Code, resp)
}

func (a *App) batchOperation(c *gin.Context) {
	var req models.BatchRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	tenantID := c.GetString("tenant_id")
	resp := a.resourceHdl.BatchOperation(c.Request.Context(), &req, tenantID)
	c.JSON(resp.Code, resp)
}

func (a *App) createTenant(c *gin.Context) {
	var body struct {
		Name        string                 `json:"name" binding:"required"`
		Description string                 `json:"description"`
		Config      *models.TenantConfig   `json:"config"`
		Quota       *models.Quota          `json:"quota"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	tenant, err := a.tenantSvc.CreateTenant(c.Request.Context(), body.Name, body.Description, body.Config, body.Quota)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "data": tenant})
}

func (a *App) listTenants(c *gin.Context) {
	page := parseInt(c.Query("page"), 1)
	pageSize := parseInt(c.Query("page_size"), 20)

	tenants, total, err := a.tenantSvc.ListTenants(c.Request.Context(), page, pageSize)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(200, gin.H{
		"code":      200,
		"data":      tenants,
		"total":     total,
		"page":      page,
		"page_size": pageSize,
	})
}

func (a *App) getTenant(c *gin.Context) {
	tenant, err := a.tenantSvc.GetTenant(c.Request.Context(), c.Param("id"))
	if err != nil {
		c.JSON(404, gin.H{"code": 404, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": tenant})
}

func (a *App) updateTenant(c *gin.Context) {
	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	if err := a.tenantSvc.UpdateTenant(c.Request.Context(), c.Param("id"), updates); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "updated"})
}

func (a *App) deleteTenant(c *gin.Context) {
	if err := a.tenantSvc.DeleteTenant(c.Request.Context(), c.Param("id")); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "deleted"})
}

func (a *App) getTenantConfig(c *gin.Context) {
	cfg, err := a.tenantSvc.GetConfig(c.Request.Context(), c.Param("id"))
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": cfg})
}

func (a *App) updateTenantConfig(c *gin.Context) {
	var cfg models.TenantConfig
	if err := c.ShouldBindJSON(&cfg); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	if err := a.tenantSvc.UpdateConfig(c.Request.Context(), c.Param("id"), &cfg); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "updated"})
}

func (a *App) getTenantQuota(c *gin.Context) {
	quota, err := a.tenantSvc.GetQuota(c.Request.Context(), c.Param("id"))
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": quota})
}

func (a *App) updateTenantQuota(c *gin.Context) {
	var quota models.Quota
	if err := c.ShouldBindJSON(&quota); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	if err := a.tenantSvc.UpdateQuota(c.Request.Context(), c.Param("id"), &quota); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "updated"})
}

func (a *App) createApprovalRule(c *gin.Context) {
	var body struct {
		Name       string                `json:"name" binding:"required"`
		WorkflowID string                `json:"workflow_id" binding:"required"`
		Condition  *approval.ApprovalCondition `json:"condition"`
		Strategy   string                `json:"strategy"`
		Approvers  *approval.ApproverSpec `json:"approvers" binding:"required"`
		Priority   int                   `json:"priority"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	if body.Strategy == "" {
		body.Strategy = approval.StrategyAll
	}

	tenantID := c.GetString("tenant_id")
	rule, err := a.approvalEng.CreateRule(c.Request.Context(), tenantID, body.Name, body.WorkflowID, body.Condition, body.Strategy, body.Approvers, body.Priority)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "data": rule})
}

func (a *App) listApprovalRules(c *gin.Context) {
	workflowID := c.Query("workflow_id")
	tenantID := c.GetString("tenant_id")

	rules, err := a.approvalEng.GetRules(c.Request.Context(), tenantID, workflowID)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(200, gin.H{"code": 200, "data": rules})
}

func (a *App) approveTask(c *gin.Context) {
	var body struct {
		ApproverID string `json:"approver_id" binding:"required"`
		Comment    string `json:"comment"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	if err := a.approvalEng.Approve(c.Request.Context(), c.Param("id"), body.ApproverID, body.Comment); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(200, gin.H{"code": 200, "message": "approved"})
}

func (a *App) rejectTask(c *gin.Context) {
	var body struct {
		ApproverID string `json:"approver_id" binding:"required"`
		Comment    string `json:"comment"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	if err := a.approvalEng.Reject(c.Request.Context(), c.Param("id"), body.ApproverID, body.Comment); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(200, gin.H{"code": 200, "message": "rejected"})
}

func (a *App) getPendingTasks(c *gin.Context) {
	approverID := c.Query("approver_id")
	tasks, err := a.approvalEng.GetPendingTasks(c.Request.Context(), approverID)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": tasks})
}

func (a *App) checkApprovalStatus(c *gin.Context) {
	status, err := a.approvalEng.CheckApprovalStatus(c.Request.Context(), c.Param("id"))
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": gin.H{"status": status}})
}

func (a *App) createSkill(c *gin.Context) {
	var body struct {
		Name        string                 `json:"name" binding:"required"`
		Description string                 `json:"description"`
		Category    string                 `json:"category"`
		ParentID    *string                `json:"parent_id"`
		Level       int                    `json:"level"`
		Metadata    map[string]interface{} `json:"metadata"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	skill, err := a.skillsSvc.CreateSkill(c.Request.Context(), body.Name, body.Description, body.Category, body.ParentID, body.Level, body.Metadata)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "data": skill})
}

func (a *App) getSkillTree(c *gin.Context) {
	category := c.Query("category")
	skills, err := a.skillsSvc.GetSkillTree(c.Request.Context(), category)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": skills})
}

func (a *App) assessEmployeeSkill(c *gin.Context) {
	var body struct {
		EmployeeID  string `json:"employee_id" binding:"required"`
		SkillID     string `json:"skill_id" binding:"required"`
		Proficiency int    `json:"proficiency" binding:"required"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	record, err := a.skillsSvc.AssessEmployeeSkill(c.Request.Context(), body.EmployeeID, body.SkillID, body.Proficiency)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(200, gin.H{"code": 200, "data": record})
}

func (a *App) getEmployeeSkills(c *gin.Context) {
	skills, err := a.skillsSvc.GetEmployeeSkills(c.Request.Context(), c.Param("id"))
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": skills})
}

func (a *App) recommendLearningPath(c *gin.Context) {
	var body struct {
		EmployeeID string `json:"employee_id" binding:"required"`
		TargetRole string `json:"target_role" binding:"required"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	path, err := a.skillsSvc.RecommendLearningPath(c.Request.Context(), body.EmployeeID, body.TargetRole)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "data": path})
}

func (a *App) getLearningPath(c *gin.Context) {
	paths, err := a.skillsSvc.GetLearningPaths(c.Request.Context(), c.Param("id"))
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": paths})
}

func (a *App) updateLearningProgress(c *gin.Context) {
	var body struct {
		Progress float64 `json:"progress" binding:"required"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	if err := a.skillsSvc.UpdateLearningPathProgress(c.Request.Context(), c.Param("id"), body.Progress); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(200, gin.H{"code": 200, "message": "updated"})
}

func (a *App) createSLAConfig(c *gin.Context) {
	var body struct {
		Name           string `json:"name" binding:"required"`
		WorkflowType   string `json:"workflow_type" binding:"required"`
		ResponseTime   int    `json:"response_time" binding:"required"`
		ResolutionTime int    `json:"resolution_time" binding:"required"`
		EscalationTime int    `json:"escalation_time" binding:"required"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	tenantID := c.GetString("tenant_id")
	cfg, err := a.slaMonitor.CreateSLAConfig(c.Request.Context(), tenantID, body.Name, body.WorkflowType, body.ResponseTime, body.ResolutionTime, body.EscalationTime)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "data": cfg})
}

func (a *App) startSLATracking(c *gin.Context) {
	var body struct {
		InstanceID  string `json:"instance_id" binding:"required"`
		SLAConfigID string `json:"sla_config_id" binding:"required"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	tenantID := c.GetString("tenant_id")
	tracking, err := a.slaMonitor.StartTracking(c.Request.Context(), tenantID, body.InstanceID, body.SLAConfigID)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "data": tracking})
}

func (a *App) recordResponse(c *gin.Context) {
	if err := a.slaMonitor.RecordResponse(c.Request.Context(), c.Param("id")); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "recorded"})
}

func (a *App) recordResolution(c *gin.Context) {
	if err := a.slaMonitor.RecordResolution(c.Request.Context(), c.Param("id")); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "recorded"})
}

func (a *App) getSLACountdown(c *gin.Context) {
	countdown, err := a.slaMonitor.GetRemainingTime(c.Request.Context(), c.Param("id"))
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": countdown})
}

func (a *App) getActiveSLATracking(c *gin.Context) {
	tenantID := c.GetString("tenant_id")
	trackings, err := a.slaMonitor.GetActiveTrackings(c.Request.Context(), tenantID)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": trackings})
}

func (a *App) createWorkflow(c *gin.Context) {
	var body struct {
		Name        string                  `json:"name" binding:"required"`
		Description string                  `json:"description"`
		Nodes       []*workflow.NodeConfig  `json:"nodes" binding:"required"`
		Edges       []*workflow.EdgeConfig  `json:"edges" binding:"required"`
		Config      map[string]interface{}  `json:"config"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	tenantID := c.GetString("tenant_id")
	wf, err := a.designer.CreateDefinition(c.Request.Context(), tenantID, body.Name, body.Description, body.Nodes, body.Edges, body.Config)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "data": wf})
}

func (a *App) validateWorkflow(c *gin.Context) {
	var body struct {
		Nodes []*workflow.NodeConfig `json:"nodes" binding:"required"`
		Edges []*workflow.EdgeConfig `json:"edges" binding:"required"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	errors := a.designer.ValidateWorkflow(body.Nodes, body.Edges)
	if len(errors) > 0 {
		c.JSON(422, gin.H{
			"code":    422,
			"message": "validation failed",
			"errors":  errors,
		})
		return
	}

	c.JSON(200, gin.H{"code": 200, "message": "valid"})
}

func (a *App) listWorkflows(c *gin.Context) {
	tenantID := c.GetString("tenant_id")
	page := parseInt(c.Query("page"), 1)
	pageSize := parseInt(c.Query("page_size"), 20)

	defs, total, err := a.designer.ListDefinitions(c.Request.Context(), tenantID, page, pageSize)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(200, gin.H{
		"code":      200,
		"data":      defs,
		"total":     total,
		"page":      page,
		"page_size": pageSize,
	})
}

func (a *App) getWorkflow(c *gin.Context) {
	wf, err := a.designer.GetDefinition(c.Request.Context(), c.Param("id"))
	if err != nil {
		c.JSON(404, gin.H{"code": 404, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": wf})
}

func (a *App) startWorkflowInstance(c *gin.Context) {
	var body struct {
		Payload map[string]interface{} `json:"payload"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	tenantID := c.GetString("tenant_id")
	instance, err := a.designer.StartInstance(c.Request.Context(), tenantID, c.Param("id"), body.Payload)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "data": instance})
}

func (a *App) advanceWorkflow(c *gin.Context) {
	var body map[string]interface{}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	if err := a.designer.AdvanceInstance(c.Request.Context(), c.Param("id"), body); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(200, gin.H{"code": 200, "message": "advanced"})
}

func (a *App) getActiveInstances(c *gin.Context) {
	tenantID := c.GetString("tenant_id")
	instances, err := a.designer.GetActiveInstances(c.Request.Context(), tenantID)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": instances})
}

func (a *App) createScheduledTask(c *gin.Context) {
	var body struct {
		Name     string                 `json:"name" binding:"required"`
		TaskType string                 `json:"task_type" binding:"required"`
		CronExpr string                 `json:"cron_expr" binding:"required"`
		Payload  map[string]interface{} `json:"payload"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	tenantID := c.GetString("tenant_id")
	task, err := a.scheduler.CreateTask(c.Request.Context(), tenantID, body.Name, body.TaskType, body.CronExpr, body.Payload)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "data": task})
}

func (a *App) listScheduledTasks(c *gin.Context) {
	tenantID := c.GetString("tenant_id")
	page := parseInt(c.Query("page"), 1)
	pageSize := parseInt(c.Query("page_size"), 20)

	tasks, total, err := a.scheduler.ListTasks(c.Request.Context(), tenantID, page, pageSize)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(200, gin.H{
		"code":      200,
		"data":      tasks,
		"total":     total,
		"page":      page,
		"page_size": pageSize,
	})
}

func (a *App) getScheduledTask(c *gin.Context) {
	task, err := a.scheduler.GetTask(c.Request.Context(), c.Param("id"))
	if err != nil {
		c.JSON(404, gin.H{"code": 404, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": task})
}

func (a *App) updateScheduledTask(c *gin.Context) {
	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": err.Error()})
		return
	}

	if err := a.scheduler.UpdateTask(c.Request.Context(), c.Param("id"), updates); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "updated"})
}

func (a *App) deleteScheduledTask(c *gin.Context) {
	if err := a.scheduler.DeleteTask(c.Request.Context(), c.Param("id")); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "deleted"})
}

func (a *App) pauseScheduledTask(c *gin.Context) {
	if err := a.scheduler.PauseTask(c.Request.Context(), c.Param("id")); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "paused"})
}

func (a *App) resumeScheduledTask(c *gin.Context) {
	if err := a.scheduler.ResumeTask(c.Request.Context(), c.Param("id")); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "resumed"})
}

func (a *App) triggerScheduledTask(c *gin.Context) {
	if err := a.scheduler.TriggerNow(c.Request.Context(), c.Param("id")); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "triggered"})
}

func (a *App) getMigrationStatus(c *gin.Context) {
	status, err := a.migrator.Status(c.Request.Context())
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "data": status})
}

func (a *App) runMigrationsUp(c *gin.Context) {
	if err := a.migrator.Up(c.Request.Context()); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "migrations applied"})
}

func (a *App) runMigrationsDown(c *gin.Context) {
	var body struct {
		Steps int `json:"steps"`
	}
	body.Steps = 1
	if err := c.ShouldBindJSON(&body); err == nil && body.Steps > 0 {
	}

	if err := a.migrator.Down(c.Request.Context(), body.Steps); err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "migrations rolled back"})
}

func (a *App) getMetricsSnapshot(c *gin.Context) {
	tenantID := c.GetString("tenant_id")
	dimensions := map[string]string{}
	for k, v := range c.Request.URL.Query() {
		if len(v) > 0 {
			dimensions[k] = v[0]
		}
	}

	snapshot, err := a.metrics.TakeSnapshot(c.Request.Context(), tenantID, dimensions)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(200, gin.H{"code": 200, "data": snapshot})
}

func (a *App) queryMetrics(c *gin.Context) {
	tenantID := c.GetString("tenant_id")
	start := time.Now().Add(-24 * time.Hour)
	end := time.Now()

	query := monitor.StatsQuery{
		TenantID:   tenantID,
		MetricName: c.Query("metric"),
		Start:      start,
		End:        end,
		Aggregation: c.Query("aggregation"),
	}

	results, err := a.metrics.QueryStats(c.Request.Context(), query)
	if err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(200, gin.H{"code": 200, "data": results})
}

func (a *App) getSystemHealth(c *gin.Context) {
	health := a.metrics.GetSystemHealth(c.Request.Context())
	c.JSON(200, gin.H{"code": 200, "data": health})
}

func parseInt(s string, def int) int {
	if v, err := strconv.Atoi(s); err == nil {
		return v
	}
	return def
}

func _unusedJSONMarshal(v interface{}) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}
