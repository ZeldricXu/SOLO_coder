package cli

import (
	"bytes"
	"os"
	"strings"
	"testing"

	"github.com/htest/htest/internal/config"
	"github.com/htest/htest/internal/env"
	"github.com/htest/htest/internal/output"
	"github.com/stretchr/testify/assert"
)

func captureOutput(f func()) string {
	old := os.Stdout
	r, w, _ := os.Pipe()
	os.Stdout = w
	f()
	w.Close()
	os.Stdout = old
	var buf bytes.Buffer
	buf.ReadFrom(r)
	return buf.String()
}

func setupApp() {
	cfg := &config.Config{
		DefaultEnv: "dev",
		Environments: map[string]config.EnvConfig{
			"dev":     {BaseURL: "http://localhost:8080", Token: "test-token", Headers: map[string]string{}, Variables: map[string]string{}},
			"staging": {BaseURL: "https://staging.example.com", Token: "staging-token", Headers: map[string]string{}, Variables: map[string]string{}},
		},
		Variables: map[string]string{},
		Settings: config.Settings{Timeout: 30},
	}
	envMgr := env.NewManager(cfg)
	out := output.NewFormatter("pretty", os.Stdout)
	AppInstance = &App{Config: cfg, EnvMgr: envMgr, Out: out}
}

func TestHandleREPLEnv_List(t *testing.T) {
	setupApp()
	output := captureOutput(func() {
		handleREPLEnv([]string{"env", "list"})
	})
	assert.Contains(t, output, "dev")
	assert.Contains(t, output, "staging")
}

func TestHandleREPLEnv_Set(t *testing.T) {
	setupApp()
	captureOutput(func() {
		handleREPLEnv([]string{"env", "set", "staging"})
	})
	assert.Equal(t, "staging", AppInstance.EnvMgr.GetEnv())
}

func TestHandleREPLEnv_SetInvalid(t *testing.T) {
	setupApp()
	output := captureOutput(func() {
		handleREPLEnv([]string{"env", "set", "nonexistent"})
	})
	assert.True(t, strings.Contains(output, "Error") || strings.Contains(output, "not found"), "expected error message, got: %s", output)
}

func TestHandleREPLEnv_NoSubcommand(t *testing.T) {
	setupApp()
	output := captureOutput(func() {
		handleREPLEnv([]string{"env"})
	})
	assert.Contains(t, output, "Usage")
}

func TestHandleREPLVar_Set(t *testing.T) {
	setupApp()
	captureOutput(func() {
		handleREPLVar([]string{"var", "set", "mykey=myval"})
	})
	assert.Equal(t, "myval", AppInstance.EnvMgr.GetVar("mykey"))
}

func TestHandleREPLVar_Get(t *testing.T) {
	setupApp()
	AppInstance.EnvMgr.SetVar("mykey", "myval")
	output := captureOutput(func() {
		handleREPLVar([]string{"var", "get", "mykey"})
	})
	assert.Contains(t, output, "myval")
}

func TestHandleREPLVar_List(t *testing.T) {
	setupApp()
	AppInstance.EnvMgr.SetVar("key1", "val1")
	AppInstance.EnvMgr.SetVar("key2", "val2")
	output := captureOutput(func() {
		handleREPLVar([]string{"var", "list"})
	})
	assert.Contains(t, output, "key1")
	assert.Contains(t, output, "key2")
}

func TestHandleREPLVar_InvalidFormat(t *testing.T) {
	setupApp()
	output := captureOutput(func() {
		handleREPLVar([]string{"var", "set", "noequalssign"})
	})
	assert.Contains(t, output, "Invalid format")
}

func TestReplExecutor_Help(t *testing.T) {
	setupApp()
	output := captureOutput(func() {
		replExecutor("help")
	})
	assert.True(t, strings.Contains(output, "Available commands") || strings.Contains(output, "rest"), "expected help output, got: %s", output)
}

func TestReplExecutor_Clear(t *testing.T) {
	setupApp()
	assert.NotPanics(t, func() {
		captureOutput(func() {
			replExecutor("clear")
		})
	})
}

func TestReplExecutor_UnknownCommand(t *testing.T) {
	setupApp()
	output := captureOutput(func() {
		replExecutor("unknown_cmd")
	})
	assert.Contains(t, output, "Unknown command")
}

func TestReplExecutor_Empty(t *testing.T) {
	setupApp()
	assert.NotPanics(t, func() {
		captureOutput(func() {
			replExecutor("")
		})
	})
}
