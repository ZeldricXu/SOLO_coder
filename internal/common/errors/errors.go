package errors

import (
	"fmt"
	"net/http"
)

type ErrorCode int

const (
	ErrCodeBadRequest     ErrorCode = 400
	ErrCodeUnauthorized   ErrorCode = 401
	ErrCodeForbidden      ErrorCode = 403
	ErrCodeNotFound       ErrorCode = 404
	ErrCodeConflict       ErrorCode = 409
	ErrCodeInternal       ErrorCode = 500
	ErrCodeNotImplemented ErrorCode = 501
	ErrCodeUnavailable    ErrorCode = 503
)

type AppError struct {
	Code    ErrorCode `json:"code"`
	Message string    `json:"message"`
	Detail  string    `json:"detail,omitempty"`
}

func (e *AppError) Error() string {
	if e.Detail != "" {
		return fmt.Sprintf("%s: %s", e.Message, e.Detail)
	}
	return e.Message
}

func (e *AppError) HTTPStatus() int {
	switch e.Code {
	case ErrCodeBadRequest:
		return http.StatusBadRequest
	case ErrCodeUnauthorized:
		return http.StatusUnauthorized
	case ErrCodeForbidden:
		return http.StatusForbidden
	case ErrCodeNotFound:
		return http.StatusNotFound
	case ErrCodeConflict:
		return http.StatusConflict
	case ErrCodeNotImplemented:
		return http.StatusNotImplemented
	case ErrCodeUnavailable:
		return http.StatusServiceUnavailable
	default:
		return http.StatusInternalServerError
	}
}

func NewBadRequest(message string, detail string) *AppError {
	return &AppError{Code: ErrCodeBadRequest, Message: message, Detail: detail}
}

func NewNotFound(message string, detail string) *AppError {
	return &AppError{Code: ErrCodeNotFound, Message: message, Detail: detail}
}

func NewConflict(message string, detail string) *AppError {
	return &AppError{Code: ErrCodeConflict, Message: message, Detail: detail}
}

func NewInternal(message string, detail string) *AppError {
	return &AppError{Code: ErrCodeInternal, Message: message, Detail: detail}
}

func NewUnavailable(message string, detail string) *AppError {
	return &AppError{Code: ErrCodeUnavailable, Message: message, Detail: detail}
}

var (
	ErrChannelNotFound    = NewNotFound("channel not found", "notification channel is not registered")
	ErrTemplateNotFound   = NewNotFound("template not found", "notification template does not exist")
	ErrNotificationFailed = NewInternal("notification failed", "failed to send notification")
	ErrSuppressionRuleNotFound = NewNotFound("suppression rule not found", "suppression rule does not exist")
	ErrInvalidPriority    = NewBadRequest("invalid priority", "priority must be between 1 and 5")
	ErrInvalidChannel     = NewBadRequest("invalid channel", "notification channel is not valid")
)
