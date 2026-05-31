package errors

import (
	"fmt"
)

type ErrorCode int

const (
	ErrCodeValidation   ErrorCode = 40001
	ErrCodeUnauthorized ErrorCode = 40101
	ErrCodeNotFound     ErrorCode = 40401
	ErrCodeConflict     ErrorCode = 40901
	ErrCodeInternal     ErrorCode = 50001
	ErrCodeTimeout      ErrorCode = 50401
)

type AppError struct {
	Code    ErrorCode `json:"code"`
	Message string    `json:"message"`
	Details []string  `json:"details,omitempty"`
	Err     error     `json:"-"`
}

func (e *AppError) Error() string {
	if e.Err != nil {
		return fmt.Sprintf("[%d] %s: %v", e.Code, e.Message, e.Err)
	}
	return fmt.Sprintf("[%d] %s", e.Code, e.Message)
}

func (e *AppError) Unwrap() error {
	return e.Err
}

func NewValidationError(message string, details ...string) *AppError {
	return &AppError{
		Code:    ErrCodeValidation,
		Message: message,
		Details: details,
	}
}

func NewUnauthorizedError(message string) *AppError {
	return &AppError{
		Code:    ErrCodeUnauthorized,
		Message: message,
	}
}

func NewNotFoundError(message string) *AppError {
	return &AppError{
		Code:    ErrCodeNotFound,
		Message: message,
	}
}

func NewConflictError(message string) *AppError {
	return &AppError{
		Code:    ErrCodeConflict,
		Message: message,
	}
}

func NewInternalError(message string, err error) *AppError {
	return &AppError{
		Code:    ErrCodeInternal,
		Message: message,
		Err:     err,
	}
}

func NewTimeoutError(message string) *AppError {
	return &AppError{
		Code:    ErrCodeTimeout,
		Message: message,
	}
}

func Wrap(err error, code ErrorCode, message string) *AppError {
	if appErr, ok := err.(*AppError); ok {
		return appErr
	}
	return &AppError{
		Code:    code,
		Message: message,
		Err:     err,
	}
}
