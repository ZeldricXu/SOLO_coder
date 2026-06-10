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
	cfg         *config.Config
	db          *db.Database
	scanner     *Scanner
	resolver    *ConflictResolver
	watcher     *watcher.Watcher
	eventChan   chan *models.FileEvent
	eventBuffer map[string]*models.FileEvent
	bufferMu    sync.Mutex
	debounceTimer *time.Timer
	stopChan    chan struct{}
	running     bool
	mu          sync.RWMutex
	onEvent     func([]*models.FileEvent)
}

func NewWatcher(cfg *config.Config, database *db.Database) *Watcher {
	return &Watcher{
		cfg:         cfg,
		db:          database,
		scanner:     NewScanner(cfg, database),
		resolver:    NewConflictResolver(cfg.VaultPath),
		eventChan:   make(chan *models.FileEvent, 100),
		eventBuffer: make(map[string]*models.FileEvent),
		stopChan:    make(chan struct{}),
	}
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
	go w.processLoop()

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
	w.running = false
}

func (w *Watcher) InitialScan() (int, int, error) {
	return w.scanner.ScanAll()
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

	var op models.FileOp
	switch event.Op {
	case watcher.Create, watcher.Move:
		op = models.FileOpCreate
	case watcher.Write:
		op = models.FileOpModify
	case watcher.Remove:
		op = models.FileOpDelete
	case watcher.Rename:
		op = models.FileOpRename
	default:
		return
	}

	realPath := path
	if op != models.FileOpDelete {
		resolved, err := w.resolveSymlink(path)
		if err != nil || resolved == "" {
			return
		}
		realPath = resolved
	}

	fileEvent := &models.FileEvent{
		Path:      realPath,
		Op:        op,
		Timestamp: time.Now(),
	}

	w.bufferEvent(fileEvent)
}

func (w *Watcher) bufferEvent(event *models.FileEvent) {
	w.bufferMu.Lock()
	defer w.bufferMu.Unlock()

	w.eventBuffer[event.Path] = event

	if w.debounceTimer != nil {
		w.debounceTimer.Stop()
	}

	w.debounceTimer = time.AfterFunc(debounceDelay, func() {
		w.flushBuffer()
	})
}

func (w *Watcher) flushBuffer() {
	w.bufferMu.Lock()
	events := make([]*models.FileEvent, 0, len(w.eventBuffer))
	for _, e := range w.eventBuffer {
		events = append(events, e)
	}
	w.eventBuffer = make(map[string]*models.FileEvent)
	w.bufferMu.Unlock()

	if len(events) > 0 {
		w.processEvents(events)
	}
}

func (w *Watcher) processLoop() {
	for {
		select {
		case <-w.stopChan:
			return
		case event := <-w.eventChan:
			w.bufferEvent(event)
		}
	}
}

func (w *Watcher) processEvents(events []*models.FileEvent) {
	var processed []*models.FileEvent

	for _, event := range events {
		result := w.processSingleEvent(event)
		if result != nil {
			processed = append(processed, result)
		}
	}

	if w.onEvent != nil && len(processed) > 0 {
		w.onEvent(processed)
	}
}

func (w *Watcher) processSingleEvent(event *models.FileEvent) *models.FileEvent {
	switch event.Op {
	case models.FileOpCreate, models.FileOpModify:
		return w.handleCreateOrModify(event)
	case models.FileOpDelete:
		return w.handleDelete(event)
	case models.FileOpRename:
		return w.handleRename(event)
	default:
		return nil
	}
}

func (w *Watcher) handleCreateOrModify(event *models.FileEvent) *models.FileEvent {
	if _, err := os.Stat(event.Path); os.IsNotExist(err) {
		return nil
	}

	content, err := os.ReadFile(event.Path)
	if err != nil {
		return nil
	}

	hash := utils.Hash(string(content))
	event.Hash = hash

	existing, err := w.db.GetNoteByPath(event.Path)
	if err == nil && existing != nil {
		if existing.Hash == hash {
			return nil
		}
		event.OurHash = existing.Hash
		event.TheirHash = hash

		conflictInfo, hasConflict, err := w.resolver.DetectConflict(event.Path, existing.Hash)
		if err == nil && hasConflict {
			event.Conflict = true
			event.OurHash = conflictInfo.OurHash
			event.TheirHash = conflictInfo.TheirHash
		}
	}

	note, changed, err := w.scanner.ScanSingle(event.Path)
	if err != nil || !changed {
		return nil
	}

	_ = note
	return event
}

func (w *Watcher) handleDelete(event *models.FileEvent) *models.FileEvent {
	existing, err := w.db.GetNoteByPath(event.Path)
	if err != nil || existing == nil {
		return nil
	}

	if err := w.db.DeleteNote(event.Path); err != nil {
		return nil
	}

	return event
}

func (w *Watcher) handleRename(event *models.FileEvent) *models.FileEvent {
	return event
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
