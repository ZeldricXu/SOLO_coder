package config

import (
	"time"
)

type Config struct {
	Ingestor      IngestorConfig      `yaml:"ingestor"`
	Windowing     WindowingConfig     `yaml:"windowing"`
	Anomaly       AnomalyConfig       `yaml:"anomaly"`
	Metrics       MetricsConfig       `yaml:"metrics"`
	Storage       StorageConfig       `yaml:"storage"`
	QueryAPI      QueryAPIConfig      `yaml:"query_api"`
	AlertManager  AlertManagerConfig  `yaml:"alert_manager"`
}

type IngestorConfig struct {
	TCPPort    int      `yaml:"tcp_port"`
	UDPPort    int      `yaml:"udp_port"`
	HTTPPort   int      `yaml:"http_port"`
	BufferSize int      `yaml:"buffer_size"`
	MaxWorkers int      `yaml:"max_workers"`
	Sources    []string `yaml:"sources"`
}

type WindowingConfig struct {
	SlidingWindowSize  time.Duration `yaml:"sliding_window_size"`
	SlidingStep        time.Duration `yaml:"sliding_step"`
	SessionTimeout     time.Duration `yaml:"session_timeout"`
	Error401Threshold  int           `yaml:"error_401_threshold"`
	RedisTTL           time.Duration `yaml:"redis_ttl"`
}

type AnomalyConfig struct {
	MovingAverageWindow int     `yaml:"moving_average_window"`
	StdDevThreshold     float64 `yaml:"std_dev_threshold"`
	IsolationForest     struct {
		Trees         int     `yaml:"trees"`
		SampleSize    int     `yaml:"sample_size"`
		Contamination float64 `yaml:"contamination"`
	} `yaml:"isolation_forest"`
}

type MetricsConfig struct {
	PrometheusPort int           `yaml:"prometheus_port"`
	FlushInterval  time.Duration `yaml:"flush_interval"`
}

type StorageConfig struct {
	ClickHouse ClickHouseConfig `yaml:"clickhouse"`
	Redis      RedisConfig      `yaml:"redis"`
}

type ClickHouseConfig struct {
	Address  string `yaml:"address"`
	Database string `yaml:"database"`
	Username string `yaml:"username"`
	Password string `yaml:"password"`
}

type RedisConfig struct {
	Address  string `yaml:"address"`
	Password string `yaml:"password"`
	DB       int    `yaml:"db"`
}

type QueryAPIConfig struct {
	Port int `yaml:"port"`
}

type AlertManagerConfig struct {
	Channels          []AlertChannelConfig     `yaml:"channels"`
	SilentPeriod      time.Duration            `yaml:"silent_period"`
	SourceSilentPeriods map[string]time.Duration `yaml:"source_silent_periods"`
}

type AlertChannelConfig struct {
	Type     string   `yaml:"type"`
	Webhook  string   `yaml:"webhook"`
	Token    string   `yaml:"token"`
	Secret   string   `yaml:"secret"`
	Levels   []string `yaml:"levels"`
}

func DefaultConfig() *Config {
	return &Config{
		Ingestor: IngestorConfig{
			TCPPort:    5140,
			UDPPort:    5140,
			HTTPPort:   8080,
			BufferSize: 10000,
			MaxWorkers: 100,
			Sources:    []string{"fluent-bit", "filebeat", "syslog"},
		},
		Windowing: WindowingConfig{
			SlidingWindowSize:  time.Minute,
			SlidingStep:        time.Second * 10,
			SessionTimeout:     time.Minute * 5,
			Error401Threshold:  5,
			RedisTTL:           time.Hour,
		},
		Anomaly: AnomalyConfig{
			MovingAverageWindow: 100,
			StdDevThreshold:     3.0,
		},
		Metrics: MetricsConfig{
			PrometheusPort: 9090,
			FlushInterval:  time.Second * 15,
		},
		Storage: StorageConfig{
			ClickHouse: ClickHouseConfig{
				Address:  "localhost:9000",
				Database: "logs",
				Username: "default",
				Password: "",
			},
			Redis: RedisConfig{
				Address:  "localhost:6379",
				Password: "",
				DB:       0,
			},
		},
		QueryAPI: QueryAPIConfig{
			Port: 8081,
		},
		AlertManager: AlertManagerConfig{
			SilentPeriod: time.Minute * 5,
		},
	}
}
