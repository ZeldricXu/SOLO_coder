package core

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/solocoder/logrotate/internal/domain"
)

type TransformRule struct {
	SourceField string      `json:"source_field"`
	TargetField string      `json:"target_field"`
	Transform   string      `json:"transform"`
	DefaultValue interface{} `json:"default_value"`
	Required    bool        `json:"required"`
}

type ValidationRule struct {
	Field   string      `json:"field"`
	Type    string      `json:"type"`
	Pattern string      `json:"pattern"`
	Min     interface{} `json:"min"`
	Max     interface{} `json:"max"`
	Options []string    `json:"options"`
}

type Schema struct {
	Name        string            `json:"name"`
	Transforms  []TransformRule   `json:"transforms"`
	Validations []ValidationRule  `json:"validations"`
	Relations   map[string]string `json:"relations"`
}

type ProcessingResult struct {
	Success   bool                   `json:"success"`
	Record    *domain.DataRecord     `json:"record,omitempty"`
	Errors    []string               `json:"errors,omitempty"`
	MetaData  map[string]interface{} `json:"meta_data"`
	StartTime time.Time              `json:"start_time"`
	EndTime   time.Time              `json:"end_time"`
}

type Processor struct {
	mu         sync.RWMutex
	schemas    map[string]*Schema
	transforms map[string]func(interface{}) (interface{}, error)
	validators map[string]func(interface{}) error
	eventBus   chan ProcessingEvent
}

type ProcessingEvent struct {
	Type      string                 `json:"type"`
	RecordID  string                 `json:"record_id"`
	Schema    string                 `json:"schema"`
	MetaData  map[string]interface{} `json:"meta_data"`
	Timestamp time.Time              `json:"timestamp"`
}

func NewProcessor() *Processor {
	p := &Processor{
		schemas:    make(map[string]*Schema),
		transforms: make(map[string]func(interface{}) (interface{}, error)),
		validators: make(map[string]func(interface{}) error),
		eventBus:   make(chan ProcessingEvent, 1000),
	}

	p.registerBuiltinTransforms()
	p.registerBuiltinValidators()

	return p
}

func (p *Processor) registerBuiltinTransforms() {
	p.transforms["trim"] = func(v interface{}) (interface{}, error) {
		if s, ok := v.(string); ok {
			return strings.TrimSpace(s), nil
		}
		return v, nil
	}

	p.transforms["lower"] = func(v interface{}) (interface{}, error) {
		if s, ok := v.(string); ok {
			return strings.ToLower(s), nil
		}
		return v, nil
	}

	p.transforms["upper"] = func(v interface{}) (interface{}, error) {
		if s, ok := v.(string); ok {
			return strings.ToUpper(s), nil
		}
		return v, nil
	}

	p.transforms["int"] = func(v interface{}) (interface{}, error) {
		switch val := v.(type) {
		case string:
			return strconv.Atoi(val)
		case float64:
			return int(val), nil
		default:
			return v, nil
		}
	}

	p.transforms["float"] = func(v interface{}) (interface{}, error) {
		switch val := v.(type) {
		case string:
			return strconv.ParseFloat(val, 64)
		case int:
			return float64(val), nil
		default:
			return v, nil
		}
	}

	p.transforms["string"] = func(v interface{}) (interface{}, error) {
		return fmt.Sprintf("%v", v), nil
	}

	p.transforms["bool"] = func(v interface{}) (interface{}, error) {
		switch val := v.(type) {
		case string:
			return strconv.ParseBool(val)
		case int:
			return val != 0, nil
		default:
			return v, nil
		}
	}
}

func (p *Processor) registerBuiltinValidators() {
	p.validators["email"] = func(v interface{}) error {
		s, ok := v.(string)
		if !ok {
			return errors.New("not a string")
		}
		if !strings.Contains(s, "@") {
			return errors.New("invalid email format")
		}
		return nil
	}

	p.validators["url"] = func(v interface{}) error {
		s, ok := v.(string)
		if !ok {
			return errors.New("not a string")
		}
		if !strings.HasPrefix(s, "http://") && !strings.HasPrefix(s, "https://") {
			return errors.New("invalid URL format")
		}
		return nil
	}

	p.validators["phone"] = func(v interface{}) error {
		s, ok := v.(string)
		if !ok {
			return errors.New("not a string")
		}
		if len(s) < 7 {
			return errors.New("phone number too short")
		}
		return nil
	}

	p.validators["not_empty"] = func(v interface{}) error {
		if v == nil {
			return errors.New("value is nil")
		}
		if s, ok := v.(string); ok && s == "" {
			return errors.New("string is empty")
		}
		return nil
	}
}

func (p *Processor) RegisterSchema(schema *Schema) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if schema.Name == "" {
		return errors.New("schema name is required")
	}
	p.schemas[schema.Name] = schema
	return nil
}

func (p *Processor) GetSchema(name string) (*Schema, bool) {
	p.mu.RLock()
	defer p.mu.RUnlock()

	schema, ok := p.schemas[name]
	return schema, ok
}

func (p *Processor) RegisterTransform(name string, fn func(interface{}) (interface{}, error)) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.transforms[name] = fn
}

func (p *Processor) RegisterValidator(name string, fn func(interface{}) error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.validators[name] = fn
}

