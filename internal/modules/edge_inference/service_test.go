package edge_inference

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"

	"edgescheduler/internal/common/eventbus"
	"edgescheduler/internal/testutils"
)

func setupInferenceTestDB(t *testing.T) *gorm.DB {
	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{})
	require.NoError(t, err)

	err = db.AutoMigrate(&AIModel{}, &ModelDeployment{}, &InferenceTask{})
	require.NoError(t, err)

	return db
}

func TestInferenceService_RegisterModel_NormalFlow(t *testing.T) {
	t.Parallel()

	testCases := []struct {
		name        string
		buildModel  func() *AIModel
		expectStatus ModelStatus
	}{
		{
			name: "注册计算机视觉模型",
			buildModel: func() *AIModel {
				return testutils.NewAIModelBuilder().
					WithModelID("model_cv_001").
					WithName("目标检测模型").
					WithVersion("v1.0").
					WithType("computer_vision").
					WithFormat("onnx").
					WithSizeBytes(1024 * 1024 * 50).
					Build()
			},
			expectStatus: ModelStatusPending,
		},
		{
			name: "注册NLP模型",
			buildModel: func() *AIModel {
				return testutils.NewAIModelBuilder().
					WithModelID("model_nlp_001").
					WithName("文本分类模型").
					WithVersion("v2.1").
					WithType("nlp").
					WithFormat("tensorrt").
					WithSizeBytes(1024 * 1024 * 200).
					WithMetadata(map[string]interface{}{
						"vocab_size": 30000,
						"layers":     12,
					}).
					Build()
			},
			expectStatus: ModelStatusPending,
		},
	}

	for _, tc := range testCases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			db := setupInferenceTestDB(t)
			mockEB := testutils.NewMockEventBus()
			service := NewInferenceServiceWithDeps(db, mockEB)
			ctx := testutils.GetTestContext()

			model := tc.buildModel()
			result, err := service.RegisterModel(ctx, model)

			require.NoError(t, err)
			require.NotNil(t, result)
			assert.Equal(t, tc.expectStatus, result.Status)
			assert.Equal(t, model.ModelID, result.ModelID)
			assert.Equal(t, model.Name, result.Name)
			assert.Equal(t, model.Version, result.Version)
			assert.Equal(t, model.Type, result.Type)
			assert.Equal(t, model.Format, result.Format)
			assert.Equal(t, model.SizeBytes, result.SizeBytes)

			var count int64
			db.Model(&AIModel{}).Where("model_id = ?", model.ModelID).Count(&count)
			assert.Equal(t, int64(1), count)
		})
	}
}

func TestInferenceService_RegisterModel_ErrorCases(t *testing.T) {
	t.Parallel()

	t.Run("重复注册模型", func(t *testing.T) {
		t.Parallel()

		db := setupInferenceTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewInferenceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		model := testutils.NewAIModelBuilder().
			WithModelID("model_dup").
			Build()

		_, err := service.RegisterModel(ctx, model)
		require.NoError(t, err)

		_, err = service.RegisterModel(ctx, model)
		require.Error(t, err)
		assert.Contains(t, err.Error(), "already registered")
	})

	t.Run("模型ID为空", func(t *testing.T) {
		t.Parallel()

		db := setupInferenceTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewInferenceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		model := testutils.NewAIModelBuilder().
			WithModelID("").
			Build()

		result, err := service.RegisterModel(ctx, model)
		if err == nil {
			assert.NotNil(t, result)
		}
	})
}

