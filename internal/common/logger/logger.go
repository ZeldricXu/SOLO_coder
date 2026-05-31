package logger

import (
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

var (
	instance *zap.Logger
	sugar    *zap.SugaredLogger
)

func Init() error {
	config := zap.NewProductionConfig()
	config.EncoderConfig.TimeKey = "timestamp"
	config.EncoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder

	var err error
	instance, err = config.Build()
	if err != nil {
		return err
	}
	sugar = instance.Sugar()
	return nil
}

func Get() *zap.Logger {
	if instance == nil {
		_ = Init()
	}
	return instance
}

func Sugar() *zap.SugaredLogger {
	if sugar == nil {
		_ = Init()
	}
	return sugar
}

func Sync() {
	if instance != nil {
		_ = instance.Sync()
	}
}
