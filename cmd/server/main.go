package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	v1 "github.com/enterprise/config-platform/api/v1"
	"github.com/enterprise/config-platform/internal/logging"
	"github.com/enterprise/config-platform/internal/middleware"
	"github.com/enterprise/config-platform/internal/storage"
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

var (
	version   = "dev"
	buildTime = "unknown"
	gitCommit = "unknown"
	appEnv    = "dev"
)

func getEnv() string {
	if env := os.Getenv("APP_ENV"); env != "" {
		return env
	}
	return appEnv
}

func setGinMode() {
	env := getEnv()
	switch env {
	case "prod":
		gin.SetMode(gin.ReleaseMode)
	case "staging":
		gin.SetMode(gin.TestMode)
	default:
		gin.SetMode(gin.DebugMode)
	}
}

func main() {
	setGinMode()

	logging.Init("logs/app.log")
	defer logging.Sync()

	storage.InitDB()

	logging.Info("Starting Enterprise Config Platform",
		zap.String("version", version),
		zap.String("build_time", buildTime),
		zap.String("git_commit", gitCommit),
		zap.String("env", getEnv()),
	)

	r := gin.New()
	r.Use(gin.Recovery())
	r.Use(middleware.CORSMiddleware())
	r.Use(middleware.TraceIDMiddleware())
	r.Use(middleware.MetricsMiddleware())
	r.Use(middleware.RateLimitMiddleware())

	api := r.Group("/api/v1")
	{
		api.POST("/resources", v1.CreateResource)
		api.GET("/resources/:id/status", v1.GetResourceStatus)
		api.POST("/resources/batch", v1.BatchOperations)
		api.POST("/execute", v1.ExecuteHandler)

		configs := api.Group("/configs")
		{
			configs.GET("", v1.ListConfigs)
			configs.POST("/:namespace", v1.CreateConfig)
			configs.GET("/:namespace", v1.GetConfig)
			configs.DELETE("/:namespace", v1.DeleteConfig)
			configs.GET("/cache/stats", v1.GetConfigCacheStats)
			configs.GET("/cache/items", v1.GetConfigCachedItems)
			configs.POST("/cache/invalidate", v1.InvalidateConfigCache)
			configs.GET("/cache/config", v1.GetConfigCacheConfig)
			configs.PUT("/cache/config", v1.SetConfigCacheConfig)
		}

		certs := api.Group("/certificates")
		{
			certs.GET("", v1.ListCertificates)
			certs.POST("", v1.IssueCertificate)
			certs.POST("/:id/revoke", v1.RevokeCertificate)
			certs.GET("/rotation-policy", v1.GetRotationPolicy)
			certs.PUT("/rotation-policy", v1.SetRotationPolicy)
			certs.GET("/root-ca", v1.GetRootCA)
			certs.GET("/stats", v1.GetCertManagerStats)
		}

		sidecars := api.Group("/sidecars")
		{
			sidecars.GET("/configs", v1.ListSidecarConfigs)
			sidecars.POST("/configs", v1.CreateSidecarConfig)
			sidecars.POST("/inject", v1.InjectSidecar)
			sidecars.GET("/instances", v1.ListSidecarInstances)
			sidecars.POST("/configs/:id/hot-update", v1.HotUpdateSidecar)
		}

		gateway := api.Group("/gateway")
		{
			gateway.GET("/routes", v1.ListRoutes)
			gateway.POST("/routes", v1.CreateRoute)
			gateway.POST("/api-keys", v1.CreateAPIKey)
		}

		faults := api.Group("/faults")
		{
			faults.GET("/scenarios", v1.ListFaultScenarios)
			faults.POST("/scenarios", v1.CreateFaultScenario)
			faults.POST("/scenarios/:id/activate", v1.ActivateFaultScenario)
			faults.POST("/scenarios/:id/deactivate", v1.DeactivateFaultScenario)
		}

		dns := api.Group("/dns")
		{
			dns.GET("/upstreams", v1.ListDNSUpstreams)
			dns.POST("/upstreams", v1.AddDNSUpstream)
			dns.PUT("/strategy", v1.SetDNSStrategy)
			dns.GET("/resolve", v1.ResolveDNS)
		}

		tasks := api.Group("/tasks")
		{
			tasks.GET("", v1.ListTasks)
			tasks.POST("", v1.CreateTask)
			tasks.POST("/:id/trigger", v1.TriggerTask)
		}

		monitoring := api.Group("/monitoring")
		{
			monitoring.GET("/metrics", v1.GetMetrics)
			monitoring.GET("/snapshots", v1.GetSnapshots)
			monitoring.GET("/prometheus", v1.PrometheusHandler())
		}

		api.GET("/engine/stats", v1.GetEngineStats)

		logs := api.Group("/logs")
		{
			logs.POST("/batch", v1.BatchLogHandler)
			logs.POST("/flush", v1.FlushLogsHandler)
			logs.GET("/batcher/stats", v1.GetLogBatcherStats)
		}
	}

	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"status":     "ok",
			"service":    "config-platform",
			"version":    version,
			"build_time": buildTime,
			"git_commit": gitCommit,
			"env":        getEnv(),
		})
	})

	r.GET("/version", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"version":    version,
			"build_time": buildTime,
			"git_commit": gitCommit,
			"env":        getEnv(),
		})
	})

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	srv := &http.Server{
		Addr:    ":" + port,
		Handler: r,
	}

	go func() {
		logging.Info("Server listening on port " + port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logging.Error("Failed to start server", zap.Error(err))
			os.Exit(1)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logging.Info("Shutdown signal received, starting graceful shutdown")

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		logging.Error("Server forced to shutdown", zap.Error(err))
		os.Exit(1)
	}

	logging.Info("Server exited gracefully")
}
