package dailynote

import (
	"fmt"
	"regexp"
	"strings"
	"time"
)

type VariableResolver struct {
	variables map[string]VariableFunc
	custom    map[string]string
}

type VariableFunc func(format string) (string, error)

func NewVariableResolver() *VariableResolver {
	vr := &VariableResolver{
		variables: make(map[string]VariableFunc),
		custom:    make(map[string]string),
	}
	vr.registerDefaults()
	return vr
}

func (vr *VariableResolver) registerDefaults() {
	vr.variables["date"] = vr.resolveDate
	vr.variables["time"] = vr.resolveTime
	vr.variables["datetime"] = vr.resolveDateTime
	vr.variables["title"] = vr.resolveTitle
	vr.variables["weather"] = vr.resolveWeather
	vr.variables["todos"] = vr.resolveTodos
	vr.variables["yesterday"] = vr.resolveYesterday
	vr.variables["tomorrow"] = vr.resolveTomorrow
	vr.variables["weekday"] = vr.resolveWeekday
	vr.variables["year"] = vr.resolveYear
	vr.variables["month"] = vr.resolveMonth
	vr.variables["day"] = vr.resolveDay
}

func (vr *VariableResolver) Set(name, value string) {
	vr.custom[name] = value
}

func (vr *VariableResolver) SetCustom(custom map[string]string) {
	for k, v := range custom {
		vr.custom[k] = v
	}
}

func (vr *VariableResolver) Resolve(content string) (string, error) {
	re := regexp.MustCompile(`\{\{\s*(\w+)(?:\s*:\s*([^}]+))?\s*\}\}`)

	result := content
	var err error
	replacements := make(map[string]string)

	matches := re.FindAllStringSubmatch(content, -1)
	for _, match := range matches {
		fullMatch := match[0]
		if _, ok := replacements[fullMatch]; ok {
			continue
		}

		varName := match[1]
		format := ""
		if len(match) > 2 {
			format = strings.TrimSpace(match[2])
		}

		value, resolveErr := vr.resolveVariable(varName, format)
		if resolveErr != nil {
			err = resolveErr
			continue
		}
		replacements[fullMatch] = value
	}

	for old, new := range replacements {
		result = strings.ReplaceAll(result, old, new)
	}

	return result, err
}

func (vr *VariableResolver) resolveVariable(name, format string) (string, error) {
	if val, ok := vr.custom[name]; ok {
		return val, nil
	}

	if fn, ok := vr.variables[name]; ok {
		return fn(format)
	}

	return "", fmt.Errorf("unknown variable: %s", name)
}

func (vr *VariableResolver) resolveDate(format string) (string, error) {
	if format == "" {
		format = "2006-01-02"
	}
	return time.Now().Format(format), nil
}

func (vr *VariableResolver) resolveTime(format string) (string, error) {
	if format == "" {
		format = "15:04:05"
	}
	return time.Now().Format(format), nil
}

func (vr *VariableResolver) resolveDateTime(format string) (string, error) {
	if format == "" {
		format = "2006-01-02 15:04:05"
	}
	return time.Now().Format(format), nil
}

func (vr *VariableResolver) resolveTitle(format string) (string, error) {
	if title, ok := vr.custom["title"]; ok {
		return title, nil
	}
	return time.Now().Format("2006-01-02"), nil
}

func (vr *VariableResolver) resolveWeather(format string) (string, error) {
	if weather, ok := vr.custom["weather"]; ok {
		return weather, nil
	}
	return "晴", nil
}

func (vr *VariableResolver) resolveTodos(format string) (string, error) {
	if todos, ok := vr.custom["todos"]; ok {
		return todos, nil
	}
	return "", nil
}

func (vr *VariableResolver) resolveYesterday(format string) (string, error) {
	if format == "" {
		format = "2006-01-02"
	}
	return time.Now().AddDate(0, 0, -1).Format(format), nil
}

func (vr *VariableResolver) resolveTomorrow(format string) (string, error) {
	if format == "" {
		format = "2006-01-02"
	}
	return time.Now().AddDate(0, 0, 1).Format(format), nil
}

func (vr *VariableResolver) resolveWeekday(format string) (string, error) {
	weekdays := []string{"星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"}
	if format == "en" || format == "english" {
		weekdays = []string{"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"}
	} else if format == "short" {
		weekdays = []string{"周日", "周一", "周二", "周三", "周四", "周五", "周六"}
	}
	return weekdays[time.Now().Weekday()], nil
}

func (vr *VariableResolver) resolveYear(format string) (string, error) {
	if format == "two-digit" || format == "2" {
		return time.Now().Format("06"), nil
	}
	return time.Now().Format("2006"), nil
}

func (vr *VariableResolver) resolveMonth(format string) (string, error) {
	if format == "name" {
		return time.Now().Month().String(), nil
	}
	if format == "short" {
		return time.Now().Format("Jan"), nil
	}
	return time.Now().Format("01"), nil
}

func (vr *VariableResolver) resolveDay(format string) (string, error) {
	return time.Now().Format("02"), nil
}

func (vr *VariableResolver) Register(name string, fn VariableFunc) {
	vr.variables[name] = fn
}
