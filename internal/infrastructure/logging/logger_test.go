package logging

import (
	"context"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/solocoder/session148/internal/domain"
	"github.com/solocoder/session148/pkg/utils"
)

func TestNewZapLogger_Basic(t *testing.T) {
	t.Run("should create logger with default config", func(t *testing.T) {
		tmpDir := t.TempDir()
		logPath := filepath.Join(tmpDir, "app.log")

		logger, err := NewZapLogger(LoggerConfig{
			Level:      "debug",
			OutputPath: logPath,
			MaxSizeMB:  1,
			MaxBackups: 3,
			MaxAgeDays: 1,
		})

		require.NoError(t, err)
		require.NotNil(t, logger)
		defer logger.Sync()

		logger.Info("test message", "key", "value")
		logger.Sync()

		_, err = os.Stat(logPath)
		assert.NoError(t, err)
	})

	t.Run("should create logger without file output", func(t *testing.T) {
		logger, err := NewZapLogger(LoggerConfig{
			Level: "info",
		})

		require.NoError(t, err)
		require.NotNil(t, logger)
		defer logger.Sync()

		logger.Info("console only test")
	})

	t.Run("should handle invalid log level gracefully", func(t *testing.T) {
		tmpDir := t.TempDir()
		logPath := filepath.Join(tmpDir, "app.log")

		logger, err := NewZapLogger(LoggerConfig{
			Level:      "invalid_level",
			OutputPath: logPath,
		})

		require.NoError(t, err)
		require.NotNil(t, logger)
		defer logger.Sync()

		logger.Info("should log at info level")
	})

	t.Run("should create directory if not exists", func(t *testing.T) {
		tmpDir := t.TempDir()
		nestedDir := filepath.Join(tmpDir, "nested", "logs")
		logPath := filepath.Join(nestedDir, "app.log")

		logger, err := NewZapLogger(LoggerConfig{
			Level:      "info",
			OutputPath: logPath,
		})

		require.NoError(t, err)
		require.NotNil(t, logger)
		defer logger.Sync()

		_, err = os.Stat(nestedDir)
		assert.NoError(t, err)
	})
}

func TestZapLogger_LogLevels(t *testing.T) {
	tmpDir := t.TempDir()
	logPath := filepath.Join(tmpDir, "app.log")

	logger, err := NewZapLogger(LoggerConfig{
		Level:      "debug",
		OutputPath: logPath,
		MaxSizeMB:  1,
		MaxBackups: 3,
	})
	require.NoError(t, err)
	defer logger.Sync()

	tests := []struct {
		name  string
		level string
		logFn func(string, ...interface{})
	}{
		{"debug", "debug", logger.Debug},
		{"info", "info", logger.Info},
		{"warn", "warn", logger.Warn},
		{"error", "error", logger.Error},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			tt.logFn("test "+tt.level, "field", tt.name)
		})
	}

	logger.Sync()

	data, err := os.ReadFile(logPath)
	require.NoError(t, err)
	content := string(data)

	for _, tt := range tests {
		assert.Contains(t, content, "test "+tt.level)
	}
}

func TestZapLogger_WithTraceID(t *testing.T) {
	tmpDir := t.TempDir()
	logPath := filepath.Join(tmpDir, "app.log")

	logger, err := NewZapLogger(LoggerConfig{
		Level:      "info",
		OutputPath: logPath,
		MaxSizeMB:  1,
	})
	require.NoError(t, err)
	defer logger.Sync()

	traceID := "trace_abc123"
	tracedLogger := logger.WithTraceID(traceID)

	tracedLogger.Info("message with trace")
	logger.Sync()

	data, err := os.ReadFile(logPath)
	require.NoError(t, err)
	content := string(data)

	assert.Contains(t, content, traceID)
	assert.Contains(t, content, "message with trace")
}

