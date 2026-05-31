package errors

import (
	"fmt"
	"time"
)

type ErrorCode string

const (
	ErrCodeInvalidParam     ErrorCode = "INVALID_PARAM"
	ErrCodeNotFound         ErrorCode = "NOT_FOUND"
	ErrCodeInternal         ErrorCode = "INTERNAL_ERROR"
	ErrCodeTimeout          ErrorCode = "TIMEOUT"
	ErrCodeUnauthorized     ErrorCode = "UNAUTHORIZED"
	ErrCodeConflict         ErrorCode = "CONFLICT"
	ErrCodeValidationFailed ErrorCode = "VALIDATION_FAILED"
)

type AppError struct {
	Code      ErrorCode `json:"code"`
	Message   string    `json:"message"`
	Detail    string    `json:"detail,omitempty"`
	Timestamp time.Time `json:"timestamp"`
	TraceID   string    `json:"trace_id,omitempty"`
}

func (e *AppError) Error() string {
	return fmt.Sprintf("[%s] %s: %s", e.Code, e.Message, e.Detail)
}

func New(code ErrorCode, message string) *AppError {
	return &AppError{
		Code:      code,
		Message:   message,
		Timestamp: time.Now().UTC(),
	}
}

func NewWithDetail(code ErrorCode, message, detail string) *AppError {
	err := New(code, message)
	err.Detail = detail
	return err
}

func NewInvalidParam(message string) *AppError {
	return New(ErrCodeInvalidParam, message)
}

func NewNotFound(message string) *AppError {
	return New(ErrCodeNotFound, message)
}

func NewInternal(message string) *AppError {
	return New(ErrCodeInternal, message)
}

func NewTimeout(message string) *AppError {
	return New(ErrCodeTimeout, message)
}
