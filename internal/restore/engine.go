package restore

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"time"

	"backupmanager/internal/logger"
	"backupmanager/internal/storage"
	"backupmanager/internal/verify"
	"backupmanager/internal/version"
	"backupmanager/pkg/models"
)

type Engine struct {
	storage  *storage.Storage
	manager  *version.Manager
	verifier *verify.Verifier
	logger   *logger.Logger
}

type RestoreOptions struct {
	VerifyOnRestore bool
	Overwrite       bool
}

func NewEngine(storage *storage.Storage, manager *version.Manager, verifier *verify.Verifier, log *logger.Logger) *Engine {
	return &Engine{
		storage:  storage,
		manager:  manager,
		verifier: verifier,
		logger:   log,
	}
}

func (e *Engine) Restore(versionID, targetPath string, verifyOnRestore bool) (*models.RestoreResult, error) {
	return e.RestoreWithOptions(versionID, targetPath, RestoreOptions{
		VerifyOnRestore: verifyOnRestore,
		Overwrite:       true,
	})
}

func (e *Engine) RestoreWithOptions(versionID, targetPath string, opts RestoreOptions) (*models.RestoreResult, error) {
	startTime := time.Now()
	result := &models.RestoreResult{
		VersionID: versionID,
		Success:   false,
		Errors:    make([]string, 0),
	}

	e.logger.Info("Starting restore: version=%s, target=%s, verify=%v", versionID, targetPath, opts.VerifyOnRestore)

	versionInfo, err := e.manager.GetVersion(versionID)
	if err != nil {
		result.Errors = append(result.Errors, err.Error())
		e.logger.Error("Failed to get version info: %v", err)
		return result, err
	}

	if opts.VerifyOnRestore {
		e.logger.Info("Verifying backup integrity before restore...")
		verifyResult, err := e.verifier.VerifyVersion(versionID)
		if err != nil {
			result.Errors = append(result.Errors, err.Error())
			e.logger.Error("Failed to verify version: %v", err)
			return result, err
		}

		if !verifyResult.IsValid {
			for _, invalid := range verifyResult.InvalidFiles {
				result.Errors = append(result.Errors, fmt.Sprintf("invalid file: %s", invalid))
			}
			for _, missing := range verifyResult.MissingFiles {
				result.Errors = append(result.Errors, fmt.Sprintf("missing file: %s", missing))
			}
			result.Errors = append(result.Errors, "backup integrity verification failed")
			e.logger.Error("Backup integrity verification failed")
			return result, fmt.Errorf("backup integrity verification failed")
		}
		e.logger.Info("Backup integrity verified successfully")
	}

	fileList, err := e.storage.LoadFileList(versionID)
	if err != nil {
		result.Errors = append(result.Errors, err.Error())
		e.logger.Error("Failed to load file list: %v", err)
		return result, err
	}

	if err := os.MkdirAll(targetPath, 0755); err != nil {
		result.Errors = append(result.Errors, err.Error())
		e.logger.Error("Failed to create target directory: %v", err)
		return result, err
	}

	versionChain, err := e.buildVersionChain(versionInfo)
	if err != nil {
		e.logger.Warn("Failed to build version chain: %v, using single version", err)
		versionChain = []*models.BackupVersion{versionInfo}
	}

	e.logger.Debug("Version chain for restore: %d versions", len(versionChain))

	fileSourceMap := e.buildFileSourceMap(fileList, versionChain)

	for _, fileInfo := range fileList {
		sourcePath, found := fileSourceMap[fileInfo.RelativePath]
		if !found {
			sourcePath = e.findFileInChain(fileInfo.RelativePath, versionInfo, versionChain)
		}

		if sourcePath == "" {
			result.FailedCount++
			result.Errors = append(result.Errors, fmt.Sprintf("backup file not found: %s", fileInfo.RelativePath))
			e.logger.Warn("Backup file not found for: %s", fileInfo.RelativePath)
			continue
		}

		targetFilePath := filepath.Join(targetPath, filepath.FromSlash(fileInfo.RelativePath))

		e.logger.Debug("Restoring: %s -> %s", sourcePath, targetFilePath)

		if err := e.storage.CopyFile(sourcePath, targetFilePath); err != nil {
			result.FailedCount++
			result.Errors = append(result.Errors, fmt.Sprintf("failed to restore %s: %v", fileInfo.RelativePath, err))
			e.logger.Error("Failed to restore file %s: %v", fileInfo.RelativePath, err)
			continue
		}

		if opts.VerifyOnRestore {
			restoredHash, err := verify.ComputeFileHash(targetFilePath)
			if err != nil {
				result.Errors = append(result.Errors, fmt.Sprintf("failed to verify restored file %s: %v", fileInfo.RelativePath, err))
				e.logger.Warn("Failed to verify restored file %s: %v", fileInfo.RelativePath, err)
			} else if restoredHash != fileInfo.Hash {
				result.Errors = append(result.Errors, fmt.Sprintf("hash mismatch for restored file: %s", fileInfo.RelativePath))
				e.logger.Warn("Hash mismatch for restored file: %s", fileInfo.RelativePath)
			}
		}

		result.RestoredCount++
		result.TotalSize += fileInfo.Size
	}

	result.Duration = time.Since(startTime)
	result.Success = len(result.Errors) == 0 && result.FailedCount == 0

	status := "success"
	if !result.Success {
		status = "partial"
	}

	e.logger.Log("restore", versionID, status, result.Duration, result.Errors)
	e.logger.Info("Restore completed: version=%s, restored=%d, failed=%d, size=%d bytes, duration=%v",
		versionID, result.RestoredCount, result.FailedCount, result.TotalSize, result.Duration)

	return result, nil
}

