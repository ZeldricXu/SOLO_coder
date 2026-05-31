package storage

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/gob"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/solocoder/backup-engine/internal/logger"
	"github.com/solocoder/backup-engine/pkg/common"
)

type BackupFormat int

const (
	FormatTar BackupFormat = iota
	FormatZip
	FormatDirectory
)

type StorageManager struct {
	config      common.BackupConfig
	backups     map[string]*common.BackupInfo
	mu          sync.RWMutex
	basePath    string
	wal         *WAL
	snapshotMgr *SnapshotManager
	sequence    int64
}

func NewStorageManager(basePath string, config common.BackupConfig) (*StorageManager, error) {
	if basePath == "" {
		basePath = "./backups"
	}
	if err := os.MkdirAll(basePath, 0755); err != nil {
		return nil, common.NewBackupError("init", err)
	}

	walPath := filepath.Join(basePath, "wal")
	wal, err := NewWAL(walPath)
	if err != nil {
		return nil, common.NewBackupError("wal_init", err)
	}

	snapshotPath := filepath.Join(basePath, "snapshots")
	snapshotMgr, err := NewSnapshotManager(snapshotPath)
	if err != nil {
		return nil, common.NewBackupError("snapshot_init", err)
	}

	sm := &StorageManager{
		config:      config,
		backups:     make(map[string]*common.BackupInfo),
		basePath:    basePath,
		wal:         wal,
		snapshotMgr: snapshotMgr,
	}

	if err := sm.recover(); err != nil {
		logger.Warn("Recovery from WAL failed, attempting snapshot restore", map[string]interface{}{
			"error": err.Error(),
		})
		if snapErr := sm.restoreLatestSnapshot(); snapErr != nil {
			logger.Warn("Snapshot restore also failed", map[string]interface{}{
				"error": snapErr.Error(),
			})
		}
	}

	go sm.snapshotLoop()

	return sm, nil
}

func (sm *StorageManager) Backup(ctx context.Context, name string, data []byte) (*common.BackupInfo, error) {
	select {
	case <-ctx.Done():
		return nil, common.NewBackupError("backup", ctx.Err())
	default:
	}

	logger.Info("Starting backup", map[string]interface{}{
		"name":   name,
		"source": sm.config.Source,
	})

	sm.mu.Lock()
	defer sm.mu.Unlock()

	backupID := common.NewID()
	backupPath := filepath.Join(sm.basePath, backupID)

	var processedData = data
	var compressed, encrypted bool

	if sm.config.Compression != "" && sm.config.Compression != "none" {
		compressedData, err := sm.compress(data)
		if err != nil {
			logger.Error("Compression failed", map[string]interface{}{"error": err.Error()})
			return nil, common.NewBackupError("compress", err)
		}
		processedData = compressedData
		compressed = true
	}

	if sm.config.EncryptionKey != "" {
		encryptedData, err := sm.encrypt(processedData, sm.config.EncryptionKey)
		if err != nil {
			logger.Error("Encryption failed", map[string]interface{}{"error": err.Error()})
			return nil, common.NewBackupError("encrypt", err)
		}
		processedData = encryptedData
		encrypted = true
	}

	if err := os.WriteFile(backupPath, processedData, 0644); err != nil {
		return nil, common.NewBackupError("write", err)
	}

	info := &common.BackupInfo{
		ID:         backupID,
		Name:       name,
		Source:     sm.config.Source,
		Size:       int64(len(processedData)),
		CreatedAt:  time.Now(),
		Checksum:   common.CalculateChecksum(data),
		Encrypted:  encrypted,
		Compressed: compressed,
	}

	sm.backups[backupID] = info

	metadataPath := backupPath + ".meta"
	if err := sm.saveMetadata(metadataPath, info); err != nil {
		logger.Warn("Failed to save metadata", map[string]interface{}{"error": err.Error()})
	}

	seq := atomic.AddInt64(&sm.sequence, 1)
	infoBytes, _ := json.Marshal(info)
	walEntry := &common.WALEntry{
		Sequence:  seq,
		Operation: "backup",
		Key:       backupID,
		Data:      infoBytes,
		Timestamp: time.Now(),
		Checksum:  common.CalculateChecksum(infoBytes),
	}
	if err := sm.wal.Append(walEntry); err != nil {
		logger.Warn("Failed to write WAL entry", map[string]interface{}{"error": err.Error()})
	}

	logger.Info("Backup completed successfully", map[string]interface{}{
		"backup_id": backupID,
		"size":      info.Size,
		"checksum":  info.Checksum,
	})

	return info, nil
}

