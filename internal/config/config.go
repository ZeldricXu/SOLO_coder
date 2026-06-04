package config

import (
	"time"
)

type Config struct {
	Server     ServerConfig     `yaml:"server"`
	Database   DatabaseConfig   `yaml:"database"`
	Redis      RedisConfig      `yaml:"redis"`
	Scheduler  SchedulerConfig  `yaml:"scheduler"`
	Executor   ExecutorConfig   `yaml:"executor"`
	Registry   RegistryConfig   `yaml:"registry"`
	Tracing    TracingConfig    `yaml:"tracing"`
	Auth       AuthConfig       `yaml:"auth"`
}

type ServerConfig struct {
	HTTPAddr string `yaml:"http_addr"`
	GRPCAddr string `yaml:"grpc_addr"`
	NodeID   string `yaml:"node_id"`
}

type DatabaseConfig struct {
	Host     string `yaml:"host"`
	Port     int    `yaml:"port"`
	User     string `yaml:"user"`
	Password string `yaml:"password"`
	DBName   string `yaml:"dbname"`
	SSLMode  string `yaml:"sslmode"`
}

type RedisConfig struct {
	Addr     string `yaml:"addr"`
	Password string `yaml:"password"`
	DB       int    `yaml:"db"`
	PoolSize int    `yaml:"pool_size"`
}

type SchedulerConfig struct {
	ShardCount         int           `yaml:"shard_count"`
	TriggerInterval    time.Duration `yaml:"trigger_interval"`
	TaskScanInterval   time.Duration `yaml:"task_scan_interval"`
	LockTTL            time.Duration `yaml:"lock_ttl"`
}

type ExecutorConfig struct {
	MaxConcurrency     int           `yaml:"max_concurrency"`
	WorkerPoolSize     int           `yaml:"worker_pool_size"`
	TaskTimeout        time.Duration `yaml:"task_timeout"`
	GracefulShutdown   time.Duration `yaml:"graceful_shutdown_timeout"`
	IsolationStrategy  string        `yaml:"isolation_strategy"`
}

type RegistryConfig struct {
	HealthCheckInterval time.Duration `yaml:"health_check_interval"`
	UnhealthyThreshold  int           `yaml:"unhealthy_threshold"`
	AutoRemoveInterval  time.Duration `yaml:"auto_remove_interval"`
}

type TracingConfig struct {
	Enabled     bool   `yaml:"enabled"`
	Endpoint    string `yaml:"endpoint"`
	ServiceName string `yaml:"service_name"`
}

type AuthConfig struct {
	JWTSecret   string `yaml:"jwt_secret"`
	AdminToken  string `yaml:"admin_token"`
}

func DefaultConfig() *Config {
	return &Config{
		Server: ServerConfig{
			HTTPAddr: ":8080",
			GRPCAddr: ":9090",
			NodeID:   "node-1",
		},
		Database: DatabaseConfig{
			Host:     "localhost",
			Port:     5432,
			User:     "postgres",
			Password: "postgres",
			DBName:   "task_scheduler",
			SSLMode:  "disable",
		},
		Redis: RedisConfig{
			Addr:     "localhost:6379",
			Password: "",
			DB:       0,
			PoolSize: 10,
		},
		Scheduler: SchedulerConfig{
			ShardCount:       32,
			TriggerInterval:  1 * time.Second,
			TaskScanInterval: 5 * time.Second,
			LockTTL:          30 * time.Second,
		},
		Executor: ExecutorConfig{
			MaxConcurrency:    100,
			WorkerPoolSize:    10,
			TaskTimeout:       30 * time.Minute,
			GracefulShutdown:  60 * time.Second,
			IsolationStrategy: "namespace",
		},
		Registry: RegistryConfig{
			HealthCheckInterval: 5 * time.Second,
			UnhealthyThreshold:  3,
			AutoRemoveInterval:  30 * time.Second,
		},
		Tracing: TracingConfig{
			Enabled:     false,
			Endpoint:    "localhost:4318",
			ServiceName: "task-scheduler",
		},
		Auth: AuthConfig{
			JWTSecret:  "your-secret-key-here",
			AdminToken: "admin-token",
		},
	}
}
