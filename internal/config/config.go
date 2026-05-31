package config

import (
	"os"
	"strconv"
	"time"
)

type AppConfig struct {
	Server    ServerConfig
	Database  DatabaseConfig
	Redis     RedisConfig
	Logging   LoggingConfig
	Worker    WorkerConfig
}

type ServerConfig struct {
	Host         string
	Port         int
	Environment  string
	ReadTimeout  time.Duration
	WriteTimeout time.Duration
	IdleTimeout  time.Duration
}

type DatabaseConfig struct {
	DSN          string
	MaxOpenConns int
	MaxIdleConns int
	MaxLifetime  time.Duration
}

type RedisConfig struct {
	Address    string
	Password   string
	DB         int
	PoolSize   int
	Expiration time.Duration
}

type LoggingConfig struct {
	Level      string
	Format     string
	FilePath   string
	MaxSize    int
	MaxBackups int
	MaxAge     int
}

type WorkerConfig struct {
	PoolSize         int
	QueueSize        int
	WorkerCount      int
	EventBusWorkers  int
	MaxRetryAttempts int
	DefaultTimeout   time.Duration
}

func Load() (*AppConfig, error) {
	return &AppConfig{
		Server: ServerConfig{
			Host:         getEnv("SERVER_HOST", "0.0.0.0"),
			Port:         getEnvInt("SERVER_PORT", 8080),
			Environment:  getEnv("SERVER_ENV", "development"),
			ReadTimeout:  getEnvDuration("SERVER_READ_TIMEOUT", 30*time.Second),
			WriteTimeout: getEnvDuration("SERVER_WRITE_TIMEOUT", 30*time.Second),
			IdleTimeout:  getEnvDuration("SERVER_IDLE_TIMEOUT", 60*time.Second),
		},
		Database: DatabaseConfig{
			DSN:          getEnv("DB_DSN", "postgres://postgres:postgres@localhost:5432/task_scheduler?sslmode=disable"),
			MaxOpenConns: getEnvInt("DB_MAX_OPEN_CONNS", 100),
			MaxIdleConns: getEnvInt("DB_MAX_IDLE_CONNS", 10),
			MaxLifetime:  getEnvDuration("DB_MAX_LIFETIME", time.Hour),
		},
		Redis: RedisConfig{
			Address:    getEnv("REDIS_ADDRESS", "localhost:6379"),
			Password:   getEnv("REDIS_PASSWORD", ""),
			DB:         getEnvInt("REDIS_DB", 0),
			PoolSize:   getEnvInt("REDIS_POOL_SIZE", 50),
			Expiration: getEnvDuration("REDIS_EXPIRATION", 24*time.Hour),
		},
		Logging: LoggingConfig{
			Level:      getEnv("LOG_LEVEL", "info"),
			Format:     getEnv("LOG_FORMAT", "json"),
			FilePath:   getEnv("LOG_FILE_PATH", ""),
			MaxSize:    getEnvInt("LOG_MAX_SIZE", 100),
			MaxBackups: getEnvInt("LOG_MAX_BACKUPS", 3),
			MaxAge:     getEnvInt("LOG_MAX_AGE", 30),
		},
		Worker: WorkerConfig{
			PoolSize:         getEnvInt("WORKER_POOL_SIZE", 10),
			QueueSize:        getEnvInt("WORKER_QUEUE_SIZE", 1000),
			WorkerCount:      getEnvInt("WORKER_COUNT", 5),
			EventBusWorkers:  getEnvInt("EVENT_BUS_WORKERS", 3),
			MaxRetryAttempts: getEnvInt("WORKER_MAX_RETRY", 3),
			DefaultTimeout:   getEnvDuration("WORKER_DEFAULT_TIMEOUT", 5*time.Minute),
		},
	}, nil
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

func getEnvInt(key string, defaultValue int) int {
	if value := os.Getenv(key); value != "" {
		if v, err := strconv.Atoi(value); err == nil {
			return v
		}
	}
	return defaultValue
}

func getEnvDuration(key string, defaultValue time.Duration) time.Duration {
	if value := os.Getenv(key); value != "" {
		if v, err := time.ParseDuration(value); err == nil {
			return v
		}
	}
	return defaultValue
}
