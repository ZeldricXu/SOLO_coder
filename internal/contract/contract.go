package contract

import (
	"encoding/json"
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/graphql-go/graphql"
	"github.com/solocoder/session138/pkg/utils"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

type SchemaValidationRequest struct {
	SchemaType string                 `json:"schema_type" binding:"required"`
	Schema     map[string]interface{} `json:"schema" binding:"required"`
	Endpoint   string                 `json:"endpoint"`
}

type ValidationResult struct {
	Valid    bool        `json:"valid"`
	Errors   []string    `json:"errors"`
	Warnings []string    `json:"warnings"`
	Metadata interface{} `json:"metadata"`
}

type MockServerConfig struct {
	ID        string                 `json:"id"`
	Name      string                 `json:"name"`
	Schema    map[string]interface{} `json:"schema"`
	Endpoints []MockEndpoint         `json:"endpoints"`
	Port      int                    `json:"port"`
	Running   bool                   `json:"running"`
}

type MockEndpoint struct {
	Path     string      `json:"path"`
	Method   string      `json:"method"`
	Response interface{} `json:"response"`
	Status   int         `json:"status"`
	Delay    int         `json:"delay"`
}

var (
	mockServers = make(map[string]*MockServerConfig)
	serverMu    sync.Mutex
)

func ValidateOpenAPISchema(c *gin.Context) {
	var req SchemaValidationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	result := validateOpenAPI(req.Schema)

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": result})
}

func validateOpenAPI(schema map[string]interface{}) ValidationResult {
	result := ValidationResult{
		Valid:    true,
		Errors:   make([]string, 0),
		Warnings: make([]string, 0),
	}

	if _, ok := schema["openapi"]; !ok {
		result.Errors = append(result.Errors, "缺少openapi版本字段")
		result.Valid = false
	}

	if info, ok := schema["info"].(map[string]interface{}); ok {
		if _, ok := info["title"]; !ok {
			result.Errors = append(result.Errors, "info.title是必填字段")
			result.Valid = false
		}
		if _, ok := info["version"]; !ok {
			result.Errors = append(result.Errors, "info.version是必填字段")
			result.Valid = false
		}
	} else {
		result.Errors = append(result.Errors, "缺少info对象")
		result.Valid = false
	}

	if paths, ok := schema["paths"].(map[string]interface{}); ok {
		for path, methods := range paths {
			if methodMap, ok := methods.(map[string]interface{}); ok {
				for method, operation := range methodMap {
					validMethods := []string{"get", "post", "put", "delete", "patch", "options", "head"}
					if !utils.Contains(validMethods, strings.ToLower(method)) {
						result.Warnings = append(result.Warnings, fmt.Sprintf("路径 %s 包含不支持的HTTP方法: %s", path, method))
					}

					if op, ok := operation.(map[string]interface{}); ok {
						if responses, ok := op["responses"].(map[string]interface{}); !ok {
							result.Errors = append(result.Errors, fmt.Sprintf("路径 %s %s 缺少responses定义", path, method))
							result.Valid = false
						} else if _, ok := responses["200"]; !ok {
							result.Warnings = append(result.Warnings, fmt.Sprintf("路径 %s %s 建议定义200响应", path, method))
						}
					}
				}
			}
		}
	} else {
		result.Errors = append(result.Errors, "缺少paths对象")
		result.Valid = false
	}

	result.Metadata = gin.H{
		"schema_version": schema["openapi"],
		"paths_count":    len(schema["paths"].(map[string]interface{})),
	}

	return result
}

func ValidateGraphQLSchema(c *gin.Context) {
	var req SchemaValidationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	result := validateGraphQL(req.Schema)

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": result})
}

func validateGraphQL(schema map[string]interface{}) ValidationResult {
	result := ValidationResult{
		Valid:    true,
		Errors:   make([]string, 0),
		Warnings: make([]string, 0),
	}

	schemaStr, ok := schema["schema"].(string)
	if !ok {
		schemaStr = utils.ToJSON(schema)
	}

	if !strings.Contains(schemaStr, "type Query") && !strings.Contains(schemaStr, "query") {
		result.Warnings = append(result.Warnings, "Schema缺少Query类型定义")
	}

	rootQuery := graphql.NewObject(graphql.ObjectConfig{
		Name: "Query",
		Fields: graphql.Fields{
			"hello": &graphql.Field{
				Type: graphql.String,
				Resolve: func(p graphql.ResolveParams) (interface{}, error) {
					return "world", nil
				},
			},
		},
	})

	_, err := graphql.NewSchema(graphql.SchemaConfig{
		Query: rootQuery,
	})

	if err != nil {
		result.Errors = append(result.Errors, err.Error())
		result.Valid = false
	}

	return result
}

func CreateMockServer(c *gin.Context) {
	var config MockServerConfig
	if err := c.ShouldBindJSON(&config); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	config.ID = utils.GenerateID("mock")
	config.Running = false

	if config.Port == 0 {
		config.Port = 9000 + len(mockServers)
	}

	serverMu.Lock()
	mockServers[config.ID] = &config
	serverMu.Unlock()

	c.JSON(http.StatusCreated, gin.H{"code": 201, "data": config})
}

func ListMockServers(c *gin.Context) {
	serverMu.Lock()
	defer serverMu.Unlock()

	result := make([]MockServerConfig, 0, len(mockServers))
	for _, s := range mockServers {
		result = append(result, *s)
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": result})
}

func StartMockServer(c *gin.Context) {
	id := c.Param("id")

	serverMu.Lock()
	config, ok := mockServers[id]
	serverMu.Unlock()

	if !ok {
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "Mock服务器不存在"})
		return
	}

	if config.Running {
		c.JSON(http.StatusOK, gin.H{"code": 200, "message": "Mock服务器已经在运行"})
		return
	}

	go startMockServer(config)

	config.Running = true

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"id":      id,
			"status":  "running",
			"base_url": fmt.Sprintf("http://localhost:%d", config.Port),
		},
	})
}

