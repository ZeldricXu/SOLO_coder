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

type Config struct {
	Level      string `json:"level"`
	Format     string `json:"format"`
	OutputFile string `json:"output_file"`
	Debug      bool   `json:"debug"`
}

func Init(cfg *Config) {
	once.Do(func() {
		if cfg == nil {
			cfg = &Config{
				Level:  "info",
				Format: "json",
				Debug:  false,
			}
		}

		level := parseLevel(cfg.Level)
		encoder := getEncoder(cfg.Format)

		var cores []zapcore.Core

		consoleEncoder := zapcore.NewConsoleEncoder(zap.NewDevelopmentEncoderConfig())
		cores = append(cores, zapcore.NewCore(consoleEncoder, zapcore.Lock(os.Stdout), level))

		if cfg.OutputFile != "" {
			file, err := os.OpenFile(cfg.OutputFile, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
			if err == nil {
				cores = append(cores, zapcore.NewCore(encoder, zapcore.Lock(file), level))
			}
		}

		core := zapcore.NewTee(cores...)

		options := []zap.Option{
			zap.AddCaller(),
			zap.AddCallerSkip(1),
			zap.AddStacktrace(zapcore.ErrorLevel),
		}

		if cfg.Debug {
			options = append(options, zap.Development())
		}

		logger = zap.New(core, options...)
		sugar = logger.Sugar()
	})
}

func parseLevel(level string) zapcore.Level {
	switch level {
	case "debug":
		return zapcore.DebugLevel
	case "info":
		return zapcore.InfoLevel
	case "warn":
		return zapcore.WarnLevel
	case "error":
		return zapcore.ErrorLevel
	case "panic":
		return zapcore.PanicLevel
	case "fatal":
		return zapcore.FatalLevel
	default:
		return zapcore.InfoLevel
	}
}

func getEncoder(format string) zapcore.Encoder {
	encoderConfig := zap.NewProductionEncoderConfig()
	encoderConfig.TimeKey = "timestamp"
	encoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder
	encoderConfig.EncodeLevel = zapcore.CapitalLevelEncoder

	if format == "console" {
		return zapcore.NewConsoleEncoder(encoderConfig)
	}
	return zapcore.NewJSONEncoder(encoderConfig)
}

func GetLogger() *zap.Logger {
	if logger == nil {
		Init(nil)
	}
	return logger
}

func GetSugar() *zap.SugaredLogger {
	if sugar == nil {
		Init(nil)
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

func Panic(msg string, fields ...zap.Field) {
	GetLogger().Panic(msg, fields...)
}

func Fatal(msg string, fields ...zap.Field) {
	GetLogger().Fatal(msg, fields...)
}

func Debugf(template string, args ...interface{}) {
	GetSugar().Debugf(template, args...)
}

func Infof(template string, args ...interface{}) {
	GetSugar().Infof(template, args...)
}

func Warnf(template string, args ...interface{}) {
	GetSugar().Warnf(template, args...)
}

func Errorf(template string, args ...interface{}) {
	GetSugar().Errorf(template, args...)
}

func Panicf(template string, args ...interface{}) {
	GetSugar().Panicf(template, args...)
}

func Fatalf(template string, args ...interface{}) {
	GetSugar().Fatalf(template, args...)
}

func WithField(key string, value interface{}) *zap.Logger {
	return GetLogger().With(zap.Any(key, value))
}

func WithTraceID(traceID string) *zap.Logger {
	return GetLogger().With(zap.String("trace_id", traceID))
}

func Sync() {
	if logger != nil {
		_ = logger.Sync()
	}
}
