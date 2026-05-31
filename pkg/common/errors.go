package common

import (
	"errors"
	"fmt"
)

var (
	ErrNotFound          = errors.New("resource not found")
	ErrAlreadyExists     = errors.New("resource already exists")
	ErrInvalidInput      = errors.New("invalid input")
	ErrTimeout           = errors.New("operation timed out")
	ErrPermissionDenied  = errors.New("permission denied")
	ErrInternal          = errors.New("internal error")
	ErrBackupFailed      = errors.New("backup operation failed")
	ErrRestoreFailed     = errors.New("restore operation failed")
	ErrInvalidAlgorithm  = errors.New("invalid algorithm")
	ErrCircuitBreakerOpen = errors.New("circuit breaker is open")
)

type BackupError struct {
	Op  string
	Err error
}

func (e *BackupError) Error() string {
	return fmt.Sprintf("backup %s: %v", e.Op, e.Err)
}

func (e *BackupError) Unwrap() error {
	return e.Err
}

type ValidationError struct {
	Field   string
	Message string
}

func (e *ValidationError) Error() string {
	return fmt.Sprintf("validation failed for %s: %s", e.Field, e.Message)
}

func NewBackupError(op string, err error) error {
	return &BackupError{Op: op, Err: err}
}

func NewValidationError(field, message string) error {
	return &ValidationError{Field: field, Message: message}
}
