package apicontract

import (
	"encoding/json"
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/solocoder/tasktracker/internal/logger"
	"io/ioutil"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
)

type SchemaType string

const (
	SchemaTypeOpenAPI   SchemaType = "openapi"
	SchemaTypeGraphQL   SchemaType = "graphql"
	SchemaTypeJSONSchema SchemaType = "jsonschema"
)

type Schema struct {
	ID      string                 `json:"id"`
	Type    SchemaType             `json:"type"`
	Version string                 `json:"version"`
	Content map[string]interface{} `json:"content"`
	Path    string                 `json:"path"`
}

type ValidationError struct {
	Path    string `json:"path"`
	Message string `json:"message"`
	Value   interface{} `json:"value,omitempty"`
}

type ValidationResult struct {
	Valid   bool              `json:"valid"`
	Errors  []ValidationError `json:"errors,omitempty"`
	SchemaID string           `json:"schema_id"`
}

type MockEndpoint struct {
	Path       string            `json:"path"`
	Method     string            `json:"method"`
	StatusCode int               `json:"status_code"`
	Response   interface{}       `json:"response"`
	Headers    map[string]string `json:"headers,omitempty"`
	DelayMs    int               `json:"delay_ms,omitempty"`
}

type ContractManager struct {
	mu            sync.RWMutex
	schemas       map[string]*Schema
	mockEndpoints map[string]*MockEndpoint
	mockServer    *httptest.Server
	ginEngine     *gin.Engine
}

func NewContractManager() *ContractManager {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	cm := &ContractManager{
		schemas:       make(map[string]*Schema),
		mockEndpoints: make(map[string]*MockEndpoint),
		ginEngine:     r,
	}

	r.NoRoute(cm.handleNoRoute)
	return cm
}

func (cm *ContractManager) LoadSchema(id string, schemaType SchemaType, version string, content map[string]interface{}) error {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	cm.schemas[id] = &Schema{
		ID:      id,
		Type:    schemaType,
		Version: version,
		Content: content,
	}

	logger.Info("Schema loaded", logger.String("schema_id", id), logger.String("type", string(schemaType)))
	return nil
}

func (cm *ContractManager) LoadSchemaFromFile(id string, schemaType SchemaType, version string, filePath string) error {
	data, err := ioutil.ReadFile(filePath)
	if err != nil {
		return fmt.Errorf("failed to read schema file: %w", err)
	}

	var content map[string]interface{}
	if err := json.Unmarshal(data, &content); err != nil {
		return fmt.Errorf("failed to parse schema file: %w", err)
	}

	return cm.LoadSchema(id, schemaType, version, content)
}

func (cm *ContractManager) GetSchema(id string) (*Schema, error) {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	schema, ok := cm.schemas[id]
	if !ok {
		return nil, fmt.Errorf("schema not found: %s", id)
	}
	return schema, nil
}

func (cm *ContractManager) ValidatePayload(schemaID string, payload map[string]interface{}) (*ValidationResult, error) {
	schema, err := cm.GetSchema(schemaID)
	if err != nil {
		return nil, err
	}

	result := &ValidationResult{
		Valid:    true,
		SchemaID: schemaID,
	}

	if schema.Type == SchemaTypeJSONSchema {
		errors := cm.validateJSONSchema(schema.Content, payload, "")
		if len(errors) > 0 {
			result.Valid = false
			result.Errors = errors
		}
	} else if schema.Type == SchemaTypeOpenAPI {
		errors := cm.validateOpenAPI(schema.Content, payload)
		if len(errors) > 0 {
			result.Valid = false
			result.Errors = errors
		}
	}

	return result, nil
}

func (cm *ContractManager) validateJSONSchema(schema, data map[string]interface{}, path string) []ValidationError {
	var errors []ValidationError

	if required, ok := schema["required"].([]interface{}); ok {
		for _, req := range required {
			reqStr, _ := req.(string)
			fieldPath := path + "." + reqStr
			if _, exists := data[reqStr]; !exists {
				errors = append(errors, ValidationError{
					Path:    fieldPath,
					Message: "required field missing",
				})
			}
		}
	}

	if properties, ok := schema["properties"].(map[string]interface{}); ok {
		for key, prop := range properties {
			fieldPath := path + "." + key
			if val, exists := data[key]; exists {
				propMap, _ := prop.(map[string]interface{})
				propErrors := cm.validateProperty(propMap, val, fieldPath)
				errors = append(errors, propErrors...)
			}
		}
	}

	return errors
}

