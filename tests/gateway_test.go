package tests

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"session130/internal/gateway"
)

func TestRateLimiter_Allow_Success(t *testing.T) {
	rl := gateway.NewRateLimiter(5, time.Minute)

	for i := 0; i < 5; i++ {
		assert.True(t, rl.Allow("client1"), "Request %d should be allowed", i+1)
	}
}

func TestRateLimiter_Allow_ExceedLimit(t *testing.T) {
	rl := gateway.NewRateLimiter(3, time.Minute)

	for i := 0; i < 3; i++ {
		assert.True(t, rl.Allow("client1"))
	}

	assert.False(t, rl.Allow("client1"), "4th request should be rate limited")
	assert.False(t, rl.Allow("client1"), "5th request should be rate limited")
}

func TestRateLimiter_Allow_DifferentKeys(t *testing.T) {
	rl := gateway.NewRateLimiter(2, time.Minute)

	assert.True(t, rl.Allow("client1"))
	assert.True(t, rl.Allow("client1"))
	assert.False(t, rl.Allow("client1"))

	assert.True(t, rl.Allow("client2"))
	assert.True(t, rl.Allow("client2"))
	assert.False(t, rl.Allow("client2"))
}

func TestRateLimiter_Allow_WindowReset(t *testing.T) {
	rl := gateway.NewRateLimiter(2, 50*time.Millisecond)

	assert.True(t, rl.Allow("client1"))
	assert.True(t, rl.Allow("client1"))
	assert.False(t, rl.Allow("client1"))

	time.Sleep(60 * time.Millisecond)

	assert.True(t, rl.Allow("client1"), "After window reset, requests should be allowed again")
}

func TestRateLimiter_Allow_ZeroLimit(t *testing.T) {
	rl := gateway.NewRateLimiter(0, time.Minute)

	assert.False(t, rl.Allow("client1"), "Zero limit should reject all requests")
}

func TestRateLimiter_Allow_NegativeLimit(t *testing.T) {
	rl := gateway.NewRateLimiter(-1, time.Minute)

	assert.False(t, rl.Allow("client1"), "Negative limit should reject all requests")
}

func TestGateway_Middleware_TraceIDGeneration(t *testing.T) {
	gw := gateway.NewGateway()
	defer func() {}()

	handler := func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("OK"))
	}
	gw.RegisterHandler("/test", handler)

	req := httptest.NewRequest("GET", "/test", nil)
	w := httptest.NewRecorder()

	gw.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.NotEmpty(t, w.Header().Get("X-Trace-ID"))
	assert.NotEmpty(t, w.Header().Get("X-Request-ID"))
	assert.Equal(t, w.Header().Get("X-Trace-ID"), w.Header().Get("X-Request-ID"))
}

func TestGateway_Middleware_ExistingTraceID(t *testing.T) {
	gw := gateway.NewGateway()

	handler := func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("OK"))
	}
	gw.RegisterHandler("/test", handler)

	existingTraceID := "test-trace-id-123"
	req := httptest.NewRequest("GET", "/test", nil)
	req.Header.Set("X-Trace-ID", existingTraceID)
	w := httptest.NewRecorder()

	gw.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, existingTraceID, w.Header().Get("X-Trace-ID"))
}

func TestGateway_Middleware_RateLimiting(t *testing.T) {
	gw := gateway.NewGateway()

	handler := func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("OK"))
	}
	gw.RegisterHandler("/test", handler)

	for i := 0; i < 1000; i++ {
		req := httptest.NewRequest("GET", "/test", nil)
		req.RemoteAddr = "192.168.1.1:12345"
		w := httptest.NewRecorder()
		gw.ServeHTTP(w, req)

		if i >= 1000 {
			assert.Equal(t, http.StatusTooManyRequests, w.Code, "Request %d should be rate limited", i+1)
		}
	}
}

func TestGateway_ServeHTTP_NotFound(t *testing.T) {
	gw := gateway.NewGateway()

	req := httptest.NewRequest("GET", "/nonexistent", nil)
	w := httptest.NewRecorder()

	gw.ServeHTTP(w, req)

	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestGateway_ServeHTTP_HandlerFound(t *testing.T) {
	gw := gateway.NewGateway()

	handler := func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	}
	gw.RegisterHandler("/api/v1/test", handler)

	req := httptest.NewRequest("GET", "/api/v1/test", nil)
	w := httptest.NewRecorder()

	gw.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, "application/json", w.Header().Get("Content-Type"))

	var response map[string]string
	err := json.NewDecoder(w.Body).Decode(&response)
	require.NoError(t, err)
	assert.Equal(t, "ok", response["status"])
}

func TestGateway_Middleware_DifferentHTTPMethods(t *testing.T) {
	gw := gateway.NewGateway()

	methods := []string{"GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"}
	for _, method := range methods {
		t.Run(method, func(t *testing.T) {
			handler := func(w http.ResponseWriter, r *http.Request) {
				assert.Equal(t, method, r.Method)
				w.WriteHeader(http.StatusOK)
			}
			gw.RegisterHandler("/test-"+method, handler)

			req := httptest.NewRequest(method, "/test-"+method, nil)
			w := httptest.NewRecorder()

			gw.ServeHTTP(w, req)
			assert.Equal(t, http.StatusOK, w.Code)
		})
	}
}

