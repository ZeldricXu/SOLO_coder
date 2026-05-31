package main

import (
	"context"
	"depguard/cache"
	"depguard/config"
	"depguard/database"
	"depguard/events"
	"depguard/logger"
	"depguard/middleware"
	"depguard/models"
	"depguard/modules/apicontract"
	"depguard/modules/docindex"
	"depguard/modules/environments"
	"depguard/modules/featureflags"
	"depguard/modules/qualitygate"
	"depguard/modules/scaffold"
	"depguard/modules/softwarecatalog"
	"depguard/modules/vulnerability"
	"depguard/search"
	"go.uber.org/zap"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
)

func main() {
	cfg := config.Load()
	logger.Init(cfg)
	defer logger.Sync()

	log := logger.Get()
	log.Info("DepGuard - 依赖漏洞扫描与SBOM管理系统启动中")
	log.Info("加载配置完成",
		zap.String("server_port", cfg.ServerPort),
		zap.String("log_level", cfg.LogLevel),
	)

	log.Info("初始化数据库连接...")
	if err := database.Init(cfg); err != nil {
		log.Fatal("数据库初始化失败", zap.Error(err))
	}
	log.Info("数据库连接成功")

	log.Info("执行数据库迁移...")
	if err := runMigrations(); err != nil {
		log.Fatal("数据库迁移失败", zap.Error(err))
	}
	log.Info("数据库迁移完成")

	log.Info("初始化Redis连接...")
	if err := cache.Init(cfg); err != nil {
		log.Warn("Redis连接失败，使用内存模式", zap.Error(err))
	} else {
		log.Info("Redis连接成功")
	}

	log.Info("初始化事件总线...")
	events.Init()
	log.Info("事件总线初始化完成")

	log.Info("初始化全文搜索引擎...")
	if err := search.Init(cfg); err != nil {
		log.Warn("全文搜索引擎初始化失败", zap.Error(err))
	} else {
		log.Info("全文搜索引擎初始化完成")
	}

	log.Info("初始化脚手架模板...")
	scaffoldSvc := scaffold.NewService()
	if err := scaffoldSvc.InitDefaultTemplates(); err != nil {
		log.Warn("脚手架模板初始化失败", zap.Error(err))
	} else {
		log.Info("脚手架模板初始化完成")
	}

	log.Info("创建HTTP服务器...")
	if cfg.LogLevel == "production" || cfg.LogLevel == "release" {
		gin.SetMode(gin.ReleaseMode)
	}

	router := gin.New()
	router.Use(
		middleware.Recovery(),
		middleware.RequestLogger(),
		middleware.CORS(),
	)

	router.GET("/health", healthCheck)
	router.GET("/api/v1/health", healthCheck)
	router.GET("/api/v1/info", systemInfo)

	apiV1 := router.Group("/api/v1")

	log.Info("注册模块路由...")

	docHandler := docindex.NewHandler()
	docindexGroup := apiV1.Group("/doc-index")
	docHandler.RegisterRoutes(docindexGroup)
	log.Info("  ✅ 内部文档索引模块")

	qualityHandler := qualitygate.NewHandler()
	qualityGroup := apiV1.Group("/quality-gate")
	qualityHandler.RegisterRoutes(qualityGroup)
	log.Info("  ✅ 代码质量门禁模块")

	ffHandler := featureflags.NewHandler()
	ffGroup := apiV1.Group("/feature-flags")
	ffHandler.RegisterRoutes(ffGroup)
	log.Info("  ✅ 特性开关管理模块")

	envHandler := environments.NewHandler()
	envGroup := apiV1.Group("/environments")
	envHandler.RegisterRoutes(envGroup)
	log.Info("  ✅ 环境自助申请模块")

	catalogHandler := softwarecatalog.NewHandler()
	catalogGroup := apiV1.Group("/software-catalog")
	catalogHandler.RegisterRoutes(catalogGroup)
	log.Info("  ✅ 软件目录与发现模块")

	scaffoldHandler := scaffold.NewHandler()
	scaffoldGroup := apiV1.Group("/scaffold")
	scaffoldHandler.RegisterRoutes(scaffoldGroup)
	log.Info("  ✅ 项目脚手架生成模块")

	contractHandler := apicontract.NewHandler()
	contractGroup := apiV1.Group("/api-contract")
	contractHandler.RegisterRoutes(contractGroup)
	log.Info("  ✅ API契约测试模块")

	vulnHandler := vulnerability.NewHandler()
	vulnGroup := apiV1.Group("/vulnerability")
	vulnHandler.RegisterRoutes(vulnGroup)
	log.Info("  ✅ 依赖漏洞分析模块")

	registerCommonRoutes(apiV1)

	log.Info("所有模块路由注册完成")

	server := &http.Server{
		Addr:    ":" + cfg.ServerPort,
		Handler: router,
	}

	go func() {
		log.Info("HTTP服务器启动中", zap.String("port", cfg.ServerPort))
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal("服务器启动失败", zap.Error(err))
		}
	}()

	log.Info("═══════════════════════════════════════════════════════════════════")
	log.Info("  DepGuard - 依赖漏洞扫描与SBOM管理系统已启动")
	log.Info("  访问地址: http://localhost:" + cfg.ServerPort)
	log.Info("  健康检查: http://localhost:" + cfg.ServerPort + "/health")
	log.Info("═══════════════════════════════════════════════════════════════════")

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Info("收到关闭信号，正在优雅关闭...")

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := server.Shutdown(ctx); err != nil {
		log.Fatal("服务器强制关闭", zap.Error(err))
	}

	log.Info("数据库连接关闭...")
	db, _ := database.Get().DB()
	if db != nil {
		db.Close()
	}

	log.Info("服务器已优雅关闭")
}

