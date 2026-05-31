package storage

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"

	"session172/internal/logger"
	"session172/pkg/models"
	"session172/pkg/utils"
)

type StorageManager struct {
	mu           sync.RWMutex
	baseDir      string
	files        map[string]*models.StoredFile
	lifecycleMgr *LifecycleManager
}

type LifecycleManager struct {
	mu         sync.RWMutex
	rules      []LifecycleRule
	fileIndex  map[string]time.Time
}

type LifecycleRule struct {
	ID             string
	Name           string
	Prefix         string
	StorageClass   string
	TransitionDays int
	ExpireDays     int
	Enabled        bool
}

var (
	storageInstance *StorageManager
	storageOnce     sync.Once
)

func NewStorageManager(baseDir string) *StorageManager {
	storageOnce.Do(func() {
		if baseDir == "" {
			baseDir = "./data"
		}

		os.MkdirAll(baseDir, 0755)

		storageInstance = &StorageManager{
			baseDir: baseDir,
			files:   make(map[string]*models.StoredFile),
			lifecycleMgr: &LifecycleManager{
				rules:     make([]LifecycleRule, 0),
				fileIndex: make(map[string]time.Time),
			},
		}

		go storageInstance.lifecycleMgr.run()
	})
	return storageInstance
}

func GetStorageManager() *StorageManager {
	if storageInstance == nil {
		return NewStorageManager("")
	}
	return storageInstance
}

func (sm *StorageManager) Save(path string, data []byte, contentType string) (*models.StoredFile, error) {
	fullPath := filepath.Join(sm.baseDir, path)

	dir := filepath.Dir(fullPath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create directory: %w", err)
	}

	if err := os.WriteFile(fullPath, data, 0644); err != nil {
		return nil, fmt.Errorf("failed to write file: %w", err)
	}

	fileInfo, err := os.Stat(fullPath)
	if err != nil {
		return nil, fmt.Errorf("failed to stat file: %w", err)
	}

	file := &models.StoredFile{
		ID:            utils.GenerateID("file"),
		Path:          path,
		Size:          fileInfo.Size(),
		ContentType:   contentType,
		MD5:           utils.MD5Hash(data),
		StorageClass:  "standard",
		LifecycleDays: 30,
		CreatedAt:     time.Now(),
		LastAccessed:  time.Now(),
	}

	sm.mu.Lock()
	sm.files[file.ID] = file
	sm.lifecycleMgr.fileIndex[file.ID] = time.Now()
	sm.mu.Unlock()

	logger.Infof("File saved: %s (%d bytes)", path, file.Size)
	return file, nil
}

func (sm *StorageManager) SaveReader(path string, reader io.Reader, contentType string) (*models.StoredFile, error) {
	data, err := io.ReadAll(reader)
	if err != nil {
		return nil, fmt.Errorf("failed to read: %w", err)
	}
	return sm.Save(path, data, contentType)
}

func (sm *StorageManager) Get(path string) ([]byte, error) {
	fullPath := filepath.Join(sm.baseDir, path)

	data, err := os.ReadFile(fullPath)
	if err != nil {
		return nil, fmt.Errorf("failed to read file: %w", err)
	}

	sm.mu.Lock()
	for _, file := range sm.files {
		if file.Path == path {
			file.LastAccessed = time.Now()
			break
		}
	}
	sm.mu.Unlock()

	return data, nil
}

func (sm *StorageManager) GetReader(path string) (io.ReadCloser, error) {
	fullPath := filepath.Join(sm.baseDir, path)
	file, err := os.Open(fullPath)
	if err != nil {
		return nil, fmt.Errorf("failed to open file: %w", err)
	}

	sm.mu.Lock()
	for _, f := range sm.files {
		if f.Path == path {
			f.LastAccessed = time.Now()
			break
		}
	}
	sm.mu.Unlock()

	return file, nil
}

func (sm *StorageManager) Delete(path string) error {
	fullPath := filepath.Join(sm.baseDir, path)

	if err := os.Remove(fullPath); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("failed to delete file: %w", err)
	}

	sm.mu.Lock()
	for id, file := range sm.files {
		if file.Path == path {
			delete(sm.files, id)
			delete(sm.lifecycleMgr.fileIndex, id)
			break
		}
	}
	sm.mu.Unlock()

	logger.Infof("File deleted: %s", path)
	return nil
}

func (sm *StorageManager) Exists(path string) bool {
	fullPath := filepath.Join(sm.baseDir, path)
	_, err := os.Stat(fullPath)
	return err == nil
}

