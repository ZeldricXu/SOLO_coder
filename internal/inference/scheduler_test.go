package inference

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/edgeplatform/session306/internal/data"
	"github.com/edgeplatform/session306/internal/mocks"
	"github.com/edgeplatform/session306/internal/model"
	"github.com/edgeplatform/session306/internal/testfactory"
	"github.com/edgeplatform/session306/pkg/events"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

type inferenceTestFixture struct {
	scheduler      *InferenceScheduler
	eventBus       *mocks.MockEventBus
	logger         *zap.Logger
	factory        *testfactory.TestDataFactory
	mockExecutor   *mocks.MockInferenceExecutor
	db             *gorm.DB
	da             *data.DataAccess
	ctx            context.Context
	cancel         context.CancelFunc
}

func setupInferenceTest(t *testing.T) *inferenceTestFixture {
	t.Helper()

	logger, _ := zap.NewDevelopment()
	eventBus := mocks.NewMockEventBus()
	factory := testfactory.NewTestDataFactory()
	mockExecutor := mocks.NewMockInferenceExecutor()

	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	require.NoError(t, err)

	err = db.AutoMigrate(
		&model.AIModel{},
		&model.InferenceTask{},
	)
	require.NoError(t, err)

	da := &data.DataAccess{}
	da.SetDB(db)

	ctx, cancel := context.WithCancel(context.Background())

	scheduler := NewInferenceScheduler(da, eventBus, logger, 2)
	scheduler.executor = mockExecutor
	scheduler.pollingInterval = 10 * time.Millisecond

	eventBus.On("Publish", mock.Anything, mock.Anything).Return(nil)
	eventBus.On("Subscribe", mock.Anything, mock.Anything).Return("sub_test")
	eventBus.On("Unsubscribe", mock.Anything).Return()
	eventBus.On("Close").Return()

	return &inferenceTestFixture{
		scheduler:    scheduler,
		eventBus:     eventBus,
		logger:       logger,
		factory:      factory,
		mockExecutor: mockExecutor,
		db:           db,
		da:           da,
		ctx:          ctx,
		cancel:       cancel,
	}
}

func (f *inferenceTestFixture) teardown() {
	f.cancel()
	f.mockExecutor.Reset()
	f.eventBus.Reset()
	sqlDB, _ := f.db.DB()
	if sqlDB != nil {
		sqlDB.Close()
	}
}

func TestNewInferenceScheduler(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	assert.NotNil(t, f.scheduler)
	assert.NotNil(t, f.scheduler.executor)
	assert.NotNil(t, f.scheduler.taskQueue)
	assert.NotNil(t, f.scheduler.deviceModels)
	assert.Equal(t, 2, f.scheduler.workerCount)
	assert.Equal(t, 10*time.Millisecond, f.scheduler.pollingInterval)
}

func TestNewInferenceScheduler_DefaultWorkers(t *testing.T) {
	t.Parallel()

	logger, _ := zap.NewDevelopment()
	eventBus := mocks.NewMockEventBus()
	mockDA := mocks.NewMockDataAccess()

	scheduler := NewInferenceScheduler(mockDA.DataAccess, eventBus, logger, 0)

	assert.Equal(t, 3, scheduler.workerCount)
}

func TestRegisterModel(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	aiModel.ModelID = ""

	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)

	require.NoError(t, err)
	assert.NotNil(t, created)
	assert.NotEmpty(t, created.ModelID)
	assert.Equal(t, model.ModelStatusPending, created.Status)
	assert.Equal(t, "image-classifier", created.Name)
	assert.Equal(t, "v2.1.0", created.Version)
	assert.NotNil(t, created.CreatedAt)
	assert.NotNil(t, created.UpdatedAt)

	var saved model.AIModel
	err = f.db.Where("model_id = ?", created.ModelID).First(&saved).Error
	require.NoError(t, err)
	assert.Equal(t, created.Name, saved.Name)
}

func TestRegisterModel_Duplicate(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()

	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)
	assert.NotNil(t, created)
}

func TestGetModel(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	found, err := f.scheduler.GetModel(f.ctx, created.ModelID)
	require.NoError(t, err)
	assert.NotNil(t, found)
	assert.Equal(t, created.ModelID, found.ModelID)
	assert.Equal(t, created.Name, found.Name)
}

