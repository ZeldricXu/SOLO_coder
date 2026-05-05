package vcs

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"os/user"
	"path/filepath"
	"strings"
	"time"

	"github.com/go-git/go-git/v5"
	"github.com/go-git/go-git/v5/config"
	"github.com/go-git/go-git/v5/plumbing"
	"github.com/go-git/go-git/v5/plumbing/object"

	"configsync/internal/models"
)

type GitManager struct {
	repoPath string
	repo     *git.Repository
}

func NewGitManager(repoPath string) (*GitManager, error) {
	gm := &GitManager{
		repoPath: repoPath,
	}

	if err := os.MkdirAll(repoPath, 0755); err != nil {
		return nil, fmt.Errorf("failed to create repo directory: %w", err)
	}

	repo, err := git.PlainOpen(repoPath)
	if err == git.ErrRepositoryNotExists {
		repo, err = git.PlainInit(repoPath, false)
		if err != nil {
			return nil, fmt.Errorf("failed to init git repository: %w", err)
		}

		if err := gm.initGitConfig(repo); err != nil {
			return nil, err
		}
	} else if err != nil {
		return nil, fmt.Errorf("failed to open git repository: %w", err)
	}

	gm.repo = repo
	return gm, nil
}

func (gm *GitManager) initGitConfig(repo *git.Repository) error {
	cfg, err := repo.Config()
	if err != nil {
		return fmt.Errorf("failed to get repo config: %w", err)
	}

	usr, err := user.Current()
	if err != nil {
		usr = &user.User{Username: "configsync"}
	}

	cfg.User.Name = "ConfigSync"
	cfg.User.Email = fmt.Sprintf("%s@localhost", usr.Username)

	if err := repo.SetConfig(cfg); err != nil {
		return fmt.Errorf("failed to set repo config: %w", err)
	}

	return nil
}

func (gm *GitManager) GetRepoPath() string {
	return gm.repoPath
}

func (gm *GitManager) Commit(message string) (plumbing.Hash, error) {
	w, err := gm.repo.Worktree()
	if err != nil {
		return plumbing.ZeroHash, fmt.Errorf("failed to get worktree: %w", err)
	}

	status, err := w.Status()
	if err != nil {
		return plumbing.ZeroHash, fmt.Errorf("failed to get status: %w", err)
	}

	if status.IsClean() {
		return plumbing.ZeroHash, fmt.Errorf("nothing to commit")
	}

	if err := w.AddWithOptions(&git.AddOptions{All: true}); err != nil {
		return plumbing.ZeroHash, fmt.Errorf("failed to add files: %w", err)
	}

	usr, err := user.Current()
	if err != nil {
		usr = &user.User{Username: "configsync"}
	}

	commitHash, err := w.Commit(message, &git.CommitOptions{
		Author: &object.Signature{
			Name:  "ConfigSync",
			Email: fmt.Sprintf("%s@localhost", usr.Username),
			When:  time.Now(),
		},
	})

	if err != nil {
		return plumbing.ZeroHash, fmt.Errorf("failed to commit: %w", err)
	}

	return commitHash, nil
}

func (gm *GitManager) CreateTag(tagName string, commitHash plumbing.Hash) error {
	_, err := gm.repo.CreateTag(tagName, commitHash, nil)
	if err != nil {
		return fmt.Errorf("failed to create tag: %w", err)
	}
	return nil
}

func (gm *GitManager) GetTag(tagName string) (*plumbing.Reference, error) {
	tagRef, err := gm.repo.Tag(tagName)
	if err != nil {
		return nil, fmt.Errorf("failed to get tag: %w", err)
	}
	return tagRef, nil
}

func (gm *GitManager) ListTags() ([]string, error) {
	tagRefs, err := gm.repo.Tags()
	if err != nil {
		return nil, fmt.Errorf("failed to list tags: %w", err)
	}

	var tags []string
	err = tagRefs.ForEach(func(ref *plumbing.Reference) error {
		tags = append(tags, ref.Name().Short())
		return nil
	})

	if err != nil {
		return nil, fmt.Errorf("failed to iterate tags: %w", err)
	}

	return tags, nil
}

