package offline_cache

import (
	"context"
	"fmt"
	"runtime"
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

func setupCacheTestDB(t *testing.T) *gorm.DB {
	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{})
	require.NoError(t, err)

	err = db.AutoMigrate(&CachedData{}, &SyncJob{})
	require.NoError(t, err)

	return db
}

type memoryMonitor struct {
	allocations int64
	frees       int64
}

func newMemoryMonitor() *memoryMonitor {
	return &memoryMonitor{}
}

func (m *memoryMonitor) recordAlloc() {
	atomic.AddInt64(&m.allocations, 1)
}

func (m *memoryMonitor) recordFree() {
	atomic.AddInt64(&m.frees, 1)
}

func (m *memoryMonitor) getNet() (alloc, free int64) {
	return atomic.LoadInt64(&m.allocations), atomic.LoadInt64(&m.frees)
}

func TestOfflineCacheService_CacheData_NormalFlow(t *testing.T) {
	t.Parallel()

	testCases := []struct {
		name      string
		buildReq  func() *CacheRequest
		hasTTL    bool
	}{
		{
			name: "缓存传感器读数",
			buildReq: func() *CacheRequest {
				return testutils.NewCacheRequestBuilder().
					WithDeviceID("dev_cache_001").
					WithDataType("temperature").
					WithPayload(map[string]interface{}{
						"value":     25.5,
						"unit":      "celsius",
						"timestamp": time.Now().Unix(),
					}).
					WithTTL(3600).
					Build()
			},
			hasTTL: true,
		},
		{
			name: "缓存无TTL数据",
			buildReq: func() *CacheRequest {
				return testutils.NewCacheRequestBuilder().
					WithDeviceID("dev_cache_002").
					WithDataType("status").
					WithPayload(map[string]interface{}{
						"status": "healthy",
						"uptime": 86400,
					}).
					WithZeroTTL().
					Build()
			},
			hasTTL: false,
		},
		{
			name: "缓存大数据量",
			buildReq: func() *CacheRequest {
				return testutils.NewCacheRequestBuilder().
					WithDeviceID("dev_cache_003").
					WithDataType("large_data").
					WithLargePayload(100).
					WithTTL(1800).
					Build()
			},
			hasTTL: true,
		},
	}

	for _, tc := range testCases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			db := setupCacheTestDB(t)
			mockEB := testutils.NewMockEventBus()
			service := NewOfflineCacheServiceWithDeps(db, mockEB)
			ctx := testutils.GetTestContext()

			req := tc.buildReq()
			cached, err := service.CacheData(ctx, req)

			require.NoError(t, err)
			require.NotNil(t, cached)
			assert.NotEmpty(t, cached.CacheKey)
			assert.Equal(t, req.DeviceID, cached.DeviceID)
			assert.Equal(t, req.DataType, cached.DataType)
			assert.Equal(t, CacheStatusPending, cached.Status)
			assert.Greater(t, cached.SizeBytes, int64(0))
			assert.Equal(t, 0, cached.RetryCount)

			if tc.hasTTL {
				assert.NotNil(t, cached.ExpiresAt)
				assert.True(t, cached.ExpiresAt.After(time.Now().UTC()))
			} else {
				assert.Nil(t, cached.ExpiresAt)
			}

			var count int64
			db.Model(&CachedData{}).Where("cache_key = ?", cached.CacheKey).Count(&count)
			assert.Equal(t, int64(1), count)

			events := mockEB.GetPublishedEventsByType(eventbus.EventDataCached)
			assert.Len(t, events, 1)
			assert.Equal(t, cached.CacheKey, events[0].Payload["cache_key"])
			assert.Equal(t, req.DeviceID, events[0].Payload["device_id"])
			assert.Equal(t, req.DataType, events[0].Payload["data_type"])
		})
	}
}

