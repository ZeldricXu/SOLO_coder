package logger

import (
	"compress/gzip"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"gopkg.in/natefinch/lumberjack.v2"
)

type Config struct {
	LogPath       string `mapstructure:"log_path"`
	MaxSize       int    `mapstructure:"max_size"`
	MaxBackups    int    `mapstructure:"max_backups"`
	MaxAge        int    `mapstructure:"max_age"`
	Compress      bool   `mapstructure:"compress"`
	Level         string `mapstructure:"level"`
	EnableConsole bool   `mapstructure:"enable_console"`
	EnableFile    bool   `mapstructure:"enable_file"`
}

type Manager struct {
	mu          sync.RWMutex
	config      Config
	logger      *zap.Logger
	sugar       *zap.SugaredLogger
	rotator     *lumberjack.Logger
	archivePath string
	stopChan    chan struct{}
	ticker      *time.Ticker
}

var (
	defaultManager *Manager
	once           sync.Once
)

func New(cfg Config) (*Manager, error) {
	m := &Manager{
		config:      cfg,
		stopChan:    make(chan struct{}),
		archivePath: filepath.Join(filepath.Dir(cfg.LogPath), "archive"),
	}

	if err := os.MkdirAll(filepath.Dir(cfg.LogPath), 0755); err != nil {
		return nil, fmt.Errorf("create log directory: %w", err)
	}
	if cfg.Compress {
		if err := os.MkdirAll(m.archivePath, 0755); err != nil {
			return nil, fmt.Errorf("create archive directory: %w", err)
		}
	}

	if err := m.buildLogger(); err != nil {
		return nil, err
	}

	if cfg.Compress {
		go m.startArchiveWorker()
	}

	return m, nil
}

func InitGlobal(cfg Config) error {
	var err error
	once.Do(func() {
		defaultManager, err = New(cfg)
	})
	return err
}

func (m *Manager) buildLogger() error {
	level, err := zapcore.ParseLevel(m.config.Level)
	if err != nil {
		level = zapcore.InfoLevel
	}

	encoderConfig := zapcore.EncoderConfig{
		TimeKey:        "timestamp",
		LevelKey:       "level",
		NameKey:        "logger",
		CallerKey:      "caller",
		FunctionKey:    zapcore.OmitKey,
		MessageKey:     "message",
		StacktraceKey:  "stacktrace",
		LineEnding:     zapcore.DefaultLineEnding,
		EncodeLevel:    zapcore.LowercaseLevelEncoder,
		EncodeTime:     zapcore.ISO8601TimeEncoder,
		EncodeDuration: zapcore.SecondsDurationEncoder,
		EncodeCaller:   zapcore.ShortCallerEncoder,
	}

	var cores []zapcore.Core

	if m.config.EnableConsole {
		consoleEncoder := zapcore.NewConsoleEncoder(encoderConfig)
		consoleCore := zapcore.NewCore(consoleEncoder, zapcore.AddSync(os.Stdout), level)
		cores = append(cores, consoleCore)
	}

	if m.config.EnableFile && m.config.LogPath != "" {
		m.rotator = &lumberjack.Logger{
			Filename:   m.config.LogPath,
			MaxSize:    m.config.MaxSize,
			MaxBackups: m.config.MaxBackups,
			MaxAge:     m.config.MaxAge,
			Compress:   false,
		}
		fileEncoder := zapcore.NewJSONEncoder(encoderConfig)
		fileCore := zapcore.NewCore(fileEncoder, zapcore.AddSync(m.rotator), level)
		cores = append(cores, fileCore)
	}

	core := zapcore.NewTee(cores...)
	m.logger = zap.New(core, zap.AddCaller(), zap.AddCallerSkip(1))
	m.sugar = m.logger.Sugar()
	return nil
}

func (m *Manager) startArchiveWorker() {
	m.ticker = time.NewTicker(1 * time.Hour)
	defer m.ticker.Stop()

	for {
		select {
		case <-m.ticker.C:
			m.archiveOldLogs()
		case <-m.stopChan:
			return
		}
	}
}

func (m *Manager) archiveOldLogs() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	logDir := filepath.Dir(m.config.LogPath)
	baseName := filepath.Base(m.config.LogPath)

	files, err := os.ReadDir(logDir)
	if err != nil {
		return fmt.Errorf("read log directory: %w", err)
	}

	cutoffTime := time.Now().AddDate(0, 0, -m.config.MaxAge)

	for _, file := range files {
		if file.IsDir() {
			continue
		}

		matched, _ := filepath.Match(baseName+".*", file.Name())
		if !matched || filepath.Ext(file.Name()) == ".gz" {
			continue
		}

		fullPath := filepath.Join(logDir, file.Name())
		info, err := file.Info()
		if err != nil {
			continue
		}

		if info.ModTime().Before(cutoffTime) {
			if err := m.compressFile(fullPath); err != nil {
				fmt.Printf("compress file %s failed: %v\n", fullPath, err)
			}
		}
	}

	return nil
}

