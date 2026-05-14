package api

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"netproxy/internal/config"
	"netproxy/internal/forward"
	"netproxy/internal/health"
	"netproxy/internal/logger"
	"netproxy/internal/pool"
	"netproxy/internal/protocol"
	"netproxy/internal/stats"
)

type APIResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message,omitempty"`
	Data    interface{} `json:"data,omitempty"`
}

type ForwardAPIRequest struct {
	Protocol    string `json:"protocol"`
	TargetHost  string `json:"target_host"`
	TargetPort  int    `json:"target_port"`
	RequestData string `json:"request_data"`
}

type ForwardAPIResponse struct {
	ResponseData string `json:"response_data"`
	Latency      int64  `json:"latency_ms"`
}

type StatsResponse struct {
	TargetHost    string `json:"target_host"`
	RequestCount  int64  `json:"request_count"`
	TrafficIn     int64  `json:"traffic_in"`
	TrafficOut    int64  `json:"traffic_out"`
	AvgLatency    int64  `json:"avg_latency"`
	MinLatency    int64  `json:"min_latency"`
	MaxLatency    int64  `json:"max_latency"`
	ErrorCount    int64  `json:"error_count"`
	LastUpdated   string `json:"last_updated"`
}

type APIServer struct {
	configMgr    *config.ConfigManager
	forwardEng   *forward.ForwardEngine
	poolMgr      *pool.PoolManager
	healthChecker *health.HealthChecker
	server       *http.Server
	mu           sync.Mutex
	running      bool
}

func NewAPIServer(configMgr *config.ConfigManager, forwardEng *forward.ForwardEngine, poolMgr *pool.PoolManager, healthChecker *health.HealthChecker) *APIServer {
	return &APIServer{
		configMgr:     configMgr,
		forwardEng:    forwardEng,
		poolMgr:       poolMgr,
		healthChecker: healthChecker,
	}
}

func (s *APIServer) Start(address string) error {
	s.mu.Lock()
	if s.running {
		s.mu.Unlock()
		return fmt.Errorf("API server is already running")
	}
	s.running = true
	s.mu.Unlock()

	mux := http.NewServeMux()

	mux.HandleFunc("/api/v1/proxy/forward", s.handleForward)
	mux.HandleFunc("/api/v1/proxy/rules", s.handleRules)
	mux.HandleFunc("/api/v1/proxy/rules/", s.handleRuleByID)
	mux.HandleFunc("/api/v1/proxy/stats", s.handleStats)
	mux.HandleFunc("/api/v1/proxy/stats/reset", s.handleStatsReset)
	mux.HandleFunc("/api/v1/proxy/pools", s.handlePoolStats)
	mux.HandleFunc("/api/v1/proxy/health", s.handleHealth)
	mux.HandleFunc("/api/v1/proxy/health/check", s.handleHealthCheck)
	mux.HandleFunc("/api/v1/proxy/status", s.handleStatus)

	s.server = &http.Server{
		Addr:         address,
		Handler:      mux,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
	}

	logger.Info("API server starting on %s", address)

	go func() {
		if err := s.server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Error("API server error: %v", err)
		}
	}()

	return nil
}

func (s *APIServer) Stop() error {
	s.mu.Lock()
	if !s.running {
		s.mu.Unlock()
		return nil
	}
	s.running = false
	s.mu.Unlock()

	if s.server != nil {
		logger.Info("Stopping API server")
		return s.server.Close()
	}

	return nil
}

func (s *APIServer) writeResponse(w http.ResponseWriter, code int, message string, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)

	resp := APIResponse{
		Code:    code,
		Message: message,
		Data:    data,
	}

	json.NewEncoder(w).Encode(resp)
}

