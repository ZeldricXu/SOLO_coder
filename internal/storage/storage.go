package storage

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"fmt"
	"github.com/solocoder/tasktracker/internal/logger"
	"io"
	"io/ioutil"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type StorageType string

const (
	StorageTypeFile   StorageType = "file"
	StorageTypeMemory StorageType = "memory"
)

type BackupInfo struct {
	BackupID   string    `json:"backup_id"`
	Timestamp  time.Time `json:"timestamp"`
	Size       int64   `json:"size"`
	Checksum   string  `json:"checksum"`
	StoreType  StorageType `json:"storage_type"`
	Path       string  `json:"path"`
	Encrypted  bool    `json:"encrypted"`
}

type StorageManager struct {
	mu          sync.RWMutex
	baseDir     string
	backups     map[string]BackupInfo
	memStore     map[string][]byte
	encryptKey  []byte
}

type Config struct {
	BaseDir    string `json:"base_dir"`
	EncryptKey string `json:"encrypt_key"`
}

func NewStorageManager(cfg Config) *StorageManager {
	if cfg.BaseDir == "" {
		cfg.BaseDir = "./data"
	}

	sm := &StorageManager{
		baseDir:    cfg.BaseDir,
		backups:    make(map[string]BackupInfo),
		memStore:   make(map[string][]byte),
		encryptKey: []byte(cfg.EncryptKey),
	}

	os.MkdirAll(cfg.BaseDir, 0755)
	os.MkdirAll(filepath.Join(cfg.BaseDir, "backups"), 0755)
	return sm
}

func (sm *StorageManager) Save(key string, data interface{}) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	jsonData, err := json.MarshalIndent(data, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal data: %w", err)
	}

	sm.memStore[key] = jsonData

	filePath := sm.getFilePath(key)
	if err := ioutil.WriteFile(filePath, jsonData, 0644); err != nil {
		return fmt.Errorf("failed to write file: %w", err)
	}

	logger.Info("Data saved", logger.String("key", key), logger.Int("size", len(jsonData)))
	return nil
}

func (sm *StorageManager) Load(key string, v interface{}) error {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	var data []byte
	var err error

	data, ok := sm.memStore[key]
	if !ok {
		filePath := sm.getFilePath(key)
		data, err = ioutil.ReadFile(filePath)
		if err != nil {
			return fmt.Errorf("failed to read file: %w", err)
		}
	}

	if err := json.Unmarshal(data, v); err != nil {
		return fmt.Errorf("failed to unmarshal data: %w", err)
	}

	logger.Info("Data loaded", logger.String("key", key))
	return nil
}

func (sm *StorageManager) Delete(key string) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	delete(sm.memStore, key)

	filePath := sm.getFilePath(key)
	if err := os.Remove(filePath); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("failed to delete file: %w", err)
	}

	logger.Info("Data deleted", logger.String("key", key))
	return nil
}

func (sm *StorageManager) Exists(key string) bool {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	if _, ok := sm.memStore[key]; ok {
		return true
	}

	filePath := sm.getFilePath(key)
	_, err := os.Stat(filePath)
	return err == nil
}

func (sm *StorageManager) Backup(key string) (*BackupInfo, error) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	backupID := fmt.Sprintf("backup_%d", time.Now().UnixNano())
	backupPath := filepath.Join(sm.baseDir, "backups", backupID+".gz")

	filePath := sm.getFilePath(key)
	data, err := ioutil.ReadFile(filePath)
	if err != nil {
		return nil, fmt.Errorf("failed to read source file: %w", err)
	}

	compressed, err := compressData(data)
	if err != nil {
		return nil, fmt.Errorf("failed to compress data: %w", err)
	}

	if err := ioutil.WriteFile(backupPath, compressed, 0644); err != nil {
		return nil, fmt.Errorf("failed to write backup: %w", err)
	}

	backupInfo := BackupInfo{
		BackupID:  backupID,
		Timestamp: time.Now(),
		Size:      int64(len(data)),
		Checksum:  fmt.Sprintf("%x", len(data)),
		StoreType: StorageTypeFile,
		Path:      backupPath,
		Encrypted: len(sm.encryptKey) > 0,
	}

	sm.backups[backupID] = backupInfo

	logger.Info("Backup created", logger.String("backup_id", backupID), logger.String("key", key), logger.Int64("size", backupInfo.Size))
	return &backupInfo, nil
}

