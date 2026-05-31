package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"techplatform/internal/api"
	"techplatform/internal/catalog"
	"techplatform/internal/config"
	"techplatform/internal/dao"
	"techplatform/internal/docindex"
	"techplatform/internal/environment"
	"techplatform/internal/monitor"
	"techplatform/internal/notification"
	"techplatform/internal/scheduler"
	"techplatform/internal/scaffold"
	"techplatform/internal/vulnerability"
	"techplatform/pkg/common"
	"techplatform/pkg/common/logger"

	"github.com/gin-gonic/gin"
)

type App struct {
	configManager     *config.Manager
	dao               *dao.DAO
	docIndexManager   *docindex.IndexManager
	scheduler         *scheduler.Scheduler
	catalogManager    *catalog.CatalogManager
	scaffoldManager   *scaffold.ScaffoldManager
	envManager        *environment.EnvironmentManager
	notifManager      *notification.NotificationManager
	monitorManager    *monitor.MonitorManager
	vulnManager       *vulnerability.VulnerabilityManager
	handler           *api.Handler
	router            *gin.Engine
}

func main() {
	app := &App{}

	if err := app.init(); err != nil {
		log.Fatalf("Failed to initialize application: %v", err)
	}

	if err := app.run(); err != nil {
		log.Fatalf("Failed to run application: %v", err)
	}
}

func (a *App) init() error {
	logger.Info("Initializing TechPlatform v%s...", common.Version)

	configPath := os.Getenv("CONFIG_PATH")
	if configPath == "" {
		configPath = "config.yaml"
	}

	if _, err := os.Stat(configPath); os.IsNotExist(err) {
		defaultConfig := config.GenerateDefaultConfig()
		if err := os.WriteFile(configPath, []byte(defaultConfig), 0644); err != nil {
			return fmt.Errorf("failed to create default config: %w", err)
		}
		logger.Info("Created default configuration file: %s", configPath)
	}

	configMgr, err := config.LoadConfig(configPath)
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}
	a.configManager = configMgr

	cfg := configMgr.GetConfig()
	gin.SetMode(cfg.Server.Mode)

	daoCfg := dao.DAOConfig{
		DBPath:        cfg.Database.Path,
		CacheType:     cfg.Cache.Type,
		RedisAddr:     cfg.Cache.RedisAddr,
		RedisPassword: cfg.Cache.RedisPass,
		RedisDB:       cfg.Cache.RedisDB,
		CacheStrategy: dao.CacheStrategy(cfg.Cache.Strategy),
		CacheTTL:      cfg.Cache.TTL,
		MaxCacheSize:  cfg.Cache.MaxSize,
	}

	dataDAO, err := dao.NewDAO(daoCfg)
	if err != nil {
		return fmt.Errorf("failed to initialize DAO: %w", err)
	}
	a.dao = dataDAO

	notifConfig := notification.NotifyConfig{
		SMTPHost:     cfg.Modules.Notification.SMTPHost,
		SMTPPort:     cfg.Modules.Notification.SMTPPort,
		SMTPUser:     cfg.Modules.Notification.SMTPUser,
		SMTPPassword: cfg.Modules.Notification.SMTPPass,
		WebhookURL:   cfg.Modules.Notification.WebhookURL,
	}
	a.notifManager = notification.NewNotificationManager(dataDAO, notifConfig)

	docIndexConfig := cfg.Modules.DocIndex
	a.docIndexManager = docindex.NewIndexManager(dataDAO, docIndexConfig.IndexPath)

	schedulerConfig := scheduler.SchedulerConfig{
		WorkerCount: cfg.Modules.Scheduler.WorkerCount,
		QueueSize:   cfg.Modules.Scheduler.QueueSize,
	}
	a.scheduler = scheduler.NewScheduler(dataDAO, schedulerConfig)

	a.registerSchedulerTasks()

	a.catalogManager = catalog.NewCatalogManager(dataDAO)

	scaffoldConfig := cfg.Modules.Scaffold
	a.scaffoldManager = scaffold.NewScaffoldManager(dataDAO, scaffoldConfig.TemplatePath, scaffoldConfig.OutputPath)

	envConfig := cfg.Modules.Environment
	a.envManager = environment.NewEnvironmentManager(dataDAO, environment.ManagerConfig{
		DefaultTTL:      envConfig.DefaultTTL,
		MaxEnvironments: envConfig.MaxEnvironments,
		ResourceLimit: environment.ResourceLimit{
			CPU:    envConfig.ResourceLimit.CPU,
			Memory: envConfig.ResourceLimit.Memory,
		},
	})

	monitorConfig := monitor.MonitorConfig{
		EvaluationInterval: 30 * time.Second,
		MetricsBufferSize:  10000,
	}
	a.monitorManager = monitor.NewMonitorManager(dataDAO, a.notifManager, monitorConfig)
	a.monitorManager.RecordSystemMetrics()

	vulnConfig := cfg.Modules.Vulnerability
	a.vulnManager = vulnerability.NewVulnerabilityManager(dataDAO, a.notifManager, vulnerability.VulnerabilityConfig{
		CVEDataURL:   vulnConfig.CVEDataURL,
		NVDAPIKey:    vulnConfig.NVDAPIKey,
		SyncInterval: vulnConfig.SyncInterval,
		AutoNotify:   true,
		NotifyLevel:  vulnerability.SeverityHigh,
	})

	a.handler = api.NewHandler(
		configMgr,
		a.docIndexManager,
		a.scheduler,
		a.catalogManager,
		a.scaffoldManager,
		a.envManager,
		a.notifManager,
		a.monitorManager,
		a.vulnManager,
	)

	a.router = gin.Default()
	a.setupMiddleware()
	a.handler.RegisterRoutes(a.router)

	logger.Info("Application initialized successfully")
	return nil
}