func (s *APIServer) handleForward(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.writeResponse(w, http.StatusMethodNotAllowed, "Method not allowed", nil)
		return
	}

	var req ForwardAPIRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.writeResponse(w, http.StatusBadRequest, "Invalid request body", nil)
		return
	}

	if req.TargetHost == "" {
		s.writeResponse(w, http.StatusBadRequest, "target_host is required", nil)
		return
	}

	if req.TargetPort <= 0 || req.TargetPort > 65535 {
		s.writeResponse(w, http.StatusBadRequest, "Invalid target_port", nil)
		return
	}

	var proto protocol.ProtocolType
	switch strings.ToLower(req.Protocol) {
	case "http":
		proto = protocol.ProtocolHTTP
	case "https":
		proto = protocol.ProtocolHTTPS
	case "socks5":
		proto = protocol.ProtocolSOCKS5
	default:
		s.writeResponse(w, http.StatusBadRequest, "Invalid protocol", nil)
		return
	}

	var requestData []byte
	if req.RequestData != "" {
		var err error
		requestData, err = base64.StdEncoding.DecodeString(req.RequestData)
		if err != nil {
			s.writeResponse(w, http.StatusBadRequest, "Invalid request_data encoding", nil)
			return
		}
	}

	proxyReq := &protocol.ProxyRequest{
		Protocol:   proto,
		TargetHost: req.TargetHost,
		TargetPort: req.TargetPort,
	}

	if err := s.forwardEng.ValidateRequest(proxyReq); err != nil {
		s.writeResponse(w, http.StatusForbidden, err.Error(), nil)
		return
	}

	if len(requestData) == 0 {
		s.writeResponse(w, http.StatusBadRequest, "request_data is required for this protocol", nil)
		return
	}

	result, err := s.forwardEng.ForwardRequest(proxyReq, requestData)
	if err != nil {
		s.writeResponse(w, http.StatusInternalServerError, err.Error(), nil)
		return
	}

	respData := ForwardAPIResponse{
		ResponseData: base64.StdEncoding.EncodeToString(result.ResponseData),
		Latency:      result.Latency,
	}

	s.writeResponse(w, http.StatusOK, "Success", respData)
}

func (s *APIServer) handleRules(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.getRules(w, r)
	case http.MethodPost:
		s.createRule(w, r)
	default:
		s.writeResponse(w, http.StatusMethodNotAllowed, "Method not allowed", nil)
	}
}

func (s *APIServer) getRules(w http.ResponseWriter, r *http.Request) {
	rules := s.configMgr.GetAllRules()
	s.writeResponse(w, http.StatusOK, "Success", map[string]interface{}{
		"rules": rules,
		"count": len(rules),
	})
}

func (s *APIServer) createRule(w http.ResponseWriter, r *http.Request) {
	var rule config.ForwardRule
	if err := json.NewDecoder(r.Body).Decode(&rule); err != nil {
		s.writeResponse(w, http.StatusBadRequest, "Invalid request body", nil)
		return
	}

	if rule.RuleID == "" {
		s.writeResponse(w, http.StatusBadRequest, "rule_id is required", nil)
		return
	}

	if rule.TargetPattern == "" {
		s.writeResponse(w, http.StatusBadRequest, "target_pattern is required", nil)
		return
	}

	if err := s.configMgr.AddRule(&rule); err != nil {
		s.writeResponse(w, http.StatusConflict, err.Error(), nil)
		return
	}

	s.writeResponse(w, http.StatusCreated, "Rule created", map[string]string{
		"rule_id": rule.RuleID,
	})
}

func (s *APIServer) handleRuleByID(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/v1/proxy/rules/")
	ruleID := strings.TrimSpace(path)

	if ruleID == "" {
		s.writeResponse(w, http.StatusBadRequest, "rule_id is required", nil)
		return
	}

	switch r.Method {
	case http.MethodGet:
		s.getRule(w, r, ruleID)
	case http.MethodPut:
		s.updateRule(w, r, ruleID)
	case http.MethodDelete:
		s.deleteRule(w, r, ruleID)
	default:
		s.writeResponse(w, http.StatusMethodNotAllowed, "Method not allowed", nil)
	}
}

func (s *APIServer) getRule(w http.ResponseWriter, r *http.Request, ruleID string) {
	rule, exists := s.configMgr.GetRule(ruleID)
	if !exists {
		s.writeResponse(w, http.StatusNotFound, "Rule not found", nil)
		return
	}
	s.writeResponse(w, http.StatusOK, "Success", rule)
}

func (s *APIServer) updateRule(w http.ResponseWriter, r *http.Request, ruleID string) {
	var rule config.ForwardRule
	if err := json.NewDecoder(r.Body).Decode(&rule); err != nil {
		s.writeResponse(w, http.StatusBadRequest, "Invalid request body", nil)
		return
	}

	if err := s.configMgr.UpdateRule(ruleID, &rule); err != nil {
		s.writeResponse(w, http.StatusNotFound, err.Error(), nil)
		return
	}

	s.writeResponse(w, http.StatusOK, "Rule updated", nil)
}

func (s *APIServer) deleteRule(w http.ResponseWriter, r *http.Request, ruleID string) {
	if err := s.configMgr.DeleteRule(ruleID); err != nil {
		s.writeResponse(w, http.StatusNotFound, err.Error(), nil)
		return
	}

	s.writeResponse(w, http.StatusOK, "Rule deleted", nil)
}

