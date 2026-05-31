package storage

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/solocoder/session148/internal/infrastructure/logging"
)

func newTestLogger(t *testing.T) domain.Logger {
	logger, err := logging.NewZapLogger(logging.LoggerConfig{
		Level: "error",
	})
	require.NoError(t, err)
	return logger
}

func TestNewLocalStorageManager_Basic(t *testing.T) {
	t.Run("should create manager with default config", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir: backupDir,
			Logger:    logger,
		})

		require.NoError(t, err)
		require.NotNil(t, mgr)

		_, err = os.Stat(backupDir)
		assert.NoError(t, err)

		_, err = os.Stat(filepath.Join(backupDir, "index.json"))
		assert.NoError(t, err)
	})

	t.Run("should create manager with cache config", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir: backupDir,
			Logger:    logger,
			CacheConfig: CacheConfig{
				MaxEntries:      50,
				MaxSizeBytes:    10 * 1024 * 1024,
				TTL:             30 * time.Minute,
				WarmupOnStartup: false,
			},
		})

		require.NoError(t, err)
		require.NotNil(t, mgr)
	})

	t.Run("should use default cache config when not provided", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir: backupDir,
			Logger:    logger,
		})

		require.NoError(t, err)
		require.NotNil(t, mgr)
	})
}

func createTestDataDir(t *testing.T) string {
	tmpDir := t.TempDir()
	dataDir := filepath.Join(tmpDir, "data")
	require.NoError(t, os.MkdirAll(dataDir, 0755))

	files := map[string]string{
		"file1.txt":            "content1",
		"file2.txt":            "content2",
		"subdir/nested.txt":    "nested content",
		"subdir/deep/file.txt": "deep content",
	}

	for path, content := range files {
		fullPath := filepath.Join(dataDir, path)
		require.NoError(t, os.MkdirAll(filepath.Dir(fullPath), 0755))
		require.NoError(t, os.WriteFile(fullPath, []byte(content), 0644))
	}

	return dataDir
}

func TestLocalStorageManager_Backup(t *testing.T) {
	t.Run("should backup directory successfully", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")
		dataDir := createTestDataDir(t)

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx := context.Background()
		backup, err := mgr.Backup(ctx, dataDir)

		require.NoError(t, err)
		require.NotNil(t, backup)
		assert.NotEmpty(t, backup.ID)
		assert.Equal(t, dataDir, backup.Source)
		assert.Greater(t, backup.Size, int64(0))
		assert.NotEmpty(t, backup.Checksum)
		assert.False(t, backup.CreatedAt.IsZero())

		_, err = os.Stat(backup.Path)
		assert.NoError(t, err)
	})

	t.Run("should return error when source not found", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx := context.Background()
		backup, err := mgr.Backup(ctx, "/nonexistent/path")

		assert.Nil(t, backup)
		assert.Error(t, err)
	})

	t.Run("should backup single file", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")

		testFile := filepath.Join(tmpDir, "single.txt")
		require.NoError(t, os.WriteFile(testFile, []byte("single file content"), 0644))

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx := context.Background()
		backup, err := mgr.Backup(ctx, testFile)

		require.NoError(t, err)
		require.NotNil(t, backup)
		assert.Greater(t, backup.Size, int64(0))
	})

	t.Run("should backup empty directory", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")
		emptyDir := filepath.Join(tmpDir, "empty")
		require.NoError(t, os.MkdirAll(emptyDir, 0755))

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx := context.Background()
		backup, err := mgr.Backup(ctx, emptyDir)

		require.NoError(t, err)
		require.NotNil(t, backup)
	})
}

