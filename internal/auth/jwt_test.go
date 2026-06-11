package auth

import (
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"DF1-56/internal/models"
	"DF1-56/internal/testutil"
)

func TestJWTMiddleware_NormalPath(t *testing.T) {
	t.Run("valid HS256 JWT passes authentication", func(t *testing.T) {
		secret := "test-secret-key"
		issuer := "test-issuer"
		userID := "user-123"

		jwtConfig := &models.JWTConfig{
			Secret:    secret,
			Algorithm: "HS256",
			Issuer:    issuer,
		}

		mw, err := NewJWTMiddleware(jwtConfig, false, 1)
		require.NoError(t, err)

		token := testutil.GenerateJWT(secret, "HS256", issuer, userID, time.Hour)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users")
		ctx.Request.Header.Set("Authorization", "Bearer "+token)

		called := false
		next := func(ctx *models.GatewayContext) error {
			called = true
			return nil
		}

		err = mw.Handle(ctx, next)
		require.NoError(t, err)
		assert.True(t, called)
		assert.Equal(t, userID, ctx.UserID)

		claimsVal, ok := ctx.Get(string(models.ContextKeyClaims))
		require.True(t, ok)
		claims, ok := claimsVal.(jwt.MapClaims)
		require.True(t, ok)
		assert.Equal(t, userID, claims["sub"])
	})

	t.Run("expired JWT is rejected", func(t *testing.T) {
		secret := "test-secret-key"
		issuer := "test-issuer"
		userID := "user-123"

		jwtConfig := &models.JWTConfig{
			Secret:    secret,
			Algorithm: "HS256",
			Issuer:    issuer,
		}

		mw, err := NewJWTMiddleware(jwtConfig, false, 1)
		require.NoError(t, err)

		expiredToken := testutil.GenerateExpiredJWT(secret, "HS256", issuer, userID)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users")
		ctx.Request.Header.Set("Authorization", "Bearer "+expiredToken)

		called := false
		next := func(ctx *models.GatewayContext) error {
			called = true
			return nil
		}

		err = mw.Handle(ctx, next)
		require.Error(t, err)
		assert.False(t, called)
		assert.Contains(t, err.Error(), "expired")
	})

	t.Run("invalid signature JWT is rejected", func(t *testing.T) {
		secret := "correct-secret"
		wrongSecret := "wrong-secret"
		issuer := "test-issuer"
		userID := "user-123"

		jwtConfig := &models.JWTConfig{
			Secret:    secret,
			Algorithm: "HS256",
			Issuer:    issuer,
		}

		mw, err := NewJWTMiddleware(jwtConfig, false, 1)
		require.NoError(t, err)

		tokenWithWrongSecret := testutil.GenerateJWT(wrongSecret, "HS256", issuer, userID, time.Hour)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users")
		ctx.Request.Header.Set("Authorization", "Bearer "+tokenWithWrongSecret)

		called := false
		next := func(ctx *models.GatewayContext) error {
			called = true
			return nil
		}

		err = mw.Handle(ctx, next)
		require.Error(t, err)
		assert.False(t, called)
	})

	t.Run("JWT in query parameter works", func(t *testing.T) {
		secret := "test-secret-key"
		issuer := "test-issuer"
		userID := "user-456"

		jwtConfig := &models.JWTConfig{
			Secret:    secret,
			Algorithm: "HS256",
			Issuer:    issuer,
		}

		mw, err := NewJWTMiddleware(jwtConfig, false, 1)
		require.NoError(t, err)

		token := testutil.GenerateJWT(secret, "HS256", issuer, userID, time.Hour)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users?token="+token)

		called := false
		next := func(ctx *models.GatewayContext) error {
			called = true
			return nil
		}

		err = mw.Handle(ctx, next)
		require.NoError(t, err)
		assert.True(t, called)
		assert.Equal(t, userID, ctx.UserID)
	})

	t.Run("optional auth allows anonymous access without token", func(t *testing.T) {
		jwtConfig := &models.JWTConfig{
			Secret:    "test-secret",
			Algorithm: "HS256",
			Issuer:    "test-issuer",
		}

		mw, err := NewJWTMiddleware(jwtConfig, true, 1)
		require.NoError(t, err)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users")

		called := false
		next := func(ctx *models.GatewayContext) error {
			called = true
			return nil
		}

		err = mw.Handle(ctx, next)
		require.NoError(t, err)
		assert.True(t, called)
		assert.Empty(t, ctx.UserID)
	})

	t.Run("missing required claims are rejected", func(t *testing.T) {
		secret := "test-secret-key"
		issuer := "test-issuer"
		userID := "user-123"

		jwtConfig := &models.JWTConfig{
			Secret:         secret,
			Algorithm:      "HS256",
			Issuer:         issuer,
			ClaimsRequired: []string{"role", "scope"},
		}

		mw, err := NewJWTMiddleware(jwtConfig, false, 1)
		require.NoError(t, err)

		claims := map[string]interface{}{
			"sub": userID,
			"iss": issuer,
			"iat": time.Now().Unix(),
			"exp": time.Now().Add(time.Hour).Unix(),
		}
		token := testutil.GenerateJWT(secret, "HS256", issuer, userID, time.Hour, claims)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users")
		ctx.Request.Header.Set("Authorization", "Bearer "+token)

		called := false
		next := func(ctx *models.GatewayContext) error {
			called = true
			return nil
		}

		err = mw.Handle(ctx, next)
		require.Error(t, err)
		assert.False(t, called)
		assert.Contains(t, err.Error(), "required claim missing")
		assert.Contains(t, err.Error(), "role")
	})

	t.Run("invalid issuer is rejected", func(t *testing.T) {
		secret := "test-secret-key"
		issuer := "test-issuer"
		wrongIssuer := "wrong-issuer"
		userID := "user-123"

		jwtConfig := &models.JWTConfig{
			Secret:    secret,
			Algorithm: "HS256",
			Issuer:    issuer,
		}

		mw, err := NewJWTMiddleware(jwtConfig, false, 1)
		require.NoError(t, err)

		token := testutil.GenerateJWT(secret, "HS256", wrongIssuer, userID, time.Hour)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users")
		ctx.Request.Header.Set("Authorization", "Bearer "+token)

		called := false
		next := func(ctx *models.GatewayContext) error {
			called = true
			return nil
		}

		err = mw.Handle(ctx, next)
		require.Error(t, err)
		assert.False(t, called)
		assert.Contains(t, err.Error(), "invalid issuer")
	})
}

