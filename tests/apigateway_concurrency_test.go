package tests

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"

	"session133/internal/apigateway"
	"session133/tests/testbuilders"
	"session133/tests/testutils"
)

func TestAuthService_ConcurrentLogin(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&apigateway.User{},
		&apigateway.UserSession{},
	)
	defer cleanup()

	authService := apigateway.NewAuthService(db, logger, "test-secret-key")

	testUser := testbuilders.NewUserBuilder().
		WithID("concurrent_user").
		WithUsername("concurrent_test").
		WithRoles([]string{"user"}).
		Build()
	testUser.PasswordHash, _ = authService.HashPassword("password123")
	require.NoError(t, db.Create(testUser).Error)

	t.Run("并发登录不会造成竞态条件", func(t *testing.T) {
		var wg sync.WaitGroup
		successCount := 0
		var mu sync.Mutex
		concurrencyLevel := 50

		for i := 0; i < concurrencyLevel; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				req := &apigateway.LoginRequest{
					Username: "concurrent_test",
					Password: "password123",
				}
				resp, err := authService.Login(context.Background(), req)
				mu.Lock()
				defer mu.Unlock()
				if err == nil && resp != nil && resp.AccessToken != "" {
					successCount++
				}
			}()
		}

		wg.Wait()
		assert.Greater(t, successCount, 0, "至少应该有一些登录成功")

		var sessionCount int64
		db.Model(&apigateway.UserSession{}).Where("user_id = ?", "concurrent_user").Count(&sessionCount)
		assert.Equal(t, int64(successCount), sessionCount, "会话数应等于成功登录数")
	})
}

func TestRateLimiter_ConcurrentAccess(t *testing.T) {
	logger, _ := zap.NewDevelopment()

	t.Run("令牌桶限流在高并发下正确工作", func(t *testing.T) {
		rl := apigateway.NewRateLimiter(&apigateway.RateLimitConfig{
			Strategy: apigateway.StrategyTokenBucket,
			Limit:    100,
			Burst:    100,
			Window:   time.Minute,
		}, nil, logger)

		var wg sync.WaitGroup
		allowedCount := 0
		deniedCount := 0
		var mu sync.Mutex
		concurrencyLevel := 200

		for i := 0; i < concurrencyLevel; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				allowed := rl.Allow("test-key", 1)
				mu.Lock()
				defer mu.Unlock()
				if allowed {
					allowedCount++
				} else {
					deniedCount++
				}
			}()
		}

		wg.Wait()
		assert.Equal(t, concurrencyLevel, allowedCount+deniedCount, "总请求数应匹配")
		assert.Equal(t, 100, allowedCount, "应恰好允许100个请求")
		assert.Equal(t, 100, deniedCount, "应拒绝100个请求")
	})

	t.Run("滑动窗口限流并发正确性", func(t *testing.T) {
		rl := apigateway.NewRateLimiter(&apigateway.RateLimitConfig{
			Strategy: apigateway.StrategySlidingWindow,
			Limit:    50,
			Window:   time.Second,
		}, nil, logger)

		var wg sync.WaitGroup
		results := make([]bool, 100)
		concurrencyLevel := 100

		for i := 0; i < concurrencyLevel; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				results[idx] = rl.Allow("sliding-key", 1)
			}(i)
		}

		wg.Wait()

		allowed := 0
		for _, r := range results {
			if r {
				allowed++
			}
		}
		assert.Equal(t, 50, allowed, "滑动窗口应只允许50个请求")
	})

	t.Run("不同key的限流互不干扰", func(t *testing.T) {
		rl := apigateway.NewRateLimiter(&apigateway.RateLimitConfig{
			Strategy: apigateway.StrategyTokenBucket,
			Limit:    10,
			Burst:    10,
			Window:   time.Minute,
		}, nil, logger)

		var wg sync.WaitGroup
		keyCount := 10
		requestsPerKey := 5

		successCounts := make(map[string]int)
		var mu sync.Mutex

		for k := 0; k < keyCount; k++ {
			key := string(rune('A' + k))
			for r := 0; r < requestsPerKey; r++ {
				wg.Add(1)
				go func(k string) {
					defer wg.Done()
					allowed := rl.Allow(k, 1)
					mu.Lock()
					defer mu.Unlock()
					if allowed {
						successCounts[k]++
					}
				}(key)
			}
		}

		wg.Wait()

		for k := range successCounts {
			assert.Equal(t, requestsPerKey, successCounts[k], "Key %s 应该所有请求都成功", k)
		}
	})
}

