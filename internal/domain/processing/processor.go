package processing

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/dataplatform/engine/internal/common/errors"
	"github.com/dataplatform/engine/internal/domain"
)

type DataProcessorImpl struct {
	rules   map[string]*TransformRule
	schemas map[string]*Schema
	logger  domain.Logger
	mu      sync.RWMutex
}

func NewDataProcessor(logger domain.Logger) *DataProcessorImpl {
	return &DataProcessorImpl{
		rules:   make(map[string]*TransformRule),
		schemas: make(map[string]*Schema),
		logger:  logger,
	}
}

func (p *DataProcessorImpl) RegisterRule(rule *TransformRule) error {
	if rule == nil {
		return errors.New(errors.ErrCodeValidation, "rule cannot be nil")
	}
	if rule.ID == "" {
		return errors.New(errors.ErrCodeValidation, "rule ID cannot be empty")
	}
	if rule.Type == "" {
		return errors.New(errors.ErrCodeValidation, "rule type cannot be empty")
	}

	p.mu.Lock()
	defer p.mu.Unlock()

	p.rules[rule.ID] = rule
	p.logger.Info("Transform rule registered",
		domain.String("rule_id", rule.ID),
		domain.String("rule_name", rule.Name),
	)
	return nil
}

func (p *DataProcessorImpl) RegisterSchema(schema *Schema) error {
	if schema == nil {
		return errors.New(errors.ErrCodeValidation, "schema cannot be nil")
	}
	if schema.Name == "" {
		return errors.New(errors.ErrCodeValidation, "schema name cannot be empty")
	}

	p.mu.Lock()
	defer p.mu.Unlock()

	p.schemas[schema.Name] = schema
	p.logger.Info("Schema registered",
		domain.String("schema_name", schema.Name),
		domain.String("schema_version", schema.Version),
	)
	return nil
}

func (p *DataProcessorImpl) GetRule(ruleID string) (*TransformRule, bool) {
	p.mu.RLock()
	defer p.mu.RUnlock()

	rule, exists := p.rules[ruleID]
	if !exists {
		return nil, false
	}

	ruleCopy := *rule
	return &ruleCopy, true
}

func (p *DataProcessorImpl) GetSchema(schemaName string) (*Schema, bool) {
	p.mu.RLock()
	defer p.mu.RUnlock()

	schema, exists := p.schemas[schemaName]
	if !exists {
		return nil, false
	}

	schemaCopy := *schema
	return &schemaCopy, true
}

func (p *DataProcessorImpl) RemoveRule(ruleID string) bool {
	p.mu.Lock()
	defer p.mu.Unlock()

	if _, exists := p.rules[ruleID]; exists {
		delete(p.rules, ruleID)
		p.logger.Info("Transform rule removed", domain.String("rule_id", ruleID))
		return true
	}
	return false
}

func (p *DataProcessorImpl) RemoveSchema(schemaName string) bool {
	p.mu.Lock()
	defer p.mu.Unlock()

	if _, exists := p.schemas[schemaName]; exists {
		delete(p.schemas, schemaName)
		p.logger.Info("Schema removed", domain.String("schema_name", schemaName))
		return true
	}
	return false
}

func (p *DataProcessorImpl) ListRules() []*TransformRule {
	p.mu.RLock()
	defer p.mu.RUnlock()

	rules := make([]*TransformRule, 0, len(p.rules))
	for _, rule := range p.rules {
		ruleCopy := *rule
		rules = append(rules, &ruleCopy)
	}
	return rules
}

func (p *DataProcessorImpl) ListSchemas() []*Schema {
	p.mu.RLock()
	defer p.mu.RUnlock()

	schemas := make([]*Schema, 0, len(p.schemas))
	for _, schema := range p.schemas {
		schemaCopy := *schema
		schemas = append(schemas, &schemaCopy)
	}
	return schemas
}

func (p *DataProcessorImpl) Process(ctx context.Context, payload interface{}, rules []*TransformRule) (interface{}, error) {
	if err := p.Validate(ctx, payload); err != nil {
		return nil, err
	}

	currentData := payload
	for _, rule := range rules {
		if rule == nil {
			continue
		}
		if !rule.Enabled {
			continue
		}

		var err error
		currentData, err = p.Transform(ctx, currentData, rule)
		if err != nil {
			p.logger.Error("transform rule failed",
				domain.String("rule_id", rule.ID),
				domain.Error(err),
			)
			return nil, errors.Wrap(err, errors.ErrCodeInternal,
				fmt.Sprintf("transform rule %s failed", rule.ID))
		}

		select {
		case <-ctx.Done():
			return nil, errors.Wrap(ctx.Err(), errors.ErrCodeTimeout, "processing cancelled")
		default:
		}
	}

	return currentData, nil
}