func TestGateway_Middleware_RequestWithBody(t *testing.T) {
	gw := gateway.NewGateway()

	type RequestBody struct {
		Name  string `json:"name"`
		Value int    `json:"value"`
	}

	handler := func(w http.ResponseWriter, r *http.Request) {
		var body RequestBody
		err := json.NewDecoder(r.Body).Decode(&body)
		if err != nil {
			http.Error(w, "invalid request body", http.StatusBadRequest)
			return
		}
		assert.Equal(t, "test", body.Name)
		assert.Equal(t, 42, body.Value)
		w.WriteHeader(http.StatusCreated)
	}
	gw.RegisterHandler("/api/v1/resource", handler)

	body := RequestBody{Name: "test", Value: 42}
	bodyBytes, _ := json.Marshal(body)
	req := httptest.NewRequest("POST", "/api/v1/resource", bytes.NewBuffer(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	gw.ServeHTTP(w, req)
	assert.Equal(t, http.StatusCreated, w.Code)
}

func TestGateway_Middleware_ResponseSizeTracking(t *testing.T) {
	gw := gateway.NewGateway()

	responseContent := "This is a test response with known length"
	handler := func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(responseContent))
	}
	gw.RegisterHandler("/test", handler)

	req := httptest.NewRequest("GET", "/test", nil)
	w := httptest.NewRecorder()

	gw.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, len(responseContent), w.Body.Len())
}

func TestGateway_Middleware_PanicRecovery(t *testing.T) {
	gw := gateway.NewGateway()

	handler := func(w http.ResponseWriter, r *http.Request) {
		panic("test panic in handler")
	}
	gw.RegisterHandler("/panic", handler)

	req := httptest.NewRequest("GET", "/panic", nil)
	w := httptest.NewRecorder()

	gw.ServeHTTP(w, req)

	assert.Equal(t, http.StatusInternalServerError, w.Code)
	assert.Contains(t, w.Body.String(), "internal server error")
}

func TestGateway_Middleware_QueryParameters(t *testing.T) {
	gw := gateway.NewGateway()

	handler := func(w http.ResponseWriter, r *http.Request) {
		query := r.URL.Query()
		assert.Equal(t, "value1", query.Get("key1"))
		assert.Equal(t, "value2", query.Get("key2"))
		w.WriteHeader(http.StatusOK)
	}
	gw.RegisterHandler("/api/search", handler)

	req := httptest.NewRequest("GET", "/api/search?key1=value1&key2=value2", nil)
	w := httptest.NewRecorder()

	gw.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestGateway_Middleware_RequestHeaders(t *testing.T) {
	gw := gateway.NewGateway()

	handler := func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "Bearer token123", r.Header.Get("Authorization"))
		assert.Equal(t, "application/json", r.Header.Get("Accept"))
		w.WriteHeader(http.StatusOK)
	}
	gw.RegisterHandler("/api/protected", handler)

	req := httptest.NewRequest("GET", "/api/protected", nil)
	req.Header.Set("Authorization", "Bearer token123")
	req.Header.Set("Accept", "application/json")
	w := httptest.NewRecorder()

	gw.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestGateway_Middleware_EmptyRequestBody(t *testing.T) {
	gw := gateway.NewGateway()

	handler := func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		assert.Empty(t, body)
		w.WriteHeader(http.StatusOK)
	}
	gw.RegisterHandler("/api/empty", handler)

	req := httptest.NewRequest("POST", "/api/empty", nil)
	w := httptest.NewRecorder()

	gw.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestGateway_Middleware_LargeRequestBody(t *testing.T) {
	gw := gateway.NewGateway()

	handler := func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		assert.Equal(t, 10000, len(body))
		w.WriteHeader(http.StatusOK)
	}
	gw.RegisterHandler("/api/large", handler)

	largeBody := make([]byte, 10000)
	for i := range largeBody {
		largeBody[i] = 'a'
	}
	req := httptest.NewRequest("POST", "/api/large", bytes.NewBuffer(largeBody))
	req.Header.Set("Content-Type", "application/octet-stream")
	w := httptest.NewRecorder()

	gw.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestGateway_Middleware_MultipleRequests(t *testing.T) {
	gw := gateway.NewGateway()

	requestCount := 0
	handler := func(w http.ResponseWriter, r *http.Request) {
		requestCount++
		w.WriteHeader(http.StatusOK)
	}
	gw.RegisterHandler("/api/counter", handler)

	for i := 0; i < 100; i++ {
		req := httptest.NewRequest("GET", "/api/counter", nil)
		w := httptest.NewRecorder()
		gw.ServeHTTP(w, req)
		assert.Equal(t, http.StatusOK, w.Code)
	}

	assert.Equal(t, 100, requestCount)
}

