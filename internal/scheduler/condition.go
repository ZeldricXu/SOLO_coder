package scheduler

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"

	"github.com/solocoder/cloudci/internal/common/types"
)

type ConditionEvaluator struct{}

func NewConditionEvaluator() *ConditionEvaluator {
	return &ConditionEvaluator{}
}

type EvaluationContext struct {
	StageResults  map[string]StageResult
	Variables     map[string]string
	Event         *types.InternalEvent
	PipelineName  string
	ExecutionID   string
}

func (e *ConditionEvaluator) Evaluate(condition string, ctx *EvaluationContext) (bool, error) {
	if condition == "" {
		return true, nil
	}

	expr := strings.TrimSpace(condition)
	if expr == "" {
		return true, nil
	}

	return e.evaluateOr(expr, ctx)
}

func (e *ConditionEvaluator) evaluateOr(expr string, ctx *EvaluationContext) (bool, error) {
	parts := e.splitTopLevel(expr, "||")
	if len(parts) == 1 {
		return e.evaluateAnd(parts[0], ctx)
	}

	for _, part := range parts {
		result, err := e.evaluateAnd(strings.TrimSpace(part), ctx)
		if err != nil {
			return false, err
		}
		if result {
			return true, nil
		}
	}
	return false, nil
}

func (e *ConditionEvaluator) evaluateAnd(expr string, ctx *EvaluationContext) (bool, error) {
	parts := e.splitTopLevel(expr, "&&")
	if len(parts) == 1 {
		return e.evaluateNot(parts[0], ctx)
	}

	for _, part := range parts {
		result, err := e.evaluateNot(strings.TrimSpace(part), ctx)
		if err != nil {
			return false, err
		}
		if !result {
			return false, nil
		}
	}
	return true, nil
}

func (e *ConditionEvaluator) evaluateNot(expr string, ctx *EvaluationContext) (bool, error) {
	expr = strings.TrimSpace(expr)
	if strings.HasPrefix(expr, "!") {
		result, err := e.evaluateParenthesized(strings.TrimPrefix(expr, "!"), ctx)
		if err != nil {
			return false, err
		}
		return !result, nil
	}
	return e.evaluateParenthesized(expr, ctx)
}

func (e *ConditionEvaluator) evaluateParenthesized(expr string, ctx *EvaluationContext) (bool, error) {
	expr = strings.TrimSpace(expr)
	if strings.HasPrefix(expr, "(") && strings.HasSuffix(expr, ")") {
		depth := 0
		for i, c := range expr {
			if c == '(' {
				depth++
			} else if c == ')' {
				depth--
				if depth == 0 && i == len(expr)-1 {
					return e.evaluateOr(strings.TrimSpace(expr[1:i]), ctx)
				}
			}
		}
	}
	return e.evaluateComparison(expr, ctx)
}

func (e *ConditionEvaluator) evaluateComparison(expr string, ctx *EvaluationContext) (bool, error) {
	expr = strings.TrimSpace(expr)

	if expr == "true" || expr == "false" {
		return strconv.ParseBool(expr)
	}

	operators := []string{"==", "!=", ">=", "<=", ">", "<", "=~", "!~", " not in ", " in ", " contains ", " startsWith ", " endsWith "}

	for _, op := range operators {
		if idx := strings.Index(expr, op); idx != -1 {
			left := strings.TrimSpace(expr[:idx])
			right := strings.TrimSpace(expr[idx+len(op):])

			leftVal, err := e.resolveValue(left, ctx)
			if err != nil {
				return false, err
			}
			rightVal, err := e.resolveValue(right, ctx)
			if err != nil {
				return false, err
			}

			return e.compare(leftVal, rightVal, op)
		}
	}

	val, err := e.resolveValue(expr, ctx)
	if err != nil {
		return false, err
	}
	return e.isTruthy(val), nil
}

func (e *ConditionEvaluator) resolveValue(expr string, ctx *EvaluationContext) (interface{}, error) {
	expr = strings.TrimSpace(expr)

	if strings.HasPrefix(expr, "\"") && strings.HasSuffix(expr, "\"") {
		return expr[1 : len(expr)-1], nil
	}
	if strings.HasPrefix(expr, "'") && strings.HasSuffix(expr, "'") {
		return expr[1 : len(expr)-1], nil
	}

	if i, err := strconv.ParseInt(expr, 10, 64); err == nil {
		return i, nil
	}
	if f, err := strconv.ParseFloat(expr, 64); err == nil {
		return f, nil
	}
	if b, err := strconv.ParseBool(expr); err == nil {
		return b, nil
	}

	if strings.HasPrefix(expr, "env.") {
		key := strings.TrimPrefix(expr, "env.")
		if ctx.Variables != nil {
			if val, ok := ctx.Variables[key]; ok {
				return val, nil
			}
		}
		return "", nil
	}

	if strings.HasPrefix(expr, "event.") {
		key := strings.TrimPrefix(expr, "event.")
		return e.getEventField(key, ctx.Event), nil
	}

	if strings.HasPrefix(expr, "stages.") {
		rest := strings.TrimPrefix(expr, "stages.")
		parts := strings.SplitN(rest, ".", 2)
		if len(parts) == 2 {
			stageName := parts[0]
			field := parts[1]
			if ctx.StageResults != nil {
				if result, ok := ctx.StageResults[stageName]; ok {
					return e.getStageResultField(result, field), nil
				}
			}
			return nil, nil
		}
	}

	if ctx.Variables != nil {
		if val, ok := ctx.Variables[expr]; ok {
			return val, nil
		}
	}

	return expr, nil
}

