package logger

import (
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"sync"
)

var (
	instance *zap.Logger
	level    zap.AtomicLevel
	once     sync.Once
)

func Init() {
	once.Do(func() {
		level = zap.NewAtomicLevelAt(zap.InfoLevel)
		cfg := zap.Config{
			Level:       level,
			Development: false,
			Sampling: &zap.SamplingConfig{
				Initial:    100,
				Thereafter: 100,
			},
			Encoding: "json",
			EncoderConfig: zapcore.EncoderConfig{
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
			},
			OutputPaths:      []string{"stdout"},
			ErrorOutputPaths: []string{"stderr"},
		}
		var err error
		instance, err = cfg.Build()
		if err != nil {
			panic(err)
		}
	})
}

func Get() *zap.Logger {
	if instance == nil {
		Init()
	}
	return instance
}

func SetLevel(l string) {
	var lv zapcore.Level
	switch l {
	case "debug":
		lv = zap.DebugLevel
	case "info":
		lv = zap.InfoLevel
	case "warn":
		lv = zap.WarnLevel
	case "error":
		lv = zap.ErrorLevel
	case "dpanic":
		lv = zap.DPanicLevel
	case "panic":
		lv = zap.PanicLevel
	case "fatal":
		lv = zap.FatalLevel
	default:
		lv = zap.InfoLevel
	}
	level.SetLevel(lv)
}

func GetLevel() string {
	return level.Level().String()
}

func Debug(msg string, fields ...zap.Field) {
	Get().Debug(msg, fields...)
}

func Info(msg string, fields ...zap.Field) {
	Get().Info(msg, fields...)
}

func Warn(msg string, fields ...zap.Field) {
	Get().Warn(msg, fields...)
}

func Error(msg string, fields ...zap.Field) {
	Get().Error(msg, fields...)
}

func Fatal(msg string, fields ...zap.Field) {
	Get().Fatal(msg, fields...)
}

func Sync() {
	if instance != nil {
		_ = instance.Sync()
	}
}
