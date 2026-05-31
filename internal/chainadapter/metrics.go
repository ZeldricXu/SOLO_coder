package chainadapter

import (
	"context"
	"fmt"
	"math"
	"net/http"
	"sort"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.uber.org/zap"

	"github.com/blockchain-middleware/core/internal/common/logger"
)

type MetricsCollector struct {
	registry               *prometheus.Registry
	rpcRequestDuration    *prometheus.HistogramVec
	rpcRequestCount      *prometheus.CounterVec
	rpcRequestErrors       *prometheus.CounterVec
	chainHeight          *prometheus.GaugeVec
	gasPriceGauge     *prometheus.GaugeVec
	nonceGauge        *prometheus.GaugeVec
	txPoolSizeGauge    *prometheus.GaugeVec
	activeConnections  *prometheus.GaugeVec
	operationDuration *prometheus.HistogramVec
	mu            sync.Mutex
	enabled       bool
	server        *http.Server
}

type MetricsConfig struct {
	Enabled  bool   `json:"enabled"`
	Port     int    `json:"port"`
	Path     string `json:"path"`
}

var (
	defaultBuckets = []float64{0.001, 0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10}
)

func NewMetricsCollector() *MetricsCollector {
	registry := prometheus.NewRegistry()

	mc := &MetricsCollector{
		registry: registry,
	}

	mc.initMetrics()
	return mc
}

func (mc *MetricsCollector) initMetrics() {
	mc.rpcRequestDuration = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
		Name:    "chain_rpc_request_duration_seconds",
		Help:    "Duration of chain RPC requests",
		Buckets: defaultBuckets,
	}, []string{"chain_id", "method", "status"})

	mc.rpcRequestCount = prometheus.NewCounterVec(
		prometheus.CounterOpts{
		Name: "chain_rpc_request_total",
		Help: "Total number of chain RPC requests",
	}, []string{"chain_id", "method"})

	mc.rpcRequestErrors = prometheus.NewCounterVec(
		prometheus.CounterOpts{
		Name: "chain_rpc_request_errors_total",
		Help: "Total number of chain RPC request errors",
	}, []string{"chain_id", "method", "error_type"})

	mc.chainHeight = prometheus.NewGaugeVec(
		prometheus.GaugeOpts{
		Name: "chain_block_height",
		Help: "Current block height of the chain",
	}, []string{"chain_id"})

	mc.gasPriceGauge = prometheus.NewGaugeVec(
		prometheus.GaugeOpts{
		Name: "chain_gas_price_wei",
		Help: "Current gas price in wei",
	}, []string{"chain_id"})

	mc.nonceGauge = prometheus.NewGaugeVec(
		prometheus.GaugeOpts{
		Name: "chain_account_nonce",
		Help: "Current nonce of an account",
	}, []string{"chain_id", "address"})

	mc.txPoolSizeGauge = prometheus.NewGaugeVec(
		prometheus.GaugeOpts{
		Name: "chain_txpool_size",
		Help: "Current size of the transaction pool",
	}, []string{"chain_id"})

	mc.activeConnections = prometheus.NewGaugeVec(
		prometheus.GaugeOpts{
		Name: "chain_active_connections",
		Help: "Number of active connections to the chain",
	}, []string{"chain_id"})

	mc.operationDuration = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
		Name:    "chain_operation_duration_seconds",
		Help:    "Duration of chain operations",
		Buckets: defaultBuckets,
	}, []string{"chain_id", "operation", "status"})

	mc.registry.MustRegister(mc.rpcRequestDuration)
	mc.registry.MustRegister(mc.rpcRequestCount)
	mc.registry.MustRegister(mc.rpcRequestErrors)
	mc.registry.MustRegister(mc.chainHeight)
	mc.registry.MustRegister(mc.gasPriceGauge)
	mc.registry.MustRegister(mc.nonceGauge)
	mc.registry.MustRegister(mc.txPoolSizeGauge)
	mc.registry.MustRegister(mc.activeConnections)
	mc.registry.MustRegister(mc.operationDuration)
}