func (e *ConditionEvaluator) getEventField(key string, event *types.InternalEvent) interface{} {
	if event == nil {
		return ""
	}

	switch key {
	case "project_id", "project":
		return event.ProjectID
	case "commit", "commit_sha":
		return event.Commit
	case "branch":
		return event.Branch
	case "tag":
		return event.Tag
	case "ref":
		return event.Ref
	case "message", "title":
		return event.Message
	case "author":
		return event.Author
	case "author_email":
		return event.AuthorEmail
	case "source":
		return string(event.EventSource)
	case "type":
		return string(event.EventType)
	}

	if event.Payload != nil {
		if val, ok := event.Payload[key]; ok {
			return val
		}
	}
	return ""
}

func (e *ConditionEvaluator) getStageResultField(result StageResult, field string) interface{} {
	switch field {
	case "status":
		return string(result.Status)
	case "success":
		return result.Status == types.StageStatusSuccess
	case "failed":
		return result.Status == types.StageStatusFailed
	case "skipped":
		return result.Status == types.StageStatusSkipped
	case "cancelled":
		return result.Status == types.StageStatusCancelled
	case "exit_code", "exitCode":
		return result.ExitCode
	case "duration":
		return result.Duration
	case "error":
		return result.Error
	}

	if result.Output != nil {
		if val, ok := result.Output[field]; ok {
			return val
		}
	}
	return ""
}

func (e *ConditionEvaluator) compare(left, right interface{}, op string) (bool, error) {
	leftNum, leftIsNum := e.toNumber(left)
	rightNum, rightIsNum := e.toNumber(right)

	if leftIsNum && rightIsNum {
		switch op {
		case "==":
			return leftNum == rightNum, nil
		case "!=":
			return leftNum != rightNum, nil
		case ">=":
			return leftNum >= rightNum, nil
		case "<=":
			return leftNum <= rightNum, nil
		case ">":
			return leftNum > rightNum, nil
		case "<":
			return leftNum < rightNum, nil
		}
	}

	leftStr := fmt.Sprintf("%v", left)
	rightStr := fmt.Sprintf("%v", right)

	switch op {
	case "==":
		return leftStr == rightStr, nil
	case "!=":
		return leftStr != rightStr, nil
	case "=~":
		matched, err := regexp.MatchString(rightStr, leftStr)
		if err != nil {
			return false, fmt.Errorf("invalid regex: %w", err)
		}
		return matched, nil
	case "!~":
		matched, err := regexp.MatchString(rightStr, leftStr)
		if err != nil {
			return false, fmt.Errorf("invalid regex: %w", err)
		}
		return !matched, nil
	case " in ":
		return strings.Contains(rightStr, leftStr), nil
	case " not in ":
		return !strings.Contains(rightStr, leftStr), nil
	case " contains ":
		return strings.Contains(leftStr, rightStr), nil
	case " startsWith ":
		return strings.HasPrefix(leftStr, rightStr), nil
	case " endsWith ":
		return strings.HasSuffix(leftStr, rightStr), nil
	case ">=", "<=", ">", "<":
		return false, fmt.Errorf("cannot compare non-numeric values with %s", op)
	}

	return false, fmt.Errorf("unknown operator: %s", op)
}

func (e *ConditionEvaluator) toNumber(v interface{}) (float64, bool) {
	switch val := v.(type) {
	case int:
		return float64(val), true
	case int32:
		return float64(val), true
	case int64:
		return float64(val), true
	case float32:
		return float64(val), true
	case float64:
		return val, true
	case string:
		if f, err := strconv.ParseFloat(val, 64); err == nil {
			return f, true
		}
	}
	return 0, false
}

func (e *ConditionEvaluator) isTruthy(v interface{}) bool {
	if v == nil {
		return false
	}
	switch val := v.(type) {
	case bool:
		return val
	case string:
		return val != "" && val != "false" && val != "0"
	case int, int32, int64:
		return val != 0
	case float32, float64:
		return val != 0
	}
	return true
}

func (e *ConditionEvaluator) splitTopLevel(expr string, sep string) []string {
	var parts []string
	depth := 0
	inQuote := false
	quoteChar := rune(0)
	start := 0

	for i, c := range expr {
		if (c == '"' || c == '\'') && (i == 0 || expr[i-1] != '\\') {
			if !inQuote {
				inQuote = true
				quoteChar = c
			} else if c == quoteChar {
				inQuote = false
			}
			continue
		}
		if inQuote {
			continue
		}
		if c == '(' {
			depth++
		} else if c == ')' {
			depth--
		} else if depth == 0 && strings.HasPrefix(expr[i:], sep) {
			parts = append(parts, expr[start:i])
			start = i + len(sep)
			i += len(sep) - 1
		}
	}

	if start < len(expr) {
		parts = append(parts, expr[start:])
	}

	return parts
}

func (e *ConditionEvaluator) EvaluateStageCondition(
	stageDef types.StageDefinition,
	stageResults map[string]StageResult,
	variables map[string]string,
	event *types.InternalEvent,
) (bool, string, error) {
	if stageDef.Condition == "" {
		return true, "", nil
	}

	ctx := &EvaluationContext{
		StageResults: stageResults,
		Variables:    variables,
		Event:        event,
	}

	result, err := e.Evaluate(stageDef.Condition, ctx)
	if err != nil {
		return false, "", fmt.Errorf("condition evaluation failed: %w", err)
	}

	reason := ""
	if !result {
		reason = fmt.Sprintf("condition not met: %s", stageDef.Condition)
	}

	return result, reason, nil
}
