package logger

import (
	"compress/gzip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/datatrace/datatrace/internal/models"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

type LogLevel string

const (
	LevelDebug LogLevel = "debug"
	LevelInfo  LogLevel = "info"
	LevelWarn  LogLevel = "warn"
	LevelError LogLevel = "error"
	LevelFatal LogLevel = "fatal"
)

type RotationPolicy string

const (
	RotationSize    RotationPolicy = "size"
	RotationTime    RotationPolicy = "time"
	RotationManual  RotationPolicy = "manual"
)

type LogConfig struct {
	LogDir          string
	MaxFileSize     int64
	MaxFiles        int
	RotationPolicy  RotationPolicy
	RotationTime    time.Duration
	Compress        bool
	RetentionDays   int
	Level           LogLevel
}

type LogEntry struct {
	Level      LogLevel  `json:"level"`
	Message    string    `json:"message"`
	Timestamp  time.Time `json:"timestamp"`
	TraceID    string    `json:"trace_id,omitempty"`
	Module     string    `json:"module,omitempty"`
	Attributes map[string]interface{} `json:"attributes,omitempty"`
}

type Logger struct {
	config       LogConfig
	currentFile  *os.File
	zapLogger    *zap.Logger
	mu           sync.Mutex
	rotateChan   chan struct{}
	stopCh       chan struct{}
	wg           sync.WaitGroup
	currentSize  int64
	fileCreated  time.Time
}

var (
	instance *Logger
	once     sync.Once
)

func GetLogger() *Logger {
	return instance
}

func InitLogger(config LogConfig) (*Logger, error) {
	var err error
	once.Do(func() {
		instance, err = newLogger(config)
	})
	return instance, err
}

func newLogger(config LogConfig) (*Logger, error) {
	if err := os.MkdirAll(config.LogDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create log directory: %w", err)
	}

	l := &Logger{
		config:     config,
		rotateChan: make(chan struct{}, 1),
		stopCh:     make(chan struct{}),
	}

	if err := l.openNewFile(); err != nil {
		return nil, err
	}

	if err := l.initZapLogger(); err != nil {
		return nil, err
	}

	l.wg.Add(1)
	go l.runRotationWorker()

	return l, nil
}

func (l *Logger) openNewFile() error {
	now := time.Now()
	filename := fmt.Sprintf("app_%s.log", now.Format("20060102_150405"))
	filepath := filepath.Join(l.config.LogDir, filename)

	file, err := os.OpenFile(filepath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err != nil {
		return fmt.Errorf("failed to open log file: %w", err)
	}

	if l.currentFile != nil {
		l.currentFile.Close()
	}

	l.currentFile = file
	l.currentSize = 0
	l.fileCreated = now
	return nil
}

func (l *Logger) initZapLogger() error {
	encoderConfig := zap.NewProductionEncoderConfig()
	encoderConfig.TimeKey = "timestamp"
	encoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder

	fileEncoder := zapcore.NewJSONEncoder(encoderConfig)
	fileCore := zapcore.NewCore(fileEncoder, zapcore.AddSync(l.currentFile), l.getZapLevel())

	consoleEncoder := zapcore.NewConsoleEncoder(encoderConfig)
	consoleCore := zapcore.NewCore(consoleEncoder, zapcore.AddSync(os.Stdout), l.getZapLevel())

	core := zapcore.NewTee(fileCore, consoleCore)
	l.zapLogger = zap.New(core, zap.AddCaller(), zap.AddCallerSkip(1))

	return nil
}

func (l *Logger) getZapLevel() zapcore.Level {
	switch l.config.Level {
	case LevelDebug:
		return zapcore.DebugLevel
	case LevelInfo:
		return zapcore.InfoLevel
	case LevelWarn:
		return zapcore.WarnLevel
	case LevelError:
		return zapcore.ErrorLevel
	case LevelFatal:
		return zapcore.FatalLevel
	default:
		return zapcore.InfoLevel
	}
}

func (l *Logger) runRotationWorker() {
	defer l.wg.Done()

	var ticker *time.Ticker
	if l.config.RotationPolicy == RotationTime && l.config.RotationTime > 0 {
		ticker = time.NewTicker(l.config.RotationTime)
		defer ticker.Stop()
	}

	for {
		select {
		case <-l.stopCh:
			return
		case <-l.rotateChan:
			l.rotate()
		case <-func() <-chan time.Time {
			if ticker != nil {
				return ticker.C
			}
			return nil
		}():
			l.rotate()
		}
	}
}

func (l *Logger) rotate() {
	l.mu.Lock()
	defer l.mu.Unlock()

	if l.currentFile == nil {
		return
	}

	if l.config.Compress {
		l.compressCurrentFile()
	}

	l.cleanupOldFiles()
	l.openNewFile()
	l.initZapLogger()
}

func (l *Logger) compressCurrentFile() {
	filename := l.currentFile.Name()
	l.currentFile.Close()

	file, err := os.Open(filename)
	if err != nil {
		return
	}
	defer file.Close()

	gzFilename := filename + ".gz"
	gzFile, err := os.Create(gzFilename)
	if err != nil {
		return
	}
	defer gzFile.Close()

	gzWriter := gzip.NewWriter(gzFile)
	defer gzWriter.Close()

	io.Copy(gzWriter, file)
	os.Remove(filename)
}

func (l *Logger) cleanupOldFiles() {
	if l.config.MaxFiles <= 0 && l.config.RetentionDays <= 0 {
		return
	}

	files, err := os.ReadDir(l.config.LogDir)
	if err != nil {
		return
	}

	logFiles := make([]os.DirEntry, 0)
	for _, f := range files {
		if filepath.Ext(f.Name()) == ".log" || filepath.Ext(f.Name()) == ".gz" {
			logFiles = append(logFiles, f)
		}
	}

	if l.config.MaxFiles > 0 && len(logFiles) > l.config.MaxFiles {
		for i := 0; i < len(logFiles)-l.config.MaxFiles; i++ {
			os.Remove(filepath.Join(l.config.LogDir, logFiles[i].Name()))
		}
	}

	if l.config.RetentionDays > 0 {
		cutoff := time.Now().AddDate(0, 0, -l.config.RetentionDays)
		for _, f := range logFiles {
			info, err := f.Info()
			if err != nil {
				continue
			}
			if info.ModTime().Before(cutoff) {
				os.Remove(filepath.Join(l.config.LogDir, f.Name()))
			}
		}
	}
}

func (l *Logger) log(level LogLevel, msg string, attrs map[string]interface{}) {
	if l.zapLogger == nil {
		return
	}

	fields := make([]zap.Field, 0, len(attrs))
	for k, v := range attrs {
		fields = append(fields, zap.Any(k, v))
	}

	switch level {
	case LevelDebug:
		l.zapLogger.Debug(msg, fields...)
	case LevelInfo:
		l.zapLogger.Info(msg, fields...)
	case LevelWarn:
		l.zapLogger.Warn(msg, fields...)
	case LevelError:
		l.zapLogger.Error(msg, fields...)
	case LevelFatal:
		l.zapLogger.Fatal(msg, fields...)
	}

	l.checkRotation(int64(len(msg)))
}

func (l *Logger) checkRotation(written int64) {
	l.mu.Lock()
	defer l.mu.Unlock()

	l.currentSize += written

	if l.config.RotationPolicy == RotationSize && l.currentSize >= l.config.MaxFileSize {
		select {
		case l.rotateChan <- struct{}{}:
		default:
		}
	}
}

func (l *Logger) Debug(msg string, attrs map[string]interface{}) {
	l.log(LevelDebug, msg, attrs)
}

func (l *Logger) Info(msg string, attrs map[string]interface{}) {
	l.log(LevelInfo, msg, attrs)
}

func (l *Logger) Warn(msg string, attrs map[string]interface{}) {
	l.log(LevelWarn, msg, attrs)
}

func (l *Logger) Error(msg string, attrs map[string]interface{}) {
	l.log(LevelError, msg, attrs)
}

func (l *Logger) Fatal(msg string, attrs map[string]interface{}) {
	l.log(LevelFatal, msg, attrs)
}

func (l *Logger) WithTrace(traceID string) *ContextLogger {
	return &ContextLogger{
		logger:  l,
		traceID: traceID,
	}
}

func (l *Logger) WithModule(module string) *ContextLogger {
	return &ContextLogger{
		logger: l,
		module: module,
	}
}

func (l *Logger) Rotate() error {
	select {
	case l.rotateChan <- struct{}{}:
		return nil
	default:
		return errors.New("rotation already pending")
	}
}

func (l *Logger) Close() error {
	close(l.stopCh)
	l.wg.Wait()

	if l.currentFile != nil {
		l.currentFile.Close()
	}

	if l.zapLogger != nil {
		l.zapLogger.Sync()
	}

	return nil
}

type ContextLogger struct {
	logger  *Logger
	traceID string
	module  string
}

func (cl *ContextLogger) addContext(attrs map[string]interface{}) map[string]interface{} {
	if attrs == nil {
		attrs = make(map[string]interface{})
	}
	if cl.traceID != "" {
		attrs["trace_id"] = cl.traceID
	}
	if cl.module != "" {
		attrs["module"] = cl.module
	}
	return attrs
}

func (cl *ContextLogger) Debug(msg string, attrs map[string]interface{}) {
	cl.logger.Debug(msg, cl.addContext(attrs))
}

func (cl *ContextLogger) Info(msg string, attrs map[string]interface{}) {
	cl.logger.Info(msg, cl.addContext(attrs))
}

func (cl *ContextLogger) Warn(msg string, attrs map[string]interface{}) {
	cl.logger.Warn(msg, cl.addContext(attrs))
}

func (cl *ContextLogger) Error(msg string, attrs map[string]interface{}) {
	cl.logger.Error(msg, cl.addContext(attrs))
}

func (cl *ContextLogger) Fatal(msg string, attrs map[string]interface{}) {
	cl.logger.Fatal(msg, cl.addContext(attrs))
}

func (cl *ContextLogger) WithTrace(traceID string) *ContextLogger {
	return &ContextLogger{
		logger:  cl.logger,
		traceID: traceID,
		module:  cl.module,
	}
}

func (cl *ContextLogger) WithModule(module string) *ContextLogger {
	return &ContextLogger{
		logger:  cl.logger,
		traceID: cl.traceID,
		module:  module,
	}
}

func (l *Logger) ToEntity() *models.Entity {
	return &models.Entity{
		ID:        uuid.New().String(),
		Type:      "logger",
		Status:    "active",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
}

func Debug(msg string, attrs map[string]interface{}) {
	if l := GetLogger(); l != nil {
		l.Debug(msg, attrs)
	}
}

func Info(msg string, attrs map[string]interface{}) {
	if l := GetLogger(); l != nil {
		l.Info(msg, attrs)
	}
}

func Warn(msg string, attrs map[string]interface{}) {
	if l := GetLogger(); l != nil {
		l.Warn(msg, attrs)
	}
}

func Error(msg string, attrs map[string]interface{}) {
	if l := GetLogger(); l != nil {
		l.Error(msg, attrs)
	}
}

func Fatal(msg string, attrs map[string]interface{}) {
	if l := GetLogger(); l != nil {
		l.Fatal(msg, attrs)
	}
}

func ParseLogFile(filepath string) ([]LogEntry, error) {
	file, err := os.Open(filepath)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	var entries []LogEntry
	decoder := json.NewDecoder(file)

	for {
		var entry LogEntry
		if err := decoder.Decode(&entry); err == io.EOF {
			break
		} else if err != nil {
			continue
		}
		entries = append(entries, entry)
	}

	return entries, nil
}
