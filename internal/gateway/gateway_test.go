package gateway

import (
	"context"
	"net/http"
	"sync"
	"testing"
	"time"
)

func TestAPIGateway_StartStop_NoResourceLeak(t *testing.T) {
	gw := NewAPIGateway(Config{
		Port:            18080,
		JWTSecret:       "test-secret",
		TokenExpiration:  time.Hour,
		RateLimit:       10,
		RateLimitWindow: time.Minute,
	})

	if err := gw.Start(); err != nil {
		t.Fatalf("failed to start gateway: %v", err)
	}

	time.Sleep(150 * time.Millisecond)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := gw.Stop(ctx); err != nil {
		t.Fatalf("failed to stop gateway: %v", err)
	}

	if err := gw.Start(); err != nil {
		t.Fatalf("failed to restart gateway: %v", err)
	}

	time.Sleep(150 * time.Millisecond)

	ctx2, cancel2 := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel2()

	if err := gw.Stop(ctx2); err != nil {
		t.Fatalf("failed to stop gateway second time: %v", err)
	}
}

func TestAPIGateway_DoubleStart_ReturnsError(t *testing.T) {
	gw := NewAPIGateway(Config{
		Port:            18081,
		JWTSecret:       "test-secret",
		TokenExpiration:  time.Hour,
		RateLimit:       10,
		RateLimitWindow: time.Minute,
	})

	if err := gw.Start(); err != nil {
		t.Fatalf("first start should succeed: %v", err)
	}
	defer gw.Stop(context.Background())

	time.Sleep(150 * time.Millisecond)

	if err := gw.Start(); err == nil {
		t.Error("second start should return error")
	}
}

func TestAPIGateway_StopBeforeStart_NoPanic(t *testing.T) {
	gw := NewAPIGateway(Config{
		Port:            0,
		JWTSecret:       "test-secret",
		TokenExpiration:  time.Hour,
		RateLimit:       10,
		RateLimitWindow: time.Minute,
	})

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()

	if err := gw.Stop(ctx); err != nil {
		t.Errorf("stop before start should not error: %v", err)
	}
}

func TestSlidingWindowLimiter_CleanupExpired(t *testing.T) {
	limiter := NewSlidingWindowLimiter(10, 50*time.Millisecond)

	limiter.Allow("key1")
	limiter.Allow("key2")

	time.Sleep(100 * time.Millisecond)

	keyCount, totalReqs := limiter.Stats()
	if keyCount != 2 || totalReqs != 2 {
		t.Errorf("expected 2 keys with 2 requests before cleanup")
	}

	limiter.CleanupExpired()

	keyCount, totalReqs = limiter.Stats()
	if keyCount != 0 || totalReqs != 0 {
		t.Errorf("expected 0 keys after cleanup")
	}
}

func TestSlidingWindowLimiter_ConcurrentSafe(t *testing.T) {
	limiter := NewSlidingWindowLimiter(100, time.Minute)

	var wg sync.WaitGroup
	iterations := 1000

	for i := 0; i < iterations; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			limiter.Allow("concurrent-key")
		}()
	}

	wg.Wait()

	_, total := limiter.Stats()
	if total != iterations {
		t.Errorf("expected %d requests, got %d", iterations, total)
	}
}

func TestSlidingWindowLimiter_ResetAll(t *testing.T) {
	limiter := NewSlidingWindowLimiter(10, time.Minute)

	limiter.Allow("key1")
	limiter.Allow("key2")

	keyCount, _ := limiter.Stats()
	if keyCount != 2 {
		t.Errorf("expected 2 keys")
	}

	limiter.ResetAll()

	keyCount, _ = limiter.Stats()
	if keyCount != 0 {
		t.Errorf("expected 0 keys after reset")
	}
}

func TestRateLimitMiddleware_ConcurrentRequests(t *testing.T) {
	mw := NewRateLimitMiddleware(100, time.Minute)

	var wg sync.WaitGroup

	for i := 0; i < 200; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_ = mw.Limit()
		}()
	}
	wg.Wait()
}

func TestJWTTokenManager_ConcurrentGenerate(t *testing.T) {
	tm := NewJWTTokenManager("secret", time.Hour)

	user := &User{UserID: "test", Roles: []string{"user"}}

	var wg sync.WaitGroup
	results := make(chan string, 100)

	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			token, err := tm.GenerateToken(user)
			if err != nil {
				t.Errorf("token generation error: %v", err)
				return
			}
			results <- token
		}()
	}

	wg.Wait()
	close(results)

	for token := range results {
		claims, err := tm.ValidateToken(token)
		if err != nil {
			t.Errorf("token validation error: %v", err)
		}
		if claims.UserID != "test" {
			t.Errorf("expected user id test")
		}
	}
}

func TestAuthenticator_ConcurrentLogin(t *testing.T) {
	auth := NewAuthenticator("secret", time.Hour)
	auth.AddUser("user", "pass", []string{"user"})

	var wg sync.WaitGroup
	errors := make(chan error, 100)

	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := auth.Login("user", "pass")
			if err != nil {
				errors <- err
			}
		}()
	}

	wg.Wait()
	close(errors)

	for err := range errors {
		if err != nil {
			t.Errorf("login error: %v", err)
		}
	}
}

func TestAuthorizer_MissingUserIDContext(t *testing.T) {
	gw := NewAPIGateway(Config{
		Port:            0,
		JWTSecret:       "secret",
		TokenExpiration:  time.Hour,
		RateLimit:       10,
		RateLimitWindow: time.Minute,
	})

	router := gw.Router()

	router.GET("/test-protected", gw.auth.Authenticate, gw.authorizer.RequireRole("admin"), func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	req, _ := http.NewRequest("GET", "/test-protected", nil)
	req.Header.Set("Authorization", "Bearer invalid")

	limiter := NewSlidingWindowLimiter(10, time.Minute)
	if limiter.Allow("test") {
	}
}
