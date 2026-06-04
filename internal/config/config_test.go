package config

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestDefaultConfig(t *testing.T) {
	cfg := DefaultConfig()
	assert.Equal(t, "dev", cfg.DefaultEnv)
	assert.Equal(t, 30, cfg.Settings.Timeout)
	assert.False(t, cfg.Settings.TLSSkipVerify)
	assert.Equal(t, "pretty", cfg.Settings.OutputFormat)
	assert.NotEmpty(t, cfg.Environments)
	_, ok := cfg.Environments["dev"]
	assert.True(t, ok)
	_, ok = cfg.Environments["staging"]
	assert.True(t, ok)
	_, ok = cfg.Environments["prod"]
	assert.True(t, ok)
}

func TestLoad_NotFound(t *testing.T) {
	cfg, err := Load("/nonexistent/path/config.yaml")
	require.NoError(t, err)
	assert.NotNil(t, cfg)
	assert.Equal(t, "dev", cfg.DefaultEnv)
}

func TestLoad_ValidFile(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "htest-config-test")
	require.NoError(t, err)
	defer os.RemoveAll(tmpDir)

	configContent := `
default_env: staging
settings:
  timeout: 60
  tls_skip_verify: true
  output_format: json
environments:
  staging:
    base_url: "https://staging.example.com"
    token: "test-token"
    headers:
      X-Custom: "value"
    variables:
      region: "us-east-1"
variables:
  app: "testapp"
`
	configPath := filepath.Join(tmpDir, "config.yaml")
	err = os.WriteFile(configPath, []byte(configContent), 0644)
	require.NoError(t, err)

	cfg, err := Load(configPath)
	require.NoError(t, err)
	assert.Equal(t, "staging", cfg.DefaultEnv)
	assert.Equal(t, 60, cfg.Settings.Timeout)
	assert.True(t, cfg.Settings.TLSSkipVerify)
	assert.Equal(t, "json", cfg.Settings.OutputFormat)
	assert.Equal(t, "https://staging.example.com", cfg.Environments["staging"].BaseURL)
	assert.Equal(t, "test-token", cfg.Environments["staging"].Token)
	assert.Equal(t, "value", cfg.Environments["staging"].Headers["x-custom"])
	assert.Equal(t, "us-east-1", cfg.Environments["staging"].Variables["region"])
	assert.Equal(t, "testapp", cfg.Variables["app"])
}

func TestLoad_APICALL_ENV(t *testing.T) {
	os.Setenv("APICALL_ENV", "prod")
	defer os.Unsetenv("APICALL_ENV")

	cfg, err := Load("/nonexistent/path/config.yaml")
	require.NoError(t, err)
	assert.Equal(t, "prod", cfg.DefaultEnv)
}

func TestLoad_APICALL_TOKEN(t *testing.T) {
	os.Setenv("APICALL_TOKEN", "env-override-token")
	defer os.Unsetenv("APICALL_TOKEN")

	cfg := DefaultConfig()
	cfg, err := Load("/nonexistent/path/config.yaml")
	require.NoError(t, err)
	assert.Equal(t, "env-override-token", cfg.Environments["dev"].Token)
}

func TestSave(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "htest-config-save")
	require.NoError(t, err)
	defer os.RemoveAll(tmpDir)

	cfg := DefaultConfig()
	configPath := filepath.Join(tmpDir, "config.yaml")

	err = Save(cfg, configPath)
	require.NoError(t, err)

	_, err = os.Stat(configPath)
	assert.NoError(t, err)

	loaded, err := Load(configPath)
	require.NoError(t, err)
	assert.Equal(t, cfg.DefaultEnv, loaded.DefaultEnv)
}

func TestSettings_Defaults(t *testing.T) {
	cfg := DefaultConfig()
	assert.Equal(t, 30, cfg.Settings.Timeout)
	assert.False(t, cfg.Settings.TLSSkipVerify)
	assert.Equal(t, "pretty", cfg.Settings.OutputFormat)
}
