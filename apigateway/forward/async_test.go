package forward

import (
	"net/http"
	"sync"
	"testing"
	"time"
)

func TestAsyncForwarder_BasicSubmit(t *testing.T) {
	af := NewAsyncForwarder(1, 10)
	af.Start()
	defer af.Stop()

	task := &ForwardTask{
		RouteID:   "test_route",
		TargetURL: "http://localhost:8080/test",
	}

	err := af.Submit(task)
	if err != nil {
		t.Errorf("Failed to submit task: %v", err)
	}

	time.Sleep(100 * time.Millisecond)

	completed, failed, pending := af.Stats()
	if completed != 1 {
		t.Errorf("Expected 1 completed, got %d", completed)
	}
	if failed != 0 {
		t.Errorf("Expected 0 failed, got %d", failed)
	}
	if pending != 0 {
		t.Errorf("Expected 0 pending, got %d", pending)
	}
}

func TestAsyncForwarder_NonBlocking(t *testing.T) {
	af := NewAsyncForwarder(1, 5)
	af.Start()
	defer af.Stop()

	for i := 0; i < 5; i++ {
		task := &ForwardTask{
			RouteID:   "test_route",
			TargetURL: "http://localhost:8080/test",
		}
		submitted := af.SubmitNonBlocking(task)
		if !submitted {
			t.Errorf("Task %d should have been submitted", i)
		}
	}

	task := &ForwardTask{
		RouteID:   "test_route",
		TargetURL: "http://localhost:8080/test",
	}

	submitted := af.SubmitNonBlocking(task)
	if submitted {
		t.Error("Task should have been rejected when queue is full")
	}
}

func TestAsyncForwarder_ConcurrentSubmit(t *testing.T) {
	workerCount := 5
	queueSize := 100
	numGoroutines := 20
	tasksPerGoroutine := 10

	af := NewAsyncForwarder(workerCount, queueSize)
	af.Start()
	defer af.Stop()

	var wg sync.WaitGroup
	var submitMu sync.Mutex
	submitErrors := 0

	wg.Add(numGoroutines)
	for i := 0; i < numGoroutines; i++ {
		go func(gid int) {
			defer wg.Done()
			for j := 0; j < tasksPerGoroutine; j++ {
				task := &ForwardTask{
					RouteID:   "concurrent_route",
					TargetURL: "http://localhost:8080/test",
				}
				err := af.Submit(task)
				if err != nil {
					submitMu.Lock()
					submitErrors++
					submitMu.Unlock()
				}
			}
		}(i)
	}

	wg.Wait()

	time.Sleep(500 * time.Millisecond)

	completed, failed, pending := af.Stats()
	expectedTotal := numGoroutines * tasksPerGoroutine

	t.Logf("Submit errors: %d", submitErrors)
	t.Logf("Completed: %d, Failed: %d, Pending: %d", completed, failed, pending)

	if completed+submitErrors != expectedTotal {
		t.Errorf("Expected %d tasks, got %d completed + %d errors = %d",
			expectedTotal, completed, submitErrors, completed+submitErrors)
	}
}

func TestAsyncForwarder_QueueCapacity(t *testing.T) {
	queueSize := 10
	af := NewAsyncForwarder(0, queueSize)

	capacity := af.QueueCapacity()
	if capacity != queueSize {
		t.Errorf("Expected capacity %d, got %d", queueSize, capacity)
	}

	for i := 0; i < queueSize; i++ {
		task := &ForwardTask{
			RouteID:   "test",
			TargetURL: "http://test",
		}
		submitted := af.SubmitNonBlocking(task)
		if !submitted {
			t.Errorf("Task %d should fit in queue", i)
		}
	}

	queueLen := af.QueueSize()
	if queueLen != queueSize {
		t.Errorf("Expected queue size %d, got %d", queueSize, queueLen)
	}

	task := &ForwardTask{
		RouteID:   "test",
		TargetURL: "http://test",
	}
	submitted := af.SubmitNonBlocking(task)
	if submitted {
		t.Error("Task should not fit in full queue")
	}
}

