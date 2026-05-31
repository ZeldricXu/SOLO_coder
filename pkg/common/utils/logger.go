package utils

import (
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"os"
)

var logger *zap.Logger

func InitLogger() {
	config := zap.NewProductionConfig()
	config.EncoderConfig.TimeKey = "timestamp"
	config.EncoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder
	config.OutputPaths = []string{"stdout"}
	config.ErrorOutputPaths = []string{"stderr"}

	if os.Getenv("ENV") == "development" {
		config = zap.NewDevelopmentConfig()
		config.EncoderConfig.EncodeLevel = zapcore.CapitalColorLevelEncoder
	}

	var err error
	logger, err = config.Build()
	if err != nil {
		panic(err)
	}
}

func GetLogger() *zap.Logger {
	if logger == nil {
		InitLogger()
	}
	return logger
}

func SyncLogger() {
	if logger != nil {
		_ = logger.Sync()
	}
}
