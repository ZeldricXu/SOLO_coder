package fsnotify

import (
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/radovskyb/watcher"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/pkg/utils"
)

const (
	debounceDelay = 100 * time.Millisecond
)

type Watcher struct {
	cfg      *config.Config
	db       *db.Database
	scanner  *Scanner
	resolver *ConflictResolver
	watcher  *watcher.Watcher
	stopChan chan struct{}
	running  bool
	mu       sync.RWMutex
	onEvent  func([]*models.FileEvent)
	session  *WatchSession
}

func NewWatcher(cfg *config.Config, database *db.Database) *Watcher {
	session := NewWatchSession(cfg.VaultPath, debounceDelay)
	w := &Watcher{
		cfg:      cfg,
		db:       database,
		scanner:  NewScanner(cfg, database),
		resolver: NewConflictResolver(cfg.VaultPath),
		stopChan: make(chan struct{}),
		session:  session,
	}

	session.OnChange = func(diff *SnapshotDiff) error {
		var events []*models.FileEvent

		for _, path := range diff.Added {
			ev := w.handleCreateFromSession(path)
			if ev != nil {
				events = append(events, ev)
			}
		}

		for _, path := range diff.Modified {
			ev := w.handleModifyFromSession(path)
			if ev != nil {
				events = append(events, ev)
			}
		}

		for _, path := range diff.Deleted {
			ev := w.handleDeleteFromSession(path)
			if ev != nil {
				events = append(events, ev)
			}
		}

		if w.onEvent != nil && len(events) > 0 {
			w.onEvent(events)
		}
		return nil
	}

	return w
}

func (w *Watcher) SetOnEvent(fn func([]*models.FileEvent)) {
	w.onEvent = fn
}

func (w *Watcher) Start() error {
	w.mu.Lock()
	defer w.mu.Unlock()

	if w.running {
		return nil
	}

	if _, err := os.Stat(w.cfg.VaultPath); os.IsNotExist(err) {
		if err := os.MkdirAll(w.cfg.VaultPath, 0755); err != nil {
			return err
		}
	}

	w.watcher = watcher.New()
	w.watcher.SetMaxEvents(100)
	w.watcher.FilterOps(watcher.Create, watcher.Write, watcher.Remove, watcher.Rename)

	if err := w.watcher.AddRecursive(w.cfg.VaultPath); err != nil {
		return err
	}

	go w.eventLoop()

	startErr := make(chan error, 1)
	go func() {
		startErr <- w.watcher.Start(100 * time.Millisecond)
	}()

	select {
	case err := <-startErr:
		if err != nil {
			return err
		}
	case <-time.After(300 * time.Millisecond):
	}

	w.session.Start()
	w.running = true
	return nil
}

func (w *Watcher) Stop() {
	w.mu.Lock()
	defer w.mu.Unlock()

	if !w.running {
		return
	}

	close(w.stopChan)
	w.watcher.Close()
	w.session.Stop()
	w.running = false
}

func (w *Watcher) InitialScan() (int, int, error) {
	added, updated, err := w.scanner.ScanAll()
	if err != nil {
		return added, updated, err
	}

	files, err := w.collectBaseSnapshotFiles()
	if err == nil {
		w.session.TakeBaseSnapshotFromFiles(files)
	}

	return added, updated, nil
}

func (w *Watcher) collectBaseSnapshotFiles() (map[string]FileInfo, error) {
	files := make(map[string]FileInfo)

	err := filepath.Walk(w.cfg.VaultPath, func(path string, info os.FileInfo, walkErr error) error {
		if walkErr != nil {
			return nil
		}

		if info.IsDir() {
			if w.isHiddenName(info.Name()) {
				return filepath.SkipDir
			}
			return nil
		}

		if w.isHiddenName(info.Name()) {
			return nil
		}

		if !utils.IsMarkdownFile(path) {
			return nil
		}

		realPath, err := w.resolveSymlinkForSnapshot(path, info)
		if err != nil || realPath == "" {
			return nil
		}

		content, readErr := os.ReadFile(realPath)
		hash := ""
		if readErr == nil {
			hash = utils.Hash(string(content))
		}

		fi := FileInfo{
			Path:       realPath,
			ModTime:    info.ModTime(),
			Size:       info.Size(),
			Hash:       hash,
			IsMarkdown: true,
		}
		files[realPath] = fi
		return nil
	})

	return files, err
}

func (w *Watcher) resolveSymlinkForSnapshot(path string, info os.FileInfo) (string, error) {
	if info.Mode()&os.ModeSymlink != 0 {
		realPath, err := filepath.EvalSymlinks(path)
		if err != nil {
			return "", err
		}
		realInfo, err := os.Stat(realPath)
		if err != nil {
			return "", err
		}
		if realInfo.IsDir() {
			return "", nil
		}
		if !utils.IsMarkdownFile(realPath) {
			return "", nil
		}
		return realPath, nil
	}
	return path, nil
}

func (w *Watcher) isHiddenName(name string) bool {
	return strings.HasPrefix(name, ".")
}

func (w *Watcher) eventLoop() {
	for {
		select {
		case <-w.stopChan:
			return
		case event := <-w.watcher.Event:
			w.handleRawEvent(&event)
		case err := <-w.watcher.Error:
			_ = err
		case <-w.watcher.Closed:
			return
		}
	}
}

