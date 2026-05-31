package core_processing

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"loglevelplatform/internal/common/database"
	"loglevelplatform/internal/common/errors"
	"loglevelplatform/internal/common/logger"
	"loglevelplatform/internal/common/models"
	"loglevelplatform/pkg/utils"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

type Service struct {
	db *gorm.DB
}

func NewService() *Service {
	return &Service{
		db: database.GetDB(),
	}
}

type ProcessRequest struct {
	TraceID   string                 `json:"trace_id"`
	Namespace string                 `json:"namespace" binding:"required"`
	Payload   map[string]interface{} `json:"payload" binding:"required"`
	Rules     map[string]interface{} `json:"rules"`
}

type ProcessResponse struct {
	TraceID   string                 `json:"trace_id"`
	Status    string                 `json:"status"`
	Result    map[string]interface{} `json:"result,omitempty"`
	Error     string                 `json:"error,omitempty"`
	StartedAt time.Time              `json:"started_at"`
	EndedAt   time.Time              `json:"ended_at"`
}

type ProcessingContext struct {
	TraceID   string
	Namespace string
	Config    *models.ConfigDefinition
	StartTime time.Time
	Metrics   map[string]float64
}

func (s *Service) validateParams(params map[string]interface{}) error {
	if params == nil {
		return errors.NewValidationError("params cannot be nil")
	}
	if _, exists := params["payload"]; !exists {
		return errors.NewValidationError("payload is required")
	}
	return nil
}

func (s *Service) loadConfig(namespace string) (*models.ConfigDefinition, error) {
	var config models.ConfigDefinition
	err := s.db.Where("namespace = ? AND enabled = ?", namespace, true).
		Order("version DESC").
		First(&config).Error
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return &models.ConfigDefinition{
				ConfigID:   utils.NewID("cfg"),
				Namespace:  namespace,
				Version:    1,
				Parameters: make(map[string]interface{}),
				Enabled:    true,
				AppliedAt:  time.Now(),
			}, nil
		}
		return nil, err
	}
	return &config, nil
}

func (s *Service) processCore(ctx context.Context, payload map[string]interface{}, rules map[string]interface{}) (map[string]interface{}, error) {
	log := logger.FromContext(ctx)

	result := make(map[string]interface{})

	for key, value := range payload {
		transformed, err := s.transformValue(key, value, rules)
		if err != nil {
			log.Warn("transformation failed", zap.String("key", key), zap.Error(err))
			continue
		}
		result[key] = transformed
	}

	result["_processed_at"] = time.Now().UTC().Format(time.RFC3339)
	result["_normalized"] = true

	log.Debug("core processing completed", zap.Int("input_fields", len(payload)), zap.Int("output_fields", len(result)))
	return result, nil
}

func (s *Service) transformValue(key string, value interface{}, rules map[string]interface{}) (interface{}, error) {
	if rules == nil {
		return value, nil
	}

	if rule, exists := rules[key]; exists {
		switch r := rule.(type) {
		case map[string]interface{}:
			if transformType, ok := r["type"].(string); ok {
				return s.applyTransform(value, transformType, r)
			}
		}
	}
	return value, nil
}

func (s *Service) applyTransform(value interface{}, transformType string, config map[string]interface{}) (interface{}, error) {
	switch transformType {
	case "string":
		return fmt.Sprintf("%v", value), nil
	case "number":
		switch v := value.(type) {
		case float64:
			return v, nil
		case int:
			return float64(v), nil
		case string:
			var result float64
			if _, err := fmt.Sscanf(v, "%f", &result); err != nil {
				return nil, err
			}
			return result, nil
		}
	case "boolean":
		switch v := value.(type) {
		case bool:
			return v, nil
		case string:
			return v == "true" || v == "1" || v == "yes", nil
		}
	case "uppercase":
		if str, ok := value.(string); ok {
			return toUpper(str), nil
		}
	case "lowercase":
		if str, ok := value.(string); ok {
			return toLower(str), nil
		}
	case "trim":
		if str, ok := value.(string); ok {
			return trimSpace(str), nil
		}
	case "json":
		if str, ok := value.(string); ok {
			var result interface{}
			if err := json.Unmarshal([]byte(str), &result); err != nil {
				return nil, err
			}
			return result, nil
		}
	}
	return value, nil
}

func toUpper(s string) string {
	result := make([]byte, len(s))
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c >= 'a' && c <= 'z' {
			result[i] = c - 32
		} else {
			result[i] = c
		}
	}
	return string(result)
}

func toLower(s string) string {
	result := make([]byte, len(s))
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c >= 'A' && c <= 'Z' {
			result[i] = c + 32
		} else {
			result[i] = c
		}
	}
	return string(result)
}

func trimSpace(s string) string {
	start := 0
	end := len(s)
	for start < end && s[start] == ' ' {
		start++
	}
	for end > start && s[end-1] == ' ' {
		end--
	}
	return s[start:end]
}

