package common

import (
	"errors"
	"time"
)

func FormatTime(t time.Time) string {
	return t.Format("2006-01-02 15:04:05")
}

var (
	ErrNotFound      = errors.New("resource not found")
	ErrInvalidInput  = errors.New("invalid input")
	ErrUnauthorized  = errors.New("unauthorized")
	ErrForbidden     = errors.New("forbidden")
	ErrInternal      = errors.New("internal error")
	ErrAlreadyExists = errors.New("resource already exists")
	ErrTimeout       = errors.New("operation timeout")
	ErrCacheMiss     = errors.New("cache miss")
	ErrConfigInvalid = errors.New("invalid configuration")
)

type ErrorResponse struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Detail  string `json:"detail,omitempty"`
}
