package services

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"
)

type ExpressionEngine struct {
	functions map[string]func(args []string, vars map[string]string) (string, error)
}

func NewExpressionEngine() *ExpressionEngine {
	engine := &ExpressionEngine{
		functions: make(map[string]func(args []string, vars map[string]string) (string, error)),
	}
	engine.registerBuiltinFunctions()
	return engine
}

func (e *ExpressionEngine) registerBuiltinFunctions() {
	e.functions["default"] = func(args []string, vars map[string]string) (string, error) {
		if len(args) < 2 {
			return "", fmt.Errorf("default function requires at least 2 arguments: variable name and default value")
		}
		varName := strings.TrimSpace(args[0])
		defaultVal := strings.TrimSpace(args[1])
		if val, exists := vars[varName]; exists && val != "" {
			return val, nil
		}
		return defaultVal, nil
	}

	e.functions["if"] = func(args []string, vars map[string]string) (string, error) {
		if len(args) < 3 {
			return "", fmt.Errorf("if function requires 3 arguments: condition, true_value, false_value")
		}
		condition := strings.TrimSpace(args[0])
		trueVal := strings.TrimSpace(args[1])
		falseVal := strings.TrimSpace(args[2])
		if e.evaluateCondition(condition, vars) {
			return trueVal, nil
		}
		return falseVal, nil
	}

	e.functions["formatDate"] = func(args []string, vars map[string]string) (string, error) {
		if len(args) < 2 {
			return "", fmt.Errorf("formatDate function requires 2 arguments: date_variable and format")
		}
		varName := strings.TrimSpace(args[0])
		format := strings.TrimSpace(args[1])
		dateStr, exists := vars[varName]
		if !exists {
			return "", nil
		}
		parsed, err := time.Parse(time.RFC3339, dateStr)
		if err != nil {
			return dateStr, nil
		}
		return parsed.Format(format), nil
	}

	e.functions["formatNumber"] = func(args []string, vars map[string]string) (string, error) {
		if len(args) < 2 {
			return "", fmt.Errorf("formatNumber function requires 2 arguments: number_variable and decimal_places")
		}
		varName := strings.TrimSpace(args[0])
		decimalPlacesStr := strings.TrimSpace(args[1])
		decimalPlaces, err := strconv.Atoi(decimalPlacesStr)
		if err != nil {
			decimalPlaces = 2
		}
		numStr, exists := vars[varName]
		if !exists {
			return "", nil
		}
		num, err := strconv.ParseFloat(numStr, 64)
		if err != nil {
			return numStr, nil
		}
		format := fmt.Sprintf("%%.%df", decimalPlaces)
		return fmt.Sprintf(format, num), nil
	}

	e.functions["length"] = func(args []string, vars map[string]string) (string, error) {
		if len(args) < 1 {
			return "", fmt.Errorf("length function requires 1 argument: variable name")
		}
		varName := strings.TrimSpace(args[0])
		val, exists := vars[varName]
		if !exists {
			return "0", nil
		}
		return strconv.Itoa(len(val)), nil
	}

	e.functions["upper"] = func(args []string, vars map[string]string) (string, error) {
		if len(args) < 1 {
			return "", fmt.Errorf("upper function requires 1 argument: variable name")
		}
		varName := strings.TrimSpace(args[0])
		val, exists := vars[varName]
		if !exists {
			return "", nil
		}
		return strings.ToUpper(val), nil
	}

	e.functions["lower"] = func(args []string, vars map[string]string) (string, error) {
		if len(args) < 1 {
			return "", fmt.Errorf("lower function requires 1 argument: variable name")
		}
		varName := strings.TrimSpace(args[0])
		val, exists := vars[varName]
		if !exists {
			return "", nil
		}
		return strings.ToLower(val), nil
	}

	e.functions["substring"] = func(args []string, vars map[string]string) (string, error) {
		if len(args) < 3 {
			return "", fmt.Errorf("substring function requires 3 arguments: variable name, start index, end index")
		}
		varName := strings.TrimSpace(args[0])
		startStr := strings.TrimSpace(args[1])
		endStr := strings.TrimSpace(args[2])
		val, exists := vars[varName]
		if !exists {
			return "", nil
		}
		start, err := strconv.Atoi(startStr)
		if err != nil {
			start = 0
		}
		end, err := strconv.Atoi(endStr)
		if err != nil || end > len(val) {
			end = len(val)
		}
		if start < 0 {
			start = 0
		}
		if start >= len(val) {
			return "", nil
		}
		return val[start:end], nil
	}
}

