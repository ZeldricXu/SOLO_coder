package logger

import (
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"os"
	"sync"
)

var (
	once sync.Once
	log  *zap.Logger
)

type Config struct {
	Level      string `json:"level"`
	Encoding   string `json:"encoding"`
	OutputPath string `json:"output_path"`
}

func Init(cfg Config) {
	once.Do(func() {
		level := zap.InfoLevel
		switch cfg.Level {
		case "debug":
			level = zap.DebugLevel
		case "warn":
			level = zap.WarnLevel
		case "error":
			level = zap.ErrorLevel
		}

		encoderCfg := zap.NewProductionEncoderConfig()
		encoderCfg.TimeKey = "timestamp"
		encoderCfg.EncodeTime = zapcore.ISO8601TimeEncoder
		encoderCfg.EncodeLevel = zapcore.LowercaseLevelEncoder

		outputPaths := []string{"stdout"}
		if cfg.OutputPath != "" {
			outputPaths = append(outputPaths, cfg.OutputPath)
		}

		zapCfg := zap.Config{
			Level:       zap.NewAtomicLevelAt(level),
			Development: false,
			Sampling: &zap.SamplingConfig{
				Initial:    100,
				Thereafter: 100,
			},
			Encoding:      "json",
			EncoderConfig:   encoderCfg,
			OutputPaths:     outputPaths,
			ErrorOutputPaths: []string{"stderr"},
		}

		if cfg.Encoding == "console" {
			zapCfg.Encoding = "console"
			zapCfg.EncoderConfig.EncodeLevel = zapcore.CapitalColorLevelEncoder
		}

		var err error
		log, err = zapCfg.Build()
		if err != nil {
			log = zap.NewExample()
			log.Error("Failed to initialize logger", zap.Error(err))
		}
	})
}

func Get() *zap.Logger {
	if log == nil {
		Init(Config{Level: "info", Encoding: "json"})
	}
	return log
}

func Sync() {
	if log != nil {
		_ = log.Sync()
	}
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

func With(fields ...zap.Field) *zap.Logger {
	return Get().With(fields...)
}

func String(key, value string) zap.Field {
	return zap.String(key, value)
}

func Int(key string, value int) zap.Field {
	return zap.Int(key, value)
}

func Float64(key string, value float64) zap.Field {
	return zap.Float64(key, value)
}

func Bool(key string, value bool) zap.Field {
	return zap.Bool(key, value)
}

func Any(key string, value interface{}) zap.Field {
	return zap.Any(key, value)
}

func ErrorField(err error) zap.Field {
	return zap.Error(err)
}