func (s *Service) persistResult(result map[string]interface{}) error {
	entity := &models.CoreEntity{
		ID:         utils.NewID("ent"),
		Type:       "processed_data",
		Status:     "completed",
		Attributes: result,
		CreatedAt:  time.Now(),
		UpdatedAt:  time.Now(),
	}
	return s.db.Create(entity).Error
}

func (s *Service) ExecuteHandler(ctx context.Context, req *ProcessRequest) (*ProcessResponse, error) {
	log := logger.FromContext(ctx)

	ctx = logger.WithContext(ctx, log.With(zap.String("trace_id", req.TraceID)))
	log = logger.FromContext(ctx)

	procCtx := &ProcessingContext{
		TraceID:   req.TraceID,
		Namespace: req.Namespace,
		StartTime: time.Now(),
		Metrics:   make(map[string]float64),
	}

	response := &ProcessResponse{
		TraceID:   req.TraceID,
		Status:    "processing",
		StartedAt: procCtx.StartTime,
	}

	if err := s.validateParams(map[string]interface{}{"payload": req.Payload}); err != nil {
		response.Status = "failed"
		response.Error = err.Error()
		response.EndedAt = time.Now()
		return response, err
	}

	config, err := s.loadConfig(req.Namespace)
	if err != nil {
		log.Error("failed to load config", zap.Error(err))
		response.Status = "failed"
		response.Error = "configuration load error"
		response.EndedAt = time.Now()
		return response, err
	}
	procCtx.Config = config

	runInstance := &models.RunInstance{
		RunID:     utils.NewID("run"),
		EntityID:  utils.NewID("ent"),
		Phase:     "processing",
		Progress:  0.0,
		StartedAt: time.Now(),
	}

	if err := s.db.Create(runInstance).Error; err != nil {
		log.Warn("failed to create run instance", zap.Error(err))
	}

	poolSize := 10
	if poolSizeParam, exists := config.Parameters["poolSize"]; exists {
		if ps, ok := poolSizeParam.(float64); ok {
			poolSize = int(ps)
		}
	}
	_ = poolSize

	result, err := s.processCore(ctx, req.Payload, req.Rules)
	if err != nil {
		runInstance.Phase = "failed"
		runInstance.Progress = 1.0
		now := time.Now()
		runInstance.CompletedAt = &now
		errMsg := err.Error()
		runInstance.ErrorDetail = &errMsg
		s.db.Save(runInstance)

		response.Status = "failed"
		response.Error = err.Error()
		response.EndedAt = time.Now()
		return response, err
	}

	runInstance.Phase = "finalizing"
	runInstance.Progress = 0.75
	s.db.Save(runInstance)

	if err := s.persistResult(result); err != nil {
		log.Error("failed to persist result", zap.Error(err))
	}

	runInstance.Phase = "completed"
	runInstance.Progress = 1.0
	now := time.Now()
	runInstance.CompletedAt = &now
	s.db.Save(runInstance)

	response.Status = "completed"
	response.Result = result
	response.EndedAt = time.Now()

	log.Info("handler execution completed",
		zap.String("phase", "finalizing"),
		zap.Float64("progress", 0.75),
		zap.Duration("duration", time.Since(procCtx.StartTime)),
	)

	return response, nil
}

func (s *Service) CreateEntity(ctx context.Context, entity *models.CoreEntity) (*models.CoreEntity, error) {
	log := logger.FromContext(ctx)

	entity.ID = utils.NewID("ent")
	entity.CreatedAt = time.Now()
	entity.UpdatedAt = time.Now()

	if err := s.db.Create(entity).Error; err != nil {
		log.Error("failed to create entity", zap.Error(err))
		return nil, err
	}

	return entity, nil
}

func (s *Service) GetEntity(ctx context.Context, id string) (*models.CoreEntity, error) {
	var entity models.CoreEntity
	if err := s.db.Where("id = ?", id).First(&entity).Error; err != nil {
		return nil, err
	}
	return &entity, nil
}

func (s *Service) UpdateEntity(ctx context.Context, id string, updates map[string]interface{}) (*models.CoreEntity, error) {
	var entity models.CoreEntity
	if err := s.db.Where("id = ?", id).First(&entity).Error; err != nil {
		return nil, err
	}

	updates["updated_at"] = time.Now()
	if err := s.db.Model(&entity).Updates(updates).Error; err != nil {
		return nil, err
	}

	return &entity, nil
}

func (s *Service) ListEntities(ctx context.Context, entityType, status string, limit, offset int) ([]models.CoreEntity, int64, error) {
	var entities []models.CoreEntity
	var total int64

	query := s.db.Model(&models.CoreEntity{})
	if entityType != "" {
		query = query.Where("type = ?", entityType)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("created_at DESC").Limit(limit).Offset(offset).Find(&entities).Error; err != nil {
		return nil, 0, err
	}

	return entities, total, nil
}