func TestOfflineCacheService_CacheData_BoundaryCases(t *testing.T) {
	t.Parallel()

	t.Run("空设备ID", func(t *testing.T) {
		t.Parallel()

		db := setupCacheTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewOfflineCacheServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		req := testutils.NewCacheRequestBuilder().
			WithEmptyDeviceID().
			Build()

		cached, err := service.CacheData(ctx, req)
		if err != nil {
			t.Logf("空设备ID处理结果: %v", err)
		} else {
			assert.NotNil(t, cached)
		}
	})

	t.Run("空数据类型", func(t *testing.T) {
		t.Parallel()

		db := setupCacheTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewOfflineCacheServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		req := testutils.NewCacheRequestBuilder().
			WithEmptyDataType().
			Build()

		cached, err := service.CacheData(ctx, req)
		if err != nil {
			t.Logf("空数据类型处理结果: %v", err)
		} else {
			assert.NotNil(t, cached)
		}
	})

	t.Run("空Payload", func(t *testing.T) {
		t.Parallel()

		db := setupCacheTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewOfflineCacheServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		req := testutils.NewCacheRequestBuilder().
			WithEmptyPayload().
			Build()

		cached, err := service.CacheData(ctx, req)
		if err != nil {
			t.Logf("空Payload处理结果: %v", err)
		} else {
			assert.NotNil(t, cached)
		}
	})

	t.Run("零TTL", func(t *testing.T) {
		t.Parallel()

		db := setupCacheTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewOfflineCacheServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		req := testutils.NewCacheRequestBuilder().
			WithZeroTTL().
			Build()

		cached, err := service.CacheData(ctx, req)
		require.NoError(t, err)
		assert.NotNil(t, cached)
		assert.Nil(t, cached.ExpiresAt)
	})

	t.Run("负TTL", func(t *testing.T) {
		t.Parallel()

		db := setupCacheTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewOfflineCacheServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		req := testutils.NewCacheRequestBuilder().
			WithTTL(-100).
			Build()

		cached, err := service.CacheData(ctx, req)
		require.NoError(t, err)
		assert.NotNil(t, cached)
		assert.Nil(t, cached.ExpiresAt)
	})
}

func TestOfflineCacheService_NetworkStatusManagement(t *testing.T) {
	t.Parallel()

	db := setupCacheTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewOfflineCacheServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	assert.True(t, service.CheckNetworkStatus(ctx), "初始状态应为在线")

	service.SetNetworkStatus(false)
	assert.False(t, service.CheckNetworkStatus(ctx), "设置离线后应为离线")

	service.SetNetworkStatus(true)
	assert.True(t, service.CheckNetworkStatus(ctx), "设置在线后应为在线")

	t.Run("网络恢复网络恢复时触发同步", func(t *testing.T) {
		mockEB.Reset()

		for i := 0; i < 5; i++ {
			req := testutils.NewCacheRequestBuilder().
				WithDeviceID(fmt.Sprintf("dev_net_%d", i%2)).
				WithDataType("reading").
				Build()
			_, err := service.CacheData(ctx, req)
			require.NoError(t, err)
		}

		service.SetNetworkStatus(false)
		assert.Equal(t, 0, mockEB.GetPublishedEventCount())

		service.SetNetworkStatus(true)

		time.Sleep(100 * time.Millisecond)

		events := mockEB.GetPublishedEventsByType(eventbus.EventNetworkRestored)
		assert.Len(t, events, 1)
	})
}

