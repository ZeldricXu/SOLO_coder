package errors

import "fmt"

type ErrorCode int

const (
	ErrCodeInvalidParams     ErrorCode = 400001
	ErrCodeUnauthorized      ErrorCode = 401001
	ErrCodeForbidden         ErrorCode = 403001
	ErrCodeNotFound          ErrorCode = 404001
	ErrCodeConflict          ErrorCode = 409001
	ErrCodeRateLimitExceeded ErrorCode = 429001
	ErrCodeInternal          ErrorCode = 500001
	ErrCodeServiceUnavailable ErrorCode = 503001
)

type AppError struct {
	Code    ErrorCode `json:"code"`
	Message string    `json:"message"`
	Details string    `json:"details,omitempty"`
}

func (e *AppError) Error() string {
	if e.Details != "" {
		return fmt.Sprintf("[%d] %s: %s", e.Code, e.Message, e.Details)
	}
	return fmt.Sprintf("[%d] %s", e.Code, e.Message)
}

func New(code ErrorCode, message string) *AppError {
	return &AppError{Code: code, Message: message}
}

func NewWithDetails(code ErrorCode, message, details string) *AppError {
	return &AppError{Code: code, Message: message, Details: details}
}

func InvalidParams(details string) *AppError {
	return NewWithDetails(ErrCodeInvalidParams, "参数无效", details)
}

func Unauthorized(details string) *AppError {
	return NewWithDetails(ErrCodeUnauthorized, "未授权", details)
}

func Forbidden(details string) *AppError {
	return NewWithDetails(ErrCodeForbidden, "禁止访问", details)
}

func NotFound(resource string) *AppError {
	return NewWithDetails(ErrCodeNotFound, "资源不存在", resource)
}

func Conflict(details string) *AppError {
	return NewWithDetails(ErrCodeConflict, "资源冲突", details)
}

func RateLimitExceeded() *AppError {
	return New(ErrCodeRateLimitExceeded, "请求频率超限")
}

func Internal(details string) *AppError {
	return NewWithDetails(ErrCodeInternal, "内部错误", details)
}

func ServiceUnavailable(service string) *AppError {
	return NewWithDetails(ErrCodeServiceUnavailable, "服务不可用", service)
}
