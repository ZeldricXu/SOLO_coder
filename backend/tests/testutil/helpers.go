package testutil

import (
	"bytes"
	"encoding/json"
	"meeting-system/internal/config"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
)

func GenerateTestToken(t *testing.T, cfg *config.Config, userID uuid.UUID, role string) string {
	t.Helper()
	claims := jwt.MapClaims{
		"user_id": userID.String(),
		"role":    role,
		"exp":     time.Now().Add(24 * time.Hour).Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	tokenString, err := token.SignedString([]byte(cfg.JWTSecret))
	require.NoError(t, err)
	return tokenString
}

type TestRequest struct {
	Method      string
	Path        string
	Body        interface{}
	AuthToken   string
	UserID      uuid.UUID
	UserRole    string
	SetupCtx    func(*gin.Context)
}

func SetupGinContext(t *testing.T, cfg *config.Config, req TestRequest) (*gin.Context, *httptest.ResponseRecorder) {
	t.Helper()
	gin.SetMode(gin.TestMode)

	var bodyBytes []byte
	if req.Body != nil {
		var err error
		bodyBytes, err = json.Marshal(req.Body)
		require.NoError(t, err)
	}

	httpReq, err := http.NewRequest(req.Method, req.Path, bytes.NewReader(bodyBytes))
	require.NoError(t, err)
	httpReq.Header.Set("Content-Type", "application/json")

	if req.AuthToken != "" {
		httpReq.Header.Set("Authorization", "Bearer "+req.AuthToken)
	}

	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httpReq

	if req.UserID != uuid.Nil {
		c.Set("userID", req.UserID)
	}
	if req.UserRole != "" {
		c.Set("userRole", req.UserRole)
	}

	if req.SetupCtx != nil {
		req.SetupCtx(c)
	}

	return c, w
}

func ParseResponse(t *testing.T, w *httptest.ResponseRecorder) map[string]interface{} {
	t.Helper()
	var result map[string]interface{}
	err := json.Unmarshal(w.Body.Bytes(), &result)
	require.NoError(t, err)
	return result
}

func ParseResponseInto(t *testing.T, w *httptest.ResponseRecorder, v interface{}) {
	t.Helper()
	err := json.Unmarshal(w.Body.Bytes(), v)
	require.NoError(t, err)
}

type BookingRequestForTest struct {
	RoomID        string   `json:"room_id"`
	Title         string   `json:"title"`
	Description   string   `json:"description"`
	StartTime     string   `json:"start_time"`
	EndTime       string   `json:"end_time"`
	RecurringRule string   `json:"recurring_rule,omitempty"`
	Attendees     []string `json:"attendees,omitempty"`
}

func FormatTimeForRequest(t time.Time) string {
	return t.Format("2006-01-02 15:04:05")
}

func GetTestConfig() *config.Config {
	return &config.Config{
		JWTSecret:  "test-secret-key-for-testing",
		ServerPort: "0",
		DBHost:     "localhost",
		DBPort:     "5432",
		DBUser:     "test",
		DBPassword: "test",
		DBName:     "test_meeting",
	}
}

func AuthMiddlewareForTest(cfg *config.Config) gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.Next()
			return
		}

		tokenStr := authHeader[len("Bearer "):]
		token, err := jwt.Parse(tokenStr, func(token *jwt.Token) (interface{}, error) {
			return []byte(cfg.JWTSecret), nil
		})
		if err != nil || !token.Valid {
			c.Next()
			return
		}

		if claims, ok := token.Claims.(jwt.MapClaims); ok {
			if userID, ok := claims["user_id"].(string); ok {
				c.Set("userID", uuid.MustParse(userID))
			}
			if role, ok := claims["role"].(string); ok {
				c.Set("userRole", role)
			}
		}
		c.Next()
	}
}

func AdminMiddlewareForTest() gin.HandlerFunc {
	return func(c *gin.Context) {
		userRole, exists := c.Get("userRole")
		if !exists || userRole != "admin" {
			c.Next()
			return
		}
		c.Next()
	}
}

func SetupTestRouter() *gin.Engine {
	gin.SetMode(gin.TestMode)
	return gin.New()
}

func MakeRequest(method, path string, body interface{}, token string) (*http.Request, *httptest.ResponseRecorder) {
	var bodyBytes []byte
	if body != nil {
		bodyBytes, _ = json.Marshal(body)
	}

	req, _ := http.NewRequest(method, path, bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}

	w := httptest.NewRecorder()
	return req, w
}

type TableDrivenCase[T any, R any] struct {
	Name     string
	Input    T
	Expected R
}

func RunTableDriven[T any, R any](
	t *testing.T,
	cases []TableDrivenCase[T, R],
	testFunc func(t *testing.T, input T, expected R),
) {
	t.Helper()
	for _, tc := range cases {
		t.Run(tc.Name, func(t *testing.T) {
			testFunc(t, tc.Input, tc.Expected)
		})
	}
}
