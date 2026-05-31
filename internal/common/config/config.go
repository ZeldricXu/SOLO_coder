package config

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"sync"

	"github.com/joho/godotenv"
)

type Environment string

const (
	EnvDevelopment Environment = "development"
	EnvTesting     Environment = "testing"
	EnvStaging     Environment = "staging"
	EnvProduction  Environment = "production"
)

type Config struct {
	Env      Environment    `json:"env"`
	Server   ServerConfig   `json:"server"`
	Database DatabaseConfig `json:"database"`
	Redis    RedisConfig    `json:"redis"`
	Log      LogConfig      `json:"log"`
	Modules  ModulesConfig  `json:"modules"`
}

type ServerConfig struct {
	Port         int    `json:"port"`
	Host         string `json:"host"`
	Mode         string `json:"mode"`
	ReadTimeout  int    `json:"read_timeout"`
	WriteTimeout int    `json:"write_timeout"`
	TLSEnabled   bool   `json:"tls_enabled"`
	TLSCert      string `json:"tls_cert"`
	TLSKey       string `json:"tls_key"`
}

type DatabaseConfig struct {
	Host            string `json:"host"`
	Port            int    `json:"port"`
	User            string `json:"user"`
	Password        string `json:"password"`
	DBName          string `json:"dbname"`
	SSLMode         string `json:"sslmode"`
	MaxOpenConn     int    `json:"max_open_conn"`
	MaxIdleConn     int    `json:"max_idle_conn"`
	ConnMaxLifetime int    `json:"conn_max_lifetime"`
	Timezone        string `json:"timezone"`
}

type RedisConfig struct {
	Addr         string `json:"addr"`
	Password     string `json:"password"`
	DB           int    `json:"db"`
	PoolSize     int    `json:"pool_size"`
	MinIdleConn  int    `json:"min_idle_conn"`
	DialTimeout  int    `json:"dial_timeout"`
	ReadTimeout  int    `json:"read_timeout"`
	WriteTimeout int    `json:"write_timeout"`
}

type LogConfig struct {
	Level      string `json:"level"`
	Format     string `json:"format"`
	Output     string `json:"output"`
	File       string `json:"file"`
	MaxSize    int    `json:"max_size"`
	MaxBackups int    `json:"max_backups"`
	MaxAge     int    `json:"max_age"`
	Compress   bool   `json:"compress"`
}

type ModulesConfig struct {
	Compression CompressionConfig `json:"compression"`
	Quality     QualityConfig     `json:"quality"`
	Lifecycle   LifecycleConfig   `json:"lifecycle"`
	CDC         CDCConfig         `json:"cdc"`
	VectorIndex VectorIndexConfig `json:"vector_index"`
	Crawler     CrawlerConfig     `json:"crawler"`
	Auth        AuthConfig        `json:"auth"`
}

type CompressionConfig struct {
	DefaultAlgorithm string `json:"default_algorithm"`
	DefaultBlockSize int    `json:"default_block_size"`
	MultiResEnabled  bool   `json:"multi_res_enabled"`
}

type QualityConfig struct {
	CheckInterval    string `json:"check_interval"`
	MaxRetry         int    `json:"max_retry"`
	AnomalyRetention int    `json:"anomaly_retention_days"`
}

type LifecycleConfig struct {
	HotThresholdDays  int    `json:"hot_threshold_days"`
	WarmThresholdDays int    `json:"warm_threshold_days"`
	ArchivePath       string `json:"archive_path"`
	CleanupCron       string `json:"cleanup_cron"`
	AutoArchive       bool   `json:"auto_archive"`
	AutoDelete        bool   `json:"auto_delete"`
}

type CDCConfig struct {
	BufferSize     int    `json:"buffer_size"`
	OutputFormat   string `json:"output_format"`
	RecoveryEnable bool   `json:"recovery_enable"`
	RecoveryPath   string `json:"recovery_path"`
	BatchSize      int    `json:"batch_size"`
}

type VectorIndexConfig struct {
	DefaultDimension int    `json:"default_dimension"`
	DefaultMetric    string `json:"default_metric"`
	IndexPath        string `json:"index_path"`
	BuildWorkerNum   int    `json:"build_worker_num"`
	SearchEF         int    `json:"search_ef"`
}

type CrawlerConfig struct {
	ScanInterval string `json:"scan_interval"`
	SampleSize   int    `json:"sample_size"`
	Timeout      int    `json:"timeout"`
	Parallelism  int    `json:"parallelism"`
}

