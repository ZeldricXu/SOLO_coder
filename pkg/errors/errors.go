package errors

import "fmt"

type AppError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Detail  string `json:"detail,omitempty"`
}

func (e *AppError) Error() string {
	if e.Detail != "" {
		return fmt.Sprintf("%s: %s", e.Message, e.Detail)
	}
	return e.Message
}

func New(code int, message string) *AppError {
	return &AppError{Code: code, Message: message}
}

func NewWithDetail(code int, message, detail string) *AppError {
	return &AppError{Code: code, Message: message, Detail: detail}
}

var (
	ErrNotFound            = New(404, "资源不存在")
	ErrBadRequest          = New(400, "请求参数错误")
	ErrUnauthorized        = New(401, "未授权访问")
	ErrForbidden           = New(403, "禁止访问")
	ErrConflict            = New(409, "资源冲突")
	ErrInternalServer      = New(500, "服务器内部错误")
	ErrServiceUnavailable  = New(503, "服务不可用")
	ErrTimeout             = New(504, "请求超时")
	ErrTenantNotFound      = New(404, "租户不存在")
	ErrQuotaExceeded       = New(429, "配额超限")
	ErrInvalidConfig       = New(400, "配置无效")
	ErrValidationFailed    = New(422, "参数校验失败")
)
