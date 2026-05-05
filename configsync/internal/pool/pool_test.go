package pool

import (
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"configsync/internal/models"
)

var (
	testServer1 = &models.Server{
		ServerID: "server-1",
		Host:     "192.168.1.10",
		Port:     22,
		User:     "admin",
	}
	testServer2 = &models.Server{
		ServerID: "server-2",
		Host:     "192.168.1.11",
		Port:     22,
		User:     "admin",
	}
	testServer3 = &models.Server{
		ServerID: "server-3",
		Host:     "192.168.1.12",
		Port:     22,
		User:     "admin",
	}
)

func TestConnectionPool_ConnectionReuse(t *testing.T) {
	factory := NewMockSSHFactory()
	pool := NewTestPool(TestPoolConfig{MaxPoolSize: 5}, factory)
	defer pool.Close()

	client1, err := pool.Get(testServer1)
	if err != nil {
		t.Fatalf("第一次获取连接失败: %v", err)
	}

	if factory.GetCreateCount() != 1 {
		t.Errorf("期望创建1次连接，实际: %d", factory.GetCreateCount())
	}

	pool.Release(testServer1)

	factory.ResetCreateCount()

	client2, err := pool.Get(testServer1)
	if err != nil {
		t.Fatalf("第二次获取连接失败: %v", err)
	}

	if factory.GetCreateCount() != 0 {
		t.Errorf("期望复用连接（创建0次），实际创建: %d", factory.GetCreateCount())
	}

	if client1 == nil || client2 == nil {
		t.Error("客户端不应为nil")
	}

	pool.Release(testServer1)

	stats := pool.Stats()
	if stats["active_connections"].(int) != 1 {
		t.Errorf("期望1个活跃连接，实际: %d", stats["active_connections"])
	}
}

func TestConnectionPool_DifferentServers(t *testing.T) {
	factory := NewMockSSHFactory()
	pool := NewTestPool(TestPoolConfig{MaxPoolSize: 5}, factory)
	defer pool.Close()

	_, err := pool.Get(testServer1)
	if err != nil {
		t.Fatalf("获取server1连接失败: %v", err)
	}
	pool.Release(testServer1)

	_, err = pool.Get(testServer2)
	if err != nil {
		t.Fatalf("获取server2连接失败: %v", err)
	}
	pool.Release(testServer2)

	if factory.GetCreateCount() != 2 {
		t.Errorf("期望创建2次连接，实际: %d", factory.GetCreateCount())
	}

	stats := pool.Stats()
	if stats["active_connections"].(int) != 2 {
		t.Errorf("期望2个活跃连接，实际: %d", stats["active_connections"])
	}
}

func TestConnectionPool_ForceClose(t *testing.T) {
	factory := NewMockSSHFactory()
	pool := NewTestPool(TestPoolConfig{MaxPoolSize: 5}, factory)
	defer pool.Close()

	_, err := pool.Get(testServer1)
	if err != nil {
		t.Fatalf("获取连接失败: %v", err)
	}

	stats := pool.Stats()
	if stats["active_connections"].(int) != 1 {
		t.Errorf("强制关闭前期望1个活跃连接")
	}

	pool.ForceClose(testServer1)

	stats = pool.Stats()
	if stats["active_connections"].(int) != 0 {
		t.Errorf("强制后期望0个活跃连接，实际: %d", stats["active_connections"])
	}

	factory.ResetCreateCount()

	_, err = pool.Get(testServer1)
	if err != nil {
		t.Fatalf("重新获取连接失败: %v", err)
	}

	if factory.GetCreateCount() != 1 {
		t.Errorf("强制关闭后期望创建新连接，实际创建: %d", factory.GetCreateCount())
	}
}

