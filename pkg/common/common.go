package common

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

func GenerateID(prefix string) string {
	return prefix + "_" + uuid.New().String()[:8]
}

func GenerateRandomHex(n int) string {
	bytes := make([]byte, n)
	_, err := rand.Read(bytes)
	if err != nil {
		return GenerateID("rand")
	}
	return hex.EncodeToString(bytes)
}

func NowPtr() *time.Time {
	now := time.Now().UTC()
	return &now
}

func TimePtr(t time.Time) *time.Time {
	return &t
}

func StringPtr(s string) *string {
	return &s
}

type Response struct {
	Code    int         `json:"code"`
	Message string      `json:"message,omitempty"`
	Data    interface{} `json:"data,omitempty"`
}

func SuccessResponse(c *gin.Context, data interface{}) {
	c.JSON(http.StatusOK, Response{
		Code: 200,
		Data: data,
	})
}

func CreatedResponse(c *gin.Context, data interface{}) {
	c.JSON(http.StatusCreated, Response{
		Code: 201,
		Data: data,
	})
}

func ErrorResponse(c *gin.Context, code int, message string) {
	c.JSON(code, Response{
		Code:    code,
		Message: message,
	})
}

func BindJSON(c *gin.Context, obj interface{}) error {
	if err := c.ShouldBindJSON(obj); err != nil {
		return err
	}
	return nil
}

func Retry(fn func() error, maxAttempts int, delay time.Duration) error {
	var err error
	for i := 0; i < maxAttempts; i++ {
		err = fn()
		if err == nil {
			return nil
		}
		if i < maxAttempts-1 {
			time.Sleep(delay * time.Duration(i+1))
		}
	}
	return err
}

type ConflictError struct {
	Message string
}

func (e *ConflictError) Error() string {
	return e.Message
}

func NewConflictError(msg string) error {
	return &ConflictError{Message: msg}
}

func IsConflictError(err error) bool {
	_, ok := err.(*ConflictError)
	return ok
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

func ValidateNotEmpty(value, fieldName string) error {
	if value == "" {
		return errors.New(fieldName + " cannot be empty")
	}
	return nil
}

type HandlerFunc func(c *gin.Context) error

func WrapHandler(f HandlerFunc) gin.HandlerFunc {
	return func(c *gin.Context) {
		if err := f(c); err != nil {
			if IsConflictError(err) {
				ErrorResponse(c, http.StatusConflict, err.Error())
				return
			}
			ErrorResponse(c, http.StatusInternalServerError, err.Error())
		}
	}
}
