package logger

import (
	"os"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

var (
	logger *zap.Logger
	sugar  *zap.SugaredLogger
)

type Config struct {
	Level  string
	Format string
	Output string
}

func Init(cfg Config) error {
	level := parseLevel(cfg.Level)

	var encoder zapcore.Encoder
	if cfg.Format == "json" {
		encoder = zapcore.NewJSONEncoder(zapcore.EncoderConfig{
			TimeKey:        "time",
			LevelKey:       "level",
			NameKey:        "logger",
			CallerKey:      "caller",
			FunctionKey:    zapcore.OmitKey,
			MessageKey:     "msg",
			StacktraceKey:  "stacktrace",
			LineEnding:     zapcore.DefaultLineEnding,
			EncodeLevel:    zapcore.LowercaseLevelEncoder,
			EncodeTime:     zapcore.ISO8601TimeEncoder,
			EncodeDuration: zapcore.SecondsDurationEncoder,
			EncodeCaller:   zapcore.ShortCallerEncoder,
		})
	} else {
		encoder = zapcore.NewConsoleEncoder(zapcore.EncoderConfig{
			TimeKey:        "time",
			LevelKey:       "level",
			NameKey:        "logger",
			CallerKey:      "caller",
			FunctionKey:    zapcore.OmitKey,
			MessageKey:     "msg",
			StacktraceKey:  "stacktrace",
			LineEnding:     zapcore.DefaultLineEnding,
			EncodeLevel:    zapcore.CapitalColorLevelEncoder,
			EncodeTime:     zapcore.ISO8601TimeEncoder,
			EncodeDuration: zapcore.SecondsDurationEncoder,
			EncodeCaller:   zapcore.ShortCallerEncoder,
		})
	}

	var output zapcore.WriteSyncer
	if cfg.Output == "stdout" {
		output = zapcore.AddSync(os.Stdout)
	} else {
		file, err := os.OpenFile(cfg.Output, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
		if err != nil {
			return err
		}
		output = zapcore.AddSync(file)
	}

	core := zapcore.NewCore(encoder, output, level)
	logger = zap.New(core, zap.AddCaller(), zap.AddStacktrace(zapcore.ErrorLevel))
	sugar = logger.Sugar()

	return nil
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

func L() *zap.Logger {
	if logger == nil {
		logger, _ = zap.NewProduction()
	}
	return logger
}

func S() *zap.SugaredLogger {
	if sugar == nil {
		if logger == nil {
			logger, _ = zap.NewProduction()
		}
		sugar = logger.Sugar()
	}
	return sugar
}

func Sync() {
	if logger != nil {
		_ = logger.Sync()
	}
}

func Debug(msg string, keysAndValues ...interface{}) {
	S().Debugw(msg, keysAndValues...)
}

func Info(msg string, keysAndValues ...interface{}) {
	S().Infow(msg, keysAndValues...)
}

func Warn(msg string, keysAndValues ...interface{}) {
	S().Warnw(msg, keysAndValues...)
}

func Error(msg string, keysAndValues ...interface{}) {
	S().Errorw(msg, keysAndValues...)
}

func Fatal(msg string, keysAndValues ...interface{}) {
	S().Fatalw(msg, keysAndValues...)
}

func With(fields ...zap.Field) *zap.Logger {
	return L().With(fields...)
}
