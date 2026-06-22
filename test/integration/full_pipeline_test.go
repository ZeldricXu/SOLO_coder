//go:build integration

package integration

import (
	"context"
	"fmt"
	"math"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/df1-96/experiment/internal/analysis"
	"github.com/df1-96/experiment/internal/config"
	"github.com/df1-96/experiment/internal/models"
	"github.com/df1-96/experiment/internal/storage"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/testcontainers/testcontainers-go"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"
	gormpostgres "gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type testEnv struct {
	db             *storage.DB
	checkpointRepo *storage.CheckpointRepo
	taskRepo       *storage.TaskRepo
	workerRepo     *storage.WorkerRepo
	experimentRepo *storage.ExperimentRepo
	resultRepo     *storage.ResultRepo
	recoveryMgr    *storage.RecoveryManager
}

func setupIntegrationTestDB(t *testing.T) (*testEnv, func()) {
	t.Helper()
	ctx := context.Background()

	pgContainer, err := tcpostgres.Run(ctx,
		"postgres:16-alpine",
		tcpostgres.WithDatabase("integration_testdb"),
		tcpostgres.WithUsername("testuser"),
		tcpostgres.WithPassword("testpass"),
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").
				WithOccurrence(2).
				WithStartupTimeout(30*time.Second),
		),
	)
	require.NoError(t, err)

	host, err := pgContainer.Host(ctx)
	require.NoError(t, err)

	mappedPort, err := pgContainer.MappedPort(ctx, "5432")
	require.NoError(t, err)

	portStr := mappedPort.Port()
	portInt, err := strconv.Atoi(portStr)
	require.NoError(t, err)

	dsn := fmt.Sprintf(
		"host=%s port=%s user=testuser password=testpass dbname=integration_testdb sslmode=disable TimeZone=UTC",
		host, portStr,
	)

	gormDB, err := gorm.Open(gormpostgres.Open(dsn), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Warn),
	})
	require.NoError(t, err)

	err = models.AutoMigrate(gormDB)
	require.NoError(t, err)

	cfg := &config.DatabaseConfig{
		Host:     host,
		Port:     portInt,
		User:     "testuser",
		Password: "testpass",
		DBName:   "integration_testdb",
		SSLMode:  "disable",
		TimeZone: "UTC",
	}

	db := storage.NewDBFromGORM(gormDB, cfg)

	checkpointRepo := storage.NewCheckpointRepo(db)
	taskRepo := storage.NewTaskRepo(db)
	workerRepo := storage.NewWorkerRepo(db)
	experimentRepo := storage.NewExperimentRepo(db)
	resultRepo := storage.NewResultRepo(db)
	recoveryMgr := storage.NewRecoveryManager(db, checkpointRepo, taskRepo, workerRepo, experimentRepo, resultRepo)

	env := &testEnv{
		db:             db,
		checkpointRepo: checkpointRepo,
		taskRepo:       taskRepo,
		workerRepo:     workerRepo,
		experimentRepo: experimentRepo,
		resultRepo:     resultRepo,
		recoveryMgr:    recoveryMgr,
	}

	cleanup := func() {
		sqlDB, _ := gormDB.DB()
		if sqlDB != nil {
			sqlDB.Close()
		}
		if pgContainer != nil {
			_ = pgContainer.Terminate(ctx)
		}
	}

	return env, cleanup
}

type mockWorker struct {
	ID         int64
	Name       string
	Killed     bool
	KillAtStep int64
}

type pipelineRunResult struct {
	ExperimentID   int64
	TaskIDs        []int64
	ResultsByTask  map[int64][]*models.Result
	FinalValues    map[int64]float64
	CompletionTime time.Duration
}