func TestLogRotator_Rotation(t *testing.T) {
	t.Run("should rotate when file exceeds max size", func(t *testing.T) {
		tmpDir := t.TempDir()
		logPath := filepath.Join(tmpDir, "app.log")

		rotator, err := NewLogRotator(RotatorConfig{
			Filename:   logPath,
			MaxSize:    1,
			MaxBackups: 5,
			MaxAge:     1,
			Compress:   false,
		})
		require.NoError(t, err)
		defer rotator.Close()

		largeMessage := make([]byte, 2048)
		for i := range largeMessage {
			largeMessage[i] = 'x'
		}

		_, err = rotator.Write(largeMessage)
		require.NoError(t, err)

		_, err = rotator.Write([]byte("second message"))
		require.NoError(t, err)

		files, err := os.ReadDir(tmpDir)
		require.NoError(t, err)

		backupCount := 0
		for _, f := range files {
			if f.Name() != "app.log" && f.Name() != "rotate.ckpt" {
				backupCount++
			}
		}
		assert.Greater(t, backupCount, 0, "should have at least one backup file")
	})

	t.Run("should respect max backups limit", func(t *testing.T) {
		tmpDir := t.TempDir()
		logPath := filepath.Join(tmpDir, "app.log")

		rotator, err := NewLogRotator(RotatorConfig{
			Filename:   logPath,
			MaxSize:    1,
			MaxBackups: 2,
			MaxAge:     1,
			Compress:   false,
		})
		require.NoError(t, err)
		defer rotator.Close()

		largeMessage := make([]byte, 2048)

		for i := 0; i < 5; i++ {
			_, err = rotator.Write(largeMessage)
			require.NoError(t, err)
			time.Sleep(10 * time.Millisecond)
		}

		files, err := os.ReadDir(tmpDir)
		require.NoError(t, err)

		backupCount := 0
		for _, f := range files {
			if f.Name() != "app.log" && f.Name() != "rotate.ckpt" {
				backupCount++
			}
		}
		assert.LessOrEqual(t, backupCount, 2, "should not exceed max backups")
	})

	t.Run("manual rotation should work", func(t *testing.T) {
		tmpDir := t.TempDir()
		logPath := filepath.Join(tmpDir, "app.log")

		rotator, err := NewLogRotator(RotatorConfig{
			Filename:   logPath,
			MaxSize:    100,
			MaxBackups: 3,
			MaxAge:     1,
		})
		require.NoError(t, err)
		defer rotator.Close()

		_, err = rotator.Write([]byte("before rotation"))
		require.NoError(t, err)

		err = rotator.Rotate()
		require.NoError(t, err)

		_, err = rotator.Write([]byte("after rotation"))
		require.NoError(t, err)

		files, err := os.ReadDir(tmpDir)
		require.NoError(t, err)

		backupCount := 0
		for _, f := range files {
			if f.Name() != "app.log" && f.Name() != "rotate.ckpt" {
				backupCount++
			}
		}
		assert.Equal(t, 1, backupCount)
	})
}

func TestLogRotator_Archive(t *testing.T) {
	tmpDir := t.TempDir()
	logPath := filepath.Join(tmpDir, "app.log")

	rotator, err := NewLogRotator(RotatorConfig{
		Filename: logPath,
		MaxSize:  10,
		MaxAge:   1,
	})
	require.NoError(t, err)
	defer rotator.Close()

	backupFile := filepath.Join(tmpDir, "app.log.2024-01-01-00-00-00")
	err = os.WriteFile(backupFile, []byte("backup content"), 0644)
	require.NoError(t, err)

	err = rotator.Archive(backupFile)
	require.NoError(t, err)

	archiveDir := filepath.Join(tmpDir, "archive")
	_, err = os.Stat(filepath.Join(archiveDir, "app.log.2024-01-01-00-00-00"))
	assert.NoError(t, err)
}

func TestLogRotator_Cleanup(t *testing.T) {
	tmpDir := t.TempDir()
	logPath := filepath.Join(tmpDir, "app.log")

	rotator, err := NewLogRotator(RotatorConfig{
		Filename: logPath,
		MaxSize:  10,
		MaxAge:   1,
	})
	require.NoError(t, err)
	defer rotator.Close()

	oldFile := filepath.Join(tmpDir, "old.log")
	err = os.WriteFile(oldFile, []byte("old"), 0644)
	require.NoError(t, err)

	oldTime := time.Now().Add(-48 * time.Hour)
	os.Chtimes(oldFile, oldTime, oldTime)

	err = rotator.Cleanup(24 * time.Hour)
	require.NoError(t, err)

	_, err = os.Stat(oldFile)
	assert.True(t, os.IsNotExist(err), "old file should be deleted")
}

func TestWriteAheadLog(t *testing.T) {
	t.Run("should write and recover entries", func(t *testing.T) {
		tmpDir := t.TempDir()

		wal, err := NewWriteAheadLog(tmpDir)
		require.NoError(t, err)

		entry := &domain.LogEntry{
			Level:     "info",
			Message:   "test wal entry",
			Timestamp: time.Now().UTC(),
			TraceID:   "trace_123",
		}

		err = wal.Write(entry)
		require.NoError(t, err)

		var recoveredEntry *domain.LogEntry
		replayDone := make(chan bool)

		go func() {
			wal.StartReplay(func(e *domain.LogEntry) {
				recoveredEntry = e
				replayDone <- true
			})
		}()

		go func() {
			wal.Recover()
			wal.Close()
		}()

		select {
		case <-replayDone:
			assert.Equal(t, "test wal entry", recoveredEntry.Message)
			assert.Equal(t, "trace_123", recoveredEntry.TraceID)
		case <-time.After(2 * time.Second):
			t.Fatal("timeout waiting for replay")
		}
	})

	t.Run("should handle corrupted entries gracefully", func(t *testing.T) {
		tmpDir := t.TempDir()

		walFile := filepath.Join(tmpDir, "wal.log")
		err := os.WriteFile(walFile, []byte("corrupted data"), 0644)
		require.NoError(t, err)

		wal, err := NewWriteAheadLog(tmpDir)
		require.NoError(t, err)

		go wal.StartReplay(func(e *domain.LogEntry) {
			t.Fatal("should not replay corrupted data")
		})

		err = wal.Recover()
		require.NoError(t, err)
		wal.Close()
	})
}

