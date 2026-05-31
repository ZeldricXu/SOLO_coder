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
	CodeValidationFailed  ErrorCode = 422
	CodeTooManyRequests   ErrorCode = 429
	CodeInternalError     ErrorCode = 500
	CodeNotImplemented    ErrorCode = 501
	CodeServiceUnavailable ErrorCode = 503
	CodeGatewayTimeout    ErrorCode = 504
)

type AppError struct {
	Code    ErrorCode   `json:"code"`
	Message string      `json:"message"`
	Details interface{} `json:"details,omitempty"`
}

func (e *AppError) Error() string {
	return fmt.Sprintf("code: %d, message: %s", e.Code, e.Message)
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

func ValidationFailed(message string, details interface{}) *AppError {
	return NewWithDetails(CodeValidationFailed, message, details)
}

func TooManyRequests(message string) *AppError {
	return New(CodeTooManyRequests, message)
}

func InternalError(message string) *AppError {
	return New(CodeInternalError, message)
}

func NotImplemented(message string) *AppError {
	return New(CodeNotImplemented, message)
}

func ServiceUnavailable(message string) *AppError {
	return New(CodeServiceUnavailable, message)
}

func GatewayTimeout(message string) *AppError {
	return New(CodeGatewayTimeout, message)
}

func (e *AppError) HTTPStatus() int {
	switch e.Code {
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
	case CodeValidationFailed:
		return http.StatusUnprocessableEntity
	case CodeTooManyRequests:
		return http.StatusTooManyRequests
	case CodeInternalError:
		return http.StatusInternalServerError
	case CodeNotImplemented:
		return http.StatusNotImplemented
	case CodeServiceUnavailable:
		return http.StatusServiceUnavailable
	case CodeGatewayTimeout:
		return http.StatusGatewayTimeout
	default:
		return http.StatusInternalServerError
	}
}
