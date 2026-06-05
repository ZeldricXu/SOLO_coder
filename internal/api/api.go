package api

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/datateam/loganalyzer/internal/aggregator"
	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/correlator"
	"github.com/datateam/loganalyzer/internal/models"
	"github.com/datateam/loganalyzer/internal/storage"
)

type Server struct {
	cfg          config.APIConfig
	clickhouse   *storage.ClickHouseClient
	redis        *storage.RedisClient
	aggregator   *aggregator.Aggregator
	correlator   *correlator.Correlator
	router       *gin.Engine
	server       *http.Server
}

type NLQuery struct {
	Original    string
	ServiceName string
	Keywords    string
	StartTime   time.Time
	EndTime     time.Time
	Level       models.LogLevel
	TraceID     string
	ErrorCode   string
	TimeRange   string
}

func NewServer(cfg config.APIConfig, clickhouse *storage.ClickHouseClient, redis *storage.RedisClient, agg *aggregator.Aggregator, corr *correlator.Correlator) *Server {
	if cfg.BasePath == "" {
		cfg.BasePath = "/api"
	}

	gin.SetMode(gin.ReleaseMode)
	router := gin.New()
	router.Use(gin.Recovery())

	if cfg.CORS.Enabled {
		router.Use(corsMiddleware(cfg.CORS))
	}

	s := &Server{
		cfg:        cfg,
		clickhouse: clickhouse,
		redis:      redis,
		aggregator: agg,
		correlator: corr,
		router:     router,
	}

	s.setupRoutes()
	return s
}

func corsMiddleware(cfg config.CORSConfig) gin.HandlerFunc {
	return func(c *gin.Context) {
		origin := c.Request.Header.Get("Origin")
		if cfg.AllowedOrigins[0] == "*" {
			c.Header("Access-Control-Allow-Origin", "*")
		} else {
			for _, allowed := range cfg.AllowedOrigins {
				if origin == allowed {
					c.Header("Access-Control-Allow-Origin", origin)
					break
				}
			}
		}

		c.Header("Access-Control-Allow-Methods", strings.Join(cfg.AllowedMethods, ","))
		c.Header("Access-Control-Allow-Headers", strings.Join(cfg.AllowedHeaders, ","))
		if cfg.AllowCredentials {
			c.Header("Access-Control-Allow-Credentials", "true")
		}

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}

		c.Next()
	}
}

func (s *Server) setupRoutes() {
	base := s.router.Group(s.cfg.BasePath)

	base.GET("/health", s.handleHealth)

	v1 := base.Group("/v1")
	{
		logs := v1.Group("/logs")
		{
			logs.GET("", s.handleQueryLogs)
			logs.GET("/search", s.handleNLSearch)
			logs.GET("/:id", s.handleGetLog)
			logs.GET("/trace/:trace_id", s.handleGetTrace)
		}

		incidents := v1.Group("/incidents")
		{
			incidents.GET("", s.handleListIncidents)
			incidents.POST("/:id/acknowledge", s.handleAcknowledgeIncident)
			incidents.POST("/:id/resolve", s.handleResolveIncident)
			incidents.GET("/:id", s.handleGetIncident)
		}

		stats := v1.Group("/stats")
		{
			stats.GET("/overview", s.handleGetOverview)
			stats.GET("/services", s.handleGetServiceStats)
			stats.GET("/errors", s.handleGetErrorStats)
		}

		traces := v1.Group("/traces")
		{
			traces.GET("", s.handleSearchTraces)
			traces.GET("/:trace_id", s.handleGetTrace)
		}
	}
}

func (s *Server) Start() error {
	if !s.cfg.Enabled {
		log.Printf("API server disabled")
		return nil
	}

	addr := fmt.Sprintf(":%d", s.cfg.HTTPPort)
	s.server = &http.Server{
		Addr:         addr,
		Handler:      s.router,
		ReadTimeout:  time.Duration(s.cfg.ReadTimeout) * time.Second,
		WriteTimeout: time.Duration(s.cfg.WriteTimeout) * time.Second,
	}

	log.Printf("API server starting on %s", addr)
	go func() {
		if err := s.server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Printf("API server error: %v", err)
		}
	}()

	return nil
}

func (s *Server) Stop(ctx context.Context) error {
	if s.server == nil {
		return nil
	}
	return s.server.Shutdown(ctx)
}

func (s *Server) handleHealth(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status": "ok",
		"time":   time.Now().UTC(),
	})
}

func (s *Server) handleQueryLogs(c *gin.Context) {
	req, err := s.parseQueryRequest(c)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := s.clickhouse.Query(c.Request.Context(), req)
	if err != nil {
		log.Printf("Query error: %v", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	c.JSON(http.StatusOK, resp)
}

func (s *Server) handleNLSearch(c *gin.Context) {
	query := c.Query("q")
	if query == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "query parameter 'q' required"})
		return
	}

	nlq := s.parseNLQuery(query)
	req := &models.LogQueryRequest{
		StartTime:   nlq.StartTime,
		EndTime:     nlq.EndTime,
		ServiceName: nlq.ServiceName,
		Keywords:    nlq.Keywords,
		Level:       nlq.Level,
		TraceID:     nlq.TraceID,
		ErrorCode:   nlq.ErrorCode,
		Page:        1,
		PageSize:    50,
	}

	resp, err := s.clickhouse.Query(c.Request.Context(), req)
	if err != nil {
		log.Printf("NL search error: %v", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "search failed"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"query":        nlq,
		"parsed_query": req,
		"results":      resp,
	})
}

