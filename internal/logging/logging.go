package logging

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
)

type RotationConfig struct {
	MaxSize    int
	MaxBackups int
	MaxAge     int
	Compress   bool
}

type FileRotator struct {
	filename   string
	maxSize    int64
	maxBackups int
	maxAge     int
	compress   bool
	file       *os.File
	size       int64
	mu         sync.Mutex
}

func NewFileRotator(filename string, config RotationConfig) *FileRotator {
	return &FileRotator{
		filename:   filename,
		maxSize:    int64(config.MaxSize) * 1024 * 1024,
		maxBackups: config.MaxBackups,
		maxAge:     config.MaxAge,
		compress:   config.Compress,
	}
}

func (r *FileRotator) Write(p []byte) (n int, err error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.file == nil {
		if err = r.openExistingOrNew(); err != nil {
			return 0, err
		}
	}

	n, err = r.file.Write(p)
	r.size += int64(n)

	if r.size >= r.maxSize {
		r.rotate()
	}

	return n, err
}

func (r *FileRotator) openExistingOrNew() error {
	info, err := os.Stat(r.filename)
	if os.IsNotExist(err) {
		return r.openNew()
	}
	if err != nil {
		return err
	}
	r.size = info.Size()
	r.file, err = os.OpenFile(r.filename, os.O_APPEND|os.O_WRONLY, 0644)
	return err
}

func (r *FileRotator) openNew() error {
	dir := filepath.Dir(r.filename)
	os.MkdirAll(dir, 0755)
	var err error
	r.file, err = os.OpenFile(r.filename, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644)
	r.size = 0
	return err
}

func (r *FileRotator) rotate() error {
	r.file.Close()

	backupName := r.filename + "." + time.Now().Format("2006-01-02T15-04-05")
	os.Rename(r.filename, backupName)

	if r.compress {
		go r.compressFile(backupName)
	}

	go r.cleanupOld()

	return r.openNew()
}

func (r *FileRotator) compressFile(filename string) {
	f, err := os.Open(filename)
	if err != nil {
		return
	}
	defer f.Close()

	gz, err := os.Create(filename + ".gz")
	if err != nil {
		return
	}
	defer gz.Close()

	w := gzip.NewWriter(gz)
	io.Copy(w, f)
	w.Close()
	os.Remove(filename)
}

func (r *FileRotator) cleanupOld() {
	dir := filepath.Dir(r.filename)
	matches, _ := filepath.Glob(filepath.Join(dir, filepath.Base(r.filename)+".*"))

	if len(matches) <= r.maxBackups {
		return
	}

	cutoff := time.Now().AddDate(0, 0, -r.maxAge)
	for _, match := range matches {
		info, err := os.Stat(match)
		if err != nil {
			continue
		}
		if info.ModTime().Before(cutoff) {
			os.Remove(match)
		}
	}
}

func (r *FileRotator) Close() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.file != nil {
		return r.file.Close()
	}
	return nil
}

type LogEntry struct {
	Level     string                 `json:"level"`
	Message   string                 `json:"message"`
	Timestamp time.Time              `json:"timestamp"`
	Fields    map[string]interface{} `json:"fields,omitempty"`
}

type BatchLogRequest struct {
	Entries []LogEntry `json:"entries"`
}

type BatchLogResult struct {
	SuccessCount int      `json:"success_count"`
	FailedCount  int      `json:"failed_count"`
	FailedIndices []int   `json:"failed_indices,omitempty"`
}

type BatcherConfig struct {
	MaxBatchSize  int
	FlushInterval time.Duration
	MaxQueueSize  int
}

type LogBatcher struct {
	config    BatcherConfig
	buffer    []LogEntry
	mu        sync.Mutex
	flushChan chan struct{}
	stopChan  chan struct{}
	metrics   BatcherMetrics
}

type BatcherMetrics struct {
	BatchesFlushed  int64 `json:"batches_flushed"`
	TotalLogs       int64 `json:"total_logs"`
	DroppedLogs     int64 `json:"dropped_logs"`
	AvgBatchSize    int64 `json:"avg_batch_size"`
	FlushesByTimer  int64 `json:"flushes_by_timer"`
	FlushesBySize   int64 `json:"flushes_by_size"`
}

var (
	logger *zap.Logger
	rotator *FileRotator
	batcher *LogBatcher
)

func DefaultBatcherConfig() BatcherConfig {
	return BatcherConfig{
		MaxBatchSize:  100,
		FlushInterval: 1 * time.Second,
		MaxQueueSize:  10000,
	}
}

func Init(logFile string) {
	config := RotationConfig{
		MaxSize:    100,
		MaxBackups: 10,
		MaxAge:     30,
		Compress:   true,
	}

	rotator = NewFileRotator(logFile, config)

	encoderConfig := zap.NewProductionEncoderConfig()
	encoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder
	encoder := zapcore.NewJSONEncoder(encoderConfig)

	core := zapcore.NewTee(
		zapcore.NewCore(encoder, zapcore.AddSync(os.Stdout), zap.InfoLevel),
		zapcore.NewCore(encoder, zapcore.AddSync(rotator), zap.InfoLevel),
	)

	logger = zap.New(core, zap.AddCaller(), zap.AddStacktrace(zap.ErrorLevel))
}

func InitBatcher(config BatcherConfig) {
	if batcher != nil {
		batcher.Stop()
	}

	batcher = &LogBatcher{
		config:    config,
		buffer:    make([]LogEntry, 0, config.MaxBatchSize),
		flushChan: make(chan struct{}, 1),
		stopChan:  make(chan struct{}),
	}

	go batcher.start()
}

