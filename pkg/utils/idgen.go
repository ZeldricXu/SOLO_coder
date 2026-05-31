package utils

import (
	"fmt"
	"math/rand"
	"sync"
	"time"

	"github.com/google/uuid"
)

var (
	rng  *rand.Rand
	once sync.Once
	mu   sync.Mutex
)

func initRNG() {
	once.Do(func() {
		rng = rand.New(rand.NewSource(time.Now().UnixNano()))
	})
}

func NewUUID() string {
	return uuid.New().String()
}

func NewID(prefix string) string {
	return fmt.Sprintf("%s_%s", prefix, NewUUID()[:13])
}

func NewTraceID() string {
	return fmt.Sprintf("trace_%s", NewUUID()[:16])
}

func RandomInt(min, max int) int {
	initRNG()
	mu.Lock()
	defer mu.Unlock()
	return rng.Intn(max-min+1) + min
}

func RandomString(length int) string {
	initRNG()
	const chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	mu.Lock()
	defer mu.Unlock()
	result := make([]byte, length)
	for i := range result {
		result[i] = chars[rng.Intn(len(chars))]
	}
	return string(result)
}

func ContainsString(slice []string, item string) bool {
	for _, s := range slice {
		if s == item {
			return true
		}
	}
	return false
}

func MapKeys(m map[string]interface{}) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	return keys
}

func Min(a, b float64) float64 {
	if a < b {
		return a
	}
	return b
}

func Max(a, b float64) float64 {
	if a > b {
		return a
	}
	return b
}

func Average(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sum := 0.0
	for _, v := range values {
		sum += v
	}
	return sum / float64(len(values))
}

func StdDev(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	avg := Average(values)
	sum := 0.0
	for _, v := range values {
		diff := v - avg
		sum += diff * diff
	}
	return sqrt(sum / float64(len(values)))
}

func sqrt(x float64) float64 {
	if x <= 0 {
		return 0
	}
	z := x / 2.0
	for i := 0; i < 100; i++ {
		prev := z
		z = (z + x/z) / 2.0
		if prev == z {
			break
		}
	}
	return z
}
