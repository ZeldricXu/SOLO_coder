package logger

import (
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

func TestMain(m *testing.M) {
	code := m.Run()
	os.Exit(code)
}

func createTempLogFile(t *testing.T) string {
	t.Helper()
	tmpDir := t.TempDir()
	return filepath.Join(tmpDir, "test.log")
}

func assertFileExists(t *testing.T, path string) {
	t.Helper()
	_, err := os.Stat(path)
	assert.NoError(t, err, "file should exist: %s", path)
}

func assertFileNotExists(t *testing.T, path string) {
	t.Helper()
	_, err := os.Stat(path)
	assert.True(t, os.IsNotExist(err), "file should not exist: %s", path)
}

func TestNewLogger_WithValidConfig(t *testing.T) {
	t.Run("console only", func(t *testing.T) {
		cfg := Config{
			Level:         "info",
			EnableConsole: true,
			EnableFile:    false,
		}

		logger, err := New(cfg)
		require.NoError(t, err)
		require.NotNil(t, logger)

		logger.Info("test message")
		assert.NoError(t, logger.Close())
	})

	t.Run("file only", func(t *testing.T) {
		logPath := createTempLogFile(t)
		cfg := Config{
			LogPath:       logPath,
			MaxSize:       1,
			MaxBackups:    3,
			MaxAge:        1,
			Compress:      false,
			Level:         "debug",
			EnableConsole: false,
			EnableFile:    true,
		}

		logger, err := New(cfg)
		require.NoError(t, err)
		require.NotNil(t, logger)

		logger.Info("test file logging")
		assert.NoError(t, logger.Sync())
		assert.NoError(t, logger.Close())

		assertFileExists(t, logPath)
	})

	t.Run("both console and file", func(t *testing.T) {
		logPath := createTempLogFile(t)
		cfg := Config{
			LogPath:       logPath,
			MaxSize:       1,
			MaxBackups:    3,
			MaxAge:        1,
			Compress:      false,
			Level:         "info",
			EnableConsole: true,
			EnableFile:    true,
		}

		logger, err := New(cfg)
		require.NoError(t, err)
		require.NotNil(t, logger)
		assert.NoError(t, logger.Close())
	})
}

func TestNewLogger_WithInvalidConfig(t *testing.T) {
	t.Run("invalid log level defaults to info", func(t *testing.T) {
		cfg := Config{
			Level:         "invalid_level",
			EnableConsole: true,
			EnableFile:    false,
		}

		logger, err := New(cfg)
		require.NoError(t, err)
		require.NotNil(t, logger)
		assert.NoError(t, logger.Close())
	})

	t.Run("empty log path with file enabled", func(t *testing.T) {
		cfg := Config{
			Level:         "info",
			EnableConsole: false,
			EnableFile:    true,
			LogPath:       "",
		}

		logger, err := New(cfg)
		require.NoError(t, err)
		require.NotNil(t, logger)
		assert.NoError(t, logger.Close())
	})
}

func TestNewLogger_CreatesDirectories(t *testing.T) {
	tmpDir := t.TempDir()
	nestedPath := filepath.Join(tmpDir, "nested", "dir", "test.log")

	cfg := Config{
		LogPath:       nestedPath,
		MaxSize:       1,
		MaxBackups:    3,
		MaxAge:        1,
		Compress:      true,
		Level:         "info",
		EnableConsole: false,
		EnableFile:    true,
	}

	logger, err := New(cfg)
	require.NoError(t, err)
	require.NotNil(t, logger)

	assertFileExists(t, filepath.Dir(nestedPath))
	assertFileExists(t, filepath.Join(filepath.Dir(nestedPath), "archive"))

	assert.NoError(t, logger.Close())
}

func TestLogger_LogLevels(t *testing.T) {
	logPath := createTempLogFile(t)
	cfg := Config{
		LogPath:       logPath,
		MaxSize:       10,
		MaxBackups:    3,
		MaxAge:        1,
		Compress:      false,
		Level:         "debug",
		EnableConsole: false,
		EnableFile:    true,
	}

	logger, err := New(cfg)
	require.NoError(t, err)
	require.NotNil(t, logger)
	defer logger.Close()

	t.Run("debug level", func(t *testing.T) {
		logger.Debug("debug message", zap.String("key", "value"))
	})

	t.Run("info level", func(t *testing.T) {
		logger.Info("info message", zap.Int("count", 42))
	})

	t.Run("warn level", func(t *testing.T) {
		logger.Warn("warn message", zap.Error(assert.AnError))
	})

	t.Run("error level", func(t *testing.T) {
		logger.Error("error message", zap.Bool("fatal", false))
	})

	t.Run("formatted methods", func(t *testing.T) {
		logger.Debugf("debug %s", "formatted")
		logger.Infof("info %d", 123)
		logger.Warnf("warn %v", true)
		logger.Errorf("error %s", "test")
	})

	assert.NoError(t, logger.Sync())
}

func TestLogger_LevelFiltering(t *testing.T) {
	logPath := createTempLogFile(t)
	cfg := Config{
		LogPath:       logPath,
		MaxSize:       10,
		MaxBackups:    3,
		MaxAge:        1,
		Compress:      false,
		Level:         "warn",
		EnableConsole: false,
		EnableFile:    true,
	}

	logger, err := New(cfg)
	require.NoError(t, err)
	require.NotNil(t, logger)
	defer logger.Close()

	logger.Debug("this should not appear")
	logger.Info("this should not appear")
	logger.Warn("this should appear")
	logger.Error("this should appear")

	assert.NoError(t, logger.Sync())

	content, err := os.ReadFile(logPath)
	require.NoError(t, err)
	logContent := string(content)

	assert.False(t, strings.Contains(logContent, "this should not appear"),
		"debug and info messages should be filtered out")
	assert.True(t, strings.Contains(logContent, "this should appear"),
		"warn and error messages should be present")
}

func TestLogger_WithFields(t *testing.T) {
	logPath := createTempLogFile(t)
	cfg := Config{
		LogPath:       logPath,
		MaxSize:       10,
		MaxBackups:    3,
		MaxAge:        1,
		Compress:      false,
		Level:         "info",
		EnableConsole: false,
		EnableFile:    true,
	}

	logger, err := New(cfg)
	require.NoError(t, err)
	require.NotNil(t, logger)
	defer logger.Close()

	childLogger := logger.With(
		zap.String("service", "test-service"),
		zap.String("version", "v1.0.0"),
	)

	childLogger.Info("message with context")

	assert.NoError(t, logger.Sync())

	content, err := os.ReadFile(logPath)
	require.NoError(t, err)
	logContent := string(content)

	assert.True(t, strings.Contains(logContent, "service"))
	assert.True(t, strings.Contains(logContent, "test-service"))
	assert.True(t, strings.Contains(logContent, "version"))
	assert.True(t, strings.Contains(logContent, "v1.0.0"))
}

func TestLogger_Rotate(t *testing.T) {
	logPath := createTempLogFile(t)
	cfg := Config{
		LogPath:       logPath,
		MaxSize:       1,
		MaxBackups:    3,
		MaxAge:        1,
		Compress:      false,
		Level:         "info",
		EnableConsole: false,
		EnableFile:    true,
	}

	logger, err := New(cfg)
	require.NoError(t, err)
	require.NotNil(t, logger)
	defer logger.Close()

	for i := 0; i < 1000; i++ {
		logger.Info("rotating log message", zap.Int("iteration", i))
	}

	err = logger.Rotate()
	assert.NoError(t, err)

	logger.Info("after rotation")
	assert.NoError(t, logger.Sync())

	logDir := filepath.Dir(logPath)
	files, err := os.ReadDir(logDir)
	require.NoError(t, err)

	rotatedFiles := 0
	for _, f := range files {
		if !f.IsDir() && strings.HasPrefix(f.Name(), filepath.Base(logPath)) {
			rotatedFiles++
		}
	}

	assert.GreaterOrEqual(t, rotatedFiles, 2, "should have at least 2 log files (current + rotated)")
}

func TestLogger_ConcurrentLogging(t *testing.T) {
	logPath := createTempLogFile(t)
	cfg := Config{
		LogPath:       logPath,
		MaxSize:       100,
		MaxBackups:    5,
		MaxAge:        1,
		Compress:      false,
		Level:         "info",
		EnableConsole: false,
		EnableFile:    true,
	}

	logger, err := New(cfg)
	require.NoError(t, err)
	require.NotNil(t, logger)
	defer logger.Close()

	numGoroutines := 100
	numMessages := 100

	var wg sync.WaitGroup
	wg.Add(numGoroutines)

	for i := 0; i < numGoroutines; i++ {
		go func(goroutineID int) {
			defer wg.Done()
			for j := 0; j < numMessages; j++ {
				logger.Info("concurrent message",
					zap.Int("goroutine", goroutineID),
					zap.Int("message", j),
				)
			}
		}(i)
	}

	wg.Wait()
	assert.NoError(t, logger.Sync())

	content, err := os.ReadFile(logPath)
	require.NoError(t, err)

	lineCount := strings.Count(string(content), "\n")
	expectedLines := numGoroutines * numMessages
	assert.GreaterOrEqual(t, lineCount, expectedLines-10,
		"should have approximately %d lines, got %d", expectedLines, lineCount)
}

func TestLogger_ConcurrentWriteAndRotate(t *testing.T) {
	logPath := createTempLogFile(t)
	cfg := Config{
		LogPath:       logPath,
		MaxSize:       1,
		MaxBackups:    5,
		MaxAge:        1,
		Compress:      false,
		Level:         "info",
		EnableConsole: false,
		EnableFile:    true,
	}

	logger, err := New(cfg)
	require.NoError(t, err)
	require.NotNil(t, logger)
	defer logger.Close()

	numWriters := 50
	numRotations := 10

	var wg sync.WaitGroup
	wg.Add(numWriters + 1)

	for i := 0; i < numWriters; i++ {
		go func(goroutineID int) {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				logger.Info("stress test message",
					zap.Int("writer", goroutineID),
					zap.Int("seq", j),
				)
			}
		}(i)
	}

	go func() {
		defer wg.Done()
		for i := 0; i < numRotations; i++ {
			time.Sleep(10 * time.Millisecond)
			_ = logger.Rotate()
		}
	}()

	wg.Wait()
	assert.NoError(t, logger.Sync())

	logDir := filepath.Dir(logPath)
	files, err := os.ReadDir(logDir)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(files), 2, "should have multiple log files after rotation")
}

