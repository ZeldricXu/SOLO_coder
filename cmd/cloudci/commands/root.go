package commands

import (
	"github.com/spf13/cobra"
)

var rootCmd = &cobra.Command{
	Use:   "cloudci",
	Short: "CloudCI - Multi-Cloud CI/CD Pipeline Orchestrator",
	Long: `CloudCI is a unified CI/CD pipeline orchestration tool that abstracts
away the differences between various CI platforms (GitHub Actions, Jenkins, GitLab CI)
and provides centralized management, security auditing, and plugin extensibility.`,
}

func Execute() error {
	return rootCmd.Execute()
}