func TestGetModel_NotFound(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	found, err := f.scheduler.GetModel(f.ctx, "nonexistent_model")
	assert.Error(t, err)
	assert.Nil(t, found)
}

func TestListModels(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	for i := 0; i < 5; i++ {
		aiModel := f.factory.CreateInferenceModel()
		aiModel.Status = model.ModelStatusReady
		_, err := f.scheduler.RegisterModel(f.ctx, aiModel)
		require.NoError(t, err)
	}

	models, total, err := f.scheduler.ListModels(f.ctx, model.ModelStatusReady, 0, 10)
	require.NoError(t, err)
	assert.Equal(t, int64(5), total)
	assert.Len(t, models, 5)
}

func TestListModels_WithStatusFilter(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	for i := 0; i < 3; i++ {
		aiModel := f.factory.CreateInferenceModel()
		aiModel.Status = model.ModelStatusReady
		_, err := f.scheduler.RegisterModel(f.ctx, aiModel)
		require.NoError(t, err)
	}

	for i := 0; i < 2; i++ {
		aiModel := f.factory.CreateInferenceModel()
		aiModel.Status = model.ModelStatusError
		_, err := f.scheduler.RegisterModel(f.ctx, aiModel)
		require.NoError(t, err)
	}

	models, total, err := f.scheduler.ListModels(f.ctx, model.ModelStatusReady, 0, 10)
	require.NoError(t, err)
	assert.Equal(t, int64(3), total)
	assert.Len(t, models, 3)
}

func TestDeployModel(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	deployReq := &model.ModelDeployRequest{
		ModelID:   created.ModelID,
		DeviceIDs: []string{"dev-001", "dev-002"},
	}

	err = f.scheduler.DeployModel(f.ctx, deployReq)
	require.NoError(t, err)

	var saved model.AIModel
	err = f.db.Where("model_id = ?", created.ModelID).First(&saved).Error
	require.NoError(t, err)
	assert.Equal(t, model.ModelStatusDeploying, saved.Status)
	assert.NotNil(t, saved.DeployedAt)

	f.scheduler.mu.RLock()
	assert.NotNil(t, f.scheduler.deviceModels["dev-001"][created.ModelID])
	assert.NotNil(t, f.scheduler.deviceModels["dev-002"][created.ModelID])
	f.scheduler.mu.RUnlock()
}

func TestDeployModel_NotFound(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	req := &model.ModelDeployRequest{
		ModelID:   "nonexistent",
		DeviceIDs: []string{"dev-001"},
	}

	err := f.scheduler.DeployModel(f.ctx, req)
	assert.Error(t, err)
}

func TestSubmitTask(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	aiModel.Status = model.ModelStatusReady
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	deployReq := &model.ModelDeployRequest{
		ModelID:   created.ModelID,
		DeviceIDs: []string{"dev-001"},
	}
	err = f.scheduler.DeployModel(f.ctx, deployReq)
	require.NoError(t, err)

	f.db.Model(&model.AIModel{}).Where("model_id = ?", created.ModelID).Update("status", model.ModelStatusReady)

	req := testfactory.NewInferenceRequestBuilder().
		WithModelID(created.ModelID).
		WithDeviceID("dev-001").
		WithInputData(`{"image": "base64_data"}`).
		WithPriority(5).
		Build()

	task, err := f.scheduler.SubmitTask(f.ctx, req)

	require.NoError(t, err)
	assert.NotNil(t, task)
	assert.NotEmpty(t, task.TaskID)
	assert.Equal(t, model.InferenceStatusQueued, task.Status)
	assert.Equal(t, 5, task.Priority)
	assert.Equal(t, created.ModelID, task.ModelID)
	assert.Equal(t, "dev-001", task.DeviceID)

	var saved model.InferenceTask
	err = f.db.Where("task_id = ?", task.TaskID).First(&saved).Error
	require.NoError(t, err)
	assert.Equal(t, task.TaskID, saved.TaskID)
}

