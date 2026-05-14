package models

import "time"

const (
	ServiceImportanceCritical = "critical"
	ServiceImportanceHigh     = "high"
	ServiceImportanceMedium   = "medium"
	ServiceImportanceLow      = "low"

	AlgorithmTokenBucket   = "token_bucket"
	AlgorithmLeakyBucket   = "leaky_bucket"
	AlgorithmSlidingWindow = "sliding_window"
	AlgorithmDistributedTokenBucket = "distributed_token_bucket"
	AlgorithmDistributedSlidingWindow = "distributed_sliding_window"
)

type ForwardConfig struct {
	Timeout           int    `json:"timeout"`
	RetryCount        int    `json:"retry_count"`
	AsyncEnabled      bool   `json:"async_enabled"`
	PersistTasks      bool   `json:"persist_tasks"`
	TaskQueueName     string `json:"task_queue_name,omitempty"`
}

type RateLimitConfig struct {
	QPS         int    `json:"qps"`
	Burst       int    `json:"burst"`
	Algorithm   string `json:"algorithm,omitempty"`
	WindowSize  int    `json:"window_size,omitempty"`
	Distributed bool   `json:"distributed,omitempty"`
}

type Route struct {
	RouteID         string           `json:"route_id"`
	RoutePattern    string           `json:"route_pattern"`
	TargetService   string           `json:"target_service"`
	TargetInstances []string         `json:"target_instances"`
	ForwardConfig   ForwardConfig    `json:"forward_config"`
	AuthRequired    bool             `json:"auth_required"`
	RateLimit       RateLimitConfig  `json:"rate_limit"`
	Enabled         bool             `json:"enabled"`
	Group           string           `json:"group"`
	CreatedAt       time.Time        `json:"created_at"`
	UpdatedAt       time.Time        `json:"updated_at"`
}

type LimitConfig struct {
	LimitID        string `json:"limit_id"`
	RouteID        string `json:"route_id"`
	LimitType      string `json:"limit_type"`
	LimitValue     int    `json:"limit_value"`
	BurstSize      int    `json:"burst_size"`
	LimitAlgorithm string `json:"limit_algorithm"`
	Enabled        bool   `json:"enabled"`
	WindowSize     int    `json:"window_size,omitempty"`
}

type CircuitBreakerConfig struct {
	CircuitID              string  `json:"circuit_id"`
	ServiceName            string  `json:"service_name"`
	ServiceImportance      string  `json:"service_importance,omitempty"`
	FailureThreshold       int     `json:"failure_threshold"`
	FailureRateThreshold   float64 `json:"failure_rate_threshold"`
	OpenTimeout            int     `json:"open_timeout"`
	HalfOpenRequests       int     `json:"half_open_requests"`
	CriticalHalfOpenRequests int   `json:"critical_half_open_requests,omitempty"`
	HighHalfOpenRequests     int   `json:"high_half_open_requests,omitempty"`
	MediumHalfOpenRequests   int   `json:"medium_half_open_requests,omitempty"`
	LowHalfOpenRequests      int   `json:"low_half_open_requests,omitempty"`
}

type CircuitBreakerState struct {
	CircuitID      string    `json:"circuit_id"`
	ServiceName    string    `json:"service_name"`
	Status         string    `json:"status"`
	FailureCount   int       `json:"failure_count"`
	SuccessCount   int       `json:"success_count"`
	TotalRequests  int       `json:"total_requests"`
	LastFailureAt  time.Time `json:"last_failure_at"`
	OpenedAt       time.Time `json:"opened_at"`
	HalfOpenCount  int       `json:"half_open_count"`
}

type ServiceInstance struct {
	InstanceID string `json:"instance_id"`
	Address    string `json:"address"`
	Weight     int    `json:"weight"`
	Healthy    bool   `json:"healthy"`
}

type LoadBalancerConfig struct {
	BalanceID       string            `json:"balance_id"`
	ServiceName     string            `json:"service_name"`
	BalanceAlgorithm string           `json:"balance_algorithm"`
	Instances       []ServiceInstance `json:"instances"`
}

