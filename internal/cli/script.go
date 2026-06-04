package cli

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/htest/htest/internal/engine/rest"
	"github.com/htest/htest/internal/script"
	"github.com/spf13/cobra"
)

func NewScriptCmd() *cobra.Command {
	var envOverride string
	var vars []string
	var stepName string
	var exportPostman string
	var exportHAR string
	var debugMode bool
	var interactiveMode bool

	cmd := &cobra.Command{
		Use:   "run [script-file]",
		Short: "Run .htest test scripts",
		Example: `  # Run a test script
  htest run test.htest

  # Run with debug output
  htest run test.htest --debug

  # Run with interactive step-by-step execution
  htest run test.htest --interactive

  # Run a specific step
  htest run test.htest --step "Create User"`,
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if AppInstance == nil {
				return fmt.Errorf("app not initialized")
			}

			scriptPath := args[0]

			testScript, err := script.ParseFile(scriptPath)
			if err != nil {
				return AppInstance.Out.FormatError(err)
			}

			if err := script.Validate(testScript); err != nil {
				return AppInstance.Out.FormatError(err)
			}

			if envOverride != "" {
				if err := AppInstance.EnvMgr.SetEnv(envOverride); err != nil {
					return AppInstance.Out.FormatError(err)
				}
			} else if testScript.Env != "" {
				if err := AppInstance.EnvMgr.SetEnv(testScript.Env); err != nil {
					return AppInstance.Out.FormatError(err)
				}
			}

			for _, v := range vars {
				parts := strings.SplitN(v, "=", 2)
				if len(parts) == 2 {
					AppInstance.EnvMgr.SetVar(parts[0], parts[1])
				}
			}

			var execOpts []script.ExecutorOption

			if debugMode || interactiveMode {
				execOpts = append(execOpts, script.WithDebugMode(true))
				execOpts = append(execOpts, script.WithAfterStep(func(ctx context.Context, step script.Step, result *script.StepResult) (*script.StepResult, error) {
					if result == nil {
						return result, nil
					}

					fmt.Fprintf(AppInstance.Out.Writer, "\n%s━━━ Step: %s ━━━%s\n", "\033[1m", step.Name, "\033[0m")
					fmt.Fprintf(AppInstance.Out.Writer, "  Status: %s%s%s\n", statusColor(result.Status), result.Status, "\033[0m")
					fmt.Fprintf(AppInstance.Out.Writer, "  Duration: %v\n", result.Duration)

					if result.Error != "" {
						fmt.Fprintf(AppInstance.Out.Writer, "  Error: %s%s%s\n", "\033[31m", result.Error, "\033[0m")
					}

					if resp, ok := result.Response.(*rest.Response); ok {
						fmt.Fprintf(AppInstance.Out.Writer, "  Status Code: %d\n", resp.StatusCode)
						if len(resp.Headers) > 0 {
							fmt.Fprintf(AppInstance.Out.Writer, "  Response Headers:\n")
							for k, vals := range resp.Headers {
								for _, v := range vals {
									fmt.Fprintf(AppInstance.Out.Writer, "    %s: %s\n", k, v)
								}
							}
						}
						if resp.Body != "" {
							body := resp.Body
							if len(body) > 500 {
								body = body[:500] + "..."
							}
							fmt.Fprintf(AppInstance.Out.Writer, "  Response Body:\n")
							fmt.Fprintf(AppInstance.Out.Writer, "    %s\n", body)
						}
					}

					for _, ar := range result.Assertions {
						marker := "\033[32m✓\033[0m"
						if !ar.Pass {
							marker = "\033[31m✗\033[0m"
						}
						fmt.Fprintf(AppInstance.Out.Writer, "  %s %s", marker, ar.Assert.Type)
						if ar.Assert.JSONPath != "" {
							fmt.Fprintf(AppInstance.Out.Writer, " (%s)", ar.Assert.JSONPath)
						}
						if ar.Pass {
							fmt.Fprintf(AppInstance.Out.Writer, " — pass\n")
						} else {
							fmt.Fprintf(AppInstance.Out.Writer, " — fail: %s\n", ar.Message)
						}
					}

					if interactiveMode && (result.Status == "fail" || result.Status == "error") {
						fmt.Fprintf(AppInstance.Out.Writer, "\n%s━━━ Step failed! Entering debug REPL ━━━%s\n", "\033[31m", "\033[0m")
						fmt.Fprintf(AppInstance.Out.Writer, "  Commands: retry|skip|vars|continue|quit\n")
						for {
							fmt.Fprintf(AppInstance.Out.Writer, "  debug> ")
							var input string
							fmt.Scanln(&input)
							switch input {
							case "retry":
								return nil, nil
							case "skip":
								return &script.StepResult{
									StepName:  step.Name,
									Status:    "pass",
									Extracted: make(map[string]string),
								}, nil
							case "vars":
								for k, v := range result.Extracted {
									fmt.Fprintf(AppInstance.Out.Writer, "    %s=%s\n", k, v)
								}
								continue
							case "continue":
								return result, nil
							case "quit":
								return result, fmt.Errorf("user quit")
							default:
								fmt.Fprintf(AppInstance.Out.Writer, "  Unknown: %s\n", input)
							}
						}
					}

					return result, nil
				}))
			}

			if interactiveMode {
				execOpts = append(execOpts, script.WithBeforeStep(func(ctx context.Context, step script.Step, result *script.StepResult) (*script.StepResult, error) {
					fmt.Fprintf(AppInstance.Out.Writer, "\n%s━━━ About to execute: %s ━━━%s\n", "\033[1m", step.Name, "\033[0m")
					fmt.Fprintf(AppInstance.Out.Writer, "  Protocol: %s\n", step.Protocol)
					if step.Request.Method != "" {
						fmt.Fprintf(AppInstance.Out.Writer, "  Method: %s\n", step.Request.Method)
					}
					fmt.Fprintf(AppInstance.Out.Writer, "  URL: %s\n", step.Request.URL)
					if step.Request.Body != "" {
						body := step.Request.Body
						if len(body) > 200 {
							body = body[:200] + "..."
						}
						fmt.Fprintf(AppInstance.Out.Writer, "  Body: %s\n", body)
					}
					fmt.Fprintf(AppInstance.Out.Writer, "\n  Press Enter to execute, or type 'skip' to skip: ")

					var input string
					fmt.Scanln(&input)
					if input == "skip" {
						return &script.StepResult{
							StepName:  step.Name,
							Status:    "pass",
							Extracted: make(map[string]string),
						}, nil
					}
					return nil, nil
				}))
			}

			executor := script.NewExecutor(AppInstance.EnvMgr, execOpts...)

			ctx := context.Background()
			start := time.Now()
			result, err := executor.Execute(ctx, testScript)
			if err != nil {
				return AppInstance.Out.FormatError(err)
			}
			result.TotalDuration = time.Since(start)

			if stepName != "" {
				var filtered []script.StepResult
				for _, s := range result.Steps {
					if s.StepName == stepName {
						filtered = append(filtered, s)
					}
				}
				result.Steps = filtered
			}

			if err := AppInstance.Out.FormatScriptResult(result); err != nil {
				return err
			}

			if exportPostman != "" {
				fmt.Fprintf(AppInstance.Out.Writer, "\nExporting to Postman: %s\n", exportPostman)
			}
			if exportHAR != "" {
				fmt.Fprintf(AppInstance.Out.Writer, "Exporting to HAR: %s\n", exportHAR)
			}

			if result.Status == "fail" {
				return fmt.Errorf("script failed")
			}
			return nil
		},
	}

	cmd.Flags().StringVarP(&envOverride, "env", "e", "", "override environment")
	cmd.Flags().StringArrayVar(&vars, "var", nil, "set variables (key=value)")
	cmd.Flags().StringVar(&stepName, "step", "", "run only specific step by name")
	cmd.Flags().StringVar(&exportPostman, "export-postman", "", "export results to Postman Collection")
	cmd.Flags().StringVar(&exportHAR, "export-har", "", "export results to HAR file")
	cmd.Flags().BoolVar(&debugMode, "debug", false, "show request/response details for each step")
	cmd.Flags().BoolVar(&interactiveMode, "interactive", false, "step-by-step execution with pause before each step")

	return cmd
}

func statusColor(status string) string {
	switch status {
	case "pass":
		return "\033[32m"
	case "fail", "error":
		return "\033[31m"
	default:
		return "\033[33m"
	}
}