func TestSubmitTask_ModelNotDeployed(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	req := testfactory.NewInferenceRequestBuilder().
		WithModelID(created.ModelID).
		WithDeviceID("dev-001").
		WithInputData(`{"image": "base64_data"}`).
		Build()

	task, err := f.scheduler.SubmitTask(f.ctx, req)
	assert.Error(t, err)
	assert.Nil(t, task)
	assert.Contains(t, err.Error(), "not deployed")
}

func TestSubmitTask_InvalidInput(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	req := testfactory.NewInferenceRequestBuilder().
		WithModelID("").
		WithDeviceID("").
		WithInputData("").
		Build()

	task, err := f.scheduler.SubmitTask(f.ctx, req)
	assert.Error(t, err)
	assert.Nil(t, task)
}

func TestGetTask(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	aiModel.Status = model.ModelStatusReady
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	deployReq := &model.ModelDeployRequest{
		ModelID:   created.ModelID,
		DeviceIDs: []string{"dev-001"},
	}
	err = f.scheduler.DeployModel(f.ctx, deployReq)
	require.NoError(t, err)

	f.db.Model(&model.AIModel{}).Where("model_id = ?", created.ModelID).Update("status", model.ModelStatusReady)

	req := testfactory.NewInferenceRequestBuilder().
		WithModelID(created.ModelID).
		WithDeviceID("dev-001").
		WithInputData(`{"image": "base64_data"}`).
		Build()

	submitted, err := f.scheduler.SubmitTask(f.ctx, req)
	require.NoError(t, err)

	found, err := f.scheduler.GetTask(f.ctx, submitted.TaskID)
	require.NoError(t, err)
	assert.NotNil(t, found)
	assert.Equal(t, submitted.TaskID, found.TaskID)
	assert.Equal(t, submitted.ModelID, found.ModelID)
}

func TestGetTask_NotFound(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	found, err := f.scheduler.GetTask(f.ctx, "nonexistent_task")
	assert.Error(t, err)
	assert.Nil(t, found)
}

func TestListTasks(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	aiModel.Status = model.ModelStatusReady
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	deployReq := &model.ModelDeployRequest{
		ModelID:   created.ModelID,
		DeviceIDs: []string{"dev-001"},
	}
	err = f.scheduler.DeployModel(f.ctx, deployReq)
	require.NoError(t, err)

	f.db.Model(&model.AIModel{}).Where("model_id = ?", created.ModelID).Update("status", model.ModelStatusReady)

	for i := 0; i < 5; i++ {
		req := testfactory.NewInferenceRequestBuilder().
			WithModelID(created.ModelID).
			WithDeviceID("dev-001").
			WithInputData(`{"data": "test"}`).
			Build()
		_, err := f.scheduler.SubmitTask(f.ctx, req)
		require.NoError(t, err)
	}

	tasks, total, err := f.scheduler.ListTasks(f.ctx, "dev-001", model.InferenceStatusQueued, 0, 10)
	require.NoError(t, err)
	assert.Equal(t, int64(5), total)
	assert.Len(t, tasks, 5)
}

func TestCancelTask(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	aiModel.Status = model.ModelStatusReady
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	deployReq := &model.ModelDeployRequest{
		ModelID:   created.ModelID,
		DeviceIDs: []string{"dev-001"},
	}
	err = f.scheduler.DeployModel(f.ctx, deployReq)
	require.NoError(t, err)

	f.db.Model(&model.AIModel{}).Where("model_id = ?", created.ModelID).Update("status", model.ModelStatusReady)

	req := testfactory.NewInferenceRequestBuilder().
		WithModelID(created.ModelID).
		WithDeviceID("dev-001").
		WithInputData(`{"image": "base64_data"}`).
		Build()

	submitted, err := f.scheduler.SubmitTask(f.ctx, req)
	require.NoError(t, err)

	err = f.scheduler.CancelTask(f.ctx, submitted.TaskID)
	require.NoError(t, err)

	var saved model.InferenceTask
	err = f.db.Where("task_id = ?", submitted.TaskID).First(&saved).Error
	require.NoError(t, err)
	assert.Equal(t, model.InferenceStatusCancelled, saved.Status)
}

func TestCancelTask_NotFound(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	err := f.scheduler.CancelTask(f.ctx, "nonexistent_task")
	assert.Error(t, err)
}

