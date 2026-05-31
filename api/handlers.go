package api

import (
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"session130/internal/alerting"
	"session130/internal/config"
	"session130/internal/core"
	"session130/internal/dnsproxy"
	"session130/internal/gateway"
	"session130/internal/logger"
	"session130/internal/metrics"
	"session130/internal/profiling"
	"session130/internal/slo"
	"session130/internal/topology"
	"session130/internal/tracing"
	"session130/pkg/models"
)

type CreateConfigRequest struct {
	Namespace  string                 `json:"namespace"`
	Parameters map[string]interface{} `json:"parameters"`
}

type UpdateConfigRequest struct {
	Parameters map[string]interface{} `json:"parameters"`
}

type RollbackRequest struct {
	TargetVersion int `json:"target_version"`
}

type ResourceRequest struct {
	Type   string                 `json:"type"`
	Config map[string]interface{} `json:"config"`
	Labels map[string]string      `json:"labels"`
}

func CreateConfigHandler(w http.ResponseWriter, r *http.Request) {
	var req CreateConfigRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	cfg, err := config.GetManager().CreateConfig(req.Namespace, req.Parameters)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	writeJSON(w, http.StatusCreated, map[string]interface{}{
		"code": 201,
		"data": cfg,
	})
}

func CreateConfigAsyncHandler(w http.ResponseWriter, r *http.Request) {
	var req CreateConfigRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	op := config.GetManager().CreateConfigAsync(req.Namespace, req.Parameters)

	writeJSON(w, http.StatusAccepted, map[string]interface{}{
		"code": 202,
		"data": map[string]interface{}{
			"operation_id": op.ID,
			"status":       op.Status,
			"type":         op.Type,
		},
	})
}

func GetOperationHandler(w http.ResponseWriter, r *http.Request) {
	opID := r.PathValue("op_id")
	op, err := config.GetManager().GetOperation(opID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": map[string]interface{}{
			"operation_id": op.ID,
			"type":         op.Type,
			"status":       op.Status,
			"namespace":    op.Namespace,
			"created_at":   op.CreatedAt,
			"completed_at": op.CompletedAt,
			"result":       op.Result,
			"error":        op.Error,
		},
	})
}

func ListOperationsHandler(w http.ResponseWriter, r *http.Request) {
	status := r.URL.Query().Get("status")
	ops := config.GetManager().ListOperations(config.OperationStatus(status))

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": ops,
	})
}

func GetConfigWorkerStatsHandler(w http.ResponseWriter, r *http.Request) {
	stats := config.GetManager().GetWorkerStats()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": stats,
	})
}

func GetConfigHandler(w http.ResponseWriter, r *http.Request) {
	namespace := r.PathValue("namespace")
	versionStr := r.URL.Query().Get("version")

	if versionStr != "" {
		version, err := strconv.Atoi(versionStr)
		if err != nil {
			http.Error(w, "invalid version", http.StatusBadRequest)
			return
		}
		cfg, err := config.GetManager().GetConfigByVersion(namespace, version)
		if err != nil {
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"code": 200,
			"data": cfg,
		})
		return
	}

	cfg, err := config.GetManager().GetConfig(namespace)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": cfg,
	})
}

func UpdateConfigHandler(w http.ResponseWriter, r *http.Request) {
	namespace := r.PathValue("namespace")
	var req UpdateConfigRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	cfg, err := config.GetManager().UpdateConfig(namespace, req.Parameters)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": cfg,
	})
}

func UpdateConfigAsyncHandler(w http.ResponseWriter, r *http.Request) {
	namespace := r.PathValue("namespace")
	var req UpdateConfigRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	op := config.GetManager().UpdateConfigAsync(namespace, req.Parameters)

	writeJSON(w, http.StatusAccepted, map[string]interface{}{
		"code": 202,
		"data": map[string]interface{}{
			"operation_id": op.ID,
			"status":       op.Status,
			"type":         op.Type,
		},
	})
}

func RollbackConfigHandler(w http.ResponseWriter, r *http.Request) {
	namespace := r.PathValue("namespace")
	var req RollbackRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	cfg, err := config.GetManager().Rollback(namespace, req.TargetVersion)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": cfg,
	})
}