func (sm *StorageManager) Restore(ctx context.Context, backupID string) ([]byte, error) {
	select {
	case <-ctx.Done():
		return nil, common.NewBackupError("restore", ctx.Err())
	default:
	}

	logger.Info("Starting restore", map[string]interface{}{"backup_id": backupID})

	sm.mu.RLock()
	info, exists := sm.backups[backupID]
	sm.mu.RUnlock()

	if !exists {
		return nil, common.NewBackupError("restore", common.ErrNotFound)
	}

	backupPath := filepath.Join(sm.basePath, backupID)
	data, err := os.ReadFile(backupPath)
	if err != nil {
		return nil, common.NewBackupError("read", err)
	}

	var processedData = data

	if info.Encrypted {
		decryptedData, err := sm.decrypt(data, sm.config.EncryptionKey)
		if err != nil {
			logger.Error("Decryption failed", map[string]interface{}{"error": err.Error()})
			return nil, common.NewBackupError("decrypt", err)
		}
		processedData = decryptedData
	}

	if info.Compressed {
		decompressedData, err := sm.decompress(processedData)
		if err != nil {
			logger.Error("Decompression failed", map[string]interface{}{"error": err.Error()})
			return nil, common.NewBackupError("decompress", err)
		}
		processedData = decompressedData
	}

	checksum := common.CalculateChecksum(processedData)
	if checksum != info.Checksum {
		return nil, common.NewBackupError("verify", fmt.Errorf("checksum mismatch: expected %s, got %s", info.Checksum, checksum))
	}

	seq := atomic.AddInt64(&sm.sequence, 1)
	restoreData, _ := json.Marshal(map[string]string{"backup_id": backupID, "status": "success"})
	walEntry := &common.WALEntry{
		Sequence:  seq,
		Operation: "restore",
		Key:       backupID,
		Data:      restoreData,
		Timestamp: time.Now(),
		Checksum:  common.CalculateChecksum(restoreData),
	}
	if err := sm.wal.Append(walEntry); err != nil {
		logger.Warn("Failed to write WAL entry for restore", map[string]interface{}{"error": err.Error()})
	}

	logger.Info("Restore completed successfully", map[string]interface{}{
		"backup_id": backupID,
		"size":      len(processedData),
	})

	return processedData, nil
}

func (sm *StorageManager) List() []*common.BackupInfo {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	list := make([]*common.BackupInfo, 0, len(sm.backups))
	for _, info := range sm.backups {
		list = append(list, info)
	}
	return list
}

func (sm *StorageManager) Delete(backupID string) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	if _, exists := sm.backups[backupID]; !exists {
		return common.NewBackupError("delete", common.ErrNotFound)
	}

	backupPath := filepath.Join(sm.basePath, backupID)
	metadataPath := backupPath + ".meta"

	if err := os.Remove(backupPath); err != nil {
		return common.NewBackupError("delete", err)
	}
	os.Remove(metadataPath)

	delete(sm.backups, backupID)

	seq := atomic.AddInt64(&sm.sequence, 1)
	deleteData, _ := json.Marshal(map[string]string{"backup_id": backupID})
	walEntry := &common.WALEntry{
		Sequence:  seq,
		Operation: "delete",
		Key:       backupID,
		Data:      deleteData,
		Timestamp: time.Now(),
		Checksum:  common.CalculateChecksum(deleteData),
	}
	if err := sm.wal.Append(walEntry); err != nil {
		logger.Warn("Failed to write WAL entry for delete", map[string]interface{}{"error": err.Error()})
	}

	logger.Info("Backup deleted", map[string]interface{}{"backup_id": backupID})
	return nil
}

