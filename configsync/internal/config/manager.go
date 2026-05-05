package config

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"configsync/internal/models"
	"configsync/internal/vcs"
)

var (
	ErrConfigNotFound = errors.New("config file not found")
)

type ConfigManager struct {
	gitManager *vcs.GitManager
}

func NewConfigManager(repoPath string) (*ConfigManager, error) {
	gm, err := vcs.NewGitManager(repoPath)
	if err != nil {
		return nil, fmt.Errorf("failed to create git manager: %w", err)
	}

	return &ConfigManager{
		gitManager: gm,
	}, nil
}

func (cm *ConfigManager) ReadConfig(fileName string) ([]byte, error) {
	filePath := filepath.Join(cm.gitManager.GetRepoPath(), fileName)

	data, err := os.ReadFile(filePath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, ErrConfigNotFound
		}
		return nil, fmt.Errorf("failed to read config file: %w", err)
	}

	return data, nil
}

func (cm *ConfigManager) ReadConfigAtVersion(fileName string, versionTag string) ([]byte, error) {
	return cm.gitManager.ReadFileAtVersion(fileName, versionTag)
}

func (cm *ConfigManager) WriteConfig(fileName string, content []byte) error {
	return cm.gitManager.WriteConfigFile(fileName, content)
}

func (cm *ConfigManager) SaveConfigVersion(fileName string, message string) (string, error) {
	commitHash, err := cm.gitManager.Commit(message)
	if err != nil {
		return "", fmt.Errorf("failed to commit config: %w", err)
	}

	versionTag := generateVersionTag()
	if err := cm.gitManager.CreateTag(versionTag, commitHash); err != nil {
		return "", fmt.Errorf("failed to create tag: %w", err)
	}

	return versionTag, nil
}

func (cm *ConfigManager) ListVersions() ([]string, error) {
	return cm.gitManager.ListTags()
}

func (cm *ConfigManager) GetDiff(fileName string) (string, error) {
	return cm.gitManager.GetDiff(fileName)
}

func (cm *ConfigManager) RollbackToVersion(fileName string, versionTag string) error {
	if err := cm.gitManager.CheckoutByTag(versionTag); err != nil {
		return fmt.Errorf("failed to checkout version: %w", err)
	}

	return nil
}

func (cm *ConfigManager) GetRepoPath() string {
	return cm.gitManager.GetRepoPath()
}

func (cm *ConfigManager) GetConfigPath(fileName string) string {
	return filepath.Join(cm.gitManager.GetRepoPath(), fileName)
}

func (cm *ConfigManager) ListConfigFiles() ([]string, error) {
	repoPath := cm.gitManager.GetRepoPath()

	var configFiles []string
	err := filepath.Walk(repoPath, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}

		if info.IsDir() {
			if info.Name() == ".git" {
				return filepath.SkipDir
			}
			return nil
		}

		relPath, err := filepath.Rel(repoPath, path)
		if err != nil {
			return err
		}

		configFiles = append(configFiles, relPath)
		return nil
	})

	if err != nil {
		return nil, fmt.Errorf("failed to list config files: %w", err)
	}

	return configFiles, nil
}

func (cm *ConfigManager) ImportConfig(sourcePath string, targetName string) error {
	data, err := os.ReadFile(sourcePath)
	if err != nil {
		if os.IsNotExist(err) {
			return ErrConfigNotFound
		}
		return fmt.Errorf("failed to read source file: %w", err)
	}

	return cm.WriteConfig(targetName, data)
}

func (cm *ConfigManager) ExportConfig(fileName string, targetPath string) error {
	data, err := cm.ReadConfig(fileName)
	if err != nil {
		return err
	}

	targetDir := filepath.Dir(targetPath)
	if err := os.MkdirAll(targetDir, 0755); err != nil {
		return fmt.Errorf("failed to create target directory: %w", err)
	}

	if err := os.WriteFile(targetPath, data, 0644); err != nil {
		return fmt.Errorf("failed to write target file: %w", err)
	}

	return nil
}

func (cm *ConfigManager) CreatePushSnapshot(configFile string, targetGroup string) (string, error) {
	message := fmt.Sprintf("Push snapshot: %s to group %s", configFile, targetGroup)
	return cm.SaveConfigVersion(configFile, message)
}

func (cm *ConfigManager) CreatePushSnapshotWithMeta(configFile string, targetGroup string, operator string, serverCount int, changeType string) (string, *models.VersionSnapshotMeta, error) {
	versionTag := generateVersionTag()

	meta := &models.VersionSnapshotMeta{
		ConfigFile:  configFile,
		TargetGroup: targetGroup,
		Operator:    operator,
		ChangeType:  changeType,
		VersionTag:  versionTag,
		ServerCount: serverCount,
	}

	message := fmt.Sprintf("%s: %s to group %s", changeType, configFile, targetGroup)

	commitHash, err := cm.gitManager.CommitWithMeta(message, meta)
	if err != nil {
		return "", nil, fmt.Errorf("failed to commit config: %w", err)
	}

	meta.CommitHash = commitHash.String()

	if err := cm.gitManager.CreateTag(versionTag, commitHash); err != nil {
		return "", meta, fmt.Errorf("failed to create tag: %w", err)
	}

	return versionTag, meta, nil
}

func (cm *ConfigManager) GetVersionMeta(versionTag string) (*models.VersionSnapshotMeta, error) {
	return cm.gitManager.GetTagMeta(versionTag)
}

func (cm *ConfigManager) ListVersionMetaHistory(limit int) ([]*models.VersionSnapshotMeta, error) {
	return cm.gitManager.ListVersionHistory(limit)
}

func (cm *ConfigManager) GetChangeSummary(oldContent, newContent []byte) string {
	oldLines := countLines(oldContent)
	newLines := countLines(newContent)
	added := newLines - oldLines
	removed := 0
	if added < 0 {
		removed = -added
		added = 0
	}
	return fmt.Sprintf("+%d lines, -%d lines", added, removed)
}

func generateVersionTag() string {
	now := time.Now()
	return fmt.Sprintf("v%d.%02d.%02d-%02d%02d%02d",
		now.Year(), now.Month(), now.Day(),
		now.Hour(), now.Minute(), now.Second())
}

func countLines(data []byte) int {
	if len(data) == 0 {
		return 0
	}
	count := 0
	for _, b := range data {
		if b == '\n' {
			count++
		}
	}
	return count + 1
}
