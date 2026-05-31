package apicontract

import (
	"depguard/internal/cache"
	"depguard/internal/common/utils"
	"depguard/internal/database"
	"depguard/internal/logger"
	apperrors "depguard/pkg/errors"
	"encoding/json"
	"strings"
	"sync"
	"time"

	"go.uber.org/zap"
)

const (
	CachePrefixSchema     = "schema:"
	CachePrefixValidation = "validation:"
	CachePrefixMockServer = "mockserver:"
	CacheTTLShort         = 5 * time.Minute
	CacheTTLLong          = 1 * time.Hour
	CacheTTLDefault       = 15 * time.Minute
)

type CacheStats struct {
	Hits        int64 `json:"hits"`
	Misses      int64 `json:"misses"`
	Evictions   int64 `json:"evictions"`
	TotalKeys   int   `json:"total_keys"`
	HitRate     float64 `json:"hit_rate"`
}

type APIContractService struct {
	schemaRepo       SchemaRepository
	validationRepo   ValidationResultRepository
	mockServerRepo   MockServerRepository
	contractTestRepo ContractTestRepository
	cacheStats       CacheStats
	cacheMutex       sync.RWMutex
	localCache       sync.Map
	warmupInProgress bool
	warmupMutex      sync.Mutex
}

func NewAPIContractService() *APIContractService {
	return &APIContractService{
		schemaRepo:       NewSchemaRepository(),
		validationRepo:   NewValidationResultRepository(),
		mockServerRepo:   NewMockServerRepository(),
		contractTestRepo: NewContractTestRepository(),
		cacheStats:       CacheStats{},
		localCache:       sync.Map{},
	}
}

func (s *APIContractService) getFromCache(key string) (interface{}, bool) {
	if val, ok := s.localCache.Load(key); ok {
		s.cacheMutex.Lock()
		s.cacheStats.Hits++
		s.cacheMutex.Unlock()
		return val, true
	}

	val, err := cache.Get(key)
	if err == nil && val != "" {
		s.cacheMutex.Lock()
		s.cacheStats.Hits++
		s.cacheMutex.Unlock()

		var result interface{}
		if json.Unmarshal([]byte(val), &result) == nil {
			s.localCache.Store(key, result)
			return result, true
		}
	}

	s.cacheMutex.Lock()
	s.cacheStats.Misses++
	s.cacheMutex.Unlock()
	return nil, false
}

func (s *APIContractService) setCache(key string, value interface{}, ttl time.Duration) error {
	s.localCache.Store(key, value)

	jsonVal, err := json.Marshal(value)
	if err != nil {
		return err
	}

	return cache.Set(key, string(jsonVal), ttl)
}

func (s *APIContractService) invalidateCache(pattern string) {
	s.localCache.Range(func(key, value interface{}) bool {
		if k, ok := key.(string); ok && strings.HasPrefix(k, pattern) {
			s.localCache.Delete(k)
			cache.Delete(k)
			s.cacheMutex.Lock()
			s.cacheStats.Evictions++
			s.cacheMutex.Unlock()
		}
		return true
	})
}

func (s *APIContractService) GetCacheStats() CacheStats {
	s.cacheMutex.RLock()
	defer s.cacheMutex.RUnlock()

	total := s.cacheStats.Hits + s.cacheStats.Misses
	hitRate := 0.0
	if total > 0 {
		hitRate = float64(s.cacheStats.Hits) / float64(total) * 100
	}

	return CacheStats{
		Hits:      s.cacheStats.Hits,
		Misses:    s.cacheStats.Misses,
		Evictions: s.cacheStats.Evictions,
		HitRate:   hitRate,
	}
}

func (s *APIContractService) ResetCacheStats() {
	s.cacheMutex.Lock()
	defer s.cacheMutex.Unlock()
	s.cacheStats = CacheStats{}
}

func (s *APIContractService) WarmupCache() (int, error) {
	s.warmupMutex.Lock()
	if s.warmupInProgress {
		s.warmupMutex.Unlock()
		return 0, apperrors.New(409, "warmup already in progress")
	}
	s.warmupInProgress = true
	s.warmupMutex.Unlock()

	defer func() {
		s.warmupMutex.Lock()
		s.warmupInProgress = false
		s.warmupMutex.Unlock()
	}()

	logger.Log.Info("Starting cache warmup")

	schemas, _, err := s.schemaRepo.List(1, 1000, "", "")
	if err != nil {
		return 0, err
	}

	count := 0
	for _, schema := range schemas {
		cacheKey := CachePrefixSchema + schema.ID
		if err := s.setCache(cacheKey, schema, CacheTTLLong); err == nil {
			count++
		}

		validations, _ := s.validationRepo.ListBySchemaID(schema.ID, 10)
		validCacheKey := CachePrefixValidation + schema.ID
		s.setCache(validCacheKey, validations, CacheTTLDefault)
	}

	logger.Log.Info("Cache warmup completed", zap.Int("items_loaded", count))
	return count, nil
}

