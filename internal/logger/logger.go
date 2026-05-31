package logger

import (
	"os"
	"sync"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

var (
	instance *zap.Logger
	once     sync.Once
)

type Config struct {
	Level       string `yaml:"level" json:"level"`
	Encoding    string `yaml:"encoding" json:"encoding"`
	OutputPaths []string `yaml:"output_paths" json:"output_paths"`
}

func Init(cfg *Config) error {
	var err error
	once.Do(func() {
		level := parseLevel(cfg.Level)
		encoderCfg := zap.NewProductionEncoderConfig()
		encoderCfg.TimeKey = "timestamp"
		encoderCfg.EncodeTime = zapcore.ISO8601TimeEncoder

		var encoder zapcore.Encoder
		if cfg.Encoding == "console" {
			encoder = zapcore.NewConsoleEncoder(encoderCfg)
		} else {
			encoder = zapcore.NewJSONEncoder(encoderCfg)
		}

		outputPaths := cfg.OutputPaths
		if len(outputPaths) == 0 {
			outputPaths = []string{"stdout"}
		}

		var cores []zapcore.Core
		for _, path := range outputPaths {
			var writer zapcore.WriteSyncer
			if path == "stdout" {
				writer = os.Stdout
			} else if path == "stderr" {
				writer = os.Stderr
			} else {
				file, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
				if err != nil {
					return
				}
				writer = zapcore.AddSync(file)
			}
			cores = append(cores, zapcore.NewCore(encoder, writer, level))
		}

		core := zapcore.NewTee(cores...)
		instance = zap.New(core, zap.AddCaller(), zap.AddStacktrace(zapcore.ErrorLevel))
	})
	return err
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

func Get() *zap.Logger {
	if instance == nil {
		Init(&Config{Level: "info", Encoding: "json", OutputPaths: []string{"stdout"}})
	}
	return instance
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

func Panic(msg string, fields ...zap.Field) {
	Get().Panic(msg, fields...)
}

func Fatal(msg string, fields ...zap.Field) {
	Get().Fatal(msg, fields...)
}

func With(fields ...zap.Field) *zap.Logger {
	return Get().With(fields...)
}

func Sync() error {
	if instance != nil {
		return instance.Sync()
	}
	return nil
}