func TestMiddleware_ConcurrentRequests(t *testing.T) {
	gin.SetMode(gin.TestMode)
	logger, _ := zap.NewDevelopment()
	db, _, cleanup := testutils.SetupTestDB(
		&apigateway.User{},
		&apigateway.UserSession{},
	)
	defer cleanup()

	authService := apigateway.NewAuthService(db, logger, "test-secret")
	rl := apigateway.NewRateLimiter(&apigateway.RateLimitConfig{
		Strategy: apigateway.StrategyTokenBucket,
		Limit:    1000,
		Burst:    1000,
		Window:   time.Minute,
	}, nil, logger)

	mw := apigateway.NewMiddleware(authService, rl, logger)

	t.Run("RequestID中间件并发安全", func(t *testing.T) {
		r := gin.New()
		r.Use(mw.RequestID())
		r.GET("/test", func(c *gin.Context) {
			requestID := c.GetString("request_id")
			c.JSON(http.StatusOK, gin.H{"request_id": requestID})
		})

		var wg sync.WaitGroup
		requestIDs := make(map[string]bool)
		var mu sync.Mutex
		concurrency := 100

		for i := 0; i < concurrency; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				req, _ := http.NewRequest("GET", "/test", nil)
				w := httptest.NewRecorder()
				r.ServeHTTP(w, req)

				var resp map[string]string
				json.Unmarshal(w.Body.Bytes(), &resp)

				mu.Lock()
				defer mu.Unlock()
				if id, ok := resp["request_id"]; ok {
					requestIDs[id] = true
				}
			}()
		}

		wg.Wait()
		assert.Equal(t, concurrency, len(requestIDs), "所有RequestID应该唯一")
	})

	t.Run("并发认证请求不会相互干扰", func(t *testing.T) {
		testUser := testbuilders.NewUserBuilder().
			WithID("auth_concurrent_user").
			WithUsername("auth_concurrent").
			Build()
		testUser.PasswordHash, _ = authService.HashPassword("pass123")
		db.Create(testUser)

		token, _ := authService.GenerateToken(testUser.ID, testUser.Username, testUser.Roles, time.Hour)

		r := gin.New()
		authGroup := r.Group("/")
		authGroup.Use(mw.AuthRequired())
		authGroup.GET("/protected", func(c *gin.Context) {
			userID := c.GetString("user_id")
			username := c.GetString("username")
			c.JSON(http.StatusOK, gin.H{"user_id": userID, "username": username})
		})

		var wg sync.WaitGroup
		concurrency := 50
		successCount := 0
		var mu sync.Mutex

		for i := 0; i < concurrency; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				req, _ := http.NewRequest("GET", "/protected", nil)
				req.Header.Set("Authorization", "Bearer "+token)
				w := httptest.NewRecorder()
				r.ServeHTTP(w, req)

				mu.Lock()
				defer mu.Unlock()
				if w.Code == http.StatusOK {
					successCount++
				}
			}()
		}

		wg.Wait()
		assert.Equal(t, concurrency, successCount, "所有并发认证请求应该成功")
	})
}

func TestAuthService_ConcurrentTokenRefresh(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&apigateway.User{},
		&apigateway.UserSession{},
	)
	defer cleanup()

	authService := apigateway.NewAuthService(db, logger, "test-secret")

	testUser := testbuilders.NewUserBuilder().
		WithID("refresh_user").
		WithUsername("refresh_test").
		Build()
	testUser.PasswordHash, _ = authService.HashPassword("password")
	db.Create(testUser)

	t.Run("并发刷新Token不会导致会话混乱", func(t *testing.T) {
		loginResp, err := authService.Login(context.Background(), &apigateway.LoginRequest{
			Username: "refresh_test",
			Password: "password",
		})
		require.NoError(t, err)

		var wg sync.WaitGroup
		concurrency := 30
		newTokens := make([]string, 0)
		var mu sync.Mutex
		errors := make([]error, 0)

		for i := 0; i < concurrency; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				resp, err := authService.RefreshToken(context.Background(), loginResp.RefreshToken)
				mu.Lock()
				defer mu.Unlock()
				if err != nil {
					errors = append(errors, err)
				} else if resp != nil {
					newTokens = append(newTokens, resp.AccessToken)
				}
			}()
		}

		wg.Wait()

		uniqueTokens := make(map[string]bool)
		for _, t := range newTokens {
			uniqueTokens[t] = true
		}
		assert.Equal(t, len(newTokens), len(uniqueTokens), "刷新的Token应该都是唯一的")
	})
}

