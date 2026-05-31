package logging

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"

	"github.com/solocoder/session148/internal/domain"
	apperr "github.com/solocoder/session148/pkg/errors"
	"github.com/solocoder/session148/pkg/utils"
)

type ZapLogger struct {
	logger   *zap.Logger
	traceID  string
	mu       sync.Mutex
	rotator  *LogRotator
	wal      *WriteAheadLog
	index    *LogIndex
	logger   domain.Logger
}

type LoggerConfig struct {
	Level       string
	OutputPath  string
	MaxSizeMB   int
	MaxBackups  int
	MaxAgeDays  int
	Compress    bool
	EnableWAL   bool
	EnableIndex bool
}

func NewZapLogger(cfg LoggerConfig) (*ZapLogger, error) {
	level, err := zapcore.ParseLevel(cfg.Level)
	if err != nil {
		level = zapcore.InfoLevel
	}

	encoderCfg := zap.NewProductionEncoderConfig()
	encoderCfg.TimeKey = "timestamp"
	encoderCfg.EncodeTime = zapcore.ISO8601TimeEncoder

	var cores []zapcore.Core

	consoleEncoder := zapcore.NewJSONEncoder(encoderCfg)
	consoleCore := zapcore.NewCore(consoleEncoder, zapcore.Lock(os.Stdout), level)
	cores = append(cores, consoleCore)

	var rotator *LogRotator
	var wal *WriteAheadLog
	var logIndex *LogIndex
	var baseLogger domain.Logger

	if cfg.OutputPath != "" {
		rotator, err = NewLogRotator(RotatorConfig{
			Filename:   cfg.OutputPath,
			MaxSize:    cfg.MaxSizeMB,
			MaxBackups: cfg.MaxBackups,
			MaxAge:     cfg.MaxAgeDays,
			Compress:   cfg.Compress,
		})
		if err != nil {
			return nil, err
		}

		if cfg.EnableWAL {
			wal, err = NewWriteAheadLog(filepath.Dir(cfg.OutputPath))
			if err != nil {
				return nil, fmt.Errorf("init WAL failed: %w", err)
			}
		}

		if cfg.EnableIndex {
			logIndex, err = NewLogIndex(filepath.Dir(cfg.OutputPath))
			if err != nil {
				return nil, fmt.Errorf("init log index failed: %w", err)
			}

			if err := logIndex.Recover(); err != nil {
				return nil, fmt.Errorf("recover log index failed: %w", err)
			}
		}

		teeCore := newTeeCore(rotator, logIndex, level, encoderCfg)
		cores = append(cores, teeCore)

		baseLogger = &ZapLogger{
			logger:  zap.New(consoleCore),
			rotator: rotator,
			wal:     wal,
			index:   logIndex,
		}
	}

	z := &ZapLogger{
		logger:  zap.New(zapcore.NewTee(cores...)),
		rotator: rotator,
		wal:     wal,
		index:   logIndex,
		logger:  baseLogger,
	}

	if cfg.EnableWAL && wal != nil {
		go wal.StartReplay(z.replayEntry)
	}

	return z, nil
}

func (l *ZapLogger) WithTraceID(traceID string) domain.Logger {
	return &ZapLogger{
		logger:  l.logger.With(zap.String("trace_id", traceID)),
		traceID: traceID,
		rotator: l.rotator,
		wal:     l.wal,
		index:   l.index,
		logger:  l.logger,
	}
}

func (l *ZapLogger) Debug(msg string, fields ...interface{}) {
	l.writeWALIfEnabled("debug", msg, fields)
	l.logger.Debug(msg, l.toZapFields(fields...)...)
}

func (l *ZapLogger) Info(msg string, fields ...interface{}) {
	l.writeWALIfEnabled("info", msg, fields)
	l.logger.Info(msg, l.toZapFields(fields...)...)
}

func (l *ZapLogger) Warn(msg string, fields ...interface{}) {
	l.writeWALIfEnabled("warn", msg, fields)
	l.logger.Warn(msg, l.toZapFields(fields...)...)
}

