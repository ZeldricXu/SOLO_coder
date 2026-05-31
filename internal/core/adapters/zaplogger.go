package adapters

import (
	"context"

	"github.com/apishield/apishield/internal/core/ports"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

type ZapLoggerAdapter struct {
	logger *zap.Logger
	fields map[string]any
}

func NewZapLoggerAdapter(level string) (*ZapLoggerAdapter, error) {
	zapLevel, err := zapcore.ParseLevel(level)
	if err != nil {
		zapLevel = zapcore.InfoLevel
	}

	config := zap.Config{
		Level:       zap.NewAtomicLevelAt(zapLevel),
		Development: false,
		Sampling: &zap.SamplingConfig{
			Initial:    100,
			Thereafter: 100,
		},
		Encoding: "json",
		EncoderConfig: zapcore.EncoderConfig{
			TimeKey:        "timestamp",
			LevelKey:       "level",
			NameKey:        "logger",
			CallerKey:      "caller",
			FunctionKey:    zapcore.OmitKey,
			MessageKey:     "message",
			StacktraceKey:  "stacktrace",
			LineEnding:     zapcore.DefaultLineEnding,
			EncodeLevel:    zapcore.LowercaseLevelEncoder,
			EncodeTime:     zapcore.ISO8601TimeEncoder,
			EncodeDuration: zapcore.SecondsDurationEncoder,
			EncodeCaller:   zapcore.ShortCallerEncoder,
		},
		OutputPaths:      []string{"stdout"},
		ErrorOutputPaths: []string{"stderr"},
	}

	logger, err := config.Build()
	if err != nil {
		return nil, err
	}

	return &ZapLoggerAdapter{
		logger: logger,
		fields: make(map[string]any),
	}, nil
}

func (l *ZapLoggerAdapter) toZapFields(fields map[string]any) []zap.Field {
	zapFields := make([]zap.Field, 0, len(fields)+len(l.fields))

	for k, v := range l.fields {
		zapFields = append(zapFields, zap.Any(k, v))
	}

	for k, v := range fields {
		zapFields = append(zapFields, zap.Any(k, v))
	}

	return zapFields
}

func (l *ZapLoggerAdapter) Debug(ctx context.Context, msg string, fields map[string]any) {
	l.logger.Debug(msg, l.toZapFields(fields)...)
}

func (l *ZapLoggerAdapter) Info(ctx context.Context, msg string, fields map[string]any) {
	l.logger.Info(msg, l.toZapFields(fields)...)
}

func (l *ZapLoggerAdapter) Warn(ctx context.Context, msg string, fields map[string]any) {
	l.logger.Warn(msg, l.toZapFields(fields)...)
}

func (l *ZapLoggerAdapter) Error(ctx context.Context, msg string, err error, fields map[string]any) {
	zapFields := l.toZapFields(fields)
	if err != nil {
		zapFields = append(zapFields, zap.Error(err))
	}
	l.logger.Error(msg, zapFields...)
}

func (l *ZapLoggerAdapter) Fatal(ctx context.Context, msg string, err error, fields map[string]any) {
	zapFields := l.toZapFields(fields)
	if err != nil {
		zapFields = append(zapFields, zap.Error(err))
	}
	l.logger.Fatal(msg, zapFields...)
}

func (l *ZapLoggerAdapter) WithFields(fields map[string]any) ports.Logger {
	newFields := make(map[string]any, len(l.fields)+len(fields))
	for k, v := range l.fields {
		newFields[k] = v
	}
	for k, v := range fields {
		newFields[k] = v
	}

	return &ZapLoggerAdapter{
		logger: l.logger,
		fields: newFields,
	}
}

func (l *ZapLoggerAdapter) Close() error {
	return l.logger.Sync()
}
