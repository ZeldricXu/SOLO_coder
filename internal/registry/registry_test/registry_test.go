package registry_test

import (
	"context"
	"testing"
	"time"

	"github.com/distributed-task-scheduler/internal/config"
	"github.com/distributed-task-scheduler/internal/models"
	"github.com/distributed-task-scheduler/internal/registry"
	"github.com/distributed-task-scheduler/internal/storage"
	"github.com/distributed-task-scheduler/test/testkit"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/stretchr/testify/suite"
)

type RegistrySuite struct {
	suite.Suite
	db      *storage.Database
	redis   *storage.RedisClient
	cleanup func()
}

func TestRegistrySuite(t *testing.T) {
	suite.Run(t, new(RegistrySuite))
}

func (s *RegistrySuite) SetupSuite() {
	db, err := storage.NewDatabase(config.DatabaseConfig{
		Host:     "localhost",
		Port:     5432,
		User:     "postgres",
		Password: "postgres",
		DBName:   "task_scheduler_test",
		SSLMode:  "disable",
	})
	if err != nil {
		s.T().Skipf("PostgreSQL not available: %v", err)
	}
	s.db = db

	redis, err := storage.NewRedisClient(config.RedisConfig{
		Addr:     "localhost:6379",
		DB:       14,
		PoolSize: 5,
	})
	if err != nil {
		db.Close()
		s.T().Skipf("Redis not available: %v", err)
	}
	s.redis = redis

	s.cleanup = func() {
		db.Close()
		redis.Close()
	}
}

func (s *RegistrySuite) TearDownSuite() {
	if s.cleanup != nil {
		s.cleanup()
	}
}

func (s *RegistrySuite) SetupTest() {
	s.db.Exec("DELETE FROM workers")
	s.redis.Client.FlushDB(context.Background())
}

func (s *RegistrySuite) TestRegister_NewWorker() {
	reg := registry.WorkerRegistration{
		Namespace:    "test-ns",
		Hostname:     "host-1",
		GRPCAddr:     "localhost:9090",
		HTTPAddr:     "localhost:8080",
		Capabilities: []string{"generic"},
		MaxLoad:      100,
	}

	r := registry.NewRegistry(s.db, s.redis, config.RegistryConfig{
		HealthCheckInterval: 5 * time.Second,
		UnhealthyThreshold:  3,
		AutoRemoveInterval:  30 * time.Second,
	})

	worker, err := r.Register(reg)
	require.NoError(s.T(), err)
	assert.NotEmpty(s.T(), worker.ID)
	assert.Equal(s.T(), "host-1", worker.Hostname)
	assert.Equal(s.T(), models.WorkerStatusHealthy, worker.Status)
}

func (s *RegistrySuite) TestRegister_Idempotent() {
	reg := registry.WorkerRegistration{
		ID:           "worker-fixed-id",
		Namespace:    "test-ns",
		Hostname:     "host-1",
		GRPCAddr:     "localhost:9090",
		HTTPAddr:     "localhost:8080",
		Capabilities: []string{"generic"},
		MaxLoad:      100,
	}

	r := registry.NewRegistry(s.db, s.redis, config.RegistryConfig{})

	worker1, err := r.Register(reg)
	require.NoError(s.T(), err)

	worker2, err := r.Register(reg)
	require.NoError(s.T(), err)

	assert.Equal(s.T(), worker1.ID, worker2.ID)
}

func (s *RegistrySuite) TestDeregister() {
	reg := registry.WorkerRegistration{
		ID:           "worker-to-remove",
		Namespace:    "test-ns",
		Hostname:     "host-remove",
		GRPCAddr:     "localhost:9090",
		HTTPAddr:     "localhost:8080",
		Capabilities: []string{"generic"},
		MaxLoad:      100,
	}

	r := registry.NewRegistry(s.db, s.redis, config.RegistryConfig{})

	_, err := r.Register(reg)
	require.NoError(s.T(), err)

	err = r.Deregister("worker-to-remove")
	require.NoError(s.T(), err)

	_, err = r.GetWorker("worker-to-remove")
	assert.Error(s.T(), err)
}

func (s *RegistrySuite) TestHeartbeat() {
	reg := registry.WorkerRegistration{
		ID:           "worker-heartbeat",
		Namespace:    "test-ns",
		Hostname:     "host-hb",
		GRPCAddr:     "localhost:9090",
		HTTPAddr:     "localhost:8080",
		Capabilities: []string{"generic"},
		MaxLoad:      100,
	}

	r := registry.NewRegistry(s.db, s.redis, config.RegistryConfig{})

	_, err := r.Register(reg)
	require.NoError(s.T(), err)

	err = r.Heartbeat("worker-heartbeat", 42)
	require.NoError(s.T(), err)

	worker, err := r.GetWorker("worker-heartbeat")
	require.NoError(s.T(), err)
	assert.Equal(s.T(), 42, worker.CurrentLoad)
	assert.Equal(s.T(), models.WorkerStatusHealthy, worker.Status)
}

func (s *RegistrySuite) TestHeartbeat_UnknownWorker() {
	r := registry.NewRegistry(s.db, s.redis, config.RegistryConfig{})

	err := r.Heartbeat("nonexistent", 0)
	assert.Error(s.T(), err)
}

