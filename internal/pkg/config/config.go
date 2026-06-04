package config

import (
	"fmt"
	"strings"
	"time"

	"github.com/spf13/viper"
)

type Config struct {
	Server       ServerConfig       `mapstructure:"server"`
	Database     DatabaseConfig     `mapstructure:"database"`
	Redis        RedisConfig        `mapstructure:"redis"`
	Triton       TritonConfig       `mapstructure:"triton"`
	Orchestrator OrchestratorConfig `mapstructure:"orchestrator"`
	Batcher      BatcherConfig      `mapstructure:"batcher"`
	Monitoring   MonitoringConfig   `mapstructure:"monitoring"`
	Tenant       TenantConfig       `mapstructure:"tenant"`
	Webhook      WebhookConfig      `mapstructure:"webhook"`
	Notification NotificationConfig `mapstructure:"notification"`
}

type WebhookConfig struct {
	Enabled           bool          `mapstructure:"enabled"`
	AuthToken         string        `mapstructure:"auth_token"`
	AutoDeployDefault bool          `mapstructure:"auto_deploy_default"`
	DefaultDeployEnv  string        `mapstructure:"default_deploy_env"`
	DownloadTimeout   time.Duration `mapstructure:"download_timeout"`
	MaxModelSizeMB    int64         `mapstructure:"max_model_size_mb"`
}

type NotificationConfig struct {
	DingTalk   DingTalkConfig   `mapstructure:"dingtalk"`
	WeChatWork WeChatWorkConfig `mapstructure:"wechat_work"`
	Email      EmailConfig      `mapstructure:"email"`
}

type DingTalkConfig struct {
	Enabled    bool   `mapstructure:"enabled"`
	WebhookURL string `mapstructure:"webhook_url"`
	Secret     string `mapstructure:"secret"`
}

type WeChatWorkConfig struct {
	Enabled    bool   `mapstructure:"enabled"`
	WebhookURL string `mapstructure:"webhook_url"`
}

type EmailConfig struct {
	Enabled   bool   `mapstructure:"enabled"`
	SMTPHost  string `mapstructure:"smtp_host"`
	SMTPPort  int    `mapstructure:"smtp_port"`
	Username  string `mapstructure:"username"`
	Password  string `mapstructure:"password"`
	FromAddr  string `mapstructure:"from_addr"`
}

type ServerConfig struct {
	Port         int           `mapstructure:"port"`
	ReadTimeout  time.Duration `mapstructure:"read_timeout"`
	WriteTimeout time.Duration `mapstructure:"write_timeout"`
	JWTSecret    string        `mapstructure:"jwt_secret"`
}

type DatabaseConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	User     string `mapstructure:"user"`
	Password string `mapstructure:"password"`
	DBName   string `mapstructure:"dbname"`
	SSLMode  string `mapstructure:"sslmode"`
}

type RedisConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	Password string `mapstructure:"password"`
	DB       int    `mapstructure:"db"`
}

type TritonConfig struct {
	GRPCHost string `mapstructure:"grpc_host"`
	GRPCPort int    `mapstructure:"grpc_port"`
	HTTPPort int    `mapstructure:"http_port"`
	ModelRepositoryPath string `mapstructure:"model_repository_path"`
}

type OrchestratorConfig struct {
	MinReplicas           int           `mapstructure:"min_replicas"`
	MaxReplicas           int           `mapstructure:"max_replicas"`
	ScaleUpThreshold      float64       `mapstructure:"scale_up_threshold"`
	ScaleDownThreshold    float64       `mapstructure:"scale_down_threshold"`
	ScaleUpDelay          time.Duration `mapstructure:"scale_up_delay"`
	ScaleDownDelay        time.Duration `mapstructure:"scale_down_delay"`
	GPUMemoryThreshold    float64       `mapstructure:"gpu_memory_threshold"`
	QueueDepthThreshold   int           `mapstructure:"queue_depth_threshold"`
	RuntimeMode           string        `mapstructure:"runtime_mode"`
	TritonImage           string        `mapstructure:"triton_image"`
	HealthCheckInterval   time.Duration `mapstructure:"health_check_interval"`
	HealthCheckInference  bool          `mapstructure:"health_check_inference"`
	DockerNetwork         string        `mapstructure:"docker_network"`
	ModelRepositoryHostPath string      `mapstructure:"model_repository_host_path"`
	TritonExecutable      string        `mapstructure:"triton_executable"`
	DockerGRPCPortStart   int           `mapstructure:"docker_grpc_port_start"`
	DockerHTTPPortStart   int           `mapstructure:"docker_http_port_start"`
	ProcessGRPCPortStart  int           `mapstructure:"process_grpc_port_start"`
	ProcessHTTPPortStart  int           `mapstructure:"process_http_port_start"`
}

type BatcherConfig struct {
	MaxBatchSize    int           `mapstructure:"max_batch_size"`
	BatchWindow     time.Duration `mapstructure:"batch_window"`
	MaxQueueSize    int           `mapstructure:"max_queue_size"`
}

type MonitoringConfig struct {
	PrometheusPort int           `mapstructure:"prometheus_port"`
	TraceEnabled   bool          `mapstructure:"trace_enabled"`
	LogLevel       string        `mapstructure:"log_level"`
}

type TenantConfig struct {
	DefaultGPUQuota float64 `mapstructure:"default_gpu_quota"`
	DefaultGPUMin   float64 `mapstructure:"default_gpu_min"`
}

func LoadConfig(configPath string, env string) (*Config, error) {
	v := viper.New()

	v.SetConfigName("config")
	v.SetConfigType("yaml")
	v.AddConfigPath(configPath)
	v.AddConfigPath(".")
	v.AddConfigPath("./config")

	if err := v.ReadInConfig(); err != nil {
		return nil, fmt.Errorf("failed to read config file: %w", err)
	}

	v.SetEnvPrefix("APP")
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	v.AutomaticEnv()

	envFile := fmt.Sprintf(".env.%s", env)
	v.SetConfigName(envFile)
	v.SetConfigType("env")
	if err := v.MergeInConfig(); err != nil {
	}

	var cfg Config
	if err := v.Unmarshal(&cfg); err != nil {
		return nil, fmt.Errorf("failed to unmarshal config: %w", err)
	}

	return &cfg, nil
}

func LoadConfigFromEnv() (*Config, error) {
	env := getEnv("APP_ENV", "development")
	configPath := getEnv("CONFIG_PATH", ".")
	return LoadConfig(configPath, env)
}

func getEnv(key, defaultValue string) string {
	v := viper.New()
	v.AutomaticEnv()
	if val := v.GetString(key); val != "" {
		return val
	}
	return defaultValue
}
