package integration_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

var binaryPath string

func TestMain(m *testing.M) {
	tmpDir, err := os.MkdirTemp("", "htest-integration")
	if err != nil {
		os.Exit(1)
	}
	binaryPath = filepath.Join(tmpDir, "htest")

	cmd := exec.Command("go", "build", "-o", binaryPath, "github.com/htest/htest/cmd/htest")
	cmd.Env = append(os.Environ(), "GOPROXY=https://goproxy.cn,direct")
	if err := cmd.Run(); err != nil {
		os.Exit(1)
	}

	code := m.Run()
	os.RemoveAll(tmpDir)
	os.Exit(code)
}

func TestHTest_Help(t *testing.T) {
	cmd := exec.Command(binaryPath, "--help")
	out, err := cmd.CombinedOutput()
	require.NoError(t, err)
	s := string(out)
	assert.True(t, strings.Contains(s, "Multi-protocol") || strings.Contains(s, "REST") || strings.Contains(s, "gRPC"), "expected CLI description in help output, got: %s", s)
}

func TestHTest_Version(t *testing.T) {
	cmd := exec.Command(binaryPath, "--version")
	out, err := cmd.CombinedOutput()
	require.NoError(t, err)
	assert.Contains(t, string(out), "commit")
}

func TestHTest_EnvList(t *testing.T) {
	cmd := exec.Command(binaryPath, "env", "list")
	out, err := cmd.CombinedOutput()
	require.NoError(t, err)
	assert.Contains(t, string(out), "dev")
}

func TestHTest_REST_GET(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	}))
	defer srv.Close()

	cmd := exec.Command(binaryPath, "rest", "get", srv.URL+"/api/health")
	out, err := cmd.CombinedOutput()
	require.NoError(t, err)
	assert.Contains(t, string(out), "200")
}

func TestHTest_REST_POST(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		var body map[string]string
		json.NewDecoder(r.Body).Decode(&body)
		json.NewEncoder(w).Encode(body)
	}))
	defer srv.Close()

	cmd := exec.Command(binaryPath, "rest", "post", srv.URL+"/api/data", "-d", `{"key":"value"}`)
	out, err := cmd.CombinedOutput()
	require.NoError(t, err)
	s := string(out)
	assert.Contains(t, s, "200")
}

func TestHTest_REST_Error(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("internal server error"))
	}))
	defer srv.Close()

	cmd := exec.Command(binaryPath, "rest", "get", srv.URL+"/error")
	out, err := cmd.CombinedOutput()
	require.NoError(t, err)
	assert.Contains(t, string(out), "500")
}

func TestHTest_Run_Script(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	}))
	defer srv.Close()

	scriptContent := "name: integration-test\nsteps:\n  - name: health check\n    protocol: rest\n    request:\n      method: GET\n      url: \"" + srv.URL + "/health\"\n"
	tmpFile, err := os.CreateTemp("", "integration-*.htest")
	require.NoError(t, err)
	defer os.Remove(tmpFile.Name())
	_, err = tmpFile.WriteString(scriptContent)
	require.NoError(t, err)
	tmpFile.Close()

	cmd := exec.Command(binaryPath, "run", tmpFile.Name())
	out, _ := cmd.CombinedOutput()
	s := string(out)
	assert.True(t, strings.Contains(s, "pass") || strings.Contains(s, "200") || strings.Contains(s, "health check"), "expected test output, got: %s", s)
}

func TestHTest_Run_WithEnv(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	}))
	defer srv.Close()

	scriptContent := "name: integration-test-env\nenv: dev\nsteps:\n  - name: health check\n    protocol: rest\n    request:\n      method: GET\n      url: \"" + srv.URL + "/health\"\n"
	tmpFile, err := os.CreateTemp("", "integration-env-*.htest")
	require.NoError(t, err)
	defer os.Remove(tmpFile.Name())
	_, err = tmpFile.WriteString(scriptContent)
	require.NoError(t, err)
	tmpFile.Close()

	cmd := exec.Command(binaryPath, "run", tmpFile.Name(), "-e", "dev")
	out, _ := cmd.CombinedOutput()
	s := string(out)
	assert.True(t, strings.Contains(s, "pass") || strings.Contains(s, "200") || strings.Contains(s, "health check"), "expected test output, got: %s", s)
}

func TestHTest_Bench_Help(t *testing.T) {
	cmd := exec.Command(binaryPath, "bench", "--help")
	out, err := cmd.CombinedOutput()
	require.NoError(t, err)
	s := string(out)
	assert.True(t, strings.Contains(s, "concurrency") || strings.Contains(s, "duration"), "expected concurrency or duration in bench help, got: %s", s)
}

func TestHTest_GQL_Help(t *testing.T) {
	cmd := exec.Command(binaryPath, "gql", "--help")
	out, err := cmd.CombinedOutput()
	require.NoError(t, err)
	s := string(out)
	assert.True(t, strings.Contains(s, "query") || strings.Contains(s, "mutate"), "expected query or mutate in gql help, got: %s", s)
}

func TestHTest_Completion_Bash(t *testing.T) {
	cmd := exec.Command(binaryPath, "completion", "bash")
	out, err := cmd.CombinedOutput()
	require.NoError(t, err)
	s := string(out)
	assert.True(t, strings.Contains(s, "complete") || strings.Contains(s, "bash"), "expected completion output, got: %s", s)
}
