package logging

import (
	"context"
	"fmt"
	"os"
	"sync"
	"sync/atomic"

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

type Logger interface {
	Debug(ctx context.Context, msg string, fields ...zap.Field)
	Info(ctx context.Context, msg string, fields ...zap.Field)
	Warn(ctx context.Context, msg string, fields ...zap.Field)
	Error(ctx context.Context, msg string, fields ...zap.Field)
	Fatal(ctx context.Context, msg string, fields ...zap.Field)
	SetLevel(level LogLevel) error
	GetLevel() LogLevel
	Sync() error
	With(fields ...zap.Field) Logger
}

type ZapLogger struct {
	mu          sync.RWMutex
	logger      *zap.Logger
	atomicLevel zap.AtomicLevel
	level       atomic.Value
}

var (
	defaultLogger *ZapLogger
	once          sync.Once
)

func NewLogger(level LogLevel, filePath string) (*ZapLogger, error) {
	zapLevel := toZapLevel(level)
	atomicLevel := zap.NewAtomicLevelAt(zapLevel)

	var cores []zapcore.Core

	consoleEncoder := zapcore.NewJSONEncoder(zapcore.EncoderConfig{
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
	})

	consoleCore := zapcore.NewCore(consoleEncoder, zapcore.AddSync(os.Stdout), atomicLevel)
	cores = append(cores, consoleCore)

	if filePath != "" {
		file, err := os.OpenFile(filePath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
		if err != nil {
			return nil, fmt.Errorf("failed to open log file: %w", err)
		}
		fileCore := zapcore.NewCore(consoleEncoder, zapcore.AddSync(file), atomicLevel)
		cores = append(cores, fileCore)
	}

	core := zapcore.NewTee(cores...)
	logger := zap.New(core, zap.AddCaller(), zap.AddCallerSkip(1))

	z := &ZapLogger{
		logger:      logger,
		atomicLevel: atomicLevel,
	}
	z.level.Store(level)

	return z, nil
}

func InitDefaultLogger(level LogLevel, filePath string) error {
	var err error
	once.Do(func() {
		defaultLogger, err = NewLogger(level, filePath)
	})
	return err
}

func GetDefaultLogger() Logger {
	if defaultLogger == nil {
		_ = InitDefaultLogger(LevelInfo, "")
	}
	return defaultLogger
}

func toZapLevel(level LogLevel) zapcore.Level {
	switch level {
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

func fromZapLevel(level zapcore.Level) LogLevel {
	switch level {
	case zapcore.DebugLevel:
		return LevelDebug
	case zapcore.InfoLevel:
		return LevelInfo
	case zapcore.WarnLevel:
		return LevelWarn
	case zapcore.ErrorLevel:
		return LevelError
	case zapcore.FatalLevel:
		return LevelFatal
	default:
		return LevelInfo
	}
}

func (l *ZapLogger) getTraceFields(ctx context.Context) []zap.Field {
	if ctx == nil {
		return nil
	}
	var fields []zap.Field
	if traceID, ok := ctx.Value("traceID").(string); ok {
		fields = append(fields, zap.String("traceID", traceID))
	}
	if requestID, ok := ctx.Value("requestID").(string); ok {
		fields = append(fields, zap.String("requestID", requestID))
	}
	if userID, ok := ctx.Value("userID").(string); ok {
		fields = append(fields, zap.String("userID", userID))
	}
	return fields
}

func (l *ZapLogger) Debug(ctx context.Context, msg string, fields ...zap.Field) {
	l.mu.RLock()
	defer l.mu.RUnlock()
	traceFields := l.getTraceFields(ctx)
	allFields := append(traceFields, fields...)
	l.logger.Debug(msg, allFields...)
}

func (l *ZapLogger) Info(ctx context.Context, msg string, fields ...zap.Field) {
	l.mu.RLock()
	defer l.mu.RUnlock()
	traceFields := l.getTraceFields(ctx)
	allFields := append(traceFields, fields...)
	l.logger.Info(msg, allFields...)
}

func (l *ZapLogger) Warn(ctx context.Context, msg string, fields ...zap.Field) {
	l.mu.RLock()
	defer l.mu.RUnlock()
	traceFields := l.getTraceFields(ctx)
	allFields := append(traceFields, fields...)
	l.logger.Warn(msg, allFields...)
}

func (l *ZapLogger) Error(ctx context.Context, msg string, fields ...zap.Field) {
	l.mu.RLock()
	defer l.mu.RUnlock()
	traceFields := l.getTraceFields(ctx)
	allFields := append(traceFields, fields...)
	l.logger.Error(msg, allFields...)
}

func (l *ZapLogger) Fatal(ctx context.Context, msg string, fields ...zap.Field) {
	l.mu.RLock()
	defer l.mu.RUnlock()
	traceFields := l.getTraceFields(ctx)
	allFields := append(traceFields, fields...)
	l.logger.Fatal(msg, allFields...)
}

func (l *ZapLogger) SetLevel(level LogLevel) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	zapLevel := toZapLevel(level)
	l.atomicLevel.SetLevel(zapLevel)
	l.level.Store(level)
	return nil
}

func (l *ZapLogger) GetLevel() LogLevel {
	return l.level.Load().(LogLevel)
}

func (l *ZapLogger) Sync() error {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.logger.Sync()
}

func (l *ZapLogger) With(fields ...zap.Field) Logger {
	l.mu.RLock()
	defer l.mu.RUnlock()
	newLogger := l.logger.With(fields...)
	return &ZapLogger{
		logger:      newLogger,
		atomicLevel: l.atomicLevel,
		level:       l.level,
	}
}

func Debug(ctx context.Context, msg string, fields ...zap.Field) {
	GetDefaultLogger().Debug(ctx, msg, fields...)
}

func Info(ctx context.Context, msg string, fields ...zap.Field) {
	GetDefaultLogger().Info(ctx, msg, fields...)
}

func Warn(ctx context.Context, msg string, fields ...zap.Field) {
	GetDefaultLogger().Warn(ctx, msg, fields...)
}

func Error(ctx context.Context, msg string, fields ...zap.Field) {
	GetDefaultLogger().Error(ctx, msg, fields...)
}

func Fatal(ctx context.Context, msg string, fields ...zap.Field) {
	GetDefaultLogger().Fatal(ctx, msg, fields...)
}

func SetLevel(level LogLevel) error {
	return GetDefaultLogger().SetLevel(level)
}

func GetLevel() LogLevel {
	return GetDefaultLogger().GetLevel()
}

func Sync() error {
	return GetDefaultLogger().Sync()
}

func Init(level string, format string) error {
	return InitDefaultLogger(LogLevel(level), "")
}

func SetLevelString(level string) error {
	return SetLevel(LogLevel(level))
}
