package storage

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"syscall"

	"backupmanager/pkg/models"
)

type Storage struct {
	rootPath string
	mu       sync.Mutex
}

func NewStorage(rootPath string) *Storage {
	return &Storage{
		rootPath: rootPath,
	}
}

func (s *Storage) GetRootPath() string {
	return s.rootPath
}

func (s *Storage) Init() error {
	if _, err := os.Stat(s.rootPath); os.IsNotExist(err) {
		if err := os.MkdirAll(s.rootPath, 0755); err != nil {
			return fmt.Errorf("failed to create storage root: %w", err)
		}
	}
	metaPath := filepath.Join(s.rootPath, "metadata")
	if err := os.MkdirAll(metaPath, 0755); err != nil {
		return fmt.Errorf("failed to create metadata directory: %w", err)
	}
	return nil
}

func (s *Storage) CreateVersionDirectory(versionID string) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	versionPath := filepath.Join(s.rootPath, "versions", versionID)
	if err := os.MkdirAll(versionPath, 0755); err != nil {
		return "", fmt.Errorf("failed to create version directory: %w", err)
	}
	return versionPath, nil
}

func (s *Storage) CopyFile(srcPath, destPath string) error {
	if err := os.MkdirAll(filepath.Dir(destPath), 0755); err != nil {
		return fmt.Errorf("failed to create destination directory: %w", err)
	}

	src, err := os.Open(srcPath)
	if err != nil {
		return fmt.Errorf("failed to open source file: %w", err)
	}
	defer src.Close()

	dest, err := os.Create(destPath)
	if err != nil {
		return fmt.Errorf("failed to create destination file: %w", err)
	}
	defer dest.Close()

	if _, err := io.Copy(dest, src); err != nil {
		return fmt.Errorf("failed to copy file: %w", err)
	}

	if err := dest.Sync(); err != nil {
		return fmt.Errorf("failed to sync file: %w", err)
	}

	srcInfo, err := os.Stat(srcPath)
	if err == nil {
		os.Chmod(destPath, srcInfo.Mode())
		os.Chtimes(destPath, srcInfo.ModTime(), srcInfo.ModTime())
	}

	return nil
}

func (s *Storage) SaveVersion(version *models.BackupVersion) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	metaPath := filepath.Join(s.rootPath, "metadata", "versions.json")
	var versions []*models.BackupVersion

	if data, err := os.ReadFile(metaPath); err == nil {
		json.Unmarshal(data, &versions)
	}

	versions = append(versions, version)
	data, err := json.MarshalIndent(versions, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal versions: %w", err)
	}

	if err := os.WriteFile(metaPath, data, 0644); err != nil {
		return fmt.Errorf("failed to save versions: %w", err)
	}
	return nil
}

func (s *Storage) LoadVersions(sourcePath string) ([]*models.BackupVersion, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	metaPath := filepath.Join(s.rootPath, "metadata", "versions.json")
	var versions []*models.BackupVersion

	if data, err := os.ReadFile(metaPath); err != nil {
		if os.IsNotExist(err) {
			return []*models.BackupVersion{}, nil
		}
		return nil, fmt.Errorf("failed to read versions: %w", err)
	} else {
		if err := json.Unmarshal(data, &versions); err != nil {
			return nil, fmt.Errorf("failed to parse versions: %w", err)
		}
	}

	if sourcePath == "" {
		return versions, nil
	}

	filtered := make([]*models.BackupVersion, 0)
	for _, v := range versions {
		if v.SourcePath == sourcePath {
			filtered = append(filtered, v)
		}
	}
	return filtered, nil
}

func (s *Storage) GetLatestVersion(sourcePath string) (*models.BackupVersion, error) {
	versions, err := s.LoadVersions(sourcePath)
	if err != nil {
		return nil, err
	}

	if len(versions) == 0 {
		return nil, nil
	}

	var latest *models.BackupVersion
	for _, v := range versions {
		if latest == nil || v.CreatedAt.After(latest.CreatedAt) {
			latest = v
		}
	}
	return latest, nil
}

func (s *Storage) GetVersionByID(versionID string) (*models.BackupVersion, error) {
	versions, err := s.LoadVersions("")
	if err != nil {
		return nil, err
	}

	for _, v := range versions {
		if v.VersionID == versionID {
			return v, nil
		}
	}
	return nil, fmt.Errorf("version not found: %s", versionID)
}

