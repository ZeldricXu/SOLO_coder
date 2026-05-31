package core

import (
	"context"
	"fmt"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"time"

	"session172/internal/gateway"
	"session172/internal/logger"
	"session172/pkg/models"
	"session172/pkg/utils"
)

type Processor struct {
	mu          sync.RWMutex
	transforms  map[string]TransformFunc
	validators  map[string]ValidateFunc
	normalizers map[string]NormalizeFunc
}

type TransformFunc func(ctx context.Context, data interface{}) (interface{}, error)
type ValidateFunc func(ctx context.Context, data interface{}) error
type NormalizeFunc func(ctx context.Context, data interface{}) (interface{}, error)

type ProcessingContext struct {
	TraceID    string
	EntityID   string
	Phase      string
	Progress   float64
	StartedAt  time.Time
	Config     map[string]interface{}
	Attributes map[string]interface{}
}

type Handler struct {
	processor *Processor
}

var (
	processorInstance *Processor
	processorOnce     sync.Once
)

func NewProcessor() *Processor {
	processorOnce.Do(func() {
		processorInstance = &Processor{
			transforms:  make(map[string]TransformFunc),
			validators:  make(map[string]ValidateFunc),
			normalizers: make(map[string]NormalizeFunc),
		}
		processorInstance.registerDefaults()
	})
	return processorInstance
}

func GetProcessor() *Processor {
	if processorInstance == nil {
		return NewProcessor()
	}
	return processorInstance
}

func (p *Processor) registerDefaults() {
	p.RegisterTransform("to_upper", transformToUpper)
	p.RegisterTransform("to_lower", transformToLower)
	p.RegisterTransform("trim", transformTrim)
	p.RegisterTransform("to_int", transformToInt)
	p.RegisterTransform("to_float", transformToFloat)
	p.RegisterTransform("to_string", transformToString)

	p.RegisterValidator("required", validateRequired)
	p.RegisterValidator("min_length", validateMinLength)
	p.RegisterValidator("max_length", validateMaxLength)
	p.RegisterValidator("email", validateEmail)
	p.RegisterValidator("numeric", validateNumeric)

	p.RegisterNormalize("trim_space", normalizeTrimSpace)
	p.RegisterNormalize("lowercase", normalizeLowercase)
	p.RegisterNormalize("uppercase", normalizeUppercase)
	p.RegisterNormalize("strip_tags", normalizeStripTags)
}

func (p *Processor) RegisterTransform(name string, fn TransformFunc) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.transforms[name] = fn
}

func (p *Processor) RegisterValidator(name string, fn ValidateFunc) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.validators[name] = fn
}

func (p *Processor) RegisterNormalize(name string, fn NormalizeFunc) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.normalizers[name] = fn
}

func (p *Processor) Transform(ctx context.Context, data interface{}, transforms ...string) (interface{}, error) {
	p.mu.RLock()
	defer p.mu.RUnlock()

	var err error
	for _, name := range transforms {
		fn, ok := p.transforms[name]
		if !ok {
			return nil, fmt.Errorf("transform not found: %s", name)
		}
		data, err = fn(ctx, data)
		if err != nil {
			return nil, fmt.Errorf("transform %s failed: %w", name, err)
		}
	}
	return data, nil
}

func (p *Processor) Validate(ctx context.Context, data interface{}, validators ...string) error {
	p.mu.RLock()
	defer p.mu.RUnlock()

	for _, name := range validators {
		fn, ok := p.validators[name]
		if !ok {
			return fmt.Errorf("validator not found: %s", name)
		}
		if err := fn(ctx, data); err != nil {
			return fmt.Errorf("validation %s failed: %w", name, err)
		}
	}
	return nil
}

func (p *Processor) Normalize(ctx context.Context, data interface{}, normalizers ...string) (interface{}, error) {
	p.mu.RLock()
	defer p.mu.RUnlock()

	var err error
	for _, name := range normalizers {
		fn, ok := p.normalizers[name]
		if !ok {
			return nil, fmt.Errorf("normalizer not found: %s", name)
		}
		data, err = fn(ctx, data)
		if err != nil {
			return nil, fmt.Errorf("normalizer %s failed: %w", name, err)
		}
	}
	return data, nil
}