func (e *ExpressionEngine) evaluateCondition(condition string, vars map[string]string) bool {
	operators := []string{"==", "!=", ">=", "<=", ">", "<", " contains ", " startsWith ", " endsWith "}
	for _, op := range operators {
		if strings.Contains(condition, op) {
			parts := strings.SplitN(condition, op, 2)
			if len(parts) != 2 {
				continue
			}
			left := strings.TrimSpace(parts[0])
			right := strings.TrimSpace(parts[1])
			leftVal := e.resolveValue(left, vars)
			rightVal := e.resolveValue(right, vars)
			switch op {
			case "==":
				return leftVal == rightVal
			case "!=":
				return leftVal != rightVal
			case ">=":
				leftNum, leftOk := strconv.ParseFloat(leftVal, 64)
				rightNum, rightOk := strconv.ParseFloat(rightVal, 64)
				return leftOk == nil && rightOk == nil && leftNum >= rightNum
			case "<=":
				leftNum, leftOk := strconv.ParseFloat(leftVal, 64)
				rightNum, rightOk := strconv.ParseFloat(rightVal, 64)
				return leftOk == nil && rightOk == nil && leftNum <= rightNum
			case ">":
				leftNum, leftOk := strconv.ParseFloat(leftVal, 64)
				rightNum, rightOk := strconv.ParseFloat(rightVal, 64)
				return leftOk == nil && rightOk == nil && leftNum > rightNum
			case "<":
				leftNum, leftOk := strconv.ParseFloat(leftVal, 64)
				rightNum, rightOk := strconv.ParseFloat(rightVal, 64)
				return leftOk == nil && rightOk == nil && leftNum < rightNum
			case " contains ":
				return strings.Contains(leftVal, rightVal)
			case " startsWith ":
				return strings.HasPrefix(leftVal, rightVal)
			case " endsWith ":
				return strings.HasSuffix(leftVal, rightVal)
			}
		}
	}
	return condition == "true"
}

func (e *ExpressionEngine) resolveValue(val string, vars map[string]string) string {
	val = strings.TrimSpace(val)
	if strings.HasPrefix(val, "\"") && strings.HasSuffix(val, "\"") {
		return val[1 : len(val)-1]
	}
	if strings.HasPrefix(val, "'") && strings.HasSuffix(val, "'") {
		return val[1 : len(val)-1]
	}
	if resolved, exists := vars[val]; exists {
		return resolved
	}
	return val
}

var expressionPattern = regexp.MustCompile(`\{\{([^{}]+)\}\}`)
var functionPattern = regexp.MustCompile(`^(\w+)\((.*)\)$`)

