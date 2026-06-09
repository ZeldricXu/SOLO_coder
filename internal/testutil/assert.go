package testutil

import (
	"math"
	"testing"
)

type Assert struct {
	t *testing.T
}

func NewAssert(t *testing.T) *Assert {
	return &Assert{t: t}
}

func (a *Assert) NoError(err error, msgAndArgs ...interface{}) {
	a.t.Helper()
	if err != nil {
		a.t.Fatalf("unexpected error: %v", err)
	}
}

func (a *Assert) Error(err error, msgAndArgs ...interface{}) {
	a.t.Helper()
	if err == nil {
		a.t.Fatalf("expected error, got nil")
	}
}

func (a *Assert) Equal(expected, actual interface{}, msgAndArgs ...interface{}) {
	a.t.Helper()
	if expected != actual {
		a.t.Fatalf("expected %v, got %v", expected, actual)
	}
}

func (a *Assert) NotEqual(expected, actual interface{}, msgAndArgs ...interface{}) {
	a.t.Helper()
	if expected == actual {
		a.t.Fatalf("expected not equal to %v", expected)
	}
}

func (a *Assert) Nil(v interface{}, msgAndArgs ...interface{}) {
	a.t.Helper()
	if v != nil {
		a.t.Fatalf("expected nil, got %v", v)
	}
}

func (a *Assert) NotNil(v interface{}, msgAndArgs ...interface{}) {
	a.t.Helper()
	if v == nil {
		a.t.Fatalf("expected not nil, got nil")
	}
}

func (a *Assert) True(b bool, msgAndArgs ...interface{}) {
	a.t.Helper()
	if !b {
		a.t.Fatalf("expected true, got false")
	}
}

func (a *Assert) False(b bool, msgAndArgs ...interface{}) {
	a.t.Helper()
	if b {
		a.t.Fatalf("expected false, got true")
	}
}

func (a *Assert) Len(v interface{}, length int, msgAndArgs ...interface{}) {
	a.t.Helper()
	switch x := v.(type) {
	case []interface{}:
		if len(x) != length {
			a.t.Fatalf("expected length %d, got %d", length, len(x))
		}
	case []string:
		if len(x) != length {
			a.t.Fatalf("expected length %d, got %d", length, len(x))
		}
	case []int:
		if len(x) != length {
			a.t.Fatalf("expected length %d, got %d", length, len(x))
		}
	case map[string]interface{}:
		if len(x) != length {
			a.t.Fatalf("expected length %d, got %d", length, len(x))
		}
	default:
		a.t.Fatalf("unsupported type for Len assertion")
	}
}

func (a *Assert) InDelta(expected, actual, delta float64, msgAndArgs ...interface{}) {
	a.t.Helper()
	if math.Abs(expected-actual) > delta {
		a.t.Fatalf("expected %v (±%v), got %v", expected, delta, actual)
	}
}

func (a *Assert) Contains(s, substr string, msgAndArgs ...interface{}) {
	a.t.Helper()
	found := false
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			found = true
			break
		}
	}
	if !found {
		a.t.Fatalf("expected %q to contain %q", s, substr)
	}
}

func (a *Assert) Greater(x, y float64, msgAndArgs ...interface{}) {
	a.t.Helper()
	if x <= y {
		a.t.Fatalf("expected %v > %v", x, y)
	}
}

func (a *Assert) Less(x, y float64, msgAndArgs ...interface{}) {
	a.t.Helper()
	if x >= y {
		a.t.Fatalf("expected %v < %v", x, y)
	}
}

func (a *Assert) GreaterOrEqual(x, y float64, msgAndArgs ...interface{}) {
	a.t.Helper()
	if x < y {
		a.t.Fatalf("expected %v >= %v", x, y)
	}
}

func (a *Assert) LessOrEqual(x, y float64, msgAndArgs ...interface{}) {
	a.t.Helper()
	if x > y {
		a.t.Fatalf("expected %v <= %v", x, y)
	}
}

func (a *Assert) NotEmpty(v interface{}, msgAndArgs ...interface{}) {
	a.t.Helper()
	switch x := v.(type) {
	case string:
		if len(x) == 0 {
			a.t.Fatalf("expected non-empty string")
		}
	case []byte:
		if len(x) == 0 {
			a.t.Fatalf("expected non-empty byte slice")
		}
	case []interface{}:
		if len(x) == 0 {
			a.t.Fatalf("expected non-empty slice")
		}
	case []string:
		if len(x) == 0 {
			a.t.Fatalf("expected non-empty slice")
		}
	case []int:
		if len(x) == 0 {
			a.t.Fatalf("expected non-empty slice")
		}
	case map[string]interface{}:
		if len(x) == 0 {
			a.t.Fatalf("expected non-empty map")
		}
	default:
		a.t.Fatalf("unsupported type for NotEmpty assertion")
	}
}
