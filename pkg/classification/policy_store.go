package classification

import "sync"

type ClassificationPolicy struct {
	Level       int
	Action      string
	Description string
}

type PolicyStore interface {
	Get(level int) (*ClassificationPolicy, bool)
	GetAll() map[int]*ClassificationPolicy
	Set(level int, action, description string)
}

type InMemoryPolicyStore struct {
	policies map[int]*ClassificationPolicy
	mu       sync.RWMutex
}

func NewInMemoryPolicyStore() *InMemoryPolicyStore {
	store := &InMemoryPolicyStore{
		policies: make(map[int]*ClassificationPolicy),
	}
	store.loadDefaultPolicies()
	return store
}

func (s *InMemoryPolicyStore) loadDefaultPolicies() {
	s.policies[1] = &ClassificationPolicy{Level: 1, Action: "none", Description: "公开数据，无限制"}
	s.policies[2] = &ClassificationPolicy{Level: 2, Action: "log", Description: "内部数据，记录访问日志"}
	s.policies[3] = &ClassificationPolicy{Level: 3, Action: "mask", Description: "敏感数据，默认脱敏显示"}
	s.policies[4] = &ClassificationPolicy{Level: 4, Action: "encrypt", Description: "高敏感数据，加密存储与传输"}
	s.policies[5] = &ClassificationPolicy{Level: 5, Action: "restrict", Description: "核心敏感数据，严格访问控制"}
}

func (s *InMemoryPolicyStore) Get(level int) (*ClassificationPolicy, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	p, ok := s.policies[level]
	return p, ok
}

func (s *InMemoryPolicyStore) GetAll() map[int]*ClassificationPolicy {
	s.mu.RLock()
	defer s.mu.RUnlock()
	result := make(map[int]*ClassificationPolicy, len(s.policies))
	for k, v := range s.policies {
		result[k] = v
	}
	return result
}

func (s *InMemoryPolicyStore) Set(level int, action, description string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.policies[level] = &ClassificationPolicy{
		Level:       level,
		Action:      action,
		Description: description,
	}
}