func TestConnectionPool_PoolSizeLimit(t *testing.T) {
	factory := NewMockSSHFactory()
	pool := NewTestPool(TestPoolConfig{MaxPoolSize: 2}, factory)
	defer pool.Close()

	_, err := pool.Get(testServer1)
	if err != nil {
		t.Fatalf("获取server1失败: %v", err)
	}
	pool.Release(testServer1)

	_, err = pool.Get(testServer2)
	if err != nil {
		t.Fatalf("获取server2失败: %v", err)
	}
	pool.Release(testServer2)

	stats := pool.Stats()
	if stats["active_connections"].(int) != 2 {
		t.Errorf("池满前期望2个活跃连接，实际: %d", stats["active_connections"])
	}

	_, err = pool.Get(testServer3)
	if err != nil {
		t.Fatalf("获取server3失败: %v", err)
	}
	pool.Release(testServer3)

	stats = pool.Stats()
	if stats["active_connections"].(int) != 2 {
		t.Errorf("LRU淘汰后期望仍为2个活跃连接，实际: %d", stats["active_connections"])
	}
}

func TestConnectionPool_ConnectionCreationError(t *testing.T) {
	factory := NewMockSSHFactory()
	factory.SetAlwaysError(fmt.Errorf("connection failed"))

	pool := NewTestPool(TestPoolConfig{MaxPoolSize: 5}, factory)
	defer pool.Close()

	_, err := pool.Get(testServer1)
	if err == nil {
		t.Error("期望连接失败，但没有错误")
	}

	stats := pool.Stats()
	if stats["active_connections"].(int) != 0 {
		t.Errorf("连接失败后期望0个活跃连接，实际: %d", stats["active_connections"])
	}
}

func TestConnectionPool_ConcurrentAccess(t *testing.T) {
	factory := NewMockSSHFactory()
	pool := NewTestPool(TestPoolConfig{MaxPoolSize: 10}, factory)
	defer pool.Close()

	var wg sync.WaitGroup
	var successCount int64
	var errorCount int64
	numGoroutines := 50
	numIterations := 10

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			for j := 0; j < numIterations; j++ {
				server := &models.Server{
					ServerID: fmt.Sprintf("server-%d", idx%5),
					Host:     fmt.Sprintf("10.0.0.%d", idx%5),
					Port:     22,
					User:     "admin",
				}
				_, err := pool.Get(server)
				if err != nil {
					atomic.AddInt64(&errorCount, 1)
					continue
				}
				atomic.AddInt64(&successCount, 1)
				pool.Release(server)
			}
		}(i)
	}

	wg.Wait()

	if errorCount > 0 {
		t.Errorf("并发访问出现错误: %d", errorCount)
	}

	expectedSuccess := int64(numGoroutines * numIterations)
	if successCount != expectedSuccess {
		t.Errorf("期望成功次数 %d，实际: %d", expectedSuccess, successCount)
	}
}

func TestConnectionPool_IdleConnectionCleanup(t *testing.T) {
	factory := NewMockSSHFactory()
	pool := NewTestPool(
		TestPoolConfig{
			MaxIdleTime: 100 * time.Millisecond,
			MaxPoolSize: 5,
		},
		factory,
	)
	defer pool.Close()

	_, err := pool.Get(testServer1)
	if err != nil {
		t.Fatalf("获取连接失败: %v", err)
	}
	pool.Release(testServer1)

	stats := pool.Stats()
	if stats["active_connections"].(int) != 1 {
		t.Errorf("清理前期望1个活跃连接，实际: %d", stats["active_connections"])
	}

	time.Sleep(150 * time.Millisecond)

	cleanerPool, ok := pool.(*ConnectionPool)
	if ok {
		cleanerPool.mu.Lock()
		cleanerPool.cleanupIdleConnections()
		cleanerPool.mu.Unlock()
	}

	stats = pool.Stats()
	if stats["active_connections"].(int) != 0 {
		t.Errorf("空闲超时后期望0个活跃连接，实际: %d", stats["active_connections"])
	}
}

func TestConnectionPool_Stats(t *testing.T) {
	factory := NewMockSSHFactory()
	pool := NewTestPool(TestPoolConfig{MaxPoolSize: 10}, factory)
	defer pool.Close()

	stats := pool.Stats()
	if stats["active_connections"].(int) != 0 {
		t.Errorf("空池期望0个活跃连接，实际: %d", stats["active_connections"])
	}
	if stats["max_pool_size"].(int) != 10 {
		t.Errorf("期望最大池大小10，实际: %d", stats["max_pool_size"])
	}

	_, err := pool.Get(testServer1)
	if err != nil {
		t.Fatalf("获取连接失败: %v", err)
	}
	pool.Release(testServer1)

	_, err = pool.Get(testServer2)
	if err != nil {
		t.Fatalf("获取连接失败: %v", err)
	}
	pool.Release(testServer2)

	stats = pool.Stats()
	if stats["active_connections"].(int) != 2 {
		t.Errorf("期望2个活跃连接，实际: %d", stats["active_connections"])
	}
}

