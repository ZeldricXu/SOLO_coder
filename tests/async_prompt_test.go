package tests

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap/zaptest"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"

	"session133/internal/prompt"
)

func setupTestDB(t *testing.T) *gorm.DB {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	require.NoError(t, err)

	err = db.AutoMigrate(
		&prompt.Prompt{},
		&prompt.PromptVersion{},
		&prompt.ABTest{},
		&prompt.ABTestResult{},
	)
	require.NoError(t, err)

	return db
}

func TestAsyncTaskManager_SubmitTask(t *testing.T) {
	logger := zaptest.NewLogger(t)
	mgr := prompt.NewAsyncTaskManager(1, logger)

	processed := make(chan bool, 1)
	mgr.SetProcessor(func(ctx context.Context, task *prompt.AsyncTask) error {
		processed <- true
		task.Result = map[string]interface{}{"success": true}
		return nil
	})

	ctx, cancel := context.WithCancel(context.Background())
	mgr.Start(ctx)
	defer cancel()
	defer mgr.Stop()

	task, err := mgr.SubmitTask(prompt.TaskTypeABTestAnalysis, map[string]interface{}{
		"ab_test_id": "test_123",
	}, 0, "")
	require.NoError(t, err)
	assert.NotEmpty(t, task.ID)
	assert.Equal(t, prompt.TaskStatusPending, task.Status)

	select {
	case <-processed:
	case <-time.After(5 * time.Second):
		t.Fatal("Task was not processed")
	}

	retrievedTask, err := mgr.GetTask(task.ID)
	require.NoError(t, err)
	assert.Equal(t, prompt.TaskStatusCompleted, retrievedTask.Status)
	assert.NotNil(t, retrievedTask.Result)
	assert.True(t, retrievedTask.Result["success"].(bool))
}

func TestAsyncTaskManager_TaskRetry(t *testing.T) {
	logger := zaptest.NewLogger(t)
	mgr := prompt.NewAsyncTaskManager(1, logger)

	attempts := 0
	mgr.SetProcessor(func(ctx context.Context, task *prompt.AsyncTask) error {
		attempts++
		if attempts < 2 {
			return assert.AnError
		}
		task.Result = map[string]interface{}{"attempts": attempts}
		return nil
	})

	ctx, cancel := context.WithCancel(context.Background())
	mgr.Start(ctx)
	defer cancel()
	defer mgr.Stop()

	task, err := mgr.SubmitTask(prompt.TaskTypePromptEvaluation, map[string]interface{}{}, 2, "")
	require.NoError(t, err)

	time.Sleep(2 * time.Second)

	retrievedTask, err := mgr.GetTask(task.ID)
	require.NoError(t, err)
	assert.Equal(t, prompt.TaskStatusCompleted, retrievedTask.Status)
	assert.Equal(t, 2, attempts)
}

func TestAsyncTaskManager_TaskFailure(t *testing.T) {
	logger := zaptest.NewLogger(t)
	mgr := prompt.NewAsyncTaskManager(1, logger)

	mgr.SetProcessor(func(ctx context.Context, task *prompt.AsyncTask) error {
		return assert.AnError
	})

	ctx, cancel := context.WithCancel(context.Background())
	mgr.Start(ctx)
	defer cancel()
	defer mgr.Stop()

	task, err := mgr.SubmitTask(prompt.TaskTypeVersionCleanup, map[string]interface{}{}, 2, "")
	require.NoError(t, err)

	time.Sleep(2 * time.Second)

	retrievedTask, err := mgr.GetTask(task.ID)
	require.NoError(t, err)
	assert.Equal(t, prompt.TaskStatusFailed, retrievedTask.Status)
	assert.Equal(t, 2, retrievedTask.RetryCount)
	assert.NotEmpty(t, retrievedTask.Error)
}

func TestAsyncTaskManager_CancelTask(t *testing.T) {
	logger := zaptest.NewLogger(t)
	mgr := prompt.NewAsyncTaskManager(1, logger)

	processing := make(chan bool, 1)
	block := make(chan bool, 1)
	mgr.SetProcessor(func(ctx context.Context, task *prompt.AsyncTask) error {
		processing <- true
		<-block
		task.Result = map[string]interface{}{"done": true}
		return nil
	})

	ctx, cancel := context.WithCancel(context.Background())
	mgr.Start(ctx)
	defer cancel()
	defer mgr.Stop()

	task1, err := mgr.SubmitTask(prompt.TaskTypeMetricAggregation, map[string]interface{}{}, 0, "")
	require.NoError(t, err)

	task2, err := mgr.SubmitTask(prompt.TaskTypeReportGeneration, map[string]interface{}{}, 0, "")
	require.NoError(t, err)

	<-processing

	err = mgr.CancelTask(task2.ID)
	require.NoError(t, err)

	cancelledTask, err := mgr.GetTask(task2.ID)
	require.NoError(t, err)
	assert.Equal(t, prompt.TaskStatusCancelled, cancelledTask.Status)

	err = mgr.CancelTask(task1.ID)
	assert.Error(t, err)

	block <- true

	time.Sleep(100 * time.Millisecond)

	runningTask, err := mgr.GetTask(task1.ID)
	require.NoError(t, err)
	assert.Equal(t, prompt.TaskStatusCompleted, runningTask.Status)
}