func (sm *StorageManager) Cleanup() error {
	if sm.config.RetentionDays <= 0 {
		return nil
	}

	cutoff := time.Now().AddDate(0, 0, -sm.config.RetentionDays)
	var toDelete []string

	sm.mu.RLock()
	for id, info := range sm.backups {
		if info.CreatedAt.Before(cutoff) {
			toDelete = append(toDelete, id)
		}
	}
	sm.mu.RUnlock()

	for _, id := range toDelete {
		if err := sm.Delete(id); err != nil {
			logger.Warn("Failed to delete expired backup", map[string]interface{}{
				"backup_id": id,
				"error":     err.Error(),
			})
		}
	}

	logger.Info("Cleanup completed", map[string]interface{}{"deleted_count": len(toDelete)})
	return nil
}

func (sm *StorageManager) CreateSnapshot() (*common.Snapshot, error) {
	sm.mu.RLock()
	data := make(map[string]*common.BackupInfo, len(sm.backups))
	for k, v := range sm.backups {
		data[k] = v
	}
	sm.mu.RUnlock()

	snapshot, err := sm.snapshotMgr.Create(data)
	if err != nil {
		return nil, common.NewBackupError("snapshot", err)
	}

	if err := sm.wal.Truncate(); err != nil {
		logger.Warn("Failed to truncate WAL after snapshot", map[string]interface{}{"error": err.Error()})
	}

	logger.Info("Snapshot created", map[string]interface{}{
		"snapshot_id": snapshot.ID,
		"entries":     len(data),
	})

	return snapshot, nil
}

func (sm *StorageManager) restoreLatestSnapshot() error {
	snapshot, err := sm.snapshotMgr.LoadLatest()
	if err != nil {
		return err
	}

	sm.mu.Lock()
	sm.backups = make(map[string]*common.BackupInfo, len(snapshot.Data))
	for k, v := range snapshot.Data {
		sm.backups[k] = v
	}
	sm.mu.Unlock()

	atomic.StoreInt64(&sm.sequence, 0)

	logger.Info("Restored from snapshot", map[string]interface{}{
		"snapshot_id": snapshot.ID,
		"entries":     len(snapshot.Data),
	})
	return nil
}

func (sm *StorageManager) recover() error {
	entries, err := sm.wal.ReadAll()
	if err != nil {
		return err
	}

	if len(entries) == 0 {
		snap, snapErr := sm.snapshotMgr.LoadLatest()
		if snapErr != nil {
			return sm.loadFromDiskFallback()
		}
		sm.mu.Lock()
		sm.backups = make(map[string]*common.BackupInfo, len(snap.Data))
		for k, v := range snap.Data {
			sm.backups[k] = v
		}
		sm.mu.Unlock()
		return nil
	}

	snap, snapErr := sm.snapshotMgr.LoadLatest()
	if snapErr == nil && snap != nil {
		sm.mu.Lock()
		sm.backups = make(map[string]*common.BackupInfo, len(snap.Data))
		for k, v := range snap.Data {
			sm.backups[k] = v
		}
		sm.mu.Unlock()
	} else {
		sm.mu.Lock()
		sm.backups = make(map[string]*common.BackupInfo)
		sm.mu.Unlock()
	}

	var maxSeq int64
	for _, entry := range entries {
		if entry.Sequence > maxSeq {
			maxSeq = entry.Sequence
		}

		switch entry.Operation {
		case "backup":
			var info common.BackupInfo
			if err := json.Unmarshal(entry.Data, &info); err == nil {
				sm.mu.Lock()
				sm.backups[entry.Key] = &info
				sm.mu.Unlock()
			}
		case "delete":
			sm.mu.Lock()
			delete(sm.backups, entry.Key)
			sm.mu.Unlock()
		case "restore":
		}
	}

	atomic.StoreInt64(&sm.sequence, maxSeq)

	logger.Info("Recovery completed", map[string]interface{}{
		"wal_entries": len(entries),
		"backups":     len(sm.backups),
	})
	return nil
}

func (sm *StorageManager) loadFromDiskFallback() error {
	files, err := os.ReadDir(sm.basePath)
	if err != nil {
		return err
	}

	for _, file := range files {
		if strings.HasSuffix(file.Name(), ".meta") {
			fullPath := filepath.Join(sm.basePath, file.Name())
			info, err := sm.loadMetadata(fullPath)
			if err != nil {
				logger.Warn("Failed to load backup metadata", map[string]interface{}{
					"file":  file.Name(),
					"error": err.Error(),
				})
				continue
			}
			sm.mu.Lock()
			sm.backups[info.ID] = info
			sm.mu.Unlock()
		}
	}

	logger.Info("Loaded backups from disk (fallback)", map[string]interface{}{
		"count": len(sm.backups),
	})
	return nil
}