func TestLogger_CloseMultipleTimes(t *testing.T) {
	cfg := Config{
		Level:         "info",
		EnableConsole: true,
		EnableFile:    false,
	}

	logger, err := New(cfg)
	require.NoError(t, err)
	require.NotNil(t, logger)

	assert.NoError(t, logger.Close())
	assert.Panics(t, func() {
		_ = logger.Close()
	}, "closing twice should panic (closing closed channel)")
}

func TestLogger_Sync(t *testing.T) {
	logPath := createTempLogFile(t)
	cfg := Config{
		LogPath:       logPath,
		MaxSize:       10,
		MaxBackups:    3,
		MaxAge:        1,
		Compress:      false,
		Level:         "info",
		EnableConsole: false,
		EnableFile:    true,
	}

	logger, err := New(cfg)
	require.NoError(t, err)
	require.NotNil(t, logger)

	logger.Info("before sync")
	assert.NoError(t, logger.Sync())

	info, err := os.Stat(logPath)
	require.NoError(t, err)
	assert.Greater(t, info.Size(), int64(0), "file should have content after sync")

	assert.NoError(t, logger.Close())
}

func TestLogger_ArchiveOldLogs(t *testing.T) {
	logPath := createTempLogFile(t)
	cfg := Config{
		LogPath:       logPath,
		MaxSize:       1,
		MaxBackups:    10,
		MaxAge:        0,
		Compress:      true,
		Level:         "info",
		EnableConsole: false,
		EnableFile:    true,
	}

	logger, err := New(cfg)
	require.NoError(t, err)
	require.NotNil(t, logger)
	defer logger.Close()

	for i := 0; i < 100; i++ {
		logger.Info("archive test message", zap.Int("i", i))
	}

	err = logger.Rotate()
	assert.NoError(t, err)

	logger.Info("after rotation")
	assert.NoError(t, logger.Sync())

	logDir := filepath.Dir(logPath)
	archiveDir := filepath.Join(logDir, "archive")

	_, err = os.Stat(archiveDir)
	assert.NoError(t, err, "archive directory should exist")

	archiveFiles, err := os.ReadDir(archiveDir)
	require.NoError(t, err)
	t.Logf("archive files: %d", len(archiveFiles))
}

