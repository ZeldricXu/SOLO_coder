package cli

import (
	"os"

	"github.com/htest/htest/internal/config"
	"github.com/htest/htest/internal/env"
	"github.com/htest/htest/internal/output"
	"github.com/htest/htest/internal/registry"
	"github.com/htest/htest/internal/script"
	"github.com/spf13/cobra"
)

var (
	cfgFile   string
	envName   string
	verbose   bool
	outputFmt string
	version   bool
)

var versionStr = "htest dev"

func SetVersion(v string) {
	versionStr = v
}

type App struct {
	Config   *config.Config
	EnvMgr   *env.Manager
	Out      *output.Formatter
	Registry *registry.Registry
}

var AppInstance *App

func NewRootCmdWithRegistry(reg *registry.Registry) *cobra.Command {
	rootCmd := &cobra.Command{
		Use:   "htest",
		Short: "Multi-protocol API testing CLI",
		Long: `A unified CLI tool for testing REST, gRPC, GraphQL and WebSocket APIs.

htest simplifies API testing by providing a single interface for multiple
protocols. Write test scripts in YAML, manage environments, and run
performance benchmarks — all from one tool.

Configuration:
  Config file: ~/.config/htest/config.yaml
  Environment: APICALL_ENV (overrides default_env)
  Auth token:  APICALL_TOKEN (overrides environment token)`,
		Example: `  # Send a REST GET request
  htest rest get https://api.example.com/users

  # Send a POST with JSON body
  htest rest post https://api.example.com/users -d '{"name":"Alice"}'

  # List gRPC services
  htest grpc list -t localhost:50051

  # Describe a gRPC service
  htest grpc describe -t localhost:50051 -s mypackage.MyService

  # Run a GraphQL query
  htest gql query -E https://api.example.com/graphql -q '{ users { id name } }'

  # Connect to WebSocket
  htest ws connect wss://echo.example.com/ws

  # Run a test script
  htest run test.htest

  # Run with debug output
  htest run test.htest --debug

  # Run performance benchmark
  htest bench test.htest -n 50 -d 30s --rps 100

  # Compare benchmark reports
  htest report diff baseline.json current.json

  # Generate shell completions
  htest completion zsh > ~/.zsh/completion/_htest`,
		RunE: func(cmd *cobra.Command, args []string) error {
			if version {
				cmd.Println(versionStr)
				return nil
			}
			return cmd.Help()
		},
		PersistentPreRunE: func(cmd *cobra.Command, args []string) error {
			var cfg *config.Config
			var err error
			if cfgFile != "" {
				cfg, err = config.Load(cfgFile)
			} else {
				cfg, err = config.Load("")
			}
			if err != nil {
				cfg = config.DefaultConfig()
			}
			envMgr := env.NewManager(cfg)
			if envName != "" {
				if e := envMgr.SetEnv(envName); e != nil {
					return e
				}
			}
			out := output.NewFormatter(outputFmt, os.Stdout)
			AppInstance = &App{
				Config:   cfg,
				EnvMgr:   envMgr,
				Out:      out,
				Registry: reg,
			}
			return nil
		},
	}

	rootCmd.PersistentFlags().StringVarP(&cfgFile, "config", "c", "", "config file path")
	rootCmd.PersistentFlags().StringVarP(&envName, "env", "e", "", "environment name (dev/staging/prod)")
	rootCmd.PersistentFlags().BoolVarP(&verbose, "verbose", "v", false, "verbose output")
	rootCmd.PersistentFlags().StringVarP(&outputFmt, "output", "o", "pretty", "output format (pretty/json/raw)")
	rootCmd.Flags().BoolVarP(&version, "version", "V", false, "print version information")

	rootCmd.AddCommand(NewRESTCmd())
	rootCmd.AddCommand(NewGRPCCmd())
	rootCmd.AddCommand(NewGraphQLCmd())
	rootCmd.AddCommand(NewWSCmd())
	rootCmd.AddCommand(NewScriptCmd())
	rootCmd.AddCommand(NewEnvCmd())
	rootCmd.AddCommand(NewBenchCmd())
	rootCmd.AddCommand(NewReportCmd())
	rootCmd.AddCommand(NewREPLCmd())
	rootCmd.AddCommand(NewCompletionCmd())
	rootCmd.AddCommand(NewManPageCmd())

	return rootCmd
}

func NewRootCmd() *cobra.Command {
	reg := registry.NewRegistry()
	registerDefaults(reg)
	return NewRootCmdWithRegistry(reg)
}

func registerDefaults(reg *registry.Registry) {
	reg.RegisterStepHandler(func() script.StepHandler { return &script.RESTStepHandler{} })
	reg.RegisterStepHandler(func() script.StepHandler { return &script.GRPCStepHandler{} })
	reg.RegisterStepHandler(func() script.StepHandler { return &script.GQLStepHandler{} })
	reg.RegisterStepHandler(func() script.StepHandler { return &script.WSStepHandler{} })
	reg.RegisterStepHandler(func() script.StepHandler { return &script.DelayStepHandler{} })
}
