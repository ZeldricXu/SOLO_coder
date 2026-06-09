package testutil

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"

	"DF1-56/internal/models"
)

func NewRouteFactory() func(opts ...RouteOption) *models.Route {
	return func(opts ...RouteOption) *models.Route {
		r := &models.Route{
			ID:              uuid.New().String(),
			Path:            "/api/v1/test",
			Method:          "GET",
			MatchType:       models.RouteMatchTypePrefix,
			UpstreamURL:     "http://localhost:8081",
			UpstreamCluster: "default",
			Protocol:        models.ProtocolHTTP,
			Timeout:         30 * time.Second,
			RetryCount:      3,
			Enabled:         true,
			CreatedAt:       time.Now(),
			UpdatedAt:       time.Now(),
		}
		for _, opt := range opts {
			opt(r)
		}
		return r
	}
}

type RouteOption func(*models.Route)

func WithRouteID(id string) RouteOption {
	return func(r *models.Route) { r.ID = id }
}

func WithRoutePath(path string) RouteOption {
	return func(r *models.Route) { r.Path = path }
}

func WithRouteMethod(method string) RouteOption {
	return func(r *models.Route) { r.Method = method }
}

func WithMatchType(matchType models.RouteMatchType) RouteOption {
	return func(r *models.Route) { r.MatchType = matchType }
}

func WithRegexPattern(pattern string) RouteOption {
	return func(r *models.Route) { r.RegexPattern = pattern }
}

func WithUpstreamURL(url string) RouteOption {
	return func(r *models.Route) { r.UpstreamURL = url }
}

func WithRouteDisabled() RouteOption {
	return func(r *models.Route) { r.Enabled = false }
}

func WithMiddlewares(middlewares ...string) RouteOption {
	return func(r *models.Route) { r.Middlewares = middlewares }
}

func WithRateLimitPolicy(policyID string) RouteOption {
	return func(r *models.Route) { r.RateLimitPolicy = policyID }
}

func WithAuthPolicy(policyID string) RouteOption {
	return func(r *models.Route) { r.AuthPolicy = policyID }
}

func WithCircuitBreaker(policyID string) RouteOption {
	return func(r *models.Route) { r.CircuitBreaker = policyID }
}

func NewRateLimitPolicyFactory() func(opts ...RateLimitOption) *models.RateLimitPolicy {
	return func(opts ...RateLimitOption) *models.RateLimitPolicy {
		p := &models.RateLimitPolicy{
			ID:        uuid.New().String(),
			Name:      "default-policy",
			Algorithm: models.AlgorithmTokenBucket,
			KeyBuilder: models.RateLimitKeyBuilder{
				IncludeAPI:  true,
				IncludeUser: true,
				IncludeIP:   true,
			},
			Rules: []models.RateLimitRule{
				{
					Dimension:  models.DimensionAPI,
					Limit:      100,
					Window:     time.Minute,
					Capacity:   100,
					RefillRate: 10,
				},
			},
			Enabled: true,
		}
		for _, opt := range opts {
			opt(p)
		}
		return p
	}
}

type RateLimitOption func(*models.RateLimitPolicy)

func WithRateLimitID(id string) RateLimitOption {
	return func(p *models.RateLimitPolicy) { p.ID = id }
}

func WithRateLimitAlgorithm(alg models.RateLimitAlgorithm) RateLimitOption {
	return func(p *models.RateLimitPolicy) { p.Algorithm = alg }
}

func WithRateLimitRules(rules ...models.RateLimitRule) RateLimitOption {
	return func(p *models.RateLimitPolicy) { p.Rules = rules }
}

func WithTokenBucketRule(limit, burst int64, window time.Duration) RateLimitOption {
	return func(p *models.RateLimitPolicy) {
		p.Rules = append(p.Rules, models.RateLimitRule{
			Dimension:  models.DimensionAPI,
			Limit:      limit,
			Burst:      burst,
			Window:     window,
			Capacity:   limit,
			RefillRate: limit / int64(window.Seconds()),
		})
	}
}