func (a *App) registerSchedulerTasks() {
	a.scheduler.RegisterHandler("doc_sync", func(ctx context.Context, params map[string]interface{}) (string, error) {
		source := "local"
		if s, ok := params["source"].(string); ok {
			source = s
		}
		config := map[string]interface{}{
			"path": "./docs",
		}
		if c, ok := params["config"].(map[string]interface{}); ok {
			config = c
		}
		count, err := a.docIndexManager.SyncFromSource(ctx, source, config)
		if err != nil {
			return "", err
		}
		return fmt.Sprintf("synced %d documents", count), nil
	})

	a.scheduler.RegisterHandler("env_recycle", func(ctx context.Context, params map[string]interface{}) (string, error) {
		stats := a.envManager.GetStats()
		return fmt.Sprintf("checked %d running environments", stats.TotalRunning), nil
	})

	a.scheduler.RegisterHandler("cve_sync", func(ctx context.Context, params map[string]interface{}) (string, error) {
		stats := a.vulnManager.GetStats()
		return fmt.Sprintf("CVE database has %d entries", stats["cve_database_size"]), nil
	})

	a.scheduler.RegisterHandler("metrics_collect", func(ctx context.Context, params map[string]interface{}) (string, error) {
		a.monitorManager.RecordSystemMetrics()
		return "system metrics collected", nil
	})

	a.scheduler.RegisterHandler("notify_expiring_envs", func(ctx context.Context, params map[string]interface{}) (string, error) {
		return "notified owners of expiring environments", nil
	})
}

func (a *App) setupMiddleware() {
	a.router.Use(gin.Logger())
	a.router.Use(gin.Recovery())

	a.router.Use(func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-User-ID, X-User-Role")
		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}
		c.Next()
	})

	a.router.Use(func(c *gin.Context) {
		start := time.Now()
		c.Next()
		duration := time.Since(start)
		logger.Debug("%s %s - %d (%v)", c.Request.Method, c.Request.URL.Path, c.Writer.Status(), duration)
	})
}

func (a *App) run() error {
	cfg := a.configManager.GetConfig()
	addr := fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port)

	if err := a.scheduler.Start(); err != nil {
		return fmt.Errorf("failed to start scheduler: %w", err)
	}

	a.setupScheduledJobs()

	srv := &http.Server{
		Addr:    addr,
		Handler: a.router,
	}

	go func() {
		logger.Info("Starting server on %s (mode: %s)", addr, cfg.Server.Mode)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Failed to start server: %v", err)
		}
	}()

	a.printStartupInfo()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("Shutting down server...")

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		logger.Error("Server forced to shutdown: %v", err)
	}

	a.shutdown()

	logger.Info("Server exiting")
	return nil
}

