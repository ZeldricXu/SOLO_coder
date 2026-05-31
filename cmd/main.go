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

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/internal/api"
	"github.com/solocoder/task-scheduler/internal/config"
	"github.com/solocoder/task-scheduler/internal/contracts"
	"github.com/solocoder/task-scheduler/internal/core"
	"github.com/solocoder/task-scheduler/internal/database"
	"github.com/solocoder/task-scheduler/internal/events"
	"github.com/solocoder/task-scheduler/internal/logging"
	"github.com/solocoder/task-scheduler/internal/notification"
	"github.com/solocoder/task-scheduler/internal/scheduler"
	"github.com/solocoder/task-scheduler/internal/storage"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	if err := logging.Init(cfg.Logging.Level, cfg.Logging.Format); err != nil {
		log.Fatalf("Failed to initialize logging: %v", err)
	}
	defer logging.Sync()

	ctx := context.Background()
	logging.Info(ctx, "Starting task scheduler service",
		zap.String("version", "1.0.0"),
		zap.String("environment", cfg.Server.Environment))

	db, err := database.New(cfg)
	if err != nil {
		logging.Fatal(ctx, "Failed to initialize database", zap.Error(err))
	}
	defer db.Close()
	logging.Info(ctx, "Database initialized successfully")

	eventBus := events.NewInMemoryEventBus(1000, cfg.Worker.EventBusWorkers)
	defer eventBus.Close()
	logging.Info(ctx, "Event bus initialized")

	handler := core.NewHandlerWithDefaults(db, eventBus, cfg.Worker.PoolSize)
	executor := core.NewTaskExecutor(handler)
	logging.Info(ctx, "Core handler initialized")

	notifier := notification.NewNotifierWithDefaults(
		eventBus,
		3,
		100,
	)
	notifier.Start()
	defer notifier.Stop()
	logging.Info(ctx, "Notification service initialized")

	storageManager := storage.NewStorageManagerWithDefaults(
		db,
		eventBus,
		"./backups",
		30,
	)
	defer storageManager.Close()
	logging.Info(ctx, "Storage manager initialized")

	sched := scheduler.NewSchedulerSimple(db, eventBus, cfg.Worker.WorkerCount)
	sched.Start(ctx)
	defer sched.Stop()
	logging.Info(ctx, "Scheduler started")

	sched.RegisterProcessor("default", func(ctx context.Context, task *scheduler.Task) error {
		entityID := ""
		if task.Resource != nil {
			entityID = task.Resource.ID
		}

		req := &contracts.ProcessRequest{
			TraceID:   fmt.Sprintf("trace_%d", time.Now().UnixNano()),
			Namespace: "default",
			Params: map[string]interface{}{
				"task_type": task.Type,
			},
			Payload:  task.Payload,
			EntityID: entityID,
		}

		result := executor.Execute(ctx, req)
		if !result.Success {
			return fmt.Errorf("task execution failed: %s", result.Error)
		}
		return nil
	})

	router := api.SetupRouter(db, sched, executor)
	logging.Info(ctx, "API router configured")

	server := &http.Server{
		Addr:         fmt.Sprintf(":%d", cfg.Server.Port),
		Handler:      router,
		ReadTimeout:  cfg.Server.ReadTimeout,
		WriteTimeout: cfg.Server.WriteTimeout,
		IdleTimeout:  cfg.Server.IdleTimeout,
	}

	go func() {
		logging.Info(ctx, "HTTP server starting", zap.Int("port", cfg.Server.Port))
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logging.Fatal(ctx, "HTTP server failed", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	logging.Info(ctx, "Shutdown signal received")

	shutdownCtx, shutdownCancel := context.WithTimeout(ctx, 30*time.Second)
	defer shutdownCancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		logging.Error(ctx, "HTTP server shutdown error", zap.Error(err))
	}

	logging.Info(ctx, "Service shutdown completed")
}
