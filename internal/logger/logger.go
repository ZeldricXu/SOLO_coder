package logger

import (
	"encoding/json"
	"fmt"
	"os"
	"sync"
	"time"

	"session130/pkg/models"
)

type Level int

const (
	DebugLevel Level = iota
	InfoLevel
	WarnLevel
	ErrorLevel
	FatalLevel
)

type logTask struct {
	entry   models.LogEntry
	level   Level
	message string
	fields  map[string]interface{}
}

type Logger struct {
	mu            sync.RWMutex
	level         Level
	service       string
	output        *os.File
	asyncMode     bool
	taskQueue     chan logTask
	workerCount   int
	minWorkers    int
	maxWorkers    int
	activeWorkers int
	stopChan      chan struct{}
	wg            sync.WaitGroup
	autoScale     bool
	batchSize     int
	flushInterval time.Duration
}

var (
	defaultLogger *Logger
	once          sync.Once
)

func NewLogger(service string, level Level) *Logger {
	l := &Logger{
		level:         level,
		service:       service,
		output:        os.Stdout,
		asyncMode:     true,
		taskQueue:     make(chan logTask, 100000),
		workerCount:   2,
		minWorkers:    1,
		maxWorkers:    16,
		activeWorkers: 0,
		stopChan:      make(chan struct{}),
		autoScale:     true,
		batchSize:     100,
		flushInterval: 100 * time.Millisecond,
	}
	if l.asyncMode {
		l.startWorkers(l.workerCount)
		go l.autoScaler()
	}
	return l
}

func NewLoggerWithConfig(service string, level Level, async bool, minWorkers, maxWorkers int, queueSize int) *Logger {
	if minWorkers < 1 {
		minWorkers = 1
	}
	if maxWorkers < minWorkers {
		maxWorkers = minWorkers
	}
	if queueSize < 1000 {
		queueSize = 1000
	}
	l := &Logger{
		level:         level,
		service:       service,
		output:        os.Stdout,
		asyncMode:     async,
		taskQueue:     make(chan logTask, queueSize),
		workerCount:   minWorkers,
		minWorkers:    minWorkers,
		maxWorkers:    maxWorkers,
		activeWorkers: 0,
		stopChan:      make(chan struct{}),
		autoScale:     true,
		batchSize:     100,
		flushInterval: 100 * time.Millisecond,
	}
	if l.asyncMode {
		l.startWorkers(l.workerCount)
		go l.autoScaler()
	}
	return l
}

func Init(service string, level Level) {
	once.Do(func() {
		defaultLogger = NewLogger(service, level)
	})
}

func Default() *Logger {
	if defaultLogger == nil {
		Init("default", InfoLevel)
	}
	return defaultLogger
}

func (l *Logger) startWorkers(count int) {
	for i := 0; i < count; i++ {
		l.wg.Add(1)
		l.activeWorkers++
		go l.worker()
	}
}

func (l *Logger) stopWorkers(count int) {
	for i := 0; i < count && l.activeWorkers > l.minWorkers; i++ {
		l.stopChan <- struct{}{}
		l.activeWorkers--
	}
}

func (l *Logger) worker() {
	defer l.wg.Done()

	batch := make([]logTask, 0, l.batchSize)
	ticker := time.NewTicker(l.flushInterval)
	defer ticker.Stop()

	for {
		select {
		case task := <-l.taskQueue:
			batch = append(batch, task)
			if len(batch) >= l.batchSize {
				l.processBatch(batch)
				batch = batch[:0]
			}
		case <-ticker.C:
			if len(batch) > 0 {
				l.processBatch(batch)
				batch = batch[:0]
			}
		case <-l.stopChan:
			if len(batch) > 0 {
				l.processBatch(batch)
			}
			return
		}
	}
}

func (l *Logger) processBatch(batch []logTask) {
	for _, task := range batch {
		l.processTask(task)
	}
}

func (l *Logger) processTask(task logTask) {
	entry := task.entry
	data, err := json.Marshal(entry)
	if err != nil {
		fmt.Fprintf(os.Stderr, "marshal log entry error: %v\n", err)
		return
	}

	l.mu.Lock()
	fmt.Fprintln(l.output, string(data))
	l.mu.Unlock()

	if task.level == FatalLevel {
		os.Exit(1)
	}
}

