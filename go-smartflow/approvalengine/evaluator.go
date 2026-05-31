package approvalengine

import (
	"fmt"
	"reflect"
)

type ConditionEvaluator struct{}

func NewConditionEvaluator() *ConditionEvaluator {
	return &ConditionEvaluator{}
}

func (e *ConditionEvaluator) Evaluate(conditions []Condition, data map[string]interface{}) bool {
	if len(conditions) == 0 {
		return true
	}

	for _, cond := range conditions {
		if !e.evaluateSingle(cond, data) {
			return false
		}
	}

	return true
}

func (e *ConditionEvaluator) evaluateSingle(cond Condition, data map[string]interface{}) bool {
	value, exists := data[cond.Field]
	if !exists {
		return false
	}

	switch cond.Operator {
	case "==", "eq":
		return e.equals(value, cond.Value)
	case "!=", "ne":
		return !e.equals(value, cond.Value)
	case ">", "gt":
		return e.greaterThan(value, cond.Value)
	case ">=", "gte":
		return e.greaterThanOrEqual(value, cond.Value)
	case "<", "lt":
		return e.lessThan(value, cond.Value)
	case "<=", "lte":
		return e.lessThanOrEqual(value, cond.Value)
	case "contains":
		return e.contains(value, cond.Value)
	case "in":
		return e.in(value, cond.Value)
	default:
		return false
	}
}

func (e *ConditionEvaluator) equals(a, b interface{}) bool {
	aVal := reflect.ValueOf(a)
	bVal := reflect.ValueOf(b)

	if aVal.Kind() == bVal.Kind() {
		return reflect.DeepEqual(a, b)
	}

	aFloat, aOK := e.toFloat(a)
	bFloat, bOK := e.toFloat(b)
	if aOK && bOK {
		return aFloat == bFloat
	}

	return fmt.Sprintf("%v", a) == fmt.Sprintf("%v", b)
}

func (e *ConditionEvaluator) greaterThan(a, b interface{}) bool {
	aFloat, aOK := e.toFloat(a)
	bFloat, bOK := e.toFloat(b)
	if !aOK || !bOK {
		return false
	}
	return aFloat > bFloat
}

func (e *ConditionEvaluator) greaterThanOrEqual(a, b interface{}) bool {
	aFloat, aOK := e.toFloat(a)
	bFloat, bOK := e.toFloat(b)
	if !aOK || !bOK {
		return false
	}
	return aFloat >= bFloat
}

func (e *ConditionEvaluator) lessThan(a, b interface{}) bool {
	aFloat, aOK := e.toFloat(a)
	bFloat, bOK := e.toFloat(b)
	if !aOK || !bOK {
		return false
	}
	return aFloat < bFloat
}

func (e *ConditionEvaluator) lessThanOrEqual(a, b interface{}) bool {
	aFloat, aOK := e.toFloat(a)
	bFloat, bOK := e.toFloat(b)
	if !aOK || !bOK {
		return false
	}
	return aFloat <= bFloat
}

func (e *ConditionEvaluator) contains(a, b interface{}) bool {
	aStr, aOK := a.(string)
	bStr, bOK := b.(string)
	if !aOK || !bOK {
		return false
	}
	return len(aStr) > 0 && len(bStr) > 0 && containsString(aStr, bStr)
}

func (e *ConditionEvaluator) in(a, b interface{}) bool {
	bSlice, ok := b.([]interface{})
	if !ok {
		return false
	}
	for _, item := range bSlice {
		if e.equals(a, item) {
			return true
		}
	}
	return false
}

func (e *ConditionEvaluator) toFloat(v interface{}) (float64, bool) {
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
	default:
		return 0, false
	}
}

func containsString(s, substr string) bool {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return true
		}
	}
	return false
}
