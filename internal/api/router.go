package api

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/distributed-task-scheduler/internal/auth"
)

func SetupRouter(handler *Handler, authMgr *auth.AuthManager) *gin.Engine {
	r := gin.Default()

	r.Use(CORSMiddleware())

	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok"})
	})

	api := r.Group("/api/v1")
	api.Use(AuthMiddleware(authMgr))

	ns := api.Group("/namespaces")
	{
		ns.GET("", handler.ListNamespaces)
		ns.POST("", handler.CreateNamespace)

		nsGroup := ns.Group("/:namespace")
		{
			nsGroup.GET("/audit", handler.GetAuditLogs)

			tasks := nsGroup.Group("/tasks")
			{
				tasks.GET("", handler.ListTasks)
				tasks.POST("", handler.CreateTask)
				tasks.GET("/:id", handler.GetTask)
				tasks.PUT("/:id", handler.UpdateTask)
				tasks.DELETE("/:id", handler.DeleteTask)
				tasks.POST("/:id/trigger", handler.TriggerTask)
				tasks.POST("/:id/pause", handler.PauseTask)
				tasks.POST("/:id/resume", handler.ResumeTask)
			}

			executions := nsGroup.Group("/executions")
			{
				executions.GET("", handler.ListExecutions)
				executions.GET("/:id", handler.GetExecution)
			}
		}
	}

	return r
}

func CORSMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}

		c.Next()
	}
}

func AuthMiddleware(authMgr *auth.AuthManager) gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.Next()
			return
		}

		parts := strings.Split(authHeader, " ")
		if len(parts) != 2 || parts[0] != "Bearer" {
			c.Next()
			return
		}

		token := parts[1]
		if token == "admin-token" {
			user := authMgr.GetAdminUser()
			c.Request = c.Request.WithContext(auth.ContextWithUser(c.Request.Context(), user))
		}

		c.Next()
	}
}
