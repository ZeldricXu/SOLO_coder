package config

import (
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Config struct {
	Server   ServerConfig   `json:"server"`
	Database DatabaseConfig `json:"database"`
	Redis    RedisConfig    `json:"redis"`
	Log      LogConfig      `json:"log"`
	GPU      GPUConfig      `json:"gpu"`
	Gateway  GatewayConfig  `json:"gateway"`
	Storage  StorageConfig  `json:"storage"`
}

type ServerConfig struct {
	Host         string        `json:"host"`
	Port         int           `json:"port"`
	ReadTimeout  time.Duration `json:"read_timeout"`
	WriteTimeout time.Duration `json:"write_timeout"`
}

type DatabaseConfig struct {
	Host     string `json:"host"`
	Port     int    `json:"port"`
	User     string `json:"user"`
	Password string `json:"password"`
	DBName   string `json:"dbname"`
	SSLMode  string `json:"sslmode"`
}

type RedisConfig struct {
	Host     string `json:"host"`
	Port     int    `json:"port"`
	Password string `json:"password"`
	DB       int    `json:"db"`
}

type LogConfig struct {
	Level    string `json:"level"`
	Format   string `json:"format"`
	Output   string `json:"output"`
	FilePath string `json:"file_path"`
}

type GPUConfig struct {
	NodeID            string   `json:"node_id"`
	DeviceIndices     []int    `json:"device_indices"`
	VRAMPerDeviceMB   uint64   `json:"vram_per_device_mb"`
	PreemptionEnabled bool     `json:"preemption_enabled"`
}

type GatewayConfig struct {
	DefaultTimeoutMs  int    `json:"default_timeout_ms"`
	DefaultMaxRetries int    `json:"default_max_retries"`
	CircuitThreshold  int    `json:"circuit_threshold"`
	CircuitTimeoutMs  int    `json:"circuit_timeout_ms"`
}

type StorageConfig struct {
	Type      string `json:"type"`
	LocalPath string `json:"local_path"`
	S3Bucket  string `json:"s3_bucket"`
	S3Region  string `json:"s3_region"`
}

var (
	instance *Config
	once     sync.Once
)

func Load() *Config {
	once.Do(func() {
		instance = &Config{
			Server: ServerConfig{
				Host:         getEnv("SERVER_HOST", "0.0.0.0"),
				Port:         getEnvInt("SERVER_PORT", 8080),
				ReadTimeout:  getEnvDuration("SERVER_READ_TIMEOUT", 30*time.Second),
				WriteTimeout: getEnvDuration("SERVER_WRITE_TIMEOUT", 30*time.Second),
			},
			Database: DatabaseConfig{
				Host:     getEnv("DB_HOST", "localhost"),
				Port:     getEnvInt("DB_PORT", 5432),
				User:     getEnv("DB_USER", "postgres"),
				Password: getEnv("DB_PASSWORD", "postgres"),
				DBName:   getEnv("DB_NAME", "dataplatform"),
				SSLMode:  getEnv("DB_SSLMODE", "disable"),
			},
			Redis: RedisConfig{
				Host:     getEnv("REDIS_HOST", "localhost"),
				Port:     getEnvInt("REDIS_PORT", 6379),
				Password: getEnv("REDIS_PASSWORD", ""),
				DB:       getEnvInt("REDIS_DB", 0),
			},
			Log: LogConfig{
				Level:    getEnv("LOG_LEVEL", "info"),
				Format:   getEnv("LOG_FORMAT", "json"),
				Output:   getEnv("LOG_OUTPUT", "stdout"),
				FilePath: getEnv("LOG_FILE_PATH", ""),
			},
			GPU: GPUConfig{
				NodeID:            getEnv("GPU_NODE_ID", "node-1"),
				DeviceIndices:     getEnvIntSlice("GPU_DEVICE_INDICES", []int{0}),
				VRAMPerDeviceMB:   uint64(getEnvInt("GPU_VRAM_PER_DEVICE_MB", 24576)),
				PreemptionEnabled: getEnvBool("GPU_PREEMPTION_ENABLED", true),
			},
			Gateway: GatewayConfig{
				DefaultTimeoutMs:  getEnvInt("GATEWAY_DEFAULT_TIMEOUT_MS", 30000),
				DefaultMaxRetries: getEnvInt("GATEWAY_DEFAULT_MAX_RETRIES", 2),
				CircuitThreshold:  getEnvInt("GATEWAY_CIRCUIT_THRESHOLD", 5),
				CircuitTimeoutMs:  getEnvInt("GATEWAY_CIRCUIT_TIMEOUT_MS", 30000),
			},
			Storage: StorageConfig{
				Type:      getEnv("STORAGE_TYPE", "local"),
				LocalPath: getEnv("STORAGE_LOCAL_PATH", "./data"),
				S3Bucket:  getEnv("STORAGE_S3_BUCKET", ""),
				S3Region:  getEnv("STORAGE_S3_REGION", "us-east-1"),
			},
		}
	})
	return instance
}

func getEnv(key, defaultValue string) string {
	if value, exists := os.LookupEnv(key); exists {
		return value
	}
	return defaultValue
}

func getEnvInt(key string, defaultValue int) int {
	if value, exists := os.LookupEnv(key); exists {
		if intVal, err := strconv.Atoi(value); err == nil {
			return intVal
		}
	}
	return defaultValue
}

func getEnvBool(key string, defaultValue bool) bool {
	if value, exists := os.LookupEnv(key); exists {
		if boolVal, err := strconv.ParseBool(value); err == nil {
			return boolVal
		}
	}
	return defaultValue
}

func getEnvDuration(key string, defaultValue time.Duration) time.Duration {
	if value, exists := os.LookupEnv(key); exists {
		if duration, err := time.ParseDuration(value); err == nil {
			return duration
		}
	}
	return defaultValue
}

func getEnvIntSlice(key string, defaultValue []int) []int {
	if value, exists := os.LookupEnv(key); exists {
		parts := strings.Split(value, ",")
		result := make([]int, 0, len(parts))
		for _, part := range parts {
			if intVal, err := strconv.Atoi(strings.TrimSpace(part)); err == nil {
				result = append(result, intVal)
			}
		}
		if len(result) > 0 {
			return result
		}
	}
	return defaultValue
}
