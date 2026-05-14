package storage

import (
	"accessguard/models"
	"sync"
	"time"
)

type AuditStore interface {
	Record(audit *models.AuditRecord) error
	Query(userID string, startTime, endTime time.Time, limit, offset int) ([]*models.AuditRecord, int, error)
	GetByID(auditID string) (*models.AuditRecord, error)
}

type InMemoryAuditStore struct {
	records []*models.AuditRecord
	userIdx map[string][]int
	mu      sync.RWMutex
}

func NewInMemoryAuditStore() *InMemoryAuditStore {
	return &InMemoryAuditStore{
		records: make([]*models.AuditRecord, 0),
		userIdx: make(map[string][]int),
	}
}

func (s *InMemoryAuditStore) Record(audit *models.AuditRecord) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	idx := len(s.records)
	s.records = append(s.records, audit)
	s.userIdx[audit.UserID] = append(s.userIdx[audit.UserID], idx)
	return nil
}

func (s *InMemoryAuditStore) Query(userID string, startTime, endTime time.Time, limit, offset int) ([]*models.AuditRecord, int, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var indices []int
	if userID != "" {
		indices = s.userIdx[userID]
	} else {
		indices = make([]int, len(s.records))
		for i := range s.records {
			indices[i] = i
		}
	}

	filtered := make([]*models.AuditRecord, 0, len(indices))
	for _, idx := range indices {
		record := s.records[idx]
		match := true
		if !startTime.IsZero() && record.AccessTime.Before(startTime) {
			match = false
		}
		if !endTime.IsZero() && record.AccessTime.After(endTime) {
			match = false
		}
		if match {
			filtered = append(filtered, record)
		}
	}

	total := len(filtered)
	if limit <= 0 {
		limit = 100
	}
	if offset < 0 {
		offset = 0
	}

	start := offset
	end := offset + limit
	if start > total {
		start = total
	}
	if end > total {
		end = total
	}

	result := make([]*models.AuditRecord, 0, end-start)
	for i := start; i < end; i++ {
		result = append(result, filtered[i])
	}

	return result, total, nil
}

func (s *InMemoryAuditStore) GetByID(auditID string) (*models.AuditRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	for _, record := range s.records {
		if record.AuditID == auditID {
			return record, nil
		}
	}
	return nil, nil
}