func RollbackConfigAsyncHandler(w http.ResponseWriter, r *http.Request) {
	namespace := r.PathValue("namespace")
	var req RollbackRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	op := config.GetManager().RollbackAsync(namespace, req.TargetVersion)

	writeJSON(w, http.StatusAccepted, map[string]interface{}{
		"code": 202,
		"data": map[string]interface{}{
			"operation_id": op.ID,
			"status":       op.Status,
			"type":         op.Type,
		},
	})
}

func DeleteConfigAsyncHandler(w http.ResponseWriter, r *http.Request) {
	namespace := r.PathValue("namespace")
	op := config.GetManager().DeleteConfigAsync(namespace)

	writeJSON(w, http.StatusAccepted, map[string]interface{}{
		"code": 202,
		"data": map[string]interface{}{
			"operation_id": op.ID,
			"status":       op.Status,
			"type":         op.Type,
		},
	})
}

func ListConfigHistoryHandler(w http.ResponseWriter, r *http.Request) {
	namespace := r.PathValue("namespace")
	history, err := config.GetManager().GetVersionHistory(namespace)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": history,
	})
}

func ListNamespacesHandler(w http.ResponseWriter, r *http.Request) {
	namespaces := config.GetManager().ListNamespaces()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": namespaces,
	})
}

func DeleteConfigHandler(w http.ResponseWriter, r *http.Request) {
	namespace := r.PathValue("namespace")
	err := config.GetManager().DeleteConfig(namespace)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "deleted",
	})
}

func PreWarmAlertCacheHandler(w http.ResponseWriter, r *http.Request) {
	alerting.GetEvaluator().PreWarmCache()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "cache pre-warmed",
	})
}

func GetAlertCacheStatsHandler(w http.ResponseWriter, r *http.Request) {
	stats := alerting.GetEvaluator().GetCacheStats()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": stats,
	})
}

func GetLoggerStatsHandler(w http.ResponseWriter, r *http.Request) {
	stats := logger.GetStats()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": stats,
	})
}

func FlushLogsHandler(w http.ResponseWriter, r *http.Request) {
	logger.Flush()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "logs flushed",
	})
}

func CreateResourceHandler(w http.ResponseWriter, r *http.Request) {
	traceID := r.Header.Get("X-Trace-ID")
	if traceID == "" {
		traceID = tracing.GenerateTraceID()
	}

	var req models.APIRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	req.TraceID = traceID

	resp, err := core.Execute(r.Context(), &req)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	writeJSON(w, resp.Code, resp)
}

func GetMetricsHandler(w http.ResponseWriter, r *http.Request) {
	snapshot := metrics.GetRegistry().Snapshot()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": snapshot,
	})
}

func GetTopologyHandler(w http.ResponseWriter, r *http.Request) {
	topo := topology.GetBuilder().GetTopology()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": topo,
	})
}

func GetTraceHandler(w http.ResponseWriter, r *http.Request) {
	traceID := r.PathValue("trace_id")
	spans, err := tracing.GetTrace(traceID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": spans,
	})
}

func ListTracesHandler(w http.ResponseWriter, r *http.Request) {
	traces := tracing.GetCollector().GetAllTraces()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": traces,
	})
}

func RecordSpanHandler(w http.ResponseWriter, r *http.Request) {
	var span models.Span
	if err := json.NewDecoder(r.Body).Decode(&span); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if span.StartTime.IsZero() {
		span.StartTime = time.Now()
	}
	if span.EndTime.IsZero() {
		span.EndTime = time.Now()
	}

	tracing.RecordSpan(&span)
	topology.RecordSpan(&span)

	writeJSON(w, http.StatusAccepted, map[string]interface{}{
		"code":    202,
		"message": "span recorded",
	})
}

func StartProfilingHandler(w http.ResponseWriter, r *http.Request) {
	durationStr := r.URL.Query().Get("duration")
	duration := 30 * time.Second
	if durationStr != "" {
		if d, err := strconv.Atoi(durationStr); err == nil {
			duration = time.Duration(d) * time.Second
		}
	}

	profiling.GetProfiler().Start(duration)
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "profiling started",
	})
}

func StopProfilingHandler(w http.ResponseWriter, r *http.Request) {
	profiling.GetProfiler().Stop()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "profiling stopped",
	})
}

func GetCPUProfileHandler(w http.ResponseWriter, r *http.Request) {
	profile := profiling.GetProfiler().GetCPUProfile()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": profile,
	})
}

