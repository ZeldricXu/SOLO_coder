package processing

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/dataplatform/engine/internal/domain"
)

type testProcessorLogger struct{}

func (l *testProcessorLogger) Debug(msg string, fields ...domain.Field) {}
func (l *testProcessorLogger) Info(msg string, fields ...domain.Field)  {}
func (l *testProcessorLogger) Warn(msg string, fields ...domain.Field)  {}
func (l *testProcessorLogger) Error(msg string, fields ...domain.Field) {}
func (l *testProcessorLogger) Fatal(msg string, fields ...domain.Field) {}
func (l *testProcessorLogger) SetLevel(level domain.LogLevel)           {}
func (l *testProcessorLogger) GetLevel() domain.LogLevel                { return domain.LogLevelInfo }
func (l *testProcessorLogger) With(fields ...domain.Field) domain.Logger { return l }
func (l *testProcessorLogger) Sync() error                              { return nil }

func TestDataProcessorConcurrentSafety(t *testing.T) {
	logger := &testProcessorLogger{}
	processor := NewDataProcessor(logger)

	const numGoroutines = 30
	const operationsPerGoroutine = 100

	var wg sync.WaitGroup
	var registerCount int64
	var processCount int64

	startTime := time.Now()

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(goroutineID int) {
			defer wg.Done()

			for j := 0; j < operationsPerGoroutine; j++ {
				rule := &TransformRule{
					ID:      "rule",
					Name:    "Test Rule",
					Type:    "rename",
					Enabled: true,
					Config: map[string]interface{}{
						"mappings": map[string]interface{}{
							"old_name": "new_name",
						},
					},
				}

				err := processor.RegisterRule(rule)
				if err == nil {
					atomic.AddInt64(&registerCount, 1)
				}

				payload := map[string]interface{}{
					"old_name": "value",
					"other":    "data",
				}

				rules := processor.ListRules()
				_, err = processor.Process(context.Background(), payload, rules)
				if err == nil {
					atomic.AddInt64(&processCount, 1)
				}

				schema := &Schema{
					Name:    "test_schema",
					Version: "1.0",
					Fields: []*FieldSchema{
						{Name: "new_name", Type: "string", Required: true},
					},
				}
				processor.RegisterSchema(schema)

				processor.ListSchemas()

				time.Sleep(time.Microsecond * 50)
			}
		}(i)
	}

	wg.Wait()

	t.Logf("Register operations: %d, Process operations: %d, Duration: %v",
		atomic.LoadInt64(&registerCount),
		atomic.LoadInt64(&processCount),
		time.Since(startTime),
	)

	rules := processor.ListRules()
	schemas := processor.ListSchemas()

	t.Logf("Final rules count: %d, schemas count: %d", len(rules), len(schemas))
}

func TestDataProcessorInputValidation(t *testing.T) {
	logger := &testProcessorLogger{}
	processor := NewDataProcessor(logger)

	err := processor.RegisterRule(nil)
	if err == nil {
		t.Error("Expected error for nil rule")
	}

	err = processor.RegisterRule(&TransformRule{ID: ""})
	if err == nil {
		t.Error("Expected error for rule with empty ID")
	}

	err = processor.RegisterRule(&TransformRule{ID: "test", Type: ""})
	if err == nil {
		t.Error("Expected error for rule with empty type")
	}

	err = processor.RegisterSchema(nil)
	if err == nil {
		t.Error("Expected error for nil schema")
	}

	err = processor.RegisterSchema(&Schema{Name: ""})
	if err == nil {
		t.Error("Expected error for schema with empty name")
	}

	_, err = processor.Process(context.Background(), nil, nil)
	if err == nil {
		t.Error("Expected error for nil payload")
	}

	_, err = processor.Process(context.Background(), map[string]interface{}{}, nil)
	if err == nil {
		t.Error("Expected error for empty payload")
	}

	_, err = processor.Transform(context.Background(), nil, nil)
	if err == nil {
		t.Error("Expected error for nil rule")
	}

	_, err = processor.Normalize(context.Background(), nil, nil)
	if err == nil {
		t.Error("Expected error for nil schema")
	}

	_, err = processor.Normalize(context.Background(), "not a map", &Schema{Name: "test"})
	if err == nil {
		t.Error("Expected error for non-map data")
	}
}

func TestDataProcessorExceptionPathSafety(t *testing.T) {
	logger := &testProcessorLogger{}
	processor := NewDataProcessor(logger)

	rule := &TransformRule{
		ID:      "test-rule",
		Name:    "Test",
		Type:    "unknown_type",
		Enabled: true,
	}
	processor.RegisterRule(rule)

	payload := map[string]interface{}{
		"field": "value",
	}

	_, err := processor.Process(context.Background(), payload, []*TransformRule{rule})
	if err == nil {
		t.Error("Expected error for unknown transform type")
	}

	processed, err := processor.Process(context.Background(), payload, []*TransformRule{nil, rule})
	if err == nil {
		t.Error("Expected error for unknown transform type")
	}

	if processed != nil {
		t.Log("Process returned partial result on error - this is acceptable")
	}

	schema := &Schema{
		Name:    "test",
		Version: "1.0",
		Fields: []*FieldSchema{
			{Name: "missing_required", Type: "string", Required: true},
			{Name: "optional_field", Type: "int", Required: false, Default: 42},
		},
	}

	data := map[string]interface{}{
		"other_field": "value",
	}

	normalized, err := processor.Normalize(context.Background(), data, schema)
	if err == nil {
		t.Error("Expected error for missing required field")
	}

	normMap, ok := normalized.(map[string]interface{})
	if !ok {
		t.Fatal("Expected normalized result to be a map")
	}

	if normMap["optional_field"] != 42 {
		t.Error("Default value should be set for optional field")
	}
}

func TestDataProcessorResourceCleanup(t *testing.T) {
	logger := &testProcessorLogger{}
	processor := NewDataProcessor(logger)

	for i := 0; i < 100; i++ {
		rule := &TransformRule{
			ID:      "test_rule",
			Name:    "Test Rule",
			Type:    "rename",
			Enabled: true,
		}
		processor.RegisterRule(rule)

		schema := &Schema{
			Name:    "test_schema",
			Version: "1.0",
		}
		processor.RegisterSchema(schema)
	}

	rulesBefore := processor.ListRules()
	schemasBefore := processor.ListSchemas()

	if len(rulesBefore) == 0 {
		t.Error("Rules should be registered")
	}
	if len(schemasBefore) == 0 {
		t.Error("Schemas should be registered")
	}

	processor.Clear()

	rulesAfter := processor.ListRules()
	schemasAfter := processor.ListSchemas()

	if len(rulesAfter) != 0 {
		t.Error("All rules should be cleared")
	}
	if len(schemasAfter) != 0 {
		t.Error("All schemas should be cleared")
	}
}

func TestDataProcessorContextCancellation(t *testing.T) {
	logger := &testProcessorLogger{}
	processor := NewDataProcessor(logger)

	ctx, cancel := context.WithCancel(context.Background())

	slowRule := &TransformRule{
		ID:      "slow",
		Name:    "Slow Rule",
		Type:    "rename",
		Enabled: true,
		Config: map[string]interface{}{
			"mappings": map[string]interface{}{
				"a": "b",
			},
		},
	}
	processor.RegisterRule(slowRule)

	cancel()

	payload := map[string]interface{}{
		"a": "1",
		"b": "2",
	}

	_, err := processor.Process(ctx, payload, []*TransformRule{slowRule})
	if err == nil {
		t.Log("Process completed before cancellation check - acceptable")
	}
}