func TestProcessTask_Success(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	aiModel.Status = model.ModelStatusReady
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	deployReq := &model.ModelDeployRequest{
		ModelID:   created.ModelID,
		DeviceIDs: []string{"dev-001"},
	}
	err = f.scheduler.DeployModel(f.ctx, deployReq)
	require.NoError(t, err)

	f.scheduler.mu.Lock()
	f.scheduler.deviceModels["dev-001"][created.ModelID].Status = model.ModelStatusReady
	f.scheduler.mu.Unlock()

	task := testfactory.NewInferenceTaskBuilder().
		WithModelID(created.ModelID).
		WithDeviceID("dev-001").
		WithStatus(model.InferenceStatusQueued).
		WithInputData(`{"image": "test_data"}`).
		Build()

	f.db.Create(task)

	f.mockExecutor.MockResult = `{"predictions": [{"class": "cat", "confidence": 0.95}]}`

	f.scheduler.processTask(f.ctx, task)

	time.Sleep(20 * time.Millisecond)

	assert.Equal(t, 1, f.mockExecutor.ExecuteCount)
	assert.Equal(t, created.ModelID, f.mockExecutor.LastModel.ModelID)
	assert.Equal(t, task.TaskID, f.mockExecutor.LastTask.TaskID)

	var saved model.InferenceTask
	err = f.db.Where("task_id = ?", task.TaskID).First(&saved).Error
	require.NoError(t, err)
	assert.Equal(t, model.InferenceStatusCompleted, saved.Status)
	assert.NotNil(t, saved.OutputData)
	assert.Contains(t, *saved.OutputData, "predictions")
	assert.NotNil(t, saved.DurationMs)
	assert.NotNil(t, saved.EndTime)
	assert.Nil(t, saved.Error)
}

func TestProcessTask_ExecutorError(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	aiModel.Status = model.ModelStatusReady
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	deployReq := &model.ModelDeployRequest{
		ModelID:   created.ModelID,
		DeviceIDs: []string{"dev-001"},
	}
	err = f.scheduler.DeployModel(f.ctx, deployReq)
	require.NoError(t, err)

	f.scheduler.mu.Lock()
	f.scheduler.deviceModels["dev-001"][created.ModelID].Status = model.ModelStatusReady
	f.scheduler.mu.Unlock()

	task := testfactory.NewInferenceTaskBuilder().
		WithModelID(created.ModelID).
		WithDeviceID("dev-001").
		WithStatus(model.InferenceStatusQueued).
		WithInputData(`{"image": "test_data"}`).
		Build()

	f.db.Create(task)

	f.mockExecutor.ReturnError = true
	f.mockExecutor.ErrorMessage = "GPU out of memory"

	f.scheduler.processTask(f.ctx, task)

	time.Sleep(20 * time.Millisecond)

	assert.Equal(t, 1, f.mockExecutor.ExecuteCount)

	var saved model.InferenceTask
	err = f.db.Where("task_id = ?", task.TaskID).First(&saved).Error
	require.NoError(t, err)
	assert.Equal(t, model.InferenceStatusFailed, saved.Status)
	assert.NotNil(t, saved.Error)
	assert.Contains(t, *saved.Error, "GPU out of memory")
	assert.Nil(t, saved.OutputData)
}

func TestProcessTask_ModelNotFound(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	task := testfactory.NewInferenceTaskBuilder().
		WithModelID("nonexistent_model").
		WithDeviceID("dev-001").
		WithStatus(model.InferenceStatusQueued).
		Build()

	f.db.Create(task)

	f.scheduler.processTask(f.ctx, task)

	time.Sleep(20 * time.Millisecond)

	assert.Equal(t, 0, f.mockExecutor.ExecuteCount)

	var saved model.InferenceTask
	err := f.db.Where("task_id = ?", task.TaskID).First(&saved).Error
	require.NoError(t, err)
	assert.Equal(t, model.InferenceStatusFailed, saved.Status)
	assert.NotNil(t, saved.Error)
	assert.Contains(t, *saved.Error, "not found")
}