func (sm *StorageManager) snapshotLoop() {
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		if len(sm.backups) > 0 {
			if _, err := sm.CreateSnapshot(); err != nil {
				logger.Warn("Periodic snapshot failed", map[string]interface{}{
					"error": err.Error(),
				})
			}
		}
	}
}

func (sm *StorageManager) LoadFromDisk() error {
	return sm.loadFromDiskFallback()
}

func (sm *StorageManager) compress(data []byte) ([]byte, error) {
	return data, nil
}

func (sm *StorageManager) decompress(data []byte) ([]byte, error) {
	return data, nil
}

func (sm *StorageManager) encrypt(data []byte, key string) ([]byte, error) {
	keyBytes := []byte(key)
	if len(keyBytes) < 32 {
		padded := make([]byte, 32)
		copy(padded, keyBytes)
		keyBytes = padded
	} else if len(keyBytes) > 32 {
		keyBytes = keyBytes[:32]
	}

	block, err := aes.NewCipher(keyBytes)
	if err != nil {
		return nil, err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}

	nonce := make([]byte, gcm.NonceSize())
	if _, err = io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}

	ciphertext := gcm.Seal(nonce, nonce, data, nil)
	return ciphertext, nil
}

func (sm *StorageManager) decrypt(data []byte, key string) ([]byte, error) {
	keyBytes := []byte(key)
	if len(keyBytes) < 32 {
		padded := make([]byte, 32)
		copy(padded, keyBytes)
		keyBytes = padded
	} else if len(keyBytes) > 32 {
		keyBytes = keyBytes[:32]
	}

	block, err := aes.NewCipher(keyBytes)
	if err != nil {
		return nil, err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}

	nonceSize := gcm.NonceSize()
	if len(data) < nonceSize {
		return nil, fmt.Errorf("ciphertext too short")
	}

	nonce, ciphertext := data[:nonceSize], data[nonceSize:]
	plaintext, err := gcm.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return nil, err
	}

	return plaintext, nil
}

func (sm *StorageManager) saveMetadata(path string, info *common.BackupInfo) error {
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()
	return gob.NewEncoder(file).Encode(info)
}

func (sm *StorageManager) loadMetadata(path string) (*common.BackupInfo, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	var info common.BackupInfo
	if err := gob.NewDecoder(file).Decode(&info); err != nil {
		return nil, err
	}
	return &info, nil
}

type BackupResult struct {
	Info  *common.BackupInfo
	Error error
}

func (sm *StorageManager) BackupMultiple(ctx context.Context, items map[string][]byte) []BackupResult {
	sem := make(chan struct{}, common.Max(1, sm.config.MaxParallel))
	var wg sync.WaitGroup
	results := make([]BackupResult, 0, len(items))
	mu := sync.Mutex{}

	for name, data := range items {
		select {
		case <-ctx.Done():
			logger.Warn("Backup cancelled", map[string]interface{}{"remaining": len(items)})
			return results
		default:
		}

		sem <- struct{}{}
		wg.Add(1)

		go func(name string, data []byte) {
			defer wg.Done()
			defer func() { <-sem }()

			info, err := sm.Backup(ctx, name, data)
			mu.Lock()
			results = append(results, BackupResult{Info: info, Error: err})
			mu.Unlock()
		}(name, data)
	}

	wg.Wait()
	return results
}

type WAL struct {
	path    string
	file    *os.File
	mu      sync.Mutex
	encoder *json.Encoder
}

func NewWAL(path string) (*WAL, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return nil, err
	}

	file, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err != nil {
		return nil, err
	}

	return &WAL{
		path:    path,
		file:    file,
		encoder: json.NewEncoder(file),
	}, nil
}

func (w *WAL) Append(entry *common.WALEntry) error {
	w.mu.Lock()
	defer w.mu.Unlock()

	if w.file == nil {
		return fmt.Errorf("WAL file is closed")
	}

	if err := w.encoder.Encode(entry); err != nil {
		return err
	}

	return w.file.Sync()
}