func TestConnectionPool_Close(t *testing.T) {
	factory := NewMockSSHFactory()
	pool := NewTestPool(TestPoolConfig{MaxPoolSize: 5}, factory)

	_, err := pool.Get(testServer1)
	if err != nil {
		t.Fatalf("获取连接失败: %v", err)
	}
	pool.Release(testServer1)

	_, err = pool.Get(testServer2)
	if err != nil {
		t.Fatalf("获取连接失败: %v", err)
	}
	pool.Release(testServer2)

	stats := pool.Stats()
	if stats["active_connections"].(int) != 2 {
		t.Errorf("Close前期望2个活跃连接")
	}

	pool.Close()

	stats = pool.Stats()
	if stats["active_connections"].(int) != 0 {
		t.Errorf("Close后期望0个活跃连接，实际: %d", stats["active_connections"])
	}
}

func TestConnectionPool_GetPoolKey(t *testing.T) {
	pool := NewTestPool(TestPoolConfig{MaxPoolSize: 5}, NewMockSSHFactory())
	defer pool.Close()

	cp, ok := pool.(*ConnectionPool)
	if !ok {
		t.Fatal("无法转换为ConnectionPool")
	}

	key1 := cp.getPoolKey(testServer1)
	expectedKey1 := "192.168.1.10:22:admin"
	if key1 != expectedKey1 {
		t.Errorf("期望key '%s'，实际: '%s'", expectedKey1, key1)
	}

	key2 := cp.getPoolKey(testServer2)
	if key2 == key1 {
		t.Error("不同服务器的key应该不同")
	}
}

func TestConnectionPool_EmptyPool(t *testing.T) {
	factory := NewMockSSHFactory()
	pool := NewTestPool(TestPoolConfig{MaxPoolSize: 5}, factory)
	defer pool.Close()

	stats := pool.Stats()
	if stats["active_connections"].(int) != 0 {
		t.Errorf("空池期望0个活跃连接")
	}

	pool.Release(testServer1)
	pool.ForceClose(testServer1)

	stats = pool.Stats()
	if stats["active_connections"].(int) != 0 {
		t.Errorf("对空池操作后期望仍为0个活跃连接")
	}
}

func TestConnectionPool_MaxIdleTimeConfiguration(t *testing.T) {
	factory := NewMockSSHFactory()
	customIdleTime := 2 * time.Minute
	pool := NewTestPool(
		TestPoolConfig{
			MaxIdleTime: customIdleTime,
			MaxPoolSize: 5,
		},
		factory,
	)
	defer pool.Close()

	stats := pool.Stats()
	if stats["max_idle_time"].(string) != "2m0s" {
		t.Errorf("期望最大空闲时间'2m0s'，实际: '%s'", stats["max_idle_time"])
	}
}

func TestConnectionPool_ConnectionReuseAfterMultipleReleases(t *testing.T) {
	factory := NewMockSSHFactory()
	pool := NewTestPool(TestPoolConfig{MaxPoolSize: 5}, factory)
	defer pool.Close()

	_, err := pool.Get(testServer1)
	if err != nil {
		t.Fatalf("第一次获取失败: %v", err)
	}
	pool.Release(testServer1)

	_, err = pool.Get(testServer1)
	if err != nil {
		t.Fatalf("第二次获取失败: %v", err)
	}
	pool.Release(testServer1)

	_, err = pool.Get(testServer1)
	if err != nil {
		t.Fatalf("第三次获取失败: %v", err)
	}
	pool.Release(testServer1)

	if factory.GetCreateCount() != 1 {
		t.Errorf("多次获取同一服务器应只创建1次连接，实际: %d", factory.GetCreateCount())
	}

	stats := pool.Stats()
	if stats["active_connections"].(int) != 1 {
		t.Errorf("期望1个活跃连接，实际: %d", stats["active_connections"])
	}
}
