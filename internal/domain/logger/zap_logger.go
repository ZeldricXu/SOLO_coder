package logger

import (
	"os"
	"sync"
	"sync/atomic"

	"github.com/dataplatform/engine/internal/domain"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

type ZapLogger struct {
	level  atomic.Int32
	logger *zap.Logger
	mu     sync.RWMutex
}

var (
	instance domain.Logger
	once     sync.Once
)

func New(level domain.LogLevel, format, output, filePath string) domain.Logger {
	once.Do(func() {
		instance = newZapLogger(level, format, output, filePath)
	})
	return instance
}

func newZapLogger(level domain.LogLevel, format, output, filePath string) *ZapLogger {
	l := &ZapLogger{}
	l.level.Store(int32(level))

	var encoder zapcore.Encoder
	if format == "json" {
		encoderCfg := zap.NewProductionEncoderConfig()
		encoderCfg.TimeKey = "timestamp"
		encoderCfg.EncodeTime = zapcore.ISO8601TimeEncoder
		encoder = zapcore.NewJSONEncoder(encoderCfg)
	} else {
		encoderCfg := zap.NewDevelopmentEncoderConfig()
		encoderCfg.EncodeLevel = zapcore.CapitalColorLevelEncoder
		encoder = zapcore.NewConsoleEncoder(encoderCfg)
	}

	var writeSyncer zapcore.WriteSyncer
	if output == "file" && filePath != "" {
		file, err := os.OpenFile(filePath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
		if err != nil {
			writeSyncer = zapcore.AddSync(os.Stdout)
		} else {
			writeSyncer = zapcore.AddSync(file)
		}
	} else {
		writeSyncer = zapcore.AddSync(os.Stdout)
	}

	core := zapcore.NewCore(encoder, writeSyncer, zapcore.Level(level))
	l.logger = zap.New(core, zap.AddCaller(), zap.AddCallerSkip(1))
	return l
}

func (l *ZapLogger) Debug(msg string, fields ...domain.Field) {
	if domain.LogLevel(l.level.Load()) <= domain.LogLevelDebug {
		l.logger.Debug(msg, l.convertFields(fields)...)
	}
}

func (l *ZapLogger) Info(msg string, fields ...domain.Field) {
	if domain.LogLevel(l.level.Load()) <= domain.LogLevelInfo {
		l.logger.Info(msg, l.convertFields(fields)...)
	}
}

func (l *ZapLogger) Warn(msg string, fields ...domain.Field) {
	if domain.LogLevel(l.level.Load()) <= domain.LogLevelWarn {
		l.logger.Warn(msg, l.convertFields(fields)...)
	}
}

func (l *ZapLogger) Error(msg string, fields ...domain.Field) {
	if domain.LogLevel(l.level.Load()) <= domain.LogLevelError {
		l.logger.Error(msg, l.convertFields(fields)...)
	}
}

func (l *ZapLogger) Fatal(msg string, fields ...domain.Field) {
	if domain.LogLevel(l.level.Load()) <= domain.LogLevelFatal {
		l.logger.Fatal(msg, l.convertFields(fields)...)
	}
}

func (l *ZapLogger) SetLevel(level domain.LogLevel) {
	l.level.Store(int32(level))
}

func (l *ZapLogger) GetLevel() domain.LogLevel {
	return domain.LogLevel(l.level.Load())
}

func (l *ZapLogger) With(fields ...domain.Field) domain.Logger {
	newLogger := l.logger.With(l.convertFields(fields)...)
	return &ZapLogger{
		logger: newLogger,
	}
}

func (l *ZapLogger) Sync() error {
	return l.logger.Sync()
}

func (l *ZapLogger) convertFields(fields []domain.Field) []zap.Field {
	result := make([]zap.Field, 0, len(fields))
	for _, f := range fields {
		switch v := f.Value.(type) {
		case string:
			result = append(result, zap.String(f.Key, v))
		case int:
			result = append(result, zap.Int(f.Key, v))
		case int64:
			result = append(result, zap.Int64(f.Key, v))
		case float64:
			result = append(result, zap.Float64(f.Key, v))
		case bool:
			result = append(result, zap.Bool(f.Key, v))
		case error:
			result = append(result, zap.Error(v))
		default:
			result = append(result, zap.Any(f.Key, v))
		}
	}
	return result
}

func String(key, value string) domain.Field {
	return domain.Field{Key: key, Value: value}
}

func Int(key string, value int) domain.Field {
	return domain.Field{Key: key, Value: value}
}

func Int64(key string, value int64) domain.Field {
	return domain.Field{Key: key, Value: value}
}

func Float64(key string, value float64) domain.Field {
	return domain.Field{Key: key, Value: value}
}

func Bool(key string, value bool) domain.Field {
	return domain.Field{Key: key, Value: value}
}

func Error(err error) domain.Field {
	return domain.Field{Key: "error", Value: err}
}

func Any(key string, value interface{}) domain.Field {
	return domain.Field{Key: key, Value: value}
}