type CallStats struct {
	StatID       string    `json:"stat_id"`
	RouteID      string    `json:"route_id"`
	StatTime     time.Time `json:"stat_time"`
	RequestCount int       `json:"request_count"`
	SuccessCount int       `json:"success_count"`
	FailCount    int       `json:"fail_count"`
	AvgLatency   int64     `json:"avg_latency"`
	MaxLatency   int64     `json:"max_latency"`
	MinLatency   int64     `json:"min_latency"`
}

type RequestLog struct {
	LogID         string    `json:"log_id"`
	RequestID     string    `json:"request_id"`
	RouteID       string    `json:"route_id"`
	ClientIP      string    `json:"client_ip"`
	RequestMethod string    `json:"request_method"`
	RequestPath   string    `json:"request_path"`
	ResponseStatus int      `json:"response_status"`
	Latency       int64     `json:"latency"`
	RequestTime   time.Time `json:"request_time"`
	TargetAddress string    `json:"target_address"`
	RetryCount    int       `json:"retry_count"`
	Error         string    `json:"error,omitempty"`
}

type APIResponse struct {
	Code int         `json:"code"`
	Data interface{} `json:"data,omitempty"`
	Msg  string      `json:"msg,omitempty"`
}

type CreateRouteRequest struct {
	RoutePattern   string          `json:"route_pattern"`
	TargetService  string          `json:"target_service"`
	TargetInstances []string       `json:"target_instances"`
	ForwardConfig  *ForwardConfig  `json:"forward_config,omitempty"`
	AuthRequired   *bool           `json:"auth_required,omitempty"`
	RateLimit      *RateLimitConfig `json:"rate_limit,omitempty"`
	Group          string          `json:"group,omitempty"`
}

type QueryStatsRequest struct {
	RouteID    string    `form:"route_id"`
	StartTime  time.Time `form:"start_time"`
	EndTime    time.Time `form:"end_time"`
}

type RedisConfig struct {
	Addresses   []string `json:"addresses"`
	MasterName  string   `json:"master_name,omitempty"`
	Password    string   `json:"password,omitempty"`
	DB          int      `json:"db"`
	PoolSize    int      `json:"pool_size"`
	MaxRetries  int      `json:"max_retries"`
	UseCluster  bool     `json:"use_cluster"`
	UseSentinel bool     `json:"use_sentinel"`
}

type GatewayConfig struct {
	Redis         RedisConfig `json:"redis"`
	DefaultRateLimit struct {
		QPS       int    `json:"qps"`
		Burst     int    `json:"burst"`
		Algorithm string `json:"algorithm"`
	} `json:"default_rate_limit"`
	DefaultCircuitBreaker struct {
		FailureThreshold     int     `json:"failure_threshold"`
		FailureRateThreshold float64 `json:"failure_rate_threshold"`
		OpenTimeout          int     `json:"open_timeout"`
	} `json:"default_circuit_breaker"`
	AsyncForward struct {
		WorkerCount   int    `json:"worker_count"`
		QueueSize     int    `json:"queue_size"`
		DefaultQueue  string `json:"default_queue"`
	} `json:"async_forward"`
}

type PersistedForwardTask struct {
	TaskID        string    `json:"task_id"`
	RouteID       string    `json:"route_id"`
	TargetURL     string    `json:"target_url"`
	Method        string    `json:"method"`
	Path          string    `json:"path"`
	Headers       map[string]string `json:"headers"`
	Body          []byte    `json:"body,omitempty"`
	Timeout       int       `json:"timeout"`
	RetryCount    int       `json:"retry_count"`
	MaxRetries    int       `json:"max_retries"`
	Status        string    `json:"status"`
	Error         string    `json:"error,omitempty"`
	CreatedAt     time.Time `json:"created_at"`
	StartedAt     time.Time `json:"started_at,omitempty"`
	CompletedAt   time.Time `json:"completed_at,omitempty"`
}

const (
	TaskStatusPending   = "pending"
	TaskStatusRunning   = "running"
	TaskStatusCompleted = "completed"
	TaskStatusFailed    = "failed"
)