func (gm *GitManager) CheckoutByTag(tagName string) error {
	tagRef, err := gm.repo.Tag(tagName)
	if err != nil {
		return fmt.Errorf("tag not found: %w", err)
	}

	w, err := gm.repo.Worktree()
	if err != nil {
		return fmt.Errorf("failed to get worktree: %w", err)
	}

	err = w.Checkout(&git.CheckoutOptions{
		Hash:  tagRef.Hash(),
		Force: true,
	})

	if err != nil {
		return fmt.Errorf("failed to checkout tag: %w", err)
	}

	return nil
}

func (gm *GitManager) CheckoutByCommit(hash plumbing.Hash) error {
	w, err := gm.repo.Worktree()
	if err != nil {
		return fmt.Errorf("failed to get worktree: %w", err)
	}

	err = w.Checkout(&git.CheckoutOptions{
		Hash:  hash,
		Force: true,
	})

	if err != nil {
		return fmt.Errorf("failed to checkout commit: %w", err)
	}

	return nil
}

func (gm *GitManager) GetDiff(filePath string) (string, error) {
	w, err := gm.repo.Worktree()
	if err != nil {
		return "", fmt.Errorf("failed to get worktree: %w", err)
	}

	status, err := w.Status()
	if err != nil {
		return "", fmt.Errorf("failed to get status: %w", err)
	}

	fileStatus := status.File(filePath)
	if fileStatus == nil {
		return "", nil
	}

	if fileStatus.Worktree == git.Unmodified && fileStatus.Staging == git.Unmodified {
		return "", nil
	}

	headRef, err := gm.repo.Head()
	if err != nil {
		return "", nil
	}

	commit, err := gm.repo.CommitObject(headRef.Hash())
	if err != nil {
		return "", nil
	}

	tree, err := commit.Tree()
	if err != nil {
		return "", nil
	}

	var buf bytes.Buffer
	changes, err := tree.Diff(&git.DiffOptions{
		Prefix: git.DiffPrefix{
			OldPrefix: "a/",
			NewPrefix: "b/",
		},
	})

	if err != nil {
		return "", err
	}

	for _, ch := range changes {
		if ch.From.Name == filePath || ch.To.Name == filePath {
			ch.Patch(&buf)
		}
	}

	return buf.String(), nil
}

func (gm *GitManager) GetHistory(limit int) ([]object.Commit, error) {
	logOptions := &git.LogOptions{}
	if limit > 0 {
		logOptions.Order = git.LogOrderCommitterTime
	}

	commitsIter, err := gm.repo.Log(logOptions)
	if err != nil {
		return nil, fmt.Errorf("failed to get log: %w", err)
	}
	defer commitsIter.Close()

	var commits []object.Commit
	count := 0
	err = commitsIter.ForEach(func(commit *object.Commit) error {
		if limit > 0 && count >= limit {
			return nil
		}
		commits = append(commits, *commit)
		count++
		return nil
	})

	if err != nil {
		return nil, fmt.Errorf("failed to iterate commits: %w", err)
	}

	return commits, nil
}

func (gm *GitManager) ReadFileAtVersion(filePath string, tagName string) ([]byte, error) {
	var commit *object.Commit
	var err error

	if tagName != "" {
		tagRef, err := gm.repo.Tag(tagName)
		if err != nil {
			return nil, fmt.Errorf("tag not found: %w", err)
		}
		commit, err = gm.repo.CommitObject(tagRef.Hash())
	} else {
		headRef, err := gm.repo.Head()
		if err != nil {
			return nil, fmt.Errorf("failed to get HEAD: %w", err)
		}
		commit, err = gm.repo.CommitObject(headRef.Hash())
	}

	if err != nil {
		return nil, fmt.Errorf("failed to get commit: %w", err)
	}

	tree, err := commit.Tree()
	if err != nil {
		return nil, fmt.Errorf("failed to get tree: %w", err)
	}

	fileEntry, err := tree.FindEntry(filePath)
	if err != nil {
		return nil, fmt.Errorf("file not found in commit: %w", err)
	}

	fileBlob, err := gm.repo.BlobObject(fileEntry.Hash)
	if err != nil {
		return nil, fmt.Errorf("failed to get blob: %w", err)
	}

	reader, err := fileBlob.Reader()
	if err != nil {
		return nil, fmt.Errorf("failed to get blob reader: %w", err)
	}
	defer reader.Close()

	buf := new(bytes.Buffer)
	_, err = buf.ReadFrom(reader)
	if err != nil {
		return nil, fmt.Errorf("failed to read blob: %w", err)
	}

	return buf.Bytes(), nil
}

