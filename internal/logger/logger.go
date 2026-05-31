package logger

import (
	"os"
	"sync"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

var (
	globalLogger *zap.Logger
	once         sync.Once
)

type Config struct {
	Level      string `json:"level" yaml:"level"`
	Encoding   string `json:"encoding" yaml:"encoding"`
	OutputPath string `json:"output_path" yaml:"output_path"`
}

func NewLogger(cfg Config) (*zap.Logger, error) {
	level := zap.InfoLevel
	if cfg.Level != "" {
		if err := level.UnmarshalText([]byte(cfg.Level)); err != nil {
			return nil, err
		}
	}

	encoderConfig := zap.NewProductionEncoderConfig()
	encoderConfig.TimeKey = "timestamp"
	encoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder
	encoderConfig.EncodeLevel = zapcore.CapitalLevelEncoder

	encoding := cfg.Encoding
	if encoding == "" {
		encoding = "json"
	}

	outputPath := cfg.OutputPath
	if outputPath == "" {
		outputPath = "stdout"
	}

	var writer zapcore.WriteSyncer
	if outputPath == "stdout" {
		writer = zapcore.AddSync(os.Stdout)
	} else {
		file, err := os.OpenFile(outputPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
		if err != nil {
			return nil, err
		}
		writer = zapcore.AddSync(file)
	}

	var encoder zapcore.Encoder
	if encoding == "json" {
		encoder = zapcore.NewJSONEncoder(encoderConfig)
	} else {
		encoder = zapcore.NewConsoleEncoder(encoderConfig)
	}

	core := zapcore.NewCore(encoder, writer, level)
	logger := zap.New(core, zap.AddCaller(), zap.AddStacktrace(zap.ErrorLevel))

	return logger, nil
}

func InitLogger(cfg Config) error {
	var err error
	once.Do(func() {
		globalLogger, err = NewLogger(cfg)
	})
	return err
}

func GetLogger() *zap.Logger {
	if globalLogger == nil {
		globalLogger, _ = NewLogger(Config{Level: "info"})
	}
	return globalLogger
}

func Sync() {
	if globalLogger != nil {
		_ = globalLogger.Sync()
	}
}

func With(fields ...zap.Field) *zap.Logger {
	return GetLogger().With(fields...)
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