func (s *Server) parseQueryRequest(c *gin.Context) (*models.LogQueryRequest, error) {
	req := &models.LogQueryRequest{
		Page:     1,
		PageSize: 50,
	}

	startStr := c.Query("start")
	endStr := c.Query("end")

	now := time.Now()
	if startStr == "" {
		req.StartTime = now.Add(-1 * time.Hour)
	} else {
		if ts, err := strconv.ParseInt(startStr, 10, 64); err == nil {
			req.StartTime = time.Unix(ts, 0)
		} else if t, err := time.Parse(time.RFC3339, startStr); err == nil {
			req.StartTime = t
		} else {
			return nil, fmt.Errorf("invalid start time format")
		}
	}

	if endStr == "" {
		req.EndTime = now
	} else {
		if ts, err := strconv.ParseInt(endStr, 10, 64); err == nil {
			req.EndTime = time.Unix(ts, 0)
		} else if t, err := time.Parse(time.RFC3339, endStr); err == nil {
			req.EndTime = t
		} else {
			return nil, fmt.Errorf("invalid end time format")
		}
	}

	req.ServiceName = c.Query("service")
	req.Keywords = c.Query("keywords")
	req.Level = models.LogLevel(c.Query("level"))
	req.TraceID = c.Query("trace_id")
	req.ErrorCode = c.Query("error_code")

	if pageStr := c.Query("page"); pageStr != "" {
		if page, err := strconv.Atoi(pageStr); err == nil && page > 0 {
			req.Page = page
		}
	}

	if sizeStr := c.Query("page_size"); sizeStr != "" {
		if size, err := strconv.Atoi(sizeStr); err == nil && size > 0 && size <= 1000 {
			req.PageSize = size
		}
	}

	return req, nil
}

func (s *Server) parseNLQuery(query string) *NLQuery {
	nlq := &NLQuery{
		Original: query,
		EndTime:  time.Now(),
	}

	now := time.Now()

	timeRangePatterns := map[string]time.Duration{
		"last 5 minutes":   5 * time.Minute,
		"last 10 minutes":  10 * time.Minute,
		"last 15 minutes":  15 * time.Minute,
		"last 30 minutes":  30 * time.Minute,
		"last hour":       1 * time.Hour,
		"last 2 hours":    2 * time.Hour,
		"last 6 hours":    6 * time.Hour,
		"last 12 hours":   12 * time.Hour,
		"last 24 hours":   24 * time.Hour,
		"today":           time.Duration(now.Hour()) * time.Hour,
		"yesterday":       24 * time.Hour,
	}

	lower := strings.ToLower(query)

	for pattern, duration := range timeRangePatterns {
		if strings.Contains(lower, pattern) {
			nlq.TimeRange = pattern
			nlq.StartTime = now.Add(-duration)
			query = strings.ReplaceAll(query, pattern, "")
			query = strings.ReplaceAll(query, strings.Title(pattern), "")
			break
		}
	}

	if nlq.StartTime.IsZero() {
		if match := regexp.MustCompile(`(?i)last\s+(\d+)\s+(minute|minutes|hour|hours|day|days)`).FindStringSubmatch(lower); match != nil {
			num, _ := strconv.Atoi(match[1])
			unit := match[2]
			var duration time.Duration
			switch unit {
			case "minute", "minutes":
				duration = time.Duration(num) * time.Minute
			case "hour", "hours":
				duration = time.Duration(num) * time.Hour
			case "day", "days":
				duration = time.Duration(num) * 24 * time.Hour
			}
			nlq.StartTime = now.Add(-duration)
			nlq.TimeRange = match[0]
		} else {
			nlq.StartTime = now.Add(-1 * time.Hour)
			nlq.TimeRange = "last 1 hour"
		}
	}

	if match := regexp.MustCompile(`(?i)service[:=\s]+["']?([a-zA-Z0-9_-]+)["']?`).FindStringSubmatch(query); match != nil {
		nlq.ServiceName = match[1]
		query = strings.Replace(query, match[0], "", 1)
	}

	if match := regexp.MustCompile(`(?i)level[:=\s]+["']?(\w+)["']?`).FindStringSubmatch(query); match != nil {
		nlq.Level = models.ParseLogLevel(match[1])
		query = strings.Replace(query, match[0], "", 1)
	}

	if match := regexp.MustCompile(`(?i)trace_id?[:=\s]+["']?([a-zA-Z0-9-]+)["']?`).FindStringSubmatch(query); match != nil {
		nlq.TraceID = match[1]
		query = strings.Replace(query, match[0], "", 1)
	}

	if match := regexp.MustCompile(`(?i)error_code?[:=\s]+["']?([a-zA-Z0-9_-]+)["']?`).FindStringSubmatch(query); match != nil {
		nlq.ErrorCode = match[1]
		query = strings.Replace(query, match[0], "", 1)
	}

	keywords := strings.TrimSpace(query)
	keywords = strings.Trim(keywords, " ,.;:!?'\"")
	if keywords != "" && keywords != "in" && keywords != "for" {
		nlq.Keywords = keywords
	}

	return nlq
}

