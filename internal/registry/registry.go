package registry

import (
	"fmt"
	"text/template"

	"github.com/htest/htest/internal/engine"
	"github.com/htest/htest/internal/script"
)

type ProtocolClientFactory func(config interface{}) (engine.ProtocolClient, error)

type StepHandlerFactory func() script.StepHandler

type Registry struct {
	protocolClients map[string]ProtocolClientFactory
	stepHandlers    []StepHandlerFactory
	templateFuncs   template.FuncMap
}

func NewRegistry() *Registry {
	return &Registry{
		protocolClients: make(map[string]ProtocolClientFactory),
		stepHandlers:    make([]StepHandlerFactory, 0),
		templateFuncs:   make(template.FuncMap),
	}
}

func (r *Registry) RegisterProtocolClient(protocol string, factory ProtocolClientFactory) {
	r.protocolClients[protocol] = factory
}

func (r *Registry) RegisterStepHandler(factory StepHandlerFactory) {
	r.stepHandlers = append(r.stepHandlers, factory)
}

func (r *Registry) RegisterTemplateFunc(name string, fn interface{}) {
	r.templateFuncs[name] = fn
}

func (r *Registry) GetProtocolClient(protocol string, config interface{}) (engine.ProtocolClient, error) {
	factory, ok := r.protocolClients[protocol]
	if !ok {
		return nil, fmt.Errorf("protocol client not registered: %s", protocol)
	}
	return factory(config)
}

func (r *Registry) BuildHandlerChain() *script.HandlerChain {
	chain := script.NewHandlerChain()
	for _, factory := range r.stepHandlers {
		chain.AddHandler(factory())
	}
	return chain
}

func (r *Registry) TemplateFuncMap() template.FuncMap {
	return r.templateFuncs
}

func (r *Registry) ProtocolNames() []string {
	names := make([]string, 0, len(r.protocolClients))
	for name := range r.protocolClients {
		names = append(names, name)
	}
	return names
}
