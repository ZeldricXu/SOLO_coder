package utils

import "errors"

var (
	ErrNotFound          = errors.New("resource not found")
	ErrInvalidParameter  = errors.New("invalid parameter")
	ErrPermissionDenied  = errors.New("permission denied")
	ErrTimeout           = errors.New("operation timeout")
	ErrInternal          = errors.New("internal error")
	ErrAlreadyExists     = errors.New("resource already exists")
	ErrBudgetExhausted   = errors.New("privacy budget exhausted")
	ErrInvalidSignature  = errors.New("invalid signature")
	ErrTamperingDetected = errors.New("data tampering detected")
	ErrTaskCancelled     = errors.New("task cancelled")
	ErrTaskFailed        = errors.New("task failed")
)

type RetryableError struct {
	Err error
}

func (e *RetryableError) Error() string {
	return e.Err.Error()
}

func NewRetryableError(err error) error {
	return &RetryableError{Err: err}
}

func IsRetryable(err error) bool {
	_, ok := err.(*RetryableError)
	return ok
}
