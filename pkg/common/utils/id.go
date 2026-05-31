package utils

import (
	"github.com/google/uuid"
	"strings"
)

func GenerateID(prefix string) string {
	id := uuid.New().String()
	if prefix != "" {
		return prefix + "_" + strings.Replace(id, "-", "", -1)[:12]
	}
	return strings.Replace(id, "-", "", -1)
}

func GenerateTraceID() string {
	return "trace_" + strings.Replace(uuid.New().String(), "-", "", -1)
}

func GenerateBatchID() string {
	return "batch_" + strings.Replace(uuid.New().String(), "-", "", -1)[:8]
}
