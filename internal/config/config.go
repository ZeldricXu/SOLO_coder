package config

import (
	"os"
	"strconv"
)

type Config struct {
	ServerPort    int
	DBHost        string
	DBPort        int
	DBUser        string
	DBPassword    string
	DBName        string
	RedisHost     string
	RedisPort     int
	RedisPassword string
	RedisDB       int
	LogLevel      string
}

func Load() *Config {
	return &Config{
		ServerPort:    getEnvInt("SERVER_PORT", 8080),
		DBHost:        getEnvStr("DB_HOST", "localhost"),
		DBPort:        getEnvInt("DB_PORT", 5432),
		DBUser:        getEnvStr("DB_USER", "postgres"),
		DBPassword:    getEnvStr("DB_PASSWORD", "postgres"),
		DBName:        getEnvStr("DB_NAME", "metrics"),
		RedisHost:     getEnvStr("REDIS_HOST", "localhost"),
		RedisPort:     getEnvInt("REDIS_PORT", 6379),
		RedisPassword: getEnvStr("REDIS_PASSWORD", ""),
		RedisDB:       getEnvInt("REDIS_DB", 0),
		LogLevel:      getEnvStr("LOG_LEVEL", "info"),
	}
}

func getEnvStr(key, defaultValue string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultValue
}

func getEnvInt(key string, defaultValue int) int {
	if v := os.Getenv(key); v != "" {
		if i, err := strconv.Atoi(v); err == nil {
			return i
		}
	}
	return defaultValue
}
