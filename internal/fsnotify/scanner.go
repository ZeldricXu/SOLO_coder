package fsnotify

import (
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/pkg/segment"
	"github.com/solocoder/knowledgebase/pkg/utils"
)

type Scanner struct {
	cfg *config.Config
	db  *db.Database
}

func NewScanner(cfg *config.Config, database *db.Database) *Scanner {
	return &Scanner{
		cfg: cfg,
		db:  database,
	}
}

func (s *Scanner) ScanAll() (int, int, error) {
	files, err := s.collectFiles()
	if err != nil {
		return 0, 0, err
	}

	dbHashes, err := s.db.GetNoteHashes()
	if err != nil {
		return 0, 0, err
	}

	added := 0
	updated := 0

	for path := range files {
		content, err := os.ReadFile(path)
		if err != nil {
			continue
		}

		hash := utils.Hash(string(content))
		oldHash, exists := dbHashes[path]

		if !exists {
			if err := s.indexNote(path, string(content), hash); err != nil {
				continue
			}
			added++
		} else if oldHash != hash {
			if err := s.indexNote(path, string(content), hash); err != nil {
				continue
			}
			updated++
		}
		delete(dbHashes, path)
	}

	for path := range dbHashes {
		_ = s.db.DeleteNote(path)
	}

	return added, updated, nil
}

func (s *Scanner) ScanSingle(path string) (*models.Note, bool, error) {
	exists := true
	note, err := s.db.GetNoteByPath(path)
	if err != nil {
		exists = false
	}

	if _, err := os.Stat(path); os.IsNotExist(err) {
		if exists {
			_ = s.db.DeleteNote(path)
		}
		return nil, false, nil
	}

	content, err := os.ReadFile(path)
	if err != nil {
		return nil, false, err
	}

	hash := utils.Hash(string(content))
	if exists && note.Hash == hash {
		return note, false, nil
	}

	if err := s.indexNote(path, string(content), hash); err != nil {
		return nil, false, err
	}

	newNote, err := s.db.GetNoteByPath(path)
	return newNote, true, err
}

func (s *Scanner) GetFileHash(path string) (string, error) {
	content, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	return utils.Hash(string(content)), nil
}

func (s *Scanner) collectFiles() (map[string]bool, error) {
	files := make(map[string]bool)
	var mu sync.Mutex

	err := filepath.Walk(s.cfg.VaultPath, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return nil
		}

		if info.IsDir() {
			if s.isHidden(info.Name()) {
				return filepath.SkipDir
			}
			return nil
		}

		if s.isHidden(info.Name()) {
			return nil
		}

		if !utils.IsMarkdownFile(path) {
			return nil
		}

		realPath, err := s.resolveSymlink(path, info)
		if err != nil {
			return nil
		}

		if strings.HasPrefix(filepath.Base(realPath), ".") {
			return nil
		}

		mu.Lock()
		files[realPath] = true
		mu.Unlock()

		return nil
	})

	return files, err
}

func (s *Scanner) indexNote(path, content, hash string) error {
	title := utils.ExtractTitle(content)
	if title == "" {
		title = strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
	}

	wordCount := utils.CountWords(content)

	note := &models.Note{
		Path:      path,
		Title:     title,
		Content:   content,
		Hash:      hash,
		WordCount: wordCount,
	}

	existing, err := s.db.GetNoteByPath(path)
	if err == nil && existing != nil {
		note.ID = existing.ID
		note.CreatedAt = existing.CreatedAt
		note.LastOpenedAt = existing.LastOpenedAt
	}

	if err := s.db.SaveNote(note); err != nil {
		return err
	}

	if err := s.indexSearch(note.ID, content); err != nil {
		return err
	}

	return nil
}

func (s *Scanner) indexSearch(noteID uint, content string) error {
	if err := s.db.ClearSearchIndex(noteID); err != nil {
		return err
	}

	tokens := segment.Segment(content, s.cfg.Search.UseCJK)
	freqMap := make(map[string]int)
	posMap := make(map[string][]int)

	for _, token := range tokens {
		freqMap[token.Text]++
		posMap[token.Text] = append(posMap[token.Text], token.Position)
	}

	for term, freq := range freqMap {
		if err := s.db.SaveSearchIndex(noteID, term, freq, posMap[term]); err != nil {
			return err
		}
	}

	return nil
}

func (s *Scanner) isHidden(name string) bool {
	return strings.HasPrefix(name, ".")
}

func (s *Scanner) resolveSymlink(path string, info os.FileInfo) (string, error) {
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
		return realPath, nil
	}
	return path, nil
}
