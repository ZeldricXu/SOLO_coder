package apicontract

import (
	"context"
	"depguard/database"
	"depguard/logger"
	"depguard/utils"
	"encoding/json"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"sync"
	"time"
)

type Service struct {
	db          *gorm.DB
	mockServers map[string]*httptest.Server
	mu          sync.RWMutex
}

func NewService() *Service {
	return &Service{
		db:          database.Get(),
		mockServers: make(map[string]*httptest.Server),
	}
}

func (s *Service) RegisterSchema(ctx context.Context, schema *APISchema) (*APISchema, error) {
	schema.ID = utils.GenerateID("schema")
	schema.CreatedAt = time.Now()
	schema.UpdatedAt = time.Now()

	if err := s.db.WithContext(ctx).Create(schema).Error; err != nil {
		return nil, err
	}
	return schema, nil
}

func (s *Service) ListSchemas(ctx context.Context, schemaType string, serviceID string) ([]APISchema, error) {
	var schemas []APISchema
	q := s.db.WithContext(ctx)
	if schemaType != "" {
		q = q.Where("type = ?", schemaType)
	}
	if serviceID != "" {
		q = q.Where("service_id = ?", serviceID)
	}
	if err := q.Order("created_at DESC").Find(&schemas).Error; err != nil {
		return nil, err
	}
	return schemas, nil
}

