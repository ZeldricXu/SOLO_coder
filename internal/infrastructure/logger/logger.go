package logger

import (
	"os"
	"sync"

	"github.com/edgevision/edgevision/internal/infrastructure/config"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"gopkg.in/natefinch/lumberjack.v2"
)

var (
	instance *zap.Logger
	once     sync.Once
)

func Init(cfg *config.LoggerConfig) {
	once.Do(func() {
		level := parseLevel(cfg.Level)

		encoderCfg := zap.NewProductionEncoderConfig()
		encoderCfg.TimeKey = "timestamp"
		encoderCfg.EncodeTime = zapcore.ISO8601TimeEncoder

		consoleEncoder := zapcore.NewConsoleEncoder(encoderCfg)
		fileEncoder := zapcore.NewJSONEncoder(encoderCfg)

		consoleSyncer := zapcore.AddSync(os.Stdout)
		fileSyncer := zapcore.AddSync(&lumberjack.Logger{
			Filename:   cfg.Filename,
			MaxSize:    cfg.MaxSize,
			MaxBackups: cfg.MaxBackups,
			MaxAge:     cfg.MaxAge,
			Compress:   cfg.Compress,
		})

		core := zapcore.NewTee(
			zapcore.NewCore(consoleEncoder, consoleSyncer, level),
			zapcore.NewCore(fileEncoder, fileSyncer, level),
		)

		instance = zap.New(core, zap.AddCaller(), zap.AddStacktrace(zapcore.ErrorLevel))
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
	default:
		return zapcore.InfoLevel
	}
}

func Get() *zap.Logger {
	if instance == nil {
		instance = zap.NewExample()
	}
	return instance
}

func Sync() {
	if instance != nil {
		_ = instance.Sync()
	}
}
