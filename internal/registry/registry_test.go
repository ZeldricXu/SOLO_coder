package registry

import (
	"context"
	"testing"
	"text/template"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/htest/htest/internal/engine"
	"github.com/htest/htest/internal/script"
)

func TestNewRegistry(t *testing.T) {
	reg := NewRegistry()
	assert.NotNil(t, reg)
	assert.Empty(t, reg.ProtocolNames())
}

func TestRegistry_RegisterProtocolClient(t *testing.T) {
	reg := NewRegistry()
	reg.RegisterProtocolClient("rest", func(config interface{}) (engine.ProtocolClient, error) {
		return &mockProtocolClient{}, nil
	})

	names := reg.ProtocolNames()
	assert.Contains(t, names, "rest")
}

func TestRegistry_GetProtocolClient(t *testing.T) {
	reg := NewRegistry()
	reg.RegisterProtocolClient("rest", func(config interface{}) (engine.ProtocolClient, error) {
		return &mockProtocolClient{}, nil
	})

	client, err := reg.GetProtocolClient("rest", nil)
	require.NoError(t, err)
	assert.NotNil(t, client)
}

func TestRegistry_GetProtocolClient_NotRegistered(t *testing.T) {
	reg := NewRegistry()
	_, err := reg.GetProtocolClient("thrift", nil)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "not registered")
}

func TestRegistry_RegisterStepHandler(t *testing.T) {
	reg := NewRegistry()
	reg.RegisterStepHandler(func() script.StepHandler { return &script.RESTStepHandler{} })
	reg.RegisterStepHandler(func() script.StepHandler { return &script.DelayStepHandler{} })

	chain := reg.BuildHandlerChain()
	assert.NotNil(t, chain)
}

func TestRegistry_BuildHandlerChain(t *testing.T) {
	reg := NewRegistry()
	reg.RegisterStepHandler(func() script.StepHandler { return &script.RESTStepHandler{} })
	reg.RegisterStepHandler(func() script.StepHandler { return &script.DelayStepHandler{} })

	chain := reg.BuildHandlerChain()

	step := script.Step{Protocol: "rest"}
	assert.True(t, chain.CanHandleStep(step))
}

func TestRegistry_RegisterTemplateFunc(t *testing.T) {
	reg := NewRegistry()
	reg.RegisterTemplateFunc("custom", func(s string) string { return "custom:" + s })

	fm := reg.TemplateFuncMap()
	assert.NotNil(t, fm["custom"])

	_, err := template.New("test").Funcs(fm).Parse(`{{"hello" | custom}}`)
	require.NoError(t, err)
}

func TestRegistry_TemplateFuncMap(t *testing.T) {
	reg := NewRegistry()
	fm := reg.TemplateFuncMap()
	assert.NotNil(t, fm)
}

type mockProtocolClient struct{}

func (m *mockProtocolClient) Execute(ctx context.Context, req *engine.ProtocolRequest) (*engine.ProtocolResponse, error) {
	return &engine.ProtocolResponse{StatusCode: 200}, nil
}
