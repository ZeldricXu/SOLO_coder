package cli

import (
	"fmt"
	"sort"
	"strings"

	"github.com/htest/htest/internal/config"
	"github.com/spf13/cobra"
)

func NewEnvCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "env",
		Short: "Manage environments and variables",
	}

	cmd.AddCommand(newEnvListCmd())
	cmd.AddCommand(newEnvShowCmd())
	cmd.AddCommand(newEnvSetCmd())
	cmd.AddCommand(newEnvVarCmd())

	return cmd
}

func newEnvListCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "List all available environments",
		RunE: func(cmd *cobra.Command, args []string) error {
			if AppInstance == nil {
				return fmt.Errorf("app not initialized")
			}

			names := make([]string, 0, len(AppInstance.Config.Environments))
			for name := range AppInstance.Config.Environments {
				names = append(names, name)
			}
			sort.Strings(names)

			fmt.Fprintf(AppInstance.Out.Writer, "\nAvailable environments:\n")
			for _, name := range names {
				marker := "  "
				if name == AppInstance.EnvMgr.GetEnv() {
					marker = "* "
				}
				fmt.Fprintf(AppInstance.Out.Writer, "%s%s\n", marker, name)
			}
			return nil
		},
	}
}

func newEnvShowCmd() *cobra.Command {
	var name string

	cmd := &cobra.Command{
		Use:   "show",
		Short: "Show environment details",
		RunE: func(cmd *cobra.Command, args []string) error {
			if AppInstance == nil {
				return fmt.Errorf("app not initialized")
			}

			envToShow := name
			if envToShow == "" {
				envToShow = AppInstance.EnvMgr.GetEnv()
			}

			envCfg, ok := AppInstance.Config.Environments[envToShow]
			if !ok {
				return fmt.Errorf("environment %q not found", envToShow)
			}

			fmt.Fprintf(AppInstance.Out.Writer, "\nEnvironment: %s\n", envToShow)
			fmt.Fprintf(AppInstance.Out.Writer, "  Base URL: %s\n", envCfg.BaseURL)
			if envCfg.Token != "" {
				fmt.Fprintf(AppInstance.Out.Writer, "  Token: %s\n", maskToken(envCfg.Token))
			}
			if len(envCfg.Headers) > 0 {
				fmt.Fprintf(AppInstance.Out.Writer, "  Headers:\n")
				for k, v := range envCfg.Headers {
					fmt.Fprintf(AppInstance.Out.Writer, "    %s: %s\n", k, v)
				}
			}
			if len(envCfg.Variables) > 0 {
				fmt.Fprintf(AppInstance.Out.Writer, "  Variables:\n")
				keys := make([]string, 0, len(envCfg.Variables))
				for k := range envCfg.Variables {
					keys = append(keys, k)
				}
				sort.Strings(keys)
				for _, k := range keys {
					fmt.Fprintf(AppInstance.Out.Writer, "    %s: %s\n", k, envCfg.Variables[k])
				}
			}
			return nil
		},
	}

	cmd.Flags().StringVarP(&name, "name", "n", "", "environment name")

	return cmd
}

func newEnvSetCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "set [name]",
		Short: "Switch to a different environment",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if AppInstance == nil {
				return fmt.Errorf("app not initialized")
			}

			if err := AppInstance.EnvMgr.SetEnv(args[0]); err != nil {
				return AppInstance.Out.FormatError(err)
			}

			cfg := *AppInstance.Config
			cfg.DefaultEnv = args[0]
			config.Save(&cfg, cfgFile)

			fmt.Fprintf(AppInstance.Out.Writer, "Switched to environment: %s\n", args[0])
			return nil
		},
	}
}

func newEnvVarCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "var",
		Short: "Manage environment variables",
	}

	cmd.AddCommand(newEnvVarSetCmd())
	cmd.AddCommand(newEnvVarGetCmd())
	cmd.AddCommand(newEnvVarListCmd())

	return cmd
}

func newEnvVarSetCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "set [key=value]",
		Short: "Set a runtime variable",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if AppInstance == nil {
				return fmt.Errorf("app not initialized")
			}

			parts := strings.SplitN(args[0], "=", 2)
			if len(parts) != 2 {
				return fmt.Errorf("invalid format, use key=value")
			}

			AppInstance.EnvMgr.SetVar(parts[0], parts[1])
			fmt.Fprintf(AppInstance.Out.Writer, "Set variable: %s=%s\n", parts[0], parts[1])
			return nil
		},
	}
}

func newEnvVarGetCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "get [key]",
		Short: "Get a variable value",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if AppInstance == nil {
				return fmt.Errorf("app not initialized")
			}

			val := AppInstance.EnvMgr.GetVar(args[0])
			fmt.Fprintf(AppInstance.Out.Writer, "%s=%s\n", args[0], val)
			return nil
		},
	}
}

func newEnvVarListCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "List all variables",
		RunE: func(cmd *cobra.Command, args []string) error {
			if AppInstance == nil {
				return fmt.Errorf("app not initialized")
			}

			vars := AppInstance.EnvMgr.AllVars()
			keys := make([]string, 0, len(vars))
			for k := range vars {
				keys = append(keys, k)
			}
			sort.Strings(keys)

			fmt.Fprintf(AppInstance.Out.Writer, "\nVariables:\n")
			for _, k := range keys {
				fmt.Fprintf(AppInstance.Out.Writer, "  %s: %s\n", k, vars[k])
			}
			return nil
		},
	}
}

func maskToken(token string) string {
	if len(token) <= 8 {
		return strings.Repeat("*", len(token))
	}
	return token[:4] + strings.Repeat("*", len(token)-8) + token[len(token)-4:]
}
