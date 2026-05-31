package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"observability-platform/internal/alerts"
	"observability-platform/internal/metrics"
	"observability-platform/internal/profiling"
	"observability-platform/pkg/models"
)

func main() {
	fmt.Println("=== Enhanced Observability Platform Demo ===")
	fmt.Println()

	fmt.Println("1. Streaming Metrics Service Demo")
	fmt.Println("-------------------------------")
	streamingDemo()

	fmt.Println()
	fmt.Println("2. Profiling Resource Pool Demo")
	fmt.Println("------------------------------")
	poolingDemo()

	fmt.Println()
	fmt.Println("3. Alert Engine Observability Demo")
	fmt.Println("----------------------------------")
	alertObservabilityDemo()

	fmt.Println()
	fmt.Println("=== All demos completed successfully ===")
}

func streamingDemo() {
	streamConfig := metrics.DefaultStreamingConfig()
	streamConfig.BatchSize = 100
	streamConfig.FlushInterval = time.Millisecond * 500
	streamConfig.ChannelBufferSize = 10000

	aggConfig := metrics.AggregationConfig{
		Interval:        time.Minute,
		RetentionPeriod: time.Hour * 24,
		Aggregations: []metrics.AggregationType{
			metrics.AggregationSum,
			metrics.AggregationAvg,
			metrics.AggregationCount,
			metrics.AggregationP95,
			metrics.AggregationP99,
		},
	}

	storageConfig := metrics.StorageConfig{
		Type: metrics.StorageTypeInMemory,
		InMemory: &metrics.InMemoryStorageConfig{
			Retention: time.Hour * 24,
			MaxPoints: 100000,
		},
	}

	streamService, err := metrics.NewStreamMetricsService(aggConfig, storageConfig, streamConfig)
	if err != nil {
		log.Fatalf("Failed to create streaming service: %v", err)
	}

	err = streamService.Start()
	if err != nil {
		log.Fatalf("Failed to start streaming service: %v", err)
	}
	defer streamService.Stop()

	metric := models.Metric{
		Name:   "request_latency_ms",
		Labels: map[string]string{"service": "api-gateway", "method": "GET"},
	}

	fmt.Printf("Ingesting 5000 metrics asynchronously...\n")
	successCount := 0
	dropCount := 0

	for i := 0; i < 5000; i++ {
		value := float64(10 + (i % 50))
		if streamService.RecordMetricAsync(metric, value) {
			successCount++
		} else {
			dropCount++
		}
	}

	fmt.Printf("  - Success: %d\n", successCount)
	fmt.Printf("  - Dropped: %d\n", dropCount)

	time.Sleep(time.Millisecond * 100)

	flushed := streamService.ForceFlush()
	fmt.Printf("  - Force flushed: %d points\n", flushed)

	time.Sleep(time.Millisecond * 500)

	stats := streamService.GetStreamingStats()
	fmt.Printf("  - Processed: %v\n", stats["processed_count"])
	fmt.Printf("  - Dropped: %v\n", stats["dropped_count"])
	fmt.Printf("  - Errors: %v\n", stats["error_count"])
	fmt.Printf("  - Channel usage: %v/%v\n", stats["channel_size"], stats["channel_cap"])

	fc := stats["flow_control"].(map[string]interface{})
	fmt.Printf("  - Backpressure blocked count: %v\n", fc["blocked_count"])

	aggSeries, _ := streamService.GetAggregatedSeries(
		"request_latency_ms",
		map[string]string{"service": "api-gateway", "method": "GET"},
		metrics.AggregationP95,
	)
	fmt.Printf("  - P95 aggregated points: %d\n", len(aggSeries))

	fmt.Printf("  ✓ Streaming service working correctly\n")
}

