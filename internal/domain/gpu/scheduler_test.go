package gpu

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/dataplatform/engine/internal/domain"
	"github.com/google/uuid"
)

type testLogger struct{}

func (l *testLogger) Debug(msg string, fields ...domain.Field) {}
func (l *testLogger) Info(msg string, fields ...domain.Field)  {}
func (l *testLogger) Warn(msg string, fields ...domain.Field)  {}
func (l *testLogger) Error(msg string, fields ...domain.Field) {}
func (l *testLogger) Fatal(msg string, fields ...domain.Field) {}
func (l *testLogger) SetLevel(level domain.LogLevel)           {}
func (l *testLogger) GetLevel() domain.LogLevel                { return domain.LogLevelInfo }
func (l *testLogger) With(fields ...domain.Field) domain.Logger { return l }
func (l *testLogger) Sync() error                              { return nil }

func TestGPUSchedulerConcurrentSafety(t *testing.T) {
	resourceManager := NewGPUResourceManager("test-node", []int{0, 1, 2, 3}, 16000)
	logger := &testLogger{}
	scheduler := NewGPUScheduler(resourceManager, logger)

	ctx := context.Background()

	const numGoroutines = 50
	const tasksPerGoroutine = 20

	var wg sync.WaitGroup
	var submittedTasks int64
	var completedTasks int64

	startTime := time.Now()

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(goroutineID int) {
			defer wg.Done()

			for j := 0; j < tasksPerGoroutine; j++ {
				task := &GPUTask{
					ID:           uuid.New().String(),
					Name:         "test-task",
					Priority:     PriorityMedium,
					VRAMRequired: 1024,
					Status:       domain.StatusPending,
					Payload:      map[string]int{"goroutine": goroutineID, "task": j},
					Preemptible:  true,
					SubmittedAt:  time.Now(),
				}

				submitted, err := scheduler.SubmitTask(ctx, task)
				if err == nil {
					atomic.AddInt64(&submittedTasks, 1)

					status, err := scheduler.GetTaskStatus(ctx, submitted.ID)
					if err == nil && status != nil {
						if status.Status == domain.StatusCompleted || status.Status == domain.StatusFailed {
							atomic.AddInt64(&completedTasks, 1)
						}
					}
				}

				time.Sleep(time.Millisecond * 10)
			}
		}(i)
	}

	wg.Wait()

	t.Logf("Submitted tasks: %d, Completed tasks: %d, Duration: %v",
		atomic.LoadInt64(&submittedTasks),
		atomic.LoadInt64(&completedTasks),
		time.Since(startTime),
	)

	queueSize := scheduler.GetQueueSize()
	runningCount := scheduler.GetRunningCount()
	completedCount := scheduler.GetCompletedCount()

	t.Logf("Queue size: %d, Running: %d, Completed: %d",
		queueSize, runningCount, completedCount)

	err := scheduler.Shutdown(ctx)
	if err != nil {
		t.Errorf("Shutdown failed: %v", err)
	}
}

func TestGPUSchedulerTaskIsolation(t *testing.T) {
	resourceManager := NewGPUResourceManager("test-node", []int{0}, 16000)
	logger := &testLogger{}
	scheduler := NewGPUScheduler(resourceManager, logger)

	ctx := context.Background()

	task := &GPUTask{
		ID:           uuid.New().String(),
		Name:         "isolation-test",
		Priority:     PriorityHigh,
		VRAMRequired: 1024,
		Status:       domain.StatusPending,
		Payload:      "original-payload",
		Preemptible:  false,
		SubmittedAt:  time.Now(),
	}

	submitted, err := scheduler.SubmitTask(ctx, task)
	if err != nil {
		t.Fatalf("Failed to submit task: %v", err)
	}

	task.Payload = "modified-payload"

	retrieved, err := scheduler.GetTaskStatus(ctx, submitted.ID)
	if err != nil {
		t.Fatalf("Failed to get task status: %v", err)
	}

	if retrieved.Payload == "modified-payload" {
		t.Error("Task payload was modified externally, isolation failed")
	}

	scheduler.Shutdown(ctx)
}

func TestGPUSchedulerInputValidation(t *testing.T) {
	resourceManager := NewGPUResourceManager("test-node", []int{0}, 16000)
	logger := &testLogger{}
	scheduler := NewGPUScheduler(resourceManager, logger)

	ctx := context.Background()

	_, err := scheduler.SubmitTask(ctx, nil)
	if err == nil {
		t.Error("Expected error for nil task")
	}

	_, err = scheduler.SubmitTask(ctx, &GPUTask{ID: "", Name: "no-id"})
	if err == nil {
		t.Error("Expected error for task with empty ID")
	}

	_, err = scheduler.SubmitTask(ctx, &GPUTask{ID: "test", Name: "", VRAMRequired: 1024})
	if err == nil {
		t.Error("Expected error for task with empty name")
	}

	_, err = scheduler.SubmitTask(ctx, &GPUTask{ID: "test", Name: "test", VRAMRequired: 0})
	if err == nil {
		t.Error("Expected error for task with zero VRAM")
	}

	err = scheduler.CancelTask(ctx, "")
	if err == nil {
		t.Error("Expected error for empty task ID")
	}

	_, err = scheduler.GetTaskStatus(ctx, "")
	if err == nil {
		t.Error("Expected error for empty task ID")
	}

	scheduler.Shutdown(ctx)

	_, err = scheduler.SubmitTask(ctx, &GPUTask{
		ID:           "after-shutdown",
		Name:         "test",
		VRAMRequired: 1024,
		SubmittedAt:  time.Now(),
	})
	if err == nil {
		t.Error("Expected error for task submitted after shutdown")
	}
}

func TestGPUSchedulerNoDeadlock(t *testing.T) {
	resourceManager := NewGPUResourceManager("test-node", []int{0, 1}, 16000)
	logger := &testLogger{}
	scheduler := NewGPUScheduler(resourceManager, logger)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	var wg sync.WaitGroup

	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < 100; i++ {
			scheduler.GetQueueSize()
			scheduler.GetRunningCount()
			scheduler.GetCompletedCount()
			scheduler.ListTasks(domain.StatusPending)
			time.Sleep(time.Millisecond)
		}
	}()

	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < 50; i++ {
			task := &GPUTask{
				ID:           uuid.New().String(),
				Name:         "deadlock-test",
				Priority:     PriorityLow,
				VRAMRequired: 512,
				Status:       domain.StatusPending,
				SubmittedAt:  time.Now(),
			}
			scheduler.SubmitTask(ctx, task)
			time.Sleep(time.Millisecond * 5)
		}
	}()

	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()

	select {
	case <-done:
		scheduler.Shutdown(ctx)
	case <-time.After(10 * time.Second):
		t.Fatal("Test timed out - possible deadlock")
	}
}
