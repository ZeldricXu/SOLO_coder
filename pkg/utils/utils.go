package utils

import (
	"crypto/rand"
	"encoding/hex"
	"math/big"
	"time"

	"github.com/google/uuid"
)

func NewID(prefix string) string {
	return prefix + "_" + uuid.New().String()[:8]
}

func NewTraceID() string {
	b := make([]byte, 16)
	_, err := rand.Read(b)
	if err != nil {
		return "trace_" + uuid.New().String()
	}
	return hex.EncodeToString(b)
}

func RandomInt(min, max int) int {
	if min >= max {
		return min
	}
	n, err := rand.Int(rand.Reader, big.NewInt(int64(max-min)))
	if err != nil {
		return min
	}
	return int(n.Int64()) + min
}

func GetBackoffDuration(base time.Duration, retryCount int) time.Duration {
	duration := base
	for i := 1; i < retryCount; i++ {
		duration *= 2
	}
	jitter := time.Duration(RandomInt(0, 1000)) * time.Millisecond
	return duration + jitter
}

func GenerateDedupKey(notificationType, channel, recipient, content string) string {
	hash := uuid.NewSHA1(uuid.Nil, []byte(notificationType+channel+recipient+content))
	return hash.String()
}

func StringPtr(s string) *string {
	return &s
}

func TimePtr(t time.Time) *time.Time {
	return &t
}

func IntPtr(i int) *int {
	return &i
}
