package models

import "errors"

var (
	ErrUserNotFound          = errors.New("user not found")
	ErrUserAlreadyExists     = errors.New("user already exists")
	ErrUserDisabled          = errors.New("user is disabled")
	ErrInvalidPassword       = errors.New("invalid password")
	ErrRoleNotFound          = errors.New("role not found")
	ErrRoleAlreadyExists     = errors.New("role already exists")
	ErrResourceNotFound      = errors.New("resource not found")
	ErrResourceAlreadyExists = errors.New("resource already exists")
	ErrSessionNotFound       = errors.New("session not found")
	ErrSessionExpired        = errors.New("session expired")
	ErrSessionRevoked        = errors.New("session revoked")
	ErrPermissionDenied      = errors.New("permission denied")
	ErrMFAFailed             = errors.New("mfa verification failed")
	ErrMFARequired           = errors.New("mfa verification required")
	ErrInvalidRequest        = errors.New("invalid request")
)