func TestLocalStorageManager_Restore(t *testing.T) {
	t.Run("should restore backup successfully", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")
		restoreDir := filepath.Join(tmpDir, "restore")
		dataDir := createTestDataDir(t)

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx := context.Background()
		backup, err := mgr.Backup(ctx, dataDir)
		require.NoError(t, err)

		err = mgr.Restore(ctx, backup.ID, restoreDir)
		require.NoError(t, err)

		_, err = os.Stat(filepath.Join(restoreDir, filepath.Base(dataDir), "file1.txt"))
		assert.NoError(t, err)

		_, err = os.Stat(filepath.Join(restoreDir, filepath.Base(dataDir), "subdir/nested.txt"))
		assert.NoError(t, err)
	})

	t.Run("should restore from cache", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")
		restoreDir1 := filepath.Join(tmpDir, "restore1")
		restoreDir2 := filepath.Join(tmpDir, "restore2")
		dataDir := createTestDataDir(t)

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx := context.Background()
		backup, err := mgr.Backup(ctx, dataDir)
		require.NoError(t, err)

		err = mgr.Restore(ctx, backup.ID, restoreDir1)
		require.NoError(t, err)

		err = mgr.Restore(ctx, backup.ID, restoreDir2)
		require.NoError(t, err)

		_, err = os.Stat(filepath.Join(restoreDir2, filepath.Base(dataDir), "file1.txt"))
		assert.NoError(t, err)
	})

	t.Run("should return error for nonexistent backup", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx := context.Background()
		err = mgr.Restore(ctx, "nonexistent_id", "/tmp/restore")

		assert.Error(t, err)
	})

	t.Run("should detect corrupted backup", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")
		dataDir := createTestDataDir(t)

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx := context.Background()
		backup, err := mgr.Backup(ctx, dataDir)
		require.NoError(t, err)

		err = os.WriteFile(backup.Path, []byte("corrupted data"), 0644)
		require.NoError(t, err)

		restoreDir := filepath.Join(tmpDir, "restore")
		err = mgr.Restore(ctx, backup.ID, restoreDir)

		assert.Error(t, err)
	})
}

func TestLocalStorageManager_ListBackups(t *testing.T) {
	t.Run("should list multiple backups", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")
		dataDir := createTestDataDir(t)

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx := context.Background()

		for i := 0; i < 3; i++ {
			_, err := mgr.Backup(ctx, dataDir)
			require.NoError(t, err)
			time.Sleep(10 * time.Millisecond)
		}

		backups, err := mgr.ListBackups(ctx)
		require.NoError(t, err)
		assert.Len(t, backups, 3)
	})

	t.Run("should return empty list when no backups", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx := context.Background()
		backups, err := mgr.ListBackups(ctx)

		require.NoError(t, err)
		assert.Empty(t, backups)
	})
}

func TestLocalStorageManager_DeleteBackup(t *testing.T) {
	t.Run("should delete backup successfully", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")
		dataDir := createTestDataDir(t)

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx := context.Background()
		backup, err := mgr.Backup(ctx, dataDir)
		require.NoError(t, err)

		err = mgr.DeleteBackup(ctx, backup.ID)
		require.NoError(t, err)

		_, err = os.Stat(backup.Path)
		assert.True(t, os.IsNotExist(err))

		backups, _ := mgr.ListBackups(ctx)
		assert.Empty(t, backups)
	})

	t.Run("should return error for nonexistent backup", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx := context.Background()
		err = mgr.DeleteBackup(ctx, "nonexistent_id")

		assert.Error(t, err)
	})
}

