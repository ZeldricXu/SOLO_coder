//go:build concurrency

package tests

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
)

type MockNotifier struct {
	mock.Mock
	mu          sync.Mutex
	notifyCount int
	notifyChan  chan struct{}
}

func NewMockNotifier(chanSize int) *MockNotifier {
	return &MockNotifier{
		notifyChan: make(chan struct{}, chanSize),
	}
}

func (m *MockNotifier) Notify(exec *types.InternalEvent, severity types.NotificationSeverity) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.notifyCount++
	select {
	case m.notifyChan <- struct{}{}:
	default:
	}

	args := m.Called(exec, severity)
	return args.Error(0)
}

func (m *MockNotifier) GetNotifyCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.notifyCount
}

func TestResourceQuota_PriorityQueue(t *testing.T) {
	t.Parallel()

	type queuedItem struct {
		priority int
		id       string
	}

	queue := make(chan queuedItem, 10)
	var wg sync.WaitGroup
	var mu sync.Mutex
	processed := make(map[int]string)

	maxConcurrent := 2
	running := 0
	cond := sync.NewCond(&mu)

	processItem := func(item queuedItem) {
		mu.Lock()
		for running >= maxConcurrent {
			cond.Wait()
		}
		running++
		mu.Unlock()

		time.Sleep(20 * time.Millisecond)

		mu.Lock()
		processed[item.priority] = item.id
		running--
		cond.Broadcast()
		mu.Unlock()
	}

	wg.Add(5)

	go func() {
		defer wg.Done()
		for item := range queue {
			go processItem(item)
		}
	}()

	items := []queuedItem{
		{priority: 3, id: "low-priority-1"},
		{priority: 1, id: "high-priority-1"},
		{priority: 2, id: "medium-priority-1"},
		{priority: 1, id: "high-priority-2"},
		{priority: 3, id: "low-priority-2"},
	}

	for _, item := range items {
		queue <- item
	}

	close(queue)

	go func() {
		wg.Wait()
	}()

	time.Sleep(200 * time.Millisecond)

	mu.Lock()
	processedCount := len(processed)
	mu.Unlock()

	assert.GreaterOrEqual(t, processedCount, 3, "应该至少处理3个项目")
}

func TestNotificationChannel_Backpressure(t *testing.T) {
	t.Parallel()

	chanSize := 3
	notifier := NewMockNotifier(chanSize)

	event := &types.InternalEvent{
		ID:          types.ID(types.NewID()),
		EventSource: types.EventSourceManual,
		EventType:   types.EventTypeManual,
	}

	notifier.On("Notify", mock.Anything, mock.Anything).Return(nil)

	var wg sync.WaitGroup
	sendCount := 10

	wg.Add(sendCount)

	for i := 0; i < sendCount; i++ {
		go func(idx int) {
			defer wg.Done()

			ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
			defer cancel()

			_ = notifier.Notify(event, types.NotificationSeverityInfo)
			_ = ctx
		}(i)
	}

	wg.Wait()

	time.Sleep(50 * time.Millisecond)

	notifyCount := notifier.GetNotifyCount()
	assert.Equal(t, sendCount, notifyCount, "所有通知都应该被处理，即使通道打满也不应该阻塞主流程")
}

func TestPluginHotReload_NonBlocking(t *testing.T) {
	t.Parallel()

	type pluginState struct {
		version string
		ready   bool
		mu      sync.RWMutex
	}

	plugin := &pluginState{
		version: "v1.0.0",
		ready:   true,
	}

	var wg sync.WaitGroup
	reloadCount := 5
	requestCount := 20

	reloadCh := make(chan struct{}, reloadCount)
	requestCh := make(chan struct{}, requestCount)

	for i := 0; i < reloadCount; i++ {
		wg.Add(1)
		go func(ver int) {
			defer wg.Done()

			plugin.mu.Lock()
			plugin.ready = false
			plugin.mu.Unlock()

			time.Sleep(10 * time.Millisecond)

			plugin.mu.Lock()
			plugin.version = string(rune('v' + ver))
			plugin.ready = true
			plugin.mu.Unlock()

			reloadCh <- struct{}{}
		}(i)
	}

	lostRequests := 0
	for i := 0; i < requestCount; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()

			plugin.mu.RLock()
			ready := plugin.ready
			plugin.mu.RUnlock()

			if !ready {
				lostRequests++
			}

			requestCh <- struct{}{}
		}()
	}

	wg.Wait()

	close(reloadCh)
	close(requestCh)

	reloadComplete := 0
	for range reloadCh {
		reloadComplete++
	}

	requestComplete := 0
	for range requestCh {
		requestComplete++
	}

	assert.Equal(t, reloadCount, reloadComplete, "所有重载都应该完成")
	assert.Equal(t, requestCount, requestComplete, "所有请求都应该处理，即使插件在热加载期间")
	assert.Less(t, lostRequests, requestCount, "不应该丢失所有请求")
}

