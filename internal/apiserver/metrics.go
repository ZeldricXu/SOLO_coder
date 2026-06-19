package apiserver

import (
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

var (
	HTTPRequestDuration = promauto.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "http_request_duration_seconds",
			Help:    "HTTP request duration in seconds",
			Buckets: prometheus.DefBuckets,
		},
		[]string{"method", "endpoint", "status"},
	)

	HTTPRequestTotal = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Name: "http_requests_total",
			Help: "Total number of HTTP requests",
		},
		[]string{"method", "endpoint", "status"},
	)

	ExperimentStatusCount = promauto.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "experiment_status_count",
			Help: "Number of experiments by status",
		},
		[]string{"status"},
	)

	TaskStatusCount = promauto.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "task_status_count",
			Help: "Number of tasks by status",
		},
		[]string{"status"},
	)

	WorkerOnlineCount = promauto.NewGauge(
		prometheus.GaugeOpts{
			Name: "worker_online_count",
			Help: "Number of online workers",
		},
	)

	WorkerStatusCount = promauto.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "worker_status_count",
			Help: "Number of workers by status",
		},
		[]string{"status"},
	)

	ResourceCPUUsagePercent = promauto.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "resource_cpu_usage_percent",
			Help: "CPU usage percentage by worker",
		},
		[]string{"worker_id", "worker_name"},
	)

	ResourceMemoryUsagePercent = promauto.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "resource_memory_usage_percent",
			Help: "Memory usage percentage by worker",
		},
		[]string{"worker_id", "worker_name"},
	)

	ResourceMemoryUsageBytes = promauto.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "resource_memory_usage_bytes",
			Help: "Memory usage in bytes by worker",
		},
		[]string{"worker_id", "worker_name"},
	)

	TaskExecutionDuration = promauto.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "task_execution_duration_seconds",
			Help:    "Task execution duration in seconds",
			Buckets: []float64{1, 5, 10, 30, 60, 120, 300, 600, 1800, 3600},
		},
		[]string{"experiment_id", "task_name", "status"},
	)

	TaskQueueDuration = promauto.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "task_queue_duration_seconds",
			Help:    "Time tasks spend in queue before execution",
			Buckets: []float64{0.1, 0.5, 1, 5, 10, 30, 60, 120},
		},
		[]string{"experiment_id", "priority"},
	)

	ExperimentDuration = promauto.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "experiment_duration_seconds",
			Help:    "Experiment total duration in seconds",
			Buckets: []float64{60, 300, 600, 1800, 3600, 7200, 14400, 28800},
		},
		[]string{"status"},
	)

	TasksCompletedTotal = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Name: "tasks_completed_total",
			Help: "Total number of completed tasks",
		},
		[]string{"experiment_id", "worker_id"},
	)

	TasksFailedTotal = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Name: "tasks_failed_total",
			Help: "Total number of failed tasks",
		},
		[]string{"experiment_id", "worker_id", "error_type"},
	)

	ResultCount = promauto.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "result_count",
			Help: "Number of results by experiment",
		},
		[]string{"experiment_id"},
	)

	APIErrorCount = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Name: "api_error_count",
			Help: "Number of API errors by endpoint",
		},
		[]string{"method", "endpoint", "error_code"},
	)
)

func RecordExperimentStatus(status string, count float64) {
	ExperimentStatusCount.WithLabelValues(status).Set(count)
}

func RecordTaskStatus(status string, count float64) {
	TaskStatusCount.WithLabelValues(status).Set(count)
}

func RecordWorkerOnline(count float64) {
	WorkerOnlineCount.Set(count)
}

func RecordWorkerStatus(status string, count float64) {
	WorkerStatusCount.WithLabelValues(status).Set(count)
}

func RecordResourceUsage(workerID, workerName string, cpuPercent, memoryPercent float64, memoryBytes float64) {
	ResourceCPUUsagePercent.WithLabelValues(workerID, workerName).Set(cpuPercent)
	ResourceMemoryUsagePercent.WithLabelValues(workerID, workerName).Set(memoryPercent)
	ResourceMemoryUsageBytes.WithLabelValues(workerID, workerName).Set(memoryBytes)
}

func RecordTaskExecution(experimentID, taskName, status string, durationSeconds float64) {
	TaskExecutionDuration.WithLabelValues(experimentID, taskName, status).Observe(durationSeconds)
	if status == "completed" {
		TasksCompletedTotal.WithLabelValues(experimentID, "").Inc()
	} else if status == "failed" {
		TasksFailedTotal.WithLabelValues(experimentID, "", "unknown").Inc()
	}
}

func RecordTaskQueue(experimentID, priority string, durationSeconds float64) {
	TaskQueueDuration.WithLabelValues(experimentID, priority).Observe(durationSeconds)
}

func RecordExperimentDuration(status string, durationSeconds float64) {
	ExperimentDuration.WithLabelValues(status).Observe(durationSeconds)
}

func RecordResultCount(experimentID string, count float64) {
	ResultCount.WithLabelValues(experimentID).Set(count)
}

func RecordAPIError(method, endpoint, errorCode string) {
	APIErrorCount.WithLabelValues(method, endpoint, errorCode).Inc()
}