func (m *Manager) compressFile(srcPath string) error {
	srcFile, err := os.Open(srcPath)
	if err != nil {
		return err
	}
	defer srcFile.Close()

	destPath := filepath.Join(m.archivePath, filepath.Base(srcPath)+".gz")
	destFile, err := os.Create(destPath)
	if err != nil {
		return err
	}
	defer destFile.Close()

	gzipWriter := gzip.NewWriter(destFile)
	defer gzipWriter.Close()

	if _, err := io.Copy(gzipWriter, srcFile); err != nil {
		return err
	}

	if err := os.Remove(srcPath); err != nil {
		return err
	}

	fmt.Printf("archived: %s -> %s\n", srcPath, destPath)
	return nil
}

func (m *Manager) Rotate() error {
	if m.rotator != nil {
		return m.rotator.Rotate()
	}
	return nil
}

func (m *Manager) Debug(msg string, fields ...zap.Field) {
	m.logger.Debug(msg, fields...)
}

func (m *Manager) Info(msg string, fields ...zap.Field) {
	m.logger.Info(msg, fields...)
}

func (m *Manager) Warn(msg string, fields ...zap.Field) {
	m.logger.Warn(msg, fields...)
}

func (m *Manager) Error(msg string, fields ...zap.Field) {
	m.logger.Error(msg, fields...)
}

func (m *Manager) Fatal(msg string, fields ...zap.Field) {
	m.logger.Fatal(msg, fields...)
}

func (m *Manager) Debugf(format string, args ...interface{}) {
	m.sugar.Debugf(format, args...)
}

func (m *Manager) Infof(format string, args ...interface{}) {
	m.sugar.Infof(format, args...)
}

func (m *Manager) Warnf(format string, args ...interface{}) {
	m.sugar.Warnf(format, args...)
}

func (m *Manager) Errorf(format string, args ...interface{}) {
	m.sugar.Errorf(format, args...)
}

func (m *Manager) Fatalf(format string, args ...interface{}) {
	m.sugar.Fatalf(format, args...)
}

func (m *Manager) With(fields ...zap.Field) *zap.Logger {
	return m.logger.With(fields...)
}

func (m *Manager) Sync() error {
	return m.logger.Sync()
}

func (m *Manager) Close() error {
	close(m.stopChan)
	if m.ticker != nil {
		m.ticker.Stop()
	}
	if m.rotator != nil {
		if err := m.rotator.Close(); err != nil {
			return err
		}
	}
	return m.Sync()
}

func Debug(msg string, fields ...zap.Field) {
	if defaultManager != nil {
		defaultManager.Debug(msg, fields...)
	}
}

func Info(msg string, fields ...zap.Field) {
	if defaultManager != nil {
		defaultManager.Info(msg, fields...)
	}
}

func Warn(msg string, fields ...zap.Field) {
	if defaultManager != nil {
		defaultManager.Warn(msg, fields...)
	}
}

func Error(msg string, fields ...zap.Field) {
	if defaultManager != nil {
		defaultManager.Error(msg, fields...)
	}
}

func Fatal(msg string, fields ...zap.Field) {
	if defaultManager != nil {
		defaultManager.Fatal(msg, fields...)
	}
}

func Debugf(format string, args ...interface{}) {
	if defaultManager != nil {
		defaultManager.Debugf(format, args...)
	}
}

func Infof(format string, args ...interface{}) {
	if defaultManager != nil {
		defaultManager.Infof(format, args...)
	}
}

func Warnf(format string, args ...interface{}) {
	if defaultManager != nil {
		defaultManager.Warnf(format, args...)
	}
}

func Errorf(format string, args ...interface{}) {
	if defaultManager != nil {
		defaultManager.Errorf(format, args...)
	}
}

func Fatalf(format string, args ...interface{}) {
	if defaultManager != nil {
		defaultManager.Fatalf(format, args...)
	}
}

func Sync() error {
	if defaultManager != nil {
		return defaultManager.Sync()
	}
	return nil
}

func Close() error {
	if defaultManager != nil {
		return defaultManager.Close()
	}
	return nil
}

func String(key, value string) zap.Field {
	return zap.String(key, value)
}

func Int(key string, value int) zap.Field {
	return zap.Int(key, value)
}

func Float64(key string, value float64) zap.Field {
	return zap.Float64(key, value)
}

func Bool(key string, value bool) zap.Field {
	return zap.Bool(key, value)
}

func Any(key string, value interface{}) zap.Field {
	return zap.Any(key, value)
}

func ErrorField(err error) zap.Field {
	return zap.Error(err)
}
