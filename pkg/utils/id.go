package utils

import (
	"fmt"
	"time"

	"github.com/google/uuid"
)

func GenerateUUID() string {
	return uuid.New().String()
}

func GenerateShortID() string {
	return uuid.New().String()[:8]
}

func NewTraceID() string {
	return fmt.Sprintf("trace-%s-%s",
		time.Now().Format("20060102150405"),
		uuid.New().String()[:8],
	)
}

func NewRequestID() string {
	return fmt.Sprintf("req-%s", uuid.New().String()[:16])
}
