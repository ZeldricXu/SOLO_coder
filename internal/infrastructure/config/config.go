package config

import (
	"os"
	"strconv"
	"time"
)

type Config struct {
	Server   ServerConfig
	Database DatabaseConfig
	Cache    CacheConfig
	Logger   LoggerConfig
	MQTT     MQTTConfig
	WAL      WALConfig
}

type ServerConfig struct {
	Host         string
	Port         int
	ReadTimeout  time.Duration
	WriteTimeout time.Duration
}

type DatabaseConfig struct {
	Host     string
	Port     int
	User     string
	Password string
	DBName   string
	SSLMode  string
}

type CacheConfig struct {
	Host     string
	Port     int
	Password string
	DB       int
	PoolSize int
}

type LoggerConfig struct {
	Level      string
	Filename   string
	MaxSize    int
	MaxBackups int
	MaxAge     int
	Compress   bool
}

type MQTTConfig struct {
	Broker   string
	ClientID string
	Username string
	Password string
	QoS      int
}

type WALConfig struct {
	Dir           string
	MaxFileSize   int64
	FlushInterval time.Duration
	Enabled       bool
}

func Load() *Config {
	return &Config{
		Server: ServerConfig{
			Host:         getEnv("SERVER_HOST", "0.0.0.0"),
			Port:         getEnvInt("SERVER_PORT", 8080),
			ReadTimeout:  time.Duration(getEnvInt("SERVER_READ_TIMEOUT", 30)) * time.Second,
			WriteTimeout: time.Duration(getEnvInt("SERVER_WRITE_TIMEOUT", 30)) * time.Second,
		},
		Database: DatabaseConfig{
			Host:     getEnv("DB_HOST", "localhost"),
			Port:     getEnvInt("DB_PORT", 5432),
			User:     getEnv("DB_USER", "postgres"),
			Password: getEnv("DB_PASSWORD", "postgres"),
			DBName:   getEnv("DB_NAME", "edgevision"),
			SSLMode:  getEnv("DB_SSLMODE", "disable"),
		},
		Cache: CacheConfig{
			Host:     getEnv("REDIS_HOST", "localhost"),
			Port:     getEnvInt("REDIS_PORT", 6379),
			Password: getEnv("REDIS_PASSWORD", ""),
			DB:       getEnvInt("REDIS_DB", 0),
			PoolSize: getEnvInt("REDIS_POOL_SIZE", 10),
		},
		Logger: LoggerConfig{
			Level:      getEnv("LOG_LEVEL", "info"),
			Filename:   getEnv("LOG_FILENAME", "logs/edgevision.log"),
			MaxSize:    getEnvInt("LOG_MAX_SIZE", 100),
			MaxBackups: getEnvInt("LOG_MAX_BACKUPS", 3),
			MaxAge:     getEnvInt("LOG_MAX_AGE", 28),
			Compress:   getEnvBool("LOG_COMPRESS", true),
		},
		MQTT: MQTTConfig{
			Broker:   getEnv("MQTT_BROKER", "tcp://localhost:1883"),
			ClientID: getEnv("MQTT_CLIENT_ID", "edgevision"),
			Username: getEnv("MQTT_USERNAME", ""),
			Password: getEnv("MQTT_PASSWORD", ""),
			QoS:      getEnvInt("MQTT_QOS", 1),
		},
		WAL: WALConfig{
			Dir:           getEnv("WAL_DIR", "data/wal"),
			MaxFileSize:   int64(getEnvInt("WAL_MAX_FILE_SIZE", 64)) * 1024 * 1024,
			FlushInterval: time.Duration(getEnvInt("WAL_FLUSH_INTERVAL", 100)) * time.Millisecond,
			Enabled:       getEnvBool("WAL_ENABLED", true),
		},
	}
}

func getEnv(key, defaultValue string) string {
	if value, exists := os.LookupEnv(key); exists {
		return value
	}
	return defaultValue
}

func getEnvInt(key string, defaultValue int) int {
	if value, exists := os.LookupEnv(key); exists {
		if intValue, err := strconv.Atoi(value); err == nil {
			return intValue
		}
	}
	return defaultValue
}

func getEnvBool(key string, defaultValue bool) bool {
	if value, exists := os.LookupEnv(key); exists {
		if boolValue, err := strconv.ParseBool(value); err == nil {
			return boolValue
		}
	}
	return defaultValue
}