func (sm *StorageManager) Restore(backupID string, key string) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	backupInfo, ok := sm.backups[backupID]
	if !ok {
		return fmt.Errorf("backup not found: %s", backupID)
	}

	compressed, err := ioutil.ReadFile(backupInfo.Path)
	if err != nil {
		return fmt.Errorf("failed to read backup file: %w", err)
	}

	data, err := decompressData(compressed)
	if err != nil {
		return fmt.Errorf("failed to decompress data: %w", err)
	}

	filePath := sm.getFilePath(key)
	if err := ioutil.WriteFile(filePath, data, 0644); err != nil {
		return fmt.Errorf("failed to write restored file: %w", err)
	}

	sm.memStore[key] = data

	logger.Info("Data restored", logger.String("backup_id", backupID), logger.String("key", key))
	return nil
}

func (sm *StorageManager) ListBackups() []BackupInfo {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	result := make([]BackupInfo, 0, len(sm.backups))
	for _, backup := range sm.backups {
		result = append(result, backup)
	}
	return result
}

func (sm *StorageManager) DeleteBackup(backupID string) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	backupInfo, ok := sm.backups[backupID]
	if !ok {
		return fmt.Errorf("backup not found: %s", backupID)
	}

	if err := os.Remove(backupInfo.Path); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("failed to delete backup file: %w", err)
	}

	delete(sm.backups, backupID)
	logger.Info("Backup deleted", logger.String("backup_id", backupID))
	return nil
}

func (sm *StorageManager) ListKeys() []string {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	keys := make([]string, 0, len(sm.memStore))
	for k := range sm.memStore {
		keys = append(keys, k)
	}

	files, _ := ioutil.ReadDir(sm.baseDir)
	for _, f := range files {
		if !f.IsDir() && filepath.Ext(f.Name()) == ".json" {
			key := f.Name()[:len(f.Name())-5]
			if _, exists := sm.memStore[key]; !exists {
				keys = append(keys, key)
			}
		}
	}

	return keys
}

func (sm *StorageManager) AutoBackup(keys []string, interval time.Duration) chan struct{} {
	ticker := time.NewTicker(interval)
	stop := make(chan struct{})

	go func() {
		for {
			select {
			case <-ticker.C:
				for _, key := range keys {
					if _, err := sm.Backup(key); err != nil {
					logger.Error("Auto backup failed", logger.String("key", key), logger.ErrorField(err))
				}
			}
			case <-stop:
				ticker.Stop()
				return
			}
		}
	}()

	return stop
}

func (sm *StorageManager) CleanOldBackups(maxAge time.Duration) int {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	count := 0
	cutoff := time.Now().Add(-maxAge)

	for id, backup := range sm.backups {
		if backup.Timestamp.Before(cutoff) {
			os.Remove(backup.Path)
			delete(sm.backups, id)
			count++
		}
	}

	logger.Info("Old backups cleaned", logger.Int("count", count))
	return count
}

func (sm *StorageManager) getFilePath(key string) string {
	return filepath.Join(sm.baseDir, key+".json")
}

func compressData(data []byte) ([]byte, error) {
	pr, pw := io.Pipe()

	gw := gzip.NewWriter(pw)

	go func() {
		_, err := gw.Write(data)
		gw.Close()
		pw.CloseWithError(err)
	}()

	return ioutil.ReadAll(pr)
}

func decompressData(data []byte) ([]byte, error) {
	gr, err := gzip.NewReader(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	defer gr.Close()
	return ioutil.ReadAll(gr)
}
