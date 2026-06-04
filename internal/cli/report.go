package cli

import (
	"fmt"

	"github.com/htest/htest/internal/bench"
	"github.com/spf13/cobra"
)

func NewReportCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "report",
		Short: "Benchmark report tools",
	}

	cmd.AddCommand(newReportDiffCmd())

	return cmd
}

func newReportDiffCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "diff <baseline.json> <current.json>",
		Short: "Compare two benchmark reports and show performance diff",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			if AppInstance == nil {
				return fmt.Errorf("app not initialized")
			}

			baseline, err := bench.LoadReportData(args[0])
			if err != nil {
				return AppInstance.Out.FormatError(err)
			}

			current, err := bench.LoadReportData(args[1])
			if err != nil {
				return AppInstance.Out.FormatError(err)
			}

			diff := bench.DiffReports(baseline, current)
			fmt.Fprint(AppInstance.Out.Writer, diff.String())

			return nil
		},
	}

	return cmd
}
