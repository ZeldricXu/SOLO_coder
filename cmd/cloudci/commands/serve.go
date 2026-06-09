package commands

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/solocoder/cloudci/internal/artifact"
	"github.com/solocoder/cloudci/internal/api"
	"github.com/solocoder/cloudci/internal/config"
	"github.com/solocoder/cloudci/internal/logger"
	"github.com/solocoder/cloudci/internal/logstore"
	"github.com/solocoder/cloudci/internal/notify"
	"github.com/solocoder/cloudci/internal/plugin"
	"github.com/solocoder/cloudci/internal/scheduler"
	"github.com/solocoder/cloudci/internal/secret"
	"github.com/solocoder/cloudci/internal/storage"
	"github.com/solocoder/cloudci/internal/trigger"
	"github.com/spf13/cobra"
	"go.uber.org/zap"
)

var serveCmd = &cobra.Command{
	Use:   "serve",
	Short: "Start the CloudCI server",
	Long:  `Start the CloudCI server including API, scheduler, and all background services.`,
	Run:   runServe,
}

func init() {
	rootCmd.AddCommand(serveCmd)
}

func runServe(cmd *cobra.Command, args []string) {
	cfg, err := config.Load()
	if err != nil {
		logger.Fatal("failed to load config", zap.Error(err))
	}

	logger.Init(&logger.Config{
		Level:  cfg.Log.Level,
		Format: cfg.Log.Format,
	})

	logger.Info("starting CloudCI server",
		zap.String("host", cfg.Server.Host),
		zap.Int("port", cfg.Server.Port))

	if err := storage.InitPostgres(&cfg.Database); err != nil {
		logger.Fatal("failed to initialize postgres", zap.Error(err))
	}
	logger.Info("postgres connected")

	if err := storage.InitRedis(&cfg.Redis); err != nil {
		logger.Fatal("failed to initialize redis", zap.Error(err))
	}
	logger.Info("redis connected")

	redisClient := &storage.RedisClient{}

	minioClient, err := storage.NewMinIOClient(&cfg.MinIO)
	if err != nil {
		logger.Fatal("failed to initialize minio", zap.Error(err))
	}
	logger.Info("minio connected")

	secretMgr, err := secret.NewSecretManager(&cfg.Vault)
	if err != nil {
		logger.Error("failed to initialize secret manager", zap.Error(err))
	} else {
		logger.Info("secret manager initialized")
	}

	notifier := notify.NewNotifier(&cfg.Notification)
	logger.Info("notifier initialized")

	logStore := logstore.NewLogStore(&cfg.LogStore, storage.GetDB(), redisClient)
	logger.Info("log store initialized")

	artifactMgr := artifact.NewArtifactManager(&cfg.Artifact, minioClient, storage.GetDB())
	logger.Info("artifact manager initialized")

	pluginMgr, err := plugin.NewPluginManager(&cfg.Plugin)
	if err != nil {
		logger.Fatal("failed to create plugin manager", zap.Error(err))
	}
	plugins, _ := pluginMgr.List(context.Background(), nil)
	logger.Info("plugin manager initialized", zap.Int("plugins", len(plugins)))

	schedulerInstance := scheduler.NewScheduler(&cfg.Scheduler, pluginMgr)
	if secretMgr != nil {
		schedulerInstance.SetSecretManager(secretMgr)
	}
	schedulerInstance.SetNotifier(notifier)
	schedulerInstance.SetLogStore(logStore)
	schedulerInstance.SetArtifactManager(artifactMgr)
	schedulerInstance.Start()
	logger.Info("scheduler started")

	triggerAdapter := trigger.NewTriggerAdapter(&cfg.Webhook)
	triggerAdapter.Start()
	logger.Info("trigger adapter started")

	apiServer := api.NewAPIServer(cfg)
	apiServer.SetScheduler(schedulerInstance)
	apiServer.SetTriggerAdapter(triggerAdapter)
	apiServer.SetPluginManager(pluginMgr)
	if secretMgr != nil {
		apiServer.SetSecretManager(secretMgr)
	}
	apiServer.SetupRoutes()

	addr := fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port)
	server := &http.Server{
		Addr:    addr,
		Handler: apiServer.Router(),
	}

	go func() {
		logger.Info("http server starting", zap.String("addr", addr))
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("http server failed", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	logger.Info("shutdown signal received")

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := server.Shutdown(ctx); err != nil {
		logger.Error("http server shutdown error", zap.Error(err))
	}

	triggerAdapter.Stop()
	pluginMgr.Shutdown()

	logger.Info("CloudCI server stopped gracefully")
}
