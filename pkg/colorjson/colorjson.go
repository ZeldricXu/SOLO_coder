package colorjson

import (
	"encoding/json"
	"fmt"
	"strings"
)

const (
	cyan   = "\033[36m"
	green  = "\033[32m"
	yellow = "\033[33m"
	magenta = "\033[35m"
	red    = "\033[31m"
	reset  = "\033[0m"
	bold   = "\033[1m"
)

func Format(data []byte, indent string) (string, error) {
	var v interface{}
	if err := json.Unmarshal(data, &v); err != nil {
		return string(data), nil
	}
	return formatValue(v, 0, indent), nil
}

func formatValue(v interface{}, depth int, indent string) string {
	switch val := v.(type) {
	case map[string]interface{}:
		return formatObject(val, depth, indent)
	case []interface{}:
		return formatArray(val, depth, indent)
	case string:
		return green + jsonEscape(val) + reset
	case float64:
		return yellow + fmt.Sprintf("%v", val) + reset
	case int:
		return yellow + fmt.Sprintf("%d", val) + reset
	case int64:
		return yellow + fmt.Sprintf("%d", val) + reset
	case bool:
		if val {
			return magenta + "true" + reset
		}
		return magenta + "false" + reset
	case nil:
		return red + "null" + reset
	default:
		return fmt.Sprintf("%v", val)
	}
}

func formatObject(obj map[string]interface{}, depth int, indent string) string {
	if len(obj) == 0 {
		return "{}"
	}

	var sb strings.Builder
	sb.WriteString("{\n")

	keys := sortedKeys(obj)
	for i, key := range keys {
		sb.WriteString(strings.Repeat(indent, depth+1))
		sb.WriteString(cyan + bold)
		sb.WriteString(jsonEscape(key))
		sb.WriteString(reset)
		sb.WriteString(": ")
		sb.WriteString(formatValue(obj[key], depth+1, indent))

		if i < len(keys)-1 {
			sb.WriteString(",")
		}
		sb.WriteString("\n")
	}

	sb.WriteString(strings.Repeat(indent, depth))
	sb.WriteString("}")
	return sb.String()
}

func formatArray(arr []interface{}, depth int, indent string) string {
	if len(arr) == 0 {
		return "[]"
	}

	var sb strings.Builder
	sb.WriteString("[\n")

	for i, item := range arr {
		sb.WriteString(strings.Repeat(indent, depth+1))
		sb.WriteString(formatValue(item, depth+1, indent))

		if i < len(arr)-1 {
			sb.WriteString(",")
		}
		sb.WriteString("\n")
	}

	sb.WriteString(strings.Repeat(indent, depth))
	sb.WriteString("]")
	return sb.String()
}

func sortedKeys(m map[string]interface{}) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	for i := 0; i < len(keys); i++ {
		for j := i + 1; j < len(keys); j++ {
			if keys[i] > keys[j] {
				keys[i], keys[j] = keys[j], keys[i]
			}
		}
	}
	return keys
}

func jsonEscape(s string) string {
	var sb strings.Builder
	sb.WriteByte('"')
	for _, r := range s {
		switch r {
		case '"':
			sb.WriteString(`\"`)
		case '\\':
			sb.WriteString(`\\`)
		case '\n':
			sb.WriteString(`\n`)
		case '\r':
			sb.WriteString(`\r`)
		case '\t':
			sb.WriteString(`\t`)
		default:
			sb.WriteRune(r)
		}
	}
	sb.WriteByte('"')
	return sb.String()
}