func (w *Watcher) handleRawEvent(event *watcher.Event) {
	path := event.Path

	if w.isHiddenPath(path) {
		return
	}

	if !utils.IsMarkdownFile(path) {
		return
	}

	switch event.Op {
	case watcher.Create, watcher.Move, watcher.Write:
		realPath := path
		resolved, err := w.resolveSymlink(path)
		if err != nil || resolved == "" {
			return
		}
		realPath = resolved

		info, statErr := os.Stat(realPath)
		if statErr != nil {
			return
		}

		content, readErr := os.ReadFile(realPath)
		hash := ""
		if readErr == nil {
			hash = utils.Hash(string(content))
		}

		fi := FileInfo{
			Path:       realPath,
			ModTime:    info.ModTime(),
			Size:       info.Size(),
			Hash:       hash,
			IsMarkdown: true,
		}
		w.session.HandleFileEvent(realPath, fi)

	case watcher.Remove, watcher.Rename:
		realPath := path
		resolved, err := w.resolveSymlinkIfExists(path)
		if err == nil && resolved != "" {
			realPath = resolved
		}
		w.session.HandleFileDelete(realPath)
	}
}

func (w *Watcher) resolveSymlinkIfExists(path string) (string, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return path, nil
	}
	if info.Mode()&os.ModeSymlink != 0 {
		realPath, err := filepath.EvalSymlinks(path)
		if err != nil {
			return "", err
		}
		realInfo, err := os.Stat(realPath)
		if err != nil {
			return realPath, nil
		}
		if realInfo.IsDir() {
			return "", nil
		}
		if !utils.IsMarkdownFile(realPath) {
			return "", nil
		}
		return realPath, nil
	}
	return path, nil
}

func (w *Watcher) handleCreateFromSession(path string) *models.FileEvent {
	if _, err := os.Stat(path); os.IsNotExist(err) {
		return nil
	}

	content, err := os.ReadFile(path)
	if err != nil {
		return nil
	}

	hash := utils.Hash(string(content))

	existing, err := w.db.GetNoteByPath(path)
	if err == nil && existing != nil {
		if existing.Hash == hash {
			return nil
		}
	}

	note, changed, err := w.scanner.ScanSingle(path)
	if err != nil || !changed {
		return nil
	}

	_ = note
	return &models.FileEvent{
		Path:      path,
		Op:        models.FileOpCreate,
		Timestamp: time.Now(),
		Hash:      hash,
	}
}

func (w *Watcher) handleModifyFromSession(path string) *models.FileEvent {
	if _, err := os.Stat(path); os.IsNotExist(err) {
		return nil
	}

	content, err := os.ReadFile(path)
	if err != nil {
		return nil
	}

	hash := utils.Hash(string(content))
	event := &models.FileEvent{
		Path:      path,
		Op:        models.FileOpModify,
		Timestamp: time.Now(),
		Hash:      hash,
	}

	existing, err := w.db.GetNoteByPath(path)
	if err == nil && existing != nil {
		if existing.Hash == hash {
			return nil
		}
		event.OurHash = existing.Hash
		event.TheirHash = hash

		conflictInfo, hasConflict, err := w.resolver.DetectConflict(path, existing.Hash)
		if err == nil && hasConflict {
			event.Conflict = true
			event.OurHash = conflictInfo.OurHash
			event.TheirHash = conflictInfo.TheirHash
		}
	}

	note, changed, err := w.scanner.ScanSingle(path)
	if err != nil || !changed {
		return nil
	}

	_ = note
	return event
}

func (w *Watcher) handleDeleteFromSession(path string) *models.FileEvent {
	existing, err := w.db.GetNoteByPath(path)
	if err != nil || existing == nil {
		return nil
	}

	if err := w.db.DeleteNote(path); err != nil {
		return nil
	}

	return &models.FileEvent{
		Path:      path,
		Op:        models.FileOpDelete,
		Timestamp: time.Now(),
	}
}

func (w *Watcher) isHiddenPath(path string) bool {
	rel, err := filepath.Rel(w.cfg.VaultPath, path)
	if err != nil {
		return strings.HasPrefix(filepath.Base(path), ".")
	}

	parts := strings.Split(rel, string(filepath.Separator))
	for _, part := range parts {
		if strings.HasPrefix(part, ".") {
			return true
		}
	}
	return false
}

func (w *Watcher) resolveSymlink(path string) (string, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return "", err
	}

	if info.Mode()&os.ModeSymlink != 0 {
		realPath, err := filepath.EvalSymlinks(path)
		if err != nil {
			return "", err
		}
		realInfo, err := os.Stat(realPath)
		if err != nil {
			return "", err
		}
		if realInfo.IsDir() {
			return "", nil
		}
		if !utils.IsMarkdownFile(realPath) {
			return "", nil
		}
		return realPath, nil
	}
	return path, nil
}

func (w *Watcher) ResolveConflict(path string, resolution models.ConflictResolution, ourContent string) error {
	info, hasConflict, err := w.resolver.DetectConflict(path, "")
	if err != nil {
		return err
	}
	if !hasConflict {
		return nil
	}

	return w.resolver.Resolve(info, resolution, ourContent)
}

func (w *Watcher) RefreshAll() (int, int, error) {
	return w.scanner.ScanAll()
}