func (p *DataProcessorImpl) Validate(ctx context.Context, payload interface{}) error {
	if payload == nil {
		return errors.New(errors.ErrCodeValidation, "payload cannot be nil")
	}

	switch v := payload.(type) {
	case map[string]interface{}:
		if len(v) == 0 {
			return errors.New(errors.ErrCodeValidation, "payload map cannot be empty")
		}
	case []interface{}:
		if len(v) == 0 {
			return errors.New(errors.ErrCodeValidation, "payload array cannot be empty")
		}
	case string:
		if strings.TrimSpace(v) == "" {
			return errors.New(errors.ErrCodeValidation, "payload string cannot be empty")
		}
	}

	return nil
}

func (p *DataProcessorImpl) Transform(ctx context.Context, data interface{}, rule *TransformRule) (interface{}, error) {
	if rule == nil {
		return nil, errors.New(errors.ErrCodeValidation, "rule cannot be nil")
	}

	var result interface{}
	var err error

	switch rule.Type {
	case "rename":
		result, err = p.renameFields(data, rule.Config)
	case "filter":
		result, err = p.filterFields(data, rule.Config)
	case "map":
		result, err = p.mapValues(data, rule.Config)
	case "convert":
		result, err = p.convertTypes(data, rule.Config)
	case "flatten":
		result, err = p.flatten(data, rule.Config)
	case "nest":
		result, err = p.nest(data, rule.Config)
	case "custom":
		result, err = p.customTransform(data, rule.Config)
	default:
		return nil, errors.New(errors.ErrCodeValidation,
			fmt.Sprintf("unknown transform type: %s", rule.Type))
	}

	if err != nil {
		return nil, err
	}

	return result, nil
}

func (p *DataProcessorImpl) Normalize(ctx context.Context, data interface{}, schema *Schema) (interface{}, error) {
	if schema == nil {
		return nil, errors.New(errors.ErrCodeValidation, "schema cannot be nil")
	}

	dataMap, ok := data.(map[string]interface{})
	if !ok {
		return nil, errors.New(errors.ErrCodeValidation, "data must be a map")
	}

	if len(schema.Fields) == 0 {
		return dataMap, nil
	}

	result := make(map[string]interface{})
	var firstErr error

	for _, field := range schema.Fields {
		if field == nil {
			continue
		}

		value, exists := dataMap[field.Name]

		if !exists {
			if field.Required {
				err := errors.New(errors.ErrCodeValidation,
					fmt.Sprintf("required field missing: %s", field.Name))
				if firstErr == nil {
					firstErr = err
				}
				p.logger.Warn("Required field missing", domain.String("field", field.Name))
				continue
			}
			if field.Default != nil {
				result[field.Name] = field.Default
			}
			continue
		}

		converted, err := p.convertValue(value, field.Type)
		if err != nil {
			err = errors.Wrap(err, errors.ErrCodeValidation,
				fmt.Sprintf("field %s type conversion failed", field.Name))
			if firstErr == nil {
				firstErr = err
			}
			p.logger.Warn("Field type conversion failed",
				domain.String("field", field.Name),
				domain.Error(err),
			)
			continue
		}
		result[field.Name] = converted
	}

	if firstErr != nil {
		return result, firstErr
	}

	return result, nil
}

func (p *DataProcessorImpl) renameFields(data interface{}, config map[string]interface{}) (interface{}, error) {
	dataMap, ok := data.(map[string]interface{})
	if !ok {
		return nil, errors.New(errors.ErrCodeValidation, "data must be a map")
	}

	if config == nil {
		return dataMap, nil
	}

	mappings, ok := config["mappings"].(map[string]interface{})
	if !ok {
		return dataMap, nil
	}

	result := make(map[string]interface{}, len(dataMap))
	for k, v := range dataMap {
		if newName, exists := mappings[k]; exists {
			if newNameStr, ok := newName.(string); ok && newNameStr != "" {
				result[newNameStr] = v
				continue
			}
		}
		result[k] = v
	}

	return result, nil
}