func (p *Processor) Process(ctx context.Context, payload interface{}, rules map[string]interface{}) (interface{}, error) {
	traceID := ""
	if tc := gateway.FromContext(ctx); tc != nil {
		traceID = tc.TraceID
	}

	logger.WithTraceID(traceID).Info("Processing started")

	if transforms, ok := rules["transforms"].([]interface{}); ok {
		transformNames := make([]string, len(transforms))
		for i, t := range transforms {
			transformNames[i] = fmt.Sprint(t)
		}
		var err error
		payload, err = p.Transform(ctx, payload, transformNames...)
		if err != nil {
			return nil, err
		}
	}

	if validators, ok := rules["validators"].([]interface{}); ok {
		validatorNames := make([]string, len(validators))
		for i, v := range validators {
			validatorNames[i] = fmt.Sprint(v)
		}
		if err := p.Validate(ctx, payload, validatorNames...); err != nil {
			return nil, err
		}
	}

	if normalizers, ok := rules["normalizers"].([]interface{}); ok {
		normalizerNames := make([]string, len(normalizers))
		for i, n := range normalizers {
			normalizerNames[i] = fmt.Sprint(n)
		}
		var err error
		payload, err = p.Normalize(ctx, payload, normalizerNames...)
		if err != nil {
			return nil, err
		}
	}

	logger.WithTraceID(traceID).Info("Processing completed")
	return payload, nil
}

func transformToUpper(ctx context.Context, data interface{}) (interface{}, error) {
	if s, ok := data.(string); ok {
		return strings.ToUpper(s), nil
	}
	return data, nil
}

func transformToLower(ctx context.Context, data interface{}) (interface{}, error) {
	if s, ok := data.(string); ok {
		return strings.ToLower(s), nil
	}
	return data, nil
}

func transformTrim(ctx context.Context, data interface{}) (interface{}, error) {
	if s, ok := data.(string); ok {
		return strings.TrimSpace(s), nil
	}
	return data, nil
}

func transformToInt(ctx context.Context, data interface{}) (interface{}, error) {
	switch v := data.(type) {
	case string:
		return strconv.Atoi(v)
	case float64:
		return int(v), nil
	case int:
		return v, nil
	}
	return nil, fmt.Errorf("cannot convert %v to int", reflect.TypeOf(data))
}

func transformToFloat(ctx context.Context, data interface{}) (interface{}, error) {
	switch v := data.(type) {
	case string:
		return strconv.ParseFloat(v, 64)
	case int:
		return float64(v), nil
	case float64:
		return v, nil
	}
	return nil, fmt.Errorf("cannot convert %v to float", reflect.TypeOf(data))
}

func transformToString(ctx context.Context, data interface{}) (interface{}, error) {
	return fmt.Sprintf("%v", data), nil
}

func validateRequired(ctx context.Context, data interface{}) error {
	if data == nil {
		return fmt.Errorf("value is required")
	}
	if s, ok := data.(string); ok && s == "" {
		return fmt.Errorf("value is required")
	}
	return nil
}

func validateMinLength(ctx context.Context, data interface{}) error {
	s, ok := data.(string)
	if !ok {
		return nil
	}
	minLen := 1
	if params := ctx.Value("params"); params != nil {
		if p, ok := params.(map[string]interface{}); ok {
			if ml, ok := p["min"].(int); ok {
				minLen = ml
			}
		}
	}
	if len(s) < minLen {
		return fmt.Errorf("minimum length is %d", minLen)
	}
	return nil
}

func validateMaxLength(ctx context.Context, data interface{}) error {
	s, ok := data.(string)
	if !ok {
		return nil
	}
	maxLen := 100
	if params := ctx.Value("params"); params != nil {
		if p, ok := params.(map[string]interface{}); ok {
			if ml, ok := p["max"].(int); ok {
				maxLen = ml
			}
		}
	}
	if len(s) > maxLen {
		return fmt.Errorf("maximum length is %d", maxLen)
	}
	return nil
}

func validateEmail(ctx context.Context, data interface{}) error {
	s, ok := data.(string)
	if !ok {
		return nil
	}
	if !strings.Contains(s, "@") || !strings.Contains(s, ".") {
		return fmt.Errorf("invalid email format")
	}
	return nil
}

func validateNumeric(ctx context.Context, data interface{}) error {
	s, ok := data.(string)
	if !ok {
		return nil
	}
	_, err := strconv.ParseFloat(s, 64)
	return err
}

func normalizeTrimSpace(ctx context.Context, data interface{}) (interface{}, error) {
	if s, ok := data.(string); ok {
		return strings.TrimSpace(s), nil
	}
	return data, nil
}

func normalizeLowercase(ctx context.Context, data interface{}) (interface{}, error) {
	if s, ok := data.(string); ok {
		return strings.ToLower(s), nil
	}
	return data, nil
}

