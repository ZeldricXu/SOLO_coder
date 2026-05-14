package api

import (
	"apigateway/auth"
	"apigateway/circuitbreaker"
	"apigateway/loadbalancer"
	"apigateway/logger"
	"apigateway/metrics"
	"apigateway/models"
	"apigateway/ratelimit"
	"apigateway/router"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"time"
)

type APIServer struct {
	routerManager    *router.RouterManager
	rateLimiter      *ratelimit.RateLimiter
	loadBalancer     *loadbalancer.LoadBalancer
	circuitBreaker   *circuitbreaker.CircuitBreaker
	metricsCollector *metrics.MetricsCollector
	logger           *logger.RequestLogger
	authManager      *auth.AuthManager
}

func NewAPIServer(
	routerManager *router.RouterManager,
	rateLimiter *ratelimit.RateLimiter,
	loadBalancer *loadbalancer.LoadBalancer,
	circuitBreaker *circuitbreaker.CircuitBreaker,
	metricsCollector *metrics.MetricsCollector,
	logger *logger.RequestLogger,
	authManager *auth.AuthManager,
) *APIServer {
	return &APIServer{
		routerManager:    routerManager,
		rateLimiter:      rateLimiter,
		loadBalancer:     loadBalancer,
		circuitBreaker:   circuitBreaker,
		metricsCollector: metricsCollector,
		logger:           logger,
		authManager:      authManager,
	}
}

func (s *APIServer) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/v1/routes/create", s.CreateRoute)
	mux.HandleFunc("/api/v1/routes/list", s.ListRoutes)
	mux.HandleFunc("/api/v1/routes/get", s.GetRoute)
	mux.HandleFunc("/api/v1/routes/update", s.UpdateRoute)
	mux.HandleFunc("/api/v1/routes/delete", s.DeleteRoute)

	mux.HandleFunc("/api/v1/stats/query", s.QueryStats)
	mux.HandleFunc("/api/v1/stats/summary", s.GetStatsSummary)
	mux.HandleFunc("/api/v1/stats/top", s.GetTopRoutes)

	mux.HandleFunc("/api/v1/circuit/status", s.GetCircuitStatus)
	mux.HandleFunc("/api/v1/circuit/list", s.ListCircuits)
	mux.HandleFunc("/api/v1/circuit/reset", s.ResetCircuit)

	mux.HandleFunc("/api/v1/logs/query", s.QueryLogs)
	mux.HandleFunc("/api/v1/logs/stats", s.GetLogStats)

	mux.HandleFunc("/api/v1/services/list", s.ListServices)
	mux.HandleFunc("/api/v1/services/instances", s.GetServiceInstances)

	mux.HandleFunc("/api/v1/auth/keys", s.ListAPIKeys)
	mux.HandleFunc("/api/v1/auth/keys/create", s.CreateAPIKey)
	mux.HandleFunc("/api/v1/auth/keys/delete", s.DeleteAPIKey)
}

func writeJSONResponse(w http.ResponseWriter, statusCode int, response models.APIResponse) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	json.NewEncoder(w).Encode(response)
}

func (s *APIServer) CreateRoute(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	var req models.CreateRouteRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
			Code: http.StatusBadRequest,
			Msg:  "Invalid request body",
		})
		return
	}

	route, err := s.routerManager.CreateRoute(&req)
	if err != nil {
		writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
			Code: http.StatusBadRequest,
			Msg:  err.Error(),
		})
		return
	}

	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Data: map[string]string{"route_id": route.RouteID},
		Msg:  "Route created successfully",
	})
}

func (s *APIServer) ListRoutes(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	group := r.URL.Query().Get("group")
	var routes []*models.Route

	if group != "" {
		routes = s.routerManager.ListRoutesByGroup(group)
	} else {
		routes = s.routerManager.ListRoutes()
	}

	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Data: map[string]interface{}{"routes": routes},
	})
}

func (s *APIServer) GetRoute(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	routeID := r.URL.Query().Get("route_id")
	if routeID == "" {
		writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
			Code: http.StatusBadRequest,
			Msg:  "Route ID is required",
		})
		return
	}

	route, err := s.routerManager.GetRoute(routeID)
	if err != nil {
		writeJSONResponse(w, http.StatusNotFound, models.APIResponse{
			Code: http.StatusNotFound,
			Msg:  "Route not found",
		})
		return
	}

	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Data: route,
	})
}

