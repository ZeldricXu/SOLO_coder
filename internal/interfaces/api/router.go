package api

import (
	"github.com/gin-gonic/gin"
)

func SetupRouter(handler *APIHandler) *gin.Engine {
	r := gin.Default()

	api := r.Group("/api/v1")
	{
		api.POST("/resources", handler.CreateResource)
		api.GET("/resources/:id/status", handler.GetResourceStatus)
		api.POST("/resources/batch", handler.ExecuteBatch)

		api.POST("/process", handler.ProcessData)
		api.GET("/runs/:id", handler.GetRunStatus)

		api.POST("/backups", handler.CreateBackup)
		api.POST("/backups/restore", handler.RestoreBackup)
		api.GET("/backups", handler.ListBackups)

		api.GET("/audit/verify", handler.VerifyAudit)

		api.GET("/metrics", handler.GetMetrics)

		api.POST("/data/masked", handler.GetMaskedData)

		api.POST("/schema/migrate", handler.MigrateSchema)
		api.GET("/schema/version", handler.GetSchemaVersion)
	}

	r.GET("/health", handler.HealthCheck)

	return r
}
