package config

import (
	"fmt"
	"strings"
	"time"

	"github.com/spf13/viper"
)

type ShardingStrategy string

const (
	ShardingStrategyByID      ShardingStrategy = "by_id"
	ShardingStrategyByHash    ShardingStrategy = "by_hash"
	ShardingStrategyByRange   ShardingStrategy = "by_range"
	ShardingStrategyByModulus ShardingStrategy = "by_modulus"
)

type LogLevel string

const (
	LogLevelDebug LogLevel = "debug"
	LogLevelInfo  LogLevel = "info"
	LogLevelWarn  LogLevel = "warn"
	LogLevelError LogLevel = "error"
)

type ServerConfig struct {
	GRPCPort int `mapstructure:"grpc_port"`
	HTTPPort int `mapstructure:"http_port"`
}

type SchedulerConfig struct {
	HeartbeatTimeout  time.Duration    `mapstructure:"heartbeat_timeout"`
	ShardingStrategy  ShardingStrategy `mapstructure:"sharding_strategy"`
	MaxRetries        int              `mapstructure:"max_retries"`
	TaskTimeout       time.Duration    `mapstructure:"task_timeout"`
	ChunkSize         int              `mapstructure:"chunk_size"`
}

type WorkerConfig struct {
	CacheSize     int           `mapstructure:"cache_size"`
	HeartbeatInterval time.Duration `mapstructure:"heartbeat_interval"`
	ConcurrentTasks int           `mapstructure:"concurrent_tasks"`
	WorkerID        int64         `mapstructure:"worker_id"`
}

type DatabaseConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	User     string `mapstructure:"user"`
	Password string `mapstructure:"password"`
	DBName   string `mapstructure:"dbname"`
	SSLMode  string `mapstructure:"sslmode"`
	TimeZone string `mapstructure:"timezone"`
}

func (c *DatabaseConfig) DSN() string {
	return fmt.Sprintf(
		"host=%s port=%d user=%s password=%s dbname=%s sslmode=%s TimeZone=%s",
		c.Host, c.Port, c.User, c.Password, c.DBName, c.SSLMode, c.TimeZone,
	)
}

type LogConfig struct {
	Level       LogLevel `mapstructure:"level"`
	Format      string   `mapstructure:"format"`
	OutputPath  string   `mapstructure:"output_path"`
	MaxSize     int      `mapstructure:"max_size"`
	MaxBackups  int      `mapstructure:"max_backups"`
	MaxAge      int      `mapstructure:"max_age"`
	Compress    bool     `mapstructure:"compress"`
}

type Config struct {
	Server    ServerConfig    `mapstructure:"server"`
	Scheduler SchedulerConfig `mapstructure:"scheduler"`
	Worker    WorkerConfig    `mapstructure:"worker"`
	Database  DatabaseConfig  `mapstructure:"database"`
	Log       LogConfig       `mapstructure:"log"`
}

func Load(configPath string) (*Config, error) {
	v := viper.New()
	v.SetEnvPrefix("EXP")
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	v.AutomaticEnv()

	v.SetDefault("server.grpc_port", 50051)
	v.SetDefault("server.http_port", 8080)
	v.SetDefault("scheduler.heartbeat_timeout", 30*time.Second)
	v.SetDefault("scheduler.sharding_strategy", ShardingStrategyByHash)
	v.SetDefault("scheduler.max_retries", 3)
	v.SetDefault("scheduler.task_timeout", 10*time.Minute)
	v.SetDefault("scheduler.chunk_size", 1000)
	v.SetDefault("worker.cache_size", 10000)
	v.SetDefault("worker.heartbeat_interval", 5*time.Second)
	v.SetDefault("worker.concurrent_tasks", 10)
	v.SetDefault("database.host", "localhost")
	v.SetDefault("database.port", 5432)
	v.SetDefault("database.sslmode", "disable")
	v.SetDefault("database.timezone", "UTC")
	v.SetDefault("log.level", LogLevelInfo)
	v.SetDefault("log.format", "json")
	v.SetDefault("log.output_path", "stdout")
	v.SetDefault("log.max_size", 100)
	v.SetDefault("log.max_backups", 3)
	v.SetDefault("log.max_age", 28)
	v.SetDefault("log.compress", true)

	if configPath != "" {
		v.SetConfigFile(configPath)
		v.SetConfigType("yaml")
		if err := v.ReadInConfig(); err != nil {
			return nil, fmt.Errorf("failed to read config file: %w", err)
		}
	}

	var cfg Config
	if err := v.Unmarshal(&cfg); err != nil {
		return nil, fmt.Errorf("failed to unmarshal config: %w", err)
	}

	if err := validate(&cfg); err != nil {
		return nil, err
	}

	return &cfg, nil
}

func validate(cfg *Config) error {
	if cfg.Server.GRPCPort <= 0 || cfg.Server.GRPCPort > 65535 {
		return fmt.Errorf("invalid gRPC port: %d", cfg.Server.GRPCPort)
	}
	if cfg.Server.HTTPPort <= 0 || cfg.Server.HTTPPort > 65535 {
		return fmt.Errorf("invalid HTTP port: %d", cfg.Server.HTTPPort)
	}
	if cfg.Scheduler.HeartbeatTimeout <= 0 {
		return fmt.Errorf("heartbeat timeout must be positive")
	}
	if cfg.Scheduler.MaxRetries < 0 {
		return fmt.Errorf("max retries cannot be negative")
	}
	if cfg.Worker.HeartbeatInterval <= 0 {
		return fmt.Errorf("heartbeat interval must be positive")
	}
	if cfg.Worker.ConcurrentTasks <= 0 {
		return fmt.Errorf("concurrent tasks must be positive")
	}
	if cfg.Database.Host == "" {
		return fmt.Errorf("database host is required")
	}
	if cfg.Database.User == "" {
		return fmt.Errorf("database user is required")
	}
	if cfg.Database.DBName == "" {
		return fmt.Errorf("database name is required")
	}
	return nil
}
