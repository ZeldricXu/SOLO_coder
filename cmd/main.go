package main

import (
	"context"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"session130/api"
	"session130/internal/alerting"
	"session130/internal/config"
	"session130/internal/core"
	"session130/internal/dnsproxy"
	"session130/internal/logger"
	"session130/internal/metrics"
	"session130/internal/profiling"
	"session130/internal/topology"
	"session130/internal/tracing"
	"session130/pkg/models"
)

func main() {
	logger.Init("main", logger.InfoLevel)
	logger.SetAsyncMode(true, 1, 10, 10000, 100, 5*time.Second)
	logger.Info("", "starting application", nil)

	metrics.GetRegistry().SetDimensions(map[string]string{
		"service": "config-versioning",
		"host":    "localhost",
		"region":  "cn-east",
	})

	alerting.GetEvaluator().SetMetricSource(func(metric string, labels map[string]string) (float64, error) {
		return float64(metrics.GetRegistry().GetCounter(metric, labels)), nil
	})
	alerting.GetEvaluator().Start()
	defer alerting.GetEvaluator().Stop()

	alerting.GetEvaluator().PreWarmCache()

	tracing.GetCollector().Subscribe(func(span *models.Span) {
		topology.GetBuilder().RecordSpan(span)
	})

	config.GetManager().Subscribe(func(cfg *models.Config) {
		logger.Info("", "config changed", map[string]interface{}{
			"config_id": cfg.ConfigID,
			"namespace": cfg.Namespace,
			"version":   cfg.Version,
		})
	})

	mux := http.NewServeMux()

	mux.HandleFunc("POST /api/v1/configs", api.CreateConfigHandler)
	mux.HandleFunc("POST /api/v1/configs/async", api.CreateConfigAsyncHandler)
	mux.HandleFunc("GET /api/v1/configs/operations", api.ListOperationsHandler)
	mux.HandleFunc("GET /api/v1/configs/operations/{op_id}", api.GetOperationHandler)
	mux.HandleFunc("GET /api/v1/configs/worker-stats", api.GetConfigWorkerStatsHandler)
	mux.HandleFunc("GET /api/v1/configs/{namespace}", api.GetConfigHandler)
	mux.HandleFunc("PUT /api/v1/configs/{namespace}", api.UpdateConfigHandler)
	mux.HandleFunc("PUT /api/v1/configs/{namespace}/async", api.UpdateConfigAsyncHandler)
	mux.HandleFunc("POST /api/v1/configs/{namespace}/rollback", api.RollbackConfigHandler)
	mux.HandleFunc("POST /api/v1/configs/{namespace}/rollback/async", api.RollbackConfigAsyncHandler)
	mux.HandleFunc("DELETE /api/v1/configs/{namespace}/async", api.DeleteConfigAsyncHandler)
	mux.HandleFunc("GET /api/v1/configs/{namespace}/history", api.ListConfigHistoryHandler)
	mux.HandleFunc("GET /api/v1/configs", api.ListNamespacesHandler)
	mux.HandleFunc("DELETE /api/v1/configs/{namespace}", api.DeleteConfigHandler)

	mux.HandleFunc("GET /api/v1/configs/cache/{namespace}", api.GetConfigCacheHandler)
	mux.HandleFunc("POST /api/v1/configs/cache/prewarm", api.PreWarmConfigCacheHandler)
	mux.HandleFunc("DELETE /api/v1/configs/cache/{namespace}", api.InvalidateConfigCacheHandler)
	mux.HandleFunc("DELETE /api/v1/configs/cache", api.InvalidateAllConfigCacheHandler)
	mux.HandleFunc("GET /api/v1/configs/cache/stats", api.GetConfigCacheStatsHandler)

	mux.HandleFunc("POST /api/v1/resources", api.CreateResourceHandler)

	mux.HandleFunc("GET /api/v1/metrics", api.GetMetricsHandler)
	mux.HandleFunc("GET /api/v1/topology", api.GetTopologyHandler)

	mux.HandleFunc("GET /api/v1/traces", api.ListTracesHandler)
	mux.HandleFunc("GET /api/v1/traces/{trace_id}", api.GetTraceHandler)
	mux.HandleFunc("POST /api/v1/spans", api.RecordSpanHandler)

	mux.HandleFunc("POST /api/v1/profiling/start", api.StartProfilingHandler)
	mux.HandleFunc("POST /api/v1/profiling/stop", api.StopProfilingHandler)
	mux.HandleFunc("GET /api/v1/profiling/cpu", api.GetCPUProfileHandler)
	mux.HandleFunc("GET /api/v1/profiling/memory", api.GetMemoryProfileHandler)
	mux.HandleFunc("GET /api/v1/profiling/stats", api.GetProfilingStatsHandler)

	mux.HandleFunc("GET /api/v1/alerts/rules", api.ListAlertRulesHandler)
	mux.HandleFunc("POST /api/v1/alerts/rules", api.CreateAlertRuleHandler)
	mux.HandleFunc("GET /api/v1/alerts", api.GetAlertsHandler)
	mux.HandleFunc("POST /api/v1/alerts/cache/prewarm", api.PreWarmAlertCacheHandler)
	mux.HandleFunc("GET /api/v1/alerts/cache/stats", api.GetAlertCacheStatsHandler)

	mux.HandleFunc("GET /api/v1/logger/stats", api.GetLoggerStatsHandler)
	mux.HandleFunc("POST /api/v1/logger/flush", api.FlushLogsHandler)
	mux.HandleFunc("POST /api/v1/logger/batch", api.BatchLogHandler)

	mux.HandleFunc("GET /api/v1/tenants/monitor/summary", api.GetTenantMonitorSummaryHandler)
	mux.HandleFunc("GET /api/v1/tenants/monitor/operations", api.GetAllTenantOperationStatsHandler)
	mux.HandleFunc("GET /api/v1/tenants/monitor/operations/{operation}", api.GetTenantOperationStatsHandler)
	mux.HandleFunc("GET /api/v1/tenants/monitor/prometheus", api.GetTenantPrometheusMetricsHandler)
	mux.HandleFunc("POST /api/v1/tenants/monitor/reset", api.ResetTenantMonitorHandler)

	mux.HandleFunc("GET /health", api.HealthHandler)

	mux.HandleFunc("POST /api/v1/slo/slis", api.CreateSLIHandler)
	mux.HandleFunc("GET /api/v1/slo/slis", api.GetSLIListHandler)
	mux.HandleFunc("GET /api/v1/slo/slis/{sli_id}", api.GetSLIHandler)
	mux.HandleFunc("POST /api/v1/slo/slis/{sli_id}/record", api.RecordSLIHandler)

	mux.HandleFunc("POST /api/v1/slo/slos", api.CreateSLOHandler)
	mux.HandleFunc("GET /api/v1/slo/slos", api.GetSLOListHandler)
	mux.HandleFunc("GET /api/v1/slo/slos/{slo_id}", api.GetSLOHandler)
	mux.HandleFunc("GET /api/v1/slo/slos/{slo_id}/error-budget", api.GetErrorBudgetHandler)
	mux.HandleFunc("GET /api/v1/slo/slos/{slo_id}/status", api.GetSLOStatusHandler)
	mux.HandleFunc("POST /api/v1/slo/slos/{slo_id}/alerts", api.AddAlertRuleHandler)
	mux.HandleFunc("GET /api/v1/slo/slos/{slo_id}/burn-rate", api.CheckBurnRateHandler)

	mux.HandleFunc("GET /api/v1/slo/router/stats", api.GetRouterStatsHandler)

	mux.HandleFunc("GET /api/v1/events/stats", api.GetEventBusStatsHandler)
	mux.HandleFunc("POST /api/v1/events/subscribe", api.SubscribeEventHandler)
	mux.HandleFunc("DELETE /api/v1/events/{event}/subscriptions/{subscription_id}", api.UnsubscribeEventHandler)
	mux.HandleFunc("GET /api/v1/events/{event}/subscribers", api.GetSubscriberCountHandler)

	mux.HandleFunc("POST /api/v1/metrics/plugins", api.LoadPluginHandler)
	mux.HandleFunc("DELETE /api/v1/metrics/plugins/{plugin_id}", api.UnloadPluginHandler)
	mux.HandleFunc("GET /api/v1/metrics/plugins", api.ListPluginsHandler)
	mux.HandleFunc("GET /api/v1/metrics/plugins/{plugin_id}", api.GetPluginHandler)
	mux.HandleFunc("GET /api/v1/metrics/plugins/stats", api.GetPluginManagerStatsHandler)
	mux.HandleFunc("GET /api/v1/metrics/aggregations", api.ListAggregationFunctionsHandler)
	mux.HandleFunc("POST /api/v1/metrics/aggregations/execute", api.ExecuteAggregationHandler)
	mux.HandleFunc("GET /api/v1/metrics/storage-adapters", api.ListStorageAdaptersHandler)

	mux.HandleFunc("POST /api/v1/dns/resolve", api.ResolveDNSHandler)
	mux.HandleFunc("POST /api/v1/dns/batch-resolve", api.ResolveDNSBatchHandler)
	mux.HandleFunc("POST /api/v1/dns/upstreams", api.CreateUpstreamHandler)
	mux.HandleFunc("GET /api/v1/dns/upstreams", api.ListUpstreamsHandler)
	mux.HandleFunc("DELETE /api/v1/dns/upstreams/{id}", api.DeleteUpstreamHandler)
	mux.HandleFunc("GET /api/v1/dns/cache/stats", api.GetDNSCacheStatsHandler)
	mux.HandleFunc("DELETE /api/v1/dns/cache/{domain}", api.InvalidateDNSCacheHandler)
	mux.HandleFunc("GET /api/v1/dns/progress", api.GetDNSProgressHandler)

	mux.HandleFunc("GET /api/v1/core/pool-stats", api.GetResourcePoolStatsHandler)

	mux.HandleFunc("GET /api/v1/gateway/observability", api.GetObservabilityStatsHandler)
	mux.HandleFunc("POST /api/v1/gateway/observability", api.SetObservabilityConfigHandler)

	dnsproxy.GetResolverService().Start()

	core.GetProcessor().RegisterPool("dns_workers", core.PoolConfig{
		MaxSize: 100,
		MinIdle: 10,
		Type:    "dns",
	}, func() (*core.PooledResource, error) {
		return &core.PooledResource{
			ID:   "res_dns_" + tracing.GenerateTraceID(),
			Type: "dns_worker",
			Data: make(map[string]interface{}),
		}, nil
	})

	server := &http.Server{
		Addr:         ":8080",
		Handler:      mux,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	go func() {
		logger.Info("", "server listening on :8080", nil)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("", "server failed to start", map[string]interface{}{
				"error": err.Error(),
			})
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("", "shutting down server", nil)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if err := server.Shutdown(ctx); err != nil {
		logger.Error("", "server forced to shutdown", map[string]interface{}{
			"error": err.Error(),
		})
	}

	profiling.GetProfiler().Stop()
	dnsproxy.GetResolverService().Stop()
	logger.Shutdown()
	logger.Info("", "application exited", nil)
}