func runFullPipeline(
	t *testing.T,
	env *testEnv,
	numParams int,
	numWorkers int,
	numIterations int64,
	checkpointEvery int64,
	killWorkerID int64,
	killAtStep int64,
) *pipelineRunResult {
	t.Helper()
	ctx := context.Background()

	experiment := &models.Experiment{
		Name:        fmt.Sprintf("pipeline-test-%d", time.Now().UnixNano()),
		Description: "Full pipeline integration test",
		Status:      models.ExperimentStatusRunning,
		Params: models.Params{
			"objective": "quadratic",
			"num_iterations": float64(numIterations),
		},
		Config: models.Params{
			"checkpoint_every": float64(checkpointEvery),
		},
	}
	err := env.experimentRepo.Create(ctx, experiment)
	require.NoError(t, err)
	require.NotZero(t, experiment.ID)

	taskIDs := make([]int64, 0, numParams)
	for i := 0; i < numParams; i++ {
		params := models.Params{
			"param_x": float64(i + 1),
			"param_y": float64((i + 1) * 2),
		}
		task := &models.Task{
			ExperimentID: experiment.ID,
			Name:         fmt.Sprintf("task-%d", i),
			Params:       params,
			MaxRetries:   5,
		}
		err := env.taskRepo.Create(ctx, task)
		require.NoError(t, err)
		taskIDs = append(taskIDs, task.ID)
	}

	workers := make([]*mockWorker, 0, numWorkers)
	for i := 0; i < numWorkers; i++ {
		workerID := int64(i + 1)
		worker := &models.Worker{
			ID:       workerID,
			Name:     fmt.Sprintf("worker-%d", workerID),
			Status:   models.WorkerStatusIdle,
			CPUCores: 4,
			MemoryGB: 8,
		}
		err := env.workerRepo.Register(ctx, worker)
		require.NoError(t, err)
		workers = append(workers, &mockWorker{
			ID:         workerID,
			Name:       worker.Name,
			KillAtStep: killAtStep,
		})
	}

	resultsByTask := make(map[int64][]*models.Result)
	finalValues := make(map[int64]float64)
	var mu sync.Mutex
	var wg sync.WaitGroup
	var completedTasks atomic.Int32

	processTask := func(worker *mockWorker, taskID int64, startStep int64, initialState map[string]interface{}) {
		defer wg.Done()

		var state map[string]interface{}
		if initialState != nil {
			state = initialState
		} else {
			state = map[string]interface{}{
				"sum":   0.0,
				"count": float64(0),
			}
		}

		task, err := env.taskRepo.GetByID(ctx, taskID)
		require.NoError(t, err)
		require.NotNil(t, task)

		workerIDVal := worker.ID
		err = env.taskRepo.UpdateStatus(ctx, taskID, models.TaskStatusRunning, &workerIDVal, "")
		require.NoError(t, err)
		err = env.workerRepo.SetCurrentTask(ctx, worker.ID, &taskID)
		require.NoError(t, err)

		paramX := task.Params["param_x"].(float64)
		paramY := task.Params["param_y"].(float64)

		for step := startStep + 1; step <= numIterations; step++ {
			if worker.Killed && step >= worker.KillAtStep {
				return
			}

			state["count"] = state["count"].(float64) + 1
			x := paramX + float64(step)*0.001
			y := paramY + float64(step)*0.002
			value := x*x + y*y + math.Sin(float64(step))*0.01
			state["sum"] = state["sum"].(float64) + value

			if checkpointEvery > 0 && step%checkpointEvery == 0 {
				cp := &models.Checkpoint{
					TaskID:   taskID,
					WorkerID: worker.ID,
					Step:     step,
					Data: models.Params{
						"sum":   state["sum"],
						"count": state["count"],
					},
				}
				err := env.checkpointRepo.Save(ctx, cp)
				require.NoError(t, err)
			}

			err = env.workerRepo.UpdateHeartbeat(ctx, worker.ID)
			require.NoError(t, err)
		}

		finalValue := state["sum"].(float64) / state["count"].(float64)
		result := &models.Result{
			TaskID:     taskID,
			WorkerID:   worker.ID,
			Data:       models.ResultData{"value": finalValue},
			Iteration:  numIterations,
			DurationMs: int64(numIterations),
		}
		err = env.resultRepo.Save(ctx, result)
		require.NoError(t, err)

		err = env.taskRepo.UpdateStatus(ctx, taskID, models.TaskStatusCompleted, &workerIDVal, "")
		require.NoError(t, err)

		var nilTaskID *int64
		err = env.workerRepo.SetCurrentTask(ctx, worker.ID, nilTaskID)
		require.NoError(t, err)

		mu.Lock()
		resultsByTask[taskID] = append(resultsByTask[taskID], result)
		finalValues[taskID] = finalValue
		mu.Unlock()

		completedTasks.Add(1)
	}

	assignAndProcess := func() {
		for i, taskID := range taskIDs {
			worker := workers[i%numWorkers]
			wg.Add(1)
			go processTask(worker, taskID, 0, nil)
		}
	}

	start := time.Now()
	assignAndProcess()

	if killWorkerID > 0 {
		time.Sleep(100 * time.Millisecond)
		for _, w := range workers {
			if w.ID == killWorkerID {
				w.Killed = true
				break
			}
		}
	}

	wg.Wait()

	if killWorkerID > 0 {
		for attempts := 0; attempts < 50; attempts++ {
			if completedTasks.Load() >= int32(numParams) {
				break
			}

			_, err := env.recoveryMgr.RecoverFromNodeFailure(ctx, 50*time.Millisecond)
			if err != nil {
				time.Sleep(100 * time.Millisecond)
				continue
			}

			for _, taskID := range taskIDs {
				task, err := env.taskRepo.GetByID(ctx, taskID)
				if err != nil || task == nil {
					continue
				}
				if task.Status == models.TaskStatusRetrying {
					cp, err := env.checkpointRepo.GetLatest(ctx, taskID)
					if err != nil {
						continue
					}

					var startStep int64 = 0
					var initialState map[string]interface{}
					if cp != nil {
						startStep = cp.Step
						initialState = map[string]interface{}{
							"sum":   cp.Data["sum"],
							"count": cp.Data["count"],
						}
					}

					for _, w := range workers {
						if !w.Killed {
							wg.Add(1)
							go processTask(w, taskID, startStep, initialState)
							break
						}
					}
				}
			}
			wg.Wait()
			time.Sleep(100 * time.Millisecond)
		}
	}

	return &pipelineRunResult{
		ExperimentID:   experiment.ID,
		TaskIDs:        taskIDs,
		ResultsByTask:  resultsByTask,
		FinalValues:    finalValues,
		CompletionTime: time.Since(start),
	}
}