func TestAsyncTaskManager_ListTasks(t *testing.T) {
	logger := zaptest.NewLogger(t)
	mgr := prompt.NewAsyncTaskManager(1, logger)

	mgr.SetProcessor(func(ctx context.Context, task *prompt.AsyncTask) error {
		time.Sleep(50 * time.Millisecond)
		return nil
	})

	ctx, cancel := context.WithCancel(context.Background())
	mgr.Start(ctx)
	defer cancel()
	defer mgr.Stop()

	for i := 0; i < 3; i++ {
		_, err := mgr.SubmitTask(prompt.TaskTypeABTestAnalysis, map[string]interface{}{"i": i}, 0, "")
		require.NoError(t, err)
	}

	_, err := mgr.SubmitTask(prompt.TaskTypePromptEvaluation, map[string]interface{}{}, 0, "")
	require.NoError(t, err)

	time.Sleep(300 * time.Millisecond)

	allTasks := mgr.ListTasks("", "", 10, 0)
	assert.Len(t, allTasks, 4)

	analysisTasks := mgr.ListTasks("", prompt.TaskTypeABTestAnalysis, 10, 0)
	assert.Len(t, analysisTasks, 3)

	completedTasks := mgr.ListTasks(prompt.TaskStatusCompleted, "", 10, 0)
	assert.Len(t, completedTasks, 4)
}

func TestAsyncTaskManager_EventHandlers(t *testing.T) {
	logger := zaptest.NewLogger(t)
	mgr := prompt.NewAsyncTaskManager(1, logger)

	handler := &testTaskHandler{
		created:   make(chan string, 10),
		started:   make(chan string, 10),
		completed: make(chan string, 10),
		failed:    make(chan string, 10),
	}
	mgr.RegisterHandler(handler)

	mgr.SetProcessor(func(ctx context.Context, task *prompt.AsyncTask) error {
		return nil
	})

	ctx, cancel := context.WithCancel(context.Background())
	mgr.Start(ctx)
	defer cancel()
	defer mgr.Stop()

	task, err := mgr.SubmitTask(prompt.TaskTypeABTestAnalysis, map[string]interface{}{}, 0, "")
	require.NoError(t, err)

	select {
	case taskID := <-handler.created:
		assert.Equal(t, task.ID, taskID)
	case <-time.After(time.Second):
		t.Fatal("OnTaskCreated not called")
	}

	select {
	case taskID := <-handler.started:
		assert.Equal(t, task.ID, taskID)
	case <-time.After(time.Second):
		t.Fatal("OnTaskStarted not called")
	}

	select {
	case taskID := <-handler.completed:
		assert.Equal(t, task.ID, taskID)
	case <-time.After(time.Second):
		t.Fatal("OnTaskCompleted not called")
	}
}

func TestAsyncPromptService_SubmitABTestAnalysis(t *testing.T) {
	logger := zaptest.NewLogger(t)
	db := setupTestDB(t)

	service := prompt.NewAsyncPromptService(db, logger)
	ctx, cancel := context.WithCancel(context.Background())
	service.Start(ctx)
	defer cancel()
	defer service.Stop()

	testPrompt := &prompt.Prompt{
		ID:          "prompt_1",
		Name:        "Test Prompt",
		Content:     "Test content",
		Namespace:   "test",
		Description: "Test description",
		CreatedAt:   time.Now(),
		UpdatedAt:   time.Now(),
	}
	db.Create(testPrompt)

	abTest := &prompt.ABTest{
		ID:             "abtest_1",
		PromptID:       "prompt_1",
		ControlVersion: 1,
		TestVersion:    2,
		TrafficSplit:   50,
		Status:         prompt.ABTestStatusRunning,
		CreatedAt:      time.Now(),
		UpdatedAt:      time.Now(),
	}
	db.Create(abTest)

	for i := 0; i < 10; i++ {
		groupType := "control"
		if i%2 == 0 {
			groupType = "test"
		}
		result := &prompt.ABTestResult{
			ID:        prompt.GenerateID(),
			ABTestID:  "abtest_1",
			GroupType: groupType,
			Success:   i%2 == 0,
			LatencyMs: 100,
			CreatedAt: time.Now(),
		}
		db.Create(result)
	}

	task, err := service.SubmitABTestAnalysis("abtest_1", "")
	require.NoError(t, err)
	assert.Equal(t, prompt.TaskTypeABTestAnalysis, task.Type)

	time.Sleep(2 * time.Second)

	completedTask, err := service.GetTaskManager().GetTask(task.ID)
	require.NoError(t, err)
	assert.Equal(t, prompt.TaskStatusCompleted, completedTask.Status)
	assert.NotNil(t, completedTask.Result)

	result := completedTask.Result
	assert.Equal(t, "abtest_1", result["ab_test_id"])
	assert.Equal(t, 10, result["control_samples"].(int)+result["test_samples"].(int))
	assert.Contains(t, result, "z_score")
	assert.Contains(t, result, "p_value")
	assert.Contains(t, result, "is_significant")
}

