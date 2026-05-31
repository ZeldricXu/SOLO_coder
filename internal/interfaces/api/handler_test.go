package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/solocoder/session148/internal/application"
	"github.com/solocoder/session148/internal/domain"
	"github.com/solocoder/session148/internal/infrastructure/logging"
	"github.com/solocoder/session148/internal/infrastructure/storage"
)

func init() {
	gin.SetMode(gin.TestMode)
}

func setupTestApp(t *testing.T) (*gin.Engine, *application.AppService, string) {
	tmpDir := t.TempDir()

	logger, err := logging.NewZapLogger(logging.LoggerConfig{
		Level:  "error",
		Dir:    filepath.Join(tmpDir, "logs"),
		Enable: false,
	})
	require.NoError(t, err)

	storageMgr, err := storage.NewLocalStorageManager(storage.StorageConfig{
		BackupDir: filepath.Join(tmpDir, "backups"),
		Logger:    logger,
		CacheConfig: storage.CacheConfig{
			MaxEntries:      100,
			MaxSizeBytes:    50 * 1024 * 1024,
			TTL:             time.Hour,
			WarmupOnStartup: false,
		},
	})
	require.NoError(t, err)

	processor := application.NewDataProcessorService(logger)

	appService := application.NewAppService(
		logger,
		storageMgr,
		processor,
		nil,
		nil,
		nil,
		nil,
		nil,
		nil,
	)

	handler := NewAPIHandler(appService, logger)
	router := SetupRouter(handler)

	return router, appService, tmpDir
}

func makeRequest(t *testing.T, router *gin.Engine, method, path string, body interface{}) *httptest.ResponseRecorder {
	var jsonBody []byte
	var err error

	if body != nil {
		jsonBody, err = json.Marshal(body)
		require.NoError(t, err)
	}

	req := httptest.NewRequest(method, path, bytes.NewBuffer(jsonBody))
	req.Header.Set("Content-Type", "application/json")

	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)

	return w
}

type APIResponse struct {
	Code    int         `json:"code"`
	Data    interface{} `json:"data,omitempty"`
	Error   string      `json:"error,omitempty"`
	Details string      `json:"details,omitempty"`
}

func parseResponse(t *testing.T, w *httptest.ResponseRecorder) APIResponse {
	var resp APIResponse
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	return resp
}

func TestHealthCheck(t *testing.T) {
	router, _, _ := setupTestApp(t)

	w := makeRequest(t, router, "GET", "/health", nil)

	assert.Equal(t, http.StatusOK, w.Code)
	resp := parseResponse(t, w)
	assert.Equal(t, 200, resp.Code)
}

func TestCreateResource(t *testing.T) {
	t.Run("should create resource successfully", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		body := CreateResourceRequest{
			Type: "test_type",
			Config: map[string]interface{}{
				"key": "value",
			},
			Labels: map[string]string{
				"env": "test",
			},
		}

		w := makeRequest(t, router, "POST", "/api/v1/resources", body)

		assert.Equal(t, http.StatusCreated, w.Code)
		resp := parseResponse(t, w)
		assert.Equal(t, 201, resp.Code)

		dataMap, ok := resp.Data.(map[string]interface{})
		require.True(t, ok)
		assert.NotEmpty(t, dataMap["id"])
		assert.NotEmpty(t, dataMap["status"])
	})

	t.Run("should return error for missing type", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		body := map[string]interface{}{
			"config": map[string]interface{}{"key": "value"},
		}

		w := makeRequest(t, router, "POST", "/api/v1/resources", body)

		assert.Equal(t, http.StatusBadRequest, w.Code)
		resp := parseResponse(t, w)
		assert.Equal(t, 400, resp.Code)
		assert.NotEmpty(t, resp.Error)
	})

	t.Run("should return error for invalid JSON", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		req := httptest.NewRequest("POST", "/api/v1/resources", bytes.NewBuffer([]byte("invalid json")))
		req.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		router.ServeHTTP(w, req)

		assert.Equal(t, http.StatusBadRequest, w.Code)
	})
}

func TestGetResourceStatus(t *testing.T) {
	t.Run("should return 404 for nonexistent resource", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		w := makeRequest(t, router, "GET", "/api/v1/resources/nonexistent/status", nil)

		assert.Equal(t, http.StatusNotFound, w.Code)
	})
}

