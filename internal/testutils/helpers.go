package testutils

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
)

var (
	testTime    time.Time
	testTimeMu  sync.RWMutex
	testCounter int
	counterMu   sync.Mutex
)

func init() {
	testTime = time.Date(2026, 5, 20, 12, 0, 0, 0, time.UTC)
}

func SetTestTime(t time.Time) {
	testTimeMu.Lock()
	defer testTimeMu.Unlock()
	testTime = t
}

func GetTestTime() time.Time {
	testTimeMu.RLock()
	defer testTimeMu.RUnlock()
	return testTime
}

func AdvanceTestTime(d time.Duration) {
	testTimeMu.Lock()
	defer testTimeMu.Unlock()
	testTime = testTime.Add(d)
}

func GenerateTestID(prefix string) string {
	counterMu.Lock()
	defer counterMu.Unlock()
	testCounter++
	return fmt.Sprintf("%s_test_%04d", prefix, testCounter)
}

func ResetTestCounter() {
	counterMu.Lock()
	defer counterMu.Unlock()
	testCounter = 0
}

func GetTestContext() context.Context {
	ctx := context.Background()
	return context.WithValue(ctx, "trace_id", uuid.New().String())
}

func GetTestContextWithTraceID(traceID string) context.Context {
	ctx := context.Background()
	return context.WithValue(ctx, "trace_id", traceID)
}

type WaitGroupWithTimeout struct {
	wg sync.WaitGroup
}

func NewWaitGroupWithTimeout() *WaitGroupWithTimeout {
	return &WaitGroupWithTimeout{}
}

func (w *WaitGroupWithTimeout) Add(delta int) {
	w.wg.Add(delta)
}

func (w *WaitGroupWithTimeout) Done() {
	w.wg.Done()
}

func (w *WaitGroupWithTimeout) WaitWithTimeout(timeout time.Duration) bool {
	done := make(chan struct{})
	go func() {
		w.wg.Wait()
		close(done)
	}()

	select {
	case <-done:
		return true
	case <-time.After(timeout):
		return false
	}
}

type ConcurrentTestHelper struct {
	mu        sync.Mutex
	errors    []error
	successes int
}

func NewConcurrentTestHelper() *ConcurrentTestHelper {
	return &ConcurrentTestHelper{
		errors: make([]error, 0),
	}
}

func (c *ConcurrentTestHelper) AddError(err error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.errors = append(c.errors, err)
}

func (c *ConcurrentTestHelper) IncrementSuccess() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.successes++
}

func (c *ConcurrentTestHelper) GetErrors() []error {
	c.mu.Lock()
	defer c.mu.Unlock()
	errs := make([]error, len(c.errors))
	copy(errs, c.errors)
	return errs
}

func (c *ConcurrentTestHelper) GetSuccessCount() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.successes
}

func (c *ConcurrentTestHelper) HasErrors() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.errors) > 0
}

func (c *ConcurrentTestHelper) Reset() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.errors = make([]error, 0)
	c.successes = 0
}