func GetMemoryProfileHandler(w http.ResponseWriter, r *http.Request) {
	profile := profiling.GetProfiler().GetMemoryProfile()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": profile,
	})
}

func GetProfilingStatsHandler(w http.ResponseWriter, r *http.Request) {
	stats := profiling.GetProfiler().GetStats()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": stats,
	})
}

func ListAlertRulesHandler(w http.ResponseWriter, r *http.Request) {
	rules := alerting.GetEvaluator().ListRules()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": rules,
	})
}

func CreateAlertRuleHandler(w http.ResponseWriter, r *http.Request) {
	var rule models.AlertRule
	if err := json.NewDecoder(r.Body).Decode(&rule); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if err := alerting.GetEvaluator().AddRule(&rule); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	writeJSON(w, http.StatusCreated, map[string]interface{}{
		"code": 201,
		"data": rule,
	})
}

func GetAlertsHandler(w http.ResponseWriter, r *http.Request) {
	status := r.URL.Query().Get("status")
	alerts := alerting.GetEvaluator().GetAlerts(status)
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": alerts,
	})
}

func HealthHandler(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "ok",
		"data": map[string]interface{}{
			"status": "healthy",
		},
	})
}

func ResolveDNSHandler(w http.ResponseWriter, r *http.Request) {
	traceID := r.Header.Get("X-Trace-ID")
	if traceID == "" {
		traceID = tracing.GenerateTraceID()
	}

	var req dnsproxy.DnsResolveRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	req.TraceID = traceID

	resp, err := dnsproxy.GetResolverService().Resolve(r.Context(), req)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{
			"code":    500,
			"message": err.Error(),
		})
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": resp,
	})
}

func ResolveDNSBatchHandler(w http.ResponseWriter, r *http.Request) {
	traceID := r.Header.Get("X-Trace-ID")
	if traceID == "" {
		traceID = tracing.GenerateTraceID()
	}

	var req dnsproxy.BatchResolveRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	for i := range req.Requests {
		req.Requests[i].TraceID = traceID
	}

	resp := dnsproxy.GetResolverService().ResolveBatch(r.Context(), req)

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": resp,
	})
}

func CreateUpstreamHandler(w http.ResponseWriter, r *http.Request) {
	var upstream dnsproxy.DnsUpstream
	if err := json.NewDecoder(r.Body).Decode(&upstream); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if upstream.ID == "" {
		upstream.ID = "upstream_" + strconv.FormatInt(time.Now().UnixNano(), 10)
	}
	upstream.CreatedAt = time.Now()
	upstream.UpdatedAt = time.Now()
	if upstream.Enabled == false {
		upstream.Enabled = true
	}
	if upstream.Port == 0 {
		upstream.Port = 53
	}
	if upstream.TimeoutMs == 0 {
		upstream.TimeoutMs = 5000
	}
	if upstream.MaxRetries == 0 {
		upstream.MaxRetries = 3
	}
	if upstream.Priority == 0 {
		upstream.Priority = 100
	}
	if upstream.Weight == 0 {
		upstream.Weight = 1
	}

	dnsproxy.GetUpstreamManager().AddUpstream(&upstream)

	writeJSON(w, http.StatusCreated, map[string]interface{}{
		"code": 201,
		"data": upstream,
	})
}

func ListUpstreamsHandler(w http.ResponseWriter, r *http.Request) {
	upstreams := dnsproxy.GetUpstreamManager().GetEnabledUpstreams()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": upstreams,
	})
}

func DeleteUpstreamHandler(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	dnsproxy.GetUpstreamManager().RemoveUpstream(id)
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "upstream deleted",
	})
}

func GetDNSCacheStatsHandler(w http.ResponseWriter, r *http.Request) {
	stats := dnsproxy.GetCacheManager().GetStats()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": stats,
	})
}

func InvalidateDNSCacheHandler(w http.ResponseWriter, r *http.Request) {
	domain := r.PathValue("domain")
	typeStr := r.URL.Query().Get("type")
	recordType := dnsproxy.TypeA
	if typeStr == "AAAA" {
		recordType = dnsproxy.TypeAAAA
	}

	dnsproxy.GetCacheManager().Invalidate(domain, recordType)
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "cache invalidated",
	})
}

func GetDNSProgressHandler(w http.ResponseWriter, r *http.Request) {
	progress := dnsproxy.GetResolverService().GetProgress()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": progress,
	})
}

