package common

import (
	"errors"
	"fmt"
)

var (
	ErrNotFound          = errors.New("resource not found")
	ErrAlreadyExists     = errors.New("resource already exists")
	ErrInvalidInput      = errors.New("invalid input")
	ErrValidationFailed  = errors.New("validation failed")
	ErrTimeout           = errors.New("operation timed out")
	ErrUnauthorized      = errors.New("unauthorized")
	ErrForbidden         = errors.New("forbidden")
	ErrInternal          = errors.New("internal error")
	ErrConflict          = errors.New("resource conflict")
	ErrServiceUnavailable = errors.New("service unavailable")
)

type AppError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Details string `json:"details,omitempty"`
	Cause   error  `json:"-"`
}

func (e *AppError) Error() string {
	if e.Cause != nil {
		return fmt.Sprintf("%s: %v", e.Message, e.Cause)
	}
	return e.Message
}

func (e *AppError) Unwrap() error {
	return e.Cause
}

func NewValidationError(message string, details string) *AppError {
	return &AppError{
		Code:    422,
		Message: message,
		Details: details,
	}
}

func NewNotFoundError(message string) *AppError {
	return &AppError{
		Code:    404,
		Message: message,
	}
}

func NewTimeoutError(message string) *AppError {
	return &AppError{
		Code:    504,
		Message: message,
	}
}

func NewInternalError(message string, cause error) *AppError {
	return &AppError{
		Code:    500,
		Message: message,
		Cause:   cause,
	}
}

func NewBadRequestError(message string) *AppError {
	return &AppError{
		Code:    400,
		Message: message,
	}
}

func NewConflictError(message string) *AppError {
	return &AppError{
		Code:    409,
		Message: message,
	}
}

type RetryableError struct {
	Err error
}

func (e *RetryableError) Error() string {
	return fmt.Sprintf("retryable error: %v", e.Err)
}

func (e *RetryableError) Unwrap() error {
	return e.Err
}

func NewRetryableError(err error) *RetryableError {
	return &RetryableError{Err: err}
}

func IsRetryable(err error) bool {
	var re *RetryableError
	return errors.As(err, &re)
}
