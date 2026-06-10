package trigger

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/solocoder/cloudci/internal/common/types"
)

type PayloadMapper struct{}

func NewPayloadMapper() *PayloadMapper {
	return &PayloadMapper{}
}

func (m *PayloadMapper) Map(payload []byte, headers map[string]string, mapping *types.PayloadMapping) (*types.InternalEvent, error) {
	var data map[string]interface{}
	if err := json.Unmarshal(payload, &data); err != nil {
		return nil, fmt.Errorf("invalid payload: %w", err)
	}

	if mapping.Condition != "" {
		conditionMet := m.evaluateCondition(data, mapping.Condition)
		if !conditionMet {
			return nil, nil
		}
	}

	eventType := mapping.EventType
	if mapping.EventHeader != "" {
		if headerVal, ok := headers[mapping.EventHeader]; ok {
			eventType = types.EventType(headerVal)
		}
	}

	event := &types.InternalEvent{
		EventSource: mapping.EventSource,
		EventType:   eventType,
		Payload:     data,
		ReceivedAt:  time.Now(),
	}

	if mapping.Deduplication != "" {
		if dedupVal, ok := m.extractValue(data, mapping.Deduplication); ok {
			event.DeduplicationKey = fmt.Sprintf("%v", dedupVal)
		}
	}

	for _, field := range mapping.Fields {
		value, ok := m.extractValue(data, field.Source)
		if !ok {
			if field.Required {
				return nil, fmt.Errorf("required field missing: %s", field.Source)
			}
			if field.Default != "" {
				value = field.Default
			} else {
				continue
			}
		}

		strValue := fmt.Sprintf("%v", value)
		if field.Transform != "" {
			strValue = m.applyTransform(strValue, field.Transform)
		}

		if err := m.setField(event, field.Target, strValue); err != nil {
			return nil, fmt.Errorf("failed to set field %s: %w", field.Target, err)
		}
	}

	if headers != nil {
		for k, v := range headers {
			event.Payload["headers_"+k] = v
		}
	}

	return event, nil
}

func (m *PayloadMapper) extractValue(data map[string]interface{}, path string) (interface{}, bool) {
	if path == "" {
		return nil, false
	}

	cleanPath := path
	if strings.HasPrefix(path, "$.") {
		cleanPath = path[2:]
	} else if strings.HasPrefix(path, "$") {
		cleanPath = path[1:]
	}

	parts := strings.Split(cleanPath, ".")
	var current interface{} = data

	for _, part := range parts {
		if current == nil {
			return nil, false
		}

		if currMap, ok := current.(map[string]interface{}); ok {
			current = currMap[part]
		} else if currArray, ok := current.([]interface{}); ok {
			idx := 0
			if _, err := fmt.Sscanf(part, "%d", &idx); err == nil && idx < len(currArray) {
				current = currArray[idx]
			} else {
				return nil, false
			}
		} else {
			return nil, false
		}
	}

	return current, current != nil
}

func (m *PayloadMapper) applyTransform(value, transform string) string {
	transforms := strings.Split(transform, ",")
	result := value

	for _, t := range transforms {
		t = strings.TrimSpace(t)
		result = m.applySingleTransform(result, t)
	}

	return result
}

func (m *PayloadMapper) applySingleTransform(value, transform string) string {
	switch {
	case strings.HasPrefix(transform, "trim_prefix:"):
		prefix := strings.TrimPrefix(transform, "trim_prefix:")
		return strings.TrimPrefix(value, prefix)
	case strings.HasPrefix(transform, "trim_suffix:"):
		suffix := strings.TrimPrefix(transform, "trim_suffix:")
		return strings.TrimSuffix(value, suffix)
	case strings.HasPrefix(transform, "replace:"):
		rest := strings.TrimPrefix(transform, "replace:")
		parts := strings.SplitN(rest, ":", 2)
		if len(parts) == 2 {
			return strings.ReplaceAll(value, parts[0], parts[1])
		}
		return value
	case transform == "lower":
		return strings.ToLower(value)
	case transform == "upper":
		return strings.ToUpper(value)
	case transform == "trim":
		return strings.TrimSpace(value)
	default:
		return value
	}
}

