package models

import (
	"time"
)

type AuditResult string

const (
	AuditResultApproved AuditResult = "approved"
	AuditResultRejected AuditResult = "rejected"
	AuditResultPending  AuditResult = "pending"
)

type AuditRecord struct {
	AuditID     string      `bson:"audit_id" json:"audit_id"`
	PostID      string      `bson:"post_id" json:"post_id"`
	AuditResult AuditResult `bson:"audit_result" json:"audit_result"`
	AuditReason *string     `bson:"audit_reason,omitempty" json:"audit_reason,omitempty"`
	AuditedAt   time.Time   `bson:"audited_at" json:"audited_at"`
}
