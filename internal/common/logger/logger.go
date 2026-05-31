package logger

import (
	"os"
	"sync"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

var (
	once   sync.Once
	logger *zap.Logger
	sugar  *zap.SugaredLogger
)

func Init(logLevel string) {
	once.Do(func() {
		level := zapcore.InfoLevel
		switch logLevel {
		case "debug":
			level = zapcore.DebugLevel
		case "warn":
			level = zapcore.WarnLevel
		case "error":
			level = zapcore.ErrorLevel
		}

		encoderConfig := zapcore.EncoderConfig{
			TimeKey:        "timestamp",
			LevelKey:       "level",
			NameKey:        "logger",
			CallerKey:      "caller",
			MessageKey:     "message",
			StacktraceKey:  "stacktrace",
			LineEnding:     zapcore.DefaultLineEnding,
			EncodeLevel:    zapcore.LowercaseLevelEncoder,
			EncodeTime:     zapcore.ISO8601TimeEncoder,
			EncodeDuration: zapcore.SecondsDurationEncoder,
			EncodeCaller:   zapcore.ShortCallerEncoder,
		}

		core := zapcore.NewCore(
			zapcore.NewJSONEncoder(encoderConfig),
			zapcore.AddSync(os.Stdout),
			level,
		)

		logger = zap.New(core, zap.AddCaller(), zap.AddStacktrace(zapcore.ErrorLevel))
		sugar = logger.Sugar()
	})
}

func GetLogger() *zap.Logger {
	if logger == nil {
		Init("info")
	}
	return logger
}

func GetSugaredLogger() *zap.SugaredLogger {
	if sugar == nil {
		Init("info")
	}
	return sugar
}

func Debug(msg string, fields ...zap.Field) {
	GetLogger().Debug(msg, fields...)
}

func Info(msg string, fields ...zap.Field) {
	GetLogger().Info(msg, fields...)
}

func Warn(msg string, fields ...zap.Field) {
	GetLogger().Warn(msg, fields...)
}

func Error(msg string, fields ...zap.Field) {
	GetLogger().Error(msg, fields...)
}

func Fatal(msg string, fields ...zap.Field) {
	GetLogger().Fatal(msg, fields...)
}

func Debugf(format string, args ...interface{}) {
	GetSugaredLogger().Debugf(format, args...)
}

func Infof(format string, args ...interface{}) {
	GetSugaredLogger().Infof(format, args...)
}

func Warnf(format string, args ...interface{}) {
	GetSugaredLogger().Warnf(format, args...)
}

func Errorf(format string, args ...interface{}) {
	GetSugaredLogger().Errorf(format, args...)
}

func Fatalf(format string, args ...interface{}) {
	GetSugaredLogger().Fatalf(format, args...)
}
