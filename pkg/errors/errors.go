package errors

import (
	"fmt"
	"net/http"
)

type ErrorCode int

const (
	ErrCodeValidation       ErrorCode = 40001
	ErrCodeUnauthorized     ErrorCode = 40101
	ErrCodeForbidden        ErrorCode = 40301
	ErrCodeNotFound         ErrorCode = 40401
	ErrCodeConflict         ErrorCode = 40901
	ErrCodeRateLimit        ErrorCode = 42901
	ErrCodeInternal         ErrorCode = 50001
	ErrCodeTimeout          ErrorCode = 50401
	ErrCodeConfigNotFound   ErrorCode = 50002
	ErrCodeResourceExhausted ErrorCode = 50003
	ErrCodeEncryption       ErrorCode = 50004
	ErrCodeDecryption       ErrorCode = 50005
	ErrCodeAttestation      ErrorCode = 50006
	ErrCodeMPCProtocol      ErrorCode = 50007
	ErrCodePrivacyBudget    ErrorCode = 50008
)

type AppError struct {
	Code     ErrorCode `json:"code"`
	Message  string    `json:"message"`
	Details  string    `json:"details,omitempty"`
	Suggestion string   `json:"suggestion,omitempty"`
	Err      error     `json:"-"`
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

func (e *AppError) HTTPStatus() int {
	switch e.Code {
	case ErrCodeValidation:
		return http.StatusBadRequest
	case ErrCodeUnauthorized:
		return http.StatusUnauthorized
	case ErrCodeForbidden:
		return http.StatusForbidden
	case ErrCodeNotFound:
		return http.StatusNotFound
	case ErrCodeConflict:
		return http.StatusConflict
	case ErrCodeRateLimit:
		return http.StatusTooManyRequests
	case ErrCodeTimeout:
		return http.StatusGatewayTimeout
	default:
		return http.StatusInternalServerError
	}
}

func New(code ErrorCode, message string) *AppError {
	return &AppError{
		Code:    code,
		Message: message,
	}
}

func NewWithDetails(code ErrorCode, message, details string) *AppError {
	return &AppError{
		Code:    code,
		Message: message,
		Details: details,
	}
}

func Wrap(err error, code ErrorCode, message string) *AppError {
	return &AppError{
		Code:    code,
		Message: message,
		Err:     err,
	}
}

func ValidationError(field, reason string) *AppError {
	return &AppError{
		Code:       ErrCodeValidation,
		Message:    "参数校验失败",
		Details:    fmt.Sprintf("字段 '%s': %s", field, reason),
		Suggestion: "请检查请求参数格式与取值范围",
	}
}

func NotFoundError(resource, id string) *AppError {
	return &AppError{
		Code:       ErrCodeNotFound,
		Message:    "资源不存在",
		Details:    fmt.Sprintf("%s '%s' 未找到", resource, id),
		Suggestion: "请确认资源ID是否正确",
	}
}

func TimeoutError(service string) *AppError {
	return &AppError{
		Code:       ErrCodeTimeout,
		Message:    "上游服务响应超时",
		Details:    fmt.Sprintf("服务 '%s' 响应超时", service),
		Suggestion: "请稍后重试或联系管理员",
	}
}

func InternalError(err error, operation string) *AppError {
	return &AppError{
		Code:       ErrCodeInternal,
		Message:    "内部处理错误",
		Details:    fmt.Sprintf("操作 '%s' 失败", operation),
		Suggestion: "请联系技术支持",
		Err:        err,
	}
}

func RateLimitError() *AppError {
	return &AppError{
		Code:       ErrCodeRateLimit,
		Message:    "请求过于频繁",
		Suggestion: "请降低请求频率稍后重试",
	}
}
