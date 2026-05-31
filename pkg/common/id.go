package common

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"strings"

	"github.com/google/uuid"
)

func GenerateID(prefix string) string {
	id := uuid.New().String()
	return fmt.Sprintf("%s_%s", prefix, strings.ReplaceAll(id, "-", ""))
}

func GenerateRandomHex(length int) string {
	b := make([]byte, length)
	if _, err := rand.Read(b); err != nil {
		return ""
	}
	return hex.EncodeToString(b)
}

func NewUUID() string {
	return uuid.New().String()
}
