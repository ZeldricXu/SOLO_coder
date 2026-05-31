package handler

import (
	"time"

	"projectservice/internal/service"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

type Handlers struct {
	Base          *Handler
	Vulnerability *VulnerabilityHandler
	Scaffold      *ScaffoldHandler
	Environment   *EnvironmentHandler
	Quality       *QualityHandler
	FeatureFlag   *FeatureFlagHandler
	Catalog       *CatalogHandler
	APIContract   *APIContractHandler
	Document      *DocumentHandler
}

func NewHandlers(
	baseHandler *Handler,
	vulnSvc *service.VulnerabilityService,
	scaffoldSvc *service.ScaffoldService,
	envSvc *service.EnvironmentService,
	qualitySvc *service.QualityService,
	flagSvc *service.FeatureFlagService,
	catalogSvc *service.CatalogService,
	apiContractSvc *service.APIContractService,
	docSvc *service.DocumentService,
) *Handlers {
	return &Handlers{
		Base:          baseHandler,
		Vulnerability: NewVulnerabilityHandler(baseHandler, vulnSvc),
		Scaffold:      NewScaffoldHandler(baseHandler, scaffoldSvc),
		Environment:   NewEnvironmentHandler(baseHandler, envSvc),
		Quality:       NewQualityHandler(baseHandler, qualitySvc),
		FeatureFlag:   NewFeatureFlagHandler(baseHandler, flagSvc),
		Catalog:       NewCatalogHandler(baseHandler, catalogSvc),
		APIContract:   NewAPIContractHandler(baseHandler, apiContractSvc),
		Document:      NewDocumentHandler(baseHandler, docSvc),
	}
}

func SetupRouter(h *Handlers) *gin.Engine {
	r := gin.Default()

	r.Use(gin.Logger())
	r.Use(gin.Recovery())
	r.Use(h.Base.ErrorHandlingMiddleware())
	r.Use(h.Base.MetricsMiddleware())
	r.Use(h.Base.RateLimitMiddleware(100, time.Minute))

	r.GET("/metrics", gin.WrapH(promhttp.Handler()))

	r.GET("/health", func(c *gin.Context) {
		c.JSON(200, gin.H{
			"status": "ok",
			"time":   time.Now().UTC(),
		})
	})

	api := r.Group("/api/v1")
	{
		resources := api.Group("/resources")
		{
			resources.POST("", h.Vulnerability.AnalyzeSBOM)
			resources.GET("/:id/status", h.Environment.GetEnvironmentStatus)
			resources.POST("/batch", h.Scaffold.BatchGenerateProjects)
		}

		vuln := api.Group("/vulnerability")
		{
			vuln.POST("/analyze", h.Vulnerability.AnalyzeSBOM)
			vuln.GET("/cves", h.Vulnerability.QueryCVE)
			vuln.GET("/cves/:cve_id", h.Vulnerability.GetCVEByID)
			vuln.POST("/cache/warmup", h.Vulnerability.WarmUpCache)
			vuln.POST("/cache/invalidate", h.Vulnerability.InvalidateCache)
			// ===== 多级缓存增强路由
			vuln.GET("/cache/stats", h.Vulnerability.GetCacheStats)
			vuln.POST("/cache/warmup-async", h.Vulnerability.WarmUpCacheAsync)
			vuln.POST("/cache/invalidate-pattern", h.Vulnerability.InvalidateCacheByPattern)
			vuln.GET("/cache/health", h.Vulnerability.CheckCacheHealth)
		}

		scaffold := api.Group("/scaffold")
		{
			scaffold.GET("/templates", h.Scaffold.ListTemplates)
			scaffold.POST("/templates", h.Scaffold.CreateTemplate)
			scaffold.GET("/templates/:template_id", h.Scaffold.GetTemplate)
			scaffold.DELETE("/templates/:template_id", h.Scaffold.DeleteTemplate)
			scaffold.GET("/templates/:template_id/questions", h.Scaffold.GetInteractiveQuestions)
			scaffold.POST("/generate", h.Scaffold.GenerateProject)
			scaffold.POST("/generate/batch", h.Scaffold.BatchGenerateProjects)
			scaffold.GET("/projects", h.Scaffold.ListGeneratedProjects)
			scaffold.GET("/projects/:project_id", h.Scaffold.GetGeneratedProject)
			// ===== 批量操作增强路由
			scaffold.GET("/batch/:batch_id/progress", h.Scaffold.GetBatchProgress)
			scaffold.GET("/batch/:batch_id/status", h.Scaffold.GetBatchStatus)
			scaffold.POST("/generate/batch/timeout", h.Scaffold.BatchGenerateWithTimeout)
			scaffold.POST("/generate/coalesce", h.Scaffold.CoalesceAndGenerate)
		}

		environment := api.Group("/environments")
		{
			environment.POST("", h.Environment.CreateEnvironment)
			environment.GET("", h.Environment.ListEnvironments)
			environment.GET("/:env_id", h.Environment.GetEnvironment)
			environment.GET("/:env_id/status", h.Environment.GetEnvironmentStatus)
			environment.PUT("/:env_id/status", h.Environment.UpdateEnvironmentStatus)
			environment.DELETE("/:env_id", h.Environment.DeleteEnvironment)
			environment.POST("/reclaim", h.Environment.ReclaimExpiredEnvironments)
			environment.GET("/usage/statistics", h.Environment.GetUsageStatistics)
			environment.POST("/:env_id/ttl/extend", h.Environment.ExtendTTL)
			// ===== 监控增强路由
			environment.GET("/:env_id/health", h.Environment.GetEnvironmentHealth)
			environment.GET("/:env_id/timing", h.Environment.GetEnvironmentTiming)
			environment.GET("/usage/summary", h.Environment.GetResourceUsageSummary)
			environment.GET("/stats/summary", h.Environment.GetEnvironmentStats)
			environment.GET("/lifecycle/events", h.Environment.GetLifecycleEvents)
		}

		quality := api.Group("/quality")
		{
			quality.POST("/rules", h.Quality.CreateRule)
			quality.GET("/rules", h.Quality.ListRules)
			quality.GET("/rules/:rule_id", h.Quality.GetRule)
			quality.PUT("/rules/:rule_id", h.Quality.UpdateRule)
			quality.DELETE("/rules/:rule_id", h.Quality.DeleteRule)
			quality.POST("/check", h.Quality.RunQualityCheck)
			quality.GET("/reports", h.Quality.ListReports)
			quality.GET("/reports/:report_id", h.Quality.GetReport)
			quality.GET("/gate-configs/:config_id", h.Quality.GetGateConfig)
			quality.PUT("/gate-configs/:config_id", h.Quality.UpdateGateConfig)
		}

		featureflag := api.Group("/feature-flags")
		{
			featureflag.POST("", h.FeatureFlag.CreateFlag)
			featureflag.GET("", h.FeatureFlag.ListFlags)
			featureflag.GET("/:flag_id", h.FeatureFlag.GetFlag)
			featureflag.GET("/key/:key", h.FeatureFlag.GetFlagByKey)
			featureflag.PUT("/:flag_id", h.FeatureFlag.UpdateFlag)
			featureflag.DELETE("/:flag_id", h.FeatureFlag.DeleteFlag)
			featureflag.POST("/evaluate", h.FeatureFlag.EvaluateFlag)
			featureflag.POST("/:flag_id/rollout", h.FeatureFlag.UpdateRollout)

			segments := featureflag.Group("/segments")
			{
				segments.POST("", h.FeatureFlag.CreateSegment)
				segments.GET("", h.FeatureFlag.ListSegments)
				segments.GET("/:segment_id", h.FeatureFlag.GetSegment)
				segments.DELETE("/:segment_id", h.FeatureFlag.DeleteSegment)
				segments.POST("/:segment_id/users", h.FeatureFlag.AddUserToSegment)
				segments.DELETE("/:segment_id/users/:user_id", h.FeatureFlag.RemoveUserFromSegment)
			}
		}

		catalog := api.Group("/catalog")
		{
			catalog.POST("/services", h.Catalog.RegisterService)
			catalog.GET("/services", h.Catalog.ListServices)
			catalog.GET("/services/search", h.Catalog.SearchCatalog)
			catalog.GET("/services/:service_id", h.Catalog.GetService)
			catalog.PUT("/services/:service_id", h.Catalog.UpdateService)
			catalog.DELETE("/services/:service_id", h.Catalog.DeleteService)
			catalog.GET("/services/:service_id/dependencies", h.Catalog.GetDependencyGraph)

			dependencies := catalog.Group("/dependencies")
			{
				dependencies.POST("", h.Catalog.AddDependency)
				dependencies.DELETE("/:dependency_id", h.Catalog.RemoveDependency)
			}
		}

		apicontract := api.Group("/api-contracts")
		{
			apicontract.POST("", h.APIContract.RegisterContract)
			apicontract.GET("", h.APIContract.ListContracts)
			apicontract.GET("/:contract_id", h.APIContract.GetContract)
			apicontract.DELETE("/:contract_id", h.APIContract.DeleteContract)
			apicontract.POST("/validate", h.APIContract.ValidateRequest)

			mock := apicontract.Group("/mock-servers")
			{
				mock.POST("", h.APIContract.CreateMockServer)
				mock.GET("", h.APIContract.ListMockServers)
				mock.GET("/:mock_id", h.APIContract.GetMockServer)
				mock.GET("/:mock_id/status", h.APIContract.GetMockServerStatus)
				mock.POST("/:mock_id/stop", h.APIContract.StopMockServer)
			}
		}

		document := api.Group("/documents")
		{
			document.POST("", h.Document.IndexDocument)
			document.GET("", h.Document.ListDocuments)
			document.GET("/search", h.Document.SearchDocuments)
			document.POST("/sync", h.Document.SyncDocuments)
			document.GET("/:doc_id", h.Document.GetDocument)
			document.PUT("/:doc_id", h.Document.UpdateDocument)
			document.DELETE("/:doc_id", h.Document.DeleteDocument)
		}
	}

	return r
}
