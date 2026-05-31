package utils

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"edgescheduler/internal/common/models"
)

func GenerateID(prefix string) string {
	return fmt.Sprintf("%s_%s", prefix, uuid.New().String()[:8])
}

func GenerateTraceID() string {
	return uuid.New().String()
}

func HashString(s string) string {
	h := sha256.New()
	h.Write([]byte(s))
	return hex.EncodeToString(h.Sum(nil))
}

func WithTraceID(ctx context.Context, traceID string) context.Context {
	return context.WithValue(ctx, "trace_id", traceID)
}

func GetTraceID(ctx context.Context) string {
	if traceID, ok := ctx.Value("trace_id").(string); ok {
		return traceID
	}
	return GenerateTraceID()
}

func SuccessResponse(c *gin.Context, data interface{}) {
	c.JSON(http.StatusOK, models.APIResponse{
		Code: 200,
		Data: data,
	})
}

func CreatedResponse(c *gin.Context, data interface{}) {
	c.JSON(http.StatusCreated, models.APIResponse{
		Code: 201,
		Data: data,
	})
}

func ErrorResponse(c *gin.Context, code int, message string) {
	c.JSON(code, models.APIResponse{
		Code:    code,
		Message: message,
	})
}

func ValidationErrorResponse(c *gin.Context, details string) {
	c.JSON(http.StatusUnprocessableEntity, models.APIResponse{
		Code:    422,
		Message: details,
	})
}

func RetryWithBackoff(operation func() error, maxRetries int, initialDelay time.Duration) error {
	var err error
	delay := initialDelay

	for i := 0; i < maxRetries; i++ {
		err = operation()
		if err == nil {
			return nil
		}

		if i < maxRetries-1 {
			time.Sleep(delay)
			delay *= 2
		}
	}

	return fmt.Errorf("operation failed after %d retries: %w", maxRetries, err)
}

func ToJSON(v interface{}) string {
	b, err := json.Marshal(v)
	if err != nil {
		return "{}"
	}
	return string(b)
}

func FromJSON(data string, v interface{}) error {
	return json.Unmarshal([]byte(data), v)
}

func MapToStruct(m map[string]interface{}, s interface{}) error {
	b, err := json.Marshal(m)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, s)
}

func ValidateParams(params map[string]interface{}, required []string) error {
	for _, key := range required {
		if _, exists := params[key]; !exists {
			return errors.New("missing required parameter: " + key)
		}
	}
	return nil
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