func GetResourcePoolStatsHandler(w http.ResponseWriter, r *http.Request) {
	stats := core.GetProcessor().GetStats()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": stats,
	})
}

func GetObservabilityStatsHandler(w http.ResponseWriter, r *http.Request) {
	stats := gateway.GetGateway().GetObservabilityStats()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": stats,
	})
}

func SetObservabilityConfigHandler(w http.ResponseWriter, r *http.Request) {
	var config struct {
		Metrics bool `json:"metrics"`
		Tracing bool `json:"tracing"`
		Logging bool `json:"logging"`
	}
	if err := json.NewDecoder(r.Body).Decode(&config); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	gateway.GetGateway().SetObservability(config.Metrics, config.Tracing, config.Logging)

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "observability config updated",
	})
}

func GetConfigCacheHandler(w http.ResponseWriter, r *http.Request) {
	namespace := r.PathValue("namespace")

	cfg, level, err := config.GetCacheManager().GetConfig(r.Context(), namespace)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": map[string]interface{}{
			"config":      cfg,
			"cache_level": level,
			"from_cache":  level != "",
		},
	})
}

func PreWarmConfigCacheHandler(w http.ResponseWriter, r *http.Request) {
	count, err := config.GetCacheManager().PreWarmCache(r.Context())
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "cache pre-warmed",
		"data": map[string]interface{}{
			"prewarmed_count": count,
		},
	})
}

func InvalidateConfigCacheHandler(w http.ResponseWriter, r *http.Request) {
	namespace := r.PathValue("namespace")
	levelStr := r.URL.Query().Get("level")
	level := config.CacheLevelAll
	if levelStr == "l1" {
		level = config.CacheLevelL1
	} else if levelStr == "l2" {
		level = config.CacheLevelL2
	}

	err := config.GetCacheManager().Invalidate(r.Context(), namespace, level)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "cache invalidated",
	})
}

func InvalidateAllConfigCacheHandler(w http.ResponseWriter, r *http.Request) {
	levelStr := r.URL.Query().Get("level")
	level := config.CacheLevelAll
	if levelStr == "l1" {
		level = config.CacheLevelL1
	} else if levelStr == "l2" {
		level = config.CacheLevelL2
	}

	err := config.GetCacheManager().InvalidateAll(r.Context(), level)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "all cache invalidated",
	})
}

func GetConfigCacheStatsHandler(w http.ResponseWriter, r *http.Request) {
	stats := config.GetCacheManager().GetStats()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": stats,
	})
}

type BatchLogRequest struct {
	Entries []BatchLogEntry `json:"entries"`
}

type BatchLogEntry struct {
	Level   string                 `json:"level"`
	TraceID string                 `json:"trace_id,omitempty"`
	Message string                 `json:"message"`
	Fields  map[string]interface{} `json:"fields,omitempty"`
}

func BatchLogHandler(w http.ResponseWriter, r *http.Request) {
	var req BatchLogRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if len(req.Entries) == 0 {
		http.Error(w, "no log entries provided", http.StatusBadRequest)
		return
	}

	if len(req.Entries) > 1000 {
		http.Error(w, "batch size exceeds maximum of 1000", http.StatusBadRequest)
		return
	}

	successCount := logger.BatchLog(req.Entries)

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": map[string]interface{}{
			"total":   len(req.Entries),
			"success": successCount,
		},
	})
}

func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(data)
}

type CreateSLIRequest struct {
	SLIID       string            `json:"sli_id"`
	Name        string            `json:"name"`
	Description string            `json:"description"`
	MetricType  string            `json:"metric_type"`
	GoodEvents  string            `json:"good_events"`
	TotalEvents string            `json:"total_events"`
	Labels      map[string]string `json:"labels"`
}

type CreateSLORequest struct {
	SLOID       string            `json:"slo_id"`
	Name        string            `json:"name"`
	SLIID       string            `json:"sli_id"`
	Target      float64           `json:"target"`
	Window      string            `json:"window"`
	ErrorBudget float64           `json:"error_budget"`
	Labels      map[string]string `json:"labels"`
}

type RecordSLIRequest struct {
	Good  bool  `json:"good"`
	Count int64 `json:"count"`
}

type AlertRuleRequest struct {
	AlertID      string  `json:"alert_id"`
	BurnRate     float64 `json:"burn_rate"`
	Window       string  `json:"window"`
	Threshold    float64 `json:"threshold"`
	Notification string  `json:"notification"`
}