func (gm *GitManager) WriteConfigFile(fileName string, content []byte) error {
	filePath := filepath.Join(gm.repoPath, fileName)
	if err := os.WriteFile(filePath, content, 0644); err != nil {
		return fmt.Errorf("failed to write config file: %w", err)
	}
	return nil
}

func (gm *GitManager) CommitWithMeta(message string, meta *models.VersionSnapshotMeta) (plumbing.Hash, error) {
	w, err := gm.repo.Worktree()
	if err != nil {
		return plumbing.ZeroHash, fmt.Errorf("failed to get worktree: %w", err)
	}

	status, err := w.Status()
	if err != nil {
		return plumbing.ZeroHash, fmt.Errorf("failed to get status: %w", err)
	}

	if status.IsClean() {
		return plumbing.ZeroHash, fmt.Errorf("nothing to commit")
	}

	if err := w.AddWithOptions(&git.AddOptions{All: true}); err != nil {
		return plumbing.ZeroHash, fmt.Errorf("failed to add files: %w", err)
	}

	fullMessage := message
	if meta != nil {
		meta.ExecutedAt = time.Now()
		metaJSON, err := json.MarshalIndent(meta, "", "  ")
		if err == nil {
			fullMessage = fmt.Sprintf("%s\n\n---METADATA---\n%s", message, string(metaJSON))
		}
	}

	usr, err := user.Current()
	if err != nil {
		usr = &user.User{Username: "configsync"}
	}

	var authorName string
	var authorEmail string
	if meta != nil && meta.Operator != "" {
		authorName = meta.Operator
		authorEmail = fmt.Sprintf("%s@localhost", meta.Operator)
	} else {
		authorName = "ConfigSync"
		authorEmail = fmt.Sprintf("%s@localhost", usr.Username)
	}

	commitHash, err := w.Commit(fullMessage, &git.CommitOptions{
		Author: &object.Signature{
			Name:  authorName,
			Email: authorEmail,
			When:  time.Now(),
		},
	})

	if err != nil {
		return plumbing.ZeroHash, fmt.Errorf("failed to commit: %w", err)
	}

	return commitHash, nil
}

func (gm *GitManager) GetCommitMeta(commitHash plumbing.Hash) (*models.VersionSnapshotMeta, error) {
	commit, err := gm.repo.CommitObject(commitHash)
	if err != nil {
		return nil, fmt.Errorf("failed to get commit: %w", err)
	}

	return parseMetaFromMessage(commit.Message)
}

func (gm *GitManager) GetTagMeta(tagName string) (*models.VersionSnapshotMeta, error) {
	tagRef, err := gm.repo.Tag(tagName)
	if err != nil {
		return nil, fmt.Errorf("tag not found: %w", err)
	}

	return gm.GetCommitMeta(tagRef.Hash())
}

func parseMetaFromMessage(message string) (*models.VersionSnapshotMeta, error) {
	marker := "---METADATA---"
	idx := strings.Index(message, marker)
	if idx == -1 {
		return nil, fmt.Errorf("no metadata found in commit message")
	}

	jsonStr := strings.TrimSpace(message[idx+len(marker):])

	var meta models.VersionSnapshotMeta
	if err := json.Unmarshal([]byte(jsonStr), &meta); err != nil {
		return nil, fmt.Errorf("failed to parse metadata: %w", err)
	}

	return &meta, nil
}

func (gm *GitManager) ListVersionHistory(limit int) ([]*models.VersionSnapshotMeta, error) {
	commits, err := gm.GetHistory(limit)
	if err != nil {
		return nil, err
	}

	var metas []*models.VersionSnapshotMeta
	for i := range commits {
		meta, err := parseMetaFromMessage(commits[i].Message)
		if err == nil {
			metas = append(metas, meta)
		}
	}

	return metas, nil
}