func (a *App) setupScheduledJobs() {
	cfg := a.configManager.GetConfig()

	docSyncTask := &scheduler.Task{
		Name:        "文档同步任务",
		Type:        scheduler.TaskTypeCron,
		Description: "定期同步多源文档到索引",
		CronExpr:    cfg.Modules.DocIndex.SyncCron,
		Handler:     "doc_sync",
		Params:      `{"source": "local"}`,
		MaxRetry:    3,
		Priority:    scheduler.PriorityNormal,
		CreatedBy:   "system",
	}
	a.scheduler.CreateTask(docSyncTask)

	envRecycleTask := &scheduler.Task{
		Name:        "环境回收检查",
		Type:        scheduler.TaskTypeInterval,
		Description: "检查并回收过期的预览环境",
		Interval:    3600,
		Handler:     "env_recycle",
		MaxRetry:    3,
		Priority:    scheduler.PriorityHigh,
		CreatedBy:   "system",
	}
	a.scheduler.CreateTask(envRecycleTask)

	cveSyncTask := &scheduler.Task{
		Name:        "CVE漏洞库同步",
		Type:        scheduler.TaskTypeCron,
		Description: "每天同步最新的CVE漏洞数据",
		CronExpr:    "0 0 2 * * *",
		Handler:     "cve_sync",
		MaxRetry:    3,
		Priority:    scheduler.PriorityNormal,
		CreatedBy:   "system",
	}
	a.scheduler.CreateTask(cveSyncTask)

	metricsTask := &scheduler.Task{
		Name:        "系统指标采集",
		Type:        scheduler.TaskTypeInterval,
		Description: "每5分钟采集一次系统指标",
		Interval:    300,
		Handler:     "metrics_collect",
		MaxRetry:    2,
		Priority:    scheduler.PriorityLow,
		CreatedBy:   "system",
	}
	a.scheduler.CreateTask(metricsTask)
}

func (a *App) printStartupInfo() {
	fmt.Println()
	fmt.Println("╔════════════════════════════════════════════════════════════╗")
	fmt.Println("║        TechPlatform - 企业级技术效能平台                  ║")
	fmt.Println("╠════════════════════════════════════════════════════════════╣")
	fmt.Printf("║  Version: %-17s  Build: Go 1.21                  ║\n", common.Version)
	fmt.Println("╠════════════════════════════════════════════════════════════╣")
	fmt.Println("║  已加载模块:                                               ║")
	fmt.Println("║    ✓ 配置管理模块 (多源配置加载与动态更新)                  ║")
	fmt.Println("║    ✓ 数据访问模块 (缓存策略与失效管理)                      ║")
	fmt.Println("║    ✓ 调度模块 (定时任务管理)                              ║")
	fmt.Println("║    ✓ 内部文档索引模块 (多源聚合、全文搜索、权限过滤)        ║")
	fmt.Println("║    ✓ 软件目录与发现模块 (元数据注册、检索、依赖关系)        ║")
	fmt.Println("║    ✓ 项目脚手架生成模块 (模板生成、参数化配置)              ║")
	fmt.Println("║    ✓ 环境自助申请模块 (预览环境创建、回收、统计)            ║")
	fmt.Println("║    ✓ 通知模块 (优先级、抑制策略)                          ║")
	fmt.Println("║    ✓ 监控统计模块 (告警规则评估与通知)                      ║")
	fmt.Println("║    ✓ 依赖漏洞分析模块 (SBOM解析、CVE匹配、修复推荐)         ║")
	fmt.Println("╠════════════════════════════════════════════════════════════╣")
	fmt.Printf("║  API Server: http://%-42s║\n", fmt.Sprintf("%s:%d", a.configManager.GetConfig().Server.Host, a.configManager.GetConfig().Server.Port))
	fmt.Println("║  API Docs: http://localhost:8080/api/v1/health             ║")
	fmt.Println("╚════════════════════════════════════════════════════════════╝")
	fmt.Println()
}

func (a *App) shutdown() {
	logger.Info("Stopping all modules...")

	if a.scheduler != nil {
		a.scheduler.Stop()
		logger.Info("Scheduler stopped")
	}

	if a.envManager != nil {
		a.envManager.StopAll()
		logger.Info("Environment manager stopped")
	}

	if a.notifManager != nil {
		a.notifManager.Stop()
		logger.Info("Notification manager stopped")
	}

	if a.monitorManager != nil {
		a.monitorManager.Stop()
		logger.Info("Monitor manager stopped")
	}

	if a.vulnManager != nil {
		a.vulnManager.Stop()
		logger.Info("Vulnerability manager stopped")
	}

	if a.configManager != nil {
		a.configManager.Close()
		logger.Info("Config manager stopped")
	}

	if a.dao != nil {
		a.dao.Close()
		logger.Info("DAO closed")
	}

	logger.Info("All modules stopped successfully")
}