func CreateSLIHandler(w http.ResponseWriter, r *http.Request) {
	var req CreateSLIRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	sli := &models.SLIConfig{
		SLIID:       req.SLIID,
		Name:        req.Name,
		Description: req.Description,
		MetricType:  req.MetricType,
		GoodEvents:  req.GoodEvents,
		TotalEvents: req.TotalEvents,
		Labels:      req.Labels,
	}

	sloManager := slo.GetManager()
	if err := sloManager.CreateSLI(r.Context(), *sli); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	writeJSON(w, http.StatusCreated, map[string]interface{}{
		"code":    201,
		"message": "SLI created successfully",
		"data":    sli,
	})
}

func CreateSLOHandler(w http.ResponseWriter, r *http.Request) {
	var req CreateSLORequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	sloCfg := &models.SLOConfig{
		SLOID:       req.SLOID,
		Name:        req.Name,
		SLIID:       req.SLIID,
		Target:      req.Target,
		Window:      req.Window,
		ErrorBudget: req.ErrorBudget,
		Labels:      req.Labels,
	}

	sloManager := slo.GetManager()
	if err := sloManager.CreateSLO(r.Context(), *sloCfg); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	writeJSON(w, http.StatusCreated, map[string]interface{}{
		"code":    201,
		"message": "SLO created successfully",
		"data":    sloCfg,
	})
}

func RecordSLIHandler(w http.ResponseWriter, r *http.Request) {
	sliID := r.PathValue("sli_id")

	var req RecordSLIRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	sloManager := slo.GetManager()
	sloManager.RecordSLI(r.Context(), sliID, req.Good, req.Count)

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "SLI recorded successfully",
	})
}

func GetSLIHandler(w http.ResponseWriter, r *http.Request) {
	sliID := r.PathValue("sli_id")

	sloManager := slo.GetManager()
	sli, err := sloManager.GetSLI(r.Context(), sliID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": sli,
	})
}

func GetSLOHandler(w http.ResponseWriter, r *http.Request) {
	sloID := r.PathValue("slo_id")

	sloManager := slo.GetManager()
	sloCfg, err := sloManager.GetSLO(r.Context(), sloID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": sloCfg,
	})
}

func GetErrorBudgetHandler(w http.ResponseWriter, r *http.Request) {
	sloID := r.PathValue("slo_id")

	sloManager := slo.GetManager()
	budget, err := sloManager.GetErrorBudget(r.Context(), sloID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": budget,
	})
}

func GetSLOStatusHandler(w http.ResponseWriter, r *http.Request) {
	sloID := r.PathValue("slo_id")

	sloManager := slo.GetManager()
	status, err := sloManager.GetSLOStatus(r.Context(), sloID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": status,
	})
}

func AddAlertRuleHandler(w http.ResponseWriter, r *http.Request) {
	sloID := r.PathValue("slo_id")

	var req AlertRuleRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	sloManager := slo.GetManager()
	sloManager.AddAlertRule(r.Context(), sloID, req.BurnRate, req.Window, req.Threshold)

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "Alert rule added successfully",
	})
}

func CheckBurnRateHandler(w http.ResponseWriter, r *http.Request) {
	sloID := r.PathValue("slo_id")
	windowStr := r.URL.Query().Get("window")

	sloManager := slo.GetManager()
	alerts, err := sloManager.CheckBurnRate(r.Context(), sloID, windowStr)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": alerts,
	})
}

func GetSLOListHandler(w http.ResponseWriter, r *http.Request) {
	sloManager := slo.GetManager()
	sloList := sloManager.ListSLOs(r.Context())

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": sloList,
	})
}

func GetSLIListHandler(w http.ResponseWriter, r *http.Request) {
	sloManager := slo.GetManager()
	sliList := sloManager.ListSLIs(r.Context())

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": sliList,
	})
}

func GetRouterStatsHandler(w http.ResponseWriter, r *http.Request) {
	sloManager := slo.GetManager()
	stats := sloManager.GetRouterStats()

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": stats,
	})
}

func GetEventBusStatsHandler(w http.ResponseWriter, r *http.Request) {
	eb := config.GetCacheEventBus()
	stats := eb.GetStats()

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": stats,
	})
}

