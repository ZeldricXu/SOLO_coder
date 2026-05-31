package utils

import "github.com/google/uuid"

func NewID(prefix string) string {
	return prefix + "_" + uuid.New().String()[:8]
}

func NewTraceID() string {
	return "trace_" + uuid.New().String()
}

func NewEntityID() string {
	return NewID("ent")
}

func NewConfigID() string {
	return NewID("cfg")
}

func NewRunID() string {
	return NewID("run")
}

func NewSnapshotID() string {
	return NewID("snap")
}

func NewAuditID() string {
	return NewID("audit")
}

func NewBackupID() string {
	return NewID("backup")
}

func NewAlertID() string {
	return NewID("alert")
}

func NewModelID() string {
	return NewID("model")
}

func NewTaskID() string {
	return NewID("task")
}

func NewResourceID() string {
	return NewID("rsc")
}

func NewBatchID() string {
	return NewID("batch")
}
