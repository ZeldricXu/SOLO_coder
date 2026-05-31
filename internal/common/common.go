package common

import (
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/datatrace/datatrace/internal/models"
	"github.com/google/uuid"
)

var (
	ErrQueueFull       = errors.New("queue is full")
	ErrNotFound        = errors.New("resource not found")
	ErrInvalidInput    = errors.New("invalid input")
	ErrNotInitialized  = errors.New("service not initialized")
	ErrAlreadyStarted  = errors.New("service already started")
	ErrTimeout         = errors.New("operation timed out")
	ErrUnsupportedType = errors.New("unsupported type")
)

type ErrorCode string

const (
	CodeInternalError   ErrorCode = "INTERNAL_ERROR"
	CodeNotFound        ErrorCode = "NOT_FOUND"
	CodeInvalidInput    ErrorCode = "INVALID_INPUT"
	CodeQueueFull       ErrorCode = "QUEUE_FULL"
	CodeTimeout         ErrorCode = "TIMEOUT"
	CodeUnavailable     ErrorCode = "UNAVAILABLE"
)

type ServiceError struct {
	Code    ErrorCode `json:"code"`
	Message string    `json:"message"`
	Cause   error     `json:"-"`
}

func (e *ServiceError) Error() string {
	if e.Cause != nil {
		return fmt.Sprintf("%s: %s: %v", e.Code, e.Message, e.Cause)
	}
	return fmt.Sprintf("%s: %s", e.Code, e.Message)
}

func (e *ServiceError) Unwrap() error {
	return e.Cause
}

func NewError(code ErrorCode, message string) *ServiceError {
	return &ServiceError{
		Code:    code,
		Message: message,
	}
}

func WrapError(code ErrorCode, message string, cause error) *ServiceError {
	return &ServiceError{
		Code:    code,
		Message: message,
		Cause:   cause,
	}
}

type Lifecycle interface {
	Start() error
	Stop() error
}

type Observable interface {
	ToEntity() *models.Entity
	GetMetrics() map[string]interface{}
}

type Service interface {
	Lifecycle
	Observable
}

type QueueStatus struct {
	Queued     int
	InFlight   int
	Processed  int64
	Dropped    int64
	Capacity   int
}

type BaseService struct {
	stopCh    chan struct{}
	wg        sync.WaitGroup
	mu        sync.RWMutex
	running   bool
	startedAt time.Time
}

func NewBaseService() BaseService {
	return BaseService{
		stopCh: make(chan struct{}),
	}
}

func (b *BaseService) Start() error {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.running {
		return ErrAlreadyStarted
	}
	b.running = true
	b.startedAt = time.Now()
	b.stopCh = make(chan struct{})
	return nil
}

func (b *BaseService) Stop() error {
	b.mu.Lock()
	if !b.running {
		b.mu.Unlock()
		return nil
	}
	b.running = false
	close(b.stopCh)
	b.mu.Unlock()

	b.wg.Wait()
	return nil
}

func (b *BaseService) IsRunning() bool {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return b.running
}

func (b *BaseService) StopChan() <-chan struct{} {
	return b.stopCh
}

func (b *BaseService) AddWorker(count int) {
	b.wg.Add(count)
}

func (b *BaseService) WorkerDone() {
	b.wg.Done()
}

func (b *BaseService) Uptime() time.Duration {
	b.mu.RLock()
	defer b.mu.RUnlock()
	if !b.running {
		return 0
	}
	return time.Since(b.startedAt)
}

func NewEntity(entityType string) *models.Entity {
	return &models.Entity{
		ID:        uuid.New().String(),
		Type:      entityType,
		Status:    "active",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
}

func NewID() string {
	return uuid.New().String()
}

func NowPtr() *time.Time {
	now := time.Now()
	return &now
}

func DurationPtr(d time.Duration) *time.Duration {
	return &d
}

func StringPtr(s string) *string {
	return &s
}
