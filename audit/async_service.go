package audit

import (
	"accessguard/models"
	"accessguard/storage"
	"accessguard/utils"
	"context"
	"log"
	"sync"
	"time"
)

type AsyncService struct {
	store          storage.AuditStore
	buffer         chan *models.AuditRecord
	bufferSize     int
	flushInterval  time.Duration
	batchSize      int
	workerWg       sync.WaitGroup
	ctx            context.Context
	cancel         context.CancelFunc
	started        bool
	mu             sync.RWMutex
}

func NewAsyncService(store storage.AuditStore, bufferSize int, flushInterval time.Duration, batchSize int) *AsyncService {
	if bufferSize <= 0 {
		bufferSize = 1000
	}
	if flushInterval <= 0 {
		flushInterval = 1 * time.Second
	}
	if batchSize <= 0 {
		batchSize = 100
	}

	return &AsyncService{
		store:         store,
		buffer:        make(chan *models.AuditRecord, bufferSize),
		bufferSize:    bufferSize,
		flushInterval: flushInterval,
		batchSize:     batchSize,
		started:       false,
	}
}

func (s *AsyncService) Start() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.started {
		return
	}

	s.ctx, s.cancel = context.WithCancel(context.Background())
	s.started = true

	s.workerWg.Add(1)
	go s.batchWorker()
}

func (s *AsyncService) Stop() {
	s.mu.Lock()
	if !s.started {
		s.mu.Unlock()
		return
	}
	s.mu.Unlock()

	s.cancel()

	close(s.buffer)

	s.workerWg.Wait()

	s.mu.Lock()
	s.started = false
	s.mu.Unlock()
}

func (s *AsyncService) batchWorker() {
	defer s.workerWg.Done()

	ticker := time.NewTicker(s.flushInterval)
	defer ticker.Stop()

	pending := make([]*models.AuditRecord, 0, s.batchSize)

	for {
		select {
		case <-s.ctx.Done():
			for record := range s.buffer {
				pending = append(pending, record)
				if len(pending) >= s.batchSize {
					s.flush(pending)
					pending = pending[:0]
				}
			}
			if len(pending) > 0 {
				s.flush(pending)
			}
			return

		case record, ok := <-s.buffer:
			if !ok {
				continue
			}
			pending = append(pending, record)
			if len(pending) >= s.batchSize {
				s.flush(pending)
				pending = pending[:0]
			}

		case <-ticker.C:
			if len(pending) > 0 {
				s.flush(pending)
				pending = pending[:0]
			}
		}
	}
}

func (s *AsyncService) flush(records []*models.AuditRecord) {
	for _, record := range records {
		if err := s.store.Record(record); err != nil {
			log.Printf("Failed to flush audit record: %v", err)
		}
	}
}

func (s *AsyncService) RecordAccessAsync(userID, resourceID, action, ipAddress, sessionID string, result models.AccessResult, reason string) {
	audit := &models.AuditRecord{
		AuditID:      utils.GenerateAuditID(),
		UserID:       userID,
		ResourceID:   resourceID,
		Action:       action,
		AccessResult: result,
		IPAddress:    ipAddress,
		SessionID:    sessionID,
		AccessTime:   time.Now(),
		Reason:       reason,
	}

	s.mu.RLock()
	started := s.started
	s.mu.RUnlock()

	if !started {
		s.store.Record(audit)
		return
	}

	select {
	case s.buffer <- audit:
	default:
		s.store.Record(audit)
	}
}

func (s *AsyncService) RecordLoginAsync(userID, ipAddress, sessionID string, success bool) {
	result := models.AccessAllowed
	reason := "login_success"
	if !success {
		result = models.AccessDenied
		reason = "login_failed"
	}

	s.RecordAccessAsync(userID, "system_login", "login", ipAddress, sessionID, result, reason)
}

func (s *AsyncService) RecordLogoutAsync(userID, ipAddress, sessionID string) {
	s.RecordAccessAsync(userID, "system_logout", "logout", ipAddress, sessionID, models.AccessAllowed, "logout_success")
}

func (s *AsyncService) QueryRecords(userID string, startTime, endTime time.Time, limit, offset int) ([]*models.AuditRecord, int, error) {
	return s.store.Query(userID, startTime, endTime, limit, offset)
}

func (s *AsyncService) GetRecordByID(auditID string) (*models.AuditRecord, error) {
	return s.store.GetByID(auditID)
}

func (s *AsyncService) QueryBySession(sessionID string, limit, offset int) ([]*models.AuditRecord, int, error) {
	records, total, err := s.store.Query("", time.Time{}, time.Time{}, 1000, 0)
	if err != nil {
		return nil, 0, err
	}

	filtered := make([]*models.AuditRecord, 0, len(records))
	for _, r := range records {
		if r.SessionID == sessionID {
			filtered = append(filtered, r)
		}
	}

	total = len(filtered)
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

	return filtered[start:end], total, nil
}

func (s *AsyncService) QueryByResource(resourceID string, limit, offset int) ([]*models.AuditRecord, int, error) {
	records, total, err := s.store.Query("", time.Time{}, time.Time{}, 1000, 0)
	if err != nil {
		return nil, 0, err
	}

	filtered := make([]*models.AuditRecord, 0, len(records))
	for _, r := range records {
		if r.ResourceID == resourceID {
			filtered = append(filtered, r)
		}
	}

	total = len(filtered)
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

	return filtered[start:end], total, nil
}

func (s *AsyncService) RecordAccess(userID, resourceID, action, ipAddress, sessionID string, result models.AccessResult, reason string) error {
	s.RecordAccessAsync(userID, resourceID, action, ipAddress, sessionID, result, reason)
	return nil
}

func (s *AsyncService) RecordLogin(userID, ipAddress, sessionID string, success bool) error {
	s.RecordLoginAsync(userID, ipAddress, sessionID, success)
	return nil
}

func (s *AsyncService) RecordLogout(userID, ipAddress, sessionID string) error {
	s.RecordLogoutAsync(userID, ipAddress, sessionID)
	return nil
}
