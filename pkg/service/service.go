package service

import "errors"

var (
	ErrServiceNotRunning = errors.New("service not running")
	ErrServiceRunning    = errors.New("service already running")
	ErrNotFound          = errors.New("resource not found")
	ErrTimeout           = errors.New("operation timeout")
	ErrValidation        = errors.New("validation failed")
)

type Service interface {
	Name() string
	Start() error
	Stop() error
	IsRunning() bool
}

type Lifecycle interface {
	OnStart() error
	OnStop() error
}

type BaseService struct {
	name    string
	running bool
}

func NewBaseService(name string) *BaseService {
	return &BaseService{name: name}
}

func (b *BaseService) Name() string {
	return b.name
}

func (b *BaseService) IsRunning() bool {
	return b.running
}

func (b *BaseService) SetRunning(value bool) {
	b.running = value
}

func (b *BaseService) ValidateStart() error {
	if b.running {
		return ErrServiceRunning
	}
	return nil
}

func (b *BaseService) ValidateStop() error {
	if !b.running {
		return ErrServiceNotRunning
	}
	return nil
}

type EventEmitter interface {
	Emit(eventType string, data interface{})
	EventChannel() <-chan interface{}
}

type EventListener interface {
	Listen(eventChan <-chan interface{})
}

type ErrorDetail struct {
	Code    string
	Message string
	Cause   error
}

func NewErrorDetail(code, message string, cause error) *ErrorDetail {
	return &ErrorDetail{
		Code:    code,
		Message: message,
		Cause:   cause,
	}
}

func (e *ErrorDetail) Error() string {
	if e.Cause != nil {
		return e.Message + ": " + e.Cause.Error()
	}
	return e.Message
}

func (e *ErrorDetail) Unwrap() error {
	return e.Cause
}