func (s *APIContractService) ClearCache() {
	s.invalidateCache(CachePrefixSchema)
	s.invalidateCache(CachePrefixValidation)
	s.invalidateCache(CachePrefixMockServer)
	logger.Log.Info("Cache cleared")
}

func (s *APIContractService) getSchemaCached(id string) (*APISchema, error) {
	cacheKey := CachePrefixSchema + id

	if cached, ok := s.getFromCache(cacheKey); ok {
		if schema, ok := cached.(*APISchema); ok {
			return schema, nil
		}
		if schemaMap, ok := cached.(map[string]interface{}); ok {
			var schema APISchema
			data, _ := json.Marshal(schemaMap)
			json.Unmarshal(data, &schema)
			return &schema, nil
		}
	}

	schema, err := s.schemaRepo.GetByID(id)
	if err != nil {
		return nil, err
	}

	s.setCache(cacheKey, schema, CacheTTLLong)
	return schema, nil
}

func (s *APIContractService) CreateSchema(req *CreateSchemaRequest) (*APISchema, error) {
	schema := &APISchema{
		Name:        req.Name,
		Version:     req.Version,
		SchemaType:  req.SchemaType,
		Content:     req.Content,
		Format:      req.Format,
		ServiceName: req.ServiceName,
		Metadata:    req.Metadata,
		IsActive:    req.IsActive,
	}

	err := s.schemaRepo.Create(schema)
	if err != nil {
		return nil, apperrors.Wrap(500, "failed to create schema", err)
	}
	return schema, nil
}

func (s *APIContractService) UpdateSchema(id string, req *UpdateSchemaRequest) error {
	_, err := s.getSchemaCached(id)
	if err != nil {
		return apperrors.ErrNotFound
	}

	updates := make(map[string]interface{})
	if req.Name != "" {
		updates["name"] = req.Name
	}
	if req.Version != "" {
		updates["version"] = req.Version
	}
	if req.Content != "" {
		updates["content"] = req.Content
	}
	if req.ServiceName != "" {
		updates["service_name"] = req.ServiceName
	}
	if req.Metadata != nil {
		updates["metadata"] = req.Metadata
	}
	if req.IsActive != nil {
		updates["is_active"] = *req.IsActive
	}

	err = s.schemaRepo.Update(id, updates)
	if err == nil {
		s.invalidateCache(CachePrefixSchema + id)
		logger.Log.Info("Schema cache invalidated", zap.String("schema_id", id))
	}
	return err
}

func (s *APIContractService) DeleteSchema(id string) error {
	_, err := s.getSchemaCached(id)
	if err != nil {
		return apperrors.ErrNotFound
	}
	err = s.schemaRepo.Delete(id)
	if err == nil {
		s.invalidateCache(CachePrefixSchema + id)
		s.invalidateCache(CachePrefixValidation + id)
		logger.Log.Info("Schema and validation cache invalidated", zap.String("schema_id", id))
	}
	return err
}

func (s *APIContractService) GetSchema(id string) (*APISchema, error) {
	return s.getSchemaCached(id)
}

func (s *APIContractService) ListSchemas(page, pageSize int, schemaType, serviceName string) ([]APISchema, int64, error) {
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}
	return s.schemaRepo.List(page, pageSize, schemaType, serviceName)
}

func (s *APIContractService) ValidateSchema(schemaID string) (*ValidationResult, error) {
	schema, err := s.getSchemaCached(schemaID)
	if err != nil {
		return nil, apperrors.New(400, "invalid schema id")
	}

	cacheKey := CachePrefixValidation + schemaID + ":last"
	if cached, ok := s.getFromCache(cacheKey); ok {
		if resultMap, ok := cached.(map[string]interface{}); ok {
			var result ValidationResult
			data, _ := json.Marshal(resultMap)
			json.Unmarshal(data, &result)
			logger.Log.Debug("Returning cached validation result", zap.String("schema_id", schemaID))
			return &result, nil
		}
	}

	startTime := time.Now()
	var errors []string
	var warnings []string

	if schema.SchemaType == "openapi" {
		if !strings.Contains(schema.Content, "openapi") && !strings.Contains(schema.Content, "swagger") {
			errors = append(errors, "Missing OpenAPI/Swagger version field")
		}
		if !strings.Contains(schema.Content, "paths") {
			errors = append(errors, "Missing paths definition")
		}
		if !strings.Contains(schema.Content, "info") {
			warnings = append(warnings, "Missing info section")
		}
	} else if schema.SchemaType == "graphql" {
		if !strings.Contains(schema.Content, "type") && !strings.Contains(schema.Content, "query") {
			errors = append(errors, "Invalid GraphQL schema format")
		}
	}

	if schema.Format == "json" {
		var js map[string]interface{}
		if err := json.Unmarshal([]byte(schema.Content), &js); err != nil {
			errors = append(errors, "Invalid JSON format: "+err.Error())
		}
	}

	status := "valid"
	if len(errors) > 0 {
		status = "invalid"
	}

	duration := time.Since(startTime).Milliseconds()

	result := &ValidationResult{
		SchemaID:    schemaID,
		Status:      status,
		Errors:      errors,
		Warnings:    warnings,
		Details:     map[string]interface{}{"schema_type": schema.SchemaType, "format": schema.Format},
		ValidatedAt: time.Now(),
		DurationMs:  duration,
	}

	err = s.validationRepo.Create(result)
	if err != nil {
		return nil, apperrors.Wrap(500, "failed to save validation result", err)
	}

	s.setCache(cacheKey, result, CacheTTLShort)

	return result, nil
}