func startMockServer(config *MockServerConfig) {
	r := gin.Default()

	for _, endpoint := range config.Endpoints {
		handler := func(ep MockEndpoint) gin.HandlerFunc {
			return func(c *gin.Context) {
				if ep.Delay > 0 {
					gin.Sleep(time.Duration(ep.Delay) * time.Millisecond)
				}
				c.JSON(ep.Status, ep.Response)
			}
		}(endpoint)

		switch strings.ToUpper(endpoint.Method) {
		case "GET":
			r.GET(endpoint.Path, handler)
		case "POST":
			r.POST(endpoint.Path, handler)
		case "PUT":
			r.PUT(endpoint.Path, handler)
		case "DELETE":
			r.DELETE(endpoint.Path, handler)
		default:
			r.Any(endpoint.Path, handler)
		}
	}

	r.Run(fmt.Sprintf(":%d", config.Port))
}

func StopMockServer(c *gin.Context) {
	id := c.Param("id")

	serverMu.Lock()
	config, ok := mockServers[id]
	serverMu.Unlock()

	if !ok {
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "Mock服务器不存在"})
		return
	}

	config.Running = false

	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "Mock服务器已停止"})
}

func DeleteMockServer(c *gin.Context) {
	id := c.Param("id")

	serverMu.Lock()
	defer serverMu.Unlock()

	if _, ok := mockServers[id]; !ok {
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "Mock服务器不存在"})
		return
	}

	delete(mockServers, id)

	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "Mock服务器已删除"})
}

func GenerateContractTests(c *gin.Context) {
	var req SchemaValidationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	tests := generateTestsFromSchema(req.Schema, req.SchemaType)

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"schema_type": req.SchemaType,
			"tests":       tests,
			"count":       len(tests),
		},
	})
}

func generateTestsFromSchema(schema map[string]interface{}, schemaType string) []map[string]interface{} {
	tests := make([]map[string]interface{}, 0)

	if strings.EqualFold(schemaType, "openapi") {
		if paths, ok := schema["paths"].(map[string]interface{}); ok {
			for path, methods := range paths {
				if methodMap, ok := methods.(map[string]interface{}); ok {
					for method := range methodMap {
						tests = append(tests, map[string]interface{}{
							"name":        fmt.Sprintf("测试 %s %s", strings.ToUpper(method), path),
							"path":        path,
							"method":      strings.ToUpper(method),
							"type":        "contract",
							"description": fmt.Sprintf("验证 %s %s 的契约", strings.ToUpper(method), path),
						})
					}
				}
			}
		}
	}

	return tests
}

func VerifyEndpoint(c *gin.Context) {
	var req struct {
		Endpoint string                 `json:"endpoint" binding:"required"`
		Schema   map[string]interface{} `json:"schema"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	resp, err := http.Get(req.Endpoint)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": gin.H{
				"valid":   false,
				"message": "端点不可访问",
				"error":   err.Error(),
			},
		})
		return
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	var responseData map[string]interface{}
	json.Unmarshal(body, &responseData)

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"valid":        true,
			"status_code":  resp.StatusCode,
			"content_type": resp.Header.Get("Content-Type"),
			"response":     responseData,
		},
	})
}

func RegisterRoutes(r *gin.RouterGroup) {
	contract := r.Group("/contract")
	{
		contract.POST("/validate/openapi", ValidateOpenAPISchema)
		contract.POST("/validate/graphql", ValidateGraphQLSchema)
		contract.POST("/verify", VerifyEndpoint)
		contract.POST("/mock", CreateMockServer)
		contract.GET("/mock", ListMockServers)
		contract.POST("/mock/:id/start", StartMockServer)
		contract.POST("/mock/:id/stop", StopMockServer)
		contract.DELETE("/mock/:id", DeleteMockServer)
		contract.POST("/generate-tests", GenerateContractTests)
	}
}
