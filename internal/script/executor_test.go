package script

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/htest/htest/internal/config"
	"github.com/htest/htest/internal/env"
)

func testExecutorConfig(baseURL string) *config.Config {
	return &config.Config{
		DefaultEnv: "dev",
		Environments: map[string]config.EnvConfig{
			"dev": {
				BaseURL:   baseURL,
				Headers:   map[string]string{},
				Variables: map[string]string{},
			},
		},
		Variables: map[string]string{},
		Settings: config.Settings{Timeout: 5},
	}
}

func TestExecutor_SingleStep_StatusAssert(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"ok"}`))
	}))
	defer server.Close()

	cfg := testExecutorConfig(server.URL)
	envMgr := env.NewManager(cfg)
	executor := NewExecutor(envMgr)

	script := &TestScript{
		Name: "Status Assert Test",
		Steps: []Step{
			{
				Name:     "Check OK",
				Protocol: "rest",
				Request: RequestDef{
					Method: "GET",
					URL:    "/",
				},
				Assert: []AssertDef{
					{Type: "status", Expected: 200},
				},
			},
		},
	}

	result, err := executor.Execute(context.Background(), script)
	require.NoError(t, err)
	assert.Equal(t, "pass", result.Status)
	assert.Equal(t, "pass", result.Steps[0].Status)
}

func TestExecutor_ExtractHeader(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Auth-Token", "test-token-123")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{}`))
	}))
	defer server.Close()

	cfg := testExecutorConfig(server.URL)
	envMgr := env.NewManager(cfg)
	executor := NewExecutor(envMgr)

	script := &TestScript{
		Name: "Header Extract Test",
		Steps: []Step{
			{
				Name:     "Get Token",
				Protocol: "rest",
				Request: RequestDef{
					Method: "GET",
					URL:    "/auth",
				},
				Extract: map[string]ExtractDef{
					"auth_token": {
						From:   "header",
						Header: "X-Auth-Token",
					},
				},
			},
		},
	}

	result, err := executor.Execute(context.Background(), script)
	require.NoError(t, err)
	assert.Equal(t, "pass", result.Status)
	assert.Equal(t, "test-token-123", result.Steps[0].Extracted["auth_token"])
}

func TestExecutor_ExtractJSONPath(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"id":42,"name":"test"}`))
	}))
	defer server.Close()

	cfg := testExecutorConfig(server.URL)
	envMgr := env.NewManager(cfg)
	executor := NewExecutor(envMgr)

	script := &TestScript{
		Name: "JSONPath Extract Test",
		Steps: []Step{
			{
				Name:     "Get Data",
				Protocol: "rest",
				Request: RequestDef{
					Method: "GET",
					URL:    "/data",
				},
				Extract: map[string]ExtractDef{
					"item_id": {
						From:     "json",
						JSONPath: "$.id",
					},
				},
			},
		},
	}

	result, err := executor.Execute(context.Background(), script)
	require.NoError(t, err)
	assert.Equal(t, "pass", result.Status)
	assert.Equal(t, "42", result.Steps[0].Extracted["item_id"])
}

func TestExecutor_ChainWithVariable(t *testing.T) {
	var requestedPath string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requestedPath = r.URL.Path
		if r.URL.Path == "/items/42" {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"detail":"found"}`))
		} else {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"id":42}`))
		}
	}))
	defer server.Close()

	cfg := testExecutorConfig(server.URL)
	envMgr := env.NewManager(cfg)
	executor := NewExecutor(envMgr)

	script := &TestScript{
		Name: "Chain Variable Test",
		Steps: []Step{
			{
				Name:     "Get ID",
				Protocol: "rest",
				Request: RequestDef{
					Method: "GET",
					URL:    "/first",
				},
				Extract: map[string]ExtractDef{
					"item_id": {
						From:     "json",
						JSONPath: "$.id",
					},
				},
			},
			{
				Name:     "Use ID",
				Protocol: "rest",
				Request: RequestDef{
					Method: "GET",
					URL:    "/items/${item_id}",
				},
			},
		},
	}

	result, err := executor.Execute(context.Background(), script)
	require.NoError(t, err)
	assert.Equal(t, "pass", result.Status)
	assert.Equal(t, "/items/42", requestedPath)
	assert.Equal(t, "42", result.Variables["item_id"])
}

func TestExecutor_FailedAssert(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte(`{"error":"internal"}`))
	}))
	defer server.Close()

	cfg := testExecutorConfig(server.URL)
	envMgr := env.NewManager(cfg)
	executor := NewExecutor(envMgr)

	script := &TestScript{
		Name: "Failed Assert Test",
		Steps: []Step{
			{
				Name:     "Expect 200",
				Protocol: "rest",
				Request: RequestDef{
					Method: "GET",
					URL:    "/fail",
				},
				Assert: []AssertDef{
					{Type: "status", Expected: 200},
				},
			},
		},
	}

	result, err := executor.Execute(context.Background(), script)
	require.NoError(t, err)
	assert.Equal(t, "fail", result.Status)
	require.Len(t, result.Steps[0].Assertions, 1)
	assert.Contains(t, result.Steps[0].Assertions[0].Message, "expected 200")
}

func TestPipelineContext_ResolveVars(t *testing.T) {
	cfg := testExecutorConfig("http://localhost")
	envMgr := env.NewManager(cfg)
	pc := NewPipelineContext(envMgr)

	pc.SetVar("host", "example.com")
	pc.SetVar("port", "8080")

	assert.Equal(t, "example.com:8080", pc.Resolve("${host}:${port}"))
	assert.Equal(t, "example.com:8080", pc.Resolve("{{.host}}:{{.port}}"))
	assert.Equal(t, "example.com:8080", pc.Resolve("${host}:{{.port}}"))
}
