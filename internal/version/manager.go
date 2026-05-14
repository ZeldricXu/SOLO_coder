package version

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"backupmanager/internal/storage"
	"backupmanager/pkg/models"
)

const DefaultMaxVersions = 10

type Manager struct {
	storage     *storage.Storage
	maxVersions int
	mu          sync.Mutex
}

func NewManager(storage *storage.Storage) *Manager {
	return &Manager{
		storage:     storage,
		maxVersions: DefaultMaxVersions,
	}
}

func NewManagerWithOptions(storage *storage.Storage, maxVersions int) *Manager {
	if maxVersions <= 0 {
		maxVersions = DefaultMaxVersions
	}
	return &Manager{
		storage:     storage,
		maxVersions: maxVersions,
	}
}

func (m *Manager) SetMaxVersions(count int) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if count <= 0 {
		count = DefaultMaxVersions
	}
	m.maxVersions = count
}

func (m *Manager) GetMaxVersions() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.maxVersions
}

func (m *Manager) CreateVersion(sourcePath string, backupType string) (*models.BackupVersion, error) {
	versionID := generateVersionID()
	versionPath := filepath.Join(m.storage.GetStoragePath(), versionID)

	version := &models.BackupVersion{
		VersionID:  versionID,
		SourcePath: sourcePath,
		BackupPath: versionPath,
		CreatedAt:  time.Now(),
		Type:       backupType,
	}

	return version, nil
}

func (m *Manager) ListVersions(sourcePath string) ([]*models.BackupVersion, error) {
	versions, err := m.storage.LoadVersions(sourcePath)
	if err != nil {
		return nil, err
	}
	return versions, nil
}

func (m *Manager) GetVersion(versionID string) (*models.BackupVersion, error) {
	return m.storage.GetVersionByID(versionID)
}

func (m *Manager) GetLatestVersion(sourcePath string) (*models.BackupVersion, error) {
	return m.storage.GetLatestVersion(sourcePath)
}

func (m *Manager) DeleteVersion(versionID string) error {
	return m.storage.DeleteVersion(versionID)
}

func (m *Manager) UpdateVersionStats(version *models.BackupVersion, fileCount, changedCount int, backupSize int64, checksum string) {
	version.FileCount = fileCount
	version.ChangedCount = changedCount
	version.BackupSize = backupSize
	version.Checksum = checksum
}

func (m *Manager) SaveVersion(version *models.BackupVersion) error {
	if err := m.storage.SaveVersion(version); err != nil {
		return err
	}

	go m.EnforceRetentionPolicy(version.SourcePath)
	return nil
}

func (m *Manager) EnforceRetentionPolicy(sourcePath string) {
	m.mu.Lock()
	maxVersions := m.maxVersions
	m.mu.Unlock()

	if maxVersions <= 0 {
		return
	}

	versions, err := m.storage.LoadVersions(sourcePath)
	if err != nil {
		return
	}

	if len(versions) <= maxVersions {
		return
	}

	sort.Slice(versions, func(i, j int) bool {
		return versions[i].CreatedAt.Before(versions[j].CreatedAt)
	})

	versionsToDelete := len(versions) - maxVersions
	for i := 0; i < versionsToDelete && i < len(versions); i++ {
		version := versions[i]

		if m.IsVersionDependedOn(version.VersionID, versions) {
			continue
		}

		if err := m.storage.DeleteVersion(version.VersionID); err != nil {
			continue
		}
	}
}

func (m *Manager) CleanupOldVersions(sourcePath string, keepCount int) ([]*models.BackupVersion, error) {
	if keepCount <= 0 {
		keepCount = m.GetMaxVersions()
	}

	versions, err := m.storage.LoadVersions(sourcePath)
	if err != nil {
		return nil, err
	}

	if len(versions) <= keepCount {
		return []*models.BackupVersion{}, nil
	}

	sort.Slice(versions, func(i, j int) bool {
		return versions[i].CreatedAt.Before(versions[j].CreatedAt)
	})

	versionsToDelete := len(versions) - keepCount
	deleted := make([]*models.BackupVersion, 0, versionsToDelete)

	for i := 0; i < versionsToDelete && i < len(versions); i++ {
		version := versions[i]

		if m.IsVersionDependedOn(version.VersionID, versions) {
			continue
		}

		if err := m.storage.DeleteVersion(version.VersionID); err != nil {
			continue
		}
		deleted = append(deleted, version)
	}

	return deleted, nil
}