func TestProcessTask_AlreadyProcessed(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	task := testfactory.NewInferenceTaskBuilder().
		WithStatus(model.InferenceStatusCompleted).
		Build()

	f.db.Create(task)

	f.scheduler.processTask(f.ctx, task)

	assert.Equal(t, 0, f.mockExecutor.ExecuteCount)
}

func TestProcessTask_Timeout(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	aiModel.Status = model.ModelStatusReady
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	deployReq := &model.ModelDeployRequest{
		ModelID:   created.ModelID,
		DeviceIDs: []string{"dev-001"},
	}
	err = f.scheduler.DeployModel(f.ctx, deployReq)
	require.NoError(t, err)

	f.scheduler.mu.Lock()
	f.scheduler.deviceModels["dev-001"][created.ModelID].Status = model.ModelStatusReady
	f.scheduler.mu.Unlock()

	task := testfactory.NewInferenceTaskBuilder().
		WithModelID(created.ModelID).
		WithDeviceID("dev-001").
		WithStatus(model.InferenceStatusQueued).
		WithTimeout(1).
		WithInputData(`{"image": "test_data"}`).
		Build()

	f.db.Create(task)

	f.mockExecutor.ExecutionDelay = 2 * time.Second
	f.mockExecutor.ReturnError = false

	ctx, cancel := context.WithTimeout(f.ctx, 500*time.Millisecond)
	defer cancel()

	f.scheduler.processTask(ctx, task)

	time.Sleep(100 * time.Millisecond)
}

func TestLoadModels(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	model1 := f.factory.CreateInferenceModel()
	model1.Status = model.ModelStatusReady
	model1.DeviceIDs = []string{"dev-001"}
	_, err := f.scheduler.RegisterModel(f.ctx, model1)
	require.NoError(t, err)

	model2 := f.factory.CreateInferenceModel()
	model2.Status = model.ModelStatusReady
	model2.DeviceIDs = []string{"dev-001", "dev-002"}
	_, err = f.scheduler.RegisterModel(f.ctx, model2)
	require.NoError(t, err)

	model3 := f.factory.CreateInferenceModel()
	model3.Status = model.ModelStatusPending
	_, err = f.scheduler.RegisterModel(f.ctx, model3)
	require.NoError(t, err)

	err = f.scheduler.loadModels(f.ctx)
	require.NoError(t, err)

	f.scheduler.mu.RLock()
	assert.NotNil(t, f.scheduler.deviceModels["dev-001"])
	assert.NotNil(t, f.scheduler.deviceModels["dev-002"])
	assert.GreaterOrEqual(t, len(f.scheduler.deviceModels["dev-001"]), 2)
	assert.Len(t, f.scheduler.deviceModels["dev-002"], 1)
	f.scheduler.mu.RUnlock()
}

func TestConcurrentSubmitTasks(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	aiModel.Status = model.ModelStatusReady
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	deployReq := &model.ModelDeployRequest{
		ModelID:   created.ModelID,
		DeviceIDs: []string{"dev-001"},
	}
	err = f.scheduler.DeployModel(f.ctx, deployReq)
	require.NoError(t, err)

	f.db.Model(&model.AIModel{}).Where("model_id = ?", created.ModelID).Update("status", model.ModelStatusReady)

	var wg sync.WaitGroup
	concurrency := 20

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()

			req := testfactory.NewInferenceRequestBuilder().
				WithModelID(created.ModelID).
				WithDeviceID("dev-001").
				WithInputData(`{"data": "test"}`).
				WithPriority(i).
				Build()

			task, err := f.scheduler.SubmitTask(f.ctx, req)
			if err == nil {
				assert.NotNil(t, task)
			}
		}(i)
	}

	wg.Wait()

	var total int64
	f.db.Model(&model.InferenceTask{}).Count(&total)
	assert.Equal(t, int64(concurrency), total)
}

