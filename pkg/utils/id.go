package utils

import (
	"github.com/google/uuid"
	"strings"
)

func GenerateID(prefix string) string {
	id := uuid.New().String()
	return prefix + "_" + strings.ReplaceAll(id, "-", "")[:16]
}

func GenerateUUID() string {
	return uuid.New().String()
}
