package utils

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"time"

	"github.com/google/uuid"
)

func GenerateID(prefix string) string {
	id := uuid.New().String()
	if prefix != "" {
		return fmt.Sprintf("%s_%s", prefix, id[:8])
	}
	return id
}

func GenerateRandomString(length int) string {
	bytes := make([]byte, length)
	if _, err := rand.Read(bytes); err != nil {
		return fmt.Sprintf("%d", time.Now().UnixNano())
	}
	return hex.EncodeToString(bytes)[:length]
}

func Pointer[T any](v T) *T {
	return &v
}

func ContainsString(slice []string, item string) bool {
	for _, s := range slice {
		if s == item {
			return true
		}
	}
	return false
}

func TimeNowPtr() *time.Time {
	now := time.Now()
	return &now
}
