package filter

import (
	"regexp"
	"strings"
	"sync"

	"loglevelplatform/internal/modules/log_pipeline/models"
)

type FilterEngine interface {
	AddRule(rule models.FilterRule)
	Matches(entry *models.LogEntry) bool
	Count() int
}

type filterEngine struct {
	mu    sync.RWMutex
	rules []models.FilterRule
}

func NewFilterEngine() FilterEngine {
	return &filterEngine{
		rules: make([]models.FilterRule, 0),
	}
}

func (e *filterEngine) AddRule(rule models.FilterRule) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.rules = append(e.rules, rule)
}

func (e *filterEngine) Matches(entry *models.LogEntry) bool {
	e.mu.RLock()
	defer e.mu.RUnlock()

	if len(e.rules) == 0 {
		return true
	}

	for _, rule := range e.rules {
		fieldValue := getFieldValue(entry, rule.Field)
		if !matchFilter(fieldValue, rule.Operator, rule.Value) {
			return false
		}
	}
	return true
}

func (e *filterEngine) Count() int {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return len(e.rules)
}

func getFieldValue(entry *models.LogEntry, field string) interface{} {
	switch field {
	case "level":
		return entry.Level
	case "message":
		return entry.Message
	case "service":
		return entry.Service
	case "host":
		return entry.Host
	case "trace_id":
		return entry.TraceID
	default:
		if entry.Fields != nil {
			if val, ok := entry.Fields[field]; ok {
				return val
			}
		}
		if entry.Tags != nil {
			if val, ok := entry.Tags[field]; ok {
				return val
			}
		}
	}
	return nil
}

func matchFilter(value interface{}, op models.FilterOperator, expected interface{}) bool {
	valStr, ok := value.(string)
	if !ok {
		return false
	}

	expStr, ok := expected.(string)
	if !ok {
		return false
	}

	switch op {
	case models.OpEquals:
		return valStr == expStr
	case models.OpNotEquals:
		return valStr != expStr
	case models.OpContains:
		return strings.Contains(valStr, expStr)
	case models.OpNotContains:
		return !strings.Contains(valStr, expStr)
	case models.OpStartsWith:
		return strings.HasPrefix(valStr, expStr)
	case models.OpEndsWith:
		return strings.HasSuffix(valStr, expStr)
	case models.OpRegex:
		matched, _ := regexp.MatchString(expStr, valStr)
		return matched
	default:
		return true
	}
}
