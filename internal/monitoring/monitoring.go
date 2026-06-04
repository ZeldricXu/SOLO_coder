package monitoring

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"model-inference-platform/internal/pkg/database"
	"model-inference-platform/internal/pkg/redis"
	"sort"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"go.uber.org/zap"
)

type InferenceLogEntry struct {
	RequestID      string                 `json:"request_id"`
	TraceID        string                 `json:"trace_id"`
	ModelName      string                 `json:"model_name"`
	Version        string                 `json:"version"`
	Namespace      string                 `json:"namespace"`
	InstanceID     string                 `json:"instance_id"`
	InputShapes    map[string][]int64     `json:"input_shapes"`
	BatchSize      int                    `json:"batch_size"`
	LatencyMs      int64                  `json:"latency_ms"`
	GPUMemoryMB    int64                  `json:"gpu_memory_mb"`
	StatusCode     int                    `json:"status_code"`
	ErrorMessage   string                 `json:"error_message,omitempty"`
	Outputs        map[string]interface{} `json:"outputs,omitempty"`
	GroundTruth    interface{}            `json:"ground_truth,omitempty"`
	PredictedLabel interface{}            `json:"predicted_label,omitempty"`
	CustomLabels   map[string]string      `json:"custom_labels,omitempty"`
	CreatedAt      time.Time              `json:"created_at"`
}

type BusinessMetrics struct {
	ModelName   string  `json:"model_name"`
	Version     string  `json:"version"`
	Namespace   string  `json:"namespace"`
	Accuracy    float64 `json:"accuracy"`
	Precision   float64 `json:"precision"`
	Recall      float64 `json:"recall"`
	F1Score     float64 `json:"f1_score"`
	MAE         float64 `json:"mae,omitempty"`
	MSE         float64 `json:"mse,omitempty"`
	RMSE        float64 `json:"rmse,omitempty"`
	SampleCount int64   `json:"sample_count"`
	WindowStart time.Time `json:"window_start"`
	WindowEnd   time.Time `json:"window_end"`
}

type ClassificationStats struct {
	TruePositives  map[string]int64
	FalsePositives map[string]int64
	TrueNegatives  map[string]int64
	FalseNegatives map[string]int64
	TotalSamples   int64
	CorrectCount   int64
}

type RegressionStats struct {
	TotalSamples int64
	AbsErrorSum  float64
	SqErrorSum   float64
}

type Monitor struct {
	db          database.DB
	redisClient redis.RedisClient
	logger      *zap.Logger

	requestCounter     *prometheus.CounterVec
	latencyHistogram *prometheus.HistogramVec
	gpuUsageGauge   *prometheus.GaugeVec
	errorCounter   *prometheus.CounterVec
	batchSizeHistogram *prometheus.HistogramVec
	accuracyGauge  *prometheus.GaugeVec
	precisionGauge *prometheus.GaugeVec
	recallGauge    *prometheus.GaugeVec
	f1Gauge        *prometheus.GaugeVec
	maeGauge       *prometheus.GaugeVec
	mseGauge       *prometheus.GaugeVec

	logBuffer  []*InferenceLogEntry
	bufferMu sync.Mutex
	bufferSize int

	classificationStats map[string]*ClassificationStats
	regressionStats     map[string]*RegressionStats
	metricsMu           sync.RWMutex
	metricsWindow       time.Duration

	traces   map[string]*Trace
	tracesMu sync.RWMutex

	stopCh chan struct{}
	wg     sync.WaitGroup
}

type Trace struct {
	TraceID    string             `json:"trace_id"`
	Spans      []*InferenceLogEntry `json:"spans"`
	StartTime  time.Time          `json:"start_time"`
	EndTime    time.Time          `json:"end_time"`
	TotalLatencyMs int64        `json:"total_latency_ms"`
}

