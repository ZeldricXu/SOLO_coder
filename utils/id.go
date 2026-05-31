package utils

import (
	"github.com/google/uuid"
	"strings"
)

func GenerateID(prefix string) string {
	return prefix + "_" + strings.ReplaceAll(uuid.New().String(), "-", "")[:20]
}
