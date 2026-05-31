package wal

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/edgevision/edgevision/internal/infrastructure/config"
)

type WALEntry struct {
	Sequence  uint64      `json:"sequence"`
	Timestamp int64       `json:"timestamp"`
	Operation string      `json:"operation"`
	Data      interface{} `json:"data"`
	Checksum  uint32      `json:"checksum"`
}

type WAL struct {
	cfg         *config.WALConfig
	mu          sync.Mutex
	file        *os.File
	sequence    uint64
	currentSize int64
	flushTicker *time.Ticker
}

func NewWAL(cfg *config.WALConfig) (*WAL, error) {
	if !cfg.Enabled {
		return nil, nil
	}

	if err := os.MkdirAll(cfg.Dir, 0755); err != nil {
		return nil, fmt.Errorf("create wal dir failed: %w", err)
	}

	wal := &WAL{
		cfg: cfg,
	}

	if err := wal.rotateFile(); err != nil {
		return nil, err
	}

	wal.flushTicker = time.NewTicker(cfg.FlushInterval)
	go wal.autoFlush()

	return wal, nil
}

func (w *WAL) rotateFile() error {
	if w.file != nil {
		if err := w.file.Sync(); err != nil {
			return err
		}
		w.file.Close()
	}

	filename := filepath.Join(w.cfg.Dir, fmt.Sprintf("wal_%d.log", time.Now().UnixNano()))
	file, err := os.OpenFile(filename, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
	if err != nil {
		return fmt.Errorf("open wal file failed: %w", err)
	}

	w.file = file
	w.currentSize = 0
	return nil
}

func (w *WAL) Write(operation string, data interface{}) (uint64, error) {
	if w == nil {
		return 0, nil
	}

	w.mu.Lock()
	defer w.mu.Unlock()

	w.sequence++
	entry := WALEntry{
		Sequence:  w.sequence,
		Timestamp: time.Now().UnixNano(),
		Operation: operation,
		Data:      data,
	}

	entry.Checksum = w.calculateChecksum(entry)

	data, err := json.Marshal(entry)
	if err != nil {
		return 0, fmt.Errorf("marshal wal entry failed: %w", err)
	}

	length := uint32(len(data))
	lengthBuf := make([]byte, 4)
	binary.BigEndian.PutUint32(lengthBuf, length)

	if _, err := w.file.Write(lengthBuf); err != nil {
		return 0, fmt.Errorf("write length failed: %w", err)
	}

	if _, err := w.file.Write(data); err != nil {
		return 0, fmt.Errorf("write data failed: %w", err)
	}

	w.currentSize += int64(4 + len(data))

	if w.currentSize >= w.cfg.MaxFileSize {
		if err := w.rotateFile(); err != nil {
			return w.sequence, err
		}
	}

	return w.sequence, nil
}

func (w *WAL) calculateChecksum(entry WALEntry) uint32 {
	data, _ := json.Marshal(entry.Data)
	var sum uint32
	for _, b := range data {
		sum += uint32(b)
	}
	return sum + uint32(entry.Sequence) + uint32(entry.Timestamp)
}

func (w *WAL) autoFlush() {
	for range w.flushTicker.C {
		w.Flush()
	}
}

func (w *WAL) Flush() error {
	if w == nil {
		return nil
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.file != nil {
		return w.file.Sync()
	}
	return nil
}

func (w *WAL) Close() error {
	if w == nil {
		return nil
	}
	w.flushTicker.Stop()
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.file != nil {
		if err := w.file.Sync(); err != nil {
			return err
		}
		return w.file.Close()
	}
	return nil
}

func (w *WAL) Replay(callback func(entry WALEntry) error) error {
	if w == nil {
		return nil
	}

	files, err := filepath.Glob(filepath.Join(w.cfg.Dir, "wal_*.log"))
	if err != nil {
		return err
	}

	for _, file := range files {
		if err := w.replayFile(file, callback); err != nil {
			return err
		}
	}

	return nil
}

func (w *WAL) replayFile(filename string, callback func(entry WALEntry) error) error {
	file, err := os.Open(filename)
	if err != nil {
		return err
	}
	defer file.Close()

	for {
		lengthBuf := make([]byte, 4)
		if _, err := file.Read(lengthBuf); err != nil {
			break
		}
		length := binary.BigEndian.Uint32(lengthBuf)

		data := make([]byte, length)
		if _, err := file.Read(data); err != nil {
			break
		}

		var entry WALEntry
		if err := json.Unmarshal(data, &entry); err != nil {
			continue
		}

		if err := callback(entry); err != nil {
			return err
		}
	}

	return nil
}
