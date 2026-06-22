package utils

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"reflect"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
)

func GenerateUUID() string {
	return uuid.New().String()
}

func HashUserID(userID string) string {
	h := sha256.New()
	h.Write([]byte(userID))
	return hex.EncodeToString(h.Sum(nil))
}

func PercentageHash(userID string, salt string) int {
	h := sha256.New()
	h.Write([]byte(userID + "|" + salt))
	hashBytes := h.Sum(nil)

	hashInt := new(big.Int).SetBytes(hashBytes[:8])
	mod := new(big.Int).Mod(hashInt, big.NewInt(100))
	return int(mod.Int64())
}

func IsSameUserGroup(userID string, salt string, percentage int) bool {
	if percentage <= 0 {
		return false
	}
	if percentage >= 100 {
		return true
	}
	hash := PercentageHash(userID, salt)
	return hash < percentage
}

func ContainsString(slice []string, item string) bool {
	for _, s := range slice {
		if s == item {
			return true
		}
	}
	return false
}

func ContainsInt(slice []int, item int) bool {
	for _, i := range slice {
		if i == item {
			return true
		}
	}
	return false
}

func StringSliceToInterface(slice []string) []interface{} {
	result := make([]interface{}, len(slice))
	for i, v := range slice {
		result[i] = v
	}
	return result
}

func ToJSON(v interface{}) string {
	b, err := json.Marshal(v)
	if err != nil {
		return fmt.Sprintf("marshal error: %v", err)
	}
	return string(b)
}

func ToPrettyJSON(v interface{}) string {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return fmt.Sprintf("marshal error: %v", err)
	}
	return string(b)
}

func FromJSON(data string, v interface{}) error {
	return json.Unmarshal([]byte(data), v)
}

func PtrString(s string) *string {
	return &s
}

func PtrInt(i int) *int {
	return &i
}

func PtrBool(b bool) *bool {
	return &b
}

func PtrTime(t time.Time) *time.Time {
	return &t
}

func SafeString(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

func SafeInt(i *int) int {
	if i == nil {
		return 0
	}
	return *i
}

func SafeBool(b *bool) bool {
	if b == nil {
		return false
	}
	return *b
}

func SafeTime(t *time.Time) time.Time {
	if t == nil {
		return time.Time{}
	}
	return *t
}

func FormatTime(t time.Time) string {
	return t.Format("2006-01-02 15:04:05")
}

func ParseTime(s string) (time.Time, error) {
	return time.ParseInLocation("2006-01-02 15:04:05", s, time.Local)
}

func FormatDate(t time.Time) string {
	return t.Format("2006-01-02")
}

func ParseDate(s string) (time.Time, error) {
	return time.ParseInLocation("2006-01-02", s, time.Local)
}

func Now() time.Time {
	return time.Now()
}

func NowString() string {
	return FormatTime(Now())
}

func Today() string {
	return FormatDate(Now())
}

func StartOfDay(t time.Time) time.Time {
	return time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, t.Location())
}

func EndOfDay(t time.Time) time.Time {
	return time.Date(t.Year(), t.Month(), t.Day(), 23, 59, 59, 999999999, t.Location())
}

func DaysAgo(days int) time.Time {
	return Now().AddDate(0, 0, -days)
}

func DaysLater(days int) time.Time {
	return Now().AddDate(0, 0, days)
}

func MinutesAgo(minutes int) time.Time {
	return Now().Add(-time.Duration(minutes) * time.Minute)
}

func ToString(v interface{}) string {
	if v == nil {
		return ""
	}
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
	case time.Time:
		return FormatTime(val)
	case []byte:
		return string(val)
	default:
		return fmt.Sprintf("%v", val)
	}
}

func ToInt(v interface{}) int {
	if v == nil {
		return 0
	}
	switch val := v.(type) {
	case int:
		return val
	case int64:
		return int(val)
	case float64:
		return int(val)
	case string:
		i, _ := strconv.Atoi(val)
		return i
	default:
		return 0
	}
}