func (s *RegistrySuite) TestListWorkers() {
	r := registry.NewRegistry(s.db, s.redis, config.RegistryConfig{})

	for i := 0; i < 3; i++ {
		reg := registry.WorkerRegistration{
			ID:           testkit.NewWorkerBuilder().Build().ID,
			Namespace:    "test-ns",
			Hostname:     "host-list",
			GRPCAddr:     "localhost:9090",
			HTTPAddr:     "localhost:8080",
			Capabilities: []string{"generic"},
			MaxLoad:      100,
		}
		_, err := r.Register(reg)
		require.NoError(s.T(), err)
	}

	workers, err := r.ListWorkers("test-ns")
	require.NoError(s.T(), err)
	assert.GreaterOrEqual(s.T(), len(workers), 3)
}

func (s *RegistrySuite) TestListHealthyWorkers() {
	r := registry.NewRegistry(s.db, s.redis, config.RegistryConfig{})

	healthyReg := registry.WorkerRegistration{
		ID:           "worker-healthy",
		Namespace:    "test-ns",
		Hostname:     "host-healthy",
		GRPCAddr:     "localhost:9090",
		HTTPAddr:     "localhost:8080",
		Capabilities: []string{"generic"},
		MaxLoad:      100,
	}
	_, err := r.Register(healthyReg)
	require.NoError(s.T(), err)

	unhealthyReg := registry.WorkerRegistration{
		ID:           "worker-unhealthy",
		Namespace:    "test-ns",
		Hostname:     "host-unhealthy",
		GRPCAddr:     "localhost:9091",
		HTTPAddr:     "localhost:8081",
		Capabilities: []string{"generic"},
		MaxLoad:      100,
	}
	_, err = r.Register(unhealthyReg)
	require.NoError(s.T(), err)

	s.db.Exec("UPDATE workers SET status = 'unhealthy' WHERE id = 'worker-unhealthy'")

	workers, err := r.ListHealthyWorkers("test-ns")
	require.NoError(s.T(), err)

	for _, w := range workers {
		assert.Equal(s.T(), models.WorkerStatusHealthy, w.Status)
	}
}

func (s *RegistrySuite) TestGetLeastLoadedWorker() {
	r := registry.NewRegistry(s.db, s.redis, config.RegistryConfig{})

	for i := 0; i < 3; i++ {
		reg := registry.WorkerRegistration{
			ID:           testkit.NewWorkerBuilder().Build().ID,
			Namespace:    "test-ns",
			Hostname:     "host-ll",
			GRPCAddr:     "localhost:9090",
			HTTPAddr:     "localhost:8080",
			Capabilities: []string{"generic", "python"},
			MaxLoad:      100,
		}
		_, err := r.Register(reg)
		require.NoError(s.T(), err)
	}

	s.db.Exec("UPDATE workers SET current_load = 50 WHERE hostname = 'host-ll' LIMIT 1")

	worker, err := r.GetLeastLoadedWorker("test-ns", []string{"generic"})
	require.NoError(s.T(), err)
	assert.NotNil(s.T(), worker)
	assert.LessOrEqual(s.T(), worker.CurrentLoad, 50)
}

func (s *RegistrySuite) TestGetLeastLoadedWorker_NoCapabilityMatch() {
	r := registry.NewRegistry(s.db, s.redis, config.RegistryConfig{})

	reg := registry.WorkerRegistration{
		ID:           testkit.NewWorkerBuilder().Build().ID,
		Namespace:    "test-ns",
		Hostname:     "host-no-cap",
		GRPCAddr:     "localhost:9090",
		HTTPAddr:     "localhost:8080",
		Capabilities: []string{"generic"},
		MaxLoad:      100,
	}
	_, err := r.Register(reg)
	require.NoError(s.T(), err)

	_, err = r.GetLeastLoadedWorker("test-ns", []string{"special-capability"})
	assert.Error(s.T(), err)
}

func (s *RegistrySuite) TestHealthCheck_PromotesToUnhealthy() {
	r := registry.NewRegistry(s.db, s.redis, config.RegistryConfig{
		HealthCheckInterval: 100 * time.Millisecond,
		UnhealthyThreshold:  2,
		AutoRemoveInterval:  30 * time.Second,
	})

	reg := registry.WorkerRegistration{
		ID:           "worker-stale",
		Namespace:    "test-ns",
		Hostname:     "host-stale",
		GRPCAddr:     "localhost:9090",
		HTTPAddr:     "localhost:8080",
		Capabilities: []string{"generic"},
		MaxLoad:      100,
	}
	_, err := r.Register(reg)
	require.NoError(s.T(), err)

	s.db.Exec("UPDATE workers SET last_heartbeat = NOW() - INTERVAL '30 seconds' WHERE id = 'worker-stale'")

	r.Start()
	defer r.Stop()

	time.Sleep(500 * time.Millisecond)

	worker, err := r.GetWorker("worker-stale")
	if err == nil {
		assert.NotEqual(s.T(), models.WorkerStatusHealthy, worker.Status)
	}
}

func (s *RegistrySuite) TestConcurrentRegistration() {
	r := registry.NewRegistry(s.db, s.redis, config.RegistryConfig{})

	done := make(chan bool, 10)
	for i := 0; i < 10; i++ {
		go func() {
			reg := registry.WorkerRegistration{
				ID:           testkit.NewWorkerBuilder().Build().ID,
				Namespace:    "test-ns",
				Hostname:     "host-concurrent",
				GRPCAddr:     "localhost:9090",
				HTTPAddr:     "localhost:8080",
				Capabilities: []string{"generic"},
				MaxLoad:      100,
			}
			_, err := r.Register(reg)
			assert.NoError(s.T(), err)
			done <- true
		}()
	}

	for i := 0; i < 10; i++ {
		<-done
	}

	workers, err := r.ListWorkers("test-ns")
	require.NoError(s.T(), err)
	assert.GreaterOrEqual(s.T(), len(workers), 10)
}
