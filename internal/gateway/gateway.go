package gateway

import (
	"bytes"
	"encoding/json"
	"github.com/gin-gonic/gin"
	"github.com/solocoder/session138/internal/logger"
	"github.com/solocoder/session138/pkg/utils"
	"go.uber.org/zap"
	"io"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"sync"
	"time"
)

type RouteConfig struct {
	ID          string            `json:"id"`
	Path        string            `json:"path" binding:"required"`
	TargetURL   string            `json:"target_url" binding:"required"`
	Method      string            `json:"method"`
	Protocol    string            `json:"protocol"`
	Timeout     int               `json:"timeout"`
	RateLimit   int               `json:"rate_limit"`
	Headers     map[string]string `json:"headers"`
	Middlewares []string          `json:"middlewares"`
	Enabled     bool              `json:"enabled"`
}

type ProtocolConversionRequest struct {
	SourceProtocol string                 `json:"source_protocol"`
	TargetProtocol string                 `json:"target_protocol"`
	Payload        map[string]interface{} `json:"payload"`
	TargetURL      string                 `json:"target_url"`
}

var routes = map[string]RouteConfig{}

func RegisterRoute(c *gin.Context) {
	var config RouteConfig
	if err := c.ShouldBindJSON(&config); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	config.ID = utils.GenerateID("route")
	config.Enabled = true

	routes[config.Path] = config

	logger.Info("gateway", "路由已注册",
		zap.String("path", config.Path),
		zap.String("target", config.TargetURL),
	)

	c.JSON(http.StatusCreated, gin.H{"code": 201, "data": config})
}

func ListRoutes(c *gin.Context) {
	result := make([]RouteConfig, 0, len(routes))
	for _, r := range routes {
		result = append(result, r)
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": result})
}

func DeleteRoute(c *gin.Context) {
	path := c.Param("path")
	if _, exists := routes[path]; !exists {
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "路由不存在"})
		return
	}

	delete(routes, path)
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "路由已删除"})
}

func ProxyHandler(c *gin.Context) {
	path := c.Param("proxy_path")
	matchedRoute, ok := routes[path]
	if !ok {
		for routePath, route := range routes {
			if strings.HasPrefix(path, routePath) {
				matchedRoute = route
				ok = true
				break
			}
		}
	}

	if !ok {
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "路由未配置"})
		return
	}

	if !matchedRoute.Enabled {
		c.JSON(http.StatusServiceUnavailable, gin.H{"code": 503, "message": "服务已禁用"})
		return
	}

	target, err := url.Parse(matchedRoute.TargetURL)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": "目标URL无效"})
		return
	}

	proxy := httputil.NewSingleHostReverseProxy(target)
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		logger.Error("gateway", "代理请求失败",
			zap.String("path", path),
			zap.String("target", target.String()),
			zap.Error(err),
		)
		w.WriteHeader(http.StatusBadGateway)
		json.NewEncoder(w).Encode(gin.H{"code": 502, "message": "上游服务错误"})
	}

	for key, value := range matchedRoute.Headers {
		c.Request.Header.Set(key, value)
	}

	originalDirector := proxy.Director
	proxy.Director = func(req *http.Request) {
		originalDirector(req)
		req.URL.Path = strings.TrimPrefix(req.URL.Path, "/proxy/"+path)
		if !strings.HasPrefix(req.URL.Path, "/") {
			req.URL.Path = "/" + req.URL.Path
		}
	}

	logger.Info("gateway", "转发请求",
		zap.String("path", path),
		zap.String("target", target.String()+c.Request.URL.Path),
		zap.String("method", c.Request.Method),
	)

	proxy.ServeHTTP(c.Writer, c.Request)
}

func ConvertProtocol(c *gin.Context) {
	var req ProtocolConversionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	var response map[string]interface{}
	var err error

	switch strings.ToLower(req.TargetProtocol) {
	case "rest":
		response, err = convertToREST(req)
	case "graphql":
		response, err = convertToGraphQL(req)
	case "grpc":
		response, err = convertToGRPC(req)
	default:
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "不支持的目标协议"})
		return
	}

	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": "协议转换失败", "error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"source_protocol": req.SourceProtocol,
			"target_protocol": req.TargetProtocol,
			"response":        response,
		},
	})
}

func convertToREST(req ProtocolConversionRequest) (map[string]interface{}, error) {
	jsonData, _ := json.Marshal(req.Payload)
	httpReq, err := http.NewRequest("POST", req.TargetURL, bytes.NewBuffer(jsonData))
	if err != nil {
		return nil, err
	}
	httpReq.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(httpReq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	var result map[string]interface{}
	json.Unmarshal(body, &result)

	return result, nil
}

func convertToGraphQL(req ProtocolConversionRequest) (map[string]interface{}, error) {
	query := buildGraphQLQuery(req.Payload)
	graphqlReq := map[string]interface{}{
		"query": query,
	}

	jsonData, _ := json.Marshal(graphqlReq)
	httpReq, err := http.NewRequest("POST", req.TargetURL, bytes.NewBuffer(jsonData))
	if err != nil {
		return nil, err
	}
	httpReq.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(httpReq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	var result map[string]interface{}
	json.Unmarshal(body, &result)

	return result, nil
}

func convertToGRPC(req ProtocolConversionRequest) (map[string]interface{}, error) {
	return map[string]interface{}{
		"status":  "simulated",
		"message": "gRPC转换模拟成功",
		"payload": req.Payload,
	}, nil
}

func buildGraphQLQuery(payload map[string]interface{}) string {
	if query, ok := payload["query"].(string); ok {
		return query
	}
	return "{ __typename }"
}

func GetGatewayMetrics(c *gin.Context) {
	metrics := map[string]interface{}{
		"active_routes": len(routes),
		"total_requests": 0,
		"error_rate":     0.0,
		"avg_latency":    0,
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": metrics})
}

func RateLimitMiddleware() gin.HandlerFunc {
	requestCounts := make(map[string]int)
	var mu sync.Mutex

	return func(c *gin.Context) {
		clientIP := c.ClientIP()

		mu.Lock()
		requestCounts[clientIP]++
		count := requestCounts[clientIP]
		mu.Unlock()

		if count > 100 {
			c.JSON(http.StatusTooManyRequests, gin.H{"code": 429, "message": "请求过于频繁"})
			c.Abort()
			return
		}

		c.Next()
	}
}

func RegisterRoutes(r *gin.RouterGroup) {
	gateway := r.Group("/gateway")
	{
		gateway.POST("/routes", RegisterRoute)
		gateway.GET("/routes", ListRoutes)
		gateway.DELETE("/routes/:path", DeleteRoute)
		gateway.POST("/convert", ConvertProtocol)
		gateway.GET("/metrics", GetGatewayMetrics)
	}

	r.Any("/proxy/*proxy_path", RateLimitMiddleware(), ProxyHandler)
}