func (s *APIServer) handleStats(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		s.writeResponse(w, http.StatusMethodNotAllowed, "Method not allowed", nil)
		return
	}

	targetHost := r.URL.Query().Get("target_host")
	if targetHost != "" {
		hostStats := stats.GetHostStats(targetHost)
		if hostStats == nil {
			s.writeResponse(w, http.StatusOK, "No stats found for host", map[string]interface{}{
				"stats": []StatsResponse{},
			})
			return
		}

		resp := StatsResponse{
			TargetHost:   targetHost,
			RequestCount: hostStats.RequestCount,
			TrafficIn:    hostStats.TrafficIn,
			TrafficOut:   hostStats.TrafficOut,
			AvgLatency:   hostStats.GetAverageLatency(),
			MinLatency:   hostStats.MinLatency,
			MaxLatency:   hostStats.MaxLatency,
			ErrorCount:   hostStats.ErrorCount,
			LastUpdated:  hostStats.LastUpdated.Format(time.RFC3339),
		}

		s.writeResponse(w, http.StatusOK, "Success", map[string]interface{}{
			"stats": []StatsResponse{resp},
		})
		return
	}

	allStats := stats.GetAllStats()
	statsList := make([]StatsResponse, 0, len(allStats))

	for host, stat := range allStats {
		statsList = append(statsList, StatsResponse{
			TargetHost:   host,
			RequestCount: stat.RequestCount,
			TrafficIn:    stat.TrafficIn,
			TrafficOut:   stat.TrafficOut,
			AvgLatency:   stat.GetAverageLatency(),
			MinLatency:   stat.MinLatency,
			MaxLatency:   stat.MaxLatency,
			ErrorCount:   stat.ErrorCount,
			LastUpdated:  stat.LastUpdated.Format(time.RFC3339),
		})
	}

	aggregated := stats.GetAggregatedStats()
	aggregatedResp := StatsResponse{
		TargetHost:   "_total",
		RequestCount: aggregated.RequestCount,
		TrafficIn:    aggregated.TrafficIn,
		TrafficOut:   aggregated.TrafficOut,
		AvgLatency:   aggregated.GetAverageLatency(),
		MinLatency:   aggregated.MinLatency,
		MaxLatency:   aggregated.MaxLatency,
		ErrorCount:   aggregated.ErrorCount,
		LastUpdated:  aggregated.LastUpdated.Format(time.RFC3339),
	}

	s.writeResponse(w, http.StatusOK, "Success", map[string]interface{}{
		"stats":      statsList,
		"aggregated": aggregatedResp,
	})
}

func (s *APIServer) handleStatsReset(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.writeResponse(w, http.StatusMethodNotAllowed, "Method not allowed", nil)
		return
	}

	targetHost := r.URL.Query().Get("target_host")
	if targetHost != "" {
		stats.ResetHostStats(targetHost)
		s.writeResponse(w, http.StatusOK, "Stats reset for host", map[string]string{
			"target_host": targetHost,
		})
		return
	}

	stats.ResetAllStats()
	s.writeResponse(w, http.StatusOK, "All stats reset", nil)
}

func (s *APIServer) handlePoolStats(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		s.writeResponse(w, http.StatusMethodNotAllowed, "Method not allowed", nil)
		return
	}

	targetHost := r.URL.Query().Get("target_host")
	portStr := r.URL.Query().Get("target_port")

	if targetHost != "" && portStr != "" {
		var port int
		fmt.Sscanf(portStr, "%d", &port)
		if port <= 0 || port > 65535 {
			s.writeResponse(w, http.StatusBadRequest, "Invalid target_port", nil)
			return
		}

		poolStat := s.poolMgr.GetPoolStats(targetHost, port)
		if poolStat == nil {
			s.writeResponse(w, http.StatusOK, "No pool stats found", map[string]interface{}{
				"pools": []pool.PoolStats{},
			})
			return
		}

		s.writeResponse(w, http.StatusOK, "Success", map[string]interface{}{
			"pools": []pool.PoolStats{*poolStat},
		})
		return
	}

	pools := s.poolMgr.GetAllPoolStats()
	s.writeResponse(w, http.StatusOK, "Success", map[string]interface{}{
		"pools": pools,
		"count": len(pools),
	})
}

