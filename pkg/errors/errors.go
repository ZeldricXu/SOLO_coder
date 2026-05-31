package errors

import (
	"fmt"
	"net/http"
)

type ErrorCode int

const (
	CodeSuccess           ErrorCode = 0
	CodeBadRequest        ErrorCode = 400
	CodeUnauthorized      ErrorCode = 401
	CodeForbidden         ErrorCode = 403
	CodeNotFound          ErrorCode = 404
	CodeConflict          ErrorCode = 409
	CodeValidationError   ErrorCode = 422
	CodeInternalError     ErrorCode = 500
	CodeServiceUnavailable ErrorCode = 503
	CodeGatewayTimeout    ErrorCode = 504
)

type AppError struct {
	Code    ErrorCode `json:"code"`
	Message string    `json:"message"`
	Details interface{} `json:"details,omitempty"`
}

func (e *AppError) Error() string {
	return fmt.Sprintf("[%d] %s", e.Code, e.Message)
}

func New(code ErrorCode, message string) *AppError {
	return &AppError{
		Code:    code,
		Message: message,
	}
}

func NewWithDetails(code ErrorCode, message string, details interface{}) *AppError {
	return &AppError{
		Code:    code,
		Message: message,
		Details: details,
	}
}

func BadRequest(message string) *AppError {
	return New(CodeBadRequest, message)
}

func Unauthorized(message string) *AppError {
	return New(CodeUnauthorized, message)
}

func Forbidden(message string) *AppError {
	return New(CodeForbidden, message)
}

func NotFound(message string) *AppError {
	return New(CodeNotFound, message)
}

func Conflict(message string, resourceID string) *AppError {
	return NewWithDetails(CodeConflict, message, map[string]string{
		"resource_id": resourceID,
	})
}

func ValidationError(message string, details interface{}) *AppError {
	return NewWithDetails(CodeValidationError, message, details)
}

func InternalError(message string) *AppError {
	return New(CodeInternalError, message)
}

func ServiceUnavailable(message string) *AppError {
	return New(CodeServiceUnavailable, message)
}

func GatewayTimeout(message string) *AppError {
	return New(CodeGatewayTimeout, message)
}

func ToHTTPStatus(code ErrorCode) int {
	switch code {
	case CodeSuccess:
		return http.StatusOK
	case CodeBadRequest:
		return http.StatusBadRequest
	case CodeUnauthorized:
		return http.StatusUnauthorized
	case CodeForbidden:
		return http.StatusForbidden
	case CodeNotFound:
		return http.StatusNotFound
	case CodeConflict:
		return http.StatusConflict
	case CodeValidationError:
		return http.StatusUnprocessableEntity
	case CodeInternalError:
		return http.StatusInternalServerError
	case CodeServiceUnavailable:
		return http.StatusServiceUnavailable
	case CodeGatewayTimeout:
		return http.StatusGatewayTimeout
	default:
		return http.StatusInternalServerError
	}
}