func (b *LogBatcher) start() {
	ticker := time.NewTicker(b.config.FlushInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			b.mu.Lock()
			if len(b.buffer) > 0 {
				b.flushLocked("timer")
			}
			b.mu.Unlock()
		case <-b.flushChan:
			b.mu.Lock()
			if len(b.buffer) >= b.config.MaxBatchSize {
				b.flushLocked("size")
			}
			b.mu.Unlock()
		case <-b.stopChan:
			b.mu.Lock()
			if len(b.buffer) > 0 {
				b.flushLocked("shutdown")
			}
			b.mu.Unlock()
			return
		}
	}
}

func (b *LogBatcher) flushLocked(reason string) {
	count := len(b.buffer)
	if count == 0 {
		return
	}

	for _, entry := range b.buffer {
		fields := make([]zap.Field, 0, len(entry.Fields))
		for k, v := range entry.Fields {
			fields = append(fields, zap.Any(k, v))
		}
		switch entry.Level {
		case "debug":
			GetLogger().Debug(entry.Message, fields...)
		case "info":
			GetLogger().Info(entry.Message, fields...)
		case "warn":
			GetLogger().Warn(entry.Message, fields...)
		case "error":
			GetLogger().Error(entry.Message, fields...)
		default:
			GetLogger().Info(entry.Message, fields...)
		}
	}

	b.metrics.BatchesFlushed++
	b.metrics.TotalLogs += int64(count)
	b.metrics.AvgBatchSize = (b.metrics.AvgBatchSize*(b.metrics.BatchesFlushed-1) + int64(count)) / b.metrics.BatchesFlushed
	if reason == "timer" {
		b.metrics.FlushesByTimer++
	} else if reason == "size" {
		b.metrics.FlushesBySize++
	}

	b.buffer = b.buffer[:0]
}

func (b *LogBatcher) Add(entry LogEntry) bool {
	b.mu.Lock()
	defer b.mu.Unlock()

	if len(b.buffer) >= b.config.MaxQueueSize {
		b.metrics.DroppedLogs++
		return false
	}

	b.buffer = append(b.buffer, entry)

	if len(b.buffer) >= b.config.MaxBatchSize {
		select {
		case b.flushChan <- struct{}{}:
		default:
		}
	}

	return true
}

func (b *LogBatcher) AddBatch(entries []LogEntry) BatchLogResult {
	result := BatchLogResult{}

	for i, entry := range entries {
		if b.Add(entry) {
			result.SuccessCount++
		} else {
			result.FailedCount++
			result.FailedIndices = append(result.FailedIndices, i)
		}
	}

	return result
}

func (b *LogBatcher) Flush() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.flushLocked("manual")
}

func (b *LogBatcher) GetMetrics() BatcherMetrics {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.metrics
}

func (b *LogBatcher) Stop() {
	close(b.stopChan)
}

func BatchLog(entries []LogEntry) BatchLogResult {
	if batcher == nil {
		InitBatcher(DefaultBatcherConfig())
	}
	return batcher.AddBatch(entries)
}

func FlushLogs() {
	if batcher != nil {
		batcher.Flush()
	}
}

func GetBatcherMetrics() BatcherMetrics {
	if batcher == nil {
		return BatcherMetrics{}
	}
	return batcher.GetMetrics()
}

func GetLogger() *zap.Logger {
	if logger == nil {
		Init("logs/app.log")
	}
	return logger
}

func Info(msg string, fields ...zap.Field) {
	GetLogger().Info(msg, fields...)
}

func Error(msg string, fields ...zap.Field) {
	GetLogger().Error(msg, fields...)
}

func Warn(msg string, fields ...zap.Field) {
	GetLogger().Warn(msg, fields...)
}

func Debug(msg string, fields ...zap.Field) {
	GetLogger().Debug(msg, fields...)
}

func Sync() {
	if logger != nil {
		logger.Sync()
	}
	if batcher != nil {
		batcher.Stop()
	}
	if rotator != nil {
		rotator.Close()
	}
}

type AuditLogger struct {
	mu sync.Mutex
}

func NewAuditLogger() *AuditLogger {
	return &AuditLogger{}
}

func (a *AuditLogger) Log(user, action, resource string, details map[string]interface{}) {
	fields := []zap.Field{
		zap.String("user", user),
		zap.String("action", action),
		zap.String("resource", resource),
	}
	for k, v := range details {
		fields = append(fields, zap.Any(k, v))
	}
	Info(fmt.Sprintf("AUDIT: %s %s %s", user, action, resource), fields...)
}

func (a *AuditLogger) LogBatch(entries []struct {
	User     string                 `json:"user"`
	Action   string                 `json:"action"`
	Resource string                 `json:"resource"`
	Details  map[string]interface{} `json:"details"`
}) BatchLogResult {
	logEntries := make([]LogEntry, 0, len(entries))
	for _, e := range entries {
		fields := map[string]interface{}{
			"user":     e.User,
			"action":   e.Action,
			"resource": e.Resource,
		}
		for k, v := range e.Details {
			fields[k] = v
		}
		logEntries = append(logEntries, LogEntry{
			Level:     "info",
			Message:   fmt.Sprintf("AUDIT: %s %s %s", e.User, e.Action, e.Resource),
			Timestamp: time.Now().UTC(),
			Fields:    fields,
		})
	}
	return BatchLog(logEntries)
}
