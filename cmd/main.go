package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"observability-platform/internal/alerts"
	"observability-platform/internal/anomaly"
	"observability-platform/internal/logs"
	"observability-platform/internal/metrics"
	"observability-platform/internal/profiling"
	"observability-platform/internal/slo"
	"observability-platform/internal/topology"
	"observability-platform/internal/trace"
	"observability-platform/pkg/models"
)

type ObservabilityPlatform struct {
	traceCollector   *trace.Collector
	topologyBuilder  *topology.TopologyBuilder
	metricsService   *metrics.MetricsService
	logPipeline      *logs.Pipeline
	alertEngine      *alerts.AlertEngine
	sloMonitor       *slo.SLOMonitor
	profiler         *profiling.Profiler
	anomalyDetector  *anomaly.AnomalyDetector
	metricProvider   *alerts.SimpleMetricProvider
}

func NewObservabilityPlatform() *ObservabilityPlatform {
	metricProvider := alerts.NewSimpleMetricProvider()

	traceCollector := trace.NewCollector(trace.CollectorConfig{
		BufferSize:       10000,
		FlushInterval:    time.Second * 5,
		MaxTraceWaitTime: time.Minute * 2,
		Sampler:          trace.NewProbabilisticSampler(1.0),
	})

	topologyBuilder := topology.NewTopologyBuilder(topology.TopologyConfig{
		TimeWindow: time.Hour,
	})

	traceExporter := &TopologyExporter{builder: topologyBuilder}
	traceCollector.AddTraceExporter(traceExporter)

	metricsService, _ := metrics.NewMetricsService(
		metrics.AggregationConfig{
			Interval:        time.Minute,
			RetentionPeriod: time.Hour * 24,
			Aggregations: []metrics.AggregationType{
				metrics.AggregationSum,
				metrics.AggregationAvg,
				metrics.AggregationCount,
				metrics.AggregationP95,
				metrics.AggregationP99,
			},
		},
		metrics.StorageConfig{
			Type: metrics.StorageTypeInMemory,
			InMemory: &metrics.InMemoryStorageConfig{
				Retention: time.Hour * 24,
				MaxPoints: 100000,
			},
		},
	)

	logPipeline := logs.NewPipeline(logs.PipelineConfig{
		BufferSize: 10000,
		Workers:    4,
		BatchSize:  100,
		BatchFlush: time.Second,
	})
	logPipeline.AddParser(logs.NewJSONParser())
	logPipeline.AddFilter(logs.NewSeverityFilter(models.SeverityInfo))
	logPipeline.AddOutput(logs.NewConsoleOutput("text"))

	alertEngine := alerts.NewAlertEngine(alerts.EngineConfig{
		EvaluationInterval: time.Second * 30,
		MaxHistorySize:     1000,
	}, metricProvider)
	alertEngine.AddNotificationSender(alerts.NewConsoleNotificationSender())

	sloMonitor := slo.NewSLOMonitor(slo.MonitorConfig{
		EvaluationInterval: time.Minute,
		MaxRecordSize:      10000,
	}, metricProvider)

	profiler := profiling.NewProfiler(profiling.ProfilerConfig{
		ServiceName:     "observability-platform",
		InstanceID:      "instance-1",
		ProfileInterval: time.Minute * 5,
		ProfileDuration: time.Second * 10,
		MaxSamples:      100,
		SampleRate:      100,
		EnabledProfiles: []models.ProfileType{
			models.ProfileTypeCPU,
			models.ProfileTypeHeap,
		},
	})

	anomalyDetector := anomaly.NewAnomalyDetector(anomaly.DetectorConfig{
		MaxHistoryPoints:   1000,
		MaxAnomalies:       1000,
		AutoUpdateBaseline: true,
	})

	return &ObservabilityPlatform{
		traceCollector:  traceCollector,
		topologyBuilder: topologyBuilder,
		metricsService:  metricsService,
		logPipeline:     logPipeline,
		alertEngine:     alertEngine,
		sloMonitor:      sloMonitor,
		profiler:        profiler,
		anomalyDetector: anomalyDetector,
		metricProvider:  metricProvider,
	}
}

type TopologyExporter struct {
	builder *topology.TopologyBuilder
}

func (e *TopologyExporter) Export(trace *models.Trace) error {
	e.builder.ProcessTrace(trace)
	return nil
}

