package utils

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"time"
)

func GenerateID(prefix string) string {
	b := make([]byte, 8)
	rand.Read(b)
	return fmt.Sprintf("%s_%s", prefix, hex.EncodeToString(b)[:8])
}

func GenerateTraceID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func CurrentTime() time.Time {
	return time.Now().UTC()
}

func RetryWithBackoff(operation func() error, maxRetries int, initialDelay time.Duration) error {
	var err error
	delay := initialDelay

	for i := 0; i < maxRetries; i++ {
		if err = operation(); err == nil {
			return nil
		}

		time.Sleep(delay)
		delay *= 2
	}

	return err
}