func (e *Engine) buildVersionChain(targetVersion *models.BackupVersion) ([]*models.BackupVersion, error) {
	allVersions, err := e.manager.ListVersions(targetVersion.SourcePath)
	if err != nil {
		return nil, err
	}

	sort.Slice(allVersions, func(i, j int) bool {
		return allVersions[i].CreatedAt.Before(allVersions[j].CreatedAt)
	})

	chain := make([]*models.BackupVersion, 0)
	for _, v := range allVersions {
		chain = append(chain, v)
		if v.VersionID == targetVersion.VersionID {
			break
		}
	}

	return chain, nil
}

func (e *Engine) buildFileSourceMap(files []*models.FileInfo, versionChain []*models.BackupVersion) map[string]string {
	fileSourceMap := make(map[string]string)

	for _, versionInfo := range versionChain {
		versionPath := e.storage.GetVersionPath(versionInfo.VersionID)
		backupFilesDir := filepath.Join(versionPath, "files")

		versionFiles, err := e.storage.LoadFileList(versionInfo.VersionID)
		if err != nil {
			continue
		}

		versionFileMap := make(map[string]*models.FileInfo)
		for _, f := range versionFiles {
			versionFileMap[f.RelativePath] = f
		}

		for _, fileInfo := range files {
			if _, exists := fileSourceMap[fileInfo.RelativePath]; exists {
				continue
			}

			if vFile, found := versionFileMap[fileInfo.RelativePath]; found {
				if vFile.Hash == fileInfo.Hash {
					backupFilePath := filepath.Join(backupFilesDir, filepath.FromSlash(fileInfo.RelativePath))
					if _, err := os.Stat(backupFilePath); err == nil {
						fileSourceMap[fileInfo.RelativePath] = backupFilePath
					}
				}
			}
		}
	}

	return fileSourceMap
}

func (e *Engine) findFileInChain(relativePath string, targetVersion *models.BackupVersion, versionChain []*models.BackupVersion) string {
	targetPath := e.storage.GetVersionPath(targetVersion.VersionID)
	targetBackupFile := filepath.Join(targetPath, "files", filepath.FromSlash(relativePath))

	if _, err := os.Stat(targetBackupFile); err == nil {
		changeRecords, loadErr := e.storage.LoadChangeRecords(targetVersion.VersionID)
		if loadErr == nil {
			for _, cr := range changeRecords {
				if cr.FilePath == relativePath && cr.ChangeType == "deleted" {
					return ""
				}
			}
		}
		return targetBackupFile
	}

	for i := len(versionChain) - 2; i >= 0; i-- {
		prevVersion := versionChain[i]
		prevPath := e.storage.GetVersionPath(prevVersion.VersionID)
		prevBackupFile := filepath.Join(prevPath, "files", filepath.FromSlash(relativePath))

		if _, err := os.Stat(prevBackupFile); err == nil {
			e.logger.Debug("Found file %s in previous version: %s", relativePath, prevVersion.VersionID)
			return prevBackupFile
		}
	}

	return ""
}

func (e *Engine) CanRestore(versionID string) (bool, []string) {
	versionInfo, err := e.manager.GetVersion(versionID)
	if err != nil {
		return false, []string{fmt.Sprintf("version not found: %s", versionID)}
	}

	fileList, err := e.storage.LoadFileList(versionID)
	if err != nil {
		return false, []string{fmt.Sprintf("failed to load file list: %v", err)}
	}

	versionChain, err := e.buildVersionChain(versionInfo)
	if err != nil {
		return false, []string{fmt.Sprintf("failed to build version chain: %v", err)}
	}

	missingFiles := make([]string, 0)
	fileSourceMap := e.buildFileSourceMap(fileList, versionChain)

	for _, fileInfo := range fileList {
		if _, found := fileSourceMap[fileInfo.RelativePath]; !found {
			sourcePath := e.findFileInChain(fileInfo.RelativePath, versionInfo, versionChain)
			if sourcePath == "" {
				missingFiles = append(missingFiles, fileInfo.RelativePath)
			}
		}
	}

	return len(missingFiles) == 0, missingFiles
}
