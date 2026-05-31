package gateway

import "errors"

var (
	ErrStrategyNotFound     = errors.New("strategy not found")
	ErrStrategyNotEnabled   = errors.New("strategy not enabled")
	ErrAuthFailed          = errors.New("authentication failed")
	ErrRateLimitExceeded   = errors.New("rate limit exceeded")
	ErrInvalidCredentials  = errors.New("invalid credentials")
)
