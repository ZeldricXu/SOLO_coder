package errors

import (
	"fmt"
)

type ErrorCode string

const (
	ErrCodeValidation     ErrorCode = "VALIDATION_ERROR"
	ErrCodeNotFound       ErrorCode = "NOT_FOUND"
	ErrCodeUnauthorized   ErrorCode = "UNAUTHORIZED"
	ErrCodeForbidden      ErrorCode = "FORBIDDEN"
	ErrCodeInternal       ErrorCode = "INTERNAL_ERROR"
	ErrCodeTimeout        ErrorCode = "TIMEOUT_ERROR"
	ErrCodeConflict       ErrorCode = "CONFLICT_ERROR"
	ErrCodeTooManyRequests ErrorCode = "TOO_MANY_REQUESTS"
	ErrCodeRetryable      ErrorCode = "RETRYABLE_ERROR"
	ErrCodeNonRetryable   ErrorCode = "NON_RETRYABLE_ERROR"
)

type AppError struct {
	Code      ErrorCode `json:"code"`
	Message   string    `json:"message"`
	Details   interface{} `json:"details,omitempty"`
	Retryable bool      `json:"retryable"`
}

func (e *AppError) Error() string {
	return fmt.Sprintf("[%s] %s", e.Code, e.Message)
}

func NewValidationError(message string, details ...interface{}) *AppError {
	return &AppError{
		Code:      ErrCodeValidation,
		Message:   message,
		Details:   details,
		Retryable: false,
	}
}

func NewNotFoundError(message string) *AppError {
	return &AppError{
		Code:      ErrCodeNotFound,
		Message:   message,
		Retryable: false,
	}
}

func NewUnauthorizedError(message string) *AppError {
	return &AppError{
		Code:      ErrCodeUnauthorized,
		Message:   message,
		Retryable: false,
	}
}

func NewInternalError(message string, err ...error) *AppError {
	e := &AppError{
		Code:      ErrCodeInternal,
		Message:   message,
		Retryable: true,
	}
	if len(err) > 0 && err[0] != nil {
		e.Details = err[0].Error()
	}
	return e
}

func NewTimeoutError(message string) *AppError {
	return &AppError{
		Code:      ErrCodeTimeout,
		Message:   message,
		Retryable: true,
	}
}

func NewConflictError(message string) *AppError {
	return &AppError{
		Code:      ErrCodeConflict,
		Message:   message,
		Retryable: true,
	}
}

func NewRetryableError(message string) *AppError {
	return &AppError{
		Code:      ErrCodeRetryable,
		Message:   message,
		Retryable: true,
	}
}

func NewNonRetryableError(message string) *AppError {
	return &AppError{
		Code:      ErrCodeNonRetryable,
		Message:   message,
		Retryable: false,
	}
}

func IsRetryable(err error) bool {
	if appErr, ok := err.(*AppError); ok {
		return appErr.Retryable
	}
	return true
}