func TestConcurrentProcessTasks(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	aiModel.Status = model.ModelStatusReady
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	deployReq := &model.ModelDeployRequest{
		ModelID:   created.ModelID,
		DeviceIDs: []string{"dev-001"},
	}
	err = f.scheduler.DeployModel(f.ctx, deployReq)
	require.NoError(t, err)

	f.scheduler.mu.Lock()
	f.scheduler.deviceModels["dev-001"][created.ModelID].Status = model.ModelStatusReady
	f.scheduler.mu.Unlock()

	ctx, cancel := context.WithCancel(f.ctx)
	defer cancel()

	err = f.scheduler.Start(ctx)
	require.NoError(t, err)

	taskCount := 10
	for i := 0; i < taskCount; i++ {
		task := testfactory.NewInferenceTaskBuilder().
			WithModelID(created.ModelID).
			WithDeviceID("dev-001").
			WithStatus(model.InferenceStatusQueued).
			WithInputData(`{"image": "test"}`).
			Build()
		f.db.Create(task)
	}

	go f.scheduler.pollPendingTasks(ctx)

	time.Sleep(200 * time.Millisecond)

	assert.GreaterOrEqual(t, f.mockExecutor.ExecuteCount, 1)
}

func TestStartAndStop(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	ctx, cancel := context.WithCancel(f.ctx)
	defer cancel()

	err := f.scheduler.Start(ctx)
	require.NoError(t, err)

	time.Sleep(20 * time.Millisecond)

	f.scheduler.Stop()

	time.Sleep(20 * time.Millisecond)
}

func TestWorkerContextCancellation(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	ctx, cancel := context.WithCancel(f.ctx)

	go f.scheduler.worker(ctx, 0)

	time.Sleep(10 * time.Millisecond)

	cancel()

	time.Sleep(20 * time.Millisecond)
}

func TestFetchPendingTasks(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	aiModel.Status = model.ModelStatusReady
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	deployReq := &model.ModelDeployRequest{
		ModelID:   created.ModelID,
		DeviceIDs: []string{"dev-001"},
	}
	err = f.scheduler.DeployModel(f.ctx, deployReq)
	require.NoError(t, err)

	for i := 0; i < 5; i++ {
		task := testfactory.NewInferenceTaskBuilder().
			WithModelID(created.ModelID).
			WithDeviceID("dev-001").
			WithStatus(model.InferenceStatusQueued).
			WithInputData(`{"data": "test"}`).
			Build()
		f.db.Create(task)
	}

	f.scheduler.fetchPendingTasks(f.ctx)

	assert.GreaterOrEqual(t, len(f.scheduler.taskQueue), 1)
}

func TestTaskQueueFull(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	aiModel.Status = model.ModelStatusReady
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	deployReq := &model.ModelDeployRequest{
		ModelID:   created.ModelID,
		DeviceIDs: []string{"dev-001"},
	}
	err = f.scheduler.DeployModel(f.ctx, deployReq)
	require.NoError(t, err)

	for i := 0; i < 1100; i++ {
		task := testfactory.NewInferenceTaskBuilder().
			WithModelID(created.ModelID).
			WithDeviceID("dev-001").
			WithStatus(model.InferenceStatusQueued).
			WithInputData(`{"data": "test"}`).
			Build()
		f.db.Create(task)
	}

	f.scheduler.fetchPendingTasks(f.ctx)

	assert.Equal(t, 1000, len(f.scheduler.taskQueue))
}

func TestCompleteTask_Success(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	task := testfactory.NewInferenceTaskBuilder().
		WithStatus(model.InferenceStatusRunning).
		Build()

	f.db.Create(task)

	output := `{"result": "success"}`
	f.scheduler.completeTask(f.ctx, task, &output, nil)

	var saved model.InferenceTask
	err := f.db.Where("task_id = ?", task.TaskID).First(&saved).Error
	require.NoError(t, err)
	assert.Equal(t, model.InferenceStatusCompleted, saved.Status)
	assert.Equal(t, output, *saved.OutputData)
	assert.Nil(t, saved.Error)
	assert.NotNil(t, saved.DurationMs)
}

func TestCompleteTask_Failure(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	task := testfactory.NewInferenceTaskBuilder().
		WithStatus(model.InferenceStatusRunning).
		Build()

	f.db.Create(task)

	testErr := assert.AnError
	f.scheduler.completeTask(f.ctx, task, nil, testErr)

	var saved model.InferenceTask
	err := f.db.Where("task_id = ?", task.TaskID).First(&saved).Error
	require.NoError(t, err)
	assert.Equal(t, model.InferenceStatusFailed, saved.Status)
	assert.NotNil(t, saved.Error)
	assert.Nil(t, saved.OutputData)
}

