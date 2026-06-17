package main

import (
	"fmt"
	"os"
	"strings"

	"github.com/multicloud/cli/internal/cli"
)

func main() {
	app := cli.NewCLI()

	if err := app.Execute(); err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)

		errMsg := err.Error()
		if strings.Contains(errMsg, "not found") || strings.Contains(errMsg, "no such file") {
			fmt.Fprintln(os.Stderr, "\nHint: Run 'multicloud init' to initialize the project first.")
		} else if strings.Contains(errMsg, "state is locked") {
			fmt.Fprintln(os.Stderr, "\nHint: Use 'multicloud state unlock <lock-id>' to force unlock.")
		} else if strings.Contains(errMsg, "credentials") {
			fmt.Fprintln(os.Stderr, "\nHint: Use 'multicloud credentials add <provider>' to configure credentials.")
		}

		os.Exit(1)
	}
}