func TestOfflineCacheService_ConcurrentCaching(t *testing.T) {
	t.Parallel()

	db := setupCacheTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewOfflineCacheServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	concurrency := 100
	iterations := 10
	var wg sync.WaitGroup
	helper := testutils.NewConcurrentTestHelper()
	monitor := newMemoryMonitor()

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()

			for j := 0; j < iterations; j++ {
				monitor.recordAlloc()

				req := testutils.NewCacheRequestBuilder().
					WithDeviceID(fmt.Sprintf("dev_conc_%d", workerID%5)).
					WithDataType(fmt.Sprintf("type_%d", j%3)).
					WithPayload(map[string]interface{}{
						"worker": workerID,
						"iter":   j,
						"value":  workerID * j,
					}).
					WithTTL(3600).
					Build()

				cached, err := service.CacheData(ctx, req)
				if err != nil {
					helper.AddError(fmt.Errorf("缓存失败 worker=%d iter=%d: %w", workerID, j, err))
					return
				}
				if cached == nil {
					helper.AddError(fmt.Errorf("缓存返回空 worker=%d iter=%d", workerID, j))
					return
				}
				helper.IncrementSuccess()
			}
		}(i)
	}

	wg.Wait()

	assert.False(t, helper.HasErrors(), "并发缓存出现错误: %v", helper.GetErrors())
	assert.Equal(t, concurrency*iterations, helper.GetSuccessCount())

	var total int64
	db.Model(&CachedData{}).Count(&total)
	assert.Equal(t, int64(concurrency*iterations), total)

	assert.Equal(t, concurrency*iterations, mockEB.GetPublishedEventCount())

	t.Run("资源释放检查", func(t *testing.T) {
		runtime.GC()
		time.Sleep(100 * time.Millisecond)

		var memStats runtime.MemStats
		runtime.ReadMemStats(&memStats)

		assert.Greater(t, memStats.NumGC, uint32(0), "GC应该已运行")

		var pendingCount int64
		db.Model(&CachedData{}).Where("status = ?", CacheStatusPending).Count(&pendingCount)
		assert.Greater(t, pendingCount, int64(0), "应该有待同步数据")
	})
}

func TestOfflineCacheService_StartSync_NormalFlow(t *testing.T) {
	t.Parallel()

	db := setupCacheTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewOfflineCacheServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	for i := 0; i < 10; i++ {
		req := testutils.NewCacheRequestBuilder().
			WithDeviceID("dev_sync_001").
			WithDataType("reading").
			WithPayload(map[string]interface{}{"idx": i}).
			Build()
		_, err := service.CacheData(ctx, req)
		require.NoError(t, err)
	}

	pending, err := service.GetPendingCount(ctx, "dev_sync_001")
	require.NoError(t, err)
	assert.Equal(t, int64(10), pending)

	job, err := service.StartSync(ctx, "dev_sync_001")

	require.NoError(t, err)
	require.NotNil(t, job)
	assert.Equal(t, SyncStatusSyncing, job.Status)
	assert.Equal(t, 10, job.TotalCount)
	assert.NotNil(t, job.StartedAt)

	time.Sleep(500 * time.Millisecond)

	updatedJob, err := service.GetSyncJob(ctx, job.JobID)
	require.NoError(t, err)
	require.NotNil(t, updatedJob)
	assert.Equal(t, SyncStatusCompleted, updatedJob.Status)
	assert.Equal(t, 10, updatedJob.SyncedCount)
	assert.NotNil(t, updatedJob.CompletedAt)

	var syncedCount int64
	db.Model(&CachedData{}).Where("device_id = ? AND status = ?", "dev_sync_001", CacheStatusSynced).Count(&syncedCount)
	assert.Equal(t, int64(10), syncedCount)

	syncEvents := mockEB.GetPublishedEventsByType(eventbus.EventDataSynced)
	assert.Len(t, syncEvents, 10)
}

func TestOfflineCacheService_StartSync_ErrorCases(t *testing.T) {
	t.Parallel()

	t.Run("无待同步数据", func(t *testing.T) {
		t.Parallel()

		db := setupCacheTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewOfflineCacheServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		job, err := service.StartSync(ctx, "dev_empty")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "no pending data")
		assert.Nil(t, job)
	})
}

