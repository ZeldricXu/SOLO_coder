package utils

import (
	"fmt"
	"github.com/google/uuid"
	"strings"
	"time"
)

func GenerateID(prefix string) string {
	id := uuid.New().String()
	id = strings.ReplaceAll(id, "-", "")
	return fmt.Sprintf("%s_%s", prefix, id[:12])
}

func GenerateShortID(prefix string) string {
	id := uuid.New().String()
	id = strings.ReplaceAll(id, "-", "")
	return fmt.Sprintf("%s_%s", prefix, id[:8])
}

func NowUTC() time.Time {
	return time.Now().UTC()
}

func FormatTime(t time.Time) string {
	return t.Format(time.RFC3339)
}

func ParseTime(s string) (time.Time, error) {
	return time.Parse(time.RFC3339, s)
}

func ContainsString(slice []string, item string) bool {
	for _, s := range slice {
		if s == item {
			return true
		}
	}
	return false
}

func MapKeys[K comparable, V any](m map[K]V) []K {
	keys := make([]K, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	return keys
}

func MergeMaps[K comparable, V any](maps ...map[K]V) map[K]V {
	result := make(map[K]V)
	for _, m := range maps {
		for k, v := range m {
			result[k] = v
		}
	}
	return result
}