func TestInferenceService_CreateInferenceTask_NormalFlow(t *testing.T) {
	t.Parallel()

	testCases := []struct {
		name      string
		buildReq  func() *InferenceRequest
	}{
		{
			name: "创建标准推理任务",
			buildReq: func() *InferenceRequest {
				return testutils.NewInferenceRequestBuilder().
					WithModelID("model_task_001").
					WithDeviceID("dev_edge_001").
					WithInputData(map[string]interface{}{
						"image_url": "s3://images/sample.jpg",
						"threshold": 0.85,
					}).
					WithPriority(5).
					WithCallbackURL("https://api.example.com/callback").
					Build()
			},
		},
		{
			name: "创建低优先级任务",
			buildReq: func() *InferenceRequest {
				return testutils.NewInferenceRequestBuilder().
					WithModelID("model_task_002").
					WithDeviceID("dev_edge_002").
					WithInputData(map[string]interface{}{"data": "test"}).
					WithPriority(0).
					Build()
			},
		},
	}

	for _, tc := range testCases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			db := setupInferenceTestDB(t)
			mockEB := testutils.NewMockEventBus()
			service := NewInferenceServiceWithDeps(db, mockEB)
			ctx := testutils.GetTestContext()

			req := tc.buildReq()
			task, err := service.CreateInferenceTask(ctx, req)

			require.NoError(t, err)
			require.NotNil(t, task)
			assert.NotEmpty(t, task.TaskID)
			assert.Equal(t, req.ModelID, task.ModelID)
			assert.Equal(t, req.DeviceID, task.DeviceID)
			assert.Equal(t, TaskStatusPending, task.Status)
			assert.Equal(t, req.Priority, task.Priority)
			assert.Equal(t, req.CallbackURL, task.CallbackURL)
			assert.NotNil(t, task.InputData)
			assert.Equal(t, req.InputData["image_url"], task.InputData["image_url"])

			var count int64
			db.Model(&InferenceTask{}).Where("task_id = ?", task.TaskID).Count(&count)
			assert.Equal(t, int64(1), count)

			events := mockEB.GetPublishedEventsByType(eventbus.EventInferenceTaskCreated)
			assert.Len(t, events, 1)
			assert.Equal(t, task.TaskID, events[0].Payload["task_id"])
		})
	}
}

func TestInferenceService_CreateInferenceTask_BoundaryCases(t *testing.T) {
	t.Parallel()

	t.Run("空输入数据", func(t *testing.T) {
		t.Parallel()

		db := setupInferenceTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewInferenceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		req := testutils.NewInferenceRequestBuilder().
			WithEmptyInputData().
			Build()

		task, err := service.CreateInferenceTask(ctx, req)
		if err != nil {
			t.Logf("空输入数据处理结果: %v", err)
		} else {
			assert.NotNil(t, task)
		}
	})

	t.Run("零优先级", func(t *testing.T) {
		t.Parallel()

		db := setupInferenceTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewInferenceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		req := testutils.NewInferenceRequestBuilder().
			WithPriority(0).
			Build()

		task, err := service.CreateInferenceTask(ctx, req)
		require.NoError(t, err)
		assert.Equal(t, 0, task.Priority)
	})

	t.Run("负优先级", func(t *testing.T) {
		t.Parallel()

		db := setupInferenceTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewInferenceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		req := testutils.NewInferenceRequestBuilder().
			WithPriority(-1).
			Build()

		task, err := service.CreateInferenceTask(ctx, req)
		require.NoError(t, err)
		assert.Equal(t, -1, task.Priority)
	})
}

func TestInferenceService_ConcurrentTaskCreation(t *testing.T) {
	t.Parallel()

	db := setupInferenceTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewInferenceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	concurrency := 100
	var wg sync.WaitGroup
	helper := testutils.NewConcurrentTestHelper()
	taskIDs := make([]string, concurrency)
	var mu sync.Mutex

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()

			req := testutils.NewInferenceRequestBuilder().
				WithModelID(fmt.Sprintf("model_concurrent_%d", idx%5)).
				WithDeviceID(fmt.Sprintf("dev_concurrent_%d", idx%10)).
				WithInputData(map[string]interface{}{
					"batch": idx,
					"ts":    time.Now().UnixNano(),
				}).
				WithPriority(idx % 10).
				Build()

			task, err := service.CreateInferenceTask(ctx, req)
			if err != nil {
				helper.AddError(fmt.Errorf("任务%d创建失败: %w", idx, err))
				return
			}
			if task == nil {
				helper.AddError(fmt.Errorf("任务%d返回为空", idx))
				return
			}

			mu.Lock()
			taskIDs[idx] = task.TaskID
			mu.Unlock()

			helper.IncrementSuccess()
		}(i)
	}

	wg.Wait()

	assert.False(t, helper.HasErrors(), "并发创建任务出现错误: %v", helper.GetErrors())
	assert.Equal(t, concurrency, helper.GetSuccessCount())

	var total int64
	db.Model(&InferenceTask{}).Count(&total)
	assert.Equal(t, int64(concurrency), total)

	uniqueIDs := make(map[string]bool)
	for _, id := range taskIDs {
		assert.NotEmpty(t, id)
		uniqueIDs[id] = true
	}
	assert.Len(t, uniqueIDs, concurrency, "TaskID应该唯一")

	assert.Equal(t, concurrency, mockEB.GetPublishedEventCount())
}

