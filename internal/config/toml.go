package config

import (
	"bufio"
	"fmt"
	"reflect"
	"sort"
	"strconv"
	"strings"
)

type tomlValue struct {
	kind reflect.Kind
	str  string
	num  float64
	b    bool
}

func encodeTOML(v interface{}) (string, error) {
	var sb strings.Builder
	rv := reflect.ValueOf(v)
	if rv.Kind() == reflect.Ptr {
		rv = rv.Elem()
	}
	if rv.Kind() != reflect.Struct {
		return "", fmt.Errorf("toml encode: root must be struct, got %s", rv.Kind())
	}

	rt := rv.Type()

	var topKeys []string
	var tables []struct {
		name string
		val  reflect.Value
	}

	for i := 0; i < rv.NumField(); i++ {
		field := rt.Field(i)
		if !field.IsExported() {
			continue
		}
		tag := field.Tag.Get("toml")
		if tag == "-" {
			continue
		}
		name := tag
		if name == "" {
			name = strings.ToLower(field.Name)
		}

		fv := rv.Field(i)
		if fv.Kind() == reflect.Struct {
			tables = append(tables, struct {
				name string
				val  reflect.Value
			}{name: name, val: fv})
		} else {
			topKeys = append(topKeys, name)
			line, err := encodeKv(name, fv)
			if err != nil {
				return "", err
			}
			sb.WriteString(line)
			sb.WriteString("\n")
		}
	}

	sort.Strings(topKeys)

	for _, t := range tables {
		sb.WriteString("\n[")
		sb.WriteString(t.name)
		sb.WriteString("]\n")

		var keys []string
		rt2 := t.val.Type()
		for i := 0; i < t.val.NumField(); i++ {
			field := rt2.Field(i)
			if !field.IsExported() {
				continue
			}
			tag := field.Tag.Get("toml")
			if tag == "-" {
				continue
			}
			name := tag
			if name == "" {
				name = strings.ToLower(field.Name)
			}
			keys = append(keys, name)
		}
		sort.Strings(keys)

		for i := 0; i < t.val.NumField(); i++ {
			field := rt2.Field(i)
			if !field.IsExported() {
				continue
			}
			tag := field.Tag.Get("toml")
			if tag == "-" {
				continue
			}
			name := tag
			if name == "" {
				name = strings.ToLower(field.Name)
			}
			line, err := encodeKv(name, t.val.Field(i))
			if err != nil {
				return "", err
			}
			sb.WriteString(line)
			sb.WriteString("\n")
		}
	}

	return sb.String(), nil
}

func encodeKv(key string, fv reflect.Value) (string, error) {
	switch fv.Kind() {
	case reflect.String:
		return fmt.Sprintf("%s = %q", key, fv.String()), nil
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		return fmt.Sprintf("%s = %d", key, fv.Int()), nil
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		return fmt.Sprintf("%s = %d", key, fv.Uint()), nil
	case reflect.Float32, reflect.Float64:
		return fmt.Sprintf("%s = %g", key, fv.Float()), nil
	case reflect.Bool:
		return fmt.Sprintf("%s = %t", key, fv.Bool()), nil
	default:
		return "", fmt.Errorf("toml encode: unsupported field type %s for %s", fv.Kind(), key)
	}
}

func decodeTOML(data string, out interface{}) error {
	rv := reflect.ValueOf(out)
	if rv.Kind() != reflect.Ptr || rv.IsNil() {
		return fmt.Errorf("toml decode: out must be non-nil pointer")
	}
	root := rv.Elem()
	if root.Kind() != reflect.Struct {
		return fmt.Errorf("toml decode: root must be struct, got %s", root.Kind())
	}

	scanner := bufio.NewScanner(strings.NewReader(data))
	scanner.Buffer([]byte(data), 1024*1024)

	currentTable := ""

	rt := root.Type()

	fieldIndex := make(map[string]int)
	for i := 0; i < rt.NumField(); i++ {
		f := rt.Field(i)
		if !f.IsExported() {
			continue
		}
		tag := f.Tag.Get("toml")
		if tag == "-" {
			continue
		}
		name := tag
		if name == "" {
			name = strings.ToLower(f.Name)
		}
		fieldIndex[name] = i
	}

	tableFieldIndex := make(map[string]map[string]int)

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}

		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			currentTable = strings.TrimSpace(line[1 : len(line)-1])
			if _, ok := tableFieldIndex[currentTable]; !ok {
				idx, ok := fieldIndex[currentTable]
				if !ok {
					continue
				}
				tableField := rt.Field(idx)
				if tableField.Type.Kind() != reflect.Struct {
					continue
				}
				subIdx := make(map[string]int)
				subRt := tableField.Type
				for j := 0; j < subRt.NumField(); j++ {
					sf := subRt.Field(j)
					if !sf.IsExported() {
						continue
					}
					tag := sf.Tag.Get("toml")
					if tag == "-" {
						continue
					}
					name := tag
					if name == "" {
						name = strings.ToLower(sf.Name)
					}
					subIdx[name] = j
				}
				tableFieldIndex[currentTable] = subIdx
			}
			continue
		}

		eqIdx := strings.Index(line, "=")
		if eqIdx < 0 {
			continue
		}
		key := strings.TrimSpace(line[:eqIdx])
		valStr := strings.TrimSpace(line[eqIdx+1:])

		var targetField reflect.Value
		if currentTable == "" {
			idx, ok := fieldIndex[key]
			if !ok {
				continue
			}
			targetField = root.Field(idx)
		} else {
			tIdx, ok := fieldIndex[currentTable]
			if !ok {
				continue
			}
			subMap, ok := tableFieldIndex[currentTable]
			if !ok {
				continue
			}
			subIdx, ok := subMap[key]
			if !ok {
				continue
			}
			targetField = root.Field(tIdx).Field(subIdx)
		}

		if err := assignTomlValue(targetField, valStr); err != nil {
			return err
		}
	}

	return scanner.Err()
}

func assignTomlValue(fv reflect.Value, raw string) error {
	if strings.HasPrefix(raw, `"`) && strings.HasSuffix(raw, `"`) {
		s, err := strconv.Unquote(raw)
		if err != nil {
			return fmt.Errorf("toml decode: invalid string %q: %w", raw, err)
		}
		switch fv.Kind() {
		case reflect.String:
			fv.SetString(s)
		default:
			return fmt.Errorf("toml decode: cannot assign string to %s", fv.Kind())
		}
		return nil
	}
	if raw == "true" {
		if fv.Kind() == reflect.Bool {
			fv.SetBool(true)
			return nil
		}
		return fmt.Errorf("toml decode: cannot assign bool to %s", fv.Kind())
	}
	if raw == "false" {
		if fv.Kind() == reflect.Bool {
			fv.SetBool(false)
			return nil
		}
		return fmt.Errorf("toml decode: cannot assign bool to %s", fv.Kind())
	}

	if n, err := strconv.ParseInt(raw, 10, 64); err == nil {
		switch fv.Kind() {
		case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
			fv.SetInt(n)
			return nil
		case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
			fv.SetUint(uint64(n))
			return nil
		case reflect.Float32, reflect.Float64:
			fv.SetFloat(float64(n))
			return nil
		}
	}

	if f, err := strconv.ParseFloat(raw, 64); err == nil {
		switch fv.Kind() {
		case reflect.Float32, reflect.Float64:
			fv.SetFloat(f)
			return nil
		}
	}

	return fmt.Errorf("toml decode: cannot parse %q as %s", raw, fv.Kind())
}
