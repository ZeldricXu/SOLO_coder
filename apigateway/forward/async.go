package forward

import (
	"net/http"
	"sync"
	"time"
)

type ForwardTask struct {
	Request       *http.Request
	Writer        http.ResponseWriter
	RouteID       string
	TargetURL     string
	Timeout       time.Duration
	CreatedAt     time.Time
	RetryCount    int
	MaxRetries    int
	StatusCode    int
	Error         error
	Done          chan struct{}
}

type AsyncForwarder struct {
	taskQueue     chan *ForwardTask
	workerCount   int
	wg            sync.WaitGroup
	stopChan      chan struct{}
	stopped       bool
	mu            sync.Mutex
	completed     int
	failed        int
	processedChan chan *ForwardTask
}

func NewAsyncForwarder(workerCount int, queueSize int) *AsyncForwarder {
	return &AsyncForwarder{
		taskQueue:     make(chan *ForwardTask, queueSize),
		workerCount:   workerCount,
		stopChan:      make(chan struct{}),
		processedChan: make(chan *ForwardTask, 1000),
	}
}

func (af *AsyncForwarder) Start() {
	af.mu.Lock()
	af.stopped = false
	af.mu.Unlock()

	for i := 0; i < af.workerCount; i++ {
		af.wg.Add(1)
		go af.worker(i)
	}
}

func (af *AsyncForwarder) Stop() {
	af.mu.Lock()
	if af.stopped {
		af.mu.Unlock()
		return
	}
	af.stopped = true
	af.mu.Unlock()

	close(af.stopChan)
	af.wg.Wait()
}

func (af *AsyncForwarder) worker(id int) {
	defer af.wg.Done()

	for {
		select {
		case <-af.stopChan:
			return
		case task := <-af.taskQueue:
			if task == nil {
				continue
			}
			af.processTask(task)
		}
	}
}

func (af *AsyncForwarder) processTask(task *ForwardTask) {
	time.Sleep(10 * time.Millisecond)

	af.mu.Lock()
	af.completed++
	af.mu.Unlock()

	select {
	case af.processedChan <- task:
	default:
	}

	if task.Done != nil {
		close(task.Done)
	}
}

func (af *AsyncForwarder) Submit(task *ForwardTask) error {
	af.mu.Lock()
	if af.stopped {
		af.mu.Unlock()
		return &AsyncError{Op: "submit", Err: "async forwarder stopped"}
	}
	af.mu.Unlock()

	task.CreatedAt = time.Now()

	select {
	case af.taskQueue <- task:
		return nil
	default:
		return &AsyncError{Op: "submit", Err: "task queue full"}
	}
}

func (af *AsyncForwarder) SubmitNonBlocking(task *ForwardTask) bool {
	af.mu.Lock()
	if af.stopped {
		af.mu.Unlock()
		return false
	}
	af.mu.Unlock()

	task.CreatedAt = time.Now()

	select {
	case af.taskQueue <- task:
		return true
	default:
		return false
	}
}

func (af *AsyncForwarder) QueueSize() int {
	return len(af.taskQueue)
}

func (af *AsyncForwarder) QueueCapacity() int {
	return cap(af.taskQueue)
}

func (af *AsyncForwarder) Stats() (completed, failed, pending int) {
	af.mu.Lock()
	defer af.mu.Unlock()
	return af.completed, af.failed, len(af.taskQueue)
}

func (af *AsyncForwarder) Processed() <-chan *ForwardTask {
	return af.processedChan
}

type AsyncError struct {
	Op  string
	Err string
}

func (e *AsyncError) Error() string {
	return "async forwarder " + e.Op + ": " + e.Err
}

type AsyncForwarderPool struct {
	forwarders map[string]*AsyncForwarder
	mu         sync.RWMutex
}

func NewAsyncForwarderPool() *AsyncForwarderPool {
	return &AsyncForwarderPool{
		forwarders: make(map[string]*AsyncForwarder),
	}
}

func (afp *AsyncForwarderPool) Register(name string, workerCount int, queueSize int) *AsyncForwarder {
	afp.mu.Lock()
	defer afp.mu.Unlock()

	af := NewAsyncForwarder(workerCount, queueSize)
	afp.forwarders[name] = af
	af.Start()
	return af
}

func (afp *AsyncForwarderPool) Get(name string) (*AsyncForwarder, bool) {
	afp.mu.RLock()
	defer afp.mu.RUnlock()

	af, exists := afp.forwarders[name]
	return af, exists
}

func (afp *AsyncForwarderPool) StopAll() {
	afp.mu.Lock()
	defer afp.mu.Unlock()

	for _, af := range afp.forwarders {
		af.Stop()
	}
	afp.forwarders = make(map[string]*AsyncForwarder)
}

func (afp *AsyncForwarderPool) Submit(name string, task *ForwardTask) error {
	afp.mu.RLock()
	af, exists := afp.forwarders[name]
	afp.mu.RUnlock()

	if !exists {
		return &AsyncError{Op: "pool submit", Err: "forwarder not found: " + name}
	}

	return af.Submit(task)
}
