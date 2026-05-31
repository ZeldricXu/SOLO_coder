package logger

import (
	"os"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

var defaultLogger *zap.Logger

func Init(level string, serviceName string) {
	config := zap.NewProductionConfig()
	config.EncoderConfig.TimeKey = "timestamp"
	config.EncoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder
	config.InitialFields = map[string]interface{}{
		"service": serviceName,
	}

	if lvl, err := zapcore.ParseLevel(level); err == nil {
		config.Level = zap.NewAtomicLevelAt(lvl)
	}

	var err error
	defaultLogger, err = config.Build()
	if err != nil {
		defaultLogger = zap.NewExample()
	}
}

func L() *zap.Logger {
	if defaultLogger == nil {
		defaultLogger = zap.NewExample()
	}
	return defaultLogger
}

func Sync() {
	if defaultLogger != nil {
		_ = defaultLogger.Sync()
	}
}

func WithContext(ctxMap map[string]interface{}) *zap.Logger {
	fields := make([]zap.Field, 0, len(ctxMap))
	for k, v := range ctxMap {
		fields = append(fields, zap.Any(k, v))
	}
	return L().With(fields...)
}
