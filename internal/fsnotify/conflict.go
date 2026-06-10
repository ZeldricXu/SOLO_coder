package fsnotify

import (
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/pkg/utils"
)

type ConflictResolver struct {
	vaultPath string
}

func NewConflictResolver(vaultPath string) *ConflictResolver {
	return &ConflictResolver{
		vaultPath: vaultPath,
	}
}

type ConflictInfo struct {
	Path       string
	OurHash    string
	TheirHash  string
	OurContent string
	TheirContent string
	Timestamp  time.Time
}

func (r *ConflictResolver) DetectConflict(path, ourHash string) (*ConflictInfo, bool, error) {
	content, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, false, nil
		}
		return nil, false, err
	}

	theirHash := utils.Hash(string(content))
	if ourHash == theirHash {
		return nil, false, nil
	}

	info := &ConflictInfo{
		Path:         path,
		OurHash:      ourHash,
		TheirHash:    theirHash,
		OurContent:   "",
		TheirContent: string(content),
		Timestamp:    time.Now(),
	}

	return info, true, nil
}

func (r *ConflictResolver) Resolve(info *ConflictInfo, resolution models.ConflictResolution, ourContent string) error {
	switch resolution {
	case models.ConflictKeepOurs:
		return r.keepOurs(info.Path, ourContent)
	case models.ConflictKeepTheirs:
		return r.keepTheirs(info)
	case models.ConflictMerge:
		return r.merge(info, ourContent)
	default:
		return r.keepTheirs(info)
	}
}

func (r *ConflictResolver) keepOurs(path, ourContent string) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}
	return os.WriteFile(path, []byte(ourContent), 0644)
}

func (r *ConflictResolver) keepTheirs(info *ConflictInfo) error {
	return nil
}

func (r *ConflictResolver) merge(info *ConflictInfo, ourContent string) error {
	ourLines := strings.Split(ourContent, "\n")
	theirLines := strings.Split(info.TheirContent, "\n")

	merged := r.mergeLines(ourLines, theirLines)
	mergedContent := strings.Join(merged, "\n")

	backupPath := r.backupPath(info.Path)
	if err := os.WriteFile(backupPath, []byte(info.TheirContent), 0644); err != nil {
		return err
	}

	return os.WriteFile(info.Path, []byte(mergedContent), 0644)
}

func (r *ConflictResolver) mergeLines(ours, theirs []string) []string {
	commonPrefix := 0
	for commonPrefix < len(ours) && commonPrefix < len(theirs) {
		if ours[commonPrefix] != theirs[commonPrefix] {
			break
		}
		commonPrefix++
	}

	commonSuffix := 0
	for commonSuffix < len(ours)-commonPrefix && commonSuffix < len(theirs)-commonPrefix {
		ourIdx := len(ours) - 1 - commonSuffix
		theirIdx := len(theirs) - 1 - commonSuffix
		if ours[ourIdx] != theirs[theirIdx] {
			break
		}
		commonSuffix++
	}

	var result []string
	result = append(result, ours[:commonPrefix]...)

	if commonPrefix < len(ours)-commonSuffix {
		result = append(result, "<<<<<<< ours")
		result = append(result, ours[commonPrefix:len(ours)-commonSuffix]...)
		result = append(result, "=======")
		result = append(result, theirs[commonPrefix:len(theirs)-commonSuffix]...)
		result = append(result, ">>>>>>> theirs")
	}

	if commonSuffix > 0 {
		result = append(result, ours[len(ours)-commonSuffix:]...)
	}

	return result
}

func (r *ConflictResolver) backupPath(origPath string) string {
	ext := filepath.Ext(origPath)
	base := strings.TrimSuffix(origPath, ext)
	timestamp := time.Now().Format("20060102-150405")
	return base + ".conflict-" + timestamp + ext
}

func (r *ConflictResolver) IsExternalModification(path string, lastKnownHash string, lastModifyTime time.Time) (bool, error) {
	info, err := os.Stat(path)
	if err != nil {
		if os.IsNotExist(err) {
			return false, nil
		}
		return false, err
	}

	if info.ModTime().Before(lastModifyTime) || info.ModTime().Equal(lastModifyTime) {
		return false, nil
	}

	currentHash, err := r.getFileHash(path)
	if err != nil {
		return false, err
	}

	return currentHash != lastKnownHash, nil
}

func (r *ConflictResolver) getFileHash(path string) (string, error) {
	content, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	return utils.Hash(string(content)), nil
}