func (cm *ContractManager) validateProperty(prop map[string]interface{}, value interface{}, path string) []ValidationError {
	var errors []ValidationError

	if propType, ok := prop["type"].(string); ok {
		typeValid := true
		switch propType {
		case "string":
			_, typeValid = value.(string)
		case "integer", "number":
			switch value.(type) {
			case float64, int, float32:
				typeValid = true
			default:
				typeValid = false
			}
		case "boolean":
			_, typeValid = value.(bool)
		case "object":
			_, typeValid = value.(map[string]interface{})
			if typeValid {
				if nestedProps, ok := prop["properties"].(map[string]interface{}); ok {
					nestedErrors := cm.validateJSONSchema(prop, value.(map[string]interface{}), path)
					errors = append(errors, nestedErrors...)
				}
			}
		case "array":
			_, typeValid = value.([]interface{})
		}

		if !typeValid {
			errors = append(errors, ValidationError{
				Path:    path,
				Message: fmt.Sprintf("expected type %s, got %T", propType, value),
				Value:   value,
			})
		}
	}

	return errors
}

func (cm *ContractManager) validateOpenAPI(schema, data map[string]interface{}) []ValidationError {
	var errors []ValidationError

	if paths, ok := schema["paths"].(map[string]interface{}); ok {
		for path, pathItem := range paths {
			pathMap, _ := pathItem.(map[string]interface{})
			for method, operation := range pathMap {
				if strings.HasPrefix(method, "x-") {
					continue
				}
				opMap, _ := operation.(map[string]interface{})
				if requestBody, ok := opMap["requestBody"].(map[string]interface{}); ok {
					if content, ok := requestBody["content"].(map[string]interface{}); ok {
						for _, mediaType := range content {
							mediaMap, _ := mediaType.(map[string]interface{})
							if schema, ok := mediaMap["schema"].(map[string]interface{}); ok {
								schemaErrors := cm.validateJSONSchema(schema, data, path)
								errors = append(errors, schemaErrors...)
							}
						}
					}
				}
			}
		}
	}

	return errors
}

func (cm *ContractManager) AddMockEndpoint(endpoint *MockEndpoint) error {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	key := endpoint.Method + ":" + endpoint.Path
	cm.mockEndpoints[key] = endpoint

	cm.ginEngine.Handle(endpoint.Method, endpoint.Path, func(c *gin.Context) {
		if endpoint.DelayMs > 0 {
			cm.mockDelay(endpoint.DelayMs)
		}
		for k, v := range endpoint.Headers {
			c.Header(k, v)
		}
		c.JSON(endpoint.StatusCode, endpoint.Response)
	})

	logger.Info("Mock endpoint added", logger.String("method", endpoint.Method), logger.String("path", endpoint.Path))
	return nil
}

func (cm *ContractManager) RemoveMockEndpoint(method, path string) {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	key := method + ":" + path
	delete(cm.mockEndpoints, key)
	logger.Info("Mock endpoint removed", logger.String("method", method), logger.String("path", path))
}

func (cm *ContractManager) StartMockServer(port int) error {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	if cm.mockServer != nil {
		cm.mockServer.Close()
	}

	server := &http.Server{
		Addr:    fmt.Sprintf(":%d", port),
		Handler: cm.ginEngine,
	}

	go func() {
		logger.Info("Mock server starting", logger.Int("port", port))
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Error("Mock server error", logger.ErrorField(err))
		}
	}()

	cm.mockServer = httptest.NewServer(cm.ginEngine)
	logger.Info("Mock server started", logger.String("url", cm.mockServer.URL))
	return nil
}

func (cm *ContractManager) StopMockServer() {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	if cm.mockServer != nil {
		cm.mockServer.Close()
		cm.mockServer = nil
		logger.Info("Mock server stopped")
	}
}

func (cm *ContractManager) GetMockServerURL() string {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	if cm.mockServer != nil {
		return cm.mockServer.URL
	}
	return ""
}

func (cm *ContractManager) handleNoRoute(c *gin.Context) {
	c.JSON(404, gin.H{
		"error":   "Mock endpoint not found",
		"path":    c.Request.URL.Path,
		"method":  c.Request.Method,
		"message": "No mock endpoint configured for this route",
	})
}

func (cm *ContractManager) mockDelay(ms int) {
	select {
	case <-cm.ginEngine.Pool().(chan struct{}):
	default:
	}
}

func (cm *ContractManager) ListSchemas() []*Schema {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	result := make([]*Schema, 0, len(cm.schemas))
	for _, s := range cm.schemas {
		result = append(result, s)
	}
	return result
}

func (cm *ContractManager) ListMockEndpoints() []*MockEndpoint {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	result := make([]*MockEndpoint, 0, len(cm.mockEndpoints))
	for _, e := range cm.mockEndpoints {
		result = append(result, e)
	}
	return result
}
