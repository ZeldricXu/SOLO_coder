package logger

import (
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"os"
	"sync"
)

var (
	instance *zap.Logger
	once     sync.Once
)

type Config struct {
	Level   string `mapstructure:"level"`
	DevMode bool   `mapstructure:"dev_mode"`
}

func Init(cfg Config) {
	once.Do(func() {
		var config zap.Config
		if cfg.DevMode {
			config = zap.NewDevelopmentConfig()
		} else {
			config = zap.NewProductionConfig()
		}

		level, err := zapcore.ParseLevel(cfg.Level)
		if err != nil {
			level = zapcore.InfoLevel
		}
		config.Level = zap.NewAtomicLevelAt(level)
		config.OutputPaths = []string{"stdout"}
		config.ErrorOutputPaths = []string{"stderr"}

		instance, err = config.Build()
		if err != nil {
			instance = zap.NewExample()
		}

		zap.ReplaceGlobals(instance)
	})
}

func Get() *zap.Logger {
	if instance == nil {
		Init(Config{Level: "info", DevMode: false})
	}
	return instance
}

func Sync() {
	if instance != nil {
		_ = instance.Sync()
	}
}

func Info(msg string, fields ...zap.Field) {
	Get().Info(msg, fields...)
}

func Error(msg string, fields ...zap.Field) {
	Get().Error(msg, fields...)
}

func Warn(msg string, fields ...zap.Field) {
	Get().Warn(msg, fields...)
}

func Debug(msg string, fields ...zap.Field) {
	Get().Debug(msg, fields...)
}

func Fatal(msg string, fields ...zap.Field) {
	Get().Fatal(msg, fields...)
	os.Exit(1)
}