func (p *DataProcessorImpl) filterFields(data interface{}, config map[string]interface{}) (interface{}, error) {
	dataMap, ok := data.(map[string]interface{})
	if !ok {
		return nil, errors.New(errors.ErrCodeValidation, "data must be a map")
	}

	if config == nil {
		return dataMap, nil
	}

	include, _ := config["include"].([]interface{})
	exclude, _ := config["exclude"].([]interface{})

	if len(include) > 0 {
		result := make(map[string]interface{}, len(include))
		for _, field := range include {
			fieldName, ok := field.(string)
			if !ok || fieldName == "" {
				continue
			}
			if v, exists := dataMap[fieldName]; exists {
				result[fieldName] = v
			}
		}
		return result, nil
	}

	if len(exclude) > 0 {
		excludeSet := make(map[string]bool, len(exclude))
		for _, field := range exclude {
			if fieldName, ok := field.(string); ok {
				excludeSet[fieldName] = true
			}
		}

		result := make(map[string]interface{}, len(dataMap))
		for k, v := range dataMap {
			if !excludeSet[k] {
				result[k] = v
			}
		}
		return result, nil
	}

	return dataMap, nil
}

func (p *DataProcessorImpl) mapValues(data interface{}, config map[string]interface{}) (interface{}, error) {
	dataMap, ok := data.(map[string]interface{})
	if !ok {
		return nil, errors.New(errors.ErrCodeValidation, "data must be a map")
	}

	if config == nil {
		return dataMap, nil
	}

	fieldMappings, ok := config["field_mappings"].(map[string]interface{})
	if !ok {
		return dataMap, nil
	}

	result := make(map[string]interface{}, len(dataMap))
	for k, v := range dataMap {
		if mappings, exists := fieldMappings[k]; exists {
			if mappingMap, ok := mappings.(map[string]interface{}); ok {
				if newValue, exists := mappingMap[fmt.Sprintf("%v", v)]; exists {
					result[k] = newValue
					continue
				}
			}
		}
		result[k] = v
	}

	return result, nil
}

func (p *DataProcessorImpl) convertTypes(data interface{}, config map[string]interface{}) (interface{}, error) {
	dataMap, ok := data.(map[string]interface{})
	if !ok {
		return nil, errors.New(errors.ErrCodeValidation, "data must be a map")
	}

	if config == nil {
		return dataMap, nil
	}

	typeMappings, ok := config["type_mappings"].(map[string]interface{})
	if !ok {
		return dataMap, nil
	}

	result := make(map[string]interface{}, len(dataMap))
	var firstErr error

	for k, v := range dataMap {
		if targetType, exists := typeMappings[k]; exists {
			targetTypeStr, ok := targetType.(string)
			if !ok {
				result[k] = v
				continue
			}

			converted, err := p.convertValue(v, targetTypeStr)
			if err != nil {
				if firstErr == nil {
					firstErr = err
				}
				result[k] = v
				continue
			}
			result[k] = converted
		} else {
			result[k] = v
		}
	}

	return result, firstErr
}

func (p *DataProcessorImpl) convertValue(value interface{}, targetType string) (interface{}, error) {
	if value == nil {
		return nil, nil
	}

	switch strings.ToLower(targetType) {
	case "string":
		return fmt.Sprintf("%v", value), nil

	case "int", "integer":
		switch v := value.(type) {
		case float64:
			return int(v), nil
		case string:
			return strconv.Atoi(strings.TrimSpace(v))
		case int:
			return v, nil
		case int64:
			return int(v), nil
		case float32:
			return int(v), nil
		case bool:
			if v {
				return 1, nil
			}
			return 0, nil
		default:
			return nil, errors.New(errors.ErrCodeValidation,
				fmt.Sprintf("cannot convert %T to int", value))
		}

	case "float", "float64", "number":
		switch v := value.(type) {
		case float64:
			return v, nil
		case string:
			return strconv.ParseFloat(strings.TrimSpace(v), 64)
		case int:
			return float64(v), nil
		case int64:
			return float64(v), nil
		case float32:
			return float64(v), nil
		case bool:
			if v {
				return 1.0, nil
			}
			return 0.0, nil
		default:
			return nil, errors.New(errors.ErrCodeValidation,
				fmt.Sprintf("cannot convert %T to float64", value))
		}

	case "bool", "boolean":
		switch v := value.(type) {
		case bool:
			return v, nil
		case string:
			return strconv.ParseBool(strings.TrimSpace(v))
		case int, int64, float64, float32:
			return fmt.Sprintf("%v", v) != "0", nil
		default:
			return nil, errors.New(errors.ErrCodeValidation,
				fmt.Sprintf("cannot convert %T to bool", value))
		}

	case "object", "map":
		switch v := value.(type) {
		case map[string]interface{}:
			return v, nil
		case string:
			if strings.TrimSpace(v) == "" {
				return make(map[string]interface{}), nil
			}
			var result map[string]interface{}
			err := json.Unmarshal([]byte(v), &result)
			if err != nil {
				return nil, errors.Wrap(err, errors.ErrCodeValidation,
					"invalid JSON object")
			}
			return result, nil
		default:
			return nil, errors.New(errors.ErrCodeValidation,
				fmt.Sprintf("cannot convert %T to object", value))
		}

	case "array", "list":
		switch v := value.(type) {
		case []interface{}:
			return v, nil
		case string:
			if strings.TrimSpace(v) == "" {
				return make([]interface{}, 0), nil
			}
			var result []interface{}
			err := json.Unmarshal([]byte(v), &result)
			if err != nil {
				return nil, errors.Wrap(err, errors.ErrCodeValidation,
					"invalid JSON array")
			}
			return result, nil
		default:
			return []interface{}{value}, nil
		}

	default:
		return value, nil
	}
}

