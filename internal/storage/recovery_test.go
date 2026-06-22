package storage

import (
	"context"
	"testing"
	"time"

	"github.com/df1-96/experiment/internal/models"
	"github.com/df1-96/experiment/pkg/util"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type inMemoryTaskRepo struct {
	tasks map[int64]*models.Task
}

func newInMemoryTaskRepo() *inMemoryTaskRepo {
	return &inMemoryTaskRepo{tasks: make(map[int64]*models.Task)}
}

func (r *inMemoryTaskRepo) GetByID(ctx context.Context, id int64) (*models.Task, error) {
	t, ok := r.tasks[id]
	if !ok {
		return nil, nil
	}
	cp := *t
	return &cp, nil
}

func (r *inMemoryTaskRepo) GetByParamsHash(ctx context.Context, experimentID int64, paramsHash string) (*models.Task, error) {
	for _, t := range r.tasks {
		if t.ExperimentID == experimentID && t.ParamsHash == paramsHash {
			cp := *t
			return &cp, nil
		}
	}
	return nil, nil
}

func (r *inMemoryTaskRepo) Create(ctx context.Context, task *models.Task) error {
	if task.ID == 0 {
		task.ID = util.GenerateID()
	}
	if task.Status == "" {
		task.Status = models.TaskStatusPending
	}
	if task.Params == nil {
		task.Params = make(models.Params)
	}
	if task.ParamsHash == "" {
		task.ParamsHash = util.HashParams(map[string]interface{}(task.Params))
	}
	if task.MaxRetries == 0 {
		task.MaxRetries = 3
	}
	cp := *task
	r.tasks[task.ID] = &cp
	return nil
}

func (r *inMemoryTaskRepo) UpdateStatus(ctx context.Context, id int64, status models.TaskStatus, workerID *int64, errorMessage string) error {
	t, ok := r.tasks[id]
	if !ok {
		return nil
	}
	t.Status = status
	if workerID != nil {
		t.WorkerID = workerID
	}
	if errorMessage != "" {
		t.ErrorMessage = errorMessage
	}
	if status == models.TaskStatusRunning {
		now := time.Now()
		t.StartTime = &now
	}
	if status == models.TaskStatusCompleted || status == models.TaskStatusFailed {
		now := time.Now()
		t.EndTime = &now
	}
	return nil
}

type inMemoryResultRepo struct {
	results map[int64][]*models.Result
}

func newInMemoryResultRepo() *inMemoryResultRepo {
	return &inMemoryResultRepo{results: make(map[int64][]*models.Result)}
}

func (r *inMemoryResultRepo) GetLatestByTask(ctx context.Context, taskID int64) (*models.Result, error) {
	list, ok := r.results[taskID]
	if !ok || len(list) == 0 {
		return nil, nil
	}
	latest := list[len(list)-1]
	cp := *latest
	return &cp, nil
}

func (r *inMemoryResultRepo) Save(ctx context.Context, result *models.Result) error {
	if result.ID == 0 {
		result.ID = util.GenerateID()
	}
	if result.Data == nil {
		result.Data = make(models.ResultData)
	}
	if result.Checksum == "" {
		result.Checksum = util.HashParams(map[string]interface{}{
			"task_id":   result.TaskID,
			"worker_id": result.WorkerID,
			"iteration": result.Iteration,
			"data":      result.Data,
		})
	}
	for _, existing := range r.results[result.TaskID] {
		if existing.Checksum == result.Checksum {
			return nil
		}
	}
	cp := *result
	r.results[result.TaskID] = append(r.results[result.TaskID], &cp)
	return nil
}

func (r *inMemoryResultRepo) ListByTask(ctx context.Context, taskID int64) ([]*models.Result, error) {
	list, ok := r.results[taskID]
	if !ok {
		return nil, nil
	}
	result := make([]*models.Result, len(list))
	for i, r := range list {
		cp := *r
		result[i] = &cp
	}
	return result, nil
}

type inMemoryCheckpointRepo struct {
	checkpoints map[int64][]*models.Checkpoint
}

func newInMemoryCheckpointRepo() *inMemoryCheckpointRepo {
	return &inMemoryCheckpointRepo{checkpoints: make(map[int64][]*models.Checkpoint)}
}

func (r *inMemoryCheckpointRepo) Save(ctx context.Context, checkpoint *models.Checkpoint) error {
	if checkpoint.ID == 0 {
		checkpoint.ID = util.GenerateID()
	}
	if checkpoint.Data == nil {
		checkpoint.Data = make(models.Params)
	}
	if checkpoint.Checksum == "" {
		checkpoint.Checksum = util.HashParams(map[string]interface{}(checkpoint.Data))
	}
	cp := *checkpoint
	r.checkpoints[checkpoint.TaskID] = append(r.checkpoints[checkpoint.TaskID], &cp)
	return nil
}

func (r *inMemoryCheckpointRepo) GetLatest(ctx context.Context, taskID int64) (*models.Checkpoint, error) {
	list, ok := r.checkpoints[taskID]
	if !ok || len(list) == 0 {
		return nil, nil
	}
	var latest *models.Checkpoint
	for _, cp := range list {
		if latest == nil || cp.Step > latest.Step {
			latest = cp
		}
	}
	if latest == nil {
		return nil, nil
	}
	cp := *latest
	return &cp, nil
}

type idempotentTestContext struct {
	taskRepo     *inMemoryTaskRepo
	resultRepo   *inMemoryResultRepo
	checkpointRepo *inMemoryCheckpointRepo
	taskID       int64
	params       models.Params
	paramsHash   string
}

func setupIdempotentTest(t *testing.T) *idempotentTestContext {
	taskRepo := newInMemoryTaskRepo()
	resultRepo := newInMemoryResultRepo()
	checkpointRepo := newInMemoryCheckpointRepo()

	params := models.Params{
		"learning_rate": 0.001,
		"batch_size":    32,
		"epochs":        10,
	}
	paramsHash := util.HashParams(map[string]interface{}(params))

	ctx := context.Background()
	task := &models.Task{
		ExperimentID: 1,
		Name:         "test-task",
		Params:       params,
		ParamsHash:   paramsHash,
		Status:       models.TaskStatusPending,
		MaxRetries:   3,
	}
	err := taskRepo.Create(ctx, task)
	require.NoError(t, err)
	require.NotZero(t, task.ID)

	return &idempotentTestContext{
		taskRepo:       taskRepo,
		resultRepo:     resultRepo,
		checkpointRepo: checkpointRepo,
		taskID:         task.ID,
		params:         params,
		paramsHash:     paramsHash,
	}
}

func TestRecovery_Idempotent_DuplicateSubmit(t *testing.T) {
	tc := setupIdempotentTest(t)
	ctx := context.Background()

	t.Run("task_not_completed_no_result", func(t *testing.T) {
		task, err := tc.taskRepo.GetByID(ctx, tc.taskID)
		require.NoError(t, err)
		require.NotNil(t, task)
		assert.Equal(t, models.TaskStatusPending, task.Status)

		paramsHash := util.HashParams(map[string]interface{}(task.Params))
		assert.Equal(t, tc.paramsHash, paramsHash)

		result, err := tc.resultRepo.GetLatestByTask(ctx, tc.taskID)
		require.NoError(t, err)
		assert.Nil(t, result)
	})

	t.Run("task_completed_has_result_returns_idempotent", func(t *testing.T) {
		err := tc.taskRepo.UpdateStatus(ctx, tc.taskID, models.TaskStatusCompleted, nil, "")
		require.NoError(t, err)

		resultData := models.ResultData{
			"loss":     0.042,
			"accuracy": 0.958,
		}
		result := &models.Result{
			TaskID:     tc.taskID,
			WorkerID:   1,
			Data:       resultData,
			Iteration:  1,
			DurationMs: 1500,
		}
		err = tc.resultRepo.Save(ctx, result)
		require.NoError(t, err)

		task, err := tc.taskRepo.GetByID(ctx, tc.taskID)
		require.NoError(t, err)
		require.NotNil(t, task)
		assert.Equal(t, models.TaskStatusCompleted, task.Status)

		existingResult, err := tc.resultRepo.GetLatestByTask(ctx, tc.taskID)
		require.NoError(t, err)
		require.NotNil(t, existingResult)
		assert.Equal(t, resultData["loss"], existingResult.Data["loss"])
		assert.Equal(t, resultData["accuracy"], existingResult.Data["accuracy"])
	})

	t.Run("duplicate_result_save_is_noop", func(t *testing.T) {
		resultData := models.ResultData{
			"loss":     0.042,
			"accuracy": 0.958,
		}
		result1 := &models.Result{
			TaskID:     tc.taskID,
			WorkerID:   1,
			Data:       resultData,
			Iteration:  1,
			DurationMs: 1500,
		}
		result1.Checksum = util.HashParams(map[string]interface{}{
			"task_id":   result1.TaskID,
			"worker_id": result1.WorkerID,
			"iteration": result1.Iteration,
			"data":      result1.Data,
		})

		err := tc.resultRepo.Save(ctx, result1)
		require.NoError(t, err)

		err = tc.resultRepo.Save(ctx, result1)
		require.NoError(t, err)

		results, err := tc.resultRepo.ListByTask(ctx, tc.taskID)
		require.NoError(t, err)

		uniqueChecksums := make(map[string]bool)
		for _, r := range results {
			uniqueChecksums[r.Checksum] = true
		}
		assert.Len(t, uniqueChecksums, 1)
	})

	t.Run("params_hash_mismatch_detection", func(t *testing.T) {
		task, err := tc.taskRepo.GetByID(ctx, tc.taskID)
		require.NoError(t, err)
		require.NotNil(t, task)

		differentParams := models.Params{
			"learning_rate": 0.01,
			"batch_size":    64,
		}
		differentHash := util.HashParams(map[string]interface{}(differentParams))
		assert.NotEqual(t, task.ParamsHash, differentHash)
	})
}

type partialResultsTestContext struct {
	taskRepo     *inMemoryTaskRepo
	resultRepo   *inMemoryResultRepo
	checkpointRepo *inMemoryCheckpointRepo
	totalTasks   int
	completedIDs []int64
	pendingIDs   []int64
}

func setupPartialResultsTest(t *testing.T) *partialResultsTestContext {
	taskRepo := newInMemoryTaskRepo()
	resultRepo := newInMemoryResultRepo()
	checkpointRepo := newInMemoryCheckpointRepo()
	ctx := context.Background()

	totalTasks := 10
	completedIDs := make([]int64, 0)
	pendingIDs := make([]int64, 0)

	for i := 0; i < totalTasks; i++ {
		params := models.Params{
			"param_a": float64(i),
			"param_b": float64(i) * 2.0,
		}
		task := &models.Task{
			ExperimentID: 1,
			Name:         "task-" + string(rune('0'+i)),
			Params:       params,
			MaxRetries:   3,
		}

		if i < 6 {
			task.Status = models.TaskStatusCompleted
			err := taskRepo.Create(ctx, task)
			require.NoError(t, err)
			completedIDs = append(completedIDs, task.ID)

			result := &models.Result{
				TaskID:     task.ID,
				WorkerID:   1,
				Data:       models.ResultData{"value": float64(i) * 10},
				Iteration:  1,
				DurationMs: 100,
			}
			err = resultRepo.Save(ctx, result)
			require.NoError(t, err)
		} else {
			task.Status = models.TaskStatusPending
			err := taskRepo.Create(ctx, task)
			require.NoError(t, err)
			pendingIDs = append(pendingIDs, task.ID)
		}
	}

	return &partialResultsTestContext{
		taskRepo:       taskRepo,
		resultRepo:     resultRepo,
		checkpointRepo: checkpointRepo,
		totalTasks:     totalTasks,
		completedIDs:   completedIDs,
		pendingIDs:     pendingIDs,
	}
}

func TestRecovery_PartialResults_Continue(t *testing.T) {
	tc := setupPartialResultsTest(t)
	ctx := context.Background()

	t.Run("correct_number_of_completed_and_pending", func(t *testing.T) {
		assert.Len(t, tc.completedIDs, 6)
		assert.Len(t, tc.pendingIDs, 4)
	})

	t.Run("completed_tasks_have_results", func(t *testing.T) {
		for _, id := range tc.completedIDs {
			task, err := tc.taskRepo.GetByID(ctx, id)
			require.NoError(t, err)
			require.NotNil(t, task)
			assert.Equal(t, models.TaskStatusCompleted, task.Status)

			result, err := tc.resultRepo.GetLatestByTask(ctx, id)
			require.NoError(t, err)
			require.NotNil(t, result)
			assert.NotEmpty(t, result.Data)
		}
	})

	t.Run("pending_tasks_have_no_results", func(t *testing.T) {
		for _, id := range tc.pendingIDs {
			task, err := tc.taskRepo.GetByID(ctx, id)
			require.NoError(t, err)
			require.NotNil(t, task)
			assert.Equal(t, models.TaskStatusPending, task.Status)

			result, err := tc.resultRepo.GetLatestByTask(ctx, id)
			require.NoError(t, err)
			assert.Nil(t, result)
		}
	})

	t.Run("checkpoint_resume_from_latest_step", func(t *testing.T) {
		pendingID := tc.pendingIDs[0]

		checkpoint1 := &models.Checkpoint{
			TaskID:   pendingID,
			WorkerID: 1,
			Step:     5,
			Data:     models.Params{"iteration": float64(5), "loss": 0.5},
		}
		err := tc.checkpointRepo.Save(ctx, checkpoint1)
		require.NoError(t, err)

		checkpoint2 := &models.Checkpoint{
			TaskID:   pendingID,
			WorkerID: 1,
			Step:     10,
			Data:     models.Params{"iteration": float64(10), "loss": 0.3},
		}
		err = tc.checkpointRepo.Save(ctx, checkpoint2)
		require.NoError(t, err)

		latest, err := tc.checkpointRepo.GetLatest(ctx, pendingID)
		require.NoError(t, err)
		require.NotNil(t, latest)
		assert.Equal(t, int64(10), latest.Step)
		assert.Equal(t, float64(10), latest.Data["iteration"])
		assert.Equal(t, 0.3, latest.Data["loss"])
	})

	t.Run("all_params_hashes_are_unique", func(t *testing.T) {
		allIDs := append(tc.completedIDs, tc.pendingIDs...)
		seenHashes := make(map[string]bool)

		for _, id := range allIDs {
			task, err := tc.taskRepo.GetByID(ctx, id)
			require.NoError(t, err)
			require.NotNil(t, task)
			assert.False(t, seenHashes[task.ParamsHash], "duplicate params hash found: %s", task.ParamsHash)
			seenHashes[task.ParamsHash] = true
		}
		assert.Len(t, seenHashes, tc.totalTasks)
	})
}
