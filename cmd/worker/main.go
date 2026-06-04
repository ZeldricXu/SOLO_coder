package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/distributed-task-scheduler/internal/config"
	"github.com/distributed-task-scheduler/internal/executor"
	"github.com/distributed-task-scheduler/internal/models"
	"github.com/distributed-task-scheduler/internal/storage"
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

	taskHandler := func(ctx context.Context, task *models.Task, executionID string) ([]byte, error) {
		fmt.Printf("[Worker] Executing task: %s (%s) type: %s\n", task.ID, task.Name, task.Type)
		fmt.Printf("[Worker] Payload: %s\n", string(task.Payload))

		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(2 * time.Second):
		}

		fmt.Printf("[Worker] Task completed: %s\n", task.ID)
		return []byte(`{"result": "success"}`), nil
	}

	execPool := executor.NewExecutorPool(db, cfg.Executor, cfg.Server.NodeID, taskHandler)
	defer execPool.Shutdown()

	log.Printf("Worker started on node: %s", cfg.Server.NodeID)
	log.Println("Waiting for tasks...")

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	log.Println("Worker shutting down...")
}