func ToInt64(v interface{}) int64 {
	if v == nil {
		return 0
	}
	switch val := v.(type) {
	case int:
		return int64(val)
	case int64:
		return val
	case float64:
		return int64(val)
	case string:
		i, _ := strconv.ParseInt(val, 10, 64)
		return i
	default:
		return 0
	}
}

func ToFloat64(v interface{}) float64 {
	if v == nil {
		return 0
	}
	switch val := v.(type) {
	case float64:
		return val
	case int:
		return float64(val)
	case int64:
		return float64(val)
	case string:
		f, _ := strconv.ParseFloat(val, 64)
		return f
	default:
		return 0
	}
}

func ToBool(v interface{}) bool {
	if v == nil {
		return false
	}
	switch val := v.(type) {
	case bool:
		return val
	case string:
		b, _ := strconv.ParseBool(strings.ToLower(val))
		return b
	case int:
		return val != 0
	default:
		return false
	}
}

func IsEmpty(v interface{}) bool {
	if v == nil {
		return true
	}
	switch val := v.(type) {
	case string:
		return val == ""
	case []string:
		return len(val) == 0
	case []int:
		return len(val) == 0
	case map[string]interface{}:
		return len(val) == 0
	default:
		return reflect.ValueOf(v).IsZero()
	}
}

func IsNotEmpty(v interface{}) bool {
	return !IsEmpty(v)
}

func SplitString(s string, sep string) []string {
	if s == "" {
		return []string{}
	}
	parts := strings.Split(s, sep)
	result := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			result = append(result, p)
		}
	}
	return result
}

func JoinStrings(slice []string, sep string) string {
	return strings.Join(slice, sep)
}

func MaskString(s string, start int, end int) string {
	if len(s) <= start+end {
		return s
	}
	mask := strings.Repeat("*", len(s)-start-end)
	return s[:start] + mask + s[len(s)-end:]
}

func MaskEmail(email string) string {
	parts := strings.Split(email, "@")
	if len(parts) != 2 {
		return email
	}
	username := parts[0]
	domain := parts[1]
	if len(username) <= 2 {
		return username + "@" + domain
	}
	return username[:1] + strings.Repeat("*", len(username)-2) + username[len(username)-1:] + "@" + domain
}

func MaskPhone(phone string) string {
	if len(phone) != 11 {
		return phone
	}
	return phone[:3] + "****" + phone[7:]
}

func RandomString(length int) string {
	const chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, length)
	for i := range b {
		b[i] = chars[time.Now().UnixNano()%int64(len(chars))]
	}
	return string(b)
}

func RandomNumber(length int) string {
	const chars = "0123456789"
	b := make([]byte, length)
	for i := range b {
		b[i] = chars[time.Now().UnixNano()%int64(len(chars))]
	}
	return string(b)
}

func Retry(fn func() error, attempts int, delay time.Duration) error {
	var err error
	for i := 0; i < attempts; i++ {
		if err = fn(); err == nil {
			return nil
		}
		time.Sleep(delay * time.Duration(i+1))
	}
	return err
}

func DoWithTimeout(fn func() error, timeout time.Duration) error {
	done := make(chan error, 1)
	go func() {
		done <- fn()
	}()
	select {
	case err := <-done:
		return err
	case <-time.After(timeout):
		return fmt.Errorf("operation timeout after %v", timeout)
	}
}

type Map = map[string]interface{}

func NewMap() Map {
	return make(Map)
}

func (m Map) Get(key string) interface{} {
	return m[key]
}

func (m Map) Set(key string, value interface{}) Map {
	m[key] = value
	return m
}

func (m Map) GetString(key string) string {
	return ToString(m[key])
}

func (m Map) GetInt(key string) int {
	return ToInt(m[key])
}

func (m Map) GetBool(key string) bool {
	return ToBool(m[key])
}

func (m Map) Has(key string) bool {
	_, ok := m[key]
	return ok
}

func (m Map) Delete(key string) Map {
	delete(m, key)
	return m
}

func (m Map) Merge(other Map) Map {
	for k, v := range other {
		m[k] = v
	}
	return m
}

func (m Map) Copy() Map {
	result := make(Map)
	for k, v := range m {
		result[k] = v
	}
	return result
}