func (s *APIServer) UpdateRoute(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPut && r.Method != http.MethodPost {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	routeID := r.URL.Query().Get("route_id")
	if routeID == "" {
		writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
			Code: http.StatusBadRequest,
			Msg:  "Route ID is required",
		})
		return
	}

	var req models.CreateRouteRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
			Code: http.StatusBadRequest,
			Msg:  "Invalid request body",
		})
		return
	}

	route, err := s.routerManager.UpdateRoute(routeID, &req)
	if err != nil {
		writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
			Code: http.StatusBadRequest,
			Msg:  err.Error(),
		})
		return
	}

	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Data: route,
		Msg:  "Route updated successfully",
	})
}

func (s *APIServer) DeleteRoute(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	routeID := r.URL.Query().Get("route_id")
	if routeID == "" {
		writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
			Code: http.StatusBadRequest,
			Msg:  "Route ID is required",
		})
		return
	}

	if err := s.routerManager.DeleteRoute(routeID); err != nil {
		writeJSONResponse(w, http.StatusNotFound, models.APIResponse{
			Code: http.StatusNotFound,
			Msg:  err.Error(),
		})
		return
	}

	s.rateLimiter.ResetRateLimit(routeID)

	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Msg:  "Route deleted successfully",
	})
}

func (s *APIServer) QueryStats(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	routeID := r.URL.Query().Get("route_id")
	if routeID == "" {
		stats := s.metricsCollector.GetAllStats()
		writeJSONResponse(w, http.StatusOK, models.APIResponse{
			Code: http.StatusOK,
			Data: map[string]interface{}{"stats": stats},
		})
		return
	}

	startTimeStr := r.URL.Query().Get("start_time")
	endTimeStr := r.URL.Query().Get("end_time")

	var startTime, endTime time.Time
	var err error

	if startTimeStr != "" {
		startTime, err = time.Parse(time.RFC3339, startTimeStr)
		if err != nil {
			writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
				Code: http.StatusBadRequest,
				Msg:  "Invalid start_time format",
			})
			return
		}
	}

	if endTimeStr != "" {
		endTime, err = time.Parse(time.RFC3339, endTimeStr)
		if err != nil {
			writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
				Code: http.StatusBadRequest,
				Msg:  "Invalid end_time format",
			})
			return
		}
	}

	if startTimeStr == "" && endTimeStr == "" {
		stats := s.metricsCollector.GetRouteStats(routeID)
		writeJSONResponse(w, http.StatusOK, models.APIResponse{
			Code: http.StatusOK,
			Data: map[string]interface{}{"stats": stats},
		})
		return
	}

	if startTimeStr == "" {
		startTime = time.Now().Add(-24 * time.Hour)
	}
	if endTimeStr == "" {
		endTime = time.Now()
	}

	stats := s.metricsCollector.QueryStats(routeID, startTime, endTime)
	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Data: map[string]interface{}{"stats": stats},
	})
}

func (s *APIServer) GetStatsSummary(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	summary := s.metricsCollector.GetSummary()
	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Data: summary,
	})
}

func (s *APIServer) GetTopRoutes(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	limitStr := r.URL.Query().Get("limit")
	sortBy := r.URL.Query().Get("sort_by")

	limit := 10
	if limitStr != "" {
		if l, err := strconv.Atoi(limitStr); err == nil {
			limit = l
		}
	}

	if sortBy == "" {
		sortBy = "request_count"
	}

	stats := s.metricsCollector.GetTopRoutes(limit, sortBy)
	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Data: map[string]interface{}{"routes": stats},
	})
}

func (s *APIServer) GetCircuitStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	serviceName := r.URL.Query().Get("service_name")
	if serviceName == "" {
		writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
			Code: http.StatusBadRequest,
			Msg:  "Service name is required",
		})
		return
	}

	state, exists := s.circuitBreaker.GetState(serviceName)
	if !exists {
		writeJSONResponse(w, http.StatusNotFound, models.APIResponse{
			Code: http.StatusNotFound,
			Msg:  "Circuit breaker not found",
		})
		return
	}

	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Data: map[string]interface{}{
			"status":         state.Status,
			"failure_count":  state.FailureCount,
			"success_count":  state.SuccessCount,
			"total_requests": state.TotalRequests,
			"opened_at":      state.OpenedAt,
			"half_open_count": state.HalfOpenCount,
		},
	})
}