func (p *DataProcessorImpl) flatten(data interface{}, config map[string]interface{}) (interface{}, error) {
	dataMap, ok := data.(map[string]interface{})
	if !ok {
		return nil, errors.New(errors.ErrCodeValidation, "data must be a map")
	}

	delimiter := "."
	if config != nil {
		if d, ok := config["delimiter"].(string); ok && d != "" {
			delimiter = d
		}
	}

	result := make(map[string]interface{})
	p.flattenHelper(dataMap, "", delimiter, result)
	return result, nil
}

func (p *DataProcessorImpl) flattenHelper(data map[string]interface{}, prefix, delimiter string, result map[string]interface{}) {
	for k, v := range data {
		if k == "" {
			continue
		}

		key := k
		if prefix != "" {
			key = prefix + delimiter + k
		}

		if nested, ok := v.(map[string]interface{}); ok && len(nested) > 0 {
			p.flattenHelper(nested, key, delimiter, result)
		} else {
			result[key] = v
		}
	}
}

func (p *DataProcessorImpl) nest(data interface{}, config map[string]interface{}) (interface{}, error) {
	dataMap, ok := data.(map[string]interface{})
	if !ok {
		return nil, errors.New(errors.ErrCodeValidation, "data must be a map")
	}

	delimiter := "."
	if config != nil {
		if d, ok := config["delimiter"].(string); ok && d != "" {
			delimiter = d
		}
	}

	result := make(map[string]interface{})
	for k, v := range dataMap {
		if k == "" {
			continue
		}

		parts := strings.Split(k, delimiter)
		if len(parts) == 0 {
			continue
		}

		current := result
		for i, part := range parts {
			if part == "" {
				break
			}
			if i == len(parts)-1 {
				current[part] = v
			} else {
				if _, exists := current[part]; !exists {
					current[part] = make(map[string]interface{})
				}
				next, ok := current[part].(map[string]interface{})
				if !ok {
					next = make(map[string]interface{})
					current[part] = next
				}
				current = next
			}
		}
	}

	return result, nil
}

func (p *DataProcessorImpl) customTransform(data interface{}, config map[string]interface{}) (interface{}, error) {
	return data, nil
}

func (p *DataProcessorImpl) ExecuteHandler(ctx context.Context, req *ProcessRequest) (*ProcessResult, error) {
	if req == nil {
		return nil, errors.New(errors.ErrCodeValidation, "request cannot be nil")
	}
	if req.TraceID == "" {
		return nil, errors.New(errors.ErrCodeValidation, "trace ID cannot be empty")
	}

	start := time.Now()
	defer func() {
		if r := recover(); r != nil {
			p.logger.Error("ExecuteHandler panicked",
				domain.String("trace_id", req.TraceID),
				domain.Any("panic", r),
			)
		}
	}()

	var rules []*TransformRule
	for _, id := range req.RuleIDs {
		if id == "" {
			continue
		}
		if rule, exists := p.GetRule(id); exists {
			rules = append(rules, rule)
		}
	}

	result, err := p.Process(ctx, req.Payload, rules)
	if err != nil {
		return nil, err
	}

	if req.SchemaName != "" {
		if schema, exists := p.GetSchema(req.SchemaName); exists {
			normalized, normErr := p.Normalize(ctx, result, schema)
			if normalized != nil {
				result = normalized
			}
			if normErr != nil && err == nil {
				err = normErr
			}
		}
	}

	processResult := &ProcessResult{
		TraceID:    req.TraceID,
		Output:     result,
		DurationMs: time.Since(start).Milliseconds(),
	}

	if err != nil {
		return processResult, err
	}

	return processResult, nil
}

func (p *DataProcessorImpl) Clear() {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.rules = make(map[string]*TransformRule)
	p.schemas = make(map[string]*Schema)
	p.logger.Info("Data processor cleared")
}
