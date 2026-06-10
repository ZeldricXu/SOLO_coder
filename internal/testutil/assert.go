package testutil

import (
	"fmt"
	"reflect"
	"strings"
	"testing"
)

func formatMsg(msg []interface{}) string {
	if len(msg) == 0 {
		return ""
	}
	if len(msg) == 1 {
		return fmt.Sprintf(": %v", msg[0])
	}
	return ": " + fmt.Sprintf(msg[0].(string), msg[1:]...)
}

func AssertEqual(t *testing.T, expected, actual interface{}, msg ...interface{}) {
	t.Helper()
	if !reflect.DeepEqual(expected, actual) {
		t.Fatalf("expected %v, got %v%s", expected, actual, formatMsg(msg))
	}
}

func AssertNotNil(t *testing.T, v interface{}, msg ...interface{}) {
	t.Helper()
	if v == nil {
		t.Fatalf("expected non-nil value, got nil%s", formatMsg(msg))
	}
	val := reflect.ValueOf(v)
	kind := val.Kind()
	if kind == reflect.Ptr || kind == reflect.Interface || kind == reflect.Map ||
		kind == reflect.Slice || kind == reflect.Chan || kind == reflect.Func {
		if val.IsNil() {
			t.Fatalf("expected non-nil value, got nil%s", formatMsg(msg))
		}
	}
}

func AssertNil(t *testing.T, v interface{}, msg ...interface{}) {
	t.Helper()
	if v == nil {
		return
	}
	val := reflect.ValueOf(v)
	kind := val.Kind()
	if kind == reflect.Ptr || kind == reflect.Interface || kind == reflect.Map ||
		kind == reflect.Slice || kind == reflect.Chan || kind == reflect.Func {
		if !val.IsNil() {
			t.Fatalf("expected nil, got %v%s", v, formatMsg(msg))
		}
		return
	}
	t.Fatalf("expected nil, got %v%s", v, formatMsg(msg))
}

func AssertNoError(t *testing.T, err error, msg ...interface{}) {
	t.Helper()
	if err != nil {
		t.Fatalf("unexpected error: %v%s", err, formatMsg(msg))
	}
}

func AssertError(t *testing.T, err error, msg ...interface{}) {
	t.Helper()
	if err == nil {
		t.Fatalf("expected error, got nil%s", formatMsg(msg))
	}
}

func AssertTrue(t *testing.T, cond bool, msg ...interface{}) {
	t.Helper()
	if !cond {
		t.Fatalf("assertion failed%s", formatMsg(msg))
	}
}

func AssertContains(t *testing.T, haystack, needle string, msg ...interface{}) {
	t.Helper()
	if !strings.Contains(haystack, needle) {
		t.Fatalf("expected %q to contain %q%s", haystack, needle, formatMsg(msg))
	}
}

func AssertLen(t *testing.T, obj interface{}, expected int, msg ...interface{}) {
	t.Helper()
	val := reflect.ValueOf(obj)
	var actual int
	switch val.Kind() {
	case reflect.Slice, reflect.Array, reflect.Map, reflect.Chan:
		actual = val.Len()
	case reflect.String:
		actual = val.Len()
	default:
		t.Fatalf("AssertLen requires slice, map, string, array, or chan, got %v%s",
			val.Kind(), formatMsg(msg))
	}
	if actual != expected {
		t.Fatalf("expected length %d, got %d%s", expected, actual, formatMsg(msg))
	}
}