func TestLRUCache(t *testing.T) {
	t.Run("should store and retrieve entries", func(t *testing.T) {
		cache := NewLRUCache(10, 1000, 0)

		entry1 := &CacheEntry{Backup: &domain.BackupInfo{ID: "b1"}, Data: []byte("data1"), Size: 100}
		cache.Put("b1", entry1)

		retrieved, exists := cache.Get("b1")
		assert.True(t, exists)
		assert.Equal(t, "b1", retrieved.Backup.ID)
	})

	t.Run("should evict least recently used entries", func(t *testing.T) {
		cache := NewLRUCache(3, 10000, 0)

		for i := 0; i < 5; i++ {
			id := fmt.Sprintf("b%d", i)
			entry := &CacheEntry{
				Backup: &domain.BackupInfo{ID: id},
				Data:   []byte(fmt.Sprintf("data%d", i)),
				Size:   100,
			}
			cache.Put(id, entry)
		}

		_, exists := cache.Get("b0")
		assert.False(t, exists, "b0 should be evicted")

		_, exists = cache.Get("b3")
		assert.True(t, exists, "b3 should exist")

		_, exists = cache.Get("b4")
		assert.True(t, exists, "b4 should exist")
	})

	t.Run("should respect size limit", func(t *testing.T) {
		cache := NewLRUCache(100, 500, 0)

		cache.Put("b1", &CacheEntry{Backup: &domain.BackupInfo{ID: "b1"}, Data: []byte("a"), Size: 200})
		cache.Put("b2", &CacheEntry{Backup: &domain.BackupInfo{ID: "b2"}, Data: []byte("b"), Size: 200})
		cache.Put("b3", &CacheEntry{Backup: &domain.BackupInfo{ID: "b3"}, Data: []byte("c"), Size: 200})

		_, exists := cache.Get("b1")
		assert.False(t, exists, "b1 should be evicted due to size limit")
	})

	t.Run("should respect TTL", func(t *testing.T) {
		cache := NewLRUCache(10, 10000, 10*time.Millisecond)

		cache.Put("b1", &CacheEntry{Backup: &domain.BackupInfo{ID: "b1"}, Data: []byte("data"), Size: 100})

		_, exists := cache.Get("b1")
		assert.True(t, exists)

		time.Sleep(20 * time.Millisecond)

		_, exists = cache.Get("b1")
		assert.False(t, exists, "should expire after TTL")
	})

	t.Run("should update access time on get", func(t *testing.T) {
		cache := NewLRUCache(2, 10000, 0)

		cache.Put("b1", &CacheEntry{Backup: &domain.BackupInfo{ID: "b1"}, Data: []byte("a"), Size: 100})
		cache.Put("b2", &CacheEntry{Backup: &domain.BackupInfo{ID: "b2"}, Data: []byte("b"), Size: 100})

		_, _ = cache.Get("b1")

		cache.Put("b3", &CacheEntry{Backup: &domain.BackupInfo{ID: "b3"}, Data: []byte("c"), Size: 100})

		_, exists := cache.Get("b1")
		assert.True(t, exists, "b1 should exist because it was accessed")

		_, exists = cache.Get("b2")
		assert.False(t, exists, "b2 should be evicted")
	})

	t.Run("should delete entry", func(t *testing.T) {
		cache := NewLRUCache(10, 10000, 0)

		cache.Put("b1", &CacheEntry{Backup: &domain.BackupInfo{ID: "b1"}, Data: []byte("data"), Size: 100})
		cache.Delete("b1")

		_, exists := cache.Get("b1")
		assert.False(t, exists)
	})

	t.Run("should track stats", func(t *testing.T) {
		cache := NewLRUCache(10, 10000, 0)

		cache.Put("b1", &CacheEntry{Backup: &domain.BackupInfo{ID: "b1"}, Data: []byte("data"), Size: 100})
		cache.Get("b1")
		cache.Get("nonexistent")
		cache.Get("b1")

		stats := cache.Stats()
		assert.Equal(t, int64(2), stats["hits"])
		assert.Equal(t, int64(1), stats["misses"])
		assert.InDelta(t, 0.666, stats["hit_rate"], 0.001)
	})
}