func TestLogIndex(t *testing.T) {
	t.Run("should append and query entries", func(t *testing.T) {
		tmpDir := t.TempDir()

		idx, err := NewLogIndex(tmpDir)
		require.NoError(t, err)

		now := time.Now().UTC()

		entries := []*domain.LogEntry{
			{Level: "info", Message: "msg1", Timestamp: now.Add(-2 * time.Minute), TraceID: "t1"},
			{Level: "error", Message: "msg2", Timestamp: now.Add(-1 * time.Minute), TraceID: "t2"},
			{Level: "info", Message: "msg3", Timestamp: now, TraceID: "t1"},
		}

		for _, e := range entries {
			idx.Append(e)
		}

		t.Run("query by level", func(t *testing.T) {
			results, err := idx.Query(LogFilter{Level: "error"})
			require.NoError(t, err)
			assert.Len(t, results, 1)
			assert.Equal(t, "error", results[0].Level)
		})

		t.Run("query by trace_id", func(t *testing.T) {
			results, err := idx.Query(LogFilter{TraceID: "t1"})
			require.NoError(t, err)
			assert.Len(t, results, 2)
		})

		t.Run("query by time range", func(t *testing.T) {
			results, err := idx.Query(LogFilter{
				StartTime: now.Add(-90 * time.Second),
				EndTime:   now.Add(-30 * time.Second),
			})
			require.NoError(t, err)
			assert.Len(t, results, 1)
			assert.Equal(t, "msg2", results[0].Message)
		})

		t.Run("query with pagination", func(t *testing.T) {
			results, err := idx.Query(LogFilter{Limit: 1, Offset: 1})
			require.NoError(t, err)
			assert.Len(t, results, 1)
		})

		t.Run("query no match", func(t *testing.T) {
			results, err := idx.Query(LogFilter{TraceID: "nonexistent"})
			require.NoError(t, err)
			assert.Len(t, results, 0)
		})
	})

	t.Run("should persist and recover index", func(t *testing.T) {
		tmpDir := t.TempDir()

		idx, err := NewLogIndex(tmpDir)
		require.NoError(t, err)

		entry := &domain.LogEntry{
			Level:     "warn",
			Message:   "persistent entry",
			Timestamp: time.Now().UTC(),
		}
		idx.Append(entry)
		idx.Sync()

		idx2, err := NewLogIndex(tmpDir)
		require.NoError(t, err)

		err = idx2.Recover()
		require.NoError(t, err)

		results, err := idx2.Query(LogFilter{Level: "warn"})
		require.NoError(t, err)
		assert.Len(t, results, 1)
	})
}