func (p *ObservabilityPlatform) Start() {
	p.traceCollector.Start()
	p.logPipeline.Start()
	p.alertEngine.Start()
	p.sloMonitor.Start()
	p.profiler.Start()
	log.Println("Observability Platform started successfully")
}

func (p *ObservabilityPlatform) Stop() {
	p.traceCollector.Stop()
	p.logPipeline.Stop()
	p.alertEngine.Stop()
	p.sloMonitor.Stop()
	p.profiler.Stop()
	p.metricsService.Close()
	log.Println("Observability Platform stopped")
}

func (p *ObservabilityPlatform) setupDemoData() {
	p.metricProvider.SetMetric("requests_total", 1000)
	p.metricProvider.SetMetric("requests_errors_total", 50)
	p.metricProvider.SetMetric("requests_fast_total", 850)
	p.metricProvider.SetMetric("cpu_usage", 45.5)
	p.metricProvider.SetMetric("memory_usage", 62.3)

	availabilitySLO := slo.NewAvailabilitySLO(
		"api-availability",
		"api-gateway",
		0.999,
		time.Hour*24*30,
	)
	p.sloMonitor.AddSLO(availabilitySLO)
	p.sloMonitor.AddAlertPolicy(slo.NewDefaultAlertPolicy(availabilitySLO.ID))

	latencySLO := slo.NewLatencySLO(
		"api-latency",
		"api-gateway",
		0.95,
		time.Hour*24*30,
		500,
	)
	p.sloMonitor.AddSLO(latencySLO)
	p.sloMonitor.AddAlertPolicy(slo.NewDefaultAlertPolicy(latencySLO.ID))

	highCpuRule := &models.AlertRule{
		Name:        "High CPU Usage",
		Description: "CPU usage is above 80%",
		Expression:  "cpu_usage > 80",
		Severity:    models.SeverityWarning,
		Enabled:     true,
	}
	p.alertEngine.AddRule(highCpuRule)

	highErrorRateRule := &models.AlertRule{
		Name:        "High Error Rate",
		Description: "Error rate is above 5%",
		Expression:  "requests_errors_total / requests_total > 0.05",
		Severity:    models.SeverityFatal,
		Enabled:     true,
	}
	p.alertEngine.AddRule(highErrorRateRule)

	for i := 0; i < 50; i++ {
		traceID := trace.GenerateTraceID()
		now := time.Now().Add(-time.Duration(i) * time.Second)

		rootSpan := &models.Span{
			TraceID:     traceID,
			SpanID:      trace.GenerateSpanID(),
			Name:        "handle_request",
			Kind:        models.SpanKindServer,
			StartTime:   now,
			EndTime:     now.Add(time.Millisecond * 150),
			Duration:    time.Millisecond * 150,
			ServiceName: "api-gateway",
			Sampled:     true,
			Status:      models.SpanStatus{Code: 0},
		}

		dbSpan := &models.Span{
			TraceID:      traceID,
			SpanID:       trace.GenerateSpanID(),
			ParentSpanID: rootSpan.SpanID,
			Name:         "query_database",
			Kind:         models.SpanKindClient,
			StartTime:    now.Add(time.Millisecond * 20),
			EndTime:      now.Add(time.Millisecond * 100),
			Duration:     time.Millisecond * 80,
			ServiceName:  "postgres",
			Sampled:      true,
			Status:       models.SpanStatus{Code: 0},
		}

		cacheSpan := &models.Span{
			TraceID:      traceID,
			SpanID:       trace.GenerateSpanID(),
			ParentSpanID: rootSpan.SpanID,
			Name:         "cache_get",
			Kind:         models.SpanKindClient,
			StartTime:    now.Add(time.Millisecond * 10),
			EndTime:      now.Add(time.Millisecond * 15),
			Duration:     time.Millisecond * 5,
			ServiceName:  "redis",
			Sampled:      true,
			Status:       models.SpanStatus{Code: 0},
		}

		p.traceCollector.ReceiveSpan(rootSpan)
		p.traceCollector.ReceiveSpan(dbSpan)
		p.traceCollector.ReceiveSpan(cacheSpan)
	}

	p.logPipeline.Ingest(&models.LogEntry{
		Timestamp:   time.Now(),
		Severity:    models.SeverityInfo,
		Message:     "System started successfully",
		ServiceName: "observability-platform",
	})

	for i := 0; i < 100; i++ {
		value := 50.0 + float64(i)*0.5
		if i%10 == 0 {
			value = 50.0 + float64(i)*0.5 + 20.0
		}
		p.anomalyDetector.AddDataPoint("cpu_usage", nil, value, time.Now().Add(-time.Duration(100-i)*time.Second))
	}
}

