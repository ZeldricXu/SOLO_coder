package logger

import (
	"io"
	"os"
	"path/filepath"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"gopkg.in/natefinch/lumberjack.v2"
)

type Config struct {
	LogDir       string `json:"log_dir"`
	MaxSizeMB    int    `json:"max_size_mb"`
	MaxBackups   int    `json:"max_backups"`
	MaxAgeDays   int    `json:"max_age_days"`
	Compress     bool   `json:"compress"`
	Level        string `json:"level"`
	EnableStdout bool   `json:"enable_stdout"`
}

var (
	rootLogger *zap.Logger
	sugar      *zap.SugaredLogger
)

func Init(cfg Config) error {
	if err := os.MkdirAll(cfg.LogDir, 0755); err != nil {
		return err
	}

	level := parseLevel(cfg.Level)

	encoderConfig := zap.NewProductionEncoderConfig()
	encoderConfig.TimeKey = "time"
	encoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder
	encoderConfig.EncodeLevel = zapcore.CapitalLevelEncoder
	jsonEncoder := zapcore.NewJSONEncoder(encoderConfig)

	writers := []zapcore.WriteSyncer{}

	fileWriter := &lumberjack.Logger{
		Filename:   filepath.Join(cfg.LogDir, "app.log"),
		MaxSize:    cfg.MaxSizeMB,
		MaxBackups: cfg.MaxBackups,
		MaxAge:     cfg.MaxAgeDays,
		Compress:   cfg.Compress,
	}
	writers = append(writers, zapcore.AddSync(fileWriter))

	if cfg.EnableStdout {
		writers = append(writers, zapcore.AddSync(os.Stdout))
	}

	core := zapcore.NewCore(
		jsonEncoder,
		zapcore.NewMultiWriteSyncer(writers...),
		level,
	)

	rootLogger = zap.New(core, zap.AddCaller(), zap.AddStacktrace(zapcore.ErrorLevel))
	sugar = rootLogger.Sugar()

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

func Debug(msg string, fields ...zap.Field)  { rootLogger.Debug(msg, fields...) }
func Info(msg string, fields ...zap.Field)   { rootLogger.Info(msg, fields...) }
func Warn(msg string, fields ...zap.Field)   { rootLogger.Warn(msg, fields...) }
func Error(msg string, fields ...zap.Field)  { rootLogger.Error(msg, fields...) }
func DPanic(msg string, fields ...zap.Field) { rootLogger.DPanic(msg, fields...) }
func Panic(msg string, fields ...zap.Field)  { rootLogger.Panic(msg, fields...) }
func Fatal(msg string, fields ...zap.Field)  { rootLogger.Fatal(msg, fields...) }

func Debugf(format string, args ...interface{})  { sugar.Debugf(format, args...) }
func Infof(format string, args ...interface{})   { sugar.Infof(format, args...) }
func Warnf(format string, args ...interface{})   { sugar.Warnf(format, args...) }
func Errorf(format string, args ...interface{})  { sugar.Errorf(format, args...) }
func DPanicf(format string, args ...interface{}) { sugar.DPanicf(format, args...) }
func Panicf(format string, args ...interface{})  { sugar.Panicf(format, args...) }
func Fatalf(format string, args ...interface{})  { sugar.Fatalf(format, args...) }

func With(fields ...zap.Field) *zap.Logger { return rootLogger.With(fields...) }
func Sugar() *zap.SugaredLogger            { return sugar }
func Logger() *zap.Logger                  { return rootLogger }

type RotatingWriter struct {
	inner *lumberjack.Logger
}

func NewRotatingWriter(filename string, maxSizeMB, maxBackups, maxAgeDays int, compress bool) io.Writer {
	return &RotatingWriter{
		inner: &lumberjack.Logger{
			Filename:   filename,
			MaxSize:    maxSizeMB,
			MaxBackups: maxBackups,
			MaxAge:     maxAgeDays,
			Compress:   compress,
		},
	}
}

func (w *RotatingWriter) Write(p []byte) (n int, err error) { return w.inner.Write(p) }
func (w *RotatingWriter) Close() error                      { return w.inner.Close() }
func (w *RotatingWriter) Rotate() error                     { return w.inner.Rotate() }

func Sync() error {
	if rootLogger != nil {
		return rootLogger.Sync()
	}
	return nil
}
