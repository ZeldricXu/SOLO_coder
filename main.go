package main

import (
	"fmt"
	"os"
	"path/filepath"

	"backupmanager/internal/cli"
)

func main() {
	homeDir, err := os.UserHomeDir()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: failed to get home directory: %v\n", err)
		os.Exit(1)
	}

	defaultStorage := filepath.Join(homeDir, ".backupmanager", "storage")

	storageRoot := os.Getenv("BACKUPMGR_STORAGE")
	if storageRoot == "" {
		storageRoot = defaultStorage
	}

	app, err := cli.NewCLI(storageRoot)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: failed to initialize BackupManager: %v\n", err)
		os.Exit(1)
	}

	app.Execute()
}