func (s *Server) handleGetLog(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"message": "not implemented yet"})
}

func (s *Server) handleGetTrace(c *gin.Context) {
	traceID := c.Param("trace_id")
	if traceID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "trace_id required"})
		return
	}

	chain, err := s.correlator.GetEventChain(c.Request.Context(), traceID)
	if err != nil {
		log.Printf("Get trace error: %v", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to get trace"})
		return
	}

	if chain == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "trace not found"})
		return
	}

	c.JSON(http.StatusOK, chain)
}

func (s *Server) handleListIncidents(c *gin.Context) {
	incidents := s.aggregator.GetActiveIncidents()

	c.JSON(http.StatusOK, gin.H{
		"total":     len(incidents),
		"incidents": incidents,
	})
}

func (s *Server) handleAcknowledgeIncident(c *gin.Context) {
	incidentID := c.Param("id")
	user := c.Query("user")
	if user == "" {
		user = "unknown"
	}

	if err := s.aggregator.AcknowledgeIncident(c.Request.Context(), incidentID, user); err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "acknowledged"})
}

func (s *Server) handleResolveIncident(c *gin.Context) {
	incidentID := c.Param("id")

	if err := s.aggregator.ResolveIncident(c.Request.Context(), incidentID); err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "resolved"})
}

func (s *Server) handleGetIncident(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"message": "not implemented yet"})
}

func (s *Server) handleGetOverview(c *gin.Context) {
	now := time.Now()
	startTime := now.Add(-1 * time.Hour)

	req := &models.LogQueryRequest{
		StartTime: startTime,
		EndTime:   now,
		Page:      1,
		PageSize:  1,
	}

	resp, err := s.clickhouse.Query(c.Request.Context(), req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to get stats"})
		return
	}

	errorCount := int64(0)
	for _, pt := range resp.TimeSeries {
		errorCount += pt.ErrorCount
	}

	incidents := s.aggregator.GetActiveIncidents()
	criticalCount := 0
	highCount := 0
	for _, inc := range incidents {
		switch inc.Severity {
		case models.SeverityCritical:
			criticalCount++
		case models.SeverityHigh:
			highCount++
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"total_logs":      resp.Total,
		"error_count":     errorCount,
		"error_rate":      float64(errorCount) / float64(resp.Total) * 100,
		"active_incidents": gin.H{
			"total":    len(incidents),
			"critical": criticalCount,
			"high":     highCount,
		},
		"services_count": len(resp.Distribution),
		"time_series":    resp.TimeSeries,
	})
}

func (s *Server) handleGetServiceStats(c *gin.Context) {
	now := time.Now()
	startTime := now.Add(-1 * time.Hour)

	req := &models.LogQueryRequest{
		StartTime: startTime,
		EndTime:   now,
		Page:      1,
		PageSize:  1,
	}

	resp, err := s.clickhouse.Query(c.Request.Context(), req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to get service stats"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"distribution": resp.Distribution,
	})
}

func (s *Server) handleGetErrorStats(c *gin.Context) {
	now := time.Now()
	startTime := now.Add(-1 * time.Hour)

	req := &models.LogQueryRequest{
		StartTime: startTime,
		EndTime:   now,
		Level:     models.LevelError,
		Page:      1,
		PageSize:  100,
	}

	resp, err := s.clickhouse.Query(c.Request.Context(), req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to get error stats"})
		return
	}

	errorCodes := make(map[string]int64)
	for _, log := range resp.Logs {
		if log.ErrorCode != "" {
			errorCodes[log.ErrorCode]++
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"total_errors": resp.Total,
		"error_codes":  errorCodes,
		"logs":         resp.Logs,
	})
}

func (s *Server) handleSearchTraces(c *gin.Context) {
	serviceName := c.Query("service")
	hasError := c.Query("has_error") == "true"

	startStr := c.Query("start")
	endStr := c.Query("end")

	now := time.Now()
	startTime := now.Add(-1 * time.Hour)
	endTime := now

	if startStr != "" {
		if ts, err := strconv.ParseInt(startStr, 10, 64); err == nil {
			startTime = time.Unix(ts, 0)
		}
	}
	if endStr != "" {
		if ts, err := strconv.ParseInt(endStr, 10, 64); err == nil {
			endTime = time.Unix(ts, 0)
		}
	}

	chains, err := s.correlator.SearchTraces(c.Request.Context(), serviceName, hasError, startTime, endTime)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to search traces"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"total":  len(chains),
		"traces": chains,
	})
}

func (s *Server) MarshalJSON(v interface{}) string {
	data, _ := json.Marshal(v)
	return string(data)
}