func TestInferenceService_ConcurrentTaskCreation_RateLimit(t *testing.T) {
	t.Parallel()

	db := setupInferenceTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewInferenceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	concurrency := 200
	burst := 50
	var wg sync.WaitGroup
	helper := testutils.NewConcurrentTestHelper()

	for batch := 0; batch < concurrency/burst; batch++ {
		for i := 0; i < burst; i++ {
			wg.Add(1)
			go func(b, idx int) {
				defer wg.Done()

				req := testutils.NewInferenceRequestBuilder().
					WithModelID(fmt.Sprintf("model_ratelimit_%d", idx%3)).
					WithDeviceID(fmt.Sprintf("dev_ratelimit_%d", idx%5)).
					WithInputData(map[string]interface{}{"batch": b, "idx": idx}).
					Build()

				_, err := service.CreateInferenceTask(ctx, req)
				if err != nil {
					helper.AddError(fmt.Errorf("批量%d任务%d失败: %w", b, idx, err))
					return
				}
				helper.IncrementSuccess()
			}(batch, i)
		}
		wg.Wait()
		time.Sleep(10 * time.Millisecond)
	}

	assert.False(t, helper.HasErrors(), "突发流量测试出现错误: %v", helper.GetErrors())
	assert.Equal(t, concurrency, helper.GetSuccessCount())

	var total int64
	db.Model(&InferenceTask{}).Count(&total)
	assert.Equal(t, int64(concurrency), total)
}

func TestInferenceService_ProcessTaskResult_NormalFlow(t *testing.T) {
	t.Parallel()

	db := setupInferenceTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewInferenceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	task := &InferenceTask{
		TaskID:    "task_result_001",
		ModelID:   "model_001",
		DeviceID:  "dev_001",
		InputData: map[string]interface{}{"test": "data"},
		Status:    TaskStatusRunning,
	}
	db.Create(task)

	mockResult := map[string]interface{}{
		"predictions": []interface{}{
			map[string]interface{}{"class": "cat", "confidence": 0.95},
			map[string]interface{}{"class": "dog", "confidence": 0.87},
		},
		"inference_time_ms": 45.5,
	}

	err := service.ProcessTaskResult(ctx, "task_result_001", mockResult, nil)

	require.NoError(t, err)

	var updated InferenceTask
	db.Where("task_id = ?", "task_result_001").First(&updated)
	assert.Equal(t, TaskStatusCompleted, updated.Status)
	assert.NotNil(t, updated.CompletedAt)
	assert.NotNil(t, updated.OutputData)
	assert.Equal(t, mockResult["inference_time_ms"], updated.OutputData["inference_time_ms"])

	events := mockEB.GetPublishedEventsByType(eventbus.EventInferenceTaskCompleted)
	assert.Len(t, events, 1)
	assert.Equal(t, "task_result_001", events[0].Payload["task_id"])
}

