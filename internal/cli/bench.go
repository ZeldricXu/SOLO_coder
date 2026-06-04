package cli

import (
	"context"
	"fmt"
	"os"
	"time"

	"github.com/htest/htest/internal/bench"
	"github.com/htest/htest/internal/script"
	"github.com/spf13/cobra"
)

func NewBenchCmd() *cobra.Command {
	var concurrency int
	var duration string
	var rps int
	var reportInterval string
	var envOverride string
	var vars []string
	var reportFile string

	cmd := &cobra.Command{
		Use:   "bench [script-file]",
		Short: "Run performance benchmarks",
		Example: `  # Run a benchmark with default settings
  htest bench test.htest

  # Benchmark with 50 concurrent workers for 30 seconds
  htest bench test.htest -n 50 -d 30s

  # Benchmark with 100 RPS target and export report
  htest bench test.htest --rps 100 --report result.json`,
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
				parts := splitKV(v)
				if len(parts) == 2 {
					AppInstance.EnvMgr.SetVar(parts[0], parts[1])
				}
			}

			executor := script.NewExecutor(AppInstance.EnvMgr)

			dur, err := parseDuration(duration)
			if err != nil {
				return AppInstance.Out.FormatError(err)
			}

			repInt, err := parseDuration(reportInterval)
			if err != nil {
				repInt, _ = parseDuration("1s")
			}

			benchCfg := bench.Config{
				Concurrency:    concurrency,
				Duration:       dur,
				RPS:            rps,
				ReportInterval: repInt,
				Script:         testScript,
			}

			runner := bench.NewRunner(benchCfg, executor)

			ctx := context.Background()
			runCtx, cancel := context.WithCancel(ctx)
			defer cancel()

			stats, err := runner.Run(runCtx)
			if err != nil {
				return AppInstance.Out.FormatError(err)
			}

			report := bench.NewReport(stats)
			fmt.Fprint(AppInstance.Out.Writer, report.String())

			if reportFile != "" {
				jsonData, err := report.ToJSON()
				if err != nil {
					return AppInstance.Out.FormatError(err)
				}
				if err := os.WriteFile(reportFile, jsonData, 0644); err != nil {
					return AppInstance.Out.FormatError(err)
				}
				fmt.Fprintf(AppInstance.Out.Writer, "\nReport exported to: %s\n", reportFile)
			}

			return nil
		},
	}

	cmd.Flags().IntVarP(&concurrency, "concurrency", "n", 10, "number of concurrent workers")
	cmd.Flags().StringVarP(&duration, "duration", "d", "30s", "test duration (e.g. 30s, 1m)")
	cmd.Flags().IntVar(&rps, "rps", 0, "target requests per second (0=unlimited)")
	cmd.Flags().StringVar(&reportInterval, "report-interval", "1s", "report interval")
	cmd.Flags().StringVarP(&envOverride, "env", "e", "", "override environment")
	cmd.Flags().StringArrayVar(&vars, "var", nil, "set variables (key=value)")
	cmd.Flags().StringVar(&reportFile, "report", "", "export benchmark report to JSON file")

	return cmd
}

func splitKV(s string) []string {
	idx := -1
	for i, c := range s {
		if c == '=' {
			idx = i
			break
		}
	}
	if idx == -1 {
		return nil
	}
	return []string{s[:idx], s[idx+1:]}
}

func parseDuration(s string) (time.Duration, error) {
	d, err := time.ParseDuration(s)
	if err != nil {
		return 0, err
	}
	return d, nil
}