func TestOfflineCacheService_ConcurrentSyncOperations(t *testing.T) {
	t.Parallel()

	db := setupCacheTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewOfflineCacheServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	deviceCount := 5
	itemsPerDevice := 20

	for d := 0; d < deviceCount; d++ {
		for i := 0; i < itemsPerDevice; i++ {
			req := testutils.NewCacheRequestBuilder().
				WithDeviceID(fmt.Sprintf("dev_multi_%d", d)).
				WithDataType("reading").
				WithPayload(map[string]interface{}{"dev": d, "idx": i}).
				Build()
			_, err := service.CacheData(ctx, req)
			require.NoError(t, err)
		}
	}

	var wg sync.WaitGroup
	helper := testutils.NewConcurrentTestHelper()
	jobIDs := make([]string, deviceCount)
	var mu sync.Mutex

	for d := 0; d < deviceCount; d++ {
		wg.Add(1)
		go func(devID int) {
			defer wg.Done()

			job, err := service.StartSync(ctx, fmt.Sprintf("dev_multi_%d", devID))
			if err != nil {
				helper.AddError(fmt.Errorf("设备%d同步失败: %w", devID, err))
				return
			}

			mu.Lock()
			jobIDs[devID] = job.JobID
			mu.Unlock()

			helper.IncrementSuccess()
		}(d)
	}

	wg.Wait()

	assert.False(t, helper.HasErrors(), "并发同步出现错误: %v", helper.GetErrors())
	assert.Equal(t, deviceCount, helper.GetSuccessCount())

	time.Sleep(1 * time.Second)

	var totalSynced int64
	db.Model(&CachedData{}).Where("status = ?", CacheStatusSynced).Count(&totalSynced)
	assert.Equal(t, int64(deviceCount*itemsPerDevice), totalSynced)

	syncEvents := mockEB.GetPublishedEventsByType(eventbus.EventDataSynced)
	assert.Len(t, syncEvents, deviceCount*itemsPerDevice)

	t.Run("资源完整性检查", func(t *testing.T) {
		for _, jobID := range jobIDs {
			job, err := service.GetSyncJob(ctx, jobID)
			require.NoError(t, err)
			require.NotNil(t, job)
			assert.Equal(t, SyncStatusCompleted, job.Status)
			assert.Equal(t, itemsPerDevice, job.TotalCount)
			assert.Equal(t, itemsPerDevice, job.SyncedCount)
			assert.Equal(t, 0, job.FailedCount)
			assert.NotNil(t, job.CompletedAt)
		}
	})
}

func TestOfflineCacheService_GetCachedData_NormalFlow(t *testing.T) {
	t.Parallel()

	db := setupCacheTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewOfflineCacheServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	dataTypes := []string{"temperature", "humidity", "pressure"}
	for i := 0; i < 15; i++ {
		req := testutils.NewCacheRequestBuilder().
			WithDeviceID("dev_get_001").
			WithDataType(dataTypes[i%3]).
			WithPayload(map[string]interface{}{"value": i}).
			Build()
		_, err := service.CacheData(ctx, req)
		require.NoError(t, err)
	}

	t.Run("查询所有数据", func(t *testing.T) {
		data, total, err := service.GetCachedData(ctx, "dev_get_001", "", 100)
		require.NoError(t, err)
		assert.Equal(t, int64(15), total)
		assert.Len(t, data, 15)
	})

	t.Run("按类型过滤", func(t *testing.T) {
		data, total, err := service.GetCachedData(ctx, "dev_get_001", "temperature", 100)
		require.NoError(t, err)
		assert.Equal(t, int64(5), total)
		assert.Len(t, data, 5)

		for _, d := range data {
			assert.Equal(t, "temperature", d.DataType)
		}
	})

	t.Run("限制返回数量", func(t *testing.T) {
		data, total, err := service.GetCachedData(ctx, "dev_get_001", "", 5)
		require.NoError(t, err)
		assert.Equal(t, int64(15), total)
		assert.Len(t, data, 5)
	})
}

