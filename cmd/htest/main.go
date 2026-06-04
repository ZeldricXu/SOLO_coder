package main

import (
	"os"

	"github.com/htest/htest/internal/cli"
	"github.com/htest/htest/internal/engine"
	"github.com/htest/htest/internal/env"
	"github.com/htest/htest/internal/registry"
	"github.com/htest/htest/internal/script"
	"github.com/htest/htest/internal/version"
)

func main() {
	reg := registry.NewRegistry()
	cli.SetVersion(version.String())

	registerProtocolClients(reg)
	registerStepHandlers(reg)
	registerTemplateFuncs(reg)

	cmd := cli.NewRootCmdWithRegistry(reg)
	if err := cmd.Execute(); err != nil {
		os.Stderr.WriteString(err.Error() + "\n")
		os.Exit(1)
	}
}

func registerProtocolClients(reg *registry.Registry) {
	reg.RegisterProtocolClient("rest", func(config interface{}) (engine.ProtocolClient, error) {
		return &engine.RESTAdapter{}, nil
	})
	reg.RegisterProtocolClient("grpc", func(config interface{}) (engine.ProtocolClient, error) {
		return &engine.GRPCAdapter{}, nil
	})
	reg.RegisterProtocolClient("gql", func(config interface{}) (engine.ProtocolClient, error) {
		return &engine.GQLAdapter{}, nil
	})
	reg.RegisterProtocolClient("ws", func(config interface{}) (engine.ProtocolClient, error) {
		return &engine.WSAdapter{}, nil
	})
}

func registerStepHandlers(reg *registry.Registry) {
	reg.RegisterStepHandler(func() script.StepHandler { return &script.RESTStepHandler{} })
	reg.RegisterStepHandler(func() script.StepHandler { return &script.GRPCStepHandler{} })
	reg.RegisterStepHandler(func() script.StepHandler { return &script.GQLStepHandler{} })
	reg.RegisterStepHandler(func() script.StepHandler { return &script.WSStepHandler{} })
	reg.RegisterStepHandler(func() script.StepHandler { return &script.DelayStepHandler{} })
}

func registerTemplateFuncs(reg *registry.Registry) {
	for name, fn := range env.DefaultFuncMap() {
		reg.RegisterTemplateFunc(name, fn)
	}
}
