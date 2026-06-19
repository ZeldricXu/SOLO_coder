package util

import (
	"os"
	"sync"

	"github.com/df1-96/experiment/internal/config"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"gopkg.in/natefinch/lumberjack.v2"
)

var (
	logger     *zap.Logger
	sugar      *zap.SugaredLogger
	loggerOnce sync.Once
)

func InitLogger(cfg config.LogConfig) error {
	var initErr error
	loggerOnce.Do(func() {
		level := toZapLevel(cfg.Level)

		encoder := getEncoder(cfg.Format)

		var cores []zapcore.Core

		if cfg.OutputPath == "stdout" || cfg.OutputPath == "" {
			stdoutWriter := zapcore.AddSync(os.Stdout)
			cores = append(cores, zapcore.NewCore(encoder, stdoutWriter, level))
		} else {
			fileWriter := zapcore.AddSync(&lumberjack.Logger{
				Filename:   cfg.OutputPath,
				MaxSize:    cfg.MaxSize,
				MaxBackups: cfg.MaxBackups,
				MaxAge:     cfg.MaxAge,
				Compress:   cfg.Compress,
			})
			cores = append(cores, zapcore.NewCore(encoder, fileWriter, level))

			stdoutWriter := zapcore.AddSync(os.Stdout)
			cores = append(cores, zapcore.NewCore(encoder, stdoutWriter, level))
		}

		core := zapcore.NewTee(cores...)

		logger = zap.New(core,
			zap.AddCaller(),
			zap.AddCallerSkip(1),
			zap.AddStacktrace(zapcore.ErrorLevel),
		)
		sugar = logger.Sugar()
	})
	return initErr
}

func Logger() *zap.Logger {
	if logger == nil {
		defaultCfg := config.LogConfig{
			Level:      config.LogLevelInfo,
			Format:     "json",
			OutputPath: "stdout",
		}
		if err := InitLogger(defaultCfg); err != nil {
			panic(err)
		}
	}
	return logger
}

func Sugar() *zap.SugaredLogger {
	if sugar == nil {
		_ = Logger()
	}
	return sugar
}

func Debug(msg string, fields ...zap.Field) {
	Logger().Debug(msg, fields...)
}

func Info(msg string, fields ...zap.Field) {
	Logger().Info(msg, fields...)
}

func Warn(msg string, fields ...zap.Field) {
	Logger().Warn(msg, fields...)
}

func Error(msg string, fields ...zap.Field) {
	Logger().Error(msg, fields...)
}

func Panic(msg string, fields ...zap.Field) {
	Logger().Panic(msg, fields...)
}

func Fatal(msg string, fields ...zap.Field) {
	Logger().Fatal(msg, fields...)
}

func Debugf(format string, args ...interface{}) {
	Sugar().Debugf(format, args...)
}

func Infof(format string, args ...interface{}) {
	Sugar().Infof(format, args...)
}

func Warnf(format string, args ...interface{}) {
	Sugar().Warnf(format, args...)
}

func Errorf(format string, args ...interface{}) {
	Sugar().Errorf(format, args...)
}

func Panicf(format string, args ...interface{}) {
	Sugar().Panicf(format, args...)
}

func Fatalf(format string, args ...interface{}) {
	Sugar().Fatalf(format, args...)
}

func With(fields ...zap.Field) *zap.Logger {
	return Logger().With(fields...)
}

func Sync() error {
	if logger != nil {
		return logger.Sync()
	}
	return nil
}

func toZapLevel(level config.LogLevel) zapcore.Level {
	switch level {
	case config.LogLevelDebug:
		return zapcore.DebugLevel
	case config.LogLevelInfo:
		return zapcore.InfoLevel
	case config.LogLevelWarn:
		return zapcore.WarnLevel
	case config.LogLevelError:
		return zapcore.ErrorLevel
	default:
		return zapcore.InfoLevel
	}
}

func getEncoder(format string) zapcore.Encoder {
	encoderConfig := zap.NewProductionEncoderConfig()
	encoderConfig.TimeKey = "time"
	encoderConfig.LevelKey = "level"
	encoderConfig.CallerKey = "caller"
	encoderConfig.MessageKey = "msg"
	encoderConfig.StacktraceKey = "stacktrace"
	encoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder
	encoderConfig.EncodeLevel = zapcore.CapitalLevelEncoder
	encoderConfig.EncodeCaller = zapcore.ShortCallerEncoder

	if format == "console" {
		return zapcore.NewConsoleEncoder(encoderConfig)
	}
	return zapcore.NewJSONEncoder(encoderConfig)
}