func (sm *StorageManager) List(prefix string) ([]*models.StoredFile, error) {
	fullPrefix := filepath.Join(sm.baseDir, prefix)

	var files []*models.StoredFile

	err := filepath.Walk(fullPrefix, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}

		relPath, _ := filepath.Rel(sm.baseDir, path)

		sm.mu.RLock()
		for _, file := range sm.files {
			if file.Path == relPath {
				files = append(files, file)
				break
			}
		}
		sm.mu.RUnlock()

		return nil
	})

	if err != nil && !os.IsNotExist(err) {
		return nil, err
	}

	return files, nil
}

func (sm *StorageManager) GetFileInfo(id string) (*models.StoredFile, bool) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	file, ok := sm.files[id]
	return file, ok
}

func (sm *StorageManager) GetFileByPath(path string) (*models.StoredFile, bool) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	for _, file := range sm.files {
		if file.Path == path {
			return file, true
		}
	}
	return nil, false
}

func (sm *StorageManager) UpdateStorageClass(id string, storageClass string) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	file, ok := sm.files[id]
	if !ok {
		return fmt.Errorf("file not found: %s", id)
	}

	file.StorageClass = storageClass
	logger.Infof("File %s storage class updated to %s", id, storageClass)
	return nil
}

func (sm *StorageManager) AddLifecycleRule(rule LifecycleRule) {
	if rule.ID == "" {
		rule.ID = utils.GenerateID("lcr")
	}

	sm.lifecycleMgr.mu.Lock()
	sm.lifecycleMgr.rules = append(sm.lifecycleMgr.rules, rule)
	sm.lifecycleMgr.mu.Unlock()

	logger.Infof("Lifecycle rule added: %s", rule.ID)
}

func (sm *StorageManager) GetLifecycleRules() []LifecycleRule {
	sm.lifecycleMgr.mu.RLock()
	defer sm.lifecycleMgr.mu.RUnlock()
	return sm.lifecycleMgr.rules
}

func (lm *LifecycleManager) run() {
	ticker := time.NewTicker(1 * time.Hour)
	defer ticker.Stop()

	for range ticker.C {
		lm.checkLifecycle()
	}
}

func (lm *LifecycleManager) checkLifecycle() {
	lm.mu.RLock()
	rules := make([]LifecycleRule, len(lm.rules))
	copy(rules, lm.rules)
	lm.mu.RUnlock()

	sm := GetStorageManager()

	for id, createdAt := range lm.fileIndex {
		file, ok := sm.GetFileInfo(id)
		if !ok {
			continue
		}

		age := time.Since(createdAt)

		for _, rule := range rules {
			if !rule.Enabled {
				continue
			}

			if rule.Prefix != "" && !startsWith(file.Path, rule.Prefix) {
				continue
			}

			if rule.TransitionDays > 0 && age.Hours() > float64(rule.TransitionDays*24) {
				if file.StorageClass != rule.StorageClass {
					sm.UpdateStorageClass(id, rule.StorageClass)
				}
			}

			if rule.ExpireDays > 0 && age.Hours() > float64(rule.ExpireDays*24) {
				sm.Delete(file.Path)
			}
		}
	}
}

func startsWith(s, prefix string) bool {
	return len(s) >= len(prefix) && s[:len(prefix)] == prefix
}

func (sm *StorageManager) Archive(id string) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	file, ok := sm.files[id]
	if !ok {
		return fmt.Errorf("file not found: %s", id)
	}

	file.Archived = true
	file.StorageClass = "archive"
	logger.Infof("File archived: %s", id)
	return nil
}

func (sm *StorageManager) Restore(id string) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	file, ok := sm.files[id]
	if !ok {
		return fmt.Errorf("file not found: %s", id)
	}

	file.Archived = false
	file.StorageClass = "standard"
	logger.Infof("File restored: %s", id)
	return nil
}

func (sm *StorageManager) GetStats() map[string]interface{} {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	var totalSize int64
	var archivedCount int
	var standardCount int
	var archiveCount int

	for _, file := range sm.files {
		totalSize += file.Size
		if file.Archived {
			archivedCount++
		}
		switch file.StorageClass {
		case "standard":
			standardCount++
		case "archive":
			archiveCount++
		}
	}

	return map[string]interface{}{
		"total_files":    len(sm.files),
		"total_size":     totalSize,
		"archived_count": archivedCount,
		"standard_count": standardCount,
		"archive_count":  archiveCount,
	}
}

func (sm *StorageManager) Close() {
	logger.Info("Storage manager closed")
}
