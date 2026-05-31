package main

import (
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/solocoder/session138/internal/catalog"
	"github.com/solocoder/session138/internal/contract"
	"github.com/solocoder/session138/internal/core"
	"github.com/solocoder/session138/internal/documentation"
	"github.com/solocoder/session138/internal/environment"
	"github.com/solocoder/session138/internal/featureflag"
	"github.com/solocoder/session138/internal/gateway"
	"github.com/solocoder/session138/internal/logger"
	"github.com/solocoder/session138/internal/scaffold"
	"github.com/solocoder/session138/internal/vulnerability"
	"github.com/solocoder/session138/pkg/cache"
	"github.com/solocoder/session138/pkg/config"
	"github.com/solocoder/session138/pkg/database"
	"github.com/solocoder/session138/pkg/metrics"
	"go.uber.org/zap"
	"time"
)

func main() {
	cfg := config.Load()

	logger.Init(&cfg.Logger)
	logger.Info("main", "系统启动中...")

	if err := database.Init(&cfg.Database); err != nil {
		logger.Warn("main", "数据库连接失败，使用内存模式", zap.Error(err))
	} else {
		logger.Info("main", "数据库连接成功")
	}

	if err := cache.Init(&cfg.Redis); err != nil {
		logger.Warn("main", "Redis连接失败，缓存功能禁用", zap.Error(err))
	} else {
		logger.Info("main", "Redis连接成功")
	}

	r := gin.Default()

	r.Use(logger.Middleware())
	r.Use(gin.Recovery())

	r.GET("/health", func(c *gin.Context) {
		c.JSON(200, gin.H{
			"code":    200,
			"status":  "ok",
			"time":    time.Now().UTC().Format(time.RFC3339),
			"version": "1.0.0",
		})
	})

	r.GET("/metrics", gin.WrapH(promhttp.Handler()))

	_ = metrics.NewTimer("main", "init")

	api := r.Group("/api/v1")
	{
		catalog.RegisterRoutes(api)
		scaffold.RegisterRoutes(api)
		vulnerability.RegisterRoutes(api)
		logger.RegisterRoutes(api)
		core.RegisterRoutes(api)
		gateway.RegisterRoutes(api)
		contract.RegisterRoutes(api)
		environment.RegisterRoutes(api)
		documentation.RegisterRoutes(api)
		featureflag.RegisterRoutes(api)
	}

	addr := fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port)
	logger.Info("main", "服务启动成功", zap.String("addr", addr))

	if err := r.Run(addr); err != nil {
		logger.Error("main", "服务启动失败", zap.Error(err))
	}
}
