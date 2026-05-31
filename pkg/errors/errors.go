package errors

import (
	"fmt"
)

type ErrorCode string

const (
	ErrCodeValidation ErrorCode = "VALIDATION_ERROR"
	ErrCodeNotFound   ErrorCode = "NOT_FOUND"
	ErrCodeInternal   ErrorCode = "INTERNAL_ERROR"
	ErrCodeTimeout    ErrorCode = "TIMEOUT"
	ErrCodePermission ErrorCode = "PERMISSION_DENIED"
	ErrCodeConflict   ErrorCode = "CONFLICT"
)

type AppError struct {
	Code    ErrorCode `json:"code"`
	Message string    `json:"message"`
	Details string    `json:"details,omitempty"`
}

func (e *AppError) Error() string {
	if e.Details != "" {
		return fmt.Sprintf("[%s] %s: %s", e.Code, e.Message, e.Details)
	}
	return fmt.Sprintf("[%s] %s", e.Code, e.Message)
}

func NewValidationError(message, details string) *AppError {
	return &AppError{
		Code:    ErrCodeValidation,
		Message: message,
		Details: details,
	}
}

func NewNotFoundError(message string) *AppError {
	return &AppError{
		Code:    ErrCodeNotFound,
		Message: message,
	}
}

func NewInternalError(message string) *AppError {
	return &AppError{
		Code:    ErrCodeInternal,
		Message: message,
	}
}

func NewTimeoutError(message string) *AppError {
	return &AppError{
		Code:    ErrCodeTimeout,
		Message: message,
	}
}

func NewPermissionError(message string) *AppError {
	return &AppError{
		Code:    ErrCodePermission,
		Message: message,
	}
}

func NewConflictError(message string) *AppError {
	return &AppError{
		Code:    ErrCodeConflict,
		Message: message,
	}
}