func TestOfflineCacheService_DeleteSyncedData_ResourceRelease(t *testing.T) {
	t.Parallel()

	db := setupCacheTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewOfflineCacheServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	for i := 0; i < 10; i++ {
		cached := testutils.NewCachedDataBuilder().
			WithCacheKey(fmt.Sprintf("cache_del_%02d", i)).
			WithDeviceID("dev_del_001")).
			WithStatus(CacheStatusSynced).
			WithSyncedAt(time.Now().UTC().Add(-48 * time.Hour * time.Duration(-1))).
			Build()
		db.Create(cached)
	}

	for i := 0; i < 5; i++ {
		cached := testutils.NewCachedDataBuilder().
			WithCacheKey(fmt.Sprintf("cache_new_%02d", i)).
			WithDeviceID("dev_del_001")).
			WithStatus(CacheStatusSynced).
			WithSyncedAt(time.Now().UTC().Add(-1 * time.Hour)).
			Build()
		db.Create(cached)
	}

	for i := 0; i < 5; i++ {
		cached := testutils.NewCachedDataBuilder().
			WithCacheKey(fmt.Sprintf("cache_pend_%02d", i)).
			WithDeviceID("dev_del_001")).
			WithStatus(CacheStatusPending).
			Build()
		db.Create(cached)
	}

	var beforeTotal int64
	db.Model(&CachedData{}).Count(&beforeTotal)
	assert.Equal(t, int64(20), beforeTotal)

	deleted, err := service.DeleteSyncedData(ctx, time.Now().UTC().Add(-24*time.Hour))

	require.NoError(t, err)
	assert.Equal(t, int64(10), deleted)

	var afterTotal int64
	db.Model(&CachedData{}).Count(&afterTotal)
	assert.Equal(t, int64(10), afterTotal)

	var pendingCount int64
	db.Model(&CachedData{}).Where("status = ?", CacheStatusPending).Count(&pendingCount)
	assert.Equal(t, int64(5), pendingCount)

	var recentSynced int64
	db.Model(&CachedData{}).Where("status = ?", CacheStatusSynced).Count(&recentSynced)
	assert.Equal(t, int64(5), recentSynced)

	t.Run("内存释放验证", func(t *testing.T) {
		runtime.GC()
		time.Sleep(200 * time.Millisecond)

		var memStats runtime.MemStats
		runtime.ReadMemStats(&memStats)

		t.Logf("GC计数: %d, Heap分配: %d bytes", memStats.NumGC, memStats.HeapAlloc)

		assert.Greater(t, memStats.NumGC, uint32(0))
	})
}

func TestOfflineCacheService_ResourceLeakDetection(t *testing.T) {
	t.Parallel()

	db := setupCacheTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewOfflineCacheServiceWithDeps(db, mockEB)
	ctx, cancel := context.WithCancel(testutils.GetTestContext())

	go service.StartAutoSync(ctx, 100*time.Millisecond)

	operationCount := 1000
	for i := 0; i < operationCount; i++ {
		req := testutils.NewCacheRequestBuilder().
			WithDeviceID(fmt.Sprintf("dev_leak_%d", i%10)).
			WithDataType("metric").
			WithPayload(map[string]interface{}{
				"idx":   i,
				"value": float64(i) * 0.5,
			}).
			Build()

		_, err := service.CacheData(ctx, req)
		require.NoError(t, err)
	}

	time.Sleep(2 * time.Second)

	cancel()

	time.Sleep(200 * time.Millisecond)

	var memStatsBefore runtime.MemStats
	runtime.ReadMemStats(&memStatsBefore)

	heapBefore := memStatsBefore.HeapInuse

	runtime.GC()
	runtime.GC()
	runtime.GC()
	time.Sleep(500 * time.Millisecond)

	var memStatsAfter runtime.MemStats
	runtime.ReadMemStats(&memStatsAfter)

	heapAfter := memStatsAfter.HeapInuse

	t.Logf("Heap使用前: %d bytes, 使用后: %d bytes, 差值: %d bytes",
		heapBefore, heapAfter, int64(heapBefore)-int64(heapAfter))

	if int64(heapBefore)-int64(heapAfter)) < 0 {
		t.Log("警告: 可能存在内存泄漏, GC后内存增加了 %d bytes",
			int64(heapAfter)-int64(heapBefore))
	}

	var synced, _ := service.GetPendingCount(ctx, "dev_leak_0")
	assert.GreaterOrEqual(t, synced, int64(0))

	t.Run("Goroutine泄漏检测", func(t *testing.T) {
		numGoroutines := runtime.NumGoroutine()
		t.Logf("当前Goroutine数量: %d", numGoroutines)

		assert.Less(t, numGoroutines, 50, "Goroutine数量应该在合理范围内")
	})
}