func TestGateway_RegisterHandler_Overwrite(t *testing.T) {
	gw := gateway.NewGateway()

	handler1 := func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("handler1"))
	}
	gw.RegisterHandler("/test", handler1)

	handler2 := func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("handler2"))
	}
	gw.RegisterHandler("/test", handler2)

	req := httptest.NewRequest("GET", "/test", nil)
	w := httptest.NewRecorder()
	gw.ServeHTTP(w, req)

	assert.Equal(t, "handler2", w.Body.String())
}

func TestGateway_GetObservabilityStats(t *testing.T) {
	gw := gateway.NewGateway()

	stats := gw.GetObservabilityStats()

	assert.NotNil(t, stats)
	assert.Contains(t, stats, "uptime_seconds")
	assert.Contains(t, stats, "start_time")
	assert.Contains(t, stats, "metrics")
	assert.Contains(t, stats, "tracing")
	assert.Contains(t, stats, "logging")
	assert.True(t, stats["metrics"].(bool))
	assert.True(t, stats["tracing"].(bool))
	assert.True(t, stats["logging"].(bool))
}

func TestGateway_SetObservability(t *testing.T) {
	gw := gateway.NewGateway()

	gw.SetObservability(false, false, false)

	stats := gw.GetObservabilityStats()
	assert.False(t, stats["metrics"].(bool))
	assert.False(t, stats["tracing"].(bool))
	assert.False(t, stats["logging"].(bool))

	gw.SetObservability(true, true, true)

	stats = gw.GetObservabilityStats()
	assert.True(t, stats["metrics"].(bool))
	assert.True(t, stats["tracing"].(bool))
	assert.True(t, stats["logging"].(bool))
}

func TestGateway_Middleware_UserAgent(t *testing.T) {
	gw := gateway.NewGateway()

	handler := func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "TestUserAgent/1.0", r.UserAgent())
		w.WriteHeader(http.StatusOK)
	}
	gw.RegisterHandler("/test", handler)

	req := httptest.NewRequest("GET", "/test", nil)
	req.Header.Set("User-Agent", "TestUserAgent/1.0")
	w := httptest.NewRecorder()

	gw.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestGateway_Middleware_RemoteAddr(t *testing.T) {
	gw := gateway.NewGateway()

	handler := func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "10.0.0.1:54321", r.RemoteAddr)
		w.WriteHeader(http.StatusOK)
	}
	gw.RegisterHandler("/test", handler)

	req := httptest.NewRequest("GET", "/test", nil)
	req.RemoteAddr = "10.0.0.1:54321"
	w := httptest.NewRecorder()

	gw.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestGateway_Middleware_ConcurrentRequests(t *testing.T) {
	gw := gateway.NewGateway()

	handler := func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(10 * time.Millisecond)
		w.WriteHeader(http.StatusOK)
	}
	gw.RegisterHandler("/api/concurrent", handler)

	var wg sync.WaitGroup
	numRequests := 50

	for i := 0; i < numRequests; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			req := httptest.NewRequest("GET", "/api/concurrent", nil)
			w := httptest.NewRecorder()
			gw.ServeHTTP(w, req)
			assert.Equal(t, http.StatusOK, w.Code)
		}()
	}

	wg.Wait()
}

func TestGateway_Middleware_QueryParamsEncoding(t *testing.T) {
	gw := gateway.NewGateway()

	handler := func(w http.ResponseWriter, r *http.Request) {
		query := r.URL.Query()
		assert.Equal(t, "hello world", query.Get("message"))
		assert.Equal(t, "test&value", query.Get("special"))
		w.WriteHeader(http.StatusOK)
	}
	gw.RegisterHandler("/api/encoded", handler)

	req := httptest.NewRequest("GET", "/api/encoded?message=hello%20world&special=test%26value", nil)
	w := httptest.NewRecorder()

	gw.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestGateway_Middleware_CustomHeaders(t *testing.T) {
	gw := gateway.NewGateway()

	handler := func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Custom-Header", "custom-value")
		w.Header().Set("X-Request-Processed", "true")
		w.WriteHeader(http.StatusAccepted)
	}
	gw.RegisterHandler("/api/custom", handler)

	req := httptest.NewRequest("GET", "/api/custom", nil)
	w := httptest.NewRecorder()

	gw.ServeHTTP(w, req)

	assert.Equal(t, http.StatusAccepted, w.Code)
	assert.Equal(t, "custom-value", w.Header().Get("X-Custom-Header"))
	assert.Equal(t, "true", w.Header().Get("X-Request-Processed"))
	assert.NotEmpty(t, w.Header().Get("X-Trace-ID"))
}

func TestGateway_Middleware_EmptyQueryString(t *testing.T) {
	gw := gateway.NewGateway()

	handler := func(w http.ResponseWriter, r *http.Request) {
		assert.Empty(t, r.URL.RawQuery)
		w.WriteHeader(http.StatusOK)
	}
	gw.RegisterHandler("/api/noquery", handler)

	req := httptest.NewRequest("GET", "/api/noquery", nil)
	w := httptest.NewRecorder()

	gw.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}