func (l *ZapLogger) Error(msg string, fields ...interface{}) {
	l.writeWALIfEnabled("error", msg, fields)
	l.logger.Error(msg, l.toZapFields(fields...)...)
}

func (l *ZapLogger) Fatal(msg string, fields ...interface{}) {
	l.writeWALIfEnabled("fatal", msg, fields)
	l.logger.Fatal(msg, l.toZapFields(fields...)...)
}

func (l *ZapLogger) Sync() error {
	if l.wal != nil {
		l.wal.Sync()
	}
	if l.index != nil {
		l.index.Sync()
	}
	return l.logger.Sync()
}

func (l *ZapLogger) writeWALIfEnabled(level, msg string, fields []interface{}) {
	if l.wal == nil {
		return
	}
	entry := &domain.LogEntry{
		Level:     level,
		Message:   msg,
		Timestamp: time.Now().UTC(),
		TraceID:   l.traceID,
		Fields:    l.fieldsToMap(fields),
	}
	l.wal.Write(entry)
}

func (l *ZapLogger) fieldsToMap(fields []interface{}) map[string]interface{} {
	result := make(map[string]interface{})
	for i := 0; i < len(fields); i += 2 {
		if i+1 >= len(fields) {
			break
		}
		if key, ok := fields[i].(string); ok {
			result[key] = fields[i+1]
		}
	}
	return result
}

func (l *ZapLogger) replayEntry(entry *domain.LogEntry) {
	if l.logger == nil {
		return
	}
	fields := make([]interface{}, 0, len(entry.Fields)*2)
	for k, v := range entry.Fields {
		fields = append(fields, k, v)
	}
	switch entry.Level {
	case "debug":
		l.logger.Debug(entry.Message, fields...)
	case "info":
		l.logger.Info(entry.Message, fields...)
	case "warn":
		l.logger.Warn(entry.Message, fields...)
	case "error":
		l.logger.Error(entry.Message, fields...)
	}
}

func (l *ZapLogger) toZapFields(fields ...interface{}) []zap.Field {
	zapFields := make([]zap.Field, 0, len(fields)/2)
	for i := 0; i < len(fields); i += 2 {
		if i+1 >= len(fields) {
			break
		}
		key, ok := fields[i].(string)
		if !ok {
			continue
		}
		zapFields = append(zapFields, zap.Any(key, fields[i+1]))
	}
	return zapFields
}

func (l *ZapLogger) Rotate() error {
	if l.rotator != nil {
		return l.rotator.Rotate()
	}
	return nil
}

func (l *ZapLogger) QueryLogs(ctx context.Context, filter LogFilter) ([]domain.LogEntry, error) {
	if l.index == nil {
		return nil, apperr.NewValidationError("log index not enabled", "EnableIndex must be true in config")
	}
	return l.index.Query(filter)
}

func (l *ZapLogger) RecoverFromCrash() error {
	if l.wal != nil {
		return l.wal.Recover()
	}
	return nil
}

type teeCore struct {
	writer   io.Writer
	index    *LogIndex
	levelEnab zapcore.LevelEnabler
	encoder  zapcore.Encoder
}

func newTeeCore(writer io.Writer, index *LogIndex, level zapcore.LevelEnabler, cfg zapcore.EncoderConfig) zapcore.Core {
	return &teeCore{
		writer:    writer,
		index:     index,
		levelEnab: level,
		encoder:   zapcore.NewJSONEncoder(cfg),
	}
}

func (c *teeCore) Enabled(level zapcore.Level) bool {
	return c.levelEnab.Enabled(level)
}

func (c *teeCore) With(fields []zap.Field) zapcore.Core {
	clone := &teeCore{
		writer:    c.writer,
		index:     c.index,
		levelEnab: c.levelEnab,
		encoder:   c.encoder.Clone(),
	}
	for _, f := range fields {
		f.AddTo(clone.encoder)
	}
	return clone
}

func (c *teeCore) Check(entry zapcore.Entry, ce *zapcore.CheckedEntry) *zapcore.CheckedEntry {
	if c.Enabled(entry.Level) {
		return ce.AddCore(entry, c)
	}
	return ce
}

