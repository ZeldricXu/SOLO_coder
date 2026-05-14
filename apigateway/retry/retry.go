package retry

import (
	"fmt"
	"net/http"
	"time"
)

type RetryConfig struct {
	MaxRetries    int
	InitialDelay  time.Duration
	MaxDelay      time.Duration
	BackoffFactor float64
	RetryableStatusCodes []int
	RetryableMethods     []string
}

type Retryer struct {
	defaultConfig RetryConfig
}

func NewRetryer() *Retryer {
	return &Retryer{
		defaultConfig: RetryConfig{
			MaxRetries:    2,
			InitialDelay:  100 * time.Millisecond,
			MaxDelay:      2 * time.Second,
			BackoffFactor: 2.0,
			RetryableStatusCodes: []int{
				http.StatusInternalServerError,
				http.StatusBadGateway,
				http.StatusServiceUnavailable,
				http.StatusGatewayTimeout,
			},
			RetryableMethods: []string{
				http.MethodGet,
				http.MethodHead,
				http.MethodOptions,
			},
		},
	}
}

func (r *Retryer) WithConfig(config RetryConfig) *Retryer {
	return &Retryer{
		defaultConfig: config,
	}
}

func (r *Retryer) Do(fn func() (*http.Response, error)) (*http.Response, int, error) {
	return r.DoWithConfig(r.defaultConfig, fn)
}

func (r *Retryer) DoWithConfig(config RetryConfig, fn func() (*http.Response, error)) (*http.Response, int, error) {
	if config.MaxRetries < 0 {
		config.MaxRetries = r.defaultConfig.MaxRetries
	}
	if config.InitialDelay <= 0 {
		config.InitialDelay = r.defaultConfig.InitialDelay
	}
	if config.MaxDelay <= 0 {
		config.MaxDelay = r.defaultConfig.MaxDelay
	}
	if config.BackoffFactor <= 0 {
		config.BackoffFactor = r.defaultConfig.BackoffFactor
	}

	var lastErr error
	var resp *http.Response
	attempts := 0

	for attempts <= config.MaxRetries {
		resp, lastErr = fn()
		attempts++

		if lastErr != nil {
			if attempts > config.MaxRetries {
				break
			}
			delay := r.calculateDelay(config, attempts)
			time.Sleep(delay)
			continue
		}

		if resp != nil && !r.shouldRetry(config, resp) {
			return resp, attempts, nil
		}

		if attempts > config.MaxRetries {
			break
		}

		delay := r.calculateDelay(config, attempts)
		time.Sleep(delay)
	}

	return resp, attempts, lastErr
}

func (r *Retryer) shouldRetry(config RetryConfig, resp *http.Response) bool {
	if resp == nil {
		return false
	}

	retryableCodes := config.RetryableStatusCodes
	if len(retryableCodes) == 0 {
		retryableCodes = r.defaultConfig.RetryableStatusCodes
	}

	for _, code := range retryableCodes {
		if resp.StatusCode == code {
			return true
		}
	}

	return false
}

func (r *Retryer) calculateDelay(config RetryConfig, attempt int) time.Duration {
	if attempt <= 1 {
		return config.InitialDelay
	}

	delay := float64(config.InitialDelay)
	for i := 1; i < attempt; i++ {
		delay *= config.BackoffFactor
		if delay >= float64(config.MaxDelay) {
			return config.MaxDelay
		}
	}

	return time.Duration(delay)
}

func (r *Retryer) IsRetryableError(err error) bool {
	return err != nil
}

func (r *Retryer) IsRetryableResponse(resp *http.Response) bool {
	if resp == nil {
		return false
	}

	for _, code := range r.defaultConfig.RetryableStatusCodes {
		if resp.StatusCode == code {
			return true
		}
	}

	return false
}

func (r *Retryer) IsMethodRetryable(method string, config RetryConfig) bool {
	retryableMethods := config.RetryableMethods
	if len(retryableMethods) == 0 {
		retryableMethods = r.defaultConfig.RetryableMethods
	}

	for _, m := range retryableMethods {
		if m == method {
			return true
		}
	}

	return false
}

func (r *Retryer) ExecuteWithRetry(maxRetries int, operation func() error) error {
	var lastErr error

	for attempt := 0; attempt <= maxRetries; attempt++ {
		lastErr = operation()
		if lastErr == nil {
			return nil
		}

		if attempt < maxRetries {
			delay := r.calculateDelay(RetryConfig{
				MaxRetries:    maxRetries,
				InitialDelay:  100 * time.Millisecond,
				MaxDelay:      2 * time.Second,
				BackoffFactor: 2.0,
			}, attempt+1)
			time.Sleep(delay)
		}
	}

	return fmt.Errorf("operation failed after %d attempts: %w", maxRetries+1, lastErr)
}