func (s *APIServer) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		s.writeResponse(w, http.StatusMethodNotAllowed, "Method not allowed", nil)
		return
	}

	targetHost := r.URL.Query().Get("target_host")
	portStr := r.URL.Query().Get("target_port")

	if targetHost != "" && portStr != "" {
		var port int
		fmt.Sscanf(portStr, "%d", &port)
		if port <= 0 || port > 65535 {
			s.writeResponse(w, http.StatusBadRequest, "Invalid target_port", nil)
			return
		}

		healthStatus := health.GetTargetHealth(targetHost, port)
		if healthStatus == nil {
			s.writeResponse(w, http.StatusOK, "No health data found", map[string]interface{}{
				"health": []health.TargetHealth{},
			})
			return
		}

		s.writeResponse(w, http.StatusOK, "Success", map[string]interface{}{
			"health": []health.TargetHealth{*healthStatus},
		})
		return
	}

	allHealth := health.GetAllHealth()
	s.writeResponse(w, http.StatusOK, "Success", map[string]interface{}{
		"health": allHealth,
		"count":  len(allHealth),
	})
}

func (s *APIServer) handleHealthCheck(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.writeResponse(w, http.StatusMethodNotAllowed, "Method not allowed", nil)
		return
	}

	targetHost := r.URL.Query().Get("target_host")
	portStr := r.URL.Query().Get("target_port")

	if targetHost != "" && portStr != "" {
		var port int
		fmt.Sscanf(portStr, "%d", &port)
		if port <= 0 || port > 65535 {
			s.writeResponse(w, http.StatusBadRequest, "Invalid target_port", nil)
			return
		}

		health.RegisterTarget(targetHost, port)
		err := health.CheckTarget(targetHost, port)
		if err != nil {
			s.writeResponse(w, http.StatusServiceUnavailable, "Health check failed", map[string]string{
				"error": err.Error(),
			})
			return
		}

		healthStatus := health.GetTargetHealth(targetHost, port)
		s.writeResponse(w, http.StatusOK, "Health check passed", healthStatus)
		return
	}

	results := make(map[string]interface{})
	allHealth := health.GetAllHealth()
	for _, h := range allHealth {
		err := health.CheckTarget(h.Host, h.Port)
		if err != nil {
			results[fmt.Sprintf("%s:%d", h.Host, h.Port)] = map[string]interface{}{
				"status": "failed",
				"error":  err.Error(),
			}
		} else {
			results[fmt.Sprintf("%s:%d", h.Host, h.Port)] = map[string]interface{}{
				"status": "passed",
			}
		}
	}

	s.writeResponse(w, http.StatusOK, "Health check completed", results)
}

func (s *APIServer) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		s.writeResponse(w, http.StatusMethodNotAllowed, "Method not allowed", nil)
		return
	}

	cfg := s.configMgr.GetConfig()
	aggregated := stats.GetAggregatedStats()
	pools := s.poolMgr.GetAllPoolStats()
	allHealth := health.GetAllHealth()

	var totalActiveConns, totalIdleConns int
	for _, p := range pools {
		totalActiveConns += p.ActiveConnections
		totalIdleConns += p.IdleConnections
	}

	healthyCount := 0
	for _, h := range allHealth {
		if h.Status == health.StatusHealthy || h.Status == health.StatusUnknown {
			healthyCount++
		}
	}

	status := map[string]interface{}{
		"server": map[string]interface{}{
			"http_address":    cfg.Server.HTTPAddress,
			"https_address":   cfg.Server.HTTPSAddress,
			"socks5_address":  cfg.Server.SOCKS5Address,
			"api_address":     cfg.Server.APIAddress,
			"max_connections": cfg.Server.MaxConnections,
		},
		"stats": map[string]interface{}{
			"total_requests":   aggregated.RequestCount,
			"total_traffic_in": aggregated.TrafficIn,
			"total_traffic_out": aggregated.TrafficOut,
			"avg_latency":      aggregated.GetAverageLatency(),
			"error_count":      aggregated.ErrorCount,
		},
		"pools": map[string]interface{}{
			"total_pools":       len(pools),
			"active_connections": totalActiveConns,
			"idle_connections":   totalIdleConns,
		},
		"health": map[string]interface{}{
			"total_targets": len(allHealth),
			"healthy":       healthyCount,
			"unhealthy":     len(allHealth) - healthyCount,
		},
		"rules": map[string]interface{}{
			"total_rules": len(s.configMgr.GetAllRules()),
		},
	}

	s.writeResponse(w, http.StatusOK, "Success", status)
}

func (s *APIServer) StartProxyServer(address string, proto protocol.ProtocolType) error {
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return fmt.Errorf("failed to listen on %s: %w", address, err)
	}

	logger.Info("Proxy server starting on %s (%s)", address, proto)

	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				logger.Error("Failed to accept connection: %v", err)
				continue
			}

			go s.forwardEng.HandleConnection(conn)
		}
	}()

	return nil
}