func (c *teeCore) Write(entry zapcore.Entry, fields []zap.Field) error {
	buf, err := c.encoder.EncodeEntry(entry, fields)
	if err != nil {
		return err
	}
	defer buf.Free()

	if _, err := c.writer.Write(buf.Bytes()); err != nil {
		return err
	}

	if c.index != nil {
		logEntry := &domain.LogEntry{
			Level:     entry.Level.String(),
			Message:   entry.Message,
			Timestamp: entry.Time,
			TraceID:   extractTraceID(fields),
		}
		c.index.Append(logEntry)
	}

	return nil
}

func (c *teeCore) Sync() error {
	if syncer, ok := c.writer.(zapcore.WriteSyncer); ok {
		return syncer.Sync()
	}
	return nil
}

func extractTraceID(fields []zap.Field) string {
	for _, f := range fields {
		if f.Key == "trace_id" && f.Type == zapcore.StringType {
			return f.String
		}
	}
	return ""
}

type LogFilter struct {
	Level     string
	TraceID   string
	StartTime time.Time
	EndTime   time.Time
	Limit     int
	Offset    int
}

type LogIndex struct {
	indexFile  string
	entries    []*LogIndexEntry
	mu         sync.RWMutex
	logDir     string
}

type LogIndexEntry struct {
	Offset    int64     `json:"offset"`
	Size      int       `json:"size"`
	Level     string    `json:"level"`
	Timestamp time.Time `json:"timestamp"`
	TraceID   string    `json:"trace_id,omitempty"`
	Filename  string    `json:"filename"`
}

func NewLogIndex(logDir string) (*LogIndex, error) {
	indexFile := filepath.Join(logDir, "log.idx")
	return &LogIndex{
		indexFile: indexFile,
		entries:   []*LogIndexEntry{},
		logDir:    logDir,
	}, nil
}

func (idx *LogIndex) Append(entry *domain.LogEntry) {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	lastOffset := int64(0)
	if len(idx.entries) > 0 {
		last := idx.entries[len(idx.entries)-1]
		lastOffset = last.Offset + int64(last.Size)
	}

	idx.entries = append(idx.entries, &LogIndexEntry{
		Offset:    lastOffset,
		Size:      len(entry.Message) + 100,
		Level:     entry.Level,
		Timestamp: entry.Timestamp,
		TraceID:   entry.TraceID,
		Filename:  "app.log",
	})
}

func (idx *LogIndex) Query(filter LogFilter) ([]domain.LogEntry, error) {
	idx.mu.RLock()
	defer idx.mu.RUnlock()

	var results []domain.LogEntry
	count := 0
	skipped := 0

	for i := len(idx.entries) - 1; i >= 0; i-- {
		entry := idx.entries[i]

		if filter.Level != "" && entry.Level != filter.Level {
			continue
		}
		if filter.TraceID != "" && entry.TraceID != filter.TraceID {
			continue
		}
		if !filter.StartTime.IsZero() && entry.Timestamp.Before(filter.StartTime) {
			continue
		}
		if !filter.EndTime.IsZero() && entry.Timestamp.After(filter.EndTime) {
			continue
		}

		if skipped < filter.Offset {
			skipped++
			continue
		}

		logEntry := domain.LogEntry{
			Level:     entry.Level,
			Timestamp: entry.Timestamp,
			TraceID:   entry.TraceID,
		}
		results = append(results, logEntry)
		count++

		if filter.Limit > 0 && count >= filter.Limit {
			break
		}
	}

	sort.Slice(results, func(i, j int) bool {
		return results[i].Timestamp.Before(results[j].Timestamp)
	})

	return results, nil
}

func (idx *LogIndex) Recover() error {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	data, err := os.ReadFile(idx.indexFile)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}

	lines := strings.Split(string(data), "\n")
	for _, line := range lines {
		if line == "" {
			continue
		}
		var entry LogIndexEntry
		if err := json.Unmarshal([]byte(line), &entry); err != nil {
			continue
		}
		idx.entries = append(idx.entries, &entry)
	}

	return nil
}

