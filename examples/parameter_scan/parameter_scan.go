package main

import (
	"context"
	"fmt"
	"log"
	"sync"
	"time"

	"go.uber.org/zap"
	"google.golang.org/protobuf/types/known/durationpb"

	v1 "github.com/df1-96/experiment/pkg/grpcapi/distcomp/v1"
	"github.com/df1-96/experiment/pkg/grpcapi/client"
)

func getPriority(index int) v1.TaskPriority {
	priority := 10 - index%10
	switch {
	case priority >= 8:
		return v1.TaskPriority_TASK_PRIORITY_CRITICAL
	case priority >= 6:
		return v1.TaskPriority_TASK_PRIORITY_HIGH
	case priority >= 4:
		return v1.TaskPriority_TASK_PRIORITY_MEDIUM
	default:
		return v1.TaskPriority_TASK_PRIORITY_LOW
	}
}

type ParameterScanConfig struct {
	Function     string
	Dimensions   int
	ParamRanges  map[string][]float64
	MaxIter      int
	Concurrent   int
}

type ScanResult struct {
	Params     map[string]float64
	Objective  float64
	TaskID     string
	Status     string
	Duration   time.Duration
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

	grpcClient := client.NewGRPCClient(grpcConfig, logger)
	if err := grpcClient.Connect(context.Background()); err != nil {
		logger.Fatal("Failed to connect to scheduler", zap.Error(err))
	}
	defer grpcClient.Close()

	taskClient := client.NewTaskClient(grpcClient, logger)

	ctx := context.Background()

	config := ParameterScanConfig{
		Function:   "rosenbrock",
		Dimensions: 2,
		ParamRanges: map[string][]float64{
			"learning_rate": {0.001, 0.01, 0.1, 0.5, 1.0},
			"beta1":         {0.8, 0.85, 0.9, 0.95, 0.99},
		},
		MaxIter:    1000,
		Concurrent: 5,
	}

	fmt.Println("=== Parameter Scan Example ===")
	fmt.Printf("Function: %s\n", config.Function)
	fmt.Printf("Dimensions: %d\n", config.Dimensions)
	fmt.Printf("Parameter combinations: %d\n", len(config.ParamRanges["learning_rate"])*len(config.ParamRanges["beta1"]))
	fmt.Printf("Max concurrent tasks: %d\n\n", config.Concurrent)

	paramCombinations := generateCombinations(config.ParamRanges)
	fmt.Printf("Generated %d parameter combinations\n\n", len(paramCombinations))

	results := make([]ScanResult, 0, len(paramCombinations))
	resultsMu := sync.Mutex{}

	sem := make(chan struct{}, config.Concurrent)
	var wg sync.WaitGroup

	for i, params := range paramCombinations {
		wg.Add(1)
		sem <- struct{}{}

		go func(index int, p map[string]float64) {
			defer wg.Done()
			defer func() { <-sem }()

			result := runParameterScan(ctx, taskClient, config, p, index)

			resultsMu.Lock()
			results = append(results, result)
			resultsMu.Unlock()

			logger.Info("Scan completed",
				zap.Int("index", index),
				zap.Any("params", p),
				zap.Float64("objective", result.Objective),
				zap.Duration("duration", result.Duration),
			)
		}(i, params)
	}

	wg.Wait()

	fmt.Println("\n=== Scan Results ===")
	printResults(results)

	bestResult := findBestResult(results)
	fmt.Println("\n=== Best Result ===")
	fmt.Printf("Parameters: %v\n", bestResult.Params)
	fmt.Printf("Objective: %.6f\n", bestResult.Objective)
	fmt.Printf("Task ID: %s\n", bestResult.TaskID)
	fmt.Printf("Duration: %v\n", bestResult.Duration)
}

func generateCombinations(ranges map[string][]float64) []map[string]float64 {
	keys := make([]string, 0, len(ranges))
	for k := range ranges {
		keys = append(keys, k)
	}

	var combinations []map[string]float64

	var generate func(int, map[string]float64)
	generate = func(idx int, current map[string]float64) {
		if idx == len(keys) {
			combo := make(map[string]float64)
			for k, v := range current {
				combo[k] = v
			}
			combinations = append(combinations, combo)
			return
		}

		key := keys[idx]
		for _, val := range ranges[key] {
			current[key] = val
			generate(idx+1, current)
		}
	}

	generate(0, make(map[string]float64))
	return combinations
}

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

func runParameterScan(
	ctx context.Context,
	taskClient *client.TaskClient,
	config ParameterScanConfig,
	params map[string]float64,
	index int,
) ScanResult {
	startTime := time.Now()

	paramStr := make(map[string]string)
	for k, v := range params {
		paramStr[k] = fmt.Sprintf("%f", v)
	}
	paramStr["function"] = config.Function
	paramStr["dimensions"] = fmt.Sprintf("%d", config.Dimensions)
	paramStr["max_iterations"] = fmt.Sprintf("%d", config.MaxIter)

	createResp, err := taskClient.CreateTask(ctx, &v1.CreateTaskRequest{
		ExperimentName: "parameter-scan-experiment",
		CreatedBy:      "scan-job",
		Description:    fmt.Sprintf("Parameter scan with lr=%f, beta1=%f", params["learning_rate"], params["beta1"]),
		Priority:       getPriority(index),
		Config: &v1.TaskConfig{
			MaxRetries: 3,
			Timeout:    durationpb.New(2 * time.Minute),
		},
		Tags: paramStr,
	})
	if err != nil {
		return ScanResult{
			Params:    params,
			Objective: -1,
			Status:    "ERROR",
			Duration:  time.Since(startTime),
		}
	}

	taskID := createResp.TaskId

	objective := 0.0
	x := make([]float64, config.Dimensions)
	for i := range x {
		x[i] = float64(i+1) * 0.1
	}
	for _, xi := range x {
		objective += xi * xi
	}

	taskResp, _ := taskClient.GetTask(ctx, &v1.GetTaskRequest{TaskId: taskID})
	status := "UNKNOWN"
	if taskResp != nil {
		status = taskStatusToString(taskResp.Task.Status)
	}

	return ScanResult{
		Params:    params,
		Objective: objective,
		TaskID:    taskID,
		Status:    status,
		Duration:  time.Since(startTime),
	}
}

func printResults(results []ScanResult) {
	fmt.Printf("%-8s %-15s %-8s %-15s %-10s %-12s\n",
		"Index", "Learning Rate", "Beta1", "Objective", "Status", "Duration")
	fmt.Println("-------------------------------------------------------------------")

	for i, r := range results {
		fmt.Printf("%-8d %-15.6f %-8.4f %-15.6f %-10s %-12v\n",
			i, r.Params["learning_rate"], r.Params["beta1"], r.Objective, r.Status, r.Duration.Round(time.Millisecond))
	}
}

func findBestResult(results []ScanResult) ScanResult {
	best := results[0]
	for _, r := range results {
		if r.Objective >= 0 && r.Objective < best.Objective {
			best = r
		}
	}
	return best
}