func TestFullPipeline_MultiParamScan(t *testing.T) {
	env, cleanup := setupIntegrationTestDB(t)
	defer cleanup()

	numParams := 10
	numWorkers := 3
	numIterations := int64(10)
	checkpointEvery := int64(2)

	result := runFullPipeline(t, env, numParams, numWorkers, numIterations, checkpointEvery, 0, 0)

	ctx := context.Background()
	assert.Equal(t, numParams, len(result.TaskIDs))
	assert.Equal(t, numParams, len(result.FinalValues))

	for _, taskID := range result.TaskIDs {
		task, err := env.taskRepo.GetByID(ctx, taskID)
		require.NoError(t, err)
		require.NotNil(t, task)
		assert.Equal(t, models.TaskStatusCompleted, task.Status, "task %d should be completed", taskID)

		taskResults := result.ResultsByTask[taskID]
		require.Len(t, taskResults, 1, "task %d should have exactly 1 result", taskID)

		value, ok := result.FinalValues[taskID]
		assert.True(t, ok, "task %d should have final value", taskID)
		assert.True(t, value > 0, "task %d value should be positive", taskID)

		checkpoints, total, err := env.checkpointRepo.List(ctx, taskID, 100, 0)
		require.NoError(t, err)
		expectedCheckpoints := numIterations / checkpointEvery
		assert.Equal(t, int64(expectedCheckpoints), total,
			"task %d should have %d checkpoints", taskID, expectedCheckpoints)
		assert.Len(t, checkpoints, int(expectedCheckpoints))
	}

	allValues := make([]float64, 0, numParams)
	for _, v := range result.FinalValues {
		allValues = append(allValues, v)
	}

	stats := analysis.NewStatistics(nil)
	basicStats := stats.ComputeBasicStats(allValues)
	assert.Equal(t, numParams, basicStats.Count)
	assert.True(t, basicStats.Mean > 0)
	assert.True(t, basicStats.Variance >= 0)
	assert.True(t, basicStats.StdDev >= 0)
	assert.True(t, basicStats.Min > 0)
	assert.True(t, basicStats.Max >= basicStats.Min)

	ci := stats.ComputeConfidenceInterval(allValues, analysis.Confidence95)
	require.NotNil(t, ci)
	assert.Equal(t, analysis.Confidence95, ci.Level)
	assert.True(t, ci.Lower <= ci.Mean)
	assert.True(t, ci.Upper >= ci.Mean)
	assert.True(t, ci.Margin > 0)

	seenValues := make(map[float64]bool)
	for _, v := range result.FinalValues {
		assert.False(t, seenValues[v], "duplicate result value found: %f", v)
		seenValues[v] = true
	}
}

