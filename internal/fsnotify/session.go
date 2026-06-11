package fsnotify

import (
	"os"
	"sync"
	"sync/atomic"
	"time"
)

type FileEventEntry struct {
	Path   string
	Info   FileInfo
	Delete bool
}

type SessionStats struct {
	EventsReceived     int64
	CallbacksTriggered int64
	AddedTotal         int64
	ModifiedTotal      int64
	DeletedTotal       int64
}

type WatchSession struct {
	vaultPath     string
	base          *FileSnapshot
	inProgress    *FileSnapshot
	debounceMu    sync.Mutex
	debounceTimer *time.Timer
	debounceDelay time.Duration
	OnChange      func(diff *SnapshotDiff) error
	running       bool
	mu            sync.RWMutex
	stats         SessionStats
}

func NewWatchSession(vaultPath string, debounceDelay time.Duration) *WatchSession {
	return &WatchSession{
		vaultPath:     vaultPath,
		base:          NewFileSnapshot(vaultPath),
		inProgress:    NewFileSnapshot(vaultPath),
		debounceDelay: debounceDelay,
	}
}

func (s *WatchSession) HandleFileEvent(path string, newInfo FileInfo) {
	s.mu.RLock()
	if !s.running {
		s.mu.RUnlock()
		return
	}
	s.mu.RUnlock()

	atomic.AddInt64(&s.stats.EventsReceived, 1)
	s.inProgress.Update(path, newInfo)
	s.scheduleDebounce()
}

func (s *WatchSession) HandleFileDelete(path string) {
	s.mu.RLock()
	if !s.running {
		s.mu.RUnlock()
		return
	}
	s.mu.RUnlock()

	atomic.AddInt64(&s.stats.EventsReceived, 1)
	s.inProgress.Remove(path)
	s.scheduleDebounce()
}

func (s *WatchSession) HandleBulkEvents(events []FileEventEntry) {
	s.mu.RLock()
	if !s.running {
		s.mu.RUnlock()
		return
	}
	s.mu.RUnlock()

	for _, e := range events {
		atomic.AddInt64(&s.stats.EventsReceived, 1)
		if e.Delete {
			s.inProgress.Remove(e.Path)
		} else {
			s.inProgress.Update(e.Path, e.Info)
		}
	}
	s.scheduleDebounce()
}

func (s *WatchSession) scheduleDebounce() {
	s.debounceMu.Lock()
	defer s.debounceMu.Unlock()

	if s.debounceTimer != nil {
		s.debounceTimer.Stop()
	}
	s.debounceTimer = time.AfterFunc(s.debounceDelay, func() {
		s.triggerCallback()
	})
}

func (s *WatchSession) triggerCallback() {
	s.mu.Lock()
	if !s.running {
		s.mu.Unlock()
		return
	}
	s.mu.Unlock()

	diff := s.base.Compare(s.inProgress)

	hasChanges := len(diff.Added) > 0 || len(diff.Modified) > 0 || len(diff.Deleted) > 0
	if !hasChanges {
		return
	}

	if s.OnChange != nil {
		if err := s.OnChange(diff); err != nil {
			return
		}
	}

	atomic.AddInt64(&s.stats.CallbacksTriggered, 1)
	atomic.AddInt64(&s.stats.AddedTotal, int64(len(diff.Added)))
	atomic.AddInt64(&s.stats.ModifiedTotal, int64(len(diff.Modified)))
	atomic.AddInt64(&s.stats.DeletedTotal, int64(len(diff.Deleted)))

	newBase := s.inProgress
	s.mu.Lock()
	s.base = newBase
	s.inProgress = NewFileSnapshot(s.vaultPath)
	for p, info := range newBase.Files {
		s.inProgress.Files[p] = info
	}
	s.mu.Unlock()
}

func (s *WatchSession) TakeBaseSnapshotFromFiles(files map[string]FileInfo) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.base = NewFileSnapshot(s.vaultPath)
	s.inProgress = NewFileSnapshot(s.vaultPath)
	for p, info := range files {
		s.base.Files[p] = info
		s.inProgress.Files[p] = info
	}
}

func (s *WatchSession) Start() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.running = true
}

func (s *WatchSession) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.running = false

	s.debounceMu.Lock()
	if s.debounceTimer != nil {
		s.debounceTimer.Stop()
		s.debounceTimer = nil
	}
	s.debounceMu.Unlock()
}

func (s *WatchSession) Stats() SessionStats {
	return SessionStats{
		EventsReceived:     atomic.LoadInt64(&s.stats.EventsReceived),
		CallbacksTriggered: atomic.LoadInt64(&s.stats.CallbacksTriggered),
		AddedTotal:         atomic.LoadInt64(&s.stats.AddedTotal),
		ModifiedTotal:      atomic.LoadInt64(&s.stats.ModifiedTotal),
		DeletedTotal:       atomic.LoadInt64(&s.stats.DeletedTotal),
	}
}

func (s *WatchSession) Flush() {
	s.debounceMu.Lock()
	if s.debounceTimer != nil {
		s.debounceTimer.Stop()
		s.debounceTimer = nil
	}
	s.debounceMu.Unlock()

	s.triggerCallback()
}

func (s *WatchSession) SaveState(path string) error {
	s.mu.RLock()
	data, err := s.base.Marshal()
	s.mu.RUnlock()
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0644)
}

func (s *WatchSession) LoadState(path string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	snap, err := UnmarshalSnapshot(data)
	if err != nil {
		return err
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	s.base = snap
	s.inProgress = NewFileSnapshot(s.vaultPath)
	for p, info := range snap.Files {
		s.inProgress.Files[p] = info
	}
	return nil
}