func TestProcessData(t *testing.T) {
	t.Run("should process data successfully", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		body := ProcessRequest{
			Namespace: "test_ns",
			Payload: map[string]interface{}{
				"field1": "value1",
				"field2": 123,
			},
			UserID: "test_user",
		}

		w := makeRequest(t, router, "POST", "/api/v1/process", body)

		assert.Equal(t, http.StatusOK, w.Code)
		resp := parseResponse(t, w)
		assert.Equal(t, 200, resp.Code)
	})

	t.Run("should return error for missing payload", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		body := map[string]interface{}{
			"namespace": "test_ns",
		}

		w := makeRequest(t, router, "POST", "/api/v1/process", body)

		assert.Equal(t, http.StatusBadRequest, w.Code)
	})

	t.Run("should handle large payload", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		largePayload := make(map[string]interface{})
		for i := 0; i < 100; i++ {
			largePayload[printf("field%d", i)] = printf("value_%d", i)
		}

		body := ProcessRequest{
			Namespace: "test_ns",
			Payload:   largePayload,
			UserID:    "test_user",
		}

		w := makeRequest(t, router, "POST", "/api/v1/process", body)

		assert.Equal(t, http.StatusOK, w.Code)
		resp := parseResponse(t, w)
		assert.Equal(t, 200, resp.Code)
	})
}

func TestBackupAPI(t *testing.T) {
	t.Run("should list backups (empty)", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		w := makeRequest(t, router, "GET", "/api/v1/backups", nil)

		assert.Equal(t, http.StatusOK, w.Code)
		resp := parseResponse(t, w)
		assert.Equal(t, 200, resp.Code)
	})

	t.Run("should restore backup - bad request", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		body := map[string]interface{}{
			"backup_id": "test_id",
		}

		w := makeRequest(t, router, "POST", "/api/v1/backups/restore", body)

		assert.Equal(t, http.StatusBadRequest, w.Code)
	})

	t.Run("should restore backup - nonexistent backup", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		body := RestoreRequest{
			BackupID: "nonexistent_id",
			Dest:     "/tmp/restore",
		}

		w := makeRequest(t, router, "POST", "/api/v1/backups/restore", body)

		assert.Equal(t, http.StatusInternalServerError, w.Code)
	})
}

func TestSchemaAPI(t *testing.T) {
	t.Run("should get schema version", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		w := makeRequest(t, router, "GET", "/api/v1/schema/version", nil)

		assert.Equal(t, http.StatusOK, w.Code)
		resp := parseResponse(t, w)
		assert.Equal(t, 200, resp.Code)
	})

	t.Run("should migrate schema", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		body := MigrateRequest{
			TargetVersion: 2,
		}

		w := makeRequest(t, router, "POST", "/api/v1/schema/migrate", body)

		assert.Equal(t, http.StatusOK, w.Code)
		resp := parseResponse(t, w)
		assert.Equal(t, 200, resp.Code)
	})

	t.Run("should return error for invalid migrate request", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		body := map[string]interface{}{}

		w := makeRequest(t, router, "POST", "/api/v1/schema/migrate", body)

		assert.Equal(t, http.StatusBadRequest, w.Code)
	})
}

func TestAuditAPI(t *testing.T) {
	t.Run("should verify audit integrity", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		w := makeRequest(t, router, "GET", "/api/v1/audit/verify", nil)

		assert.Equal(t, http.StatusOK, w.Code)
		resp := parseResponse(t, w)
		assert.Equal(t, 200, resp.Code)

		dataMap, ok := resp.Data.(map[string]interface{})
		require.True(t, ok)
		assert.NotNil(t, dataMap["valid"])
	})
}

func TestMetricsAPI(t *testing.T) {
	t.Run("should get metrics snapshot", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		w := makeRequest(t, router, "GET", "/api/v1/metrics", nil)

		assert.Equal(t, http.StatusOK, w.Code)
		resp := parseResponse(t, w)
		assert.Equal(t, 200, resp.Code)
	})
}

func TestMaskedDataAPI(t *testing.T) {
	t.Run("should return error for invalid request", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		body := map[string]interface{}{
			"record_id": "test_id",
		}

		w := makeRequest(t, router, "POST", "/api/v1/data/masked", body)

		assert.Equal(t, http.StatusBadRequest, w.Code)
	})
}

