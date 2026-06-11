package fsnotify

import (
	"encoding/json"
	"sort"
	"sync"
	"time"
)

type FileInfo struct {
	Path       string
	ModTime    time.Time
	Size       int64
	Hash       string
	IsMarkdown bool
}

type FileSnapshot struct {
	Files     map[string]FileInfo
	VaultPath string
	Timestamp time.Time
	mu        sync.RWMutex
}

type SnapshotDiff struct {
	Added     []string
	Modified  []string
	Deleted   []string
	Unchanged []string
}

func NewFileSnapshot(vaultPath string) *FileSnapshot {
	return &FileSnapshot{
		Files:     make(map[string]FileInfo),
		VaultPath: vaultPath,
		Timestamp: time.Now(),
	}
}

func (s *FileSnapshot) Update(path string, info FileInfo) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.Files[path] = info
	s.Timestamp = time.Now()
}

func (s *FileSnapshot) Remove(path string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.Files, path)
	s.Timestamp = time.Now()
}

func (s *FileSnapshot) Contains(path string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	_, ok := s.Files[path]
	return ok
}

func (s *FileSnapshot) Get(path string) (FileInfo, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	info, ok := s.Files[path]
	return info, ok
}

func (s *FileSnapshot) Count() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.Files)
}

func (s *FileSnapshot) ListAll() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	result := make([]string, 0, len(s.Files))
	for p := range s.Files {
		result = append(result, p)
	}
	sort.Strings(result)
	return result
}

func (s *FileSnapshot) Compare(s2 *FileSnapshot) *SnapshotDiff {
	s.mu.RLock()
	defer s.mu.RUnlock()
	s2.mu.RLock()
	defer s2.mu.RUnlock()

	diff := &SnapshotDiff{
		Added:     make([]string, 0),
		Modified:  make([]string, 0),
		Deleted:   make([]string, 0),
		Unchanged: make([]string, 0),
	}

	for path := range s2.Files {
		if _, exists := s.Files[path]; !exists {
			diff.Added = append(diff.Added, path)
		}
	}

	for path := range s.Files {
		if _, exists := s2.Files[path]; !exists {
			diff.Deleted = append(diff.Deleted, path)
		}
	}

	for path, oldInfo := range s.Files {
		newInfo, exists := s2.Files[path]
		if !exists {
			continue
		}
		if fileInfoChanged(oldInfo, newInfo) {
			diff.Modified = append(diff.Modified, path)
		} else {
			diff.Unchanged = append(diff.Unchanged, path)
		}
	}

	sort.Strings(diff.Added)
	sort.Strings(diff.Modified)
	sort.Strings(diff.Deleted)
	sort.Strings(diff.Unchanged)

	return diff
}

func fileInfoChanged(a, b FileInfo) bool {
	if a.Size != b.Size {
		return true
	}
	if !a.ModTime.Equal(b.ModTime) {
		return true
	}
	if a.Hash != "" && b.Hash != "" && a.Hash != b.Hash {
		return true
	}
	return false
}

func (s *FileSnapshot) MergeChanges(diff *SnapshotDiff, newInfoProvider func(string) FileInfo) *FileSnapshot {
	s.mu.RLock()
	defer s.mu.RUnlock()

	merged := &FileSnapshot{
		Files:     make(map[string]FileInfo, len(s.Files)),
		VaultPath: s.VaultPath,
		Timestamp: time.Now(),
	}

	for p, info := range s.Files {
		merged.Files[p] = info
	}

	for _, path := range diff.Added {
		merged.Files[path] = newInfoProvider(path)
	}

	for _, path := range diff.Modified {
		merged.Files[path] = newInfoProvider(path)
	}

	for _, path := range diff.Deleted {
		delete(merged.Files, path)
	}

	return merged
}

func (s *FileSnapshot) Marshal() ([]byte, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	type serializableFileInfo struct {
		Path       string
		ModTime    int64
		Size       int64
		Hash       string
		IsMarkdown bool
	}

	type serializableSnapshot struct {
		Files     []serializableFileInfo
		VaultPath string
		Timestamp int64
	}

	ss := serializableSnapshot{
		VaultPath: s.VaultPath,
		Timestamp: s.Timestamp.UnixNano(),
		Files:     make([]serializableFileInfo, 0, len(s.Files)),
	}

	for _, info := range s.Files {
		ss.Files = append(ss.Files, serializableFileInfo{
			Path:       info.Path,
			ModTime:    info.ModTime.UnixNano(),
			Size:       info.Size,
			Hash:       info.Hash,
			IsMarkdown: info.IsMarkdown,
		})
	}

	return json.Marshal(ss)
}

func UnmarshalSnapshot(data []byte) (*FileSnapshot, error) {
	type serializableFileInfo struct {
		Path       string
		ModTime    int64
		Size       int64
		Hash       string
		IsMarkdown bool
	}

	type serializableSnapshot struct {
		Files     []serializableFileInfo
		VaultPath string
		Timestamp int64
	}

	var ss serializableSnapshot
	if err := json.Unmarshal(data, &ss); err != nil {
		return nil, err
	}

	s := &FileSnapshot{
		Files:     make(map[string]FileInfo, len(ss.Files)),
		VaultPath: ss.VaultPath,
		Timestamp: time.Unix(0, ss.Timestamp),
	}

	for _, fi := range ss.Files {
		s.Files[fi.Path] = FileInfo{
			Path:       fi.Path,
			ModTime:    time.Unix(0, fi.ModTime),
			Size:       fi.Size,
			Hash:       fi.Hash,
			IsMarkdown: fi.IsMarkdown,
		}
	}

	return s, nil
}