func (l *Logger) autoScaler() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		if !l.autoScale || !l.asyncMode {
			continue
		}

		queueLen := len(l.taskQueue)
		loadFactor := float64(queueLen) / float64(cap(l.taskQueue))

		if loadFactor > 0.7 && l.activeWorkers < l.maxWorkers {
			toAdd := (l.maxWorkers - l.activeWorkers) / 2
			if toAdd < 1 {
				toAdd = 1
			}
			l.startWorkers(toAdd)
			l.workerCount += toAdd
			l.logInternal(InfoLevel, "", "scaling up log workers", map[string]interface{}{
				"queue_length": queueLen,
				"load_factor":  loadFactor,
				"workers":      l.workerCount,
			})
		} else if loadFactor < 0.1 && l.activeWorkers > l.minWorkers {
			toStop := (l.activeWorkers - l.minWorkers) / 2
			if toStop > 0 {
				l.stopWorkers(toStop)
				l.workerCount -= toStop
				l.logInternal(InfoLevel, "", "scaling down log workers", map[string]interface{}{
					"queue_length": queueLen,
					"load_factor":  loadFactor,
					"workers":      l.workerCount,
				})
			}
		}
	}
}

func (l *Logger) logInternal(level Level, traceID, msg string, fields map[string]interface{}) {
	entry := models.LogEntry{
		Timestamp: time.Now(),
		Level:     levelToString(level),
		Service:   l.service,
		TraceID:   traceID,
		Message:   msg,
		Fields:    fields,
	}

	l.mu.Lock()
	defer l.mu.Unlock()

	data, err := json.Marshal(entry)
	if err != nil {
		fmt.Fprintf(os.Stderr, "marshal log entry error: %v\n", err)
		return
	}
	fmt.Fprintln(l.output, string(data))
}

func (l *Logger) SetLevel(level Level) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.level = level
}

func (l *Logger) SetAsyncMode(enabled bool, minWorkers, maxWorkers, queueSize, batchSize int, flushInterval time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()

	if minWorkers > 0 {
		l.minWorkers = minWorkers
	}
	if maxWorkers > 0 && maxWorkers >= l.minWorkers {
		l.maxWorkers = maxWorkers
	}
	if queueSize > 0 {
		l.taskQueue = make(chan logTask, queueSize)
	}
	if batchSize > 0 {
		l.batchSize = batchSize
	}
	if flushInterval > 0 {
		l.flushInterval = flushInterval
	}

	if enabled && !l.asyncMode {
		l.asyncMode = true
		l.startWorkers(l.workerCount)
		go l.autoScaler()
	} else if !enabled && l.asyncMode {
		l.asyncMode = false
		close(l.stopChan)
		l.wg.Wait()
		l.stopChan = make(chan struct{})
	}
}

func (l *Logger) log(level Level, traceID, msg string, fields map[string]interface{}) {
	l.mu.RLock()
	currentLevel := l.level
	asyncMode := l.asyncMode
	serviceName := l.service
	l.mu.RUnlock()

	if level < currentLevel {
		return
	}

	entry := models.LogEntry{
		Timestamp: time.Now(),
		Level:     levelToString(level),
		Service:   serviceName,
		TraceID:   traceID,
		Message:   msg,
		Fields:    fields,
	}

	if asyncMode {
		task := logTask{
			entry:   entry,
			level:   level,
			message: msg,
			fields:  fields,
		}

		select {
		case l.taskQueue <- task:
		default:
			l.processTask(task)
		}
	} else {
		l.processTask(logTask{entry: entry, level: level})
	}
}

func (l *Logger) Flush() {
	if !l.asyncMode {
		return
	}

	for len(l.taskQueue) > 0 {
		time.Sleep(10 * time.Millisecond)
	}
}

func (l *Logger) Shutdown() {
	l.mu.Lock()
	asyncMode := l.asyncMode
	l.mu.Unlock()

	if asyncMode {
		l.Flush()
		close(l.stopChan)
		l.wg.Wait()
	}
}

func (l *Logger) GetStats() map[string]interface{} {
	l.mu.RLock()
	defer l.mu.RUnlock()

	return map[string]interface{}{
		"async_mode":     l.asyncMode,
		"active_workers": l.activeWorkers,
		"min_workers":    l.minWorkers,
		"max_workers":    l.maxWorkers,
		"queue_size":     len(l.taskQueue),
		"queue_cap":      cap(l.taskQueue),
		"auto_scale":     l.autoScale,
		"batch_size":     l.batchSize,
	}
}

func levelToString(level Level) string {
	switch level {
	case DebugLevel:
		return "DEBUG"
	case InfoLevel:
		return "INFO"
	case WarnLevel:
		return "WARN"
	case ErrorLevel:
		return "ERROR"
	case FatalLevel:
		return "FATAL"
	default:
		return "UNKNOWN"
	}
}

func (l *Logger) Debug(traceID, msg string, fields map[string]interface{}) {
	l.log(DebugLevel, traceID, msg, fields)
}

func (l *Logger) Info(traceID, msg string, fields map[string]interface{}) {
	l.log(InfoLevel, traceID, msg, fields)
}

