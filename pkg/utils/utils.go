package utils

import (
	"encoding/json"
	"github.com/google/uuid"
	"time"
)

func GenerateID(prefix string) string {
	return prefix + "_" + uuid.New().String()[:8]
}

func Now() time.Time {
	return time.Now().UTC()
}

func ToJSON(v interface{}) string {
	b, _ := json.Marshal(v)
	return string(b)
}

func FromJSON(data string, v interface{}) error {
	return json.Unmarshal([]byte(data), v)
}

func Contains(slice []string, item string) bool {
	for _, s := range slice {
		if s == item {
			return true
		}
	}
	return false
}
