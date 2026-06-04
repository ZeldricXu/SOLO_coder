package version

import (
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestString_DefaultValues(t *testing.T) {
	s := String()
	assert.Contains(t, s, "dev")
	assert.Contains(t, s, "none")
	assert.Contains(t, s, "unknown")
}

func TestString_CustomValues(t *testing.T) {
	origVersion := Version
	origCommit := Commit
	origBuildTime := BuildTime
	defer func() {
		Version = origVersion
		Commit = origCommit
		BuildTime = origBuildTime
	}()

	Version = "1.0.0"
	Commit = "abc123"
	BuildTime = "2026-06-03"

	s := String()
	assert.Equal(t, "1.0.0 (commit: abc123, built at: 2026-06-03)", s)
}

func TestString_ContainsAllParts(t *testing.T) {
	Version = "v1.0.0"
	Commit = "deadbeef"
	BuildTime = "2026-01-01T00:00:00Z"
	s := String()
	assert.True(t, strings.Contains(s, "v1.0.0"))
	assert.True(t, strings.Contains(s, "deadbeef"))
	assert.True(t, strings.Contains(s, "2026-01-01"))
}