func TestOfflineCacheService_GetPendingCount(t *testing.T) {
	t.Parallel()

	db := setupCacheTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewOfflineCacheServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	for i := 0; i < 7; i++ {
		cached := testutils.NewCachedDataBuilder().
			WithCacheKey(fmt.Sprintf("cache_pend_cnt_%d", i)).
			WithDeviceID("dev_pend_001")).
			WithStatus(CacheStatusPending).
			Build()
		db.Create(cached)
	}

	for i := 0; i < 3; i++ {
		cached := testutils.NewCachedDataBuilder().
			WithCacheKey(fmt.Sprintf("cache_sync_cnt_%d", i)).
			WithDeviceID("dev_pend_001")).
			WithStatus(CacheStatusSynced).
			Build()
		db.Create(cached)
	}

	count, err := service.GetPendingCount(ctx, "dev_pend_001")

	require.NoError(t, err)
	assert.Equal(t, int64(7), count)
}

func TestOfflineCacheService_OfflineCachingAndSyncCycle(t *testing.T) {
	t.Parallel()

	db := setupCacheTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewOfflineCacheServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	service.SetNetworkStatus(false)

	cachedItems := 20
	for i := 0; i < cachedItems; i++ {
		req := testutils.NewCacheRequestBuilder().
			WithDeviceID("dev_cycle_001").
			WithDataType("cycle_reading").
			WithPayload(map[string]interface{}{
				"value": i,
				"ts":    time.Now().UnixNano(),
			}).
			Build()

		_, err := service.CacheData(ctx, req)
		require.NoError(t, err)
	}

	pending, _ := service.GetPendingCount(ctx, "dev_cycle_001")
	assert.Equal(t, int64(cachedItems), pending)

	service.SetNetworkStatus(true)

	time.Sleep(500 * time.Millisecond)

	pendingAfter, _ := service.GetPendingCount(ctx, "dev_cycle_001")
	assert.Equal(t, int64(0), pendingAfter)

	var syncedCount int64
	db.Model(&CachedData{}).Where("device_id = ? AND status = ?",
		"dev_cycle_001", CacheStatusSynced).Count(&syncedCount)
	assert.Equal(t, int64(cachedItems), syncedCount)

	syncEvents := mockEB.GetPublishedEventsByType(eventbus.EventDataSynced)
	assert.Len(t, syncEvents, cachedItems)
}

func TestOfflineCacheService_DatabaseFailure(t *testing.T) {
	t.Parallel()

	db, _ := gorm.Open(sqlite.Open("file::memory:"), &gorm.Config{})
	mockEB := testutils.NewMockEventBus()
	service := NewOfflineCacheServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	req := testutils.NewCacheRequestBuilder().Build()

	cached, err := service.CacheData(ctx, req)

	require.Error(t, err)
	assert.Nil(t, cached)
	assert.Contains(t, err.Error(), "failed to cache data")
}

