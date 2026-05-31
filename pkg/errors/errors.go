package errors

import (
	"fmt"
)

type ErrorCode string

const (
	ErrCodeNotFound      ErrorCode = "NOT_FOUND"
	ErrCodeAlreadyExists ErrorCode = "ALREADY_EXISTS"
	ErrCodeInvalidInput  ErrorCode = "INVALID_INPUT"
	ErrCodeUnauthorized  ErrorCode = "UNAUTHORIZED"
	ErrCodeForbidden     ErrorCode = "FORBIDDEN"
	ErrCodeRateLimited   ErrorCode = "RATE_LIMITED"
	ErrCodeInternal      ErrorCode = "INTERNAL_ERROR"
	ErrCodeConflict      ErrorCode = "CONFLICT"
)

type AppError struct {
	Code    ErrorCode `json:"code"`
	Message string    `json:"message"`
	Err     error     `json:"-"`
}

func (e *AppError) Error() string {
	if e.Err != nil {
		return fmt.Sprintf("[%s] %s: %v", e.Code, e.Message, e.Err)
	}
	return fmt.Sprintf("[%s] %s", e.Code, e.Message)
}

func (e *AppError) Unwrap() error {
	return e.Err
}

func New(code ErrorCode, message string) *AppError {
	return &AppError{Code: code, Message: message}
}

func Wrap(code ErrorCode, message string, err error) *AppError {
	return &AppError{Code: code, Message: message, Err: err}
}

func NotFound(message string) *AppError {
	return New(ErrCodeNotFound, message)
}

func AlreadyExists(message string) *AppError {
	return New(ErrCodeAlreadyExists, message)
}

func InvalidInput(message string) *AppError {
	return New(ErrCodeInvalidInput, message)
}

func Unauthorized(message string) *AppError {
	return New(ErrCodeUnauthorized, message)
}

func Forbidden(message string) *AppError {
	return New(ErrCodeForbidden, message)
}

func RateLimited(message string) *AppError {
	return New(ErrCodeRateLimited, message)
}

func Internal(message string, err error) *AppError {
	return Wrap(ErrCodeInternal, message, err)
}

func Conflict(message string) *AppError {
	return New(ErrCodeConflict, message)
}