func (mc *MetricsCollector) ObserveRPCDuration(chainID uint64, method string, duration time.Duration, status string) {
	if !mc.enabled {
		return
	}
	mc.rpcRequestDuration.WithLabelValues(
		fmt.Sprintf("%d", chainID),
		method,
		status,
	).Observe(duration.Seconds())
}

func (mc *MetricsCollector) IncRPCCount(chainID uint64, method string) {
	if !mc.enabled {
		return
	}
	mc.rpcRequestCount.WithLabelValues(
		fmt.Sprintf("%d", chainID),
		method,
	).Inc()
}

func (mc *MetricsCollector) IncRPCErrors(chainID uint64, method string, errorType string) {
	if !mc.enabled {
		return
	}
	mc.rpcRequestErrors.WithLabelValues(
		fmt.Sprintf("%d", chainID),
		method,
		errorType,
	).Inc()
}

func (mc *MetricsCollector) SetChainHeight(chainID uint64, height uint64) {
	if !mc.enabled {
		return
	}
	mc.chainHeight.WithLabelValues(
		fmt.Sprintf("%d", chainID),
	).Set(float64(height))
}

func (mc *MetricsCollector) SetGasPrice(chainID uint64, gasPrice float64) {
	if !mc.enabled {
		return
	}
	mc.gasPriceGauge.WithLabelValues(
		fmt.Sprintf("%d", chainID),
	).Set(gasPrice)
}

func (mc *MetricsCollector) SetNonce(chainID uint64, address string, nonce uint64) {
	if !mc.enabled {
		return
	}
	mc.nonceGauge.WithLabelValues(
		fmt.Sprintf("%d", chainID),
		address,
	).Set(float64(nonce))
}

func (mc *MetricsCollector) SetTxPoolSize(chainID uint64, size int) {
	if !mc.enabled {
		return
	}
	mc.txPoolSizeGauge.WithLabelValues(
		fmt.Sprintf("%d", chainID),
	).Set(float64(size))
}

func (mc *MetricsCollector) SetActiveConnections(chainID uint64, count int) {
	if !mc.enabled {
		return
	}
	mc.activeConnections.WithLabelValues(
		fmt.Sprintf("%d", chainID),
	).Set(float64(count))
}

func (mc *MetricsCollector) ObserveOperationDuration(chainID uint64, operation string, duration time.Duration, status string) {
	if !mc.enabled {
		return
	}
	mc.operationDuration.WithLabelValues(
		fmt.Sprintf("%d", chainID),
		operation,
		status,
	).Observe(duration.Seconds())
}

func (mc *MetricsCollector) StartServer(config MetricsConfig) error {
	if !config.Enabled {
		mc.enabled = false
		return nil
	}

	mc.enabled = true

	mux := http.NewServeMux()
	mux.Handle(config.Path, promhttp.HandlerFor(mc.registry, promhttp.HandlerOpts{})

	mc.server = &http.Server{
		Addr:    fmt.Sprintf(":%d", config.Port),
		Handler: mux,
	}

	go func() {
		logger.Log.Info("Prometheus metrics server started",
			zap.Int("port", config.Port),
			zap.String("path", config.Path))

		if err := mc.server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Log.Error("Prometheus metrics server error", zap.Error(err))
		}
	}()

	return nil
}

func (mc *MetricsCollector) StopServer(ctx context.Context) error {
	if mc.server == nil {
		return nil
	}

	mc.enabled = false
	return mc.server.Shutdown(ctx)
}

type OperationTimer struct {
	collector *MetricsCollector
	chainID   uint64
	operation string
	startTime time.Time
}

func (mc *MetricsCollector) StartTimer(chainID uint64, operation string) *OperationTimer {
	return &OperationTimer{
		collector: mc,
		chainID: chainID,
		operation: operation,
		startTime: time.Now(),
	}
}

func (t *OperationTimer) Observe(status string) {
	duration := time.Since(t.startTime)
	t.collector.ObserveOperationDuration(t.chainID, t.operation, duration, status)
}