func TestBatchAPI(t *testing.T) {
	t.Run("should return error for empty operations", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		body := BatchRequest{
			Operations: []application.BatchOperation{},
		}

		w := makeRequest(t, router, "POST", "/api/v1/resources/batch", body)

		assert.Equal(t, http.StatusOK, w.Code)
	})

	t.Run("should return error for missing operations", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		body := map[string]interface{}{}

		w := makeRequest(t, router, "POST", "/api/v1/resources/batch", body)

		assert.Equal(t, http.StatusBadRequest, w.Code)
	})
}

func TestRunStatusAPI(t *testing.T) {
	t.Run("should return 404 for nonexistent run", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		w := makeRequest(t, router, "GET", "/api/v1/runs/nonexistent", nil)

		assert.Equal(t, http.StatusNotFound, w.Code)
	})
}

func TestConcurrentAPIRequests(t *testing.T) {
	router, _, _ := setupTestApp(t)

	const goroutines = 30
	var wg sync.WaitGroup
	wg.Add(goroutines)

	var errorCount int32
	var successCount int32

	for i := 0; i < goroutines; i++ {
		go func(id int) {
			defer wg.Done()

			body := ProcessRequest{
				Namespace: "concurrent_ns",
				Payload: map[string]interface{}{
					"id":    id,
					"value": printf("data_%d", id),
				},
				UserID: "test_user",
			}

			w := makeRequest(t, router, "POST", "/api/v1/process", body)

			if w.Code == http.StatusOK {
				atomic.AddInt32(&successCount, 1)
			} else {
				atomic.AddInt32(&errorCount, 1)
			}
		}(i)
	}

	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()

	select {
	case <-done:
		assert.Equal(t, int32(0), errorCount, "no errors during concurrent requests")
		assert.Equal(t, int32(goroutines), successCount)
	case <-time.After(30 * time.Second):
		t.Fatal("timeout waiting for concurrent requests to complete")
	}
}

func TestAPIBoundaryConditions(t *testing.T) {
	t.Run("empty string fields", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		body := ProcessRequest{
			Namespace: "",
			Payload: map[string]interface{}{
				"key": "value",
			},
			UserID: "",
		}

		w := makeRequest(t, router, "POST", "/api/v1/process", body)

		assert.Equal(t, http.StatusOK, w.Code)
	})

	t.Run("special characters in input", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		body := ProcessRequest{
			Namespace: "test<>!@#$%^&*()",
			Payload: map[string]interface{}{
				"special": "值包含中文和特殊字符!@#$%",
			},
			UserID: "user_123",
		}

		w := makeRequest(t, router, "POST", "/api/v1/process", body)

		assert.Equal(t, http.StatusOK, w.Code)
	})

	t.Run("very long string input", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		longString := ""
		for i := 0; i < 10000; i++ {
			longString += "a"
		}

		body := ProcessRequest{
			Namespace: longString[:50],
			Payload: map[string]interface{}{
				"long_field": longString,
			},
			UserID: "test_user",
		}

		w := makeRequest(t, router, "POST", "/api/v1/process", body)

		assert.Equal(t, http.StatusOK, w.Code)
	})

	t.Run("nil values in payload", func(t *testing.T) {
		router, _, _ := setupTestApp(t)

		body := ProcessRequest{
			Namespace: "test_ns",
			Payload: map[string]interface{}{
				"nil_field":   nil,
				"empty_array": []interface{}{},
				"empty_map":   map[string]interface{}{},
			},
			UserID: "test_user",
		}

		w := makeRequest(t, router, "POST", "/api/v1/process", body)

		assert.Equal(t, http.StatusOK, w.Code)
	})
}

func TestAPIResponseStructure(t *testing.T) {
	router, _, _ := setupTestApp(t)

	w := makeRequest(t, router, "GET", "/health", nil)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, "application/json; charset=utf-8", w.Header().Get("Content-Type"))

	var response map[string]interface{}
	err := json.Unmarshal(w.Body.Bytes(), &response)
	require.NoError(t, err)

	assert.Contains(t, response, "code")
	assert.Contains(t, response, "status")
	assert.Contains(t, response, "version")
}

func printf(format string, a ...interface{}) string {
	return fmt.Sprintf(format, a...)
}