func (s *APIServer) ListCircuits(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	states := s.circuitBreaker.GetAllStates()
	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Data: map[string]interface{}{"circuits": states},
	})
}

func (s *APIServer) ResetCircuit(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	serviceName := r.URL.Query().Get("service_name")
	if serviceName == "" {
		writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
			Code: http.StatusBadRequest,
			Msg:  "Service name is required",
		})
		return
	}

	s.circuitBreaker.Reset(serviceName)

	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Msg:  "Circuit breaker reset successfully",
	})
}

func (s *APIServer) QueryLogs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	routeID := r.URL.Query().Get("route_id")
	requestID := r.URL.Query().Get("request_id")
	limitStr := r.URL.Query().Get("limit")
	startTimeStr := r.URL.Query().Get("start_time")
	endTimeStr := r.URL.Query().Get("end_time")

	limit := 100
	if limitStr != "" {
		if l, err := strconv.Atoi(limitStr); err == nil {
			limit = l
		}
	}

	var startTime, endTime time.Time
	var err error

	if startTimeStr != "" {
		startTime, err = time.Parse(time.RFC3339, startTimeStr)
		if err != nil {
			writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
				Code: http.StatusBadRequest,
				Msg:  "Invalid start_time format",
			})
			return
		}
	}

	if endTimeStr != "" {
		endTime, err = time.Parse(time.RFC3339, endTimeStr)
		if err != nil {
			writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
				Code: http.StatusBadRequest,
				Msg:  "Invalid end_time format",
			})
			return
		}
	}

	var logs []*models.RequestLog

	if requestID != "" {
		logs = s.logger.GetLogsByRequestID(requestID)
	} else {
		logs = s.logger.QueryLogs(routeID, startTime, endTime, limit)
	}

	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Data: map[string]interface{}{"logs": logs, "count": len(logs)},
	})
}

func (s *APIServer) GetLogStats(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	stats := s.logger.GetStats()
	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Data: stats,
	})
}

func (s *APIServer) ListServices(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	services := s.loadBalancer.ListServices()
	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Data: map[string]interface{}{"services": services},
	})
}

func (s *APIServer) GetServiceInstances(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	serviceName := r.URL.Query().Get("service_name")
	if serviceName == "" {
		writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
			Code: http.StatusBadRequest,
			Msg:  "Service name is required",
		})
		return
	}

	config, exists := s.loadBalancer.GetServiceConfig(serviceName)
	if !exists {
		writeJSONResponse(w, http.StatusNotFound, models.APIResponse{
			Code: http.StatusNotFound,
			Msg:  "Service not found",
		})
		return
	}

	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Data: config,
	})
}

func (s *APIServer) ListAPIKeys(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	keys := s.authManager.ListAPIKeys()
	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Data: map[string]interface{}{"api_keys": keys},
	})
}

func (s *APIServer) CreateAPIKey(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	var req struct {
		UserID string   `json:"user_id"`
		Roles  []string `json:"roles"`
	}

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
			Code: http.StatusBadRequest,
			Msg:  "Invalid request body",
		})
		return
	}

	key := &auth.APIKey{
		Key:     auth.GenerateAPIKey(),
		Secret:  auth.GenerateAPISecret(),
		UserID:  req.UserID,
		Roles:   req.Roles,
		Enabled: true,
	}

	if err := s.authManager.AddAPIKey(key); err != nil {
		writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
			Code: http.StatusBadRequest,
			Msg:  err.Error(),
		})
		return
	}

	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Data: map[string]string{
			"key":    key.Key,
			"secret": key.Secret,
		},
		Msg: "API key created successfully",
	})
}

func (s *APIServer) DeleteAPIKey(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		writeJSONResponse(w, http.StatusMethodNotAllowed, models.APIResponse{
			Code: http.StatusMethodNotAllowed,
			Msg:  "Method not allowed",
		})
		return
	}

	key := r.URL.Query().Get("key")
	if key == "" {
		writeJSONResponse(w, http.StatusBadRequest, models.APIResponse{
			Code: http.StatusBadRequest,
			Msg:  "API key is required",
		})
		return
	}

	s.authManager.RemoveAPIKey(key)

	writeJSONResponse(w, http.StatusOK, models.APIResponse{
		Code: http.StatusOK,
		Msg:  fmt.Sprintf("API key %s deleted successfully", key),
	})
}
