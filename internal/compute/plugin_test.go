package compute

import (
	"fmt"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type mockPanicPlugin struct{}

func (m *mockPanicPlugin) Name() string                      { return "panic-plugin" }
func (m *mockPanicPlugin) Version() string                   { return "1.0.0" }
func (m *mockPanicPlugin) APIVersion() string                { return "1.0.0" }
func (m *mockPanicPlugin) Evaluate(x []float64) float64      { panic("intentional panic in Evaluate") }
func (m *mockPanicPlugin) Gradient(x []float64, grad []float64) { panic("intentional panic in Gradient") }
func (m *mockPanicPlugin) Validate() error                   { return nil }
func (m *mockPanicPlugin) Close() error                      { return nil }

type mockSlowPlugin struct{}

func (m *mockSlowPlugin) Name() string                      { return "slow-plugin" }
func (m *mockSlowPlugin) Version() string                   { return "1.0.0" }
func (m *mockSlowPlugin) APIVersion() string                { return "1.0.0" }
func (m *mockSlowPlugin) Evaluate(x []float64) float64 {
	time.Sleep(5 * time.Second)
	return 42.0
}
func (m *mockSlowPlugin) Gradient(x []float64, grad []float64) {
	time.Sleep(5 * time.Second)
	for i := range grad {
		grad[i] = 0
	}
}
func (m *mockSlowPlugin) Validate() error { return nil }
func (m *mockSlowPlugin) Close() error    { return nil }

type mockValidPlugin struct{}

func (m *mockValidPlugin) Name() string                      { return "valid-plugin" }
func (m *mockValidPlugin) Version() string                   { return "1.0.0" }
func (m *mockValidPlugin) APIVersion() string                { return "1.0.0" }
func (m *mockValidPlugin) Evaluate(x []float64) float64      { return x[0]*x[0] + x[1]*x[1] }
func (m *mockValidPlugin) Gradient(x []float64, grad []float64) {
	for i, xi := range x {
		grad[i] = 2 * xi
	}
}
func (m *mockValidPlugin) Validate() error { return nil }
func (m *mockValidPlugin) Close() error    { return nil }

func TestPlugin_PanicInEvaluate_IsRecovered(t *testing.T) {
	panicPlugin := &mockPanicPlugin{}
	sandboxed := NewSandboxedPlugin(panicPlugin, 1*time.Second)

	assert.NotPanics(t, func() {
		defer func() {
			if r := recover(); r != nil {
				assert.Contains(t, fmt.Sprintf("%v", r), "plugin panicked",
					"panic message should indicate plugin panic")
			}
		}()
		_ = sandboxed.Evaluate([]float64{1.0, 2.0})
	}, "SandboxedPlugin should catch internal panic and re-panic with message")
}

func TestPlugin_TimeoutExecution(t *testing.T) {
	slowPlugin := &mockSlowPlugin{}
	sandboxed := NewSandboxedPlugin(slowPlugin, 100*time.Millisecond)

	assert.Panics(t, func() {
		_ = sandboxed.Evaluate([]float64{1.0, 2.0})
	}, "slow plugin execution should timeout and panic")
}

func TestPlugin_ValidPlugin_WorksCorrectly(t *testing.T) {
	validPlugin := &mockValidPlugin{}
	sandboxed := NewSandboxedPlugin(validPlugin, 1*time.Second)

	result := sandboxed.Evaluate([]float64{3.0, 4.0})
	assert.InDelta(t, 25.0, result, 1e-6, "sphere(3,4) should be 25")

	grad := make([]float64, 2)
	assert.NotPanics(t, func() {
		sandboxed.Gradient([]float64{3.0, 4.0}, grad)
	})
	assert.InDelta(t, 6.0, grad[0], 1e-6, "d/dx sphere at 3 should be 6")
	assert.InDelta(t, 8.0, grad[1], 1e-6, "d/dy sphere at 4 should be 8")
}

func TestPlugin_PanicInGradient_IsRecovered(t *testing.T) {
	panicPlugin := &mockPanicPlugin{}
	sandboxed := NewSandboxedPlugin(panicPlugin, 1*time.Second)

	grad := make([]float64, 2)
	assert.Panics(t, func() {
		sandboxed.Gradient([]float64{1.0, 2.0}, grad)
	}, "panic in Gradient should propagate as sandbox panic message")
}

func TestPlugin_TimeoutInGradient(t *testing.T) {
	slowPlugin := &mockSlowPlugin{}
	sandboxed := NewSandboxedPlugin(slowPlugin, 100*time.Millisecond)

	grad := make([]float64, 2)
	assert.Panics(t, func() {
		sandboxed.Gradient([]float64{1.0, 2.0}, grad)
	}, "slow gradient should timeout")
}

func TestPluginLoader_New(t *testing.T) {
	pl := NewPluginLoader()
	require.NotNil(t, pl)
	assert.Equal(t, 30*time.Second, pl.timeout)
	assert.True(t, pl.sandbox)
	assert.Empty(t, pl.List())
}

func TestPluginLoader_SetSandbox(t *testing.T) {
	pl := NewPluginLoader()
	pl.SetSandbox(false)
	assert.False(t, pl.sandbox)
}

func TestPluginLoader_SetTimeout(t *testing.T) {
	pl := NewPluginLoader()
	newTimeout := 5 * time.Minute
	pl.SetTimeout(newTimeout)
	assert.Equal(t, newTimeout, pl.timeout)
}

func TestPluginWrapper_Objective(t *testing.T) {
	validPlugin := &mockValidPlugin{}
	wrapper := NewPluginWrapper(validPlugin)

	obj := wrapper.Objective()
	result := obj([]float64{2.0, 3.0})
	assert.InDelta(t, 13.0, result, 1e-6)
}

func TestPluginWrapper_Gradient(t *testing.T) {
	validPlugin := &mockValidPlugin{}
	wrapper := NewPluginWrapper(validPlugin)

	gradFn := wrapper.Gradient()
	grad := make([]float64, 2)
	gradFn([]float64{2.0, 3.0}, grad)
	assert.InDelta(t, 4.0, grad[0], 1e-6)
	assert.InDelta(t, 6.0, grad[1], 1e-6)
}

func TestPluginWrapper_CreateEngine(t *testing.T) {
	validPlugin := &mockValidPlugin{}
	wrapper := NewPluginWrapper(validPlugin)

	engine := wrapper.CreateEngine(2)
	require.NotNil(t, engine)

	initialX := []float64{5.0, 5.0}
	config := OptimizerConfig{
		Type:         Adam,
		MaxIter:      1000,
		Tolerance:    1e-12,
		LearningRate: 0.05,
		Beta1:        0.9,
		Beta2:        0.999,
		Epsilon:      1e-8,
	}

	resultX, resultF, err := engine.Optimize(initialX, config)
	require.NoError(t, err)
	assert.InDelta(t, 0.0, resultF, 1e-3)
	assert.InDelta(t, 0.0, resultX[0], 1e-2)
	assert.InDelta(t, 0.0, resultX[1], 1e-2)
}

func TestPluginInfo(t *testing.T) {
	info := PluginInfo{
		Name:       "test",
		Version:    "1.0.0",
		APIVersion: "1.0.0",
		Path:       "/tmp/test.so",
		Loaded:     time.Now(),
		Validated:  true,
	}

	assert.Equal(t, "test", info.Name)
	assert.Equal(t, "1.0.0", info.Version)
	assert.True(t, info.Validated)
}

func TestPluginLoadError(t *testing.T) {
	err := &PluginLoadError{
		Path:   "/tmp/bad.so",
		Reason: "test reason",
		Err:    fmt.Errorf("root cause"),
	}

	msg := err.Error()
	assert.Contains(t, msg, "/tmp/bad.so")
	assert.Contains(t, msg, "test reason")
	assert.Contains(t, msg, "root cause")
}
