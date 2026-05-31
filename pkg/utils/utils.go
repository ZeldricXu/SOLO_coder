package utils

import (
	"crypto/rand"
	"encoding/hex"
	"time"

	"github.com/google/uuid"
)

func GenerateID(prefix string) string {
	return prefix + "_" + uuid.New().String()[:8]
}

func GenerateRandomString(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func NowPtr() *time.Time {
	now := time.Now().UTC()
	return &now
}

func TimePtr(t time.Time) *time.Time {
	return &t
}

func ContainsString(slice []string, item string) bool {
	for _, s := range slice {
		if s == item {
			return true
		}
	}
	return false
}

func MergeMaps(base, overlay map[string]interface{}) map[string]interface{} {
	result := make(map[string]interface{})
	for k, v := range base {
		result[k] = v
	}
	for k, v := range overlay {
		result[k] = v
	}
	return result
}