func TestJWTMiddleware_AbnormalPath(t *testing.T) {
	t.Run("malformed token is rejected", func(t *testing.T) {
		jwtConfig := &models.JWTConfig{
			Secret:    "test-secret",
			Algorithm: "HS256",
			Issuer:    "test-issuer",
		}

		mw, err := NewJWTMiddleware(jwtConfig, false, 1)
		require.NoError(t, err)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users")
		ctx.Request.Header.Set("Authorization", "Bearer malformed.token.here")

		called := false
		next := func(ctx *models.GatewayContext) error {
			called = true
			return nil
		}

		err = mw.Handle(ctx, next)
		require.Error(t, err)
		assert.False(t, called)
	})

	t.Run("empty token is rejected", func(t *testing.T) {
		jwtConfig := &models.JWTConfig{
			Secret:    "test-secret",
			Algorithm: "HS256",
			Issuer:    "test-issuer",
		}

		mw, err := NewJWTMiddleware(jwtConfig, false, 1)
		require.NoError(t, err)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users")

		called := false
		next := func(ctx *models.GatewayContext) error {
			called = true
			return nil
		}

		err = mw.Handle(ctx, next)
		require.Error(t, err)
		assert.False(t, called)
		assert.Contains(t, err.Error(), "missing or invalid token")
	})

	t.Run("unsupported algorithm is rejected", func(t *testing.T) {
		jwtConfig := &models.JWTConfig{
			Secret:    "test-secret",
			Algorithm: "INVALID",
			Issuer:    "test-issuer",
		}

		mw, err := NewJWTMiddleware(jwtConfig, false, 1)
		require.NoError(t, err)

		token := testutil.GenerateJWT("test-secret", "HS256", "test-issuer", "user-123", time.Hour)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users")
		ctx.Request.Header.Set("Authorization", "Bearer "+token)

		called := false
		next := func(ctx *models.GatewayContext) error {
			called = true
			return nil
		}

		err = mw.Handle(ctx, next)
		require.Error(t, err)
		assert.False(t, called)
		assert.Contains(t, err.Error(), "invalid token")
	})

	t.Run("RS256 algorithm with public key validation", func(t *testing.T) {
		privateKey, _, publicKeyStr, err := testutil.GenerateRSAKeys()
		require.NoError(t, err)

		jwtConfig := &models.JWTConfig{
			PublicKey: publicKeyStr,
			Algorithm: "RS256",
			Issuer:    "test-issuer",
		}

		mw, err := NewJWTMiddleware(jwtConfig, false, 1)
		require.NoError(t, err)

		token := testutil.GenerateRS256JWT(privateKey, "test-issuer", "user-123", time.Hour)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users")
		ctx.Request.Header.Set("Authorization", "Bearer "+token)

		called := false
		next := func(ctx *models.GatewayContext) error {
			called = true
			return nil
		}

		err = mw.Handle(ctx, next)
		require.NoError(t, err)
		assert.True(t, called)
		assert.Equal(t, "user-123", ctx.UserID)
	})

	t.Run("RS256 with wrong signature is rejected", func(t *testing.T) {
		_, _, publicKeyStr, err := testutil.GenerateRSAKeys()
		require.NoError(t, err)

		otherPrivateKey, _, _, err := testutil.GenerateRSAKeys()
		require.NoError(t, err)

		jwtConfig := &models.JWTConfig{
			PublicKey: publicKeyStr,
			Algorithm: "RS256",
			Issuer:    "test-issuer",
		}

		mw, err := NewJWTMiddleware(jwtConfig, false, 1)
		require.NoError(t, err)

		token := testutil.GenerateRS256JWT(otherPrivateKey, "test-issuer", "user-123", time.Hour)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users")
		ctx.Request.Header.Set("Authorization", "Bearer "+token)

		called := false
		next := func(ctx *models.GatewayContext) error {
			called = true
			return nil
		}

		err = mw.Handle(ctx, next)
		require.Error(t, err)
		assert.False(t, called)
	})
}