func (p *Processor) Process(ctx context.Context, rawData map[string]interface{}, schemaName string) *ProcessingResult {
	startTime := time.Now()
	result := &ProcessingResult{
		Success:   false,
		StartTime: startTime,
		MetaData:  make(map[string]interface{}),
	}

	schema, ok := p.GetSchema(schemaName)
	if !ok {
		result.Errors = append(result.Errors, fmt.Sprintf("schema %s not found", schemaName))
		result.EndTime = time.Now()
		return result
	}

	record := &domain.DataRecord{
		ID:         uuid.New().String(),
		RawData:    rawData,
		Normalized: make(map[string]interface{}),
		Schema:     schemaName,
		Timestamp:  startTime,
	}

	if err := p.applyTransforms(rawData, record.Normalized, schema.Transforms); err != nil {
		result.Errors = append(result.Errors, err.Error())
		result.EndTime = time.Now()
		return result
	}

	if validationErrors := p.validate(record.Normalized, schema.Validations); len(validationErrors) > 0 {
		result.Errors = append(result.Errors, validationErrors...)
		result.EndTime = time.Now()
		return result
	}

	result.Success = true
	result.Record = record
	result.EndTime = time.Now()
	result.MetaData["processing_time_ms"] = result.EndTime.Sub(result.StartTime).Milliseconds()

	p.emitEvent("record.processed", record.ID, schemaName, result.MetaData)

	return result
}

func (p *Processor) applyTransforms(source, target map[string]interface{}, rules []TransformRule) error {
	for _, rule := range rules {
		value, exists := getNestedValue(source, rule.SourceField)

		if !exists {
			if rule.Required {
				return fmt.Errorf("required field %s missing", rule.SourceField)
			}
			if rule.DefaultValue != nil {
				setNestedValue(target, rule.TargetField, rule.DefaultValue)
			}
			continue
		}

		if rule.Transform != "" {
			transformFn, ok := p.transforms[rule.Transform]
			if ok {
				transformed, err := transformFn(value)
				if err != nil {
					return fmt.Errorf("transform %s failed for field %s: %w", rule.Transform, rule.SourceField, err)
				}
				value = transformed
			}
		}

		setNestedValue(target, rule.TargetField, value)
	}
	return nil
}

func (p *Processor) validate(data map[string]interface{}, rules []ValidationRule) []string {
	var errors []string

	for _, rule := range rules {
		value, exists := getNestedValue(data, rule.Field)
		if !exists {
			errors = append(errors, fmt.Sprintf("field %s missing", rule.Field))
			continue
		}

		if rule.Type != "" {
			if err := validateType(value, rule.Type); err != nil {
				errors = append(errors, fmt.Sprintf("field %s: %v", rule.Field, err))
			}
		}

		if len(rule.Options) > 0 {
			strVal := fmt.Sprintf("%v", value)
			found := false
			for _, opt := range rule.Options {
				if strVal == opt {
					found = true
					break
				}
			}
			if !found {
				errors = append(errors, fmt.Sprintf("field %s: value %s not in options %v", rule.Field, strVal, rule.Options))
			}
		}
	}

	return errors
}

func validateType(value interface{}, typ string) error {
	switch typ {
	case "string":
		if _, ok := value.(string); !ok {
			return errors.New("expected string")
		}
	case "int":
		switch value.(type) {
		case int, int32, int64, float64:
		default:
			return errors.New("expected integer")
		}
	case "float":
		switch value.(type) {
		case float64, int, int32, int64:
		default:
			return errors.New("expected float")
		}
	case "bool":
		if _, ok := value.(bool); !ok {
			return errors.New("expected boolean")
		}
	case "array":
		if reflect.TypeOf(value).Kind() != reflect.Slice {
			return errors.New("expected array")
		}
	case "object":
		if reflect.TypeOf(value).Kind() != reflect.Map {
			return errors.New("expected object")
		}
	}
	return nil
}

func getNestedValue(data map[string]interface{}, path string) (interface{}, bool) {
	parts := strings.Split(path, ".")
	var current interface{} = data

	for _, part := range parts {
		m, ok := current.(map[string]interface{})
		if !ok {
			return nil, false
		}
		current, ok = m[part]
		if !ok {
			return nil, false
		}
	}
	return current, true
}

func setNestedValue(data map[string]interface{}, path string, value interface{}) {
	parts := strings.Split(path, ".")
	current := data

	for i, part := range parts {
		if i == len(parts)-1 {
			current[part] = value
			return
		}

		if _, ok := current[part]; !ok {
			current[part] = make(map[string]interface{})
		}

		next, ok := current[part].(map[string]interface{})
		if !ok {
			return
		}
		current = next
	}
}

func (p *Processor) BatchProcess(ctx context.Context, batch []map[string]interface{}, schemaName string) []*ProcessingResult {
	results := make([]*ProcessingResult, len(batch))

	var wg sync.WaitGroup
	sem := make(chan struct{}, 10)

	for i, item := range batch {
		wg.Add(1)
		sem <- struct{}{}

		go func(index int, data map[string]interface{}) {
			defer wg.Done()
			defer func() { <-sem }()
			results[index] = p.Process(ctx, data, schemaName)
		}(i, item)
	}

	wg.Wait()
	return results
}

func (p *Processor) emitEvent(eventType, recordID, schema string, meta map[string]interface{}) {
	event := ProcessingEvent{
		Type:      eventType,
		RecordID:  recordID,
		Schema:    schema,
		MetaData:  meta,
		Timestamp: time.Now(),
	}

	select {
	case p.eventBus <- event:
	default:
	}
}

func (p *Processor) Events() <-chan ProcessingEvent {
	return p.eventBus
}

func (p *Processor) Close() {
	close(p.eventBus)
}

func (p *ProcessingResult) ToJSON() ([]byte, error) {
	return json.Marshal(p)
}