func (m *Manager) IsVersionDependedOn(versionID string, allVersions []*models.BackupVersion) bool {
	if len(allVersions) <= 1 {
		return false
	}

	var sortedVersions []*models.BackupVersion
	for _, v := range allVersions {
		sortedVersions = append(sortedVersions, v)
	}
	sort.Slice(sortedVersions, func(i, j int) bool {
		return sortedVersions[i].CreatedAt.Before(sortedVersions[j].CreatedAt)
	})

	versionIndex := -1
	for i, v := range sortedVersions {
		if v.VersionID == versionID {
			versionIndex = i
			break
		}
	}

	if versionIndex == -1 {
		return false
	}

	if versionIndex == len(sortedVersions)-1 {
		return false
	}

	currentFiles, err := m.storage.LoadFileList(versionID)
	if err != nil || len(currentFiles) == 0 {
		return false
	}

	currentFileMap := make(map[string]*models.FileInfo)
	for _, f := range currentFiles {
		currentFileMap[f.RelativePath] = f
	}

	previousVersionIndex := versionIndex - 1
	var previousFiles map[string]*models.FileInfo
	if previousVersionIndex >= 0 {
		prevFiles, err := m.storage.LoadFileList(sortedVersions[previousVersionIndex].VersionID)
		if err == nil {
			previousFiles = make(map[string]*models.FileInfo)
			for _, f := range prevFiles {
				previousFiles[f.RelativePath] = f
			}
		}
	}

	uniqueFiles := make(map[string]bool)
	for path, file := range currentFileMap {
		if previousFiles == nil {
			uniqueFiles[path] = true
			continue
		}
		if prevFile, exists := previousFiles[path]; !exists || prevFile.Hash != file.Hash {
			uniqueFiles[path] = true
		}
	}

	if len(uniqueFiles) == 0 {
		return false
	}

	for i := versionIndex + 1; i < len(sortedVersions); i++ {
		laterVersion := sortedVersions[i]

		laterFiles, err := m.storage.LoadFileList(laterVersion.VersionID)
		if err != nil {
			continue
		}

		laterFileMap := make(map[string]*models.FileInfo)
		for _, f := range laterFiles {
			laterFileMap[f.RelativePath] = f
		}

		changes, err := m.storage.LoadChangeRecords(laterVersion.VersionID)
		if err != nil {
			continue
		}

		changeMap := make(map[string]*models.FileChangeRecord)
		for _, c := range changes {
			changeMap[c.FilePath] = c
		}

		for path := range uniqueFiles {
			if currentFile, exists := currentFileMap[path]; exists {
				if laterFile, found := laterFileMap[path]; found {
					if change, hasChange := changeMap[path]; hasChange {
						if change.ChangeType == "deleted" {
							continue
						}
						if change.NewHash != currentFile.Hash {
							continue
						}
					} else if laterFile.Hash != currentFile.Hash {
						continue
					}

					versionPath := m.storage.GetVersionPath(laterVersion.VersionID)
					backupFilePath := filepath.Join(versionPath, "files", filepath.FromSlash(path))
					if m.fileExistsOnDisk(backupFilePath) {
						continue
					}

					return true
				} else {
					if change, hasChange := changeMap[path]; hasChange && change.ChangeType == "deleted" {
						continue
					}
					return true
				}
			}
		}
	}

	return false
}

func (m *Manager) fileExistsOnDisk(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func (m *Manager) CheckVersionDependencyChain(sourcePath string) ([]string, error) {
	versions, err := m.storage.LoadVersions(sourcePath)
	if err != nil {
		return nil, err
	}

	if len(versions) <= 1 {
		return []string{}, nil
	}

	sort.Slice(versions, func(i, j int) bool {
		return versions[i].CreatedAt.Before(versions[j].CreatedAt)
	})

	dependentVersions := make([]string, 0)
	for _, v := range versions {
		if m.IsVersionDependedOn(v.VersionID, versions) {
			dependentVersions = append(dependentVersions, v.VersionID)
		}
	}

	return dependentVersions, nil
}

func (m *Manager) ComputeVersionChecksum(versionID string) (string, error) {
	files, err := m.storage.LoadFileList(versionID)
	if err != nil {
		return "", fmt.Errorf("failed to load file list: %w", err)
	}

	hasher := sha256.New()
	for _, file := range files {
		hasher.Write([]byte(file.RelativePath))
		hasher.Write([]byte(file.Hash))
	}

	return "sha256:" + hex.EncodeToString(hasher.Sum(nil)), nil
}

func (m *Manager) GetRetentionPolicy() *models.VersionRetentionPolicy {
	return &models.VersionRetentionPolicy{
		MaxVersions: m.GetMaxVersions(),
	}
}

func (m *Manager) SetRetentionPolicy(policy *models.VersionRetentionPolicy) {
	if policy != nil {
		m.SetMaxVersions(policy.MaxVersions)
	}
}

func generateVersionID() string {
	return fmt.Sprintf("ver_%s", time.Now().Format("20060102_150405"))
}