type SubscribeEventRequest struct {
	Event string `json:"event"`
}

func SubscribeEventHandler(w http.ResponseWriter, r *http.Request) {
	var req SubscribeEventRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	eb := config.GetCacheEventBus()
	subID := eb.Subscribe(config.CacheEventType(req.Event), func(ctx context.Context, event config.CacheEventData) {
		logger.Info("", "cache event received", map[string]interface{}{
			"event": string(event.Event),
			"key":   event.Key,
		})
	})

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "subscribed successfully",
		"data": map[string]interface{}{
			"subscription_id": subID,
			"event":           req.Event,
		},
	})
}

func UnsubscribeEventHandler(w http.ResponseWriter, r *http.Request) {
	event := config.CacheEventType(r.PathValue("event"))
	subID := r.PathValue("subscription_id")

	eb := config.GetCacheEventBus()
	success := eb.Unsubscribe(event, subID)

	if !success {
		http.Error(w, "subscription not found", http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "unsubscribed successfully",
	})
}

func GetSubscriberCountHandler(w http.ResponseWriter, r *http.Request) {
	event := config.CacheEventType(r.PathValue("event"))

	eb := config.GetCacheEventBus()
	count := eb.GetSubscriberCount(event)

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": map[string]interface{}{
			"event":            string(event),
			"subscriber_count": count,
		},
	})
}

type LoadPluginRequest struct {
	PluginType string                 `json:"plugin_type"`
	PluginName string                 `json:"plugin_name"`
	Config     map[string]interface{} `json:"config"`
}

func LoadPluginHandler(w http.ResponseWriter, r *http.Request) {
	var req LoadPluginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	pm := metrics.GetPluginManager()
	var plugin metrics.MetricsPlugin

	switch req.PluginName {
	case "statistical-aggregations":
		plugin = metrics.NewStatisticalAggregationPlugin()
	case "inmemory-storage":
		plugin = metrics.NewInMemoryStoragePlugin()
	default:
		http.Error(w, "unknown plugin", http.StatusBadRequest)
		return
	}

	pluginID, err := pm.LoadPlugin(r.Context(), plugin, req.Config)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "plugin loaded successfully",
		"data": map[string]interface{}{
			"plugin_id": pluginID,
		},
	})
}

func UnloadPluginHandler(w http.ResponseWriter, r *http.Request) {
	pluginID := r.PathValue("plugin_id")

	pm := metrics.GetPluginManager()
	if err := pm.UnloadPlugin(r.Context(), pluginID); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "plugin unloaded successfully",
	})
}

func ListPluginsHandler(w http.ResponseWriter, r *http.Request) {
	pm := metrics.GetPluginManager()
	plugins := pm.ListPlugins()

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": plugins,
	})
}

func GetPluginHandler(w http.ResponseWriter, r *http.Request) {
	pluginID := r.PathValue("plugin_id")

	pm := metrics.GetPluginManager()
	plugin, exists := pm.GetPlugin(pluginID)
	if !exists {
		http.Error(w, "plugin not found", http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": map[string]interface{}{
			"id":          plugin.ID(),
			"name":        plugin.Name(),
			"version":     plugin.Version(),
			"type":        string(plugin.Type()),
			"description": plugin.Description(),
			"status":      string(plugin.Status()),
		},
	})
}

func ListAggregationFunctionsHandler(w http.ResponseWriter, r *http.Request) {
	pm := metrics.GetPluginManager()
	functions := pm.ListAggregationFunctions()

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": functions,
	})
}

func ListStorageAdaptersHandler(w http.ResponseWriter, r *http.Request) {
	pm := metrics.GetPluginManager()
	adapters := pm.ListStorageAdapters()

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": adapters,
	})
}

type ExecuteAggregationRequest struct {
	Function string    `json:"function"`
	Values   []float64 `json:"values"`
}

func ExecuteAggregationHandler(w http.ResponseWriter, r *http.Request) {
	var req ExecuteAggregationRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	pm := metrics.GetPluginManager()
	result, err := pm.ExecuteAggregation(r.Context(), req.Function, req.Values)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": map[string]interface{}{
			"function": req.Function,
			"result":   result,
		},
	})
}

func GetPluginManagerStatsHandler(w http.ResponseWriter, r *http.Request) {
	pm := metrics.GetPluginManager()
	stats := pm.GetStats()

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": stats,
	})
}
