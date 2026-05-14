package logger

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"

	"backupmanager/pkg/models"
)

type Logger struct {
	logPath string
	mu      sync.Mutex
	writer  io.Writer
}

func NewLogger(logPath string) *Logger {
	return &Logger{
		logPath: logPath,
	}
}

func (l *Logger) Init() error {
	if err := os.MkdirAll(filepath.Dir(l.logPath), 0755); err != nil {
		return fmt.Errorf("failed to create log directory: %w", err)
	}

	file, err := os.OpenFile(l.logPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
	if err != nil {
		return fmt.Errorf("failed to open log file: %w", err)
	}
	l.writer = io.MultiWriter(file, os.Stdout)
	return nil
}

func (l *Logger) Log(operation, versionID, status string, duration time.Duration, errors []string) (*models.BackupLog, error) {
	l.mu.Lock()
	defer l.mu.Unlock()

	logEntry := &models.BackupLog{
		LogID:     generateLogID(),
		Operation: operation,
		VersionID: versionID,
		Status:    status,
		Duration:  duration.Milliseconds(),
		Errors:    errors,
		LoggedAt:  time.Now(),
	}

	data, err := json.Marshal(logEntry)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal log entry: %w", err)
	}

	if l.writer != nil {
		fmt.Fprintln(l.writer, string(data))
	}
	return logEntry, nil
}

func (l *Logger) Info(format string, args ...interface{}) {
	l.logLevel("INFO", format, args...)
}

func (l *Logger) Warn(format string, args ...interface{}) {
	l.logLevel("WARN", format, args...)
}

func (l *Logger) Error(format string, args ...interface{}) {
	l.logLevel("ERROR", format, args...)
}

func (l *Logger) Debug(format string, args ...interface{}) {
	l.logLevel("DEBUG", format, args...)
}

func (l *Logger) logLevel(level, format string, args ...interface{}) {
	l.mu.Lock()
	defer l.mu.Unlock()

	timestamp := time.Now().Format("2006-01-02 15:04:05")
	message := fmt.Sprintf(format, args...)
	logLine := fmt.Sprintf("[%s] %s: %s", timestamp, level, message)

	if l.writer != nil {
		fmt.Fprintln(l.writer, logLine)
	} else {
		fmt.Println(logLine)
	}
}

func generateLogID() string {
	return fmt.Sprintf("log_%s", time.Now().Format("20060102150405000"))
}
