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

func Init() {
	once.Do(func() {
		encoderCfg := zap.NewProductionEncoderConfig()
		encoderCfg.TimeKey = "timestamp"
		encoderCfg.EncodeTime = zapcore.ISO8601TimeEncoder

		core := zapcore.NewCore(
			zapcore.NewJSONEncoder(encoderCfg),
			zapcore.AddSync(os.Stdout),
			zap.InfoLevel,
		)

		instance = zap.New(core, zap.AddCaller(), zap.AddCallerSkip(1))
	})
}

func Get() *zap.Logger {
	if instance == nil {
		Init()
	}
	return instance
}

func Sync() {
	if instance != nil {
		_ = instance.Sync()
	}
}
