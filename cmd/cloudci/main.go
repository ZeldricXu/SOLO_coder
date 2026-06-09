package main

import (
	"os"

	"github.com/solocoder/cloudci/cmd/cloudci/commands"
)

func main() {
	if err := commands.Execute(); err != nil {
		os.Exit(1)
	}
}