func TestInferenceService_ProcessTaskResult_ErrorCases(t *testing.T) {
	t.Parallel()

	t.Run("处理失败任务结果", func(t *testing.T) {
		t.Parallel()

		db := setupInferenceTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewInferenceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		task := &InferenceTask{
			TaskID:    "task_fail_001",
			ModelID:   "model_001",
			DeviceID:  "dev_001",
			InputData: map[string]interface{}{"test": "data"},
			Status:    TaskStatusRunning,
		}
		db.Create(task)

		taskErr := fmt.Errorf("GPU内存不足")
		err := service.ProcessTaskResult(ctx, "task_fail_001", nil, taskErr)

		require.NoError(t, err)

		var updated InferenceTask
		db.Where("task_id = ?", "task_fail_001").First(&updated)
		assert.Equal(t, TaskStatusFailed, updated.Status)
		assert.Equal(t, "GPU内存不足", updated.ErrorDetail)

		events := mockEB.GetPublishedEventsByType(eventbus.EventInferenceTaskFailed)
		assert.Len(t, events, 1)
	})

	t.Run("处理不存在的任务", func(t *testing.T) {
		t.Parallel()

		db := setupInferenceTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewInferenceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		err := service.ProcessTaskResult(ctx, "task_nonexistent", nil, nil)

		require.Error(t, err)
		assert.Contains(t, err.Error(), "not found")
	})
}

func TestInferenceService_ConcurrentTaskResultProcessing(t *testing.T) {
	t.Parallel()

	db := setupInferenceTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewInferenceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	taskCount := 50
	taskIDs := make([]string, taskCount)

	for i := 0; i < taskCount; i++ {
		task := &InferenceTask{
			TaskID:    fmt.Sprintf("task_concurrent_result_%03d", i),
			ModelID:   "model_concurrent",
			DeviceID:  "dev_concurrent",
			InputData: map[string]interface{}{"idx": i},
			Status:    TaskStatusRunning,
		}
		db.Create(task)
		taskIDs[i] = task.TaskID
	}

	var wg sync.WaitGroup
	helper := testutils.NewConcurrentTestHelper()
	var successCount atomic.Int32
	var failCount atomic.Int32

	for i, taskID := range taskIDs {
		wg.Add(1)
		go func(idx int, tID string) {
			defer wg.Done()

			var resultErr error
			if idx%3 == 0 {
				resultErr = fmt.Errorf("模拟错误_%d", idx)
			}

			mockResult := map[string]interface{}{
				"result": idx * 100,
				"ts":     time.Now().UnixNano(),
			}

			err := service.ProcessTaskResult(ctx, tID, mockResult, resultErr)
			if err != nil {
				helper.AddError(fmt.Errorf("任务%s处理失败: %w", tID, err))
				return
			}

			if resultErr != nil {
				failCount.Add(1)
			} else {
				successCount.Add(1)
			}
			helper.IncrementSuccess()
		}(i, taskID)
	}

	wg.Wait()

	assert.False(t, helper.HasErrors(), "并发处理结果出现错误: %v", helper.GetErrors())
	assert.Equal(t, taskCount, helper.GetSuccessCount())
	assert.Equal(t, int32(taskCount/3+1), failCount.Load())
	assert.Equal(t, int32(taskCount-taskCount/3-1), successCount.Load())

	var completed int64
	db.Model(&InferenceTask{}).Where("status = ?", TaskStatusCompleted).Count(&completed)
	assert.Equal(t, int64(successCount.Load()), completed)

	var failed int64
	db.Model(&InferenceTask{}).Where("status = ?", TaskStatusFailed).Count(&failed)
	assert.Equal(t, int64(failCount.Load()), failed)

	completedEvents := mockEB.GetPublishedEventsByType(eventbus.EventInferenceTaskCompleted)
	assert.Len(t, completedEvents, int(successCount.Load()))

	failedEvents := mockEB.GetPublishedEventsByType(eventbus.EventInferenceTaskFailed)
	assert.Len(t, failedEvents, int(failCount.Load()))
}

func TestInferenceService_GetTask_NormalFlow(t *testing.T) {
	t.Parallel()

	db := setupInferenceTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewInferenceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	task := &InferenceTask{
		TaskID:    "task_get_001",
		ModelID:   "model_001",
		DeviceID:  "dev_001",
		InputData: map[string]interface{}{"test": "data"},
		Status:    TaskStatusPending,
	}
	db.Create(task)

	result, err := service.GetTask(ctx, "task_get_001")

	require.NoError(t, err)
	require.NotNil(t, result)
	assert.Equal(t, "task_get_001", result.TaskID)
	assert.Equal(t, TaskStatusPending, result.Status)
}

