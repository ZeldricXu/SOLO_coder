package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/distributed-task-scheduler/internal/api"
	"github.com/distributed-task-scheduler/internal/auth"
	"github.com/distributed-task-scheduler/internal/config"
	"github.com/distributed-task-scheduler/internal/executor"
	"github.com/distributed-task-scheduler/internal/models"
	"github.com/distributed-task-scheduler/internal/registry"
	"github.com/distributed-task-scheduler/internal/scheduler"
	"github.com/distributed-task-scheduler/internal/storage"
	"github.com/distributed-task-scheduler/internal/tracing"
)

func main() {
	cfg := config.DefaultConfig()

	db, err := storage.NewDatabase(cfg.Database)
	if err != nil {
		log.Fatalf("Failed to connect to database: %v", err)
	}
	defer db.Close()

	redis, err := storage.NewRedisClient(cfg.Redis)
	if err != nil {
		log.Fatalf("Failed to connect to redis: %v", err)
	}
	defer redis.Close()

	tracerMgr, err := tracing.NewTracerManager(cfg.Tracing)
	if err != nil {
		log.Printf("Warning: Failed to initialize tracer: %v", err)
	}
	defer func() {
		if tracerMgr != nil {
			tracerMgr.Shutdown(context.Background())
		}
	}()

	authMgr := auth.NewAuthManager(db)

	reg := registry.NewRegistry(db, redis, cfg.Registry)
	reg.Start()
	defer reg.Stop()

	grpcServer := registry.NewGRPCServer(reg)
	if err := grpcServer.Start(cfg.Server.GRPCAddr); err != nil {
		log.Printf("Warning: Failed to start gRPC server: %v", err)
	}
	defer grpcServer.Stop()

	sched := scheduler.NewScheduler(db, redis, cfg.Scheduler, cfg.Server.NodeID)
	sched.Start()
	defer sched.Stop()

	taskHandler := func(ctx context.Context, task *models.Task, executionID string) ([]byte, error) {
		fmt.Printf("Executing task: %s (%s) on node: %s\n", task.ID, task.Name, cfg.Server.NodeID)
		return []byte(`{"status": "completed"}`), nil
	}

	execPool := executor.NewExecutorPool(db, cfg.Executor, cfg.Server.NodeID, taskHandler)
	defer execPool.Shutdown()

	go func() {
		for task := range sched.JobChannel() {
			var execution models.Execution
			err := db.Get(&execution, "SELECT * FROM executions WHERE task_id = $1 ORDER BY created_at DESC LIMIT 1", task.ID)
			if err == nil {
				execPool.Submit(task, execution.ID)
			}
		}
	}()

	handler := api.NewHandler(db, sched, authMgr)
	router := api.SetupRouter(handler, authMgr)

	go func() {
		log.Printf("Starting HTTP server on %s", cfg.Server.HTTPAddr)
		if err := router.Run(cfg.Server.HTTPAddr); err != nil {
			log.Fatalf("HTTP server failed: %v", err)
		}
	}()

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	log.Println("Shutting down gracefully...")
}
