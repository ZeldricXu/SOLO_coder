package main

import (
	"context"
	"fmt"
	"log"
	"time"

	"go.uber.org/zap"
	"google.golang.org/protobuf/types/known/durationpb"

	v1 "github.com/df1-96/experiment/pkg/grpcapi/distcomp/v1"
	"github.com/df1-96/experiment/pkg/grpcapi/client"
)

func taskStatusToString(status v1.TaskStatus) string {
	switch status {
	case v1.TaskStatus_TASK_STATUS_UNSPECIFIED:
		return "UNSPECIFIED"
	case v1.TaskStatus_TASK_STATUS_PENDING:
		return "PENDING"
	case v1.TaskStatus_TASK_STATUS_QUEUED:
		return "QUEUED"
	case v1.TaskStatus_TASK_STATUS_RUNNING:
		return "RUNNING"
	case v1.TaskStatus_TASK_STATUS_PAUSED:
		return "PAUSED"
	case v1.TaskStatus_TASK_STATUS_COMPLETED:
		return "COMPLETED"
	case v1.TaskStatus_TASK_STATUS_FAILED:
		return "FAILED"
	case v1.TaskStatus_TASK_STATUS_CANCELLED:
		return "CANCELLED"
	case v1.TaskStatus_TASK_STATUS_TIMED_OUT:
		return "TIMED_OUT"
	default:
		return fmt.Sprintf("UNKNOWN(%d)", status)
	}
}

func main() {
	logger, err := zap.NewDevelopment()
	if err != nil {
		log.Fatalf("Failed to create logger: %v", err)
	}
	defer logger.Sync()

	grpcConfig := client.DefaultGRPCClientConfig()
	grpcConfig.Address = "localhost:50051"
	grpcConfig.Block = true
	grpcConfig.DialTimeout = 10 * time.Second

	grpcClient := client.NewGRPCClient(grpcConfig, logger)
	if err := grpcClient.Connect(context.Background()); err != nil {
		logger.Fatal("Failed to connect to scheduler", zap.Error(err))
	}
	defer grpcClient.Close()

	taskClient := client.NewTaskClient(grpcClient, logger)
	computeClient := client.NewComputeClient(grpcClient, logger)

	ctx := context.Background()

	fmt.Println("=== Example 1: Create and submit a task ===")
	createResp, err := taskClient.CreateTask(ctx, &v1.CreateTaskRequest{
		ExperimentName: "example-experiment",
		CreatedBy:      "user@example.com",
		Description:    "Example optimization task using Rosenbrock function",
		Priority:       v1.TaskPriority_TASK_PRIORITY_MEDIUM,
		Config: &v1.TaskConfig{
			MaxRetries: 3,
			Timeout:    durationpb.New(5 * time.Minute),
		},
		Tags: map[string]string{
			"function":       "rosenbrock",
			"dimensions":     "4",
			"max_iterations": "1000",
		},
	})
	if err != nil {
		logger.Fatal("Failed to create task", zap.Error(err))
	}

	logger.Info("Task created successfully",
		zap.String("task_id", createResp.TaskId),
		zap.String("status", taskStatusToString(createResp.Status)),
	)

	fmt.Println("\n=== Example 2: Get task status ===")
	taskResp, err := taskClient.GetTask(ctx, &v1.GetTaskRequest{
		TaskId: createResp.TaskId,
	})
	if err != nil {
		logger.Error("Failed to get task", zap.Error(err))
	} else {
		createdAt := int64(0)
		if taskResp.Task.CreatedAt != nil {
			createdAt = taskResp.Task.CreatedAt.AsTime().Unix()
		}
		logger.Info("Task status",
			zap.String("task_id", taskResp.Task.TaskId),
			zap.String("status", taskStatusToString(taskResp.Task.Status)),
			zap.Int64("created_at", createdAt),
		)
	}

	fmt.Println("\n=== Example 3: List tasks ===")
	listResp, err := taskClient.ListTasks(ctx, &v1.ListTasksRequest{
		ExperimentName: "example-experiment",
		PageSize:       10,
		PageToken:      "",
	})
	if err != nil {
		logger.Error("Failed to list tasks", zap.Error(err))
	} else {
		logger.Info("Tasks listed",
			zap.Int("total_tasks", int(listResp.TotalCount)),
			zap.Int("returned_tasks", len(listResp.Tasks)),
		)
		for _, task := range listResp.Tasks {
			fmt.Printf("  - %s: %s\n", task.TaskId, taskStatusToString(task.Status))
		}
	}

	fmt.Println("\n=== Example 4: Get compute shard ===")
	shardResp, err := computeClient.GetShard(ctx, &v1.GetShardRequest{
		WorkerId: "example-worker-1",
		TaskId:   createResp.TaskId,
	})
	if err != nil {
		logger.Error("Failed to get shard", zap.Error(err))
	} else {
		logger.Info("Shard retrieved",
			zap.String("task_id", shardResp.Shard.TaskId),
			zap.Int32("shard_id", shardResp.Shard.ShardId),
			zap.Int32("total_shards", shardResp.Shard.TotalShards),
		)
	}

	fmt.Println("\n=== Example 5: Monitor task status (streaming) ===")
	taskIDs := []string{createResp.TaskId}
	err = taskClient.StartTaskStatusMonitoring(ctx, taskIDs, func(task *v1.Task) {
		logger.Info("Task status update",
			zap.String("task_id", task.TaskId),
			zap.String("status", taskStatusToString(task.Status)),
		)
	})
	if err != nil {
		logger.Error("Failed to start task monitoring", zap.Error(err))
	}

	time.Sleep(2 * time.Second)

	fmt.Println("\n=== Example 6: Cancel a task ===")
	cancelResp, err := taskClient.CancelTask(ctx, &v1.CancelTaskRequest{
		TaskId: createResp.TaskId,
		Reason: "Example cancellation",
	})
	if err != nil {
		logger.Error("Failed to cancel task", zap.Error(err))
	} else {
		logger.Info("Task cancelled",
			zap.String("task_id", createResp.TaskId),
			zap.Bool("success", cancelResp.Success),
		)
	}

	fmt.Println("\nAll examples completed!")
}
