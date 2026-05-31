package classification

import (
	"regexp"
	"sync"
)

type SensitivePattern struct {
	Name        string
	Pattern     *regexp.Regexp
	Sensitivity string
	Category    string
	Level       int
}

type PatternStore interface {
	Get(name string) (*SensitivePattern, bool)
	GetAll() map[string]*SensitivePattern
	Add(name, regexStr, sensitivity, category string, level int) error
	Remove(name string)
	ListNames() []string
}

type InMemoryPatternStore struct {
	patterns map[string]*SensitivePattern
	mu       sync.RWMutex
}

func NewInMemoryPatternStore() *InMemoryPatternStore {
	store := &InMemoryPatternStore{
		patterns: make(map[string]*SensitivePattern),
	}
	store.loadDefaultPatterns()
	return store
}

func (s *InMemoryPatternStore) loadDefaultPatterns() {
	defaults := []struct {
		name        string
		regex       string
		sensitivity string
		category    string
		level       int
	}{
		{"phone", `1[3-9]\d{9}`, "high", "personal_identity", 4},
		{"id_card", `[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]`, "high", "personal_identity", 5},
		{"email", `[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}`, "medium", "contact_info", 3},
		{"bank_card", `\d{16,19}`, "high", "financial", 5},
		{"address", `(北京|上海|广州|深圳|杭州|成都|武汉|西安|南京|重庆)[市]?[\u4e00-\u9fa5]{2,}(区|县|街道|镇|路|街|巷|号|楼|栋|单元|室)`, "medium", "location", 3},
	}

	for _, d := range defaults {
		pattern, _ := regexp.Compile(d.regex)
		s.patterns[d.name] = &SensitivePattern{
			Name:        d.name,
			Pattern:     pattern,
			Sensitivity: d.sensitivity,
			Category:    d.category,
			Level:       d.level,
		}
	}
}

func (s *InMemoryPatternStore) Get(name string) (*SensitivePattern, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	p, ok := s.patterns[name]
	return p, ok
}

func (s *InMemoryPatternStore) GetAll() map[string]*SensitivePattern {
	s.mu.RLock()
	defer s.mu.RUnlock()
	result := make(map[string]*SensitivePattern, len(s.patterns))
	for k, v := range s.patterns {
		result[k] = v
	}
	return result
}

func (s *InMemoryPatternStore) Add(name, regexStr, sensitivity, category string, level int) error {
	pattern, err := regexp.Compile(regexStr)
	if err != nil {
		return err
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	s.patterns[name] = &SensitivePattern{
		Name:        name,
		Pattern:     pattern,
		Sensitivity: sensitivity,
		Category:    category,
		Level:       level,
	}
	return nil
}

func (s *InMemoryPatternStore) Remove(name string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.patterns, name)
}

func (s *InMemoryPatternStore) ListNames() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	names := make([]string, 0, len(s.patterns))
	for name := range s.patterns {
		names = append(names, name)
	}
	return names
}
