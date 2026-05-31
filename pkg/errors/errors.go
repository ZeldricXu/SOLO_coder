package errors

import "fmt"

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

func New(code int, message string) *AppError {
	return &AppError{
		Code:    code,
		Message: message,
	}
}

func Wrap(code int, message string, err error) *AppError {
	return &AppError{
		Code:    code,
		Message: message,
		Err:     err,
	}
}

var (
	ErrNotFound      = New(404, "resource not found")
	ErrInvalidParam  = New(400, "invalid parameters")
	ErrUnauthorized  = New(401, "unauthorized")
	ErrForbidden     = New(403, "forbidden")
	ErrInternal      = New(500, "internal server error")
	ErrConflict      = New(409, "resource conflict")
	ErrTimeout       = New(504, "operation timeout")
	ErrBusy          = New(503, "service busy, please try again later")
)