func NewMonitor(db database.DB, redisClient redis.RedisClient, logger *zap.Logger) *Monitor {
	return &Monitor{
		db:          db,
		redisClient: redisClient,
		logger:      logger,
		requestCounter: promauto.NewCounterVec(
			prometheus.CounterOpts{
				Name: "inference_requests_total",
				Help: "Total number of inference requests",
			},
			[]string{"model", "version", "namespace", "status"},
		),
		latencyHistogram: promauto.NewHistogramVec(
			prometheus.HistogramOpts{
				Name:    "inference_latency_ms",
				Help:    "Inference latency in milliseconds",
				Buckets: []float64{1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000},
			},
			[]string{"model", "version", "namespace"},
		),
		gpuUsageGauge: promauto.NewGaugeVec(
			prometheus.GaugeOpts{
				Name: "gpu_memory_usage_mb",
				Help: "GPU memory usage in MB",
			},
			[]string{"model", "version", "instance_id"},
		),
		errorCounter: promauto.NewCounterVec(
			prometheus.CounterOpts{
				Name: "inference_errors_total",
				Help: "Total number of inference errors",
			},
			[]string{"model", "version", "namespace", "error_type"},
		),
		batchSizeHistogram: promauto.NewHistogramVec(
			prometheus.HistogramOpts{
				Name:    "inference_batch_size",
				Help:    "Batch size for inference requests",
				Buckets: []float64{1, 2, 4, 8, 16, 32, 64, 128},
			},
			[]string{"model", "version"},
		),
		accuracyGauge: promauto.NewGaugeVec(
			prometheus.GaugeOpts{
				Name: "business_accuracy",
				Help: "Model prediction accuracy",
			},
			[]string{"model", "version", "namespace"},
		),
		precisionGauge: promauto.NewGaugeVec(
			prometheus.GaugeOpts{
				Name: "business_precision",
				Help: "Model prediction precision",
			},
			[]string{"model", "version", "namespace"},
		),
		recallGauge: promauto.NewGaugeVec(
			prometheus.GaugeOpts{
				Name: "business_recall",
				Help: "Model prediction recall",
			},
			[]string{"model", "version", "namespace"},
		),
		f1Gauge: promauto.NewGaugeVec(
			prometheus.GaugeOpts{
				Name: "business_f1_score",
				Help: "Model prediction F1 score",
			},
			[]string{"model", "version", "namespace"},
		),
		maeGauge: promauto.NewGaugeVec(
			prometheus.GaugeOpts{
				Name: "business_mae",
				Help: "Mean absolute error for regression models",
			},
			[]string{"model", "version", "namespace"},
		),
		mseGauge: promauto.NewGaugeVec(
			prometheus.GaugeOpts{
				Name: "business_mse",
				Help: "Mean squared error for regression models",
			},
			[]string{"model", "version", "namespace"},
		),
		logBuffer:           make([]*InferenceLogEntry, 0, 1000),
		bufferSize:          1000,
		classificationStats: make(map[string]*ClassificationStats),
		regressionStats:     make(map[string]*RegressionStats),
		metricsWindow:       5 * time.Minute,
		traces:              make(map[string]*Trace),
		stopCh:              make(chan struct{}),
	}
}

func (m *Monitor) Start(ctx context.Context) error {
	m.wg.Add(3)
	go m.logFlusher(ctx)
	go m.traceCleaner(ctx)
	go m.businessMetricsCollector(ctx)
	m.logger.Info("Monitoring started")
	return nil
}

func (m *Monitor) Stop() {
	close(m.stopCh)
	m.wg.Wait()
	m.logger.Info("Monitoring stopped")
}

func (m *Monitor) RecordInference(log *InferenceLogEntry) {
	statusLabel := "success"
	if log.StatusCode >= 400 {
		statusLabel = "error"
	}

	m.requestCounter.WithLabelValues(log.ModelName, log.Version, log.Namespace, statusLabel).Inc()
	m.latencyHistogram.WithLabelValues(log.ModelName, log.Version, log.Namespace).Observe(float64(log.LatencyMs))
	m.batchSizeHistogram.WithLabelValues(log.ModelName, log.Version).Observe(float64(log.BatchSize))

	if log.GPUMemoryMB > 0 {
		m.gpuUsageGauge.WithLabelValues(log.ModelName, log.Version, log.InstanceID).Set(float64(log.GPUMemoryMB))
	}

	if log.ErrorMessage != "" {
		errorType := "unknown"
		if log.StatusCode == 503 {
			errorType = "timeout"
		} else if log.StatusCode == 500 {
			errorType = "internal"
		}
		m.errorCounter.WithLabelValues(log.ModelName, log.Version, log.Namespace, errorType).Inc()
	}

	log.CreatedAt = time.Now()

	if log.GroundTruth != nil && log.PredictedLabel != nil {
		m.updateBusinessMetrics(log)
	}

	m.bufferMu.Lock()
	m.logBuffer = append(m.logBuffer, log)
	m.bufferMu.Unlock()

	if log.TraceID != "" {
		m.tracesMu.Lock()
		trace, ok := m.traces[log.TraceID]
		if !ok {
			trace = &Trace{
				TraceID:   log.TraceID,
				Spans:     make([]*InferenceLogEntry, 0),
				StartTime: log.CreatedAt,
			}
			m.traces[log.TraceID] = trace
		}
		trace.Spans = append(trace.Spans, log)
		if log.CreatedAt.After(trace.EndTime) {
			trace.EndTime = log.CreatedAt
			trace.TotalLatencyMs = trace.EndTime.Sub(trace.StartTime).Milliseconds()
		}
		m.tracesMu.Unlock()
	}
}