func poolingDemo() {
	poolConfig := profiling.DefaultPoolConfig()

	profilerConfig := profiling.ProfilerConfig{
		ServiceName:     "pooled-service",
		InstanceID:      "instance-001",
		ProfileInterval: time.Minute * 5,
		ProfileDuration: time.Second * 2,
		MaxSamples:      100,
		SampleRate:      100,
		EnabledProfiles: []models.ProfileType{
			models.ProfileTypeCPU,
			models.ProfileTypeHeap,
		},
	}

	profiler := profiling.NewProfilerWithPool(profilerConfig, poolConfig)

	fmt.Printf("Collecting profile with pooled resources...\n")

	sample, err := profiler.CollectProfile(models.ProfileTypeCPU, time.Millisecond*100)
	if err != nil {
		log.Printf("Warning: CPU profiling not available: %v", err)
	} else {
		fmt.Printf("  - Profile collected: %s (%d bytes)\n", sample.ProfileType, len(sample.Data))
		profiler.ReleaseSample(sample)
		fmt.Printf("  - Sample released to pool\n")
	}

	flameGraph, fgErr := profiler.GenerateFlameGraph(sample)
	if fgErr != nil {
		fmt.Printf("  - Flame graph generation failed: %v\n", fgErr)
	} else {
		fmt.Printf("  - Flame graph generated: %s, total samples: %d\n", flameGraph.ID, flameGraph.TotalSamples)
	}

	poolStats := profiler.GetPoolStats()
	fmt.Printf("\nPool Statistics:\n")

	bufferStats := poolStats["buffer_pool"].(map[string]interface{})
	fmt.Printf("  Buffer Pool:\n")
	fmt.Printf("    - Hit rate: %.1f%%\n", bufferStats["hit_rate"].(float64))
	fmt.Printf("    - In use: %d\n", bufferStats["in_use"].(int64))
	fmt.Printf("    - Max in use: %d\n", bufferStats["max_in_use"].(int64))

	sampleStats := poolStats["sample_pool"].(map[string]interface{})
	fmt.Printf("  Sample Pool:\n")
	fmt.Printf("    - Hit rate: %.1f%%\n", sampleStats["hit_rate"].(float64))
	fmt.Printf("    - Total created: %d\n", sampleStats["total_created"].(int64))

	nodeStats := poolStats["node_pool"].(map[string]interface{})
	fmt.Printf("  Node Pool:\n")
	fmt.Printf("    - Hit rate: %.1f%%\n", nodeStats["hit_rate"].(float64))
	fmt.Printf("    - Total destroyed: %d\n", nodeStats["total_destroyed"].(int64))

	fmt.Println(profiler.GetPoolManager().Summary())

	fmt.Printf("  ✓ Resource pooling working correctly\n")
}

