package config

import (
	"fmt"
	"os"
	"regexp"
	"time"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Server    ServerConfig    `yaml:"server"`
	Database  DatabaseConfig  `yaml:"database"`
	Redis     RedisConfig     `yaml:"redis"`
	Log       LogConfig       `yaml:"log"`
	Gateway   GatewayConfig   `yaml:"gateway"`
	Scheduler SchedulerConfig `yaml:"scheduler"`
	Security  SecurityConfig  `yaml:"security"`
}

type SecurityConfig struct {
	SecretKey string        `yaml:"secret_key"`
	RateLimit int           `yaml:"rate_limit"`
	RateWindow time.Duration `yaml:"rate_window"`
}

type ServerConfig struct {
	Port         int           `yaml:"port"`
	Mode         string        `yaml:"mode"`
	ReadTimeout  time.Duration `yaml:"read_timeout"`
	WriteTimeout time.Duration `yaml:"write_timeout"`
}

type DatabaseConfig struct {
	Host         string `yaml:"host"`
	Port         int    `yaml:"port"`
	User         string `yaml:"user"`
	Password     string `yaml:"password"`
	DBName       string `yaml:"dbname"`
	SSLMode      string `yaml:"sslmode"`
	MaxOpenConns int    `yaml:"max_open_conns"`
	MaxIdleConns int    `yaml:"max_idle_conns"`
}

type RedisConfig struct {
	Host     string `yaml:"host"`
	Port     int    `yaml:"port"`
	Password string `yaml:"password"`
	DB       int    `yaml:"db"`
	PoolSize int    `yaml:"pool_size"`
}

type LogConfig struct {
	Level  string `yaml:"level"`
	Format string `yaml:"format"`
	Output string `yaml:"output"`
}

type GatewayConfig struct {
	Providers []ProviderConfig `yaml:"providers"`
}

type ProviderConfig struct {
	Name       string        `yaml:"name"`
	APIKey     string        `yaml:"api_key"`
	BaseURL    string        `yaml:"base_url"`
	Timeout    time.Duration `yaml:"timeout"`
	MaxRetries int           `yaml:"max_retries"`
}

type SchedulerConfig struct {
	GPUResources     []GPUResourceConfig `yaml:"gpu_resources"`
	PreemptionEnabled bool               `yaml:"preemption_enabled"`
	MaxQueueSize     int                `yaml:"max_queue_size"`
}

type GPUResourceConfig struct {
	ID              string `yaml:"id"`
	Node            string `yaml:"node"`
	TotalMemory     int64  `yaml:"total_memory"`
	AvailableMemory int64  `yaml:"available_memory"`
	TotalCompute    int    `yaml:"total_compute"`
	AvailableCompute int   `yaml:"available_compute"`
}

var envVarRegex = regexp.MustCompile(`\$\{([^}]+)\}`)

func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("failed to read config file: %w", err)
	}

	processedData := replaceEnvVars(string(data))

	var cfg Config
	if err := yaml.Unmarshal([]byte(processedData), &cfg); err != nil {
		return nil, fmt.Errorf("failed to parse config file: %w", err)
	}

	return &cfg, nil
}

func replaceEnvVars(input string) string {
	return envVarRegex.ReplaceAllStringFunc(input, func(match string) string {
		varName := match[2 : len(match)-1]
		if value, exists := os.LookupEnv(varName); exists {
			return value
		}
		return match
	})
}

func (c *DatabaseConfig) DSN() string {
	return fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
		c.Host, c.Port, c.User, c.Password, c.DBName, c.SSLMode)
}

func (c *RedisConfig) Addr() string {
	return fmt.Sprintf("%s:%d", c.Host, c.Port)
}
