package testutil

import (
	"os"
	"path/filepath"
	"testing"
)

func TempDir(t *testing.T, prefix string) (string, func()) {
	t.Helper()
	dir, err := os.MkdirTemp("", prefix)
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	cleanup := func() {
		os.RemoveAll(dir)
	}
	t.Cleanup(cleanup)
	return dir, cleanup
}

func TempFile(t *testing.T, dir, pattern string, content []byte) (string, func()) {
	t.Helper()
	if dir == "" {
		dir = os.TempDir()
	}
	f, err := os.CreateTemp(dir, pattern)
	if err != nil {
		t.Fatalf("failed to create temp file: %v", err)
	}
	path := f.Name()
	if len(content) > 0 {
		if _, err := f.Write(content); err != nil {
			f.Close()
			t.Fatalf("failed to write temp file: %v", err)
		}
	}
	if err := f.Close(); err != nil {
		t.Fatalf("failed to close temp file: %v", err)
	}
	cleanup := func() {
		os.Remove(path)
	}
	t.Cleanup(cleanup)
	return path, cleanup
}

func WriteFile(t *testing.T, path string, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		t.Fatalf("failed to create directory for %s: %v", path, err)
	}
	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		t.Fatalf("failed to write file %s: %v", path, err)
	}
}

func MakeUnwritable(t *testing.T, path string) (restore func()) {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("failed to stat path %s: %v", path, err)
	}
	origMode := info.Mode()
	if err := os.Chmod(path, 0000); err != nil {
		t.Fatalf("failed to chmod 0000 on %s: %v", path, err)
	}
	restore = func() {
		os.Chmod(path, origMode)
	}
	t.Cleanup(restore)
	return restore
}
