package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"log-pipeline/internal/alert"
	"log-pipeline/internal/anomaly"
	"log-pipeline/internal/ingestor"
	"log-pipeline/internal/metrics"
	"log-pipeline/internal/query"
	"log-pipeline/internal/storage"
	"log-pipeline/internal/visualization"
	"log-pipeline/internal/windowing"
	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
)

func main() {
	mode := flag.String("mode", "run", "Operation mode: run, export-dashboard, export-rules")
	_ = flag.String("config", "", "Path to config file")
	flag.Parse()

	cfg := config.DefaultConfig()

	switch *mode {
	case "run":
		runPipeline(cfg)
	case "export-dashboard":
		exportDashboard()
	case "export-rules":
		exportRules()
	default:
		log.Fatalf("Unknown mode: %s", *mode)
	}
}

func runPipeline(cfg *config.Config) {
	fmt.Println("=============================================")
	fmt.Println("  Real-time Log Analysis Pipeline Starting")
	fmt.Println("=============================================")

	chStore, err := storage.NewClickHouseStore(&cfg.Storage.ClickHouse)
	if err != nil {
		log.Printf("Warning: Failed to connect to ClickHouse: %v", err)
		log.Println("Continuing without ClickHouse storage...")
	} else {
		defer chStore.Close()
		fmt.Println("✓ ClickHouse connected")
	}

	redisStore, err := storage.NewRedisStore(&cfg.Storage.Redis)
	if err != nil {
		log.Printf("Warning: Failed to connect to Redis: %v", err)
		log.Println("Continuing without Redis storage...")
	} else {
		defer redisStore.Close()
		fmt.Println("✓ Redis connected")
	}

	logIngestor := ingestor.NewIngestor(&cfg.Ingestor)
	if err := logIngestor.Start(); err != nil {
		log.Fatalf("Failed to start ingestor: %v", err)
	}
	defer logIngestor.Stop()
	fmt.Println("✓ Log Ingestor started (TCP/UDP/HTTP)")

	windowEngine := windowing.NewWindowEngine(&cfg.Windowing)
	windowEngine.Start(logIngestor.Logs())
	defer windowEngine.Stop()
	fmt.Println("✓ Stream Windowing Engine started")

	anomalyDetector := anomaly.NewAnomalyDetector(&cfg.Anomaly)
	anomalyDetector.Start(windowEngine.Aggregates())
	defer anomalyDetector.Stop()
	fmt.Println("✓ Anomaly Detector started")

	logsCopy := make(chan *models.LogEntry, cfg.Ingestor.BufferSize)
	go func() {
		for logEntry := range logIngestor.Logs() {
			select {
			case logsCopy <- logEntry:
			default:
			}
		}
	}()

	metricsAggregator := metrics.NewMetricsAggregator(&cfg.Metrics, chStore)
	metricsAggregator.Start(
		windowEngine.Aggregates(),
		anomalyDetector.Anomalies(),
		windowEngine.Alerts(),
		logsCopy,
	)
	defer metricsAggregator.Stop()
	fmt.Println("✓ Metrics Aggregator started")

	queryEngine := query.NewQueryEngine(&cfg.QueryAPI, chStore)
	if err := queryEngine.Start(); err != nil {
		log.Printf("Warning: Failed to start Query API: %v", err)
	}
	fmt.Println("✓ Query API server started")

	var alertManager *alert.AlertManager
	if redisStore != nil {
		alertManager = alert.NewAlertManager(&cfg.AlertManager, redisStore)
		alertManager.Start(windowEngine.Alerts())
		defer alertManager.Stop()
		fmt.Println("✓ Alert Manager started")
	}

	fmt.Println("=============================================")
	fmt.Println("  Pipeline started successfully!")
	fmt.Println("=============================================")
	printEndpoints(cfg)

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	fmt.Println("\nShutting down pipeline...")
	fmt.Println("Pipeline stopped gracefully")
}

func exportDashboard() {
	fmt.Println("Generating Grafana Dashboard JSON...")

	cfg := visualization.DashboardConfig{
		Title:         "Log Pipeline Monitoring",
		PrometheusURL: "http://localhost:9090",
		ClickHouseURL: "http://localhost:8123",
	}

	dashboard, err := visualization.GenerateGrafanaDashboard(cfg)
	if err != nil {
		log.Fatalf("Failed to generate dashboard: %v", err)
	}

	filename := "grafana-dashboard.json"
	if err := os.WriteFile(filename, []byte(dashboard), 0644); err != nil {
		log.Fatalf("Failed to write dashboard: %v", err)
	}

	fmt.Printf("✓ Dashboard exported to: %s\n\n", filename)
	visualization.PrintExportInstructions()
}

func exportRules() {
	fmt.Println("Generating Prometheus Alert Rules...")

	rules := visualization.GenerateAlertRules()
	filename := "prometheus-alerts.yml"

	if err := os.WriteFile(filename, []byte(rules), 0644); err != nil {
		log.Fatalf("Failed to write rules: %v", err)
	}

	fmt.Printf("✓ Alert rules exported to: %s\n", filename)
}

func printEndpoints(cfg *config.Config) {
	fmt.Println("\nAvailable Endpoints:")
	fmt.Printf("  - HTTP Ingestor:    http://localhost:%d/api/v1/logs (POST)\n", cfg.Ingestor.HTTPPort)
	fmt.Printf("  - TCP Ingestor:     localhost:%d\n", cfg.Ingestor.TCPPort)
	fmt.Printf("  - UDP Ingestor:     localhost:%d\n", cfg.Ingestor.UDPPort)
	fmt.Printf("  - Prometheus:       http://localhost:%d/metrics\n", cfg.Metrics.PrometheusPort)
	fmt.Printf("  - Query API:        http://localhost:%d/api/v1/query\n", cfg.QueryAPI.Port)
	fmt.Printf("  - Health Check:     http://localhost:%d/api/v1/health\n", cfg.Ingestor.HTTPPort)
	fmt.Println("\nPress Ctrl+C to stop")
}
