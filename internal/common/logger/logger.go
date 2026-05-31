package logger

import (
	"context"
	"sync"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

type contextKey string

const LoggerKey contextKey = "logger"

var (
	globalLogger *zap.Logger
	levelMap     = make(map[string]zapcore.Level)
	levelMutex   sync.RWMutex
)

func init() {
	config := zap.NewProductionConfig()
	config.Level = zap.NewAtomicLevelAt(zap.InfoLevel)
	globalLogger, _ = config.Build()
}

func Init(level string) error {
	zapLevel, err := zapcore.ParseLevel(level)
	if err != nil {
		return err
	}
	config := zap.NewProductionConfig()
	config.Level = zap.NewAtomicLevelAt(zapLevel)
	config.EncoderConfig.TimeKey = "timestamp"
	config.EncoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder

	globalLogger, err = config.Build()
	if err != nil {
		return err
	}
	return nil
}

func GetLogger() *zap.Logger {
	return globalLogger
}

func GetComponentLogger(component string) *zap.Logger {
	level := getComponentLevel(component)
	levelMutex.RLock()
	defer levelMutex.RUnlock()

	core := zapcore.NewCore(
		zapcore.NewJSONEncoder(zap.NewProductionEncoderConfig()),
		zapcore.AddSync(globalLogger.Core().(interface{ Sync() }).(zapcore.WriteSyncer)),
		level,
	)
	return zap.New(core).With(zap.String("component", component))
}

func SetComponentLevel(component, level string) error {
	zapLevel, err := zapcore.ParseLevel(level)
	if err != nil {
		return err
	}
	levelMutex.Lock()
	defer levelMutex.Unlock()
	levelMap[component] = zapLevel
	return nil
}

func GetComponentLevel(component string) zapcore.Level {
	return getComponentLevel(component)
}

func getComponentLevel(component string) zapcore.Level {
	levelMutex.RLock()
	defer levelMutex.RUnlock()
	if level, exists := levelMap[component]; exists {
		return level
	}
	return getGlobalLevel()
}

func getGlobalLevel() zapcore.Level {
	for level := zapcore.DebugLevel; level <= zapcore.FatalLevel; level++ {
		if globalLogger.Core().Enabled(level) {
			return level
		}
	}
	return zapcore.InfoLevel
}

func GetAllComponentLevels() map[string]string {
	levelMutex.RLock()
	defer levelMutex.RUnlock()
	result := make(map[string]string)
	for component, level := range levelMap {
		result[component] = level.String()
	}
	return result
}

func FromContext(ctx context.Context) *zap.Logger {
	if logger, ok := ctx.Value(LoggerKey).(*zap.Logger); ok {
		return logger
	}
	return globalLogger
}

func WithContext(ctx context.Context, logger *zap.Logger) context.Context {
	return context.WithValue(ctx, LoggerKey, logger)
}

func Debug(msg string, fields ...zap.Field) {
	globalLogger.Debug(msg, fields...)
}

func Info(msg string, fields ...zap.Field) {
	globalLogger.Info(msg, fields...)
}

func Warn(msg string, fields ...zap.Field) {
	globalLogger.Warn(msg, fields...)
}

func Error(msg string, fields ...zap.Field) {
	globalLogger.Error(msg, fields...)
}

func Fatal(msg string, fields ...zap.Field) {
	globalLogger.Fatal(msg, fields...)
}
