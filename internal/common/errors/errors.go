package errors

import (
	"fmt"
	"net/http"
)

type AppError struct {
	Code    int
	Message string
	Err     error
}

func (e *AppError) Error() string {
	if e.Err != nil {
		return fmt.Sprintf("%s: %v", e.Message, e.Err)
	}
	return e.Message
}

func (e *AppError) Unwrap() error {
	return e.Err
}

func New(code int, message string, err error) *AppError {
	return &AppError{
		Code:    code,
		Message: message,
		Err:     err,
	}
}

func BadRequest(message string, err error) *AppError {
	return New(http.StatusBadRequest, message, err)
}

func Unauthorized(message string, err error) *AppError {
	return New(http.StatusUnauthorized, message, err)
}

func Forbidden(message string, err error) *AppError {
	return New(http.StatusForbidden, message, err)
}

func NotFound(message string, err error) *AppError {
	return New(http.StatusNotFound, message, err)
}

func Conflict(resourceID string, err error) *AppError {
	return New(http.StatusConflict, fmt.Sprintf("resource conflict: %s", resourceID), err)
}

func Internal(message string, err error) *AppError {
	return New(http.StatusInternalServerError, message, err)
}

func Timeout(message string, err error) *AppError {
	return New(http.StatusGatewayTimeout, message, err)
}
