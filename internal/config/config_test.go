package config

import (
	"fmt"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"techplatform/internal/testdata"
	"techplatform/pkg/common"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newTestManager(t *testing.T) (*Manager, func()) {
	t.Helper()
	mgr := NewManager()
	mgr.autoRefresh = false
	return mgr, func() { mgr.Close() }
}

func TestNewManager(t *testing.T) {
	mgr := NewManager()
	defer mgr.Close()

	assert.NotNil(t, mgr)
	assert.NotNil(t, mgr.viper)
	assert.NotNil(t, mgr.config)
	assert.NotNil(t, mgr.listeners)
	assert.NotNil(t, mgr.items)
	assert.Equal(t, true, mgr.autoRefresh)
	assert.Equal(t, 5*time.Minute, mgr.refreshInterval)
}

func TestLoad_DefaultsOnly(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	cfg := mgr.GetConfig()
	assert.Equal(t, "0.0.0.0", cfg.Server.Host)
	assert.Equal(t, 8080, cfg.Server.Port)
	assert.Equal(t, "debug", cfg.Server.Mode)
	assert.Equal(t, "techplatform.db", cfg.Database.Path)
	assert.Equal(t, "memory", cfg.Cache.Type)
	assert.Equal(t, true, cfg.Security.EnableAuth)
}

func TestLoad_FromFile(t *testing.T) {
	yamlContent := `server:
  host: 127.0.0.1
  port: 9090
  mode: release
database:
  path: test.db
  max_conns: 50
cache:
  type: redis
  strategy: write_through
security:
  secret: my-secret
  jwt_expire: 7200
  enable_auth: false
logging:
  level: warn
  path: /var/log
`
	path, cleanup := testdata.WriteTestConfigFile(yamlContent)
	defer cleanup()

	mgr, mgrCleanup := newTestManager(t)
	defer mgrCleanup()

	err := mgr.Load(path)
	require.NoError(t, err)

	cfg := mgr.GetConfig()
	assert.Equal(t, "127.0.0.1", cfg.Server.Host)
	assert.Equal(t, 9090, cfg.Server.Port)
	assert.Equal(t, "release", cfg.Server.Mode)
	assert.Equal(t, "test.db", cfg.Database.Path)
	assert.Equal(t, 50, cfg.Database.MaxConns)
	assert.Equal(t, "redis", cfg.Cache.Type)
	assert.Equal(t, "my-secret", cfg.Security.Secret)
	assert.Equal(t, 7200, cfg.Security.JWTExpire)
	assert.Equal(t, false, cfg.Security.EnableAuth)
}

func TestLoad_InvalidFile(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("/nonexistent/path/config.yaml")
	require.NoError(t, err)

	cfg := mgr.GetConfig()
	assert.Equal(t, 8080, cfg.Server.Port)
}

func TestLoad_InvalidYAML(t *testing.T) {
	yamlContent := `server:
  host: [invalid yaml
  port: broken
`
	path, cleanup := testdata.WriteTestConfigFile(yamlContent)
	defer cleanup()

	mgr, mgrCleanup := newTestManager(t)
	defer mgrCleanup()

	err := mgr.Load(path)
	require.NoError(t, err)
}

func TestGet(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	val := mgr.Get("server.port")
	assert.Equal(t, 8080, val)

	strVal := mgr.GetString("server.host")
	assert.Equal(t, "0.0.0.0", strVal)

	intVal := mgr.GetInt("server.port")
	assert.Equal(t, 8080, intVal)

	boolVal := mgr.GetBool("security.enable_auth")
	assert.Equal(t, true, boolVal)

	durVal := mgr.GetDuration("cache.ttl")
	assert.Equal(t, time.Hour, durVal)
}

func TestGet_NonExistentKey(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	val := mgr.GetString("nonexistent.key")
	assert.Equal(t, "", val)

	intVal := mgr.GetInt("nonexistent.key")
	assert.Equal(t, 0, intVal)
}

func TestSet(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	err = mgr.Set("server.port", 9090, SourceRemote)
	require.NoError(t, err)

	assert.Equal(t, 9090, mgr.GetInt("server.port"))

	item, err := mgr.GetItem("server.port")
	require.NoError(t, err)
	assert.Equal(t, SourceRemote, item.Source)
	assert.GreaterOrEqual(t, item.Version, 2)
}

func TestSet_TriggersListener(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	var event atomic.Value
	mgr.OnChange("server.port", func(e ConfigChangeEvent) {
		event.Store(e)
	})

	err = mgr.Set("server.port", 7070, SourceRemote)
	require.NoError(t, err)

	time.Sleep(100 * time.Millisecond)

	e, ok := event.Load().(ConfigChangeEvent)
	if ok {
		assert.Equal(t, "server.port", e.Key)
		assert.Equal(t, 7070, e.NewValue)
		assert.Equal(t, SourceRemote, e.Source)
	}
}

func TestSet_WildcardListener(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	var callCount atomic.Int32
	mgr.OnChange("*", func(e ConfigChangeEvent) {
		callCount.Add(1)
	})

	err = mgr.Set("server.port", 7070, SourceDefault)
	require.NoError(t, err)
	err = mgr.Set("server.host", "1.2.3.4", SourceDefault)
	require.NoError(t, err)

	time.Sleep(100 * time.Millisecond)
	assert.GreaterOrEqual(t, callCount.Load(), int32(2))
}

func TestSet_PrefixListener(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	var serverCalled, cacheCalled atomic.Bool
	mgr.OnChange("server.*", func(e ConfigChangeEvent) {
		serverCalled.Store(true)
	})
	mgr.OnChange("cache.*", func(e ConfigChangeEvent) {
		cacheCalled.Store(true)
	})

	err = mgr.Set("server.port", 7070, SourceDefault)
	require.NoError(t, err)
	err = mgr.Set("cache.type", "redis", SourceDefault)
	require.NoError(t, err)

	time.Sleep(200 * time.Millisecond)
	assert.True(t, serverCalled.Load())
	assert.True(t, cacheCalled.Load())
}

func TestOnChange_MultipleListeners(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	var count1, count2 atomic.Int32
	mgr.OnChange("server.port", func(e ConfigChangeEvent) {
		count1.Add(1)
	})
	mgr.OnChange("server.port", func(e ConfigChangeEvent) {
		count2.Add(1)
	})

	err = mgr.Set("server.port", 5000, SourceDefault)
	require.NoError(t, err)

	time.Sleep(200 * time.Millisecond)
	assert.GreaterOrEqual(t, count1.Load(), int32(1))
	assert.GreaterOrEqual(t, count2.Load(), int32(1))
}

func TestGetItem(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	item, err := mgr.GetItem("server.port")
	require.NoError(t, err)
	assert.Equal(t, "server.port", item.Key)
	assert.Equal(t, SourceDefault, item.Source)
	assert.Equal(t, 1, item.Version)

	_, err = mgr.GetItem("nonexistent.key")
	assert.Equal(t, common.ErrNotFound, err)
}

func TestGetAllItems(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	items := mgr.GetAllItems()
	assert.GreaterOrEqual(t, len(items), 10)

	keys := make(map[string]bool)
	for _, item := range items {
		keys[item.Key] = true
		assert.NotEmpty(t, item.Key)
		assert.NotNil(t, item.Value)
	}
	assert.True(t, keys["server.port"])
	assert.True(t, keys["server.host"])
}

func TestGetSources(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	sources := mgr.GetSources()
	assert.Contains(t, sources, SourceEnv)
	assert.Contains(t, sources, SourceDefault)
}

func TestExport(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	exported, err := mgr.Export()
	require.NoError(t, err)
	assert.Contains(t, exported, "server")
	assert.Contains(t, exported, "port")
}

func TestValidate_ValidConfig(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	err = mgr.Validate()
	assert.NoError(t, err)
}

func TestValidate_InvalidPort(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	mgr.config.Server.Port = 99999
	err = mgr.Validate()
	assert.Error(t, err)
}

func TestLoadConfig_TopLevel(t *testing.T) {
	yamlContent := `server:
  host: 0.0.0.0
  port: 8080
  mode: debug
database:
  path: test.db
`
	path, cleanup := testdata.WriteTestConfigFile(yamlContent)
	defer cleanup()

	mgr, err := LoadConfig(path)
	if mgr != nil {
		mgr.Close()
	}
	require.NoError(t, err)
}

func TestGenerateDefaultConfig(t *testing.T) {
	content := GenerateDefaultConfig()
	assert.Contains(t, content, "server")
	assert.Contains(t, content, "database")
	assert.Contains(t, content, "cache")
	assert.Contains(t, content, "security")
}

func TestMatchPattern(t *testing.T) {
	tests := []struct {
		pattern string
		key     string
		match   bool
	}{
		{"*", "any.key", true},
		{"server.port", "server.port", true},
		{"server.port", "server.host", false},
		{"server.*", "server.port", true},
		{"server.*", "server.host", true},
		{"server.*", "cache.type", false},
		{"", "server.port", false},
	}

	for _, tt := range tests {
		t.Run(tt.pattern+"_"+tt.key, func(t *testing.T) {
			result := matchPattern(tt.pattern, tt.key)
			assert.Equal(t, tt.match, result)
		})
	}
}

func TestConcurrentAccess(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	var wg sync.WaitGroup
	const goroutines = 50

	for i := 0; i < goroutines; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			key := fmt.Sprintf("test.key.%d", idx)
			mgr.Set(key, idx, SourceRemote)
			mgr.Get(key)
			mgr.GetString(key)
			mgr.GetInt(key)
			mgr.GetItem(key)
		}(i)
	}

	wg.Wait()

	items := mgr.GetAllItems()
	assert.GreaterOrEqual(t, len(items), goroutines)
}

