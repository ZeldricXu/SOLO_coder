package logger

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"

	"netproxy/internal/config"
)

type LogLevel int

const (
	LevelDebug LogLevel = iota
	LevelInfo
	LevelWarn
	LevelError
)

type AccessLogEntry struct {
	Timestamp      time.Time `json:"timestamp"`
	RequestID      string    `json:"request_id"`
	Protocol       string    `json:"protocol"`
	SourceIP       string    `json:"source_ip"`
	TargetHost     string    `json:"target_host"`
	TargetPort     int       `json:"target_port"`
	RequestSize    int64     `json:"request_size"`
	ResponseSize   int64     `json:"response_size"`
	Latency        int64     `json:"latency_ms"`
	StatusCode     int       `json:"status_code"`
	Error          string    `json:"error,omitempty"`
	RuleID         string    `json:"rule_id,omitempty"`
}

type Logger struct {
	config      *config.LogConfig
	logFile     *os.File
	logWriter   io.Writer
	accessFile  *os.File
	accessWriter io.Writer
	level       LogLevel
	mu          sync.Mutex
	logChan     chan AccessLogEntry
	doneChan    chan struct{}
	wg          sync.WaitGroup
}

var (
	instance *Logger
	once     sync.Once
)

func NewLogger(cfg *config.LogConfig) (*Logger, error) {
	logger := &Logger{
		config:   cfg,
		logChan:  make(chan AccessLogEntry, 10000),
		doneChan: make(chan struct{}),
	}

	switch cfg.Level {
	case "debug":
		logger.level = LevelDebug
	case "info":
		logger.level = LevelInfo
	case "warn":
		logger.level = LevelWarn
	case "error":
		logger.level = LevelError
	default:
		logger.level = LevelInfo
	}

	if cfg.FilePath != "" {
		dir := filepath.Dir(cfg.FilePath)
		if err := os.MkdirAll(dir, 0755); err != nil {
			return nil, fmt.Errorf("failed to create log directory: %w", err)
		}

		file, err := os.OpenFile(cfg.FilePath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
		if err != nil {
			return nil, fmt.Errorf("failed to open log file: %w", err)
		}
		logger.logFile = file
		logger.logWriter = io.MultiWriter(os.Stdout, file)

		accessPath := filepath.Join(dir, "access.log")
		accessFile, err := os.OpenFile(accessPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
		if err != nil {
			return nil, fmt.Errorf("failed to open access log file: %w", err)
		}
		logger.accessFile = accessFile
		logger.accessWriter = io.MultiWriter(os.Stdout, accessFile)
	} else {
		logger.logWriter = os.Stdout
		logger.accessWriter = os.Stdout
	}

	log.SetOutput(logger.logWriter)
	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)

	logger.wg.Add(1)
	go logger.processAccessLogs()

	return logger, nil
}

func (l *Logger) processAccessLogs() {
	defer l.wg.Done()

	for {
		select {
		case entry := <-l.logChan:
			l.writeAccessLog(entry)
		case <-l.doneChan:
			for entry := range l.logChan {
				l.writeAccessLog(entry)
			}
			return
		}
	}
}

func (l *Logger) writeAccessLog(entry AccessLogEntry) {
	data, err := json.Marshal(entry)
	if err != nil {
		log.Printf("failed to marshal access log: %v", err)
		return
	}

	l.mu.Lock()
	defer l.mu.Unlock()

	fmt.Fprintln(l.accessWriter, string(data))
}

func (l *Logger) Close() {
	close(l.doneChan)
	l.wg.Wait()

	if l.logFile != nil {
		l.logFile.Close()
	}
	if l.accessFile != nil {
		l.accessFile.Close()
	}
}

func (l *Logger) Debug(format string, v ...interface{}) {
	if l.level <= LevelDebug {
		log.Printf("[DEBUG] "+format, v...)
	}
}

func (l *Logger) Info(format string, v ...interface{}) {
	if l.level <= LevelInfo {
		log.Printf("[INFO] "+format, v...)
	}
}

func (l *Logger) Warn(format string, v ...interface{}) {
	if l.level <= LevelWarn {
		log.Printf("[WARN] "+format, v...)
	}
}

func (l *Logger) Error(format string, v ...interface{}) {
	if l.level <= LevelError {
		log.Printf("[ERROR] "+format, v...)
	}
}

func (l *Logger) Access(entry AccessLogEntry) {
	entry.Timestamp = time.Now()
	select {
	case l.logChan <- entry:
	default:
		l.Warn("access log channel full, dropping entry")
	}
}

func InitLogger(cfg *config.LogConfig) error {
	var err error
	once.Do(func() {
		instance, err = NewLogger(cfg)
	})
	return err
}

func GetLogger() *Logger {
	if instance == nil {
		log.Fatal("logger not initialized")
	}
	return instance
}

func Debug(format string, v ...interface{}) {
	if instance != nil {
		instance.Debug(format, v...)
	}
}

func Info(format string, v ...interface{}) {
	if instance != nil {
		instance.Info(format, v...)
	}
}

func Warn(format string, v ...interface{}) {
	if instance != nil {
		instance.Warn(format, v...)
	}
}

func Error(format string, v ...interface{}) {
	if instance != nil {
		instance.Error(format, v...)
	}
}

func Access(entry AccessLogEntry) {
	if instance != nil {
		instance.Access(entry)
	}
}

func Close() {
	if instance != nil {
		instance.Close()
	}
}