func TestJWTMiddleware_Concurrency(t *testing.T) {
	t.Run("concurrent JWT validation does not race", func(t *testing.T) {
		secret := "test-secret-key"
		issuer := "test-issuer"

		jwtConfig := &models.JWTConfig{
			Secret:    secret,
			Algorithm: "HS256",
			Issuer:    issuer,
		}

		mw, err := NewJWTMiddleware(jwtConfig, false, 1)
		require.NoError(t, err)

		numGoroutines := 20
		requestsPerGoroutine := 100

		var wg sync.WaitGroup
		var successCount int64
		var failCount int64

		for i := 0; i < numGoroutines; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()

				userID := "user-" + string(rune('A'+idx%26))
				var token string
				if idx%2 == 0 {
					token = testutil.GenerateJWT(secret, "HS256", issuer, userID, time.Hour)
				} else {
					token = testutil.GenerateExpiredJWT(secret, "HS256", issuer, userID)
				}

				for j := 0; j < requestsPerGoroutine; j++ {
					ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users")
					ctx.Request.Header.Set("Authorization", "Bearer "+token)

					nextCalled := false
					next := func(ctx *models.GatewayContext) error {
						nextCalled = true
						return nil
					}

					err := mw.Handle(ctx, next)
					if err == nil && nextCalled {
						atomic.AddInt64(&successCount, 1)
					} else {
						atomic.AddInt64(&failCount, 1)
					}
				}
			}(i)
		}

		wg.Wait()

		expectedSuccess := int64(numGoroutines/2) * int64(requestsPerGoroutine)
		expectedFail := int64(numGoroutines/2) * int64(requestsPerGoroutine)

		assert.Equal(t, expectedSuccess, successCount, "success count should match expected")
		assert.Equal(t, expectedFail, failCount, "fail count should match expected")
	})

	t.Run("concurrent access with different tokens", func(t *testing.T) {
		secret := "test-secret-key"
		issuer := "test-issuer"

		jwtConfig := &models.JWTConfig{
			Secret:    secret,
			Algorithm: "HS256",
			Issuer:    issuer,
		}

		mw, err := NewJWTMiddleware(jwtConfig, false, 1)
		require.NoError(t, err)

		numTokens := 50
		tokens := make([]string, numTokens)
		for i := 0; i < numTokens; i++ {
			tokens[i] = testutil.GenerateJWT(secret, "HS256", issuer, "user-"+string(rune('A'+i%26)), time.Hour)
		}

		numGoroutines := 10
		var wg sync.WaitGroup

		for i := 0; i < numGoroutines; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				for j := 0; j < 500; j++ {
					token := tokens[(idx*5+j)%numTokens]
					ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users")
					ctx.Request.Header.Set("Authorization", "Bearer "+token)

					next := func(ctx *models.GatewayContext) error {
						return nil
					}

					err := mw.Handle(ctx, next)
					assert.NoError(t, err)
				}
			}(i)
		}

		wg.Wait()
	})
}