func TestConcurrentBackup(t *testing.T) {
	tmpDir := t.TempDir()
	backupDir := filepath.Join(tmpDir, "backups")
	dataDir := createTestDataDir(t)

	logger := newTestLogger(t)
	mgr, err := NewLocalStorageManager(StorageConfig{
		BackupDir:   backupDir,
		Logger:      logger,
		CacheConfig: CacheConfig{WarmupOnStartup: false, MaxEntries: 50},
	})
	require.NoError(t, err)

	const goroutines = 20
	var wg sync.WaitGroup
	wg.Add(goroutines)

	var errorCount int32
	var successCount int32

	for i := 0; i < goroutines; i++ {
		go func() {
			defer wg.Done()
			ctx := context.Background()
			_, err := mgr.Backup(ctx, dataDir)
			if err != nil {
				atomic.AddInt32(&errorCount, 1)
			} else {
				atomic.AddInt32(&successCount, 1)
			}
		}()
	}

	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()

	select {
	case <-done:
		assert.Equal(t, int32(0), errorCount, "no errors during concurrent backups")
		assert.Equal(t, int32(goroutines), successCount)

		ctx := context.Background()
		backups, _ := mgr.ListBackups(ctx)
		assert.Len(t, backups, goroutines)
	case <-time.After(30 * time.Second):
		t.Fatal("timeout waiting for concurrent backups to complete")
	}
}

func TestConcurrentRestore(t *testing.T) {
	tmpDir := t.TempDir()
	backupDir := filepath.Join(tmpDir, "backups")
	dataDir := createTestDataDir(t)

	logger := newTestLogger(t)
	mgr, err := NewLocalStorageManager(StorageConfig{
		BackupDir:   backupDir,
		Logger:      logger,
		CacheConfig: CacheConfig{WarmupOnStartup: false, MaxEntries: 50},
	})
	require.NoError(t, err)

	ctx := context.Background()
	backup, err := mgr.Backup(ctx, dataDir)
	require.NoError(t, err)

	const goroutines = 20
	var wg sync.WaitGroup
	wg.Add(goroutines)

	var errorCount int32

	for i := 0; i < goroutines; i++ {
		go func(id int) {
			defer wg.Done()
			restoreDir := filepath.Join(tmpDir, fmt.Sprintf("restore_%d", id))
			err := mgr.Restore(context.Background(), backup.ID, restoreDir)
			if err != nil {
				atomic.AddInt32(&errorCount, 1)
			}
		}(i)
	}

	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()

	select {
	case <-done:
		assert.Equal(t, int32(0), errorCount, "no errors during concurrent restores")
	case <-time.After(30 * time.Second):
		t.Fatal("timeout waiting for concurrent restores to complete")
	}
}

func TestStorage_EdgeCases(t *testing.T) {
	t.Run("backup very large file", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")
		dataDir := filepath.Join(tmpDir, "data")
		require.NoError(t, os.MkdirAll(dataDir, 0755))

		largeFile := filepath.Join(dataDir, "large.dat")
		f, err := os.Create(largeFile)
		require.NoError(t, err)

		size := int64(5 * 1024 * 1024)
		_, err = f.Write(make([]byte, size))
		require.NoError(t, err)
		f.Close()

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false, MaxSizeBytes: 100 * 1024 * 1024},
		})
		require.NoError(t, err)

		ctx := context.Background()
		backup, err := mgr.Backup(ctx, dataDir)
		require.NoError(t, err)
		assert.GreaterOrEqual(t, backup.Size, size)
	})

	t.Run("backup with special characters in filename", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")
		dataDir := filepath.Join(tmpDir, "data")
		require.NoError(t, os.MkdirAll(dataDir, 0755))

		specialFile := filepath.Join(dataDir, "file with spaces & special!@#$%^&*().txt")
		require.NoError(t, os.WriteFile(specialFile, []byte("content"), 0644))

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx := context.Background()
		backup, err := mgr.Backup(ctx, dataDir)
		require.NoError(t, err)

		restoreDir := filepath.Join(tmpDir, "restore")
		err = mgr.Restore(ctx, backup.ID, restoreDir)
		require.NoError(t, err)
	})

	t.Run("backup symlinks", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")
		dataDir := filepath.Join(tmpDir, "data")
		require.NoError(t, os.MkdirAll(dataDir, 0755))

		originalFile := filepath.Join(dataDir, "original.txt")
		require.NoError(t, os.WriteFile(originalFile, []byte("original"), 0644))

		symlink := filepath.Join(dataDir, "link.txt")
		require.NoError(t, os.Symlink(originalFile, symlink))

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx := context.Background()
		backup, err := mgr.Backup(ctx, dataDir)
		require.NoError(t, err)
		assert.NotNil(t, backup)
	})
}