func (m *Monitor) updateBusinessMetrics(log *InferenceLogEntry) {
	key := fmt.Sprintf("%s:%s:%s", log.Namespace, log.ModelName, log.Version)

	groundTruthStr := fmt.Sprintf("%v", log.GroundTruth)
	predictedStr := fmt.Sprintf("%v", log.PredictedLabel)

	gtFloat, gtIsNumber := toFloat64(log.GroundTruth)
	predFloat, predIsNumber := toFloat64(log.PredictedLabel)

	if gtIsNumber && predIsNumber {
		m.metricsMu.Lock()
		if _, ok := m.regressionStats[key]; !ok {
			m.regressionStats[key] = &RegressionStats{}
		}
		stats := m.regressionStats[key]
		stats.TotalSamples++
		error := math.Abs(gtFloat - predFloat)
		stats.AbsErrorSum += error
		stats.SqErrorSum += error * error
		m.metricsMu.Unlock()
	} else {
		m.metricsMu.Lock()
		if _, ok := m.classificationStats[key]; !ok {
			m.classificationStats[key] = &ClassificationStats{
				TruePositives:  make(map[string]int64),
				FalsePositives: make(map[string]int64),
				TrueNegatives:  make(map[string]int64),
				FalseNegatives: make(map[string]int64),
			}
		}
		stats := m.classificationStats[key]
		stats.TotalSamples++

		if groundTruthStr == predictedStr {
			stats.CorrectCount++
			stats.TruePositives[predictedStr]++
		} else {
			stats.FalsePositives[predictedStr]++
			stats.FalseNegatives[groundTruthStr]++
		}
		m.metricsMu.Unlock()
	}
}

func (m *Monitor) businessMetricsCollector(ctx context.Context) {
	defer m.wg.Done()

	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-m.stopCh:
			return
		case <-ticker.C:
			m.calculateAndExposeMetrics(ctx)
		}
	}
}

func (m *Monitor) calculateAndExposeMetrics(ctx context.Context) {
	m.metricsMu.RLock()
	classStats := make(map[string]*ClassificationStats)
	for k, v := range m.classificationStats {
		classStats[k] = v
	}
	regStats := make(map[string]*RegressionStats)
	for k, v := range m.regressionStats {
		regStats[k] = v
	}
	m.metricsMu.RUnlock()

	for key, stats := range classStats {
		parts := splitKey(key)
		if len(parts) != 3 {
			continue
		}
		namespace, modelName, version := parts[0], parts[1], parts[2]

		metrics := m.calculateClassificationMetrics(stats)

		m.accuracyGauge.WithLabelValues(modelName, version, namespace).Set(metrics.Accuracy)
		m.precisionGauge.WithLabelValues(modelName, version, namespace).Set(metrics.Precision)
		m.recallGauge.WithLabelValues(modelName, version, namespace).Set(metrics.Recall)
		m.f1Gauge.WithLabelValues(modelName, version, namespace).Set(metrics.F1Score)

		m.persistBusinessMetrics(ctx, namespace, modelName, version, metrics)
	}

	for key, stats := range regStats {
		parts := splitKey(key)
		if len(parts) != 3 {
			continue
		}
		namespace, modelName, version := parts[0], parts[1], parts[2]

		metrics := m.calculateRegressionMetrics(stats)

		m.maeGauge.WithLabelValues(modelName, version, namespace).Set(metrics.MAE)
		m.mseGauge.WithLabelValues(modelName, version, namespace).Set(metrics.MSE)

		m.persistBusinessMetrics(ctx, namespace, modelName, version, metrics)
	}
}

