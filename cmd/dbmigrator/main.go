package main

import (
	"fmt"
	"os"

	"dbmigrator/internal/cli"
)

func main() {
	registry := cli.NewCommandRegistry()

	registry.Register(cli.NewMigrateCommand())
	registry.Register(cli.NewRollbackCommand())
	registry.Register(cli.NewStatusCommand())
	registry.Register(cli.NewCreateCommand())
	registry.Register(cli.NewCompareCommand())

	args := os.Args[1:]

	if len(args) == 0 {
		registry.PrintUsage()
		os.Exit(1)
	}

	if err := registry.Execute(args); err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}
}