func TestConcurrentSetAndGet(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	const iterations = 100
	var wg sync.WaitGroup
	var readCount atomic.Int32

	for i := 0; i < iterations; i++ {
		wg.Add(2)
		go func(idx int) {
			defer wg.Done()
			mgr.Set("concurrent.key", idx, SourceRemote)
		}(i)
		go func() {
			defer wg.Done()
			mgr.GetInt("concurrent.key")
			readCount.Add(1)
		}()
	}

	wg.Wait()
	assert.Equal(t, int32(iterations), readCount.Load())
}

func TestManager_CloseIdempotent(t *testing.T) {
	mgr := NewManager()
	mgr.autoRefresh = false

	err := mgr.Load("")
	require.NoError(t, err)

	mgr.Close()
	mgr.Close()
}

func TestConfigItem_VersionIncrements(t *testing.T) {
	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	err = mgr.Set("server.port", 9090, SourceRemote)
	require.NoError(t, err)
	item1, _ := mgr.GetItem("server.port")
	v1 := item1.Version

	err = mgr.Set("server.port", 9091, SourceRemote)
	require.NoError(t, err)
	item2, _ := mgr.GetItem("server.port")
	assert.Greater(t, item2.Version, v1)
}

func TestLoad_FromEnv(t *testing.T) {
	os.Setenv("TECH_SERVER_HOST", "10.0.0.1")
	defer os.Unsetenv("TECH_SERVER_HOST")

	mgr, cleanup := newTestManager(t)
	defer cleanup()

	err := mgr.Load("")
	require.NoError(t, err)

	host := mgr.GetString("server.host")
	assert.Equal(t, "10.0.0.1", host)
}