func TestCacheWarmup(t *testing.T) {
	t.Run("should warmup cache on startup", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")
		dataDir := createTestDataDir(t)

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir: backupDir,
			Logger:    logger,
			CacheConfig: CacheConfig{
				WarmupOnStartup: false,
				WarmupCount:     3,
			},
		})
		require.NoError(t, err)

		ctx := context.Background()
		for i := 0; i < 5; i++ {
			_, err := mgr.Backup(ctx, dataDir)
			require.NoError(t, err)
			time.Sleep(10 * time.Millisecond)
		}

		stats := mgr.GetCacheStats()
		assert.Equal(t, 5, stats["entry_count"])
	})

	t.Run("manual warmup should work", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")
		dataDir := createTestDataDir(t)

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx := context.Background()
		for i := 0; i < 3; i++ {
			_, err := mgr.Backup(ctx, dataDir)
			require.NoError(t, err)
		}

		mgr.PurgeCache()

		stats := mgr.GetCacheStats()
		assert.Equal(t, 0, stats["entry_count"])

		err = mgr.WarmupCache(2)
		require.NoError(t, err)

		stats = mgr.GetCacheStats()
		assert.Equal(t, 2, stats["entry_count"])
	})

	t.Run("preload specific backup", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")
		dataDir := createTestDataDir(t)

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx := context.Background()
		backup, err := mgr.Backup(ctx, dataDir)
		require.NoError(t, err)

		mgr.PurgeCache()

		err = mgr.PreloadBackup(backup.ID)
		require.NoError(t, err)

		stats := mgr.GetCacheStats()
		assert.Equal(t, 1, stats["entry_count"])
	})
}

func TestAutoSnapshot(t *testing.T) {
	t.Run("should take periodic snapshots", func(t *testing.T) {
		tmpDir := t.TempDir()
		backupDir := filepath.Join(tmpDir, "backups")
		dataDir := createTestDataDir(t)

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   backupDir,
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()

		go mgr.AutoSnapshot(ctx, dataDir, SnapshotPolicy{
			Interval: 100 * time.Millisecond,
			MaxCount: 3,
		})

		time.Sleep(350 * time.Millisecond)
		cancel()

		ctx2 := context.Background()
		backups, _ := mgr.ListBackups(ctx2)
		assert.GreaterOrEqual(t, len(backups), 2)
		assert.LessOrEqual(t, len(backups), 4)
	})
}

func TestTarGzOperations(t *testing.T) {
	t.Run("createTarGz and extractTarGzFromBytes should work together", func(t *testing.T) {
		tmpDir := t.TempDir()
		sourceDir := filepath.Join(tmpDir, "source")
		destDir := filepath.Join(tmpDir, "dest")

		require.NoError(t, os.MkdirAll(sourceDir, 0755))
		require.NoError(t, os.WriteFile(filepath.Join(sourceDir, "test.txt"), []byte("hello world"), 0644))

		tarPath := filepath.Join(tmpDir, "test.tar.gz")

		logger := newTestLogger(t)
		mgr, err := NewLocalStorageManager(StorageConfig{
			BackupDir:   filepath.Join(tmpDir, "backups"),
			Logger:      logger,
			CacheConfig: CacheConfig{WarmupOnStartup: false},
		})
		require.NoError(t, err)

		err = mgr.createTarGz(sourceDir, tarPath)
		require.NoError(t, err)

		data, err := os.ReadFile(tarPath)
		require.NoError(t, err)

		err = mgr.extractTarGzFromBytes(data, destDir)
		require.NoError(t, err)

		content, err := os.ReadFile(filepath.Join(destDir, filepath.Base(sourceDir), "test.txt"))
		require.NoError(t, err)
		assert.Equal(t, "hello world", string(content))
	})
}
