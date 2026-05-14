package audit

import (
	"accessguard/models"
	"time"
)

type Service interface {
	RecordAccess(userID, resourceID, action, ipAddress, sessionID string, result models.AccessResult, reason string) error
	RecordLogin(userID, ipAddress, sessionID string, success bool) error
	RecordLogout(userID, ipAddress, sessionID string) error
	QueryRecords(userID string, startTime, endTime time.Time, limit, offset int) ([]*models.AuditRecord, int, error)
	GetRecordByID(auditID string) (*models.AuditRecord, error)
	QueryBySession(sessionID string, limit, offset int) ([]*models.AuditRecord, int, error)
	QueryByResource(resourceID string, limit, offset int) ([]*models.AuditRecord, int, error)
}