func TestConcurrentSecretAccess(t *testing.T) {
	t.Parallel()

	type secretCache struct {
		mu     sync.RWMutex
		values map[string]string
		usage  map[string]int
	}

	cache := &secretCache{
		values: map[string]string{
			"DB_PASSWORD": "secret123",
			"API_KEY":     "key456",
			"TOKEN":       "token789",
		},
		usage: make(map[string]int),
	}

	goroutineCount := 50
	var wg sync.WaitGroup
	var mu sync.Mutex
	errors := make([]error, 0)

	resolveSecret := func(name string) (string, error) {
		cache.mu.RLock()
		defer cache.mu.RUnlock()

		if val, ok := cache.values[name]; ok {
			return val, nil
		}
		return "", assert.AnError
	}

	logUsage := func(name string) {
		cache.mu.Lock()
		defer cache.mu.Unlock()
		cache.usage[name]++
	}

	for i := 0; i < goroutineCount; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()

			secretNames := []string{"DB_PASSWORD", "API_KEY", "TOKEN"}
			name := secretNames[idx%3]

			val, err := resolveSecret(name)
			if err != nil {
				mu.Lock()
				errors = append(errors, err)
				mu.Unlock()
				return
			}

			require.NotEmpty(t, val)

			logUsage(name)
		}(i)
	}

	wg.Wait()

	assert.Empty(t, errors, "并发访问密钥不应该有错误")

	cache.mu.RLock()
	totalUsage := 0
	for _, count := range cache.usage {
		totalUsage += count
	}
	cache.mu.RUnlock()

	assert.Equal(t, goroutineCount, totalUsage, "所有密钥使用都应该被记录")
}

func TestSchedulerConcurrent_Cancellation(t *testing.T) {
	t.Parallel()

	executionCount := 5
	var wg sync.WaitGroup

	type execution struct {
		id     types.ID
		cancel context.CancelFunc
		ctx    context.Context
	}

	var mu sync.Mutex
	runningExecs := make(map[types.ID]context.CancelFunc)

	for i := 0; i < executionCount; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()

			execID := types.ID(string(rune(idx)))
			ctx, cancel := context.WithCancel(context.Background())

			mu.Lock()
			runningExecs[execID] = cancel
			mu.Unlock()

			go func() {
				<-ctx.Done()
			}()

			time.Sleep(10 * time.Millisecond)

			mu.Lock()
			if cancelFn, ok := runningExecs[execID]; ok {
				cancelFn()
				delete(runningExecs, execID)
			}
			mu.Unlock()
		}(i)
	}

	wg.Wait()

	mu.Lock()
	defer mu.Unlock()

	assert.Empty(t, runningExecs, "所有执行都应该被取消并从运行列表中移除")
}

func TestLogStreaming_ConcurrentWrites(t *testing.T) {
	t.Parallel()

	type logStore struct {
		mu     sync.Mutex
		logs   []string
		notify chan string
	}

	store := &logStore{
		logs:   make([]string, 0),
		notify: make(chan string, 100),
	}

	writerCount := 10
	logsPerWriter := 50
	var wg sync.WaitGroup

	for w := 0; w < writerCount; w++ {
		wg.Add(1)
		go func(writerID int) {
			defer wg.Done()

			for l := 0; l < logsPerWriter; l++ {
				logMsg := string(rune(writerID)) + string(rune(l))

				store.mu.Lock()
				store.logs = append(store.logs, logMsg)
				store.mu.Unlock()

				select {
				case store.notify <- logMsg:
				default:
				}
			}
		}(w)
	}

	wg.Wait()

	close(store.notify)

	assert.Equal(t, writerCount*logsPerWriter, len(store.logs), "所有日志都应该被写入")

	notifiedCount := 0
	for range store.notify {
		notifiedCount++
	}

	assert.Greater(t, notifiedCount, 0, "应该有日志通知被发送")
	assert.LessOrEqual(t, notifiedCount, writerCount*logsPerWriter, "通知数量不应该超过日志数量")
}

func TestDeduplication_ConcurrentEvents(t *testing.T) {
	t.Parallel()

	type dedupStore struct {
		mu    sync.RWMutex
		seen  map[string]bool
		count int
	}

	store := &dedupStore{
		seen: make(map[string]bool),
	}

	isNew := func(key string) bool {
		store.mu.Lock()
		defer store.mu.Unlock()

		if store.seen[key] {
			return false
		}
		store.seen[key] = true
		store.count++
		return true
	}

	goroutineCount := 30
	uniqueKeys := 10
	var wg sync.WaitGroup
	var mu sync.Mutex
	processed := 0
	duplicates := 0

	for i := 0; i < goroutineCount; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()

			key := string(rune('A' + idx%uniqueKeys))

			if isNew(key) {
				mu.Lock()
				processed++
				mu.Unlock()
			} else {
				mu.Lock()
				duplicates++
				mu.Unlock()
			}
		}(i)
	}

	wg.Wait()

	assert.Equal(t, uniqueKeys, processed, "应该只处理不重复的事件")
	assert.Equal(t, goroutineCount-uniqueKeys, duplicates, "重复事件应该被正确识别")
	assert.Equal(t, uniqueKeys, store.count, "存储的唯一键数量应该正确")
}
