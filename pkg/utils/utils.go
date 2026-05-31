package utils

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/rand"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
)

func GenerateID(prefix string) string {
	return fmt.Sprintf("%s_%s", prefix, uuid.New().String()[:8])
}

func GenerateTraceID() string {
	return uuid.New().String()
}

func HashSHA256(data string) string {
	h := sha256.New()
	h.Write([]byte(data))
	return hex.EncodeToString(h.Sum(nil))
}

func ToJSON(v interface{}) string {
	data, _ := json.Marshal(v)
	return string(data)
}

func FromJSON(data string, v interface{}) error {
	return json.Unmarshal([]byte(data), v)
}

func RandomString(n int) string {
	const letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, n)
	for i := range b {
		b[i] = letters[rand.Intn(len(letters))]
	}
	return string(b)
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

func GetClientIP(r *http.Request) string {
	xForwardedFor := r.Header.Get("X-Forwarded-For")
	if xForwardedFor != "" {
		ips := strings.Split(xForwardedFor, ",")
		if len(ips) > 0 {
			return strings.TrimSpace(ips[0])
		}
	}

	xRealIP := r.Header.Get("X-Real-IP")
	if xRealIP != "" {
		return xRealIP
	}

	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func ContainsString(slice []string, item string) bool {
	for _, s := range slice {
		if s == item {
			return true
		}
	}
	return false
}

func Min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func Max(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func Retry(fn func() error, attempts int, interval time.Duration) error {
	var err error
	for i := 0; i < attempts; i++ {
		if err = fn(); err == nil {
			return nil
		}
		time.Sleep(interval)
	}
	return err
}

func SafeGo(fn func(), recovery func(interface{})) {
	go func() {
		defer func() {
			if r := recover(); r != nil {
				if recovery != nil {
					recovery(r)
				}
			}
		}()
		fn()
	}()
}
