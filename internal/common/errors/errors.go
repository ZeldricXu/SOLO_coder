package errors

import (
	"fmt"
	"net/http"
)

type ErrorCode string

const (
	ErrCodeValidation  ErrorCode = "VALIDATION_ERROR"
	ErrCodeNotFound    ErrorCode = "NOT_FOUND"
	ErrCodeInternal    ErrorCode = "INTERNAL_ERROR"
	ErrCodeUnauthorized ErrorCode = "UNAUTHORIZED"
	ErrCodeForbidden   ErrorCode = "FORBIDDEN"
	ErrCodeConflict    ErrorCode = "CONFLICT"
	ErrCodeTimeout     ErrorCode = "TIMEOUT"
	ErrCodeQuotaExceeded ErrorCode = "QUOTA_EXCEEDED"
)

type Error struct {
	Code    ErrorCode `json:"code"`
	Message string    `json:"message"`
	Details []string  `json:"details,omitempty"`
	Cause   error     `json:"-"`
}

func (e *Error) Error() string {
	if e.Cause != nil {
		return fmt.Sprintf("%s: %s: %v", e.Code, e.Message, e.Cause)
	}
	return fmt.Sprintf("%s: %s", e.Code, e.Message)
}

func (e *Error) Unwrap() error {
	return e.Cause
}

func (e *Error) HTTPStatus() int {
	switch e.Code {
	case ErrCodeValidation:
		return http.StatusBadRequest
	case ErrCodeNotFound:
		return http.StatusNotFound
	case ErrCodeUnauthorized:
		return http.StatusUnauthorized
	case ErrCodeForbidden:
		return http.StatusForbidden
	case ErrCodeConflict:
		return http.StatusConflict
	case ErrCodeTimeout:
		return http.StatusGatewayTimeout
	case ErrCodeQuotaExceeded:
		return http.StatusTooManyRequests
	default:
		return http.StatusInternalServerError
	}
}

func New(code ErrorCode, message string, details ...string) *Error {
	return &Error{
		Code:    code,
		Message: message,
		Details: details,
	}
}

func Wrap(err error, code ErrorCode, message string, details ...string) *Error {
	return &Error{
		Code:    code,
		Message: message,
		Details: details,
		Cause:   err,
	}
}

func ValidationError(message string, details ...string) *Error {
	return New(ErrCodeValidation, message, details...)
}

func NotFoundError(message string, details ...string) *Error {
	return New(ErrCodeNotFound, message, details...)
}

func InternalError(err error, message string, details ...string) *Error {
	return Wrap(err, ErrCodeInternal, message, details...)
}
