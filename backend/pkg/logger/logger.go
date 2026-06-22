package logger

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"gopkg.in/natefinch/lumberjack.v2"
)

var (
	Logger *zap.Logger
	Sugar  *zap.SugaredLogger
)

type Config struct {
	Level      string
	Filename   string
	MaxSize    int
	MaxBackups int
	MaxAge     int
	Compress   bool
	Console    bool
}

func Init(cfg *Config) error {
	if cfg == nil {
		cfg = &Config{
			Level:      "info",
			MaxSize:    100,
			MaxBackups: 3,
			MaxAge:     30,
			Compress:   true,
			Console:    true,
		}
	}

	level, err := zapcore.ParseLevel(cfg.Level)
	if err != nil {
		return fmt.Errorf("invalid log level: %w", err)
	}

	encoderConfig := zapcore.EncoderConfig{
		TimeKey:        "time",
		LevelKey:       "level",
		NameKey:        "logger",
		CallerKey:      "caller",
		FunctionKey:    zapcore.OmitKey,
		MessageKey:     "msg",
		StacktraceKey:  "stacktrace",
		LineEnding:     zapcore.DefaultLineEnding,
		EncodeLevel:    zapcore.CapitalLevelEncoder,
		EncodeTime:     zapcore.TimeEncoderOfLayout("2006-01-02 15:04:05.000"),
		EncodeDuration: zapcore.SecondsDurationEncoder,
		EncodeCaller:   customCallerEncoder,
	}

	var cores []zapcore.Core

	if cfg.Console {
		consoleEncoder := zapcore.NewConsoleEncoder(encoderConfig)
		consoleCore := zapcore.NewCore(consoleEncoder, zapcore.AddSync(os.Stdout), level)
		cores = append(cores, consoleCore)
	}

	if cfg.Filename != "" {
		fileWriter := &lumberjack.Logger{
			Filename:   cfg.Filename,
			MaxSize:    cfg.MaxSize,
			MaxBackups: cfg.MaxBackups,
			MaxAge:     cfg.MaxAge,
			Compress:   cfg.Compress,
		}
		fileEncoder := zapcore.NewJSONEncoder(encoderConfig)
		fileCore := zapcore.NewCore(fileEncoder, zapcore.AddSync(fileWriter), level)
		cores = append(cores, fileCore)
	}

	core := zapcore.NewTee(cores...)
	Logger = zap.New(core, zap.AddCaller(), zap.AddCallerSkip(1))
	Sugar = Logger.Sugar()

	return nil
}

func customCallerEncoder(caller zapcore.EntryCaller, enc zapcore.PrimitiveArrayEncoder) {
	if !caller.Defined {
		enc.AppendString("<unknown>")
		return
	}
	_, file, line, ok := runtime.Caller(8)
	if !ok {
		enc.AppendString("<unknown>")
		return
	}
	dir := filepath.Base(filepath.Dir(file))
	filename := filepath.Base(file)
	enc.AppendString(fmt.Sprintf("%s/%s:%d", dir, filename, line))
}

func GetCaller(skip int) string {
	pc, file, line, ok := runtime.Caller(skip)
	if !ok {
		return "unknown"
	}
	fn := runtime.FuncForPC(pc)
	funcName := "unknown"
	if fn != nil {
		fullName := fn.Name()
		idx := strings.LastIndex(fullName, ".")
		if idx > 0 {
			funcName = fullName[idx+1:]
		}
	}
	dir := filepath.Base(filepath.Dir(file))
	filename := filepath.Base(file)
	return fmt.Sprintf("%s/%s:%d:%s", dir, filename, line, funcName)
}

func Debug(msg string, fields ...zap.Field) {
	Logger.Debug(msg, fields...)
}

func Info(msg string, fields ...zap.Field) {
	Logger.Info(msg, fields...)
}

func Warn(msg string, fields ...zap.Field) {
	Logger.Warn(msg, fields...)
}

func Error(msg string, fields ...zap.Field) {
	Logger.Error(msg, fields...)
}

func Panic(msg string, fields ...zap.Field) {
	Logger.Panic(msg, fields...)
}

func Fatal(msg string, fields ...zap.Field) {
	Logger.Fatal(msg, fields...)
}

func Debugf(format string, args ...interface{}) {
	Sugar.Debugf(format, args...)
}

func Infof(format string, args ...interface{}) {
	Sugar.Infof(format, args...)
}

func Warnf(format string, args ...interface{}) {
	Sugar.Warnf(format, args...)
}

func Errorf(format string, args ...interface{}) {
	Sugar.Errorf(format, args...)
}

func Panicf(format string, args ...interface{}) {
	Sugar.Panicf(format, args...)
}

func Fatalf(format string, args ...interface{}) {
	Sugar.Fatalf(format, args...)
}

func WithField(key string, value interface{}) *zap.SugaredLogger {
	return Sugar.With(key, value)
}

func WithFields(fields map[string]interface{}) *zap.SugaredLogger {
	var zapFields []interface{}
	for k, v := range fields {
		zapFields = append(zapFields, k, v)
	}
	return Sugar.With(zapFields...)
}

func Sync() {
	if Logger != nil {
		_ = Logger.Sync()
	}
}

func Now() string {
	return time.Now().Format("2006-01-02 15:04:05")
}