func TestRateLimiter_MemorySafetyUnderLoad(t *testing.T) {
	logger, _ := zap.NewDevelopment()

	t.Run("长时间运行不会内存泄漏", func(t *testing.T) {
		rl := apigateway.NewRateLimiter(&apigateway.RateLimitConfig{
			Strategy: apigateway.StrategyTokenBucket,
			Limit:    100,
			Burst:    100,
			Window:   100 * time.Millisecond,
		}, nil, logger)

		done := make(chan bool)
		var wg sync.WaitGroup

		for i := 0; i < 10; i++ {
			wg.Add(1)
			go func(id int) {
				defer wg.Done()
				for j := 0; j < 100; j++ {
					key := string(rune('A'+id%26)) + "-" + string(rune('a'+j%26))
					rl.Allow(key, 1)
					time.Sleep(1 * time.Millisecond)
				}
			}(i)
		}

		go func() {
			wg.Wait()
			done <- true
		}()

		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Fatal("测试超时")
		}
	})
}

func TestAPIKeyAuth_ConcurrentValidation(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&apigateway.User{},
		&apigateway.UserSession{},
	)
	defer cleanup()

	authService := apigateway.NewAuthService(db, logger, "test-secret")

	users := make([]*apigateway.User, 5)
	for i := 0; i < 5; i++ {
		user := testbuilders.NewUserBuilder().
			WithID("api_user_" + string(rune('0'+i))).
			WithUsername("api_user_" + string(rune('0'+i))).
			WithAPIKey("api-key-" + string(rune('0'+i))).
			Build()
		users[i] = user
		db.Create(user)
	}

	t.Run("并发API Key验证", func(t *testing.T) {
		var wg sync.WaitGroup
		concurrency := 100
		results := make(chan bool, concurrency)

		for i := 0; i < concurrency; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				userIdx := idx % 5
				user, err := authService.ValidateAPIKey(context.Background(), "api-key-"+string(rune('0'+userIdx)))
				results <- (err == nil && user != nil && user.ID == users[userIdx].ID)
			}(i)
		}

		wg.Wait()
		close(results)

		successCount := 0
		for r := range results {
			if r {
				successCount++
			}
		}
		assert.Equal(t, concurrency, successCount, "所有API Key验证应该成功")
	})
}

func TestRBAC_ConcurrentPermissionCheck(t *testing.T) {
	logger, _ := zap.NewDevelopment()

	t.Run("并发权限检查", func(t *testing.T) {
		var wg sync.WaitGroup
		concurrency := 200
		results := make(chan bool, concurrency)

		for i := 0; i < concurrency; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()

				var userRoles []string
				if idx%3 == 0 {
					userRoles = []string{"admin"}
				} else if idx%3 == 1 {
					userRoles = []string{"editor"}
				} else {
					userRoles = []string{"viewer"}
				}

				ctx := context.Background()
				ctx = context.WithValue(ctx, "user_roles", userRoles)

				var hasPermission bool
				if idx%3 == 0 {
					hasPermission = apigateway.HasRole(ctx, "admin")
				} else if idx%3 == 1 {
					hasPermission = apigateway.HasRole(ctx, "editor")
				} else {
					hasPermission = apigateway.HasRole(ctx, "viewer")
				}

				results <- hasPermission
			}(i)
		}

		wg.Wait()
		close(results)

		allTrue := true
		for r := range results {
			if !r {
				allTrue = false
				break
			}
		}
		assert.True(t, allTrue, "所有权限检查应该正确返回true")
	})
}