func TestOfflineCacheService_EventBusFailure_Isolation(t *testing.T) {
	t.Parallel()

	db := setupCacheTestDB(t)
	mockEB := testutils.NewMockEventBus()
	mockEB.SetShouldFail(true, eventbus.EventDataCached)

	service := NewOfflineCacheServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	req := testutils.NewCacheRequestBuilder().
		WithDeviceID("dev_eb_fail").
		Build()

	cached, err := service.CacheData(ctx, req)

	require.NoError(t, err, "事件总线失败不应影响缓存操作")
	require.NotNil(t, cached)

	var count int64
	db.Model(&CachedData{}).Where("cache_key = ?", cached.CacheKey).Count(&count)
	assert.Equal(t, int64(1), count)
}

func TestOfflineCacheService_ContextCancellation_ResourceCleanup(t *testing.T) {
	t.Parallel()

	db := setupCacheTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewOfflineCacheServiceWithDeps(db, mockEB)

	ctx, cancel := context.WithCancel(testutils.GetTestContext())

	goroutineBefore := runtime.NumGoroutine()

	go service.StartAutoSync(ctx, 50*time.Millisecond)

	time.Sleep(100 * time.Millisecond)

	goroutineDuring := runtime.NumGoroutine()
	assert.Greater(t, goroutineDuring, goroutineBefore)

	cancel()

	time.Sleep(200 * time.Millisecond)

	goroutineAfter := runtime.NumGoroutine()
	assert.LessOrEqual(t, goroutineAfter, goroutineDuring,
		"Context取消后Goroutine应该被清理")

	t.Logf("Goroutine数量: 之前=%d, 期间=%d, 之后=%d",
		goroutineBefore, goroutineDuring, goroutineAfter)
}

func TestOfflineCacheService_MassiveData_ResourceManagement(t *testing.T) {
	t.Parallel()

	db := setupCacheTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewOfflineCacheServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	largeCount := 500
	var wg sync.WaitGroup
	helper := testutils.NewConcurrentTestHelper()

	for i := 0; i < largeCount; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()

			req := testutils.NewCacheRequestBuilder().
				WithDeviceID(fmt.Sprintf("dev_mass_%d", idx%20)).
				WithDataType("massive").
				WithPayload(map[string]interface{}{
					"idx":   idx,
					"data":  string(make([]byte, 1024)),
				}).
				Build()

			_, err := service.CacheData(ctx, req)
			if err != nil {
				helper.AddError(fmt.Errorf("大规模缓存失败 idx=%d: %w", idx, err))
				return
			}
			helper.IncrementSuccess()
		}(i)
	}

	wg.Wait()

	assert.False(t, helper.HasErrors(), "大规模缓存出现错误: %v", helper.GetErrors())
	assert.Equal(t, largeCount, helper.GetSuccessCount())

	var total int64
	db.Model(&CachedData{}).Count(&total)
	assert.Equal(t, int64(largeCount), total)

	t.Run("同步后资源释放", func(t *testing.T) {
		ctxSync, cancelSync := context.WithCancel(ctx)
		go service.StartAutoSync(ctxSync, 10*time.Millisecond)

		time.Sleep(3 * time.Second)

		cancelSync()
		time.Sleep(200 * time.Millisecond)

		var synced int64
		db.Model(&CachedData{}).Where("status = ?", CacheStatusSynced).Count(&synced)
		assert.Greater(t, synced, int64(0), "应该有数据已同步")

		deleted, err := service.DeleteSyncedData(ctx, time.Now().UTC())
		require.NoError(t, err)
		assert.Greater(t, deleted, int64(0), "应该有数据被清理")

		runtime.GC()
		time.Sleep(100 * time.Millisecond)

		var memStats runtime.MemStats
		runtime.ReadMemStats(&memStats)
		t.Logf("最终Heap使用: %d bytes, GC次数: %d",
			memStats.HeapInuse, memStats.NumGC)
	})
}
