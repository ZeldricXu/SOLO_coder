package common

import (
	"context"
	"math"
	"time"
)

type RetryConfig struct {
	MaxAttempts     int
	InitialInterval time.Duration
	MaxInterval     time.Duration
	Multiplier      float64
	Jitter          float64
}

func DefaultRetryConfig() RetryConfig {
	return RetryConfig{
		MaxAttempts:     3,
		InitialInterval: 1 * time.Second,
		MaxInterval:     30 * time.Second,
		Multiplier:      2.0,
		Jitter:          0.1,
	}
}

type RetryFunc func(ctx context.Context) error

func Retry(ctx context.Context, config RetryConfig, fn RetryFunc) error {
	var lastErr error

	for attempt := 0; attempt < config.MaxAttempts; attempt++ {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}

		err := fn(ctx)
		if err == nil {
			return nil
		}
		lastErr = err

		if attempt == config.MaxAttempts-1 {
			break
		}

		waitDuration := calculateBackoff(config, attempt)

		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(waitDuration):
		}
	}

	return lastErr
}

func calculateBackoff(config RetryConfig, attempt int) time.Duration {
	interval := float64(config.InitialInterval) * math.Pow(config.Multiplier, float64(attempt))

	if interval > float64(config.MaxInterval) {
		interval = float64(config.MaxInterval)
	}

	if config.Jitter > 0 {
		jitterAmount := interval * config.Jitter
		interval += jitterAmount * (2*math.Floor(float64(time.Now().UnixNano()%1000)/1000.0) - 1)
	}

	return time.Duration(interval)
}