func (e *ExpressionEngine) Parse(content string, vars map[string]string) (string, error) {
	var err error
	result := expressionPattern.ReplaceAllStringFunc(content, func(match string) string {
		inner := strings.TrimSpace(match[2 : len(match)-2])
		if matches := functionPattern.FindStringSubmatch(inner); len(matches) == 3 {
			funcName := matches[1]
			argsStr := matches[2]
			args := parseFunctionArgs(argsStr)
			if fn, exists := e.functions[funcName]; exists {
				result, fnErr := fn(args, vars)
				if fnErr != nil {
					err = fnErr
					return match
				}
				return result
			}
			return match
		}
		if strings.Contains(inner, "?") && strings.Contains(inner, ":") {
			parts := strings.SplitN(inner, "?", 2)
			if len(parts) == 2 {
				condition := strings.TrimSpace(parts[0])
				trueFalse := strings.SplitN(parts[1], ":", 2)
				if len(trueFalse) == 2 {
					trueVal := strings.TrimSpace(trueFalse[0])
					falseVal := strings.TrimSpace(trueFalse[1])
					if e.evaluateCondition(condition, vars) {
						return e.resolveValue(trueVal, vars)
					}
					return e.resolveValue(falseVal, vars)
				}
			}
		}
		if val, exists := vars[inner]; exists {
			return val
		}
		return match
	})
	return result, err
}

func parseFunctionArgs(argsStr string) []string {
	var args []string
	var current strings.Builder
	depth := 0
	inQuote := false
	quoteChar := rune(0)
	for _, char := range argsStr {
		if (char == '"' || char == '\'') && !inQuote {
			inQuote = true
			quoteChar = char
			continue
		}
		if char == quoteChar && inQuote {
			inQuote = false
			quoteChar = rune(0)
			continue
		}
		if char == '(' && !inQuote {
			depth++
		}
		if char == ')' && !inQuote {
			depth--
		}
		if char == ',' && !inQuote && depth == 0 {
			args = append(args, current.String())
			current.Reset()
			continue
		}
		current.WriteRune(char)
	}
	if current.Len() > 0 {
		args = append(args, current.String())
	}
	return args
}

func (e *ExpressionEngine) ExtractVariables(content string) []string {
	matches := expressionPattern.FindAllStringSubmatch(content, -1)
	variables := make(map[string]bool)
	for _, match := range matches {
		if len(match) < 2 {
			continue
		}
		inner := strings.TrimSpace(match[1])
		if matches := functionPattern.FindStringSubmatch(inner); len(matches) == 3 {
			argsStr := matches[2]
			args := parseFunctionArgs(argsStr)
			for _, arg := range args {
				arg = strings.TrimSpace(arg)
				if !strings.HasPrefix(arg, "\"") && !strings.HasPrefix(arg, "'") && !isNumeric(arg) {
					variables[arg] = true
				}
			}
		} else if strings.Contains(inner, "?") && strings.Contains(inner, ":") {
			parts := strings.SplitN(inner, "?", 2)
			if len(parts) == 2 {
				trueFalse := strings.SplitN(parts[1], ":", 2)
				if len(trueFalse) == 2 {
					conditionPart := strings.TrimSpace(parts[0])
					truePart := strings.TrimSpace(trueFalse[0])
					falsePart := strings.TrimSpace(trueFalse[1])
					for _, token := range extractTokens(conditionPart) {
						if !strings.HasPrefix(token, "\"") && !strings.HasPrefix(token, "'") && !isNumeric(token) {
							variables[token] = true
						}
					}
					if !strings.HasPrefix(truePart, "\"") && !strings.HasPrefix(truePart, "'") && !isNumeric(truePart) {
						variables[truePart] = true
					}
					if !strings.HasPrefix(falsePart, "\"") && !strings.HasPrefix(falsePart, "'") && !isNumeric(falsePart) {
						variables[falsePart] = true
					}
				}
			}
		} else {
			variables[inner] = true
		}
	}
	result := make([]string, 0, len(variables))
	for v := range variables {
		result = append(result, v)
	}
	return result
}

func isNumeric(s string) bool {
	_, err := strconv.ParseFloat(strings.TrimSpace(s), 64)
	return err == nil
}

func extractTokens(s string) []string {
	operators := []string{"==", "!=", ">=", "<=", ">", "<", " contains ", " startsWith ", " endsWith "}
	result := s
	for _, op := range operators {
		result = strings.ReplaceAll(result, op, " ")
	}
	fields := strings.Fields(result)
	return fields
}