func (m *Monitor) calculateClassificationMetrics(stats *ClassificationStats) *BusinessMetrics {
	if stats.TotalSamples == 0 {
		return &BusinessMetrics{}
	}

	accuracy := float64(stats.CorrectCount) / float64(stats.TotalSamples)

	var precisionSum, recallSum float64
	var labelCount int

	allLabels := make(map[string]bool)
	for label := range stats.TruePositives {
		allLabels[label] = true
	}
	for label := range stats.FalsePositives {
		allLabels[label] = true
	}
	for label := range stats.FalseNegatives {
		allLabels[label] = true
	}

	labels := make([]string, 0, len(allLabels))
	for label := range allLabels {
		labels = append(labels, label)
	}
	sort.Strings(labels)

	for _, label := range labels {
		tp := stats.TruePositives[label]
		fp := stats.FalsePositives[label]
		fn := stats.FalseNegatives[label]

		var precision, recall float64
		if tp+fp > 0 {
			precision = float64(tp) / float64(tp+fp)
		}
		if tp+fn > 0 {
			recall = float64(tp) / float64(tp+fn)
		}

		precisionSum += precision
		recallSum += recall
		labelCount++
	}

	var macroPrecision, macroRecall, f1Score float64
	if labelCount > 0 {
		macroPrecision = precisionSum / float64(labelCount)
		macroRecall = recallSum / float64(labelCount)
		if macroPrecision+macroRecall > 0 {
			f1Score = 2 * macroPrecision * macroRecall / (macroPrecision + macroRecall)
		}
	}

	return &BusinessMetrics{
		Accuracy:    accuracy,
		Precision:   macroPrecision,
		Recall:      macroRecall,
		F1Score:     f1Score,
		SampleCount: stats.TotalSamples,
	}
}

func (m *Monitor) calculateRegressionMetrics(stats *RegressionStats) *BusinessMetrics {
	if stats.TotalSamples == 0 {
		return &BusinessMetrics{}
	}

	mae := stats.AbsErrorSum / float64(stats.TotalSamples)
	mse := stats.SqErrorSum / float64(stats.TotalSamples)
	rmse := math.Sqrt(mse)

	return &BusinessMetrics{
		MAE:         mae,
		MSE:         mse,
		RMSE:        rmse,
		SampleCount: stats.TotalSamples,
	}
}

func (m *Monitor) persistBusinessMetrics(ctx context.Context, namespace, modelName, version string, metrics *BusinessMetrics) {
	metricsJSON, _ := json.Marshal(metrics)

	query := `
		INSERT INTO model_business_metrics 
		(namespace, model_name, version, accuracy, precision, recall, f1_score, 
		 mae, mse, rmse, sample_count, metrics)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
	`
	_, err := m.db.Exec(ctx, query,
		namespace, modelName, version,
		metrics.Accuracy, metrics.Precision, metrics.Recall, metrics.F1Score,
		metrics.MAE, metrics.MSE, metrics.RMSE,
		metrics.SampleCount, metricsJSON)
	if err != nil {
		m.logger.Warn("Failed to persist business metrics",
			zap.String("model", modelName),
			zap.Error(err))
	}
}

func (m *Monitor) GetBusinessMetrics(ctx context.Context, namespace, modelName, version string) (*BusinessMetrics, error) {
	key := fmt.Sprintf("%s:%s:%s", namespace, modelName, version)

	m.metricsMu.RLock()
	classStats, classOk := m.classificationStats[key]
	regStats, regOk := m.regressionStats[key]
	m.metricsMu.RUnlock()

	if classOk {
		return m.calculateClassificationMetrics(classStats), nil
	}
	if regOk {
		return m.calculateRegressionMetrics(regStats), nil
	}

	return nil, fmt.Errorf("no metrics found for model %s:%s", modelName, version)
}

func (m *Monitor) ResetBusinessMetrics(namespace, modelName, version string) {
	key := fmt.Sprintf("%s:%s:%s", namespace, modelName, version)

	m.metricsMu.Lock()
	delete(m.classificationStats, key)
	delete(m.regressionStats, key)
	m.metricsMu.Unlock()

	m.logger.Info("Business metrics reset",
		zap.String("namespace", namespace),
		zap.String("model", modelName),
		zap.String("version", version))
}

func toFloat64(v interface{}) (float64, bool) {
	switch val := v.(type) {
	case int:
		return float64(val), true
	case int32:
		return float64(val), true
	case int64:
		return float64(val), true
	case float32:
		return float64(val), true
	case float64:
		return val, true
	case string:
		var result float64
		if _, err := fmt.Sscanf(val, "%f", &result); err == nil {
			return result, true
		}
	}
	return 0, false
}