func TestLogger_CompressFile(t *testing.T) {
	tmpDir := t.TempDir()
	srcFile := filepath.Join(tmpDir, "test.log")
	archiveDir := filepath.Join(tmpDir, "archive")
	require.NoError(t, os.MkdirAll(archiveDir, 0755))

	content := strings.Repeat("test log content\n", 1000)
	require.NoError(t, os.WriteFile(srcFile, []byte(content), 0644))

	cfg := Config{
		LogPath:  srcFile,
		Compress: true,
	}

	logger := &Manager{
		config:      cfg,
		archivePath: archiveDir,
	}

	err := logger.compressFile(srcFile)
	require.NoError(t, err)

	compressedPath := filepath.Join(archiveDir, "test.log.gz")
	_, err = os.Stat(compressedPath)
	assert.NoError(t, err, "compressed file should exist")

	_, err = os.Stat(srcFile)
	assert.True(t, os.IsNotExist(err), "source file should be deleted")

	compressedInfo, err := os.Stat(compressedPath)
	require.NoError(t, err)
	assert.Less(t, compressedInfo.Size(), int64(len(content)),
		"compressed file should be smaller than original")
}

func TestLogger_GlobalLogger(t *testing.T) {
	t.Run("before init", func(t *testing.T) {
		assert.NotPanics(t, func() {
			Info("test before init")
			Debug("test before init")
			Warn("test before init")
			Error("test before init")
		})
	})

	t.Run("after init", func(t *testing.T) {
		logPath := createTempLogFile(t)
		cfg := Config{
			LogPath:       logPath,
			MaxSize:       10,
			MaxBackups:    3,
			MaxAge:        1,
			Compress:      false,
			Level:         "info",
			EnableConsole: false,
			EnableFile:    true,
		}

		err := InitGlobal(cfg)
		require.NoError(t, err)

		Info("global info message")
		Error("global error message")
		Infof("global %s message", "formatted")
		Errorf("global %s message", "formatted")

		assert.NoError(t, Sync())

		content, err := os.ReadFile(logPath)
		require.NoError(t, err)
		assert.True(t, strings.Contains(string(content), "global info message"))
		assert.True(t, strings.Contains(string(content), "global error message"))

		assert.NoError(t, Close())
	})
}

