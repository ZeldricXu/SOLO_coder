package service

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"projectservice/internal/infrastructure/logger"
	"projectservice/internal/infrastructure/monitor"
	"projectservice/internal/model"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

type APIContractService struct {
	db      *gorm.DB
	logger  *logger.Logger
	metrics *monitor.Metrics
}

func NewAPIContractService(db *gorm.DB, log *logger.Logger, metrics *monitor.Metrics) *APIContractService {
	return &APIContractService{
		db:      db,
		logger:  log,
		metrics: metrics,
	}
}

func (s *APIContractService) RegisterContract(ctx context.Context, req *model.RegisterContractRequest) (*model.APIContract, error) {
	start := time.Now()
	defer func() {
		s.metrics.ObserveTaskDuration("apicontract", "register", "success", time.Since(start))
	}()

	if err := s.validateSchema(req.ContractType, req.Schema); err != nil {
		s.metrics.ObserveError("apicontract", "validation_error")
		return nil, fmt.Errorf("invalid schema: %w", err)
	}

	contract := &model.APIContract{
		ID:              uuid.New().String(),
		ServiceID:       req.ServiceID,
		ContractType:    req.ContractType,
		Schema:          req.Schema,
		Version:         req.Version,
		BasePath:        req.BasePath,
		Servers:         req.Servers,
		SecuritySchemes: req.SecuritySchemes,
		CreatedBy:       "system",
		Status:          "active",
		CreatedAt:       time.Now(),
		UpdatedAt:       time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(contract).Error; err != nil {
		s.metrics.ObserveError("apicontract", "db_error")
		return nil, fmt.Errorf("failed to register contract: %w", err)
	}

	return contract, nil
}

func (s *APIContractService) validateSchema(contractType string, schema map[string]interface{}) error {
	if schema == nil {
		return fmt.Errorf("schema cannot be nil")
	}

	switch contractType {
	case "openapi":
		if _, ok := schema["openapi"]; !ok {
			return fmt.Errorf("openapi version is required")
		}
		if _, ok := schema["info"]; !ok {
			return fmt.Errorf("info is required")
		}
	case "graphql":
		if _, ok := schema["types"]; !ok {
			return fmt.Errorf("types are required for GraphQL schema")
		}
	default:
		return fmt.Errorf("unsupported contract type: %s", contractType)
	}

	return nil
}

func (s *APIContractService) GetContract(ctx context.Context, contractID string) (*model.APIContract, error) {
	var contract model.APIContract
	if err := s.db.WithContext(ctx).Where("id = ?", contractID).First(&contract).Error; err != nil {
		return nil, fmt.Errorf("contract not found: %w", err)
	}
	return &contract, nil
}

func (s *APIContractService) ListContracts(ctx context.Context, serviceID, contractType string, page, pageSize int) ([]model.APIContract, int64, error) {
	var contracts []model.APIContract
	var total int64

	query := s.db.WithContext(ctx).Model(&model.APIContract{})

	if serviceID != "" {
		query = query.Where("service_id = ?", serviceID)
	}
	if contractType != "" {
		query = query.Where("contract_type = ?", contractType)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&contracts).Error; err != nil {
		return nil, 0, err
	}

	return contracts, total, nil
}

func (s *APIContractService) ValidateRequest(ctx context.Context, req *model.ValidateContractRequest) (*model.ValidationResult, error) {
	start := time.Now()
	defer func() {
		s.metrics.ObserveTaskDuration("apicontract", "validate", "success", time.Since(start))
	}()

	contract, err := s.GetContract(ctx, req.ContractID)
	if err != nil {
		return nil, err
	}

	result := &model.ValidationResult{
		Valid: true,
	}

	s.validateAgainstSchema(contract, req, result)

	return result, nil
}

func (s *APIContractService) validateAgainstSchema(contract *model.APIContract, req *model.ValidateContractRequest, result *model.ValidationResult) {
	schemaJSON, _ := json.Marshal(contract.Schema)
	var schema map[string]interface{}
	json.Unmarshal(schemaJSON, &schema)

	switch contract.ContractType {
	case "openapi":
		paths, ok := schema["paths"].(map[string]interface{})
		if !ok {
			result.Valid = false
			result.Errors = append(result.Errors, model.ValidationError{
				Field:   "paths",
				Message: "No paths defined in schema",
				Type:    "schema_error",
			})
			return
		}

		pathObj, exists := paths[req.Path]
		if !exists {
			result.Valid = false
			result.Errors = append(result.Errors, model.ValidationError{
				Field:   "path",
				Message: fmt.Sprintf("Path %s not found in contract", req.Path),
				Type:    "not_found",
			})
			return
		}

		methods, ok := pathObj.(map[string]interface{})
		if !ok {
			result.Valid = false
			result.Errors = append(result.Errors, model.ValidationError{
				Field:   "path",
				Message: "Invalid path definition",
				Type:    "schema_error",
			})
			return
		}

		lowerMethod := toLower(req.Method)
		if _, exists := methods[lowerMethod]; !exists {
			result.Valid = false
			result.Errors = append(result.Errors, model.ValidationError{
				Field:   "method",
				Message: fmt.Sprintf("Method %s not allowed for path %s", req.Method, req.Path),
				Type:    "method_not_allowed",
			})
		}
	case "graphql":
		query, ok := req.RequestBody.(string)
		if !ok || query == "" {
			result.Valid = false
			result.Errors = append(result.Errors, model.ValidationError{
				Field:   "query",
				Message: "GraphQL query is required",
				Type:    "validation_error",
			})
		}
	}
}

func toLower(s string) string {
	b := make([]byte, len(s))
	for i := range s {
		c := s[i]
		if c >= 'A' && c <= 'Z' {
			c += 32
		}
		b[i] = c
	}
	return string(b)
}

func (s *APIContractService) CreateMockServer(ctx context.Context, req *model.CreateMockServerRequest) (*model.MockServer, error) {
	start := time.Now()
	defer func() {
		s.metrics.ObserveTaskDuration("apicontract", "create_mock", "success", time.Since(start))
	}()

	contract, err := s.GetContract(ctx, req.ContractID)
	if err != nil {
		return nil, err
	}

	mock := &model.MockServer{
		ID:         uuid.New().String(),
		ContractID: req.ContractID,
		Name:       req.Name,
		BaseURL:    fmt.Sprintf("http://localhost:%d/mock", req.Port),
		Port:       req.Port,
		Status:     "starting",
		DelayMs:    req.DelayMs,
		ErrorRate:  req.ErrorRate,
		CreatedBy:  "system",
		CreatedAt:  time.Now(),
		UpdatedAt:  time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(mock).Error; err != nil {
		return nil, fmt.Errorf("failed to create mock server: %w", err)
	}

	mock.Status = "running"
	now := time.Now()
	mock.StartedAt = &now
	if err := s.db.WithContext(ctx).Save(mock).Error; err != nil {
		s.logger.Errorw("Failed to update mock server status", "error", err)
	}

	s.logger.Infow("Mock server created", "contract", contract.ID, "port", req.Port)

	return mock, nil
}

func (s *APIContractService) GetMockServer(ctx context.Context, mockID string) (*model.MockServer, error) {
	var mock model.MockServer
	if err := s.db.WithContext(ctx).Where("id = ?", mockID).First(&mock).Error; err != nil {
		return nil, fmt.Errorf("mock server not found: %w", err)
	}
	return &mock, nil
}

func (s *APIContractService) GetMockServerStatus(ctx context.Context, mockID string) (*model.MockServerStatus, error) {
	mock, err := s.GetMockServer(ctx, mockID)
	if err != nil {
		return nil, err
	}

	return &model.MockServerStatus{
		ID:        mock.ID,
		Name:      mock.Name,
		BaseURL:   mock.BaseURL,
		Status:    mock.Status,
		Port:      mock.Port,
		StartedAt: mock.StartedAt,
	}, nil
}

func (s *APIContractService) ListMockServers(ctx context.Context, contractID, status string, page, pageSize int) ([]model.MockServer, int64, error) {
	var mocks []model.MockServer
	var total int64

	query := s.db.WithContext(ctx).Model(&model.MockServer{})

	if contractID != "" {
		query = query.Where("contract_id = ?", contractID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&mocks).Error; err != nil {
		return nil, 0, err
	}

	return mocks, total, nil
}

func (s *APIContractService) StopMockServer(ctx context.Context, mockID string) error {
	mock, err := s.GetMockServer(ctx, mockID)
	if err != nil {
		return err
	}

	mock.Status = "stopped"
	now := time.Now()
	mock.StoppedAt = &now
	mock.UpdatedAt = now

	return s.db.WithContext(ctx).Save(mock).Error
}

func (s *APIContractService) DeleteContract(ctx context.Context, contractID string) error {
	result := s.db.WithContext(ctx).
		Model(&model.APIContract{}).
		Where("id = ?", contractID).
		Update("status", "inactive")

	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("contract not found")
	}
	return nil
}