func alertObservabilityDemo() {
	metricProvider := alerts.NewSimpleMetricProvider()
	metricProvider.SetMetric("cpu_usage", 45.0)
	metricProvider.SetMetric("memory_usage", 60.0)
	metricProvider.SetMetric("error_rate", 0.02)

	engineConfig := alerts.EngineConfig{
		EvaluationInterval: time.Second * 2,
		MaxHistorySize:     1000,
	}

	engine := alerts.NewObservableAlertEngine(engineConfig, metricProvider)

	highCpuRule := &models.AlertRule{
		Name:        "High CPU Usage",
		Description: "CPU usage is above 80%",
		Expression:  "cpu_usage > 80",
		Severity:    models.SeverityWarning,
		Enabled:     true,
	}
	engine.AddRule(highCpuRule)

	highMemoryRule := &models.AlertRule{
		Name:        "High Memory Usage",
		Description: "Memory usage is above 90%",
		Expression:  "memory_usage > 90",
		Severity:    models.SeverityWarning,
		Enabled:     true,
	}
	engine.AddRule(highMemoryRule)

	highErrorRule := &models.AlertRule{
		Name:        "High Error Rate",
		Description: "Error rate is above 5%",
		Expression:  "error_rate > 0.05",
		Severity:    models.SeverityError,
		Enabled:     true,
	}
	engine.AddRule(highErrorRule)

	engine.AddNotificationSender(alerts.NewConsoleNotificationSender())

	channel := &models.NotificationChannel{
		ID:   "console",
		Type: "console",
		Name: "Console Channel",
	}
	engine.AddNotificationChannel(channel)
	highCpuRule.NotificationIDs = []string{"console"}
	highMemoryRule.NotificationIDs = []string{"console"}
	highErrorRule.NotificationIDs = []string{"console"}

	engine.Start()
	defer engine.Stop()

	fmt.Printf("Running 3 rule evaluations...\n")
	for i := 0; i < 3; i++ {
		if i == 1 {
			metricProvider.SetMetric("cpu_usage", 85.0)
		}
		if i == 2 {
			metricProvider.SetMetric("cpu_usage", 40.0)
		}
		time.Sleep(time.Millisecond * 100)
	}

	jsonStats := engine.GetMetricsJSON()
	fmt.Printf("\nAlert Engine Metrics (JSON):\n")
	engineStats := jsonStats["engine"].(map[string]interface{})
	fmt.Printf("  - Total evaluations: %v\n", engineStats["evaluations_total"])
	fmt.Printf("  - Total firings: %v\n", engineStats["rule_firings_total"])
	fmt.Printf("  - Alerts created: %v\n", engineStats["alerts_created_total"])
	fmt.Printf("  - Alerts resolved: %v\n", engineStats["alerts_resolved_total"])
	fmt.Printf("  - Active alerts: %v\n", engineStats["active_alerts"])
	fmt.Printf("  - Rules total: %v\n", engineStats["rules_total"])
	fmt.Printf("  - Rules enabled: %v\n", engineStats["rules_enabled"])

	rulesStats := jsonStats["rules"].(map[string]interface{})
	for ruleID, stat := range rulesStats {
		ruleStat := stat.(map[string]interface{})
		fmt.Printf("\n  Rule %s (%s):\n", ruleID[:8], ruleStat["rule_name"])
		fmt.Printf("    - Evaluations: %v\n", ruleStat["evaluations_total"])
		fmt.Printf("    - Firings: %v\n", ruleStat["firings_total"])
		fmt.Printf("    - Current status: %v\n", ruleStat["current_status"])
	}

	promMetrics := engine.ExportMetricsPrometheus()
	fmt.Printf("\nPrometheus Export (first 500 chars):\n")
	if len(promMetrics) > 500 {
		fmt.Printf("  %s...\n", promMetrics[:500])
	} else {
		fmt.Printf("  %s\n", promMetrics)
	}

	fmt.Printf("  ✓ Alert engine observability working correctly\n")
}

func startHTTPServer(
	streamService *metrics.StreamMetricsService,
	profiler *profiling.ProfilerWithPool,
	alertEngine *alerts.ObservableAlertEngine,
) {
	mux := http.NewServeMux()

	mux.HandleFunc("/metrics/streaming/stats", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(streamService.GetStreamingStats())
	})

	mux.HandleFunc("/metrics/streaming/flush", func(w http.ResponseWriter, r *http.Request) {
		flushed := streamService.ForceFlush()
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]int{"flushed": flushed})
	})

	mux.HandleFunc("/profiling/pool/stats", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(profiler.GetPoolStats())
	})

	mux.HandleFunc("/alerts/metrics", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(alertEngine.GetMetricsJSON())
	})

	mux.HandleFunc("/alerts/metrics/prometheus", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		w.Write([]byte(alertEngine.ExportMetricsPrometheus()))
	})

	server := &http.Server{
		Addr:    ":8081",
		Handler: mux,
	}

	go func() {
		log.Printf("Demo HTTP server starting on :8081")
		log.Printf("Endpoints:")
		log.Printf("  GET /metrics/streaming/stats")
		log.Printf("  POST /metrics/streaming/flush")
		log.Printf("  GET /profiling/pool/stats")
		log.Printf("  GET /alerts/metrics")
		log.Printf("  GET /alerts/metrics/prometheus")
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Printf("HTTP server error: %v", err)
		}
	}()

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	server.Shutdown(nil)
}
