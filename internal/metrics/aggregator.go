package metrics

import (
	"context"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"log-pipeline/internal/storage"
	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
)

type MetricsAggregator struct {
	config           *config.MetricsConfig
	chStore          *storage.ClickHouseStore
	registry         *prometheus.Registry
	logCount         *prometheus.CounterVec
	logLevelCount    *prometheus.GaugeVec
	errorRate        *prometheus.GaugeVec
	anomalyCount     *prometheus.CounterVec
	alertCount       *prometheus.CounterVec
	mu               sync.Mutex
	ctx              context.Context
	cancel           context.CancelFunc
	wg               sync.WaitGroup
}

func NewMetricsAggregator(cfg *config.MetricsConfig, chStore *storage.ClickHouseStore) *MetricsAggregator {
	ctx, cancel := context.WithCancel(context.Background())

	reg := prometheus.NewRegistry()

	agg := &MetricsAggregator{
		config:   cfg,
		chStore:  chStore,
		registry: reg,
		ctx:      ctx,
		cancel:   cancel,
	}

	agg.initMetrics()
	return agg
}

func (ma *MetricsAggregator) initMetrics() {
	ma.logCount = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "log_pipeline_logs_total",
			Help: "Total number of logs processed",
		},
		[]string{"source", "host", "level"},
	)

	ma.logLevelCount = prometheus.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "log_pipeline_logs_by_level",
			Help: "Number of logs by level in current window",
		},
		[]string{"window_type", "key", "level"},
	)

	ma.errorRate = prometheus.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "log_pipeline_error_rate",
			Help: "Error rate in current window",
		},
		[]string{"window_type", "key"},
	)

	ma.anomalyCount = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "log_pipeline_anomalies_total",
			Help: "Total number of anomalies detected",
		},
		[]string{"method", "metric_name"},
	)

	ma.alertCount = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "log_pipeline_alerts_total",
			Help: "Total number of alerts generated",
		},
		[]string{"alert_type", "severity"},
	)

	ma.registry.MustRegister(ma.logCount)
	ma.registry.MustRegister(ma.logLevelCount)
	ma.registry.MustRegister(ma.errorRate)
	ma.registry.MustRegister(ma.anomalyCount)
	ma.registry.MustRegister(ma.alertCount)
}

func (ma *MetricsAggregator) Start(
	aggChan <-chan *models.WindowAggregate,
	anomalyChan <-chan *models.AnomalyResult,
	alertChan <-chan *models.AlertEvent,
	logChan <-chan *models.LogEntry,
) {
	ma.wg.Add(4)
	go ma.processAggregates(aggChan)
	go ma.processAnomalies(anomalyChan)
	go ma.processAlerts(alertChan)
	go ma.processLogs(logChan)
	go ma.startHTTPServer()
}

func (ma *MetricsAggregator) Stop() {
	ma.cancel()
	ma.wg.Wait()
}

func (ma *MetricsAggregator) startHTTPServer() {
	mux := http.NewServeMux()
	mux.Handle("/metrics", promhttp.HandlerFor(ma.registry, promhttp.HandlerOpts{}))

	server := &http.Server{
		Addr:    fmt.Sprintf(":%d", ma.config.PrometheusPort),
		Handler: mux,
	}

	fmt.Printf("Prometheus metrics endpoint on port %d/metrics\n", ma.config.PrometheusPort)

	go func() {
		<-ma.ctx.Done()
		server.Shutdown(context.Background())
	}()

	if err := server.ListenAndServe(); err != http.ErrServerClosed {
		fmt.Printf("Prometheus server error: %v\n", err)
	}
}

func (ma *MetricsAggregator) processAggregates(aggChan <-chan *models.WindowAggregate) {
	defer ma.wg.Done()

	for {
		select {
		case <-ma.ctx.Done():
			return
		case agg, ok := <-aggChan:
			if !ok {
				return
			}
			ma.updateAggregateMetrics(agg)
			ma.saveToClickHouse(agg)
		}
	}
}

func (ma *MetricsAggregator) updateAggregateMetrics(agg *models.WindowAggregate) {
	ma.mu.Lock()
	defer ma.mu.Unlock()

	for level, count := range agg.LevelCounts {
		ma.logLevelCount.WithLabelValues(agg.WindowType, agg.Key, level).Set(float64(count))
	}

	total := agg.Count
	errorCount := agg.LevelCounts["ERROR"]
	if total > 0 {
		ma.errorRate.WithLabelValues(agg.WindowType, agg.Key).Set(float64(errorCount) / float64(total))
	}
}

func (ma *MetricsAggregator) saveToClickHouse(agg *models.WindowAggregate) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := ma.chStore.InsertAggregate(ctx, agg); err != nil {
		fmt.Printf("Failed to save aggregate to ClickHouse: %v\n", err)
	}
}

func (ma *MetricsAggregator) processAnomalies(anomalyChan <-chan *models.AnomalyResult) {
	defer ma.wg.Done()

	for {
		select {
		case <-ma.ctx.Done():
			return
		case anomaly, ok := <-anomalyChan:
			if !ok {
				return
			}
			if anomaly.IsAnomaly {
				ma.anomalyCount.WithLabelValues(anomaly.Method, anomaly.MetricName).Inc()
				ma.saveAnomalyToClickHouse(anomaly)
			}
		}
	}
}

func (ma *MetricsAggregator) saveAnomalyToClickHouse(anomaly *models.AnomalyResult) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := ma.chStore.InsertAnomaly(ctx, anomaly); err != nil {
		fmt.Printf("Failed to save anomaly to ClickHouse: %v\n", err)
	}
}

func (ma *MetricsAggregator) processAlerts(alertChan <-chan *models.AlertEvent) {
	defer ma.wg.Done()

	for {
		select {
		case <-ma.ctx.Done():
			return
		case alert, ok := <-alertChan:
			if !ok {
				return
			}
			ma.alertCount.WithLabelValues(alert.AlertType, alert.Severity).Inc()
			ma.saveAlertToClickHouse(alert)
		}
	}
}

func (ma *MetricsAggregator) saveAlertToClickHouse(alert *models.AlertEvent) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := ma.chStore.InsertAlert(ctx, alert); err != nil {
		fmt.Printf("Failed to save alert to ClickHouse: %v\n", err)
	}
}

func (ma *MetricsAggregator) processLogs(logChan <-chan *models.LogEntry) {
	defer ma.wg.Done()

	for {
		select {
		case <-ma.ctx.Done():
			return
		case log, ok := <-logChan:
			if !ok {
				return
			}
			level := log.Level
			if level == "" {
				level = "INFO"
			}
			ma.logCount.WithLabelValues(log.Source, log.Host, level).Inc()
			ma.saveLogToClickHouse(log)
		}
	}
}

func (ma *MetricsAggregator) saveLogToClickHouse(log *models.LogEntry) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := ma.chStore.InsertLog(ctx, log); err != nil {
		fmt.Printf("Failed to save log to ClickHouse: %v\n", err)
	}
}