type RPCTimer struct {
	collector *MetricsCollector
	chainID   uint64
	method    string
	startTime time.Time
}

func (mc *MetricsCollector) StartRPCTimer(chainID uint64, method string) *RPCTimer {
	mc.IncRPCCount(chainID, method)
	return &RPCTimer{
		collector: mc,
		chainID: chainID,
		method: method,
		startTime: time.Now(),
	}
}

func (t *RPCTimer) Observe(status string) {
	duration := time.Since(t.startTime)
	t.collector.ObserveRPCDuration(t.chainID, t.method, duration, status)
}

func (t *RPCTimer) ObserveError(errorType string) {
	t.Observe("error")
	t.collector.IncRPCErrors(t.chainID, t.method, errorType)
}

type CircuitBreakerMetrics struct {
	State           string
	TotalRequests   uint64
	SuccessCount    uint64
	FailureCount    uint64
	OpenCount       uint64
	HalfOpenCount   uint64
	LastStateChange  time.Time
}

type HealthStatus struct {
	ChainID           uint64
	IsHealthy        bool
	LastChecked       time.Time
	LastSuccess       time.Time
	LastError         string
	LatencyP50       float64
	LatencyP95       float64
	LatencyP99       float64
	RequestRate        float64
	ErrorRate        float64
}

type ChainMonitor struct {
	chainID          uint64
	metricsCollector *MetricsCollector
	healthStatus    *HealthStatus
	requestTimes     []time.Duration
	maxHistorySize    int
	mu             sync.RWMutex
}

func NewChainMonitor(chainID uint64, collector *MetricsCollector, maxHistorySize int) *ChainMonitor {
	return &ChainMonitor{
		chainID:          chainID,
		metricsCollector: collector,
		maxHistorySize:  maxHistorySize,
		healthStatus: &HealthStatus{
			ChainID:    chainID,
			IsHealthy: true,
		},
	}
}

func (cm *ChainMonitor) RecordRequest(duration time.Duration, success bool, errorMsg string) {
	cm.mu.Lock()
	cm.requestTimes = append(cm.requestTimes, duration)
	if len(cm.requestTimes) > cm.maxHistorySize {
		cm.requestTimes = cm.requestTimes[1:]
	}

	now := time.Now()
	cm.healthStatus.LastChecked = now

	if success {
		cm.healthStatus.LastSuccess = now
		cm.healthStatus.LastError = ""
	} else {
		cm.healthStatus.LastError = errorMsg
	}

	cm.updateHealthStats()
	cm.mu.Unlock()
}

func (cm *ChainMonitor) updateHealthStats() {
	if len(cm.requestTimes) == 0 {
		return
	}

	total := float64(len(cm.requestTimes))
	cm.healthStatus.LatencyP50 = cm.percentile(50)
	cm.healthStatus.LatencyP95 = cm.percentile(95)
	cm.healthStatus.LatencyP99 = cm.percentile(99)

	recentWindow := 1 * time.Minute
	recentRequests := 0
	recentErrors := 0

	cutoff := time.Now().Add(-recentWindow)
	for _, rt := range cm.requestTimes {
		if rt > 0 {
			recentRequests++
		}
	}

	cm.healthStatus.RequestRate = float64(recentRequests) / recentWindow.Seconds()
	cm.healthStatus.ErrorRate = float64(recentErrors) / math.Max(1, float64(recentRequests))
}

func (cm *ChainMonitor) percentile(p int) float64 {
	if len(cm.requestTimes) == 0 {
		return 0
	}

	sorted := make([]float64, len(cm.requestTimes))
	for i, d := range cm.requestTimes {
		sorted[i] = d.Seconds()
	}
	sort.Float64s(sorted)

	index := (p * len(sorted)) / 100
	if index >= len(sorted) {
		index = len(sorted) - 1
	}
	return sorted[index]
}

func (cm *ChainMonitor) GetHealthStatus() HealthStatus {
	cm.mu.RLock()
	defer cm.mu.RUnlock()
	return *cm.healthStatus
}