func TestGinContext_ConcurrentAccess(t *testing.T) {
	gin.SetMode(gin.TestMode)

	t.Run("在处理函数中并发访问Context不会panic", func(t *testing.T) {
		logger, _ := zap.NewDevelopment()
		db, _, cleanup := testutils.SetupTestDB(&apigateway.User{})
		defer cleanup()

		authService := apigateway.NewAuthService(db, logger, "test")
		rl := apigateway.NewRateLimiter(&apigateway.RateLimitConfig{
			Strategy: apigateway.StrategyTokenBucket,
			Limit:    1000,
			Burst:    1000,
		}, nil, logger)
		mw := apigateway.NewMiddleware(authService, rl, logger)

		r := gin.New()
		r.Use(mw.RequestID())
		r.GET("/test", func(c *gin.Context) {
			reqID := c.GetString("request_id")

			var wg sync.WaitGroup
			results := make([]string, 5)

			for i := 0; i < 5; i++ {
				wg.Add(1)
				go func(idx int) {
					defer wg.Done()
					time.Sleep(1 * time.Millisecond)
					results[idx] = reqID
				}(i)
			}

			wg.Wait()

			for _, r := range results {
				assert.Equal(t, reqID, r)
			}

			c.JSON(http.StatusOK, gin.H{"status": "ok"})
		})

		req, _ := http.NewRequest("GET", "/test", nil)
		w := httptest.NewRecorder()
		r.ServeHTTP(w, req)

		assert.Equal(t, http.StatusOK, w.Code)
	})
}

func TestRateLimiter_CustomLimiterCreation(t *testing.T) {
	logger, _ := zap.NewDevelopment()

	t.Run("创建新的限流器不会影响现有", func(t *testing.T) {
		rl := apigateway.NewRateLimiter(&apigateway.RateLimitConfig{
			Strategy: apigateway.StrategyTokenBucket,
			Limit:    10,
			Burst:    10,
		}, nil, logger)

		var wg sync.WaitGroup
		keys := make([]string, 100)
		for i := 0; i < 100; i++ {
			keys[i] = "key-" + string(rune('A'+i%26)) + string(rune('a'+i%26))
		}

		for _, key := range keys {
			wg.Add(1)
			go func(k string) {
				defer wg.Done()
				for j := 0; j < 5; j++ {
					rl.Allow(k, 1)
				}
			}(key)
		}

		wg.Wait()
	})
}

func TestMiddleware_CORSConcurrentRequests(t *testing.T) {
	gin.SetMode(gin.TestMode)
	logger, _ := zap.NewDevelopment()
	db, _, cleanup := testutils.SetupTestDB()
	defer cleanup()

	authService := apigateway.NewAuthService(db, logger, "test")
	rl := apigateway.NewRateLimiter(&apigateway.RateLimitConfig{
		Strategy: apigateway.StrategyTokenBucket,
		Limit:    1000,
	}, nil, logger)
	mw := apigateway.NewMiddleware(authService, rl, logger)

	r := gin.New()
	r.Use(mw.CORS())
	r.OPTIONS("/api", func(c *gin.Context) {
		c.Status(http.StatusOK)
	})
	r.GET("/api", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"data": "test"})
	})

	t.Run("并发CORS预检请求", func(t *testing.T) {
		var wg sync.WaitGroup
		concurrency := 50
		success := 0
		var mu sync.Mutex

		for i := 0; i < concurrency; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()

				req, _ := http.NewRequest("OPTIONS", "/api", nil)
				req.Header.Set("Origin", "http://example.com")
				req.Header.Set("Access-Control-Request-Method", "GET")

				w := httptest.NewRecorder()
				r.ServeHTTP(w, req)

				mu.Lock()
				defer mu.Unlock()
				if w.Code == http.StatusOK &&
					w.Header().Get("Access-Control-Allow-Origin") != "" {
					success++
				}
			}()
		}

		wg.Wait()
		assert.Equal(t, concurrency, success, "所有CORS预检请求应该成功")
	})

	t.Run("混合并发请求（OPTIONS+GET）", func(t *testing.T) {
		var wg sync.WaitGroup
		concurrency := 100
		success := 0
		var mu sync.Mutex

		for i := 0; i < concurrency; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()

				var req *http.Request
				if idx%2 == 0 {
					req, _ = http.NewRequest("OPTIONS", "/api", nil)
					req.Header.Set("Origin", "http://example.com")
				} else {
					req, _ = http.NewRequest("GET", "/api", nil)
				}

				w := httptest.NewRecorder()
				r.ServeHTTP(w, req)

				mu.Lock()
				defer mu.Unlock()
				if w.Code == http.StatusOK {
					success++
				}
			}(i)
		}

		wg.Wait()
		assert.Equal(t, concurrency, success, "所有混合请求应该成功")
	})
}