func (idx *LogIndex) Sync() {
	idx.mu.RLock()
	defer idx.mu.RUnlock()

	file, err := os.OpenFile(idx.indexFile, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644)
	if err != nil {
		return
	}
	defer file.Close()

	for _, entry := range idx.entries {
		data, _ := json.Marshal(entry)
		file.Write(append(data, '\n'))
	}
}

type WriteAheadLog struct {
	walFile  string
	mu       sync.Mutex
	logDir   string
	replayCh chan *domain.LogEntry
}

func NewWriteAheadLog(logDir string) (*WriteAheadLog, error) {
	walFile := filepath.Join(logDir, "wal.log")
	if err := os.MkdirAll(logDir, 0755); err != nil {
		return nil, err
	}

	return &WriteAheadLog{
		walFile:  walFile,
		logDir:   logDir,
		replayCh: make(chan *domain.LogEntry, 1000),
	}, nil
}

func (wal *WriteAheadLog) Write(entry *domain.LogEntry) error {
	wal.mu.Lock()
	defer wal.mu.Unlock()

	file, err := os.OpenFile(wal.walFile, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		return err
	}
	defer file.Close()

	data, err := json.Marshal(entry)
	if err != nil {
		return err
	}

	length := make([]byte, 4)
	binary.BigEndian.PutUint32(length, uint32(len(data)))
	if _, err := file.Write(length); err != nil {
		return err
	}
	if _, err := file.Write(data); err != nil {
		return err
	}
	return file.Sync()
}

func (wal *WriteAheadLog) Recover() error {
	wal.mu.Lock()
	defer wal.mu.Unlock()

	data, err := os.ReadFile(wal.walFile)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}

	offset := 0
	for offset < len(data) {
		if offset+4 > len(data) {
			break
		}
		length := binary.BigEndian.Uint32(data[offset : offset+4])
		offset += 4

		if offset+int(length) > len(data) {
			break
		}

		var entry domain.LogEntry
		if err := json.Unmarshal(data[offset:offset+int(length)], &entry); err != nil {
			offset += int(length)
			continue
		}

		wal.replayCh <- &entry
		offset += int(length)
	}

	return os.Truncate(wal.walFile, 0)
}

func (wal *WriteAheadLog) StartReplay(handler func(*domain.LogEntry)) {
	for entry := range wal.replayCh {
		handler(entry)
	}
}

func (wal *WriteAheadLog) Sync() {
	wal.mu.Lock()
	defer wal.mu.Unlock()
}

func (wal *WriteAheadLog) Close() {
	close(wal.replayCh)
}

type LogRotator struct {
	filename   string
	maxSize    int
	maxBackups int
	maxAge     int
	compress   bool
	file       *os.File
	size       int64
	mu         sync.Mutex
	clock      domain.Clock
	index      *LogIndex
	checkpoint *CheckpointManager
}

type CheckpointManager struct {
	checkpointFile string
	mu             sync.Mutex
}

type RotatorConfig struct {
	Filename   string
	MaxSize    int
	MaxBackups int
	MaxAge     int
	Compress   bool
}

func NewLogRotator(cfg RotatorConfig) (*LogRotator, error) {
	if cfg.Filename == "" {
		return nil, fmt.Errorf("filename is required")
	}
	if cfg.MaxSize == 0 {
		cfg.MaxSize = 100
	}

	dir := filepath.Dir(cfg.Filename)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, err
	}

	rotator := &LogRotator{
		filename:   cfg.Filename,
		maxSize:    cfg.MaxSize * 1024 * 1024,
		maxBackups: cfg.MaxBackups,
		maxAge:     cfg.MaxAge,
		compress:   cfg.Compress,
		clock:      utils.NewRealClock(),
		checkpoint: &CheckpointManager{
			checkpointFile: filepath.Join(dir, "rotate.ckpt"),
		},
	}

	if err := rotator.recoverFromCheckpoint(); err != nil {
		return nil, err
	}

	if err := rotator.openFile(); err != nil {
		return nil, err
	}

	return rotator, nil
}