type AuthConfig struct {
	Enabled        bool   `json:"enabled"`
	JWTSecret      string `json:"jwt_secret"`
	JWTExpiryHours int    `json:"jwt_expiry_hours"`
	APIKeysEnabled bool   `json:"api_keys_enabled"`
}

var (
	instance *Config
	once     sync.Once
)

func GetDSN(db *DatabaseConfig) string {
	return fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=%s TimeZone=%s",
		db.Host, db.Port, db.User, db.Password, db.DBName, db.SSLMode, db.Timezone)
}

func Load(env ...Environment) (*Config, error) {
	var err error
	once.Do(func() {
		targetEnv := EnvDevelopment
		if len(env) > 0 {
			targetEnv = env[0]
		} else {
			envFromEnv := os.Getenv("APP_ENV")
			if envFromEnv != "" {
				targetEnv = Environment(strings.ToLower(envFromEnv))
			}
		}

		envFile := fmt.Sprintf(".env.%s", targetEnv)
		if _, statErr := os.Stat(envFile); statErr == nil {
			if loadErr := godotenv.Load(envFile); loadErr != nil {
				fmt.Printf("Warning: failed to load %s: %v\n", envFile, loadErr)
			}
		} else if _, statErr := os.Stat(".env"); statErr == nil {
			if loadErr := godotenv.Load(); loadErr != nil {
				fmt.Printf("Warning: failed to load .env: %v\n", loadErr)
			}
		}

		configFile := fmt.Sprintf("config/%s.json", targetEnv)
		var data []byte
		data, readErr := os.ReadFile(configFile)
		if readErr != nil {
			data, readErr = os.ReadFile("config/default.json")
			if readErr != nil {
				err = fmt.Errorf("no config file found: %w", readErr)
				return
			}
		}

		instance = &Config{}
		if parseErr := json.Unmarshal(data, instance); parseErr != nil {
			err = fmt.Errorf("failed to parse config: %w", parseErr)
			return
		}

		instance.Env = targetEnv
		overrideFromEnv(instance)
	})
	return instance, err
}

func overrideFromEnv(cfg *Config) {
	if port := os.Getenv("SERVER_PORT"); port != "" {
		fmt.Sscanf(port, "%d", &cfg.Server.Port)
	}
	if host := os.Getenv("SERVER_HOST"); host != "" {
		cfg.Server.Host = host
	}
	if mode := os.Getenv("GIN_MODE"); mode != "" {
		cfg.Server.Mode = mode
	}

	if dbHost := os.Getenv("DB_HOST"); dbHost != "" {
		cfg.Database.Host = dbHost
	}
	if dbPort := os.Getenv("DB_PORT"); dbPort != "" {
		fmt.Sscanf(dbPort, "%d", &cfg.Database.Port)
	}
	if dbUser := os.Getenv("DB_USER"); dbUser != "" {
		cfg.Database.User = dbUser
	}
	if dbPass := os.Getenv("DB_PASSWORD"); dbPass != "" {
		cfg.Database.Password = dbPass
	}
	if dbName := os.Getenv("DB_NAME"); dbName != "" {
		cfg.Database.DBName = dbName
	}

	if redisAddr := os.Getenv("REDIS_ADDR"); redisAddr != "" {
		cfg.Redis.Addr = redisAddr
	}
	if redisPass := os.Getenv("REDIS_PASSWORD"); redisPass != "" {
		cfg.Redis.Password = redisPass
	}

	if logLevel := os.Getenv("LOG_LEVEL"); logLevel != "" {
		cfg.Log.Level = logLevel
	}

	if jwtSecret := os.Getenv("JWT_SECRET"); jwtSecret != "" {
		cfg.Modules.Auth.JWTSecret = jwtSecret
	}
}

func Get() *Config {
	if instance == nil {
		instance = &Config{}
	}
	return instance
}

func GetEnvironment() Environment {
	if instance != nil {
		return instance.Env
	}
	envStr := os.Getenv("APP_ENV")
	if envStr == "" {
		return EnvDevelopment
	}
	return Environment(strings.ToLower(envStr))
}

func IsProduction() bool {
	return GetEnvironment() == EnvProduction
}

func IsDevelopment() bool {
	return GetEnvironment() == EnvDevelopment
}

func IsTesting() bool {
	return GetEnvironment() == EnvTesting
}