func (w *WAL) ReadAll() ([]*common.WALEntry, error) {
	w.mu.Lock()
	defer w.mu.Unlock()

	if w.file != nil {
		w.file.Close()
	}

	file, err := os.Open(w.path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	defer file.Close()

	var entries []*common.WALEntry
	decoder := json.NewDecoder(file)
	for decoder.More() {
		var entry common.WALEntry
		if err := decoder.Decode(&entry); err != nil {
			break
		}
		entries = append(entries, &entry)
	}

	reopened, err := os.OpenFile(w.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err != nil {
		return nil, err
	}
	w.file = reopened
	w.encoder = json.NewEncoder(reopened)

	return entries, nil
}

func (w *WAL) Truncate() error {
	w.mu.Lock()
	defer w.mu.Unlock()

	if w.file != nil {
		w.file.Close()
	}

	file, err := os.OpenFile(w.path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644)
	if err != nil {
		return err
	}
	w.file = file
	w.encoder = json.NewEncoder(file)

	return nil
}

func (w *WAL) Close() error {
	w.mu.Lock()
	defer w.mu.Unlock()

	if w.file != nil {
		return w.file.Close()
	}
	return nil
}

type SnapshotManager struct {
	path string
	mu   sync.Mutex
}

func NewSnapshotManager(path string) (*SnapshotManager, error) {
	if err := os.MkdirAll(path, 0755); err != nil {
		return nil, err
	}
	return &SnapshotManager{path: path}, nil
}

func (sm *SnapshotManager) Create(data map[string]*common.BackupInfo) (*common.Snapshot, error) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	snapshot := &common.Snapshot{
		ID:        common.NewID(),
		CreatedAt: time.Now(),
		Data:      data,
	}

	snapshotBytes, err := json.Marshal(data)
	if err != nil {
		return nil, err
	}
	snapshot.Checksum = common.CalculateChecksum(snapshotBytes)

	filename := fmt.Sprintf("snapshot_%s.snap", snapshot.ID)
	filePath := filepath.Join(sm.path, filename)

	file, err := os.Create(filePath)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	if err := json.NewEncoder(file).Encode(snapshot); err != nil {
		os.Remove(filePath)
		return nil, err
	}

	sm.cleanupOldSnapshots(5)

	return snapshot, nil
}

func (sm *SnapshotManager) LoadLatest() (*common.Snapshot, error) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	entries, err := os.ReadDir(sm.path)
	if err != nil {
		return nil, err
	}

	var latestFile string
	var latestTime time.Time
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".snap") {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		if info.ModTime().After(latestTime) {
			latestTime = info.ModTime()
			latestFile = entry.Name()
		}
	}

	if latestFile == "" {
		return nil, fmt.Errorf("no snapshots found")
	}

	filePath := filepath.Join(sm.path, latestFile)
	file, err := os.Open(filePath)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	var snapshot common.Snapshot
	if err := json.NewDecoder(file).Decode(&snapshot); err != nil {
		return nil, err
	}

	return &snapshot, nil
}

func (sm *SnapshotManager) ListSnapshots() ([]*common.Snapshot, error) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	entries, err := os.ReadDir(sm.path)
	if err != nil {
		return nil, err
	}

	var snapshots []*common.Snapshot
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".snap") {
			continue
		}

		filePath := filepath.Join(sm.path, entry.Name())
		file, err := os.Open(filePath)
		if err != nil {
			continue
		}

		var snapshot common.Snapshot
		if err := json.NewDecoder(file).Decode(&snapshot); err != nil {
			file.Close()
			continue
		}
		file.Close()
		snapshots = append(snapshots, &snapshot)
	}

	return snapshots, nil
}

func (sm *SnapshotManager) cleanupOldSnapshots(keep int) {
	entries, err := os.ReadDir(sm.path)
	if err != nil {
		return
	}

	var snapFiles []os.DirEntry
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(entry.Name(), ".snap") {
			snapFiles = append(snapFiles, entry)
		}
	}

	if len(snapFiles) <= keep {
		return
	}

	for i := 0; i < len(snapFiles)-keep; i++ {
		filePath := filepath.Join(sm.path, snapFiles[i].Name())
		os.Remove(filePath)
	}
}
