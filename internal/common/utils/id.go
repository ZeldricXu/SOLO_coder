package utils

import (
	"crypto/rand"
	"encoding/hex"
	"time"
)

func GenerateID(prefix string) string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return prefix + "_" + hex.EncodeToString(b)[:12]
}

func NowPtr() *time.Time {
	t := time.Now().UTC()
	return &t
}

func TimePtr(t time.Time) *time.Time {
	return &t
}