func NewAuthPolicyFactory() func(opts ...AuthOption) *models.AuthPolicy {
	return func(opts ...AuthOption) *models.AuthPolicy {
		p := &models.AuthPolicy{
			ID:             uuid.New().String(),
			Name:           "default-auth",
			AllowAnonymous: false,
			TokenTTL:       time.Hour,
			Enabled:        true,
			Strategies: []models.AuthStrategy{
				{
					Type:     models.AuthTypeJWT,
					Priority: 1,
					Config: models.AuthConfig{
						JWTConfig: &models.JWTConfig{
							Secret:    "test-secret-key",
							Algorithm: "HS256",
							Issuer:    "test-issuer",
						},
					},
				},
			},
		}
		for _, opt := range opts {
			opt(p)
		}
		return p
	}
}

type AuthOption func(*models.AuthPolicy)

func WithAuthID(id string) AuthOption {
	return func(p *models.AuthPolicy) { p.ID = id }
}

func WithJWTStrategy(secret, algorithm string, audience ...string) AuthOption {
	return func(p *models.AuthPolicy) {
		p.Strategies = []models.AuthStrategy{
			{
				Type:     models.AuthTypeJWT,
				Priority: 1,
				Config: models.AuthConfig{
					JWTConfig: &models.JWTConfig{
						Secret:    secret,
						Algorithm: algorithm,
						Issuer:    "test-issuer",
						Audience:  audience,
					},
				},
			},
		}
	}
}

func WithAPIKeyStrategy(headerName, queryParam string) AuthOption {
	return func(p *models.AuthPolicy) {
		p.Strategies = append(p.Strategies, models.AuthStrategy{
			Type:     models.AuthTypeAPIKey,
			Priority: 2,
			Config: models.AuthConfig{
				APIKeyConfig: &models.APIKeyConfig{
					HeaderName: headerName,
					QueryParam: queryParam,
				},
			},
		})
	}
}

func NewCircuitBreakerPolicyFactory() func(opts ...CBOption) *models.CircuitBreakerPolicy {
	return func(opts ...CBOption) *models.CircuitBreakerPolicy {
		p := &models.CircuitBreakerPolicy{
			ID:               uuid.New().String(),
			Name:             "default-cb",
			ErrorThreshold:   0.5,
			RequestVolume:    10,
			SleepWindow:      30 * time.Second,
			HalfOpenRequests: 3,
			SuccessThreshold: 2,
			Timeout:          5 * time.Second,
			Enabled:          true,
		}
		for _, opt := range opts {
			opt(p)
		}
		return p
	}
}

type CBOption func(*models.CircuitBreakerPolicy)

func WithCBID(id string) CBOption {
	return func(p *models.CircuitBreakerPolicy) { p.ID = id }
}

func WithErrorThreshold(threshold float64) CBOption {
	return func(p *models.CircuitBreakerPolicy) { p.ErrorThreshold = threshold }
}

func WithRequestVolume(volume int64) CBOption {
	return func(p *models.CircuitBreakerPolicy) { p.RequestVolume = volume }
}

func WithSleepWindow(window time.Duration) CBOption {
	return func(p *models.CircuitBreakerPolicy) { p.SleepWindow = window }
}

func WithHalfOpenRequests(requests int64) CBOption {
	return func(p *models.CircuitBreakerPolicy) { p.HalfOpenRequests = requests }
}

func WithSuccessThreshold(threshold int64) CBOption {
	return func(p *models.CircuitBreakerPolicy) { p.SuccessThreshold = threshold }
}

func WithFallbackResponse(statusCode int, body string) CBOption {
	return func(p *models.CircuitBreakerPolicy) {
		p.FallbackResponse = &models.FallbackResp{
			StatusCode: statusCode,
			Body:       body,
		}
	}
}