func TestEventPublishing(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	aiModel.Status = model.ModelStatusReady
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	deployReq := &model.ModelDeployRequest{
		ModelID:   created.ModelID,
		DeviceIDs: []string{"dev-001"},
	}
	err = f.scheduler.DeployModel(f.ctx, deployReq)
	require.NoError(t, err)

	f.scheduler.mu.Lock()
	f.scheduler.deviceModels["dev-001"][created.ModelID].Status = model.ModelStatusReady
	f.scheduler.mu.Unlock()

	task := testfactory.NewInferenceTaskBuilder().
		WithModelID(created.ModelID).
		WithDeviceID("dev-001").
		WithStatus(model.InferenceStatusQueued).
		WithInputData(`{"image": "test_data"}`).
		WithTraceID("trace_test_001").
		Build()

	f.db.Create(task)

	f.mockExecutor.MockResult = `{"result": "test"}`

	f.scheduler.processTask(f.ctx, task)

	time.Sleep(50 * time.Millisecond)

	assert.GreaterOrEqual(t, f.eventBus.GetEventCount(), 1)

	lastEvent := f.eventBus.GetLastEvent()
	assert.NotNil(t, lastEvent)

	event, ok := lastEvent.(events.Event)
	if ok {
		assert.Equal(t, events.EventInferenceResult, event.Type)
		assert.Equal(t, "trace_test_001", event.TraceID)
		assert.Contains(t, event.Payload, "task_id")
		assert.Contains(t, event.Payload, "success")
	}
}

func TestResourceCleanup(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	ctx, cancel := context.WithCancel(f.ctx)

	err := f.scheduler.Start(ctx)
	require.NoError(t, err)

	aiModel := f.factory.CreateInferenceModel()
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	deployReq := &model.ModelDeployRequest{
		ModelID:   created.ModelID,
		DeviceIDs: []string{"dev-cleanup-1", "dev-cleanup-2"},
	}
	err = f.scheduler.DeployModel(f.ctx, deployReq)
	require.NoError(t, err)

	f.scheduler.mu.RLock()
	assert.NotNil(t, f.scheduler.deviceModels["dev-cleanup-1"])
	assert.NotNil(t, f.scheduler.deviceModels["dev-cleanup-2"])
	f.scheduler.mu.RUnlock()

	cancel()
	f.scheduler.Stop()

	time.Sleep(50 * time.Millisecond)

	select {
	case _, ok := <-f.scheduler.taskQueue:
		assert.False(t, ok, "task queue should be closed")
	default:
	}
}

func TestPriorityQueueOrdering(t *testing.T) {
	t.Parallel()

	f := setupInferenceTest(t)
	defer f.teardown()

	aiModel := f.factory.CreateInferenceModel()
	aiModel.Status = model.ModelStatusReady
	created, err := f.scheduler.RegisterModel(f.ctx, aiModel)
	require.NoError(t, err)

	deployReq := &model.ModelDeployRequest{
		ModelID:   created.ModelID,
		DeviceIDs: []string{"dev-001"},
	}
	err = f.scheduler.DeployModel(f.ctx, deployReq)
	require.NoError(t, err)

	f.db.Model(&model.AIModel{}).Where("model_id = ?", created.ModelID).Update("status", model.ModelStatusReady)

	priorities := []int{0, 5, 10, 3, 7}
	for _, p := range priorities {
		req := testfactory.NewInferenceRequestBuilder().
			WithModelID(created.ModelID).
			WithDeviceID("dev-001").
			WithInputData(`{"data": "test"}`).
			WithPriority(p).
			Build()
		_, err := f.scheduler.SubmitTask(f.ctx, req)
		require.NoError(t, err)
	}

	var tasks []model.InferenceTask
	f.db.Order("priority DESC, created_at ASC").Find(&tasks)

	assert.Equal(t, 10, tasks[0].Priority)
	assert.Equal(t, 7, tasks[1].Priority)
	assert.Equal(t, 5, tasks[2].Priority)
	assert.Equal(t, 3, tasks[3].Priority)
	assert.Equal(t, 0, tasks[4].Priority)
}