func (l *Logger) Warn(traceID, msg string, fields map[string]interface{}) {
	l.log(WarnLevel, traceID, msg, fields)
}

func (l *Logger) Error(traceID, msg string, fields map[string]interface{}) {
	l.log(ErrorLevel, traceID, msg, fields)
}

func (l *Logger) Fatal(traceID, msg string, fields map[string]interface{}) {
	l.log(FatalLevel, traceID, msg, fields)
	os.Exit(1)
}

func Debug(traceID, msg string, fields map[string]interface{}) {
	Default().Debug(traceID, msg, fields)
}

func Info(traceID, msg string, fields map[string]interface{}) {
	Default().Info(traceID, msg, fields)
}

func Warn(traceID, msg string, fields map[string]interface{}) {
	Default().Warn(traceID, msg, fields)
}

func Error(traceID, msg string, fields map[string]interface{}) {
	Default().Error(traceID, msg, fields)
}

func Fatal(traceID, msg string, fields map[string]interface{}) {
	Default().Fatal(traceID, msg, fields)
}

func Flush() {
	Default().Flush()
}

func Shutdown() {
	Default().Shutdown()
}

func GetStats() map[string]interface{} {
	return Default().GetStats()
}

type BatchLogEntry struct {
	Level   string                 `json:"level"`
	TraceID string                 `json:"trace_id,omitempty"`
	Message string                 `json:"message"`
	Fields  map[string]interface{} `json:"fields,omitempty"`
}

func (l *Logger) BatchLog(entries []BatchLogEntry) int {
	if len(entries) == 0 {
		return 0
	}

	successCount := 0

	for _, entry := range entries {
		level := stringToLevel(entry.Level)
		if level < l.level {
			continue
		}

		l.log(level, entry.TraceID, entry.Message, entry.Fields)
		successCount++
	}

	return successCount
}

func BatchLog(entries []BatchLogEntry) int {
	return Default().BatchLog(entries)
}

func stringToLevel(levelStr string) Level {
	switch levelStr {
	case "DEBUG", "debug":
		return DebugLevel
	case "INFO", "info":
		return InfoLevel
	case "WARN", "warn", "WARNING", "warning":
		return WarnLevel
	case "ERROR", "error":
		return ErrorLevel
	case "FATAL", "fatal":
		return FatalLevel
	default:
		return InfoLevel
	}
}

type AsyncBatchProcessor struct {
	logger     *Logger
	batchSize  int
	flushInt   time.Duration
	batchChan  chan BatchLogEntry
	stopChan   chan struct{}
	wg         sync.WaitGroup
}

func NewAsyncBatchProcessor(logger *Logger, batchSize int, flushInt time.Duration) *AsyncBatchProcessor {
	if batchSize <= 0 {
		batchSize = 100
	}
	if flushInt <= 0 {
		flushInt = time.Second
	}

	abp := &AsyncBatchProcessor{
		logger:    logger,
		batchSize: batchSize,
		flushInt:  flushInt,
		batchChan: make(chan BatchLogEntry, 10000),
		stopChan:  make(chan struct{}),
	}

	abp.wg.Add(1)
	go abp.run()

	return abp
}

func (abp *AsyncBatchProcessor) run() {
	defer abp.wg.Done()

	batch := make([]BatchLogEntry, 0, abp.batchSize)
	ticker := time.NewTicker(abp.flushInt)
	defer ticker.Stop()

	for {
		select {
		case entry := <-abp.batchChan:
			batch = append(batch, entry)
			if len(batch) >= abp.batchSize {
				abp.logger.BatchLog(batch)
				batch = batch[:0]
			}
		case <-ticker.C:
			if len(batch) > 0 {
				abp.logger.BatchLog(batch)
				batch = batch[:0]
			}
		case <-abp.stopChan:
			if len(batch) > 0 {
				abp.logger.BatchLog(batch)
			}
			return
		}
	}
}

func (abp *AsyncBatchProcessor) Log(entry BatchLogEntry) {
	select {
	case abp.batchChan <- entry:
	default:
		abp.logger.BatchLog([]BatchLogEntry{entry})
	}
}

func (abp *AsyncBatchProcessor) Flush() {
	for len(abp.batchChan) > 0 {
		time.Sleep(10 * time.Millisecond)
	}
}

func (abp *AsyncBatchProcessor) Stop() {
	close(abp.stopChan)
	abp.wg.Wait()
}

func (abp *AsyncBatchProcessor) GetStats() map[string]interface{} {
	return map[string]interface{}{
		"batch_size":   abp.batchSize,
		"flush_interval": abp.flushInt.String(),
		"queue_size":   len(abp.batchChan),
		"queue_cap":    cap(abp.batchChan),
	}
}