func TestAsyncForwarder_StartStop(t *testing.T) {
	af := NewAsyncForwarder(2, 10)

	err := af.Submit(&ForwardTask{RouteID: "test"})
	if err == nil {
		t.Error("Should fail when not started")
	}

	af.Start()

	err = af.Submit(&ForwardTask{RouteID: "test"})
	if err != nil {
		t.Errorf("Should submit when started: %v", err)
	}

	af.Stop()

	err = af.Submit(&ForwardTask{RouteID: "test"})
	if err == nil {
		t.Error("Should fail when stopped")
	}
}

func TestAsyncForwarder_DoneChannel(t *testing.T) {
	af := NewAsyncForwarder(1, 10)
	af.Start()
	defer af.Stop()

	doneChan := make(chan struct{})
	task := &ForwardTask{
		RouteID:   "test",
		TargetURL: "http://test",
		Done:      doneChan,
	}

	err := af.Submit(task)
	if err != nil {
		t.Errorf("Failed to submit: %v", err)
	}

	select {
	case <-doneChan:
	case <-time.After(1 * time.Second):
		t.Error("Task completion timed out")
	}
}

func TestAsyncForwarder_ProcessedChannel(t *testing.T) {
	af := NewAsyncForwarder(1, 10)
	af.Start()
	defer af.Stop()

	numTasks := 5
	for i := 0; i < numTasks; i++ {
		task := &ForwardTask{
			RouteID:   "test",
			TargetURL: "http://test",
		}
		af.Submit(task)
	}

	processedCount := 0
	timeout := time.After(2 * time.Second)

loop:
	for {
		select {
		case <-af.Processed():
			processedCount++
			if processedCount == numTasks {
				break loop
			}
		case <-timeout:
			break loop
		}
	}

	if processedCount != numTasks {
		t.Errorf("Expected %d processed, got %d", numTasks, processedCount)
	}
}

func TestAsyncForwarderPool_Basic(t *testing.T) {
	pool := NewAsyncForwarderPool()
	defer pool.StopAll()

	af1 := pool.Register("service_a", 2, 20)
	if af1 == nil {
		t.Fatal("Failed to register service_a")
	}

	af2 := pool.Register("service_b", 1, 10)
	if af2 == nil {
		t.Fatal("Failed to register service_b")
	}

	got, exists := pool.Get("service_a")
	if !exists || got == nil {
		t.Error("Should get service_a")
	}

	got, exists = pool.Get("service_c")
	if exists {
		t.Error("Should not get service_c")
	}

	err := pool.Submit("service_a", &ForwardTask{RouteID: "test"})
	if err != nil {
		t.Errorf("Should submit to service_a: %v", err)
	}

	err = pool.Submit("service_c", &ForwardTask{RouteID: "test"})
	if err == nil {
		t.Error("Should fail to submit to non-existent service")
	}
}

func TestAsyncForwarderPool_StopAll(t *testing.T) {
	pool := NewAsyncForwarderPool()

	pool.Register("service_1", 1, 10)
	pool.Register("service_2", 1, 10)

	err := pool.Submit("service_1", &ForwardTask{RouteID: "test"})
	if err != nil {
		t.Errorf("Should submit before stop: %v", err)
	}

	pool.StopAll()

	err = pool.Submit("service_1", &ForwardTask{RouteID: "test"})
	if err == nil {
		t.Error("Should fail after stop all")
	}
}

func TestAsyncForwarder_SubmitAfterStop(t *testing.T) {
	af := NewAsyncForwarder(2, 10)
	af.Start()

	err := af.Submit(&ForwardTask{RouteID: "test1"})
	if err != nil {
		t.Errorf("Should submit when running: %v", err)
	}

	af.Stop()

	err = af.Submit(&ForwardTask{RouteID: "test2"})
	if err == nil {
		t.Error("Should fail after stop")
	}

	err = af.Submit(&ForwardTask{RouteID: "test3"})
	if err == nil {
		t.Error("Should still fail")
	}
}

