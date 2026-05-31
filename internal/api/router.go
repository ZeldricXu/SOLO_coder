package api

import (
	"github.com/gin-gonic/gin"

	"llmgateway/internal/infrastructure/config"
	"llmgateway/internal/infrastructure/middleware"
	"llmgateway/internal/service/adversarial"
	"llmgateway/internal/service/document"
	"llmgateway/internal/service/evaluation"
	"llmgateway/internal/service/feature_store"
	"llmgateway/internal/service/gateway"
	"llmgateway/internal/service/model_registry"
	"llmgateway/internal/service/prompt_eval"
	"llmgateway/internal/service/scheduler"
)

func SetupRouter(
	cfg *config.Config,
	modelRegistry *model_registry.Service,
	gatewaySvc *gateway.Service,
	schedulerSvc *scheduler.Service,
	promptEval *prompt_eval.Service,
	evaluationSvc *evaluation.Service,
	adversarialSvc *adversarial.Service,
	documentSvc *document.Service,
	featureStoreSvc *feature_store.Service,
) *gin.Engine {
	if cfg.Server.Mode == "release" {
		gin.SetMode(gin.ReleaseMode)
	}

	r := gin.New()

	r.Use(middleware.RequestID())
	r.Use(middleware.RequestLogger())
	r.Use(middleware.Recovery())
	r.Use(middleware.CORSMiddleware())

	if cfg.Security.RateLimit > 0 {
		window := cfg.Security.RateWindow
		if window == 0 {
			window = 60000000000
		}
		r.Use(middleware.RateLimiter(cfg.Security.RateLimit, window))
	}

	if cfg.Security.SecretKey != "" {
		r.Use(middleware.SignatureValidation(cfg.Security.SecretKey))
	}

	handler := NewHandler(
		modelRegistry,
		gatewaySvc,
		schedulerSvc,
		promptEval,
		evaluationSvc,
		adversarialSvc,
		documentSvc,
		featureStoreSvc,
	)

	api := r.Group("/api/v1")
	{
		api.GET("/health", handler.HealthCheck)

		api.POST("/resources", handler.CreateResource)
		api.GET("/resources/:id/status", handler.GetResourceStatus)
		api.POST("/resources/batch", handler.BatchOperation)

		models := api.Group("/models")
		{
			models.POST("", handler.CreateModel)
			models.GET("", handler.ListModels)
			models.GET("/:id", handler.GetModel)
			models.PUT("/:id", handler.UpdateModel)
			models.DELETE("/:id", handler.DeleteModel)

			versions := models.Group("/versions")
			{
				versions.POST("", handler.CreateModelVersion)
				versions.GET("", handler.ListModelVersions)
				versions.GET("/:id", handler.GetModelVersion)
				versions.POST("/:id/promote", handler.PromoteVersion)
			}
		}

		gatewayAPI := api.Group("/gateway")
		{
			gatewayAPI.POST("/infer", handler.Infer)
			gatewayAPI.GET("/providers", handler.ListProviders)
		}

		schedulerAPI := api.Group("/scheduler")
		{
			schedulerAPI.POST("/tasks", handler.SubmitTask)
			schedulerAPI.GET("/tasks", handler.ListTasks)
			schedulerAPI.GET("/tasks/:id", handler.GetTask)
			schedulerAPI.POST("/tasks/:id/cancel", handler.CancelTask)
			schedulerAPI.GET("/gpus", handler.ListGPUs)
			schedulerAPI.GET("/queue-stats", handler.GetQueueStats)
			schedulerAPI.GET("/metrics", handler.GetSchedulerMetrics)
			schedulerAPI.GET("/metrics/prometheus", handler.GetPrometheusMetrics)
			schedulerAPI.GET("/events", handler.GetTaskEvents)
			schedulerAPI.GET("/gpu-history", handler.GetGPUHistory)
		}

		prompts := api.Group("/prompts")
		{
			prompts.POST("", handler.CreatePrompt)
			prompts.GET("", handler.ListPrompts)
			prompts.GET("/:id", handler.GetPrompt)

			versions := prompts.Group("/versions")
			{
				versions.POST("", handler.CreatePromptVersion)
				versions.GET("", handler.ListPromptVersions)
			}
		}

		experiments := api.Group("/experiments")
		{
			experiments.POST("", handler.CreateExperiment)
			experiments.GET("/:id", handler.GetExperiment)
			experiments.POST("/:id/start", handler.StartExperiment)
			experiments.GET("/:id/results", handler.GetExperimentResults)
		}

		evaluationAPI := api.Group("/evaluation")
		{
			datasets := evaluationAPI.Group("/datasets")
			{
				datasets.POST("", handler.CreateDataset)
				datasets.GET("", handler.ListDatasets)
				datasets.GET("/:id", handler.GetDataset)
			}

			evaluationAPI.POST("/start", handler.StartEvaluation)
			evaluationAPI.GET("/metrics", handler.GetEvaluationMetrics)
			evaluationAPI.POST("/compare", handler.CompareModels)
			evaluationAPI.POST("/detect-drift", handler.DetectDrift)
			evaluationAPI.GET("/drift-history", handler.GetDriftHistory)

			evaluationAPI.POST("/stream/start", handler.StartStreamEvaluation)
			evaluationAPI.GET("/stream", handler.ListStreamingEvaluations)
			evaluationAPI.GET("/stream/:id/status", handler.GetStreamingStatus)
			evaluationAPI.POST("/stream/:id/cancel", handler.CancelStreamingEvaluation)
			evaluationAPI.GET("/stream/batch-metrics", handler.GetBatchMetrics)
		}

		adversarialAPI := api.Group("/adversarial")
		{
			adversarialAPI.POST("/strategies", handler.RegisterAttackStrategy)
			adversarialAPI.GET("/strategies", handler.ListAttackStrategies)
			adversarialAPI.POST("/generate", handler.GenerateAdversarialPrompt)

			assessments := adversarialAPI.Group("/assessments")
			{
				assessments.POST("", handler.StartSecurityAssessment)
				assessments.GET("", handler.ListAssessments)
				assessments.GET("/:id", handler.GetAssessment)
			}

			adversarialAPI.GET("/vulnerabilities", handler.GetVulnerabilities)

			cache := adversarialAPI.Group("/cache")
			{
				cache.GET("/stats", handler.GetAdversarialCacheStats)
				cache.POST("/clear", handler.ClearAdversarialCache)
				cache.POST("/config", handler.SetAdversarialCacheConfig)
			}
		}

		documentAPI := api.Group("/documents")
		{
			documentAPI.POST("", handler.UploadDocument)
			documentAPI.GET("", handler.ListDocuments)
			documentAPI.GET("/:id", handler.GetDocument)
			documentAPI.GET("/chunks", handler.GetDocumentChunks)

			pipelines := documentAPI.Group("/pipelines")
			{
				pipelines.POST("", handler.CreatePipeline)
				pipelines.GET("", handler.ListPipelines)
				pipelines.GET("/:id", handler.GetPipeline)
				pipelines.POST("/execute", handler.ExecutePipeline)
			}

			executions := documentAPI.Group("/executions")
			{
				executions.GET("/:id", handler.GetExecution)
			}
		}

		featureAPI := api.Group("/features")
		{
			featureAPI.POST("", handler.RegisterFeature)
			featureAPI.GET("", handler.ListFeatures)
			featureAPI.GET("/:id", handler.GetFeature)
			featureAPI.PUT("/:id", handler.UpdateFeature)

			featureAPI.POST("/values", handler.InsertFeatureValue)
			featureAPI.POST("/values/batch", handler.BatchInsertFeatureValues)
			featureAPI.GET("/values/online", handler.GetOnlineFeatureValue)

			sets := featureAPI.Group("/sets")
			{
				sets.POST("", handler.CreateFeatureSet)
				sets.GET("/:id", handler.GetFeatureSet)
				sets.GET("/values", handler.GetFeatureSetValues)
			}

			views := featureAPI.Group("/views")
			{
				views.POST("", handler.CreateFeatureView)
			}

			featureAPI.GET("/compare-online-offline", handler.CompareOnlineOffline)
		}
	}

	return r
}