func TestAsyncPromptService_SubmitVersionCleanup(t *testing.T) {
	logger := zaptest.NewLogger(t)
	db := setupTestDB(t)

	service := prompt.NewAsyncPromptService(db, logger)
	ctx, cancel := context.WithCancel(context.Background())
	service.Start(ctx)
	defer cancel()
	defer service.Stop()

	testPrompt := &prompt.Prompt{
		ID:        "prompt_cleanup",
		Name:      "Test Prompt",
		Content:   "Test content",
		Namespace: "test",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
	db.Create(testPrompt)

	for i := 1; i <= 15; i++ {
		version := &prompt.PromptVersion{
			ID:            prompt.GenerateID(),
			PromptID:      "prompt_cleanup",
			VersionNumber: i,
			Content:       fmt.Sprintf("version %d", i),
			CreatedAt:     time.Now(),
		}
		db.Create(version)
	}

	task, err := service.SubmitVersionCleanup("prompt_cleanup", 10, "")
	require.NoError(t, err)

	time.Sleep(2 * time.Second)

	completedTask, err := service.GetTaskManager().GetTask(task.ID)
	require.NoError(t, err)
	assert.Equal(t, prompt.TaskStatusCompleted, completedTask.Status)

	result := completedTask.Result
	assert.Equal(t, 15, result["total_versions"])
	assert.Equal(t, 5, result["deleted_versions"])
	assert.Equal(t, 10, result["remaining_versions"])

	var remainingCount int64
	db.Model(&prompt.PromptVersion{}).Where("prompt_id = ?", "prompt_cleanup").Count(&remainingCount)
	assert.Equal(t, int64(10), remainingCount)
}

func TestAsyncPromptService_SubmitMetricAggregation(t *testing.T) {
	logger := zaptest.NewLogger(t)
	db := setupTestDB(t)

	service := prompt.NewAsyncPromptService(db, logger)
	ctx, cancel := context.WithCancel(context.Background())
	service.Start(ctx)
	defer cancel()
	defer service.Stop()

	startDate := time.Now().AddDate(0, 0, -7)
	endDate := time.Now()

	for i := 0; i < 5; i++ {
		p := &prompt.Prompt{
			ID:        fmt.Sprintf("prompt_%d", i),
			Name:      fmt.Sprintf("Prompt %d", i),
			Content:   "content",
			Namespace: "test",
			CreatedAt: time.Now(),
			UpdatedAt: time.Now(),
		}
		db.Create(p)
	}

	task, err := service.SubmitMetricAggregation(startDate, endDate, "")
	require.NoError(t, err)

	time.Sleep(2 * time.Second)

	completedTask, err := service.GetTaskManager().GetTask(task.ID)
	require.NoError(t, err)
	assert.Equal(t, prompt.TaskStatusCompleted, completedTask.Status)

	result := completedTask.Result
	assert.Equal(t, int64(5), result["total_prompts"])
}

type testTaskHandler struct {
	created   chan string
	started   chan string
	completed chan string
	failed    chan string
}

func (h *testTaskHandler) OnTaskCreated(task *prompt.AsyncTask) {
	h.created <- task.ID
}

func (h *testTaskHandler) OnTaskStarted(task *prompt.AsyncTask) {
	h.started <- task.ID
}

func (h *testTaskHandler) OnTaskCompleted(task *prompt.AsyncTask) {
	h.completed <- task.ID
}

func (h *testTaskHandler) OnTaskFailed(task *prompt.AsyncTask) {
	h.failed <- task.ID
}