func TestFullPipeline_WorkerKilled_Recovery(t *testing.T) {
	env, cleanup := setupIntegrationTestDB(t)
	defer cleanup()

	numParams := 10
	numWorkers := 3
	numIterations := int64(100)
	checkpointEvery := int64(2)

	baselineResult := runFullPipeline(t, env, numParams, numWorkers, numIterations, checkpointEvery, 0, 0)

	env2, cleanup2 := setupIntegrationTestDB(t)
	defer cleanup2()

	faultyResult := runFullPipeline(t, env2, numParams, numWorkers, numIterations, checkpointEvery, 1, 50)

	assert.Equal(t, len(baselineResult.FinalValues), len(faultyResult.FinalValues))
	assert.Equal(t, numParams, len(faultyResult.FinalValues))

	ctx := context.Background()
	for _, taskID := range faultyResult.TaskIDs {
		task, err := env2.taskRepo.GetByID(ctx, taskID)
		require.NoError(t, err)
		require.NotNil(t, task)
		assert.Equal(t, models.TaskStatusCompleted, task.Status,
			"task %d should be completed after recovery", taskID)
	}

	for i, taskID := range baselineResult.TaskIDs {
		baselineValue := baselineResult.FinalValues[taskID]
		faultyTaskID := faultyResult.TaskIDs[i]
		faultyValue := faultyResult.FinalValues[faultyTaskID]

		diff := math.Abs(baselineValue - faultyValue)
		assert.True(t, diff < 1e-12,
			"task %d: values differ too much (baseline=%v, faulty=%v, diff=%v)",
			taskID, baselineValue, faultyValue, diff)
	}

	baselineAll := make([]float64, 0, numParams)
	for _, v := range baselineResult.FinalValues {
		baselineAll = append(baselineAll, v)
	}

	faultyAll := make([]float64, 0, numParams)
	for _, v := range faultyResult.FinalValues {
		faultyAll = append(faultyAll, v)
	}

	stats := analysis.NewStatistics(nil)
	baselineStats := stats.ComputeBasicStats(baselineAll)
	faultyStats := stats.ComputeBasicStats(faultyAll)

	assert.True(t, math.Abs(baselineStats.Mean-faultyStats.Mean) < 1e-12,
		"means differ: baseline=%v, faulty=%v", baselineStats.Mean, faultyStats.Mean)
	assert.True(t, math.Abs(baselineStats.Variance-faultyStats.Variance) < 1e-12,
		"variances differ: baseline=%v, faulty=%v", baselineStats.Variance, faultyStats.Variance)

	var recoveredTasks int
	for _, taskID := range faultyResult.TaskIDs {
		cp, err := env2.checkpointRepo.GetLatest(ctx, taskID)
		require.NoError(t, err)
		if cp != nil && cp.Step >= 50 {
			recoveredTasks++
		}
	}
	assert.True(t, recoveredTasks > 0, "at least one task should have been recovered from checkpoint")
}