func TestAPIKeyMiddleware_NormalPath(t *testing.T) {
	t.Run("valid API Key in header passes", func(t *testing.T) {
		apiKeyConfig := &models.APIKeyConfig{
			HeaderName: "X-API-Key",
			QueryParam: "api_key",
		}

		validKeys := map[string]string{
			"valid-key-123": "user-123",
			"valid-key-456": "user-456",
		}

		mw, err := NewAPIKeyMiddleware(apiKeyConfig, validKeys, false, 1)
		require.NoError(t, err)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users")
		ctx.Request.Header.Set("X-API-Key", "valid-key-123")

		called := false
		next := func(ctx *models.GatewayContext) error {
			called = true
			return nil
		}

		err = mw.Handle(ctx, next)
		require.NoError(t, err)
		assert.True(t, called)
	})

	t.Run("invalid API Key is rejected", func(t *testing.T) {
		apiKeyConfig := &models.APIKeyConfig{
			HeaderName: "X-API-Key",
			QueryParam: "api_key",
		}

		validKeys := map[string]string{
			"valid-key-123": "user-123",
		}

		mw, err := NewAPIKeyMiddleware(apiKeyConfig, validKeys, false, 1)
		require.NoError(t, err)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users")
		ctx.Request.Header.Set("X-API-Key", "invalid-key")

		called := false
		next := func(ctx *models.GatewayContext) error {
			called = true
			return nil
		}

		err = mw.Handle(ctx, next)
		require.Error(t, err)
		assert.False(t, called)
		assert.Contains(t, err.Error(), "invalid API key")
	})

	t.Run("API Key in query parameter works", func(t *testing.T) {
		apiKeyConfig := &models.APIKeyConfig{
			HeaderName: "X-API-Key",
			QueryParam: "api_key",
		}

		validKeys := map[string]string{
			"query-key-789": "user-789",
		}

		mw, err := NewAPIKeyMiddleware(apiKeyConfig, validKeys, false, 1)
		require.NoError(t, err)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/api/v1/users?api_key=query-key-789")

		called := false
		next := func(ctx *models.GatewayContext) error {
			called = true
			return nil
		}

		err = mw.Handle(ctx, next)
		require.NoError(t, err)
		assert.True(t, called)
	})
}

func TestMiddlewareChain_NormalPath(t *testing.T) {
	t.Run("chain executes middlewares in order", func(t *testing.T) {
		executionOrder := []int{}

		mw1 := &testMiddleware{name: "mw1", order: 1, executeFn: func() {
			executionOrder = append(executionOrder, 1)
		}}
		mw2 := &testMiddleware{name: "mw2", order: 2, executeFn: func() {
			executionOrder = append(executionOrder, 2)
		}}
		mw3 := &testMiddleware{name: "mw3", order: 3, executeFn: func() {
			executionOrder = append(executionOrder, 3)
		}}

		chain := models.NewMiddlewareChain(mw1, mw2, mw3)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/test")

		handlerCalled := false
		handler := func(ctx *models.GatewayContext) error {
			handlerCalled = true
			return nil
		}

		finalHandler := chain.Then(handler)
		err := finalHandler(ctx)

		require.NoError(t, err)
		assert.True(t, handlerCalled)
		assert.Equal(t, []int{1, 2, 3}, executionOrder)
	})

	t.Run("middleware error stops chain", func(t *testing.T) {
		executionOrder := []int{}
		expectedErr := errors.New("middleware failed")

		mw1 := &testMiddleware{name: "mw1", order: 1, executeFn: func() {
			executionOrder = append(executionOrder, 1)
		}}
		mw2 := &testMiddleware{name: "mw2", order: 2, executeFn: func() {
			executionOrder = append(executionOrder, 2)
		}, returnErr: expectedErr}
		mw3 := &testMiddleware{name: "mw3", order: 3, executeFn: func() {
			executionOrder = append(executionOrder, 3)
		}}

		chain := models.NewMiddlewareChain(mw1, mw2, mw3)

		ctx, _ := testutil.NewTestGatewayContext("GET", "/test")

		handlerCalled := false
		handler := func(ctx *models.GatewayContext) error {
			handlerCalled = true
			return nil
		}

		finalHandler := chain.Then(handler)
		err := finalHandler(ctx)

		assert.Equal(t, expectedErr, err)
		assert.False(t, handlerCalled)
		assert.Equal(t, []int{1, 2}, executionOrder)
	})
}

type testMiddleware struct {
	name      string
	order     int
	executeFn func()
	returnErr error
}

func (m *testMiddleware) Name() string {
	return m.name
}

func (m *testMiddleware) Priority() int {
	return m.order
}

func (m *testMiddleware) Handle(ctx *models.GatewayContext, next models.HandlerFunc) error {
	if m.executeFn != nil {
		m.executeFn()
	}
	if m.returnErr != nil {
		return m.returnErr
	}
	return next(ctx)
}

func (m *testMiddleware) SetValidKeys(keys map[string]bool) {
}