func (s *Storage) DeleteVersion(versionID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	metaPath := filepath.Join(s.rootPath, "metadata", "versions.json")
	var versions []*models.BackupVersion

	if data, err := os.ReadFile(metaPath); err == nil {
		json.Unmarshal(data, &versions)
	}

	found := false
	newVersions := make([]*models.BackupVersion, 0)
	var versionToDelete *models.BackupVersion
	for _, v := range versions {
		if v.VersionID == versionID {
			found = true
			versionToDelete = v
		} else {
			newVersions = append(newVersions, v)
		}
	}

	if !found {
		return fmt.Errorf("version not found: %s", versionID)
	}

	if versionToDelete != nil {
		versionPath := filepath.Join(s.rootPath, "versions", versionID)
		if _, err := os.Stat(versionPath); err == nil {
			if err := os.RemoveAll(versionPath); err != nil {
				return fmt.Errorf("failed to delete version directory: %w", err)
			}
		}
	}

	data, err := json.MarshalIndent(newVersions, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal versions: %w", err)
	}

	if err := os.WriteFile(metaPath, data, 0644); err != nil {
		return fmt.Errorf("failed to save versions: %w", err)
	}
	return nil
}

func (s *Storage) SaveFileList(versionID string, files []*models.FileInfo) error {
	versionPath := filepath.Join(s.rootPath, "versions", versionID)
	listPath := filepath.Join(versionPath, "filelist.json")

	data, err := json.MarshalIndent(files, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal file list: %w", err)
	}

	if err := os.WriteFile(listPath, data, 0644); err != nil {
		return fmt.Errorf("failed to save file list: %w", err)
	}
	return nil
}

func (s *Storage) LoadFileList(versionID string) ([]*models.FileInfo, error) {
	versionPath := filepath.Join(s.rootPath, "versions", versionID)
	listPath := filepath.Join(versionPath, "filelist.json")

	var files []*models.FileInfo
	if data, err := os.ReadFile(listPath); err != nil {
		if os.IsNotExist(err) {
			return []*models.FileInfo{}, nil
		}
		return nil, fmt.Errorf("failed to read file list: %w", err)
	} else {
		if err := json.Unmarshal(data, &files); err != nil {
			return nil, fmt.Errorf("failed to parse file list: %w", err)
		}
	}
	return files, nil
}

func (s *Storage) SaveChangeRecords(versionID string, changes []*models.FileChangeRecord) error {
	versionPath := filepath.Join(s.rootPath, "versions", versionID)
	changesPath := filepath.Join(versionPath, "changes.json")

	data, err := json.MarshalIndent(changes, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal change records: %w", err)
	}

	if err := os.WriteFile(changesPath, data, 0644); err != nil {
		return fmt.Errorf("failed to save change records: %w", err)
	}
	return nil
}

func (s *Storage) LoadChangeRecords(versionID string) ([]*models.FileChangeRecord, error) {
	versionPath := filepath.Join(s.rootPath, "versions", versionID)
	changesPath := filepath.Join(versionPath, "changes.json")

	var changes []*models.FileChangeRecord
	if data, err := os.ReadFile(changesPath); err != nil {
		if os.IsNotExist(err) {
			return []*models.FileChangeRecord{}, nil
		}
		return nil, fmt.Errorf("failed to read change records: %w", err)
	} else {
		if err := json.Unmarshal(data, &changes); err != nil {
			return nil, fmt.Errorf("failed to parse change records: %w", err)
		}
	}
	return changes, nil
}

func (s *Storage) GetDiskUsage() (used, free, total uint64, err error) {
	fs := syscall.Statfs_t{}
	if err := syscall.Statfs(s.rootPath, &fs); err != nil {
		return 0, 0, 0, fmt.Errorf("failed to get disk usage: %w", err)
	}

	total = fs.Blocks * uint64(fs.Bsize)
	free = fs.Bfree * uint64(fs.Bsize)
	used = total - free
	return used, free, total, nil
}

func (s *Storage) GetStoragePath() string {
	return filepath.Join(s.rootPath, "versions")
}

func (s *Storage) GetVersionPath(versionID string) string {
	return filepath.Join(s.rootPath, "versions", versionID)
}
