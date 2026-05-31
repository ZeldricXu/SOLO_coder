package logger

import (
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

type Logger struct {
	*zap.SugaredLogger
	raw *zap.Logger
}

func New(level, format, output string) (*Logger, error) {
	cfg := zap.NewProductionConfig()

	switch level {
	case "debug":
		cfg.Level = zap.NewAtomicLevelAt(zapcore.DebugLevel)
	case "info":
		cfg.Level = zap.NewAtomicLevelAt(zapcore.InfoLevel)
	case "warn":
		cfg.Level = zap.NewAtomicLevelAt(zapcore.WarnLevel)
	case "error":
		cfg.Level = zap.NewAtomicLevelAt(zapcore.ErrorLevel)
	default:
		cfg.Level = zap.NewAtomicLevelAt(zapcore.InfoLevel)
	}

	cfg.Encoding = "json"
	if format == "console" {
		cfg.Encoding = "console"
	}

	cfg.OutputPaths = []string{output}

	l, err := cfg.Build()
	if err != nil {
		return nil, err
	}

	return &Logger{
		SugaredLogger: l.Sugar(),
		raw:           l,
	}, nil
}

func (l *Logger) Sync() {
	_ = l.raw.Sync()
}
