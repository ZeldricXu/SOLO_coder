#!/usr/bin/env python3
import os

BASE_DIR = "/Users/huangzitong/SoloCoder/session193"

def wf(path, content):
    full = os.path.join(BASE_DIR, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, 'w') as f:
        f.write(content)
    print(f"OK: {path}")

wf("internal/core/adapters/memory_repository.go", """package adapters

import (
	"context"
	"sync"

	"github.com/apishield/apishield/internal/core/models"
	"github.com/apishield/apishield/internal/core/ports"
	"github.com/google/uuid"
)

type MemoryRepositoryAdapter struct {
	mu         sync.RWMutex
	entities   map[uuid.UUID]*models.Entity
	configs    map[uuid.UUID]*models.Config
	snapshots  map[uuid.UUID]*models.Snapshot
}

func NewMemoryRepositoryAdapter() ports.ResourceManager {
	return &MemoryRepositoryAdapter{
		entities:  make(map[uuid.UUID]*models.Entity),
		configs:   make(map[uuid.UUID]*models.Config),
		snapshots: make(map[uuid.UUID]*models.Snapshot),
	}
}

func (r *MemoryRepositoryAdapter) CreateEntity(ctx context.Context, entity *models.Entity) (*models.Entity, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if entity.ID == uuid.Nil {
		entity.ID = uuid.New()
	}
	r.entities[entity.ID] = entity
	return entity, nil
}

func (r *MemoryRepositoryAdapter) GetEntity(ctx context.Context, id uuid.UUID) (*models.Entity, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	entity, ok := r.entities[id]
	if !ok {
		return nil, nil
	}
	return entity, nil
}

func (r *MemoryRepositoryAdapter) UpdateEntity(ctx context.Context, entity *models.Entity) (*models.Entity, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	r.entities[entity.ID] = entity
	return entity, nil
}

func (r *MemoryRepositoryAdapter) DeleteEntity(ctx context.Context, id uuid.UUID) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	delete(r.entities, id)
	return nil
}

func (r *MemoryRepositoryAdapter) ListEntities(ctx context.Context, filters map[string]string) ([]*models.Entity, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	result := make([]*models.Entity, 0, len(r.entities))
	for _, e := range r.entities {
		matched := true
		for k, v := range filters {
			switch k {
			case "type":
				if e.Type != v {
					matched = false
				}
			case "status":
				if string(e.Status) != v {
					matched = false
				}
			}
		}
		if matched {
			result = append(result, e)
		}
	}
	return result, nil
}

func (r *MemoryRepositoryAdapter) CreateConfig(ctx context.Context, config *models.Config) (*models.Config, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if config.ID == uuid.Nil {
		config.ID = uuid.New()
	}
	r.configs[config.ID] = config
	return config, nil
}

func (r *MemoryRepositoryAdapter) GetConfig(ctx context.Context, id uuid.UUID) (*models.Config, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	config, ok := r.configs[id]
	if !ok {
		return nil, nil
	}
	return config, nil
}

func (r *MemoryRepositoryAdapter) UpdateConfig(ctx context.Context, config *models.Config) (*models.Config, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	r.configs[config.ID] = config
	return config, nil
}

func (r *MemoryRepositoryAdapter) DeleteConfig(ctx context.Context, id uuid.UUID) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	delete(r.configs, id)
	return nil
}

func (r *MemoryRepositoryAdapter) ListConfigs(ctx context.Context, entityID uuid.UUID) ([]*models.Config, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	result := make([]*models.Config, 0)
	for _, c := range r.configs {
		if c.EntityID == entityID {
			result = append(result, c)
		}
	}
	return result, nil
}

func (r *MemoryRepositoryAdapter) CreateSnapshot(ctx context.Context, snapshot *models.Snapshot) (*models.Snapshot, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if snapshot.ID == uuid.Nil {
		snapshot.ID = uuid.New()
	}
	r.snapshots[snapshot.ID] = snapshot
	return snapshot, nil
}

func (r *MemoryRepositoryAdapter) GetSnapshot(ctx context.Context, id uuid.UUID) (*models.Snapshot, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	snapshot, ok := r.snapshots[id]
	if !ok {
		return nil, nil
	}
	return snapshot, nil
}

func (r *MemoryRepositoryAdapter) ListSnapshots(ctx context.Context, entityID uuid.UUID) ([]*models.Snapshot, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	result := make([]*models.Snapshot, 0)
	for _, s := range r.snapshots {
		if s.EntityID == entityID {
			result = append(result, s)
		}
	}
	return result, nil
}

func (r *MemoryRepositoryAdapter) RestoreSnapshot(ctx context.Context, snapshotID uuid.UUID) error {
	r.mu.RLock()
	defer r.mu.RUnlock()

	_, ok := r.snapshots[snapshotID]
	if !ok {
		return nil
	}
	return nil
}
""")