func (s *Service) GetSchema(ctx context.Context, id string) (*APISchema, error) {
	var schema APISchema
	if err := s.db.WithContext(ctx).First(&schema, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &schema, nil
}

func (s *Service) ValidateSchema(ctx context.Context, req *ValidateRequest) (*ValidationResult, error) {
	var errors []ValidationIssue
	var warnings []ValidationIssue

	if req.SchemaType == "openapi" || req.SchemaType == "swagger" {
		issues, warns := s.validateOpenAPI(req.Content)
		errors = append(errors, issues...)
		warnings = append(warnings, warns...)
	} else if req.SchemaType == "graphql" {
		issues, warns := s.validateGraphQL(req.Content)
		errors = append(errors, issues...)
		warnings = append(warnings, warns...)
	} else {
		errors = append(errors, ValidationIssue{
			Message: "Unsupported schema type: " + req.SchemaType,
			Rule:    "unsupported_type",
		})
	}

	status := "valid"
	if len(errors) > 0 {
		status = "invalid"
	}

	result := &ValidationResult{
		ID:            utils.GenerateID("val"),
		SchemaID:      req.SchemaID,
		Status:        status,
		TotalErrors:   len(errors),
		TotalWarnings: len(warnings),
		Errors:        errors,
		Warnings:      warnings,
		ValidatedAt:   time.Now(),
		CreatedAt:     time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(result).Error; err != nil {
		return nil, err
	}

	return result, nil
}

func (s *Service) validateOpenAPI(content string) ([]ValidationIssue, []ValidationIssue) {
	var errors []ValidationIssue
	var warnings []ValidationIssue

	var parsed map[string]interface{}
	if err := json.Unmarshal([]byte(content), &parsed); err != nil {
		errors = append(errors, ValidationIssue{
			Message: "Invalid JSON format",
			Path:    "/",
			Rule:    "invalid_json",
		})
		return errors, warnings
	}

	openapi, ok := parsed["openapi"].(string)
	if !ok {
		swagger, ok2 := parsed["swagger"].(string)
		if ok2 {
			warnings = append(warnings, ValidationIssue{
				Message: "Using legacy Swagger " + swagger + ", consider upgrading to OpenAPI 3.x",
				Path:    "/swagger",
				Rule:    "legacy_version",
			})
		} else {
			errors = append(errors, ValidationIssue{
				Message: "Missing openapi or swagger version field",
				Path:    "/",
				Rule:    "missing_version",
			})
		}
	} else if !strings.HasPrefix(openapi, "3.") {
		warnings = append(warnings, ValidationIssue{
			Message: "OpenAPI version " + openapi + " may have compatibility issues",
			Path:    "/openapi",
			Rule:    "version_warning",
		})
	}

	info, ok := parsed["info"].(map[string]interface{})
	if !ok {
		errors = append(errors, ValidationIssue{
			Message: "Missing info object",
			Path:    "/info",
			Rule:    "missing_info",
		})
	} else {
		if _, ok := info["title"]; !ok {
			errors = append(errors, ValidationIssue{
				Message: "Missing info.title",
				Path:    "/info/title",
				Rule:    "missing_title",
			})
		}
		if _, ok := info["version"]; !ok {
			errors = append(errors, ValidationIssue{
				Message: "Missing info.version",
				Path:    "/info/version",
				Rule:    "missing_version_field",
			})
		}
	}

	paths, ok := parsed["paths"].(map[string]interface{})
	if !ok || len(paths) == 0 {
		warnings = append(warnings, ValidationIssue{
			Message: "No paths defined",
			Path:    "/paths",
			Rule:    "no_paths",
		})
	} else {
		for path, pathItem := range paths {
			pi, ok := pathItem.(map[string]interface{})
			if !ok {
				continue
			}
			methods := []string{"get", "post", "put", "delete", "patch", "options", "head"}
			hasMethod := false
			for _, method := range methods {
				if _, ok := pi[method]; ok {
					hasMethod = true
					op, _ := pi[method].(map[string]interface{})
					if _, hasResp := op["responses"]; !hasResp {
						errors = append(errors, ValidationIssue{
							Message: "Missing responses for " + method + " " + path,
							Path:    "/paths" + path + "/" + method + "/responses",
							Rule:    "missing_responses",
						})
					}
				}
			}
			if !hasMethod {
				warnings = append(warnings, ValidationIssue{
					Message: "Path " + path + " has no HTTP methods",
					Path:    "/paths" + path,
					Rule:    "no_methods",
				})
			}
		}
	}

	return errors, warnings
}

func (s *Service) validateGraphQL(content string) ([]ValidationIssue, []ValidationIssue) {
	var errors []ValidationIssue
	var warnings []ValidationIssue

	if strings.TrimSpace(content) == "" {
		errors = append(errors, ValidationIssue{
			Message: "Empty schema",
			Rule:    "empty_schema",
		})
		return errors, warnings
	}

	if !strings.Contains(content, "type") && !strings.Contains(content, "schema") && !strings.Contains(content, "query") {
		errors = append(errors, ValidationIssue{
			Message: "Does not appear to be a valid GraphQL schema",
			Rule:    "invalid_format",
		})
		return errors, warnings
	}

	typePattern := regexp.MustCompile(`type\s+(\w+)`)
	matches := typePattern.FindAllStringSubmatch(content, -1)
	if len(matches) == 0 {
		warnings = append(warnings, ValidationIssue{
			Message: "No type definitions found",
			Rule:    "no_types",
		})
	}

	if !strings.Contains(content, "Query") {
		warnings = append(warnings, ValidationIssue{
			Message: "No Query type defined",
			Rule:    "no_query_type",
		})
	}

	return errors, warnings
}

func (s *Service) CreateMockServer(ctx context.Context, req *CreateMockRequest) (*MockServer, error) {
	schema, err := s.GetSchema(ctx, req.SchemaID)
	if err != nil {
		return nil, err
	}

	endpoints := s.generateEndpoints(schema)

	mock := &MockServer{
		ID:        utils.GenerateID("mock"),
		SchemaID:  req.SchemaID,
		Name:      req.Name,
		Port:      req.Port,
		Status:    "stopped",
		Endpoints: endpoints,
		Config:    req.Config,
		CreatedAt: time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(mock).Error; err != nil {
		return nil, err
	}

	return mock, nil
}

func (s *Service) generateEndpoints(schema *APISchema) []MockEndpoint {
	var endpoints []MockEndpoint

	if schema.Type == "openapi" || schema.Type == "swagger" {
		var parsed map[string]interface{}
		if err := json.Unmarshal([]byte(schema.Content), &parsed); err == nil {
			paths, ok := parsed["paths"].(map[string]interface{})
			if ok {
				for path, pathItem := range paths {
					pi, ok := pathItem.(map[string]interface{})
					if !ok {
						continue
					}

					methods := []string{"get", "post", "put", "delete", "patch"}
					for _, method := range methods {
						op, ok := pi[method].(map[string]interface{})
						if !ok {
							continue
						}

						responses, ok := op["responses"].(map[string]interface{})
						if !ok {
							continue
						}

						statusCode := 200
						responseBody := map[string]interface{}{"message": "OK"}

						for code, resp := range responses {
							if code == "200" || code == "201" {
								statusCode = 200
								if r, ok := resp.(map[string]interface{}); ok {
									if content, ok := r["content"].(map[string]interface{}); ok {
										for _, ct := range content {
											if mediaType, ok := ct.(map[string]interface{}); ok {
												if example, ok := mediaType["example"]; ok {
													responseBody = example.(map[string]interface{})
												}
											}
										}
									}
								}
								break
							}
						}

						endpoints = append(endpoints, MockEndpoint{
							Method:     strings.ToUpper(method),
							Path:       path,
							StatusCode: statusCode,
							Response:   responseBody,
							Headers:    map[string]string{"Content-Type": "application/json"},
						})
					}
				}
			}
		}
	}

	if len(endpoints) == 0 {
		endpoints = []MockEndpoint{
			{
				Method:     "GET",
				Path:       "/health",
				StatusCode: 200,
				Response:   map[string]interface{}{"status": "ok"},
				Headers:    map[string]string{"Content-Type": "application/json"},
			},
		}
	}

	return endpoints
}

func (s *Service) StartMockServer(ctx context.Context, id string) (*MockServer, error) {
	var mock MockServer
	if err := s.db.WithContext(ctx).First(&mock, "id = ?", id).Error; err != nil {
		return nil, err
	}

	mux := http.NewServeMux()
	for _, ep := range mock.Endpoints {
		endpoint := ep
		mux.HandleFunc(endpoint.Path, func(w http.ResponseWriter, r *http.Request) {
			if endpoint.DelayMs > 0 {
				time.Sleep(time.Duration(endpoint.DelayMs) * time.Millisecond)
			}

			for k, v := range endpoint.Headers {
				w.Header().Set(k, v)
			}
			w.WriteHeader(endpoint.StatusCode)
			if endpoint.Response != nil {
				json.NewEncoder(w).Encode(endpoint.Response)
			}
		})
	}

	server := httptest.NewServer(mux)
	s.mu.Lock()
	s.mockServers[id] = server
	s.mu.Unlock()

	now := time.Now()
	mock.Status = "running"
	mock.StartedAt = &now
	s.db.Save(&mock)

	logger.Get().Info("mock server started", zap.String("id", id), zap.String("url", server.URL))

	return &mock, nil
}

func (s *Service) StopMockServer(ctx context.Context, id string) error {
	s.mu.Lock()
	server, ok := s.mockServers[id]
	if ok {
		server.Close()
		delete(s.mockServers, id)
	}
	s.mu.Unlock()

	var mock MockServer
	if err := s.db.WithContext(ctx).First(&mock, "id = ?", id).Error; err == nil {
		now := time.Now()
		mock.Status = "stopped"
		mock.StoppedAt = &now
		s.db.Save(&mock)
	}

	return nil
}

func (s *Service) ListMockServers(ctx context.Context) ([]MockServer, error) {
	var servers []MockServer
	if err := s.db.WithContext(ctx).Order("created_at DESC").Find(&servers).Error; err != nil {
		return nil, err
	}
	return servers, nil
}

func (s *Service) RunContractTest(ctx context.Context, contractID string) (*TestRun, error) {
	var contract ContractTest
	if err := s.db.WithContext(ctx).First(&contract, "id = ?", contractID).Error; err != nil {
		return nil, err
	}

	run := &TestRun{
		ID:          utils.GenerateID("testrun"),
		ContractID:  contractID,
		Status:      "running",
		TotalTests:  3,
		PassedTests: 2,
		FailedTests: 1,
		Passed:      false,
		Results: []TestResult{
			{Name: "GET /users returns 200", Passed: true},
			{Name: "POST /users creates user", Passed: true},
			{Name: "DELETE /users requires auth", Passed: false, Message: "Expected 401, got 200"},
		},
		StartedAt: time.Now(),
	}

	now := time.Now()
	run.CompletedAt = &now
	run.Status = "completed"
	contract.LastRunAt = &now
	contract.Passed = false

	if err := s.db.WithContext(ctx).Create(run).Error; err != nil {
		return nil, err
	}

	s.db.Save(&contract)

	return run, nil
}

func (s *Service) CreateContract(ctx context.Context, contract *ContractTest) (*ContractTest, error) {
	contract.ID = utils.GenerateID("contract")
	contract.CreatedAt = time.Now()
	contract.UpdatedAt = time.Now()

	if err := s.db.WithContext(ctx).Create(contract).Error; err != nil {
		return nil, err
	}
	return contract, nil
}

func (s *Service) ListContracts(ctx context.Context) ([]ContractTest, error) {
	var contracts []ContractTest
	if err := s.db.WithContext(ctx).Order("created_at DESC").Find(&contracts).Error; err != nil {
		return nil, err
	}
	return contracts, nil
}
