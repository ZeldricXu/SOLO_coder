package utils

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"math"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
)

func GenerateID(prefix string) string {
	return fmt.Sprintf("%s_%s", prefix, ShortUUID())
}

func UUID() string {
	return uuid.New().String()
}

func ShortUUID() string {
	u := uuid.New()
	return strings.Replace(u.String(), "-", "", -1)[:16]
}

func RandomString(n int) string {
	b := make([]byte, n/2)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func Now() time.Time {
	return time.Now().UTC()
}

func NowPtr() *time.Time {
	t := Now()
	return &t
}

func ParseTime(s string) (time.Time, error) {
	return time.Parse(time.RFC3339, s)
}

func FormatTime(t time.Time) string {
	return t.Format(time.RFC3339)
}

func PtrBool(b bool) *bool {
	return &b
}

func PtrString(s string) *string {
	return &s
}

func PtrInt(i int) *int {
	return &i
}

func PtrInt64(i int64) *int64 {
	return &i
}

func PtrFloat64(f float64) *float64 {
	return &f
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

func ToString(v interface{}) string {
	switch val := v.(type) {
	case string:
		return val
	case int:
		return strconv.Itoa(val)
	case int64:
		return strconv.FormatInt(val, 10)
	case float64:
		return strconv.FormatFloat(val, 'f', -1, 64)
	case bool:
		return strconv.FormatBool(val)
	case []byte:
		return string(val)
	default:
		return fmt.Sprintf("%v", val)
	}
}

func ToInt64(v interface{}) (int64, error) {
	switch val := v.(type) {
	case int:
		return int64(val), nil
	case int64:
		return val, nil
	case float64:
		return int64(val), nil
	case string:
		return strconv.ParseInt(val, 10, 64)
	default:
		return 0, fmt.Errorf("cannot convert %T to int64", v)
	}
}

func ToFloat64(v interface{}) (float64, error) {
	switch val := v.(type) {
	case float64:
		return val, nil
	case int:
		return float64(val), nil
	case int64:
		return float64(val), nil
	case string:
		return strconv.ParseFloat(val, 64)
	default:
		return 0, fmt.Errorf("cannot convert %T to float64", v)
	}
}

func RoundFloat(f float64, precision int) float64 {
	mult := math.Pow10(precision)
	return math.Round(f*mult) / mult
}

func TruncateString(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	return s[:maxLen] + "..."
}

func HashString(s string) string {
	hash := sha256.Sum256([]byte(s))
	return hex.EncodeToString(hash[:])[:16]
}

func SafeGetMap(m map[string]interface{}, key string) interface{} {
	if m == nil {
		return nil
	}
	return m[key]
}

func SafeGetMapString(m map[string]interface{}, key string) string {
	v := SafeGetMap(m, key)
	if v == nil {
		return ""
	}
	return ToString(v)
}

func SafeGetMapInt(m map[string]interface{}, key string) int {
	v := SafeGetMap(m, key)
	if v == nil {
		return 0
	}
	i64, _ := ToInt64(v)
	return int(i64)
}

func SafeGetMapFloat(m map[string]interface{}, key string) float64 {
	v := SafeGetMap(m, key)
	if v == nil {
		return 0
	}
	f, _ := ToFloat64(v)
	return f
}

type RetryConfig struct {
	MaxAttempts int
	Delay       time.Duration
	MaxDelay    time.Duration
}

func Retry(fn func() error, config RetryConfig) error {
	var err error
	delay := config.Delay

	for i := 0; i < config.MaxAttempts; i++ {
		err = fn()
		if err == nil {
			return nil
		}

		if i < config.MaxAttempts-1 {
			time.Sleep(delay)
			delay *= 2
			if delay > config.MaxDelay {
				delay = config.MaxDelay
			}
		}
	}

	return err
}