func TestInferenceService_GetTask_ErrorCases(t *testing.T) {
	t.Parallel()

	db := setupInferenceTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewInferenceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	result, err := service.GetTask(ctx, "task_nonexistent")

	require.Error(t, err)
	assert.Contains(t, err.Error(), "not found")
	assert.Nil(t, result)
}

func TestInferenceService_ListTasks_NormalFlow(t *testing.T) {
	t.Parallel()

	db := setupInferenceTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewInferenceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	models := []string{"model_a", "model_b"}
	devices := []string{"dev_1", "dev_2", "dev_3"}
	statuses := []TaskStatus{TaskStatusPending, TaskStatusRunning, TaskStatusCompleted, TaskStatusFailed}

	for i := 0; i < 30; i++ {
		task := &InferenceTask{
			TaskID:    fmt.Sprintf("task_list_%03d", i),
			ModelID:   models[i%2],
			DeviceID:  devices[i%3],
			InputData: map[string]interface{}{"idx": i},
			Status:    statuses[i%4],
		}
		db.Create(task)
	}

	t.Run("全量查询", func(t *testing.T) {
		tasks, total, err := service.ListTasks(ctx, map[string]interface{}{}, 0, 10)
		require.NoError(t, err)
		assert.Equal(t, int64(30), total)
		assert.Len(t, tasks, 10)
	})

	t.Run("按状态过滤", func(t *testing.T) {
		tasks, total, err := service.ListTasks(ctx, map[string]interface{}{
			"status": string(TaskStatusCompleted),
		}, 0, 20)
		require.NoError(t, err)
		assert.Equal(t, int64(7), total)
		assert.Len(t, tasks, 7)
	})

	t.Run("按设备过滤", func(t *testing.T) {
		tasks, total, err := service.ListTasks(ctx, map[string]interface{}{
			"device_id": "dev_1",
		}, 0, 20)
		require.NoError(t, err)
		assert.Equal(t, int64(10), total)
		assert.Len(t, tasks, 10)
	})

	t.Run("按模型过滤", func(t *testing.T) {
		tasks, total, err := service.ListTasks(ctx, map[string]interface{}{
			"model_id": "model_a",
		}, 0, 20)
		require.NoError(t, err)
		assert.Equal(t, int64(15), total)
		assert.Len(t, tasks, 15)
	})

	t.Run("分页查询第二页", func(t *testing.T) {
		tasks, total, err := service.ListTasks(ctx, map[string]interface{}{}, 10, 10)
		require.NoError(t, err)
		assert.Equal(t, int64(30), total)
		assert.Len(t, tasks, 10)
	})
}

func TestInferenceService_ListModels_NormalFlow(t *testing.T) {
	t.Parallel()

	db := setupInferenceTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewInferenceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	for i := 0; i < 10; i++ {
		model := testutils.NewAIModelBuilder().
			WithModelID(fmt.Sprintf("model_list_%03d", i)).
			WithName(fmt.Sprintf("模型%d", i)).
			Build()
		_, err := service.RegisterModel(ctx, model)
		require.NoError(t, err)
	}

	models, total, err := service.ListModels(ctx, 0, 5)
	require.NoError(t, err)
	assert.Equal(t, int64(10), total)
	assert.Len(t, models, 5)
}

func TestInferenceService_DeployModel_NormalFlow(t *testing.T) {
	t.Parallel()

	db := setupInferenceTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewInferenceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	model := testutils.NewAIModelBuilder().
		WithModelID("model_deploy_001").
		Build()
	_, err := service.RegisterModel(ctx, model)
	require.NoError(t, err)

	req := &ModelDeployRequest{
		ModelID:  "model_deploy_001",
		DeviceID: "dev_deploy_001",
	}

	deployment, err := service.DeployModel(ctx, req)

	require.NoError(t, err)
	require.NotNil(t, deployment)
	assert.Equal(t, ModelStatusDeploying, deployment.Status)
	assert.Equal(t, "model_deploy_001", deployment.ModelID)
	assert.Equal(t, "dev_deploy_001", deployment.DeviceID)
	assert.NotEmpty(t, deployment.DeploymentID)
}