func splitKey(key string) []string {
	parts := make([]string, 0, 3)
	current := ""
	for _, c := range key {
		if c == ':' {
			parts = append(parts, current)
			current = ""
		} else {
			current += string(c)
		}
	}
	if current != "" {
		parts = append(parts, current)
	}
	return parts
}

func (m *Monitor) logFlusher(ctx context.Context) {
	defer m.wg.Done()

	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-m.stopCh:
			return
		case <-ticker.C:
			m.flushLogs(ctx)
		}
	}
}

func (m *Monitor) flushLogs(ctx context.Context) {
	m.bufferMu.Lock()
	if len(m.logBuffer) == 0 {
		m.bufferMu.Unlock()
		return
	}

	logs := m.logBuffer
	m.logBuffer = make([]*InferenceLogEntry, 0, m.bufferSize)
	m.bufferMu.Unlock()

	for _, log := range logs {
		inputShapesJSON, _ := json.Marshal(log.InputShapes)
		outputsJSON, _ := json.Marshal(log.Outputs)

		query := `
			INSERT INTO inference_logs (request_id, trace_id, model_name, version, namespace,
				instance_id, input_shapes, batch_size, latency_ms, gpu_memory_used_mb,
				status_code, error_message, outputs, created_at)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
		`
		_, err := m.db.Exec(ctx, query, log.RequestID, log.TraceID, log.ModelName, log.Version,
			log.Namespace, log.InstanceID, inputShapesJSON, log.BatchSize, log.LatencyMs,
			log.GPUMemoryMB, log.StatusCode, log.ErrorMessage, outputsJSON, log.CreatedAt)
		if err != nil {
			m.logger.Warn("Failed to insert inference log", zap.Error(err))
		}
	}

	m.logger.Debug("Flushed inference logs", zap.Int("count", len(logs)))
}

func (m *Monitor) traceCleaner(ctx context.Context) {
	defer m.wg.Done()

	ticker := time.NewTicker(10 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-m.stopCh:
			return
		case <-ticker.C:
			m.cleanOldTraces()
		}
	}
}

func (m *Monitor) cleanOldTraces() {
	cutoff := time.Now().Add(-1 * time.Hour)

	m.tracesMu.Lock()
	for id, trace := range m.traces {
		if trace.EndTime.Before(cutoff) {
			delete(m.traces, id)
		}
	}
	m.tracesMu.Unlock()
}

func (m *Monitor) GetTrace(traceID string) (*Trace, bool) {
	m.tracesMu.RLock()
	defer m.tracesMu.RUnlock()

	trace, ok := m.traces[traceID]
	return trace, ok
}

func (m *Monitor) GetMetrics(modelName, version, namespace string, startTime, endTime time.Time) (*AggregatedMetrics, error) {
	query := `
		SELECT
			COUNT(*) as total_requests,
			COUNT(CASE WHEN status_code < 400 THEN 1 END) as success_count,
			PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY latency_ms) as p50_latency,
			PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY latency_ms) as p95_latency,
			PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY latency_ms) as p99_latency,
			AVG(gpu_memory_used_mb) as avg_gpu_memory
		FROM inference_logs
		WHERE model_name = $1 AND version = $2 AND namespace = $3
		  AND created_at BETWEEN $4 AND $5
	`

	metrics := &AggregatedMetrics{}
	err := m.db.QueryRow(context.Background(), query, modelName, version, namespace, startTime, endTime).Scan(
		&metrics.TotalRequests, &metrics.SuccessCount, &metrics.P50LatencyMs,
		&metrics.P95LatencyMs, &metrics.P99LatencyMs, &metrics.AvgGPUMemoryMB,
	)
	if err != nil {
		return nil, err
	}

	if metrics.TotalRequests > 0 {
		metrics.ErrorRate = float64(metrics.TotalRequests-metrics.SuccessCount) / float64(metrics.TotalRequests)
	}

	return metrics, nil
}

type AggregatedMetrics struct {
	TotalRequests   int64   `json:"total_requests"`
	SuccessCount    int64   `json:"success_count"`
	ErrorRate       float64 `json:"error_rate"`
	P50LatencyMs   float64 `json:"p50_latency_ms"`
	P95LatencyMs   float64 `json:"p95_latency_ms"`
	P99LatencyMs   float64 `json:"p99_latency_ms"`
	AvgGPUMemoryMB float64 `json:"avg_gpu_memory_mb"`
}
