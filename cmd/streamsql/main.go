package main

import (
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/gin-gonic/gin"
	"streamsql/internal/api"
	"streamsql/internal/common/logger"
	"streamsql/internal/engine"
	"streamsql/internal/gateway"
)

func main() {
	port := 8080

	logger.InitLogger()
	defer logger.Sync()

	logger.Sugar().Info("==================================================")
	logger.Sugar().Info("  StreamSQL - 流式SQL计算执行引擎")
	logger.Sugar().Info("  Version: 1.0.0")
	logger.Sugar().Info("==================================================")

	coreEngine := engine.NewCoreEngine()
	coreEngine.Start()
	defer coreEngine.Stop()

	apiGateway := gateway.NewAPIGateway()
	handler := api.NewAPIHandler(coreEngine)

	setupRoutes(apiGateway, handler)

	logger.Sugar().Infof("Starting server on port %d", port)
	logger.Sugar().Info("API Documentation:")
	logger.Sugar().Info("  GET    /api/v1/health")
	logger.Sugar().Info("  POST   /api/v1/query/execute")
	logger.Sugar().Info("  GET    /api/v1/query/queries")
	logger.Sugar().Info("  POST   /api/v1/query/parse")
	logger.Sugar().Info("  GET    /api/v1/quality/rules")
	logger.Sugar().Info("  POST   /api/v1/quality/rules")
	logger.Sugar().Info("  GET    /api/v1/meta/sources")
	logger.Sugar().Info("  POST   /api/v1/meta/sources")
	logger.Sugar().Info("  GET    /api/v1/lineage/dag")
	logger.Sugar().Info("  GET    /api/v1/vector/indexes")
	logger.Sugar().Info("  POST   /api/v1/vector/indexes")
	logger.Sugar().Info("==================================================")

	go func() {
		if err := apiGateway.Start(port); err != nil {
			log.Fatalf("Failed to start server: %v", err)
		}
	}()

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	logger.Sugar().Info("Shutting down StreamSQL...")
	logger.Sugar().Info("StreamSQL stopped successfully")
}

func setupRoutes(g *gateway.APIGateway, h *api.APIHandler) {
	g.GETRouter().Use(func(c *gin.Context) {
		c.Set("gateway", g)
		c.Next()
	})

	apiV1 := g.GETRouter().Group("/api/v1")
	{
		apiV1.GET("/health", h.Health)
		apiV1.GET("/gateway/stats", h.GetGatewayStats)

		query := apiV1.Group("/query")
		{
			query.POST("/execute", h.ExecuteQuery)
			query.GET("/queries", h.ListQueries)
			query.GET("/queries/:id", h.GetQuery)
			query.DELETE("/queries/:id", h.DeleteQuery)
			query.POST("/parse", h.ParseSQL)
			query.POST("/optimize", h.OptimizePlan)
			query.POST("/physical", h.GeneratePhysicalPlan)
		}

		quality := apiV1.Group("/quality")
		{
			quality.GET("/rules", h.ListQualityRules)
			quality.POST("/rules", h.CreateQualityRule)
			quality.GET("/rules/:id", h.GetQualityRule)
			quality.PUT("/rules/:id", h.UpdateQualityRule)
			quality.DELETE("/rules/:id", h.DeleteQualityRule)
			quality.POST("/rules/:id/start", h.StartQualityRule)
			quality.POST("/rules/:id/stop", h.StopQualityRule)
			quality.POST("/rules/:id/execute", h.ExecuteQualityRule)
			quality.GET("/anomalies", h.ListQualityAnomalies)
		}

		meta := apiV1.Group("/meta")
		{
			meta.GET("/sources", h.ListDataSources)
			meta.POST("/sources", h.CreateDataSource)
			meta.GET("/sources/:id", h.GetDataSource)
			meta.PUT("/sources/:id", h.UpdateDataSource)
			meta.DELETE("/sources/:id", h.DeleteDataSource)
			meta.POST("/sources/:id/test", h.TestDataSource)
			meta.POST("/sources/:id/crawl", h.StartCrawl)
			meta.GET("/tasks", h.ListCrawlTasks)
			meta.GET("/tasks/:id", h.GetCrawlTask)
			meta.GET("/schemas", h.ListSchemas)
			meta.GET("/schemas/:id", h.GetSchema)
			meta.GET("/tables/search", h.SearchTables)
		}

		lineage := apiV1.Group("/lineage")
		{
			lineage.POST("/parse", h.ParseLineage)
			lineage.GET("/parsed", h.ListLineage)
			lineage.GET("/dag", h.GetLineageDAG)
			lineage.GET("/upstream/:node", h.GetUpstream)
			lineage.GET("/downstream/:node", h.GetDownstream)
			lineage.GET("/stats", h.GetLineageStats)
		}

		vector := apiV1.Group("/vector")
		{
			vector.GET("/indexes", h.ListVectorIndexes)
			vector.POST("/indexes", h.CreateVectorIndex)
			vector.POST("/indexes/:name/add", h.AddToVectorIndex)
			vector.POST("/indexes/:name/search", h.SearchVectorIndex)
			vector.POST("/indexes/:name/build", h.BuildVectorIndex)
		}
	}

	g.GETRouter().GET("/", func(c *gin.Context) {
		c.JSON(200, gin.H{
			"name":    "StreamSQL 流式SQL计算执行引擎",
			"version": "1.0.0",
			"status":  "running",
			"docs":    "/api/v1/health",
		})
	})
}