func runMigrations() error {
	db := database.Get()

	log := logger.Get()
	log.Info("自动迁移数据库模型...")

	modelsToMigrate := []interface{}{
		&models.Resource{},
		&models.ConfigRecord{},
		&models.RunInstance{},
		&models.MetricsSnapshot{},

		&docindex.Document{},
		&docindex.DocumentSource{},
		&docindex.SyncJob{},

		&qualitygate.AnalysisRule{},
		&qualitygate.QualityProfile{},
		&qualitygate.QualityGate{},
		&qualitygate.AnalysisReport{},

		&featureflags.FeatureFlag{},
		&featureflags.UserSegment{},
		&featureflags.RolloutRule{},

		&environments.Environment{},
		&environments.EnvironmentRequest{},
		&environments.UsageRecord{},
		&environments.DailyStats{},

		&softwarecatalog.Service{},
		&softwarecatalog.ServiceVersion{},
		&softwarecatalog.Library{},
		&softwarecatalog.Dependency{},

		&scaffold.Template{},

		&apicontract.APISchema{},
		&apicontract.ValidationResult{},
		&apicontract.MockServer{},
		&apicontract.ContractTest{},
		&apicontract.TestRun{},

		&vulnerability.SBOM{},
		&vulnerability.CVEEntry{},
		&vulnerability.VulnerabilityScan{},
		&vulnerability.RemediationReport{},
	}

	for _, model := range modelsToMigrate {
		if err := db.AutoMigrate(model); err != nil {
			return err
		}
	}

	log.Info("数据库迁移完成")
	return nil
}

func healthCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":    "ok",
		"service":   "DepGuard",
		"timestamp": time.Now().UTC().Format(time.RFC3339),
		"version":   "1.0.0",
	})
}

func systemInfo(c *gin.Context) {
	c.JSON(http.StatusOK, models.SuccessResponse(gin.H{
		"service": "DepGuard - 依赖漏洞扫描与SBOM管理系统",
		"version": "1.0.0",
		"modules": []string{
			"内部文档索引模块",
			"代码质量门禁模块",
			"特性开关管理模块",
			"环境自助申请模块",
			"软件目录与发现模块",
			"项目脚手架生成模块",
			"API契约测试模块",
			"依赖漏洞分析模块",
		},
		"endpoints": gin.H{
			"doc-index":      "/api/v1/doc-index",
			"quality-gate":   "/api/v1/quality-gate",
			"feature-flags":  "/api/v1/feature-flags",
			"environments":   "/api/v1/environments",
			"software-catalog": "/api/v1/software-catalog",
			"scaffold":       "/api/v1/scaffold",
			"api-contract":   "/api/v1/api-contract",
			"vulnerability":  "/api/v1/vulnerability",
		},
	}))
}

func registerCommonRoutes(r *gin.RouterGroup) {
	resources := r.Group("/resources")
	{
		resources.POST("", createResource)
		resources.GET("/:id/status", getResourceStatus)
		resources.POST("/batch", batchOperations)
	}
}

func createResource(c *gin.Context) {
	var req struct {
		Type   string                 `json:"type" binding:"required"`
		Config map[string]interface{} `json:"config"`
		Labels map[string]string      `json:"labels"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	resource := &models.Resource{
		ID:         "rsc_" + time.Now().Format("150405"),
		Type:       req.Type,
		Status:     "provisioning",
		Attributes: req.Config,
		CreatedAt:  time.Now(),
		UpdatedAt:  time.Now(),
	}

	if err := database.Get().WithContext(c.Request.Context()).Create(resource).Error; err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusCreated, models.CreatedResponse(gin.H{
		"id":     resource.ID,
		"status": resource.Status,
	}))
}

func getResourceStatus(c *gin.Context) {
	id := c.Param("id")
	var resource models.Resource
	if err := database.Get().WithContext(c.Request.Context()).First(&resource, "id = ?", id).Error; err != nil {
		c.JSON(http.StatusNotFound, models.ErrorResponse(404, "Resource not found"))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(gin.H{
		"id":       resource.ID,
		"status":   resource.Status,
		"progress": 0.8,
	}))
}

func batchOperations(c *gin.Context) {
	var req struct {
		Operations []struct {
			Action string `json:"action" binding:"required"`
			ID     string `json:"id" binding:"required"`
		} `json:"operations" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	results := make([]gin.H, 0, len(req.Operations))
	for _, op := range req.Operations {
		results = append(results, gin.H{
			"id":      op.ID,
			"action":  op.Action,
			"success": true,
		})
	}

	c.JSON(http.StatusOK, models.SuccessResponse(gin.H{
		"batch_id": "batch_" + time.Now().Format("150405"),
		"results":  results,
	}))
}
