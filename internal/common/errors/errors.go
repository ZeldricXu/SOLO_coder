package errors

import (
	"fmt"
	"net/http"
)

type AppError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Details string `json:"details,omitempty"`
}

func (e *AppError) Error() string {
	if e.Details != "" {
		return fmt.Sprintf("%s: %s", e.Message, e.Details)
	}
	return e.Message
}

func New(code int, message string, details ...string) *AppError {
	appErr := &AppError{
		Code:    code,
		Message: message,
	}
	if len(details) > 0 {
		appErr.Details = details[0]
	}
	return appErr
}

var (
	ErrInvalidParams      = New(http.StatusBadRequest, "参数错误")
	ErrUnauthorized       = New(http.StatusUnauthorized, "未授权访问")
	ErrNotFound           = New(http.StatusNotFound, "资源不存在")
	ErrInternal           = New(http.StatusInternalServerError, "内部处理错误")
	ErrTimeout            = New(http.StatusGatewayTimeout, "上游服务响应超时")
	ErrChainNotSupported  = New(http.StatusBadRequest, "不支持的链类型")
	ErrInsufficientFunds  = New(http.StatusBadRequest, "账户余额不足")
	ErrInvalidSignature   = New(http.StatusBadRequest, "签名无效")
	ErrGasEstimationFailed = New(http.StatusInternalServerError, "Gas费用预估失败")
	ErrStorageFailed      = New(http.StatusInternalServerError, "存储操作失败")
)

func Wrap(err error, message string) *AppError {
	if appErr, ok := err.(*AppError); ok {
		return appErr
	}
	return New(http.StatusInternalServerError, message, err.Error())
}