func TestInferenceService_DeployModel_ErrorCases(t *testing.T) {
	t.Parallel()

	t.Run("部署不存在的模型", func(t *testing.T) {
		t.Parallel()

		db := setupInferenceTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewInferenceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		req := &ModelDeployRequest{
			ModelID:  "model_nonexistent",
			DeviceID: "dev_001",
		}

		deployment, err := service.DeployModel(ctx, req)

		require.Error(t, err)
		assert.Contains(t, err.Error(), "not found")
		assert.Nil(t, deployment)
	})
}

func TestInferenceService_ConcurrentMixedOperations(t *testing.T) {
	t.Parallel()

	db := setupInferenceTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewInferenceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	for i := 0; i < 5; i++ {
		model := testutils.NewAIModelBuilder().
			WithModelID(fmt.Sprintf("model_mixed_%d", i)).
			Build()
		_, err := service.RegisterModel(ctx, model)
		require.NoError(t, err)
	}

	concurrency := 150
	var wg sync.WaitGroup
	helper := testutils.NewConcurrentTestHelper()

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()

			switch idx % 3 {
			case 0:
				req := testutils.NewInferenceRequestBuilder().
					WithModelID(fmt.Sprintf("model_mixed_%d", idx%5)).
					WithDeviceID(fmt.Sprintf("dev_mixed_%d", idx%10)).
					WithInputData(map[string]interface{}{"idx": idx}).
					Build()
				_, err := service.CreateInferenceTask(ctx, req)
				if err != nil {
					helper.AddError(fmt.Errorf("创建任务失败: %w", err))
				} else {
					helper.IncrementSuccess()
				}

			case 1:
				_, total, err := service.ListTasks(ctx, map[string]interface{}{
					"status": string(TaskStatusPending),
				}, 0, 10)
				if err != nil {
					helper.AddError(fmt.Errorf("查询任务失败: %w", err))
				} else {
					assert.GreaterOrEqual(t, total, int64(0))
					helper.IncrementSuccess()
				}

			case 2:
				_, total, err := service.ListModels(ctx, 0, 10)
				if err != nil {
					helper.AddError(fmt.Errorf("查询模型失败: %w", err))
				} else {
					assert.GreaterOrEqual(t, total, int64(5))
					helper.IncrementSuccess()
				}
			}
		}(i)
	}

	wg.Wait()

	assert.False(t, helper.HasErrors(), "混合操作出现错误: %v", helper.GetErrors())
	assert.Equal(t, concurrency, helper.GetSuccessCount())
}

func TestInferenceService_TaskScheduler_ConcurrentExecution(t *testing.T) {
	t.Parallel()

	db := setupInferenceTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewInferenceServiceWithDeps(db, mockEB)
	ctx, cancel := context.WithCancel(testutils.GetTestContext())
	defer cancel()

	maxConcurrent := 5
	go service.StartTaskScheduler(ctx, maxConcurrent)

	taskCount := 20
	for i := 0; i < taskCount; i++ {
		req := testutils.NewInferenceRequestBuilder().
			WithModelID("model_sched").
			WithDeviceID("dev_sched").
			WithInputData(map[string]interface{}{"idx": i}).
			Build()

		_, err := service.CreateInferenceTask(ctx, req)
		require.NoError(t, err)
	}

	time.Sleep(3 * time.Second)

	var completed int64
	db.Model(&InferenceTask{}).Where("status = ?", TaskStatusCompleted).Count(&completed)
	assert.GreaterOrEqual(t, completed, int64(5), "至少应有5个任务完成")

	cancel()
}

func TestInferenceService_DatabaseFailure(t *testing.T) {
	t.Parallel()

	db, _ := gorm.Open(sqlite.Open("file::memory:"), &gorm.Config{})
	mockEB := testutils.NewMockEventBus()
	service := NewInferenceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	req := testutils.NewInferenceRequestBuilder().Build()

	task, err := service.CreateInferenceTask(ctx, req)
	require.Error(t, err)
	assert.Nil(t, task)
}