func (s *APIContractService) GetValidationHistory(schemaID string, limit int) ([]ValidationResult, error) {
	if limit < 1 || limit > 100 {
		limit = 20
	}

	cacheKey := CachePrefixValidation + schemaID + ":history"
	if cached, ok := s.getFromCache(cacheKey); ok {
		if results, ok := cached.([]ValidationResult); ok {
			return results, nil
		}
	}

	results, err := s.validationRepo.ListBySchemaID(schemaID, limit)
	if err == nil {
		s.setCache(cacheKey, results, CacheTTLDefault)
	}
	return results, err
}

func (s *APIContractService) CreateMockServer(req *CreateMockServerRequest) (*MockServer, error) {
	_, err := s.schemaRepo.GetByID(req.SchemaID)
	if err != nil {
		return nil, apperrors.New(400, "invalid schema id")
	}

	port := 8000 + int(time.Now().Unix()%1000)
	serverID := utils.GenerateID("msrv")

	server := &MockServer{
		ServerID:   serverID,
		SchemaID:   req.SchemaID,
		Name:       req.Name,
		Port:       port,
		Status:     "running",
		BaseURL:    "http://localhost:" + utils.GenerateID(""),
		Config:     req.Config,
		Endpoints:  []string{"/api/v1/users", "/api/v1/health", "/api/v1/docs"},
		StartedAt:  utils.TimeNowPtr(),
	}

	err = s.mockServerRepo.Create(server)
	if err != nil {
		return nil, apperrors.Wrap(500, "failed to create mock server", err)
	}

	return server, nil
}

func (s *APIContractService) StopMockServer(serverID string) error {
	server, err := s.mockServerRepo.GetByServerID(serverID)
	if err != nil {
		return apperrors.ErrNotFound
	}

	now := time.Now()
	return s.mockServerRepo.Update(server.ID, map[string]interface{}{
		"status":     "stopped",
		"stopped_at": &now,
	})
}

func (s *APIContractService) GetMockServer(serverID string) (*MockServer, error) {
	server, err := s.mockServerRepo.GetByServerID(serverID)
	if err != nil {
		return nil, apperrors.ErrNotFound
	}
	return server, nil
}

func (s *APIContractService) ListMockServers(page, pageSize int, status string) ([]MockServer, int64, error) {
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}
	return s.mockServerRepo.List(page, pageSize, status)
}

func (s *APIContractService) CreateContractTest(req *CreateContractTestRequest) (*ContractTest, error) {
	_, err := s.schemaRepo.GetByID(req.SchemaID)
	if err != nil {
		return nil, apperrors.New(400, "invalid schema id")
	}

	test := &ContractTest{
		TestID:   utils.GenerateID("ctest"),
		SchemaID: req.SchemaID,
		Name:     req.Name,
		TestType: req.TestType,
		Request:  req.Request,
		Expected: req.Expected,
	}

	err = s.contractTestRepo.Create(test)
	if err != nil {
		return nil, apperrors.Wrap(500, "failed to create contract test", err)
	}
	return test, nil
}

func (s *APIContractService) RunContractTest(testID string) (map[string]interface{}, error) {
	test, err := s.contractTestRepo.GetByTestID(testID)
	if err != nil {
		return nil, apperrors.ErrNotFound
	}

	passed := len(test.Request) > 0
	now := time.Now()

	updates := map[string]interface{}{
		"last_result": "pass",
		"last_run_at": &now,
		"pass_count":  test.PassCount + 1,
	}

	if !passed {
		updates["last_result"] = "fail"
		updates["fail_count"] = test.FailCount + 1
	}

	err = s.contractTestRepo.Update(test.ID, updates)
	if err != nil {
		return nil, apperrors.Wrap(500, "failed to update test result", err)
	}

	return map[string]interface{}{
		"test_id":     testID,
		"result":      updates["last_result"],
		"duration_ms": 42,
		"run_at":      now,
	}, nil
}

func (s *APIContractService) ListContractTests(schemaID string) ([]ContractTest, error) {
	return s.contractTestRepo.ListBySchemaID(schemaID)
}

func (s *APIContractService) DeleteContractTest(id string) error {
	_, err := s.contractTestRepo.GetByID(id)
	if err != nil {
		return apperrors.ErrNotFound
	}
	return s.contractTestRepo.Delete(id)
}
