package common

import "fmt"

type ErrorCode string

const (
	ErrNotFound          ErrorCode = "NOT_FOUND"
	ErrAlreadyExists     ErrorCode = "ALREADY_EXISTS"
	ErrInvalidConfig     ErrorCode = "INVALID_CONFIG"
	ErrUnauthorized      ErrorCode = "UNAUTHORIZED"
	ErrPermissionDenied  ErrorCode = "PERMISSION_DENIED"
	ErrStateLocked       ErrorCode = "STATE_LOCKED"
	ErrStateCorrupted    ErrorCode = "STATE_CORRUPTED"
	ErrProviderError     ErrorCode = "PROVIDER_ERROR"
	ErrDependencyError   ErrorCode = "DEPENDENCY_ERROR"
	ErrComplianceFailed  ErrorCode = "COMPLIANCE_FAILED"
	ErrCredentialExpired ErrorCode = "CREDENTIAL_EXPIRED"
	ErrOperationFailed   ErrorCode = "OPERATION_FAILED"
)

type AppError struct {
	Code    ErrorCode `json:"code"`
	Message string    `json:"message"`
	Cause   error     `json:"-"`
}

func (e *AppError) Error() string {
	if e.Cause != nil {
		return fmt.Sprintf("[%s] %s: %v", e.Code, e.Message, e.Cause)
	}
	return fmt.Sprintf("[%s] %s", e.Code, e.Message)
}

func NewError(code ErrorCode, message string, cause ...error) *AppError {
	err := &AppError{
		Code:    code,
		Message: message,
	}
	if len(cause) > 0 && cause[0] != nil {
		err.Cause = cause[0]
	}
	return err
}

func (e *AppError) Unwrap() error {
	return e.Cause
}