func (m *PayloadMapper) setField(event *types.InternalEvent, target, value string) error {
	switch target {
	case "project_id":
		event.ProjectID = value
	case "commit", "commit_sha":
		event.Commit = value
	case "branch":
		event.Branch = value
	case "tag":
		event.Tag = value
	case "ref":
		event.Ref = value
	case "message", "title":
		event.Message = value
	case "author", "author_name":
		event.Author = value
	case "author_email":
		event.AuthorEmail = value
	case "pipeline_id":
		event.PipelineID = types.ID(value)
	default:
		if event.Payload == nil {
			event.Payload = make(map[string]interface{})
		}
		event.Payload[target] = value
	}
	return nil
}

func (m *PayloadMapper) ValidateMapping(mapping *types.PayloadMapping) error {
	if mapping.EventSource == "" {
		return fmt.Errorf("event_source is required")
	}
	if mapping.EventType == "" && mapping.EventHeader == "" {
		return fmt.Errorf("either event_type or event_header must be specified")
	}
	if len(mapping.Fields) == 0 {
		return fmt.Errorf("at least one field mapping is required")
	}

	validTargets := map[string]bool{
		"project_id":   true,
		"commit":       true,
		"commit_sha":   true,
		"branch":       true,
		"tag":          true,
		"ref":          true,
		"message":      true,
		"title":        true,
		"author":       true,
		"author_name":  true,
		"author_email": true,
		"pipeline_id":  true,
	}

	for i, field := range mapping.Fields {
		if field.Source == "" {
			return fmt.Errorf("field %d: source is required", i)
		}
		if field.Target == "" {
			return fmt.Errorf("field %d: target is required", i)
		}
		if _, ok := validTargets[field.Target]; !ok {
		}
	}

	return nil
}

func (m *PayloadMapper) ExtractString(data map[string]interface{}, key string) string {
	if val, ok := m.extractValue(data, key); ok {
		return fmt.Sprintf("%v", val)
	}
	return ""
}

func (m *PayloadMapper) ExtractInt(data map[string]interface{}, key string) (int64, bool) {
	if val, ok := m.extractValue(data, key); ok {
		switch v := val.(type) {
		case float64:
			return int64(v), true
		case int:
			return int64(v), true
		case int64:
			return v, true
		case string:
			if i, err := strconv.ParseInt(v, 10, 64); err == nil {
				return i, true
			}
		}
	}
	return 0, false
}

func (m *PayloadMapper) ExtractBool(data map[string]interface{}, key string) (bool, bool) {
	if val, ok := m.extractValue(data, key); ok {
		switch v := val.(type) {
		case bool:
			return v, true
		case string:
			if b, err := strconv.ParseBool(v); err == nil {
				return b, true
			}
		}
	}
	return false, false
}

func (m *PayloadMapper) evaluateCondition(data map[string]interface{}, condition string) bool {
	parts := strings.Split(condition, "&&")
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if !m.evaluateSingleCondition(data, part) {
			return false
		}
	}
	return true
}

func (m *PayloadMapper) evaluateSingleCondition(data map[string]interface{}, condition string) bool {
	operators := []string{" == ", " != "}
	for _, op := range operators {
		if idx := strings.Index(condition, op); idx != -1 {
			left := strings.TrimSpace(condition[:idx])
			right := strings.TrimSpace(condition[idx+len(op):])

			leftVal, _ := m.extractValue(data, left)
			rightVal := strings.Trim(right, "'\"")

			leftStr := fmt.Sprintf("%v", leftVal)

			switch op {
			case " == ":
				return leftStr == rightVal || fmt.Sprintf("%T", leftVal) == "bool" && leftVal == (rightVal == "true")
			case " != ":
				return leftStr != rightVal
			}
		}
	}
	return true
}
