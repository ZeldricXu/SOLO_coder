package util

import (
	"context"
	"errors"
	"fmt"
	"math/rand"
	"time"
)

var (
	ErrMaxRetriesExceeded = errors.New("max retries exceeded")
	ErrContextCanceled    = errors.New("context canceled")
)

type RetryConfig struct {
	MaxRetries    int
	BaseDelay     time.Duration
	MaxDelay      time.Duration
	JitterFactor  float64
	RetryableFunc func(error) bool
}

func DefaultRetryConfig() RetryConfig {
	return RetryConfig{
		MaxRetries:    3,
		BaseDelay:     100 * time.Millisecond,
		MaxDelay:      10 * time.Second,
		JitterFactor:  0.1,
		RetryableFunc: func(err error) bool { return true },
	}
}

type RetryOption func(*RetryConfig)

func WithMaxRetries(n int) RetryOption {
	return func(c *RetryConfig) {
		c.MaxRetries = n
	}
}

func WithBaseDelay(d time.Duration) RetryOption {
	return func(c *RetryConfig) {
		c.BaseDelay = d
	}
}

func WithMaxDelay(d time.Duration) RetryOption {
	return func(c *RetryConfig) {
		c.MaxDelay = d
	}
}

func WithJitterFactor(f float64) RetryOption {
	return func(c *RetryConfig) {
		c.JitterFactor = f
	}
}

func WithRetryableFunc(fn func(error) bool) RetryOption {
	return func(c *RetryConfig) {
		c.RetryableFunc = fn
	}
}

func Do(ctx context.Context, fn func() error, opts ...RetryOption) error {
	cfg := DefaultRetryConfig()
	for _, opt := range opts {
		opt(&cfg)
	}

	var lastErr error
	for attempt := 0; attempt <= cfg.MaxRetries; attempt++ {
		select {
		case <-ctx.Done():
			return fmt.Errorf("%w: %v", ErrContextCanceled, ctx.Err())
		default:
		}

		if err := fn(); err != nil {
			lastErr = err
			if !cfg.RetryableFunc(err) {
				return err
			}
			if attempt == cfg.MaxRetries {
				break
			}
			delay := calculateDelay(attempt, cfg)
			select {
			case <-ctx.Done():
				return fmt.Errorf("%w: %v", ErrContextCanceled, ctx.Err())
			case <-time.After(delay):
			}
			continue
		}
		return nil
	}

	return fmt.Errorf("%w: %v", ErrMaxRetriesExceeded, lastErr)
}

func DoWithResult[T any](ctx context.Context, fn func() (T, error), opts ...RetryOption) (T, error) {
	var result T
	err := Do(ctx, func() error {
		var err error
		result, err = fn()
		return err
	}, opts...)
	return result, err
}

func calculateDelay(attempt int, cfg RetryConfig) time.Duration {
	expDelay := cfg.BaseDelay * time.Duration(1<<uint(attempt))
	if expDelay > cfg.MaxDelay {
		expDelay = cfg.MaxDelay
	}

	jitter := time.Duration(0)
	if cfg.JitterFactor > 0 {
		maxJitter := int64(float64(expDelay) * cfg.JitterFactor)
		if maxJitter > 0 {
			jitter = time.Duration(rand.Int63n(maxJitter))
		}
	}

	return expDelay + jitter
}