func TestLogger_EdgeCases(t *testing.T) {
	t.Run("nil fields", func(t *testing.T) {
		cfg := Config{
			Level:         "info",
			EnableConsole: true,
			EnableFile:    false,
		}

		logger, err := New(cfg)
		require.NoError(t, err)
		require.NotNil(t, logger)
		defer logger.Close()

		assert.NotPanics(t, func() {
			logger.Info("message with nil", zap.Any("nil", nil))
		})
	})

	t.Run("empty message", func(t *testing.T) {
		cfg := Config{
			Level:         "info",
			EnableConsole: true,
			EnableFile:    false,
		}

		logger, err := New(cfg)
		require.NoError(t, err)
		require.NotNil(t, logger)
		defer logger.Close()

		assert.NotPanics(t, func() {
			logger.Info("")
		})
	})

	t.Run("very long message", func(t *testing.T) {
		logPath := createTempLogFile(t)
		cfg := Config{
			LogPath:       logPath,
			MaxSize:       10,
			MaxBackups:    3,
			MaxAge:        1,
			Compress:      false,
			Level:         "info",
			EnableConsole: false,
			EnableFile:    true,
		}

		logger, err := New(cfg)
		require.NoError(t, err)
		require.NotNil(t, logger)
		defer logger.Close()

		longMessage := strings.Repeat("x", 10000)
		logger.Info(longMessage)
		assert.NoError(t, logger.Sync())

		content, err := os.ReadFile(logPath)
		require.NoError(t, err)
		assert.True(t, strings.Contains(string(content), longMessage[:100]))
	})

	t.Run("special characters in message", func(t *testing.T) {
		logPath := createTempLogFile(t)
		cfg := Config{
			LogPath:       logPath,
			MaxSize:       10,
			MaxBackups:    3,
			MaxAge:        1,
			Compress:      false,
			Level:         "info",
			EnableConsole: false,
			EnableFile:    true,
		}

		logger, err := New(cfg)
		require.NoError(t, err)
		require.NotNil(t, logger)
		defer logger.Close()

		specialMsg := "message with special chars: 中文 🔥 🎉 \n \t \\ \" '"
		logger.Info(specialMsg)
		assert.NoError(t, logger.Sync())

		content, err := os.ReadFile(logPath)
		require.NoError(t, err)
		assert.True(t, strings.Contains(string(content), "中文"))
	})
}

func TestLogger_FieldHelpers(t *testing.T) {
	assert.Equal(t, zap.String("key", "value"), String("key", "value"))
	assert.Equal(t, zap.Int("key", 42), Int("key", 42))
	assert.Equal(t, zap.Float64("key", 3.14), Float64("key", 3.14))
	assert.Equal(t, zap.Bool("key", true), Bool("key", true))
	assert.Equal(t, zap.Error(assert.AnError), ErrorField(assert.AnError))
}

func BenchmarkLogger_Info(b *testing.B) {
	logPath := filepath.Join(b.TempDir(), "bench.log")
	cfg := Config{
		LogPath:       logPath,
		MaxSize:       100,
		MaxBackups:    3,
		MaxAge:        1,
		Compress:      false,
		Level:         "info",
		EnableConsole: false,
		EnableFile:    true,
	}

	logger, _ := New(cfg)
	defer logger.Close()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		logger.Info("benchmark message",
			zap.Int("iteration", i),
			zap.String("status", "ok"),
		)
	}
}

func BenchmarkLogger_Concurrent(b *testing.B) {
	logPath := filepath.Join(b.TempDir(), "bench_concurrent.log")
	cfg := Config{
		LogPath:       logPath,
		MaxSize:       100,
		MaxBackups:    3,
		MaxAge:        1,
		Compress:      false,
		Level:         "info",
		EnableConsole: false,
		EnableFile:    true,
	}

	logger, _ := New(cfg)
	defer logger.Close()

	b.SetParallelism(10)
	b.RunParallel(func(pb *testing.PB) {
		i := 0
		for pb.Next() {
			logger.Info("concurrent bench", zap.Int("i", i))
			i++
		}
	})
}