func TestConcurrentLogging(t *testing.T) {
	tmpDir := t.TempDir()
	logPath := filepath.Join(tmpDir, "app.log")

	logger, err := NewZapLogger(LoggerConfig{
		Level:      "info",
		OutputPath: logPath,
		MaxSizeMB:  10,
		MaxBackups: 5,
	})
	require.NoError(t, err)
	defer logger.Sync()

	const goroutines = 100
	const messagesPerGoroutine = 100

	var wg sync.WaitGroup
	wg.Add(goroutines)

	var errorCount int32

	for i := 0; i < goroutines; i++ {
		go func(id int) {
			defer wg.Done()
			for j := 0; j < messagesPerGoroutine; j++ {
				func() {
					defer func() {
						if r := recover(); r != nil {
							atomic.AddInt32(&errorCount, 1)
						}
					}()
					logger.Info("concurrent message", "goroutine", id, "msg_num", j)
				}()
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
		logger.Sync()
		assert.Equal(t, int32(0), errorCount, "no panics during concurrent logging")

		info, err := os.Stat(logPath)
		require.NoError(t, err)
		assert.Greater(t, info.Size(), int64(0), "log file should not be empty")
	case <-time.After(30 * time.Second):
		t.Fatal("timeout waiting for concurrent logging to complete")
	}
}

func TestConcurrentRotation(t *testing.T) {
	tmpDir := t.TempDir()
	logPath := filepath.Join(tmpDir, "app.log")

	rotator, err := NewLogRotator(RotatorConfig{
		Filename:   logPath,
		MaxSize:    10,
		MaxBackups: 10,
		MaxAge:     1,
	})
	require.NoError(t, err)
	defer rotator.Close()

	const goroutines = 20
	const iterations = 50

	var wg sync.WaitGroup
	wg.Add(goroutines)

	var errorCount int32

	for i := 0; i < goroutines; i++ {
		go func() {
			defer wg.Done()
			for j := 0; j < iterations; j++ {
				func() {
					defer func() {
						if r := recover(); r != nil {
							atomic.AddInt32(&errorCount, 1)
						}
					}()

					msg := make([]byte, 512)
					_, err := rotator.Write(msg)
					if err != nil {
						atomic.AddInt32(&errorCount, 1)
					}

					if j%10 == 0 {
						rotator.Rotate()
					}
				}()
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
		assert.Equal(t, int32(0), errorCount, "no errors during concurrent rotation")
	case <-time.After(30 * time.Second):
		t.Fatal("timeout waiting for concurrent rotation to complete")
	}
}

func TestLogger_EdgeCases(t *testing.T) {
	t.Run("empty log message", func(t *testing.T) {
		tmpDir := t.TempDir()
		logPath := filepath.Join(tmpDir, "app.log")

		logger, err := NewZapLogger(LoggerConfig{
			Level:      "info",
			OutputPath: logPath,
		})
		require.NoError(t, err)
		defer logger.Sync()

		assert.NotPanics(t, func() {
			logger.Info("", "key", "value")
		})
	})

	t.Run("nil fields", func(t *testing.T) {
		tmpDir := t.TempDir()
		logPath := filepath.Join(tmpDir, "app.log")

		logger, err := NewZapLogger(LoggerConfig{
			Level:      "info",
			OutputPath: logPath,
		})
		require.NoError(t, err)
		defer logger.Sync()

		assert.NotPanics(t, func() {
			logger.Info("message with nil field", "nil_key", nil)
		})
	})

	t.Run("odd number of fields", func(t *testing.T) {
		tmpDir := t.TempDir()
		logPath := filepath.Join(tmpDir, "app.log")

		logger, err := NewZapLogger(LoggerConfig{
			Level:      "info",
			OutputPath: logPath,
		})
		require.NoError(t, err)
		defer logger.Sync()

		assert.NotPanics(t, func() {
			logger.Info("message with odd fields", "key1", "val1", "key_without_value")
		})
	})

	t.Run("very long log message", func(t *testing.T) {
		tmpDir := t.TempDir()
		logPath := filepath.Join(tmpDir, "app.log")

		logger, err := NewZapLogger(LoggerConfig{
			Level:      "info",
			OutputPath: logPath,
			MaxSizeMB:  1,
		})
		require.NoError(t, err)
		defer logger.Sync()

		longMsg := make([]byte, 10000)
		for i := range longMsg {
			longMsg[i] = 'a'
		}

		assert.NotPanics(t, func() {
			logger.Info(string(longMsg))
		})
	})
}

func TestCheckpointRecovery(t *testing.T) {
	tmpDir := t.TempDir()
	logPath := filepath.Join(tmpDir, "app.log")

	rotator, err := NewLogRotator(RotatorConfig{
		Filename:   logPath,
		MaxSize:    1,
		MaxBackups: 5,
		MaxAge:     1,
	})
	require.NoError(t, err)

	_, err = rotator.Write([]byte("before rotation"))
	require.NoError(t, err)

	err = rotator.Rotate()
	require.NoError(t, err)

	rotator.Close()

	rotator2, err := NewLogRotator(RotatorConfig{
		Filename:   logPath,
		MaxSize:    1,
		MaxBackups: 5,
		MaxAge:     1,
	})
	require.NoError(t, err)
	defer rotator2.Close()

	_, err = rotator2.Write([]byte("after recovery"))
	require.NoError(t, err)

	_, err = os.Stat(logPath)
	assert.NoError(t, err)
}

func TestQueryLogs(t *testing.T) {
	tmpDir := t.TempDir()
	logPath := filepath.Join(tmpDir, "app.log")

	logger, err := NewZapLogger(LoggerConfig{
		Level:       "debug",
		OutputPath:  logPath,
		MaxSizeMB:   10,
		EnableIndex: true,
	})
	require.NoError(t, err)
	defer logger.Sync()

	logger.Info("test message 1", "user", "alice")
	logger.Error("test error", "user", "bob")
	logger.Info("test message 2", "user", "alice")
	logger.Sync()

	ctx := context.Background()

	t.Run("query without index should return error", func(t *testing.T) {
		basicLogger, _ := NewZapLogger(LoggerConfig{
			Level:      "info",
			OutputPath: filepath.Join(tmpDir, "basic.log"),
		})
		_, err := basicLogger.QueryLogs(ctx, LogFilter{Level: "info"})
		assert.Error(t, err)
	})
}
