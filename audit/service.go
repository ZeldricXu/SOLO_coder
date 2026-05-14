package audit

import (
	"accessguard/models"
	"accessguard/storage"
	"accessguard/utils"
	"time"
)

type Service struct {
	store storage.AuditStore
}

func NewService(store storage.AuditStore) *Service {
	return &Service{store: store}
}

func (s *Service) RecordAccess(userID, resourceID, action, ipAddress, sessionID string, result models.AccessResult, reason string) error {
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

	return s.store.Record(audit)
}

func (s *Service) RecordLogin(userID, ipAddress, sessionID string, success bool) error {
	result := models.AccessAllowed
	reason := "login_success"
	if !success {
		result = models.AccessDenied
		reason = "login_failed"
	}

	return s.RecordAccess(userID, "system_login", "login", ipAddress, sessionID, result, reason)
}

func (s *Service) RecordLogout(userID, ipAddress, sessionID string) error {
	return s.RecordAccess(userID, "system_logout", "logout", ipAddress, sessionID, models.AccessAllowed, "logout_success")
}

func (s *Service) QueryRecords(userID string, startTime, endTime time.Time, limit, offset int) ([]*models.AuditRecord, int, error) {
	return s.store.Query(userID, startTime, endTime, limit, offset)
}

func (s *Service) GetRecordByID(auditID string) (*models.AuditRecord, error) {
	return s.store.GetByID(auditID)
}

func (s *Service) QueryBySession(sessionID string, limit, offset int) ([]*models.AuditRecord, int, error) {
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

func (s *Service) QueryByResource(resourceID string, limit, offset int) ([]*models.AuditRecord, int, error) {
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
