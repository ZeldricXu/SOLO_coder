package scheduler

import (
	"context"
	"testing"
	"time"

	"github.com/df1-96/experiment/internal/models"
	"github.com/df1-96/experiment/pkg/util"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestPriorityQueue_HighPriorityFirst(t *testing.T) {
	pq := NewPriorityQueue()
	defer pq.Clear()

	taskLow := &models.Task{ID: util.GenerateID(), Name: "low", Priority: int(PriorityLow)}
	taskNormal := &models.Task{ID: util.GenerateID(), Name: "normal", Priority: int(PriorityNormal)}
	taskHigh := &models.Task{ID: util.GenerateID(), Name: "high", Priority: int(PriorityHigh)}

	_, err := pq.Enqueue(taskLow, nil)
	require.NoError(t, err)
	_, err = pq.Enqueue(taskNormal, nil)
	require.NoError(t, err)
	_, err = pq.Enqueue(taskHigh, nil)
	require.NoError(t, err)

	assert.Equal(t, 3, pq.Length())

	qt1, err := pq.TryDequeue()
	require.NoError(t, err)
	require.NotNil(t, qt1)
	assert.Equal(t, taskHigh.ID, qt1.Task.ID)
	assert.Equal(t, PriorityHigh, PriorityLevel(qt1.Task.Priority))

	qt2, err := pq.TryDequeue()
	require.NoError(t, err)
	require.NotNil(t, qt2)
	assert.Equal(t, taskNormal.ID, qt2.Task.ID)

	qt3, err := pq.TryDequeue()
	require.NoError(t, err)
	require.NotNil(t, qt3)
	assert.Equal(t, taskLow.ID, qt3.Task.ID)

	assert.Equal(t, 0, pq.Length())
}

func TestPriorityQueue_CriticalJumpsQueue(t *testing.T) {
	pq := NewPriorityQueue()
	defer pq.Clear()

	for i := 0; i < 10; i++ {
		task := &models.Task{
			ID:       util.GenerateID(),
			Name:     "normal",
			Priority: int(PriorityNormal),
		}
		_, err := pq.Enqueue(task, nil)
		require.NoError(t, err)
	}

	criticalTask := &models.Task{
		ID:       util.GenerateID(),
		Name:     "critical",
		Priority: int(PriorityCritical),
	}
	_, err := pq.Enqueue(criticalTask, nil)
	require.NoError(t, err)

	assert.Equal(t, 11, pq.Length())

	qt, err := pq.TryDequeue()
	require.NoError(t, err)
	require.NotNil(t, qt)
	assert.Equal(t, criticalTask.ID, qt.Task.ID)
	assert.Equal(t, PriorityCritical, PriorityLevel(qt.Task.Priority))

	remainingNormal := 0
	for {
		qt, err := pq.TryDequeue()
		require.NoError(t, err)
		if qt == nil {
			break
		}
		assert.Equal(t, PriorityNormal, PriorityLevel(qt.Task.Priority))
		remainingNormal++
	}
	assert.Equal(t, 10, remainingNormal)
}

func TestPriorityQueue_SamePriorityFIFO(t *testing.T) {
	pq := NewPriorityQueue()
	defer pq.Clear()

	order := []int64{}
	for i := 0; i < 5; i++ {
		id := util.GenerateID()
		task := &models.Task{
			ID:       id,
			Name:     "task",
			Priority: int(PriorityNormal),
		}
		_, err := pq.Enqueue(task, nil)
		require.NoError(t, err)
		order = append(order, id)
	}

	for i := 0; i < 5; i++ {
		qt, err := pq.TryDequeue()
		require.NoError(t, err)
		require.NotNil(t, qt)
		assert.Equal(t, order[i], qt.Task.ID, "task %d should be dequeued at position %d", order[i], i)
	}
}

func TestPriorityQueue_TimeoutExpiration(t *testing.T) {
	pq := NewPriorityQueue()
	defer pq.Clear()

	pastDeadline := time.Now().Add(-1 * time.Second)
	taskExpired := &models.Task{
		ID:       util.GenerateID(),
		Name:     "expired",
		Priority: int(PriorityHigh),
	}
	_, err := pq.Enqueue(taskExpired, &pastDeadline)
	require.NoError(t, err)

	futureDeadline := time.Now().Add(1 * time.Hour)
	taskValid := &models.Task{
		ID:       util.GenerateID(),
		Name:     "valid",
		Priority: int(PriorityNormal),
	}
	_, err = pq.Enqueue(taskValid, &futureDeadline)
	require.NoError(t, err)

	assert.Equal(t, 2, pq.Length())

	qt, err := pq.TryDequeue()
	require.NoError(t, err)
	require.NotNil(t, qt)
	assert.Equal(t, taskValid.ID, qt.Task.ID)

	qt2, err := pq.TryDequeue()
	require.NoError(t, err)
	assert.Nil(t, qt2)
	assert.Equal(t, 0, pq.Length())
}

func TestPriorityQueue_CancelTask(t *testing.T) {
	pq := NewPriorityQueue()
	defer pq.Clear()

	task1 := &models.Task{ID: util.GenerateID(), Name: "task1", Priority: int(PriorityHigh)}
	task2 := &models.Task{ID: util.GenerateID(), Name: "task2", Priority: int(PriorityNormal)}
	task3 := &models.Task{ID: util.GenerateID(), Name: "task3", Priority: int(PriorityLow)}

	_, err := pq.Enqueue(task1, nil)
	require.NoError(t, err)
	_, err = pq.Enqueue(task2, nil)
	require.NoError(t, err)
	_, err = pq.Enqueue(task3, nil)
	require.NoError(t, err)

	assert.True(t, pq.Contains(task2.ID))
	assert.Equal(t, 3, pq.Length())

	cancelled := pq.Cancel(task2.ID)
	assert.True(t, cancelled)
	assert.False(t, pq.Contains(task2.ID))
	assert.Equal(t, 2, pq.Length())

	cancelled = pq.Cancel(999999999)
	assert.False(t, cancelled)

	qt1, err := pq.TryDequeue()
	require.NoError(t, err)
	require.NotNil(t, qt1)
	assert.Equal(t, task1.ID, qt1.Task.ID)

	qt3, err := pq.TryDequeue()
	require.NoError(t, err)
	require.NotNil(t, qt3)
	assert.Equal(t, task3.ID, qt3.Task.ID)
}

func TestPriorityQueue_CleanExpired(t *testing.T) {
	pq := NewPriorityQueue()
	defer pq.Clear()

	pastDeadline := time.Now().Add(-1 * time.Minute)
	for i := 0; i < 3; i++ {
		task := &models.Task{
			ID:       util.GenerateID(),
			Name:     "expired",
			Priority: int(PriorityNormal),
		}
		_, err := pq.Enqueue(task, &pastDeadline)
		require.NoError(t, err)
	}

	for i := 0; i < 2; i++ {
		task := &models.Task{
			ID:       util.GenerateID(),
			Name:     "valid",
			Priority: int(PriorityNormal),
		}
		_, err := pq.Enqueue(task, nil)
		require.NoError(t, err)
	}

	assert.Equal(t, 5, pq.Length())

	cleaned := pq.CleanExpired()
	assert.Equal(t, 3, cleaned)
	assert.Equal(t, 2, pq.Length())
}

func TestPriorityQueue_DequeueWithTimeout(t *testing.T) {
	pq := NewPriorityQueue()
	defer pq.Clear()

	start := time.Now()
	qt, err := pq.DequeueWithTimeout(50 * time.Millisecond)
	elapsed := time.Since(start)

	assert.Error(t, err)
	assert.Nil(t, qt)
	assert.GreaterOrEqual(t, elapsed, 50*time.Millisecond)
	assert.Less(t, elapsed, 500*time.Millisecond)
}

func TestPriorityQueue_DequeueWithContext(t *testing.T) {
	pq := NewPriorityQueue()
	defer pq.Clear()

	ctx, cancel := context.WithCancel(context.Background())

	done := make(chan struct{})
	go func() {
		defer close(done)
		qt, err := pq.Dequeue(ctx)
		assert.Error(t, err)
		assert.Nil(t, qt)
	}()

	time.Sleep(10 * time.Millisecond)
	cancel()

	select {
	case <-done:
	case <-time.After(100 * time.Millisecond):
		t.Fatal("Dequeue did not return after context cancellation")
	}
}

func TestPriorityQueue_PromoteTask(t *testing.T) {
	pq := NewPriorityQueue()
	defer pq.Clear()

	task1 := &models.Task{ID: util.GenerateID(), Name: "task1", Priority: int(PriorityLow)}
	task2 := &models.Task{ID: util.GenerateID(), Name: "task2", Priority: int(PriorityLow)}

	_, err := pq.Enqueue(task1, nil)
	require.NoError(t, err)
	_, err = pq.Enqueue(task2, nil)
	require.NoError(t, err)

	err = pq.Promote(task2.ID, int(PriorityHigh))
	require.NoError(t, err)

	qt, err := pq.TryDequeue()
	require.NoError(t, err)
	require.NotNil(t, qt)
	assert.Equal(t, task2.ID, qt.Task.ID)
	assert.Equal(t, PriorityHigh, PriorityLevel(qt.Task.Priority))
}

func TestPriorityQueue_DuplicateEnqueue(t *testing.T) {
	pq := NewPriorityQueue()
	defer pq.Clear()

	task := &models.Task{ID: util.GenerateID(), Name: "task", Priority: int(PriorityNormal)}
	_, err := pq.Enqueue(task, nil)
	require.NoError(t, err)

	_, err = pq.Enqueue(task, nil)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "already in queue")
	assert.Equal(t, 1, pq.Length())
}

func TestPriorityQueue_NilTask(t *testing.T) {
	pq := NewPriorityQueue()
	defer pq.Clear()

	_, err := pq.Enqueue(nil, nil)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "cannot be nil")
}