func normalizeUppercase(ctx context.Context, data interface{}) (interface{}, error) {
	if s, ok := data.(string); ok {
		return strings.ToUpper(s), nil
	}
	return data, nil
}

func normalizeStripTags(ctx context.Context, data interface{}) (interface{}, error) {
	if s, ok := data.(string); ok {
		result := s
		for {
			start := strings.Index(result, "<")
			if start == -1 {
				break
			}
			end := strings.Index(result[start:], ">")
			if end == -1 {
				break
			}
			result = result[:start] + result[start+end+1:]
		}
		return result, nil
	}
	return data, nil
}

func NewHandler() *Handler {
	return &Handler{
		processor: GetProcessor(),
	}
}

func (h *Handler) Execute(ctx context.Context, request map[string]interface{}) (*models.ResourceResponse, error) {
	traceID := ""
	if tc := gateway.FromContext(ctx); tc != nil {
		traceID = tc.TraceID
	}

	log := logger.WithTraceID(traceID)
	log.Info("Executing handler")

	runInstance := &models.RunInstance{
		RunID:     utils.GenerateID("run"),
		EntityID:  utils.GenerateID("ent"),
		Phase:     "initializing",
		Progress:  0,
		StartedAt: time.Now(),
	}

	defer func() {
		if r := recover(); r != nil {
			runInstance.Phase = "failed"
			errMsg := fmt.Sprintf("panic: %v", r)
			runInstance.ErrorDetail = &errMsg
			log.Error("Handler panic recovered", zap.Any("error", r))
		}
	}()

	params, ok := request["params"].(map[string]interface{})
	if ok {
		if err := h.validateParams(params); err != nil {
			runInstance.Phase = "failed"
			errMsg := err.Error()
			runInstance.ErrorDetail = &errMsg
			return &models.ResourceResponse{
				Code:    422,
				Message: "Validation error: " + err.Error(),
			}, err
		}
	}
	runInstance.Progress = 0.25
	runInstance.Phase = "validated"

	namespace := "default"
	if ns, ok := request["namespace"].(string); ok {
		namespace = ns
	}

	config := h.loadConfig(namespace)
	runInstance.Progress = 0.5
	runInstance.Phase = "config_loaded"

	payload := request["payload"]
	rules, _ := config["rules"].(map[string]interface{})

	result, err := h.processor.Process(ctx, payload, rules)
	if err != nil {
		runInstance.Phase = "failed"
		errMsg := err.Error()
		runInstance.ErrorDetail = &errMsg

		if strings.Contains(err.Error(), "timeout") {
			return &models.ResourceResponse{
				Code:    504,
				Message: "上游服务响应超时",
			}, err
		}
		return &models.ResourceResponse{
			Code:    500,
			Message: "内部处理错误: " + err.Error(),
		}, err
	}

	runInstance.Progress = 0.75
	runInstance.Phase = "processed"

	h.persistResult(result)
	h.emitEvent("task.completed", result)

	runInstance.Progress = 1.0
	runInstance.Phase = "completed"
	completedAt := time.Now()
	runInstance.CompletedAt = &completedAt

	log.Info("Handler execution completed")

	return &models.ResourceResponse{
		Code: 200,
		Data: map[string]interface{}{
			"result":     result,
			"run_id":     runInstance.RunID,
			"entity_id":  runInstance.EntityID,
			"phase":      runInstance.Phase,
			"progress":   runInstance.Progress,
			"started_at": runInstance.StartedAt,
		},
	}, nil
}

func (h *Handler) validateParams(params map[string]interface{}) error {
	processor := GetProcessor()
	ctx := context.Background()

	for key, value := range params {
		if err := processor.Validate(ctx, value, "required"); err != nil {
			return fmt.Errorf("param %s: %w", key, err)
		}
	}
	return nil
}

func (h *Handler) loadConfig(namespace string) map[string]interface{} {
	return map[string]interface{}{
		"namespace": namespace,
		"poolSize":  10,
		"rules": map[string]interface{}{
			"transforms":  []string{"trim", "to_lower"},
			"validators":  []string{"required"},
			"normalizers": []string{"trim_space"},
		},
	}
}

func (h *Handler) persistResult(result interface{}) {
	logger.Debug("Result persisted", zap.Any("result", result))
}

func (h *Handler) emitEvent(eventType string, data interface{}) {
	logger.Debug("Event emitted", zap.String("type", eventType), zap.Any("data", data))
}
