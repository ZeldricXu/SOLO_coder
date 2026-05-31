package logger

import (
	"os"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

var Logger *zap.Logger
var SugaredLogger *zap.SugaredLogger

type Config struct {
	Level      string
	Format     string
	OutputPath string
}

func Init(cfg Config) error {
	level, err := zapcore.ParseLevel(cfg.Level)
	if err != nil {
		level = zapcore.InfoLevel
	}

	var encoderConfig zapcore.EncoderConfig
	if cfg.Format == "json" {
		encoderConfig = zap.NewProductionEncoderConfig()
	} else {
		encoderConfig = zap.NewDevelopmentEncoderConfig()
		encoderConfig.EncodeLevel = zapcore.CapitalColorLevelEncoder
	}
	encoderConfig.TimeKey = "timestamp"
	encoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder

	var encoder zapcore.Encoder
	if cfg.Format == "json" {
		encoder = zapcore.NewJSONEncoder(encoderConfig)
	} else {
		encoder = zapcore.NewConsoleEncoder(encoderConfig)
	}

	var cores []zapcore.Core

	consoleCore := zapcore.NewCore(encoder, zapcore.AddSync(os.Stdout), level)
	cores = append(cores, consoleCore)

	if cfg.OutputPath != "" {
		file, err := os.OpenFile(cfg.OutputPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
		if err != nil {
			return err
		}
		fileCore := zapcore.NewCore(encoder, zapcore.AddSync(file), level)
		cores = append(cores, fileCore)
	}

	core := zapcore.NewTee(cores...)
	Logger = zap.New(core, zap.AddCaller(), zap.AddCallerSkip(1))
	SugaredLogger = Logger.Sugar()

	return nil
}

func Sync() {
	if Logger != nil {
		_ = Logger.Sync()
	}
	if SugaredLogger != nil {
		_ = SugaredLogger.Sync()
	}
}

func Info(msg string, fields ...zap.Field) {
	if Logger != nil {
		Logger.Info(msg, fields...)
	}
}

func Warn(msg string, fields ...zap.Field) {
	if Logger != nil {
		Logger.Warn(msg, fields...)
	}
}

func Error(msg string, fields ...zap.Field) {
	if Logger != nil {
		Logger.Error(msg, fields...)
	}
}

func Debug(msg string, fields ...zap.Field) {
	if Logger != nil {
		Logger.Debug(msg, fields...)
	}
}

func Fatal(msg string, fields ...zap.Field) {
	if Logger != nil {
		Logger.Fatal(msg, fields...)
	}
}

func Infof(format string, args ...interface{}) {
	if SugaredLogger != nil {
		SugaredLogger.Infof(format, args...)
	}
}

func Warnf(format string, args ...interface{}) {
	if SugaredLogger != nil {
		SugaredLogger.Warnf(format, args...)
	}
}

func Errorf(format string, args ...interface{}) {
	if SugaredLogger != nil {
		SugaredLogger.Errorf(format, args...)
	}
}

func Debugf(format string, args ...interface{}) {
	if SugaredLogger != nil {
		SugaredLogger.Debugf(format, args...)
	}
}

func Fatalf(format string, args ...interface{}) {
	if SugaredLogger != nil {
		SugaredLogger.Fatalf(format, args...)
	}
}
