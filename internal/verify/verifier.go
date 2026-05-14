package verify

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"time"

	"backupmanager/internal/storage"
	"backupmanager/internal/version"
	"backupmanager/pkg/models"
)

type Verifier struct {
	storage *storage.Storage
	manager *version.Manager
}

func NewVerifier(storage *storage.Storage, manager *version.Manager) *Verifier {
	return &Verifier{
		storage: storage,
		manager: manager,
	}
}

type VerifyResult struct {
	VersionID       string
	IsValid         bool
	TotalFiles      int
	ValidFiles      int
	InvalidFiles    []string
	MissingFiles    []string
	Errors          []string
	ChecksumMatch   bool
	ExpectedChecksum string
	ActualChecksum   string
	Duration        time.Duration
}

func (v *Verifier) VerifyVersion(versionID string) (*VerifyResult, error) {
	startTime := time.Now()
	result := &VerifyResult{
		VersionID: versionID,
	}

	versionInfo, err := v.manager.GetVersion(versionID)
	if err != nil {
		return nil, fmt.Errorf("failed to get version info: %w", err)
	}

	fileList, err := v.storage.LoadFileList(versionID)
	if err != nil {
		return nil, fmt.Errorf("failed to load file list: %w", err)
	}

	result.TotalFiles = len(fileList)
	versionPath := v.storage.GetVersionPath(versionID)

	for _, file := range fileList {
		backupFilePath := filepath.Join(versionPath, "files", file.RelativePath)
		if _, err := os.Stat(backupFilePath); os.IsNotExist(err) {
			result.MissingFiles = append(result.MissingFiles, file.RelativePath)
			continue
		}

		fileHash, err := ComputeFileHash(backupFilePath)
		if err != nil {
			result.Errors = append(result.Errors, fmt.Sprintf("failed to compute hash for %s: %v", file.RelativePath, err))
			continue
		}

		if fileHash != file.Hash {
			result.InvalidFiles = append(result.InvalidFiles, file.RelativePath)
		} else {
			result.ValidFiles++
		}
	}

	actualChecksum, err := v.manager.ComputeVersionChecksum(versionID)
	if err != nil {
		result.Errors = append(result.Errors, fmt.Sprintf("failed to compute version checksum: %v", err))
	}
	result.ActualChecksum = actualChecksum
	result.ExpectedChecksum = versionInfo.Checksum
	result.ChecksumMatch = actualChecksum == versionInfo.Checksum

	result.IsValid = len(result.InvalidFiles) == 0 &&
		len(result.MissingFiles) == 0 &&
		len(result.Errors) == 0 &&
		result.ChecksumMatch

	result.Duration = time.Since(startTime)
	return result, nil
}

func (v *Verifier) VerifyFile(versionID, relativePath string) (bool, string, error) {
	versionPath := v.storage.GetVersionPath(versionID)
	backupFilePath := filepath.Join(versionPath, "files", relativePath)

	if _, err := os.Stat(backupFilePath); os.IsNotExist(err) {
		return false, "", fmt.Errorf("file not found in backup: %s", relativePath)
	}

	fileList, err := v.storage.LoadFileList(versionID)
	if err != nil {
		return false, "", fmt.Errorf("failed to load file list: %w", err)
	}

	var expectedHash string
	for _, file := range fileList {
		if file.RelativePath == relativePath {
			expectedHash = file.Hash
			break
		}
	}

	if expectedHash == "" {
		return false, "", fmt.Errorf("file not found in file list: %s", relativePath)
	}

	actualHash, err := ComputeFileHash(backupFilePath)
	if err != nil {
		return false, "", fmt.Errorf("failed to compute hash: %w", err)
	}

	return actualHash == expectedHash, actualHash, nil
}

func ComputeFileHash(filePath string) (string, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return "", fmt.Errorf("failed to open file: %w", err)
	}
	defer file.Close()

	hasher := sha256.New()
	if _, err := io.Copy(hasher, file); err != nil {
		return "", fmt.Errorf("failed to compute hash: %w", err)
	}

	return "sha256:" + hex.EncodeToString(hasher.Sum(nil)), nil
}

func CompareFileInfo(files1, files2 []*models.FileInfo) ([]*models.FileChangeRecord, error) {
	if files1 == nil {
		files1 = []*models.FileInfo{}
	}
	if files2 == nil {
		files2 = []*models.FileInfo{}
	}

	fileMap1 := make(map[string]*models.FileInfo)
	for _, f := range files1 {
		fileMap1[f.RelativePath] = f
	}

	fileMap2 := make(map[string]*models.FileInfo)
	for _, f := range files2 {
		fileMap2[f.RelativePath] = f
	}

	var changes []*models.FileChangeRecord

	for path, file2 := range fileMap2 {
		if file1, exists := fileMap1[path]; exists {
			if file1.Hash != file2.Hash {
				changes = append(changes, &models.FileChangeRecord{
					ChangeID:   generateChangeID(),
					FilePath:   path,
					ChangeType: "modified",
					OldHash:    file1.Hash,
					NewHash:    file2.Hash,
					FileSize:   file2.Size,
				})
			}
		} else {
			changes = append(changes, &models.FileChangeRecord{
				ChangeID:   generateChangeID(),
				FilePath:   path,
				ChangeType: "added",
				OldHash:    "",
				NewHash:    file2.Hash,
				FileSize:   file2.Size,
			})
		}
	}

	for path, file1 := range fileMap1 {
		if _, exists := fileMap2[path]; !exists {
			changes = append(changes, &models.FileChangeRecord{
				ChangeID:   generateChangeID(),
				FilePath:   path,
				ChangeType: "deleted",
				OldHash:    file1.Hash,
				NewHash:    "",
				FileSize:   file1.Size,
			})
		}
	}

	return changes, nil
}

func IsSourceDirectoryValid(sourcePath string) error {
	if sourcePath == "" {
		return errors.New("source path is empty")
	}

	info, err := os.Stat(sourcePath)
	if err != nil {
		if os.IsNotExist(err) {
			return fmt.Errorf("source directory does not exist: %s", sourcePath)
		}
		return fmt.Errorf("failed to access source directory: %w", err)
	}

	if !info.IsDir() {
		return fmt.Errorf("source path is not a directory: %s", sourcePath)
	}

	return nil
}

func generateChangeID() string {
	return fmt.Sprintf("change_%d", time.Now().UnixNano())
}
