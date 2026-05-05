package cli

import (
	"os"
	"path/filepath"
	"strings"
)

func findConfigPath(configFlag string) string {
	if configFlag != "" {
		absPath, _ := filepath.Abs(configFlag)
		return absPath
	}

	cwd, _ := os.Getwd()
	return cwd
}

func sanitizeMigrationName(name string) string {
	name = strings.ToLower(name)
	name = strings.Map(func(r rune) rune {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '_' {
			return r
		}
		return '_'
	}, name)

	for strings.Contains(name, "__") {
		name = strings.ReplaceAll(name, "__", "_")
	}
	name = strings.Trim(name, "_")

	return name
}

func truncateString(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	return s[:maxLen-3] + "..."
}