func (r *LogRotator) recoverFromCheckpoint() error {
	data, err := os.ReadFile(r.checkpoint.checkpointFile)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}

	var checkpoint struct {
		LastRotate time.Time `json:"last_rotate"`
		LastFile   string    `json:"last_file"`
	}
	if err := json.Unmarshal(data, &checkpoint); err != nil {
		return nil
	}

	if checkpoint.LastFile != "" {
		if _, err := os.Stat(checkpoint.LastFile); err == nil {
			r.filename = checkpoint.LastFile
		}
	}
	return nil
}

func (r *LogRotator) saveCheckpoint() error {
	checkpoint := struct {
		LastRotate time.Time `json:"last_rotate"`
		LastFile   string    `json:"last_file"`
	}{
		LastRotate: r.clock.Now(),
		LastFile:   r.filename,
	}
	data, _ := json.Marshal(checkpoint)
	return os.WriteFile(r.checkpoint.checkpointFile, data, 0644)
}

func (r *LogRotator) Write(p []byte) (n int, err error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.size+int64(len(p)) > int64(r.maxSize) {
		if err := r.rotateLocked(); err != nil {
			return 0, err
		}
	}

	n, err = r.file.Write(p)
	r.size += int64(n)
	return n, err
}

func (r *LogRotator) Rotate() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.rotateLocked()
}

func (r *LogRotator) rotateLocked() error {
	if r.file != nil {
		r.file.Close()
	}

	now := r.clock.Now()
	backupName := r.filename + "." + now.Format("2006-01-02-15-04-05")
	if err := os.Rename(r.filename, backupName); err != nil && !os.IsNotExist(err) {
		return err
	}

	r.saveCheckpoint()

	if r.compress {
		go r.compressFile(backupName)
	}

	if err := r.openFile(); err != nil {
		return err
	}

	go r.cleanupOldFiles()

	return nil
}

func (r *LogRotator) Archive(filename string) error {
	backupDir := filepath.Dir(r.filename) + "/archive"
	if err := os.MkdirAll(backupDir, 0755); err != nil {
		return err
	}

	dest := filepath.Join(backupDir, filepath.Base(filename))
	return os.Rename(filename, dest)
}

func (r *LogRotator) Cleanup(retention time.Duration) error {
	dir := filepath.Dir(r.filename)
	files, err := os.ReadDir(dir)
	if err != nil {
		return err
	}

	cutoff := r.clock.Now().Add(-retention)
	for _, f := range files {
		if f.IsDir() {
			continue
		}
		info, err := f.Info()
		if err != nil {
			continue
		}
		if info.ModTime().Before(cutoff) {
			os.Remove(filepath.Join(dir, f.Name()))
		}
	}

	return nil
}

func (r *LogRotator) openFile() error {
	file, err := os.OpenFile(r.filename, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		return err
	}

	info, err := file.Stat()
	if err != nil {
		file.Close()
		return err
	}

	r.file = file
	r.size = info.Size()
	return nil
}

func (r *LogRotator) compressFile(filename string) {
}

func (r *LogRotator) cleanupOldFiles() {
	if r.maxBackups <= 0 {
		return
	}

	dir := filepath.Dir(r.filename)
	files, err := filepath.Glob(r.filename + ".*")
	if err != nil {
		return
	}

	if len(files) <= r.maxBackups {
		return
	}

	type fileInfo struct {
		path string
		mod  time.Time
	}

	var infos []fileInfo
	for _, f := range files {
		info, err := os.Stat(f)
		if err != nil {
			continue
		}
		infos = append(infos, fileInfo{path: f, mod: info.ModTime()})
	}

	sort.Slice(infos, func(i, j int) bool {
		return infos[i].mod.Before(infos[j].mod)
	})

	for i := 0; i < len(infos)-r.maxBackups; i++ {
		os.Remove(infos[i].path)
	}
}

func (r *LogRotator) Close() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.file != nil {
		return r.file.Close()
	}
	return nil
}
