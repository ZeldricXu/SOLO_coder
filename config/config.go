package config

import (
	"os"
	"sync"
)

type Config struct {
	Server   ServerConfig
	Database DatabaseConfig
	Redis    RedisConfig
	Search   SearchConfig
}

type ServerConfig struct {
	Port    string
	Mode    string
}

type DatabaseConfig struct {
	Host     string
	Port     string
	User     string
	Password string
	DBName   string
	SSLMode  string
}

type RedisConfig struct {
	Host     string
	Port     string
	Password string
	DB       int
}

type SearchConfig struct {
	IndexPath string
}

var (
	cfg  *Config
	once sync.Once
)

func Get() *Config {
	once.Do(func() {
		cfg = &Config{
			Server: ServerConfig{
				Port: getEnv("SERVER_PORT", "8080"),
				Mode: getEnv("GIN_MODE", "debug"),
			},
			Database: DatabaseConfig{
				Host:     getEnv("DB_HOST", "localhost"),
				Port:     getEnv("DB_PORT", "5432"),
				User:     getEnv("DB_USER", "postgres"),
				Password: getEnv("DB_PASSWORD", "postgres"),
				DBName:   getEnv("DB_NAME", "depguard"),
				SSLMode:  getEnv("DB_SSLMODE", "disable"),
			},
			Redis: RedisConfig{
				Host:     getEnv("REDIS_HOST", "localhost"),
				Port:     getEnv("REDIS_PORT", "6379"),
				Password: getEnv("REDIS_PASSWORD", ""),
				DB:       0,
			},
			Search: SearchConfig{
				IndexPath: getEnv("SEARCH_INDEX_PATH", "./data/search_index"),
			},
		}
	})
	return cfg
}

func getEnv(key, defaultValue string) string {
	if value, exists := os.LookupEnv(key); exists {
		return value
	}
	return defaultValue
}