func TestAsyncForwarder_MultipleStartStop(t *testing.T) {
	af := NewAsyncForwarder(1, 10)

	af.Start()
	af.Stop()

	af.Start()
	err := af.Submit(&ForwardTask{RouteID: "test"})
	if err != nil {
		t.Errorf("Should submit after restart: %v", err)
	}
	af.Stop()
}

func TestAsyncForwarder_ErrorTypes(t *testing.T) {
	af := NewAsyncForwarder(1, 1)
	af.Stop()

	err := af.Submit(&ForwardTask{RouteID: "test"})
	if err == nil {
		t.Fatal("Expected error")
	}

	asyncErr, ok := err.(*AsyncError)
	if !ok {
		t.Errorf("Expected AsyncError, got %T", err)
		return
	}

	if asyncErr.Op != "submit" {
		t.Errorf("Expected op 'submit', got '%s'", asyncErr.Op)
	}

	t.Logf("Error message: %s", asyncErr.Error())
}

func TestAsyncForwarder_QueueFullError(t *testing.T) {
	af := NewAsyncForwarder(0, 1)

	task1 := &ForwardTask{RouteID: "test1"}
	err := af.Submit(task1)
	if err != nil {
		t.Errorf("First task should submit: %v", err)
	}

	task2 := &ForwardTask{RouteID: "test2"}
	err = af.Submit(task2)
	if err == nil {
		t.Error("Second task should fail with queue full")
		return
	}

	asyncErr, ok := err.(*AsyncError)
	if !ok {
		t.Errorf("Expected AsyncError, got %T", err)
		return
	}

	t.Logf("Error: %s", asyncErr.Error())
}

func TestAsyncForwarder_StatsConcurrency(t *testing.T) {
	workerCount := 3
	queueSize := 100
	af := NewAsyncForwarder(workerCount, queueSize)
	af.Start()
	defer af.Stop()

	var submitWg sync.WaitGroup
	var statsWg sync.WaitGroup

	numSubmissions := 50
	numStatsReaders := 10

	submitWg.Add(1)
	go func() {
		defer submitWg.Done()
		for i := 0; i < numSubmissions; i++ {
			af.SubmitNonBlocking(&ForwardTask{RouteID: "test"})
			time.Sleep(1 * time.Millisecond)
		}
	}()

	statsWg.Add(numStatsReaders)
	for i := 0; i < numStatsReaders; i++ {
		go func() {
			defer statsWg.Done()
			for j := 0; j < 20; j++ {
				_, _, _ = af.Stats()
				_ = af.QueueSize()
				_ = af.QueueCapacity()
				time.Sleep(2 * time.Millisecond)
			}
		}()
	}

	submitWg.Wait()
	statsWg.Wait()

	time.Sleep(200 * time.Millisecond)

	completed, _, _ := af.Stats()
	if completed == 0 {
		t.Error("Expected some tasks to be completed")
	}

	t.Logf("Final stats: completed=%d", completed)
}

func TestForwardTask_Initialization(t *testing.T) {
	req, _ := http.NewRequest("GET", "http://test.com/path", nil)

	task := &ForwardTask{
		Request:    req,
		RouteID:    "test_route_123",
		TargetURL:  "http://backend:8080/api",
		Timeout:    5 * time.Second,
		MaxRetries: 3,
	}

	if task.RouteID != "test_route_123" {
		t.Errorf("Expected route_id 'test_route_123', got '%s'", task.RouteID)
	}

	if task.TargetURL != "http://backend:8080/api" {
		t.Errorf("Expected target_url 'http://backend:8080/api', got '%s'", task.TargetURL)
	}

	if task.Timeout != 5*time.Second {
		t.Errorf("Expected timeout 5s, got %v", task.Timeout)
	}

	if task.MaxRetries != 3 {
		t.Errorf("Expected max_retries 3, got %d", task.MaxRetries)
	}
}