func (p *ObservabilityPlatform) printStatus() {
	fmt.Println("\n=== Observability Platform Status ===")

	topology := p.topologyBuilder.BuildTopology()
	fmt.Printf("\nService Topology:\n")
	fmt.Printf("  Nodes: %d\n", len(topology.Nodes))
	fmt.Printf("  Edges: %d\n", len(topology.Edges))
	for _, node := range topology.Nodes {
		fmt.Printf("    - %s\n", node.ServiceName)
	}
	for _, edge := range topology.Edges {
		fmt.Printf("    %s -> %s (calls: %d, avg latency: %v)\n",
			edge.FromService, edge.ToService, edge.CallCount, edge.AvgLatency)
	}

	fmt.Printf("\nSLO Status:\n")
	for _, slo := range p.sloMonitor.GetAllSLOs() {
		fmt.Printf("  - %s: SLI=%.4f, Target=%.4f, Budget Consumed=%.1f%%\n",
			slo.Name, slo.SLI.CurrentValue, slo.Target, slo.ErrorBudget.ConsumedPercentage)
	}

	fmt.Printf("\nActive Alerts: %d\n", len(p.alertEngine.GetActiveAlerts()))

	anomalies := p.anomalyDetector.GetAnomalies("", anomaly.AnomalySeverity(""), 5)
	fmt.Printf("\nRecent Anomalies: %d\n", len(anomalies))
	for _, a := range anomalies {
		fmt.Printf("  - [%s] %s - %s (score: %.2f)\n",
			a.Severity, a.Type, a.Description, a.Score)
	}

	fmt.Printf("\nMetrics Stats:\n")
	stats := p.metricsService.GetStats()
	for k, v := range stats {
		fmt.Printf("  %s: %v\n", k, v)
	}

	traceStats := p.traceCollector.GetStats()
	fmt.Printf("\nTrace Collector Stats:\n")
	fmt.Printf("  Received: %d, Sampled: %d, Exported: %d\n",
		traceStats.ReceivedSpans, traceStats.SampledSpans, traceStats.ExportedTraces)
}

func (p *ObservabilityPlatform) setupHTTPServer() *http.Server {
	mux := http.NewServeMux()

	mux.HandleFunc("/api/health", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]string{"status": "healthy"})
	})

	mux.HandleFunc("/api/topology", func(w http.ResponseWriter, r *http.Request) {
		topology := p.topologyBuilder.BuildTopology()
		json.NewEncoder(w).Encode(topology)
	})

	mux.HandleFunc("/api/slos", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(p.sloMonitor.GetAllSLOs())
	})

	mux.HandleFunc("/api/alerts", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(p.alertEngine.GetActiveAlerts())
	})

	mux.HandleFunc("/api/anomalies", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(p.anomalyDetector.GetAnomalies("", anomaly.AnomalySeverity(""), 20))
	})

	mux.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) {
		p.printStatus()
		w.WriteHeader(http.StatusOK)
	})

	server := &http.Server{
		Addr:    ":8080",
		Handler: mux,
	}

	go func() {
		log.Printf("HTTP server starting on :8080")
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Printf("HTTP server error: %v", err)
		}
	}()

	return server
}

func main() {
	fmt.Println("=== Observability Platform ===")
	fmt.Println("Starting all modules...")

	platform := NewObservabilityPlatform()
	platform.Start()
	platform.setupDemoData()

	time.Sleep(time.Second * 2)
	platform.printStatus()

	httpServer := platform.setupHTTPServer()

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

	fmt.Println("\nPlatform is running. Press Ctrl+C to stop.")
	fmt.Println("Available endpoints:")
	fmt.Println("  GET /api/health    - Health check")
	fmt.Println("  GET /api/topology  - Service topology")
	fmt.Println("  GET /api/slos      - SLO status")
	fmt.Println("  GET /api/alerts    - Active alerts")
	fmt.Println("  GET /api/anomalies - Recent anomalies")
	fmt.Println("  GET /api/status    - Print status to console")

	<-sigChan
	fmt.Println("\nShutting down...")

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	httpServer.Shutdown(ctx)

	platform.Stop()
	fmt.Println("Goodbye!")
}