wf("pkg/common/common.go", """package common

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"time"

	"github.com/google/uuid"
)

type contextKey string

const (
	RequestIDKey    contextKey = "request_id"
	UserIDKey       contextKey = "user_id"
	TraceIDKey      contextKey = "trace_id"
)

func GenerateID() string {
	return uuid.New().String()
}

func GenerateUUID() uuid.UUID {
	return uuid.New()
}

func GenerateRandomString(length int) string {
	bytes := make([]byte, length)
	if _, err := rand.Read(bytes); err != nil {
		return ""
	}
	return hex.EncodeToString(bytes)
}

func WithRequestID(ctx context.Context, requestID string) context.Context {
	return context.WithValue(ctx, RequestIDKey, requestID)
}

func GetRequestID(ctx context.Context) string {
	if id, ok := ctx.Value(RequestIDKey).(string); ok {
		return id
	}
	return ""
}

func WithUserID(ctx context.Context, userID string) context.Context {
	return context.WithValue(ctx, UserIDKey, userID)
}

func GetUserID(ctx context.Context) string {
	if id, ok := ctx.Value(UserIDKey).(string); ok {
		return id
	}
	return ""
}

func WithTraceID(ctx context.Context, traceID string) context.Context {
	return context.WithValue(ctx, TraceIDKey, traceID)
}

func GetTraceID(ctx context.Context) string {
	if id, ok := ctx.Value(TraceIDKey).(string); ok {
		return id
	}
	return ""
}

type Response struct {
	Success   bool        `json:"success"`
	Data      any         `json:"data,omitempty"`
	Error     string      `json:"error,omitempty"`
	Message   string      `json:"message,omitempty"`
	Timestamp int64       `json:"timestamp"`
	RequestID string      `json:"request_id,omitempty"`
}

func NewSuccessResponse(data any) Response {
	return Response{
		Success:   true,
		Data:      data,
		Timestamp: time.Now().Unix(),
	}
}

func NewSuccessResponseWithMessage(data any, message string) Response {
	return Response{
		Success:   true,
		Data:      data,
		Message:   message,
		Timestamp: time.Now().Unix(),
	}
}

func NewErrorResponse(errMsg string) Response {
	return Response{
		Success:   false,
		Error:     errMsg,
		Timestamp: time.Now().Unix(),
	}
}

func (r Response) JSON(w http.ResponseWriter, statusCode int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	_ = json.NewEncoder(w).Encode(r)
}

func IsContextDone(ctx context.Context) bool {
	select {
	case <-ctx.Done():
		return true
	default:
		return false
	}
}
""")

wf("pkg/errors/errors.go", """package errors

import (
	"fmt"
	"net/http"
)

type ErrorCode string

const (
	ErrCodeValidation ErrorCode = "VALIDATION_ERROR"
	ErrCodeNotFound   ErrorCode = "NOT_FOUND"
	ErrCodeConflict   ErrorCode = "CONFLICT"
	ErrCodeInternal   ErrorCode = "INTERNAL_ERROR"
	ErrCodeUnauthorized ErrorCode = "UNAUTHORIZED"
	ErrCodeForbidden  ErrorCode = "FORBIDDEN"
)

type AppError interface {
	error
	Code() ErrorCode
	Message() string
	HTTPStatus() int
	Details() map[string]string
	WithDetail(key, value string) AppError
}

type baseError struct {
	code    ErrorCode
	message string
	status  int
	details map[string]string
}

func (e *baseError) Error() string {
	if len(e.details) > 0 {
		return fmt.Sprintf("%s: %s - %v", e.code, e.message, e.details)
	}
	return fmt.Sprintf("%s: %s", e.code, e.message)
}

func (e *baseError) Code() ErrorCode {
	return e.code
}

func (e *baseError) Message() string {
	return e.message
}

func (e *baseError) HTTPStatus() int {
	return e.status
}

func (e *baseError) Details() map[string]string {
	return e.details
}

func (e *baseError) WithDetail(key, value string) AppError {
	if e.details == nil {
		e.details = make(map[string]string)
	}
	e.details[key] = value
	return e
}

type ValidationError struct {
	*baseError
}

func NewValidationError(message string) *ValidationError {
	return &ValidationError{
		baseError: &baseError{
			code:    ErrCodeValidation,
			message: message,
			status:  http.StatusBadRequest,
			details: make(map[string]string),
		},
	}
}

func NewValidationErrorf(format string, args ...any) *ValidationError {
	return NewValidationError(fmt.Sprintf(format, args...))
}

func (e *ValidationError) WithFieldError(field, message string) *ValidationError {
	e.WithDetail(field, message)
	return e
}

type NotFoundError struct {
	*baseError
}

func NewNotFoundError(resource string, id string) *NotFoundError {
	return &NotFoundError{
		baseError: &baseError{
			code:    ErrCodeNotFound,
			message: fmt.Sprintf("%s with id '%s' not found", resource, id),
			status:  http.StatusNotFound,
			details: make(map[string]string),
		},
	}
}

func NewNotFoundErrorf(format string, args ...any) *NotFoundError {
	return &NotFoundError{
		baseError: &baseError{
			code:    ErrCodeNotFound,
			message: fmt.Sprintf(format, args...),
			status:  http.StatusNotFound,
			details: make(map[string]string),
		},
	}
}

type ConflictError struct {
	*baseError
}

func NewConflictError(message string) *ConflictError {
	return &ConflictError{
		baseError: &baseError{
			code:    ErrCodeConflict,
			message: message,
			status:  http.StatusConflict,
			details: make(map[string]string),
		},
	}
}

func NewConflictErrorf(format string, args ...any) *ConflictError {
	return NewConflictError(fmt.Sprintf(format, args...))
}

func IsValidationError(err error) bool {
	_, ok := err.(*ValidationError)
	return ok
}

func IsNotFoundError(err error) bool {
	_, ok := err.(*NotFoundError)
	return ok
}

func IsConflictError(err error) bool {
	_, ok := err.(*ConflictError)
	return ok
}

func AsAppError(err error) (AppError, bool) {
	appErr, ok := err.(AppError)
	return appErr, ok
}
""")

print("\\nAll files generated successfully!")
