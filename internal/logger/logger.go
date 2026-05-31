package logger

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"runtime"
	"sync"
	"time"

	"github.com/solocoder/backup-engine/pkg/common"
)

type Level int

const (
	DebugLevel Level = iota
	InfoLevel
	WarnLevel
	ErrorLevel
	FatalLevel
)

func (l Level) String() string {
	switch l {
	case DebugLevel:
		return "debug"
	case InfoLevel:
		return "info"
	case WarnLevel:
		return "warn"
	case ErrorLevel:
		return "error"
	case FatalLevel:
		return "fatal"
	default:
		return "unknown"
	}
}

type Config struct {
	Level      Level
	Output     io.Writer
	Service    string
	JSONFormat bool
	Caller     bool
}

type Logger struct {
	config Config
	mu     sync.Mutex
}

var (
	defaultLogger *Logger
	once          sync.Once
)

func Default() *Logger {
	once.Do(func() {
		defaultLogger = New(Config{
			Level:      InfoLevel,
			Output:     os.Stdout,
			Service:    "backup-engine",
			JSONFormat: true,
			Caller:     true,
		})
	})
	return defaultLogger
}

func New(config Config) *Logger {
	if config.Output == nil {
		config.Output = os.Stdout
	}
	return &Logger{
		config: config,
	}
}

func (l *Logger) SetLevel(level Level) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.config.Level = level
}

func (l *Logger) GetLevel() Level {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.config.Level
}

func (l *Logger) Debug(msg string, fields ...map[string]interface{}) {
	l.log(DebugLevel, msg, fields...)
}

func (l *Logger) Info(msg string, fields ...map[string]interface{}) {
	l.log(InfoLevel, msg, fields...)
}

func (l *Logger) Warn(msg string, fields ...map[string]interface{}) {
	l.log(WarnLevel, msg, fields...)
}

func (l *Logger) Error(msg string, fields ...map[string]interface{}) {
	l.log(ErrorLevel, msg, fields...)
}

func (l *Logger) Fatal(msg string, fields ...map[string]interface{}) {
	l.log(FatalLevel, msg, fields...)
	os.Exit(1)
}

func (l *Logger) WithTrace(traceID string) *Logger {
	return l.WithField("trace_id", traceID)
}

func (l *Logger) WithField(key string, value interface{}) *Logger {
	newFields := make(map[string]interface{})
	newFields[key] = value
	return &Logger{
		config: l.config,
	}
}

func (l *Logger) log(level Level, msg string, fields ...map[string]interface{}) {
	if level < l.config.Level {
		return
	}

	entry := common.LogEntry{
		ID:        common.NewID(),
		Timestamp: time.Now(),
		Level:     level.String(),
		Message:   msg,
		Service:   l.config.Service,
		Fields:    make(map[string]interface{}),
	}

	if len(fields) > 0 {
		for _, f := range fields {
			for k, v := range f {
				entry.Fields[k] = v
			}
		}
	}

	if traceID, ok := entry.Fields["trace_id"].(string); ok {
		entry.TraceID = traceID
	}

	if l.config.Caller {
		_, file, line, ok := runtime.Caller(3)
		if ok {
			entry.Fields["caller"] = fmt.Sprintf("%s:%d", file, line)
		}
	}

	l.mu.Lock()
	defer l.mu.Unlock()

	if l.config.JSONFormat {
		data, err := json.Marshal(entry)
		if err != nil {
			fmt.Fprintf(l.config.Output, "logger marshal error: %v\n", err)
			return
		}
		fmt.Fprintln(l.config.Output, string(data))
	} else {
		fmt.Fprintf(l.config.Output, "[%s] %s: %s %v\n",
			entry.Timestamp.Format(time.RFC3339),
			level.String(),
			msg,
			entry.Fields,
		)
	}
}

func Debug(msg string, fields ...map[string]interface{}) {
	Default().Debug(msg, fields...)
}

func Info(msg string, fields ...map[string]interface{}) {
	Default().Info(msg, fields...)
}

func Warn(msg string, fields ...map[string]interface{}) {
	Default().Warn(msg, fields...)
}

func Error(msg string, fields ...map[string]interface{}) {
	Default().Error(msg, fields...)
}

func Fatal(msg string, fields ...map[string]interface{}) {
	Default().Fatal(msg, fields...)
}

func WithTrace(traceID string) *Logger {
	return Default().WithTrace(traceID)
}
