package common

import "fmt"

type ErrorCode string

const (
	ErrCodeNotFound       ErrorCode = "NOT_FOUND"
	ErrCodeInvalidInput   ErrorCode = "INVALID_INPUT"
	ErrCodeInternal       ErrorCode = "INTERNAL_ERROR"
	ErrCodeConflict       ErrorCode = "CONFLICT"
	ErrCodeUnauthorized   ErrorCode = "UNAUTHORIZED"
	ErrCodeTimeout        ErrorCode = "TIMEOUT"
	ErrCodeNotImplemented ErrorCode = "NOT_IMPLEMENTED"
)

type AppError struct {
	Code    ErrorCode `json:"code"`
	Message string    `json:"message"`
	ResourceID string `json:"resource_id,omitempty"`
	Details interface{} `json:"details,omitempty"`
}

func (e *AppError) Error() string {
	if e.ResourceID != "" {
		return fmt.Sprintf("[%s] %s (resource: %s)", e.Code, e.Message, e.ResourceID)
	}
	return fmt.Sprintf("[%s] %s", e.Code, e.Message)
}

func NewNotFoundError(resource string, id string) *AppError {
	return &AppError{
		Code:       ErrCodeNotFound,
		Message:    fmt.Sprintf("%s not found: %s", resource, id),
		ResourceID: id,
	}
}

func NewInvalidInputError(message string) *AppError {
	return &AppError{
		Code:    ErrCodeInvalidInput,
		Message: message,
	}
}

func NewInternalError(message string) *AppError {
	return &AppError{
		Code:    ErrCodeInternal,
		Message: message,
	}
}

func NewConflictError(resourceID string, message string) *AppError {
	return &AppError{
		Code:       ErrCodeConflict,
		Message:    message,
		ResourceID: resourceID,
	}
}

func NewTimeoutError(message string) *AppError {
	return &AppError{
		Code:    ErrCodeTimeout,
		Message: message,
	}
}