func GenerateJWT(secret, algorithm, issuer, userID string, expiresIn time.Duration, claims ...map[string]interface{}) string {
	var mapClaims jwt.MapClaims
	if len(claims) > 0 && claims[0] != nil {
		mapClaims = claims[0]
	} else {
		mapClaims = jwt.MapClaims{}
	}

	mapClaims["sub"] = userID
	mapClaims["iss"] = issuer
	mapClaims["iat"] = time.Now().Unix()
	if expiresIn > 0 {
		mapClaims["exp"] = time.Now().Add(expiresIn).Unix()
	}

	var token *jwt.Token
	switch algorithm {
	case "RS256":
		token = jwt.NewWithClaims(jwt.SigningMethodRS256, mapClaims)
	default:
		token = jwt.NewWithClaims(jwt.SigningMethodHS256, mapClaims)
	}

	tokenString, _ := token.SignedString([]byte(secret))
	return tokenString
}

func GenerateExpiredJWT(secret, algorithm, issuer, userID string) string {
	claims := jwt.MapClaims{
		"sub": userID,
		"iss": issuer,
		"iat": time.Now().Add(-2 * time.Hour).Unix(),
		"exp": time.Now().Add(-time.Hour).Unix(),
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	tokenString, _ := token.SignedString([]byte(secret))
	return tokenString
}

func GenerateRSAKeys() (*rsa.PrivateKey, *rsa.PublicKey, string, error) {
	privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, nil, "", err
	}

	publicKeyBytes, err := x509.MarshalPKIXPublicKey(&privateKey.PublicKey)
	if err != nil {
		return nil, nil, "", err
	}

	pemBytes := pem.EncodeToMemory(&pem.Block{
		Type:  "PUBLIC KEY",
		Bytes: publicKeyBytes,
	})

	publicKeyStr := base64.StdEncoding.EncodeToString(pemBytes)
	return privateKey, &privateKey.PublicKey, publicKeyStr, nil
}

func GenerateRS256JWT(privateKey *rsa.PrivateKey, issuer, userID string, expiresIn time.Duration) string {
	claims := jwt.MapClaims{
		"sub": userID,
		"iss": issuer,
		"iat": time.Now().Unix(),
	}
	if expiresIn > 0 {
		claims["exp"] = time.Now().Add(expiresIn).Unix()
	}

	token := jwt.NewWithClaims(jwt.SigningMethodRS256, claims)
	tokenString, _ := token.SignedString(privateKey)
	return tokenString
}

func NewTestGatewayContext(method, path string, body ...string) (*models.GatewayContext, *httptest.ResponseRecorder) {
	var req *http.Request
	if len(body) > 0 && body[0] != "" {
		req = httptest.NewRequest(method, path, nil)
	} else {
		req = httptest.NewRequest(method, path, nil)
	}
	req.Header.Set("X-Request-ID", uuid.New().String())
	req.Header.Set("X-Forwarded-For", "127.0.0.1")

	recorder := httptest.NewRecorder()
	ctx := models.NewGatewayContext(recorder, req)
	ctx.RequestID = req.Header.Get("X-Request-ID")
	ctx.ClientIP = "127.0.0.1"
	return ctx, recorder
}

func NewTestHTTPResponse(statusCode int, body interface{}) *http.Response {
	bodyBytes, _ := json.Marshal(body)
	recorder := httptest.NewRecorder()
	recorder.Header().Set("Content-Type", "application/json")
	recorder.WriteHeader(statusCode)
	_, _ = recorder.Write(bodyBytes)
	resp := recorder.Result()
	resp.Body = io.NopCloser(recorder.Body)
	return resp
}

func NewMockUpstreamServer(statusCode int, body interface{}, delay ...time.Duration) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if len(delay) > 0 && delay[0] > 0 {
			time.Sleep(delay[0])
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(statusCode)
		if body != nil {
			_ = json.NewEncoder(w).Encode(body)
		}
	}))
}

func AlmostEqual(a, b, epsilon float64) bool {
	return math.Abs(a-b) <= epsilon
}

func StringPtr(s string) *string {
	return &s
}

func IntPtr(i int) *int {
	return &i
}

func Int64Ptr(i int64) *int64 {
	return &i
}

func BoolPtr(b bool) *bool {
	return &b
}
