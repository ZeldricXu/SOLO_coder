package config

import (
	"log"
	"os"
	"strconv"

	"github.com/joho/godotenv"
)

type Config struct {
	AppName string
	AppEnv  string
	AppPort string

	DBHost     string
	DBPort     string
	DBUser     string
	DBPassword string
	DBName     string

	RedisHost     string
	RedisPort     string
	RedisPassword string
	RedisDB       int

	LogLevel string

	JWTSecret      string
	JWTExpireHours int
}

var AppConfig *Config

func Load() {
	if err := godotenv.Load(); err != nil {
		log.Printf("Warning: .env file not found, using environment variables: %v", err)
	}

	redisDB, _ := strconv.Atoi(getEnv("REDIS_DB", "0"))
	jwtExpireHours, _ := strconv.Atoi(getEnv("JWT_EXPIRE_HOURS", "24"))

	AppConfig = &Config{
		AppName: getEnv("APP_NAME", "depguard"),
		AppEnv:  getEnv("APP_ENV", "development"),
		AppPort: getEnv("APP_PORT", "8080"),

		DBHost:     getEnv("DB_HOST", "localhost"),
		DBPort:     getEnv("DB_PORT", "5432"),
		DBUser:     getEnv("DB_USER", "postgres"),
		DBPassword: getEnv("DB_PASSWORD", "postgres"),
		DBName:     getEnv("DB_NAME", "depguard"),

		RedisHost:     getEnv("REDIS_HOST", "localhost"),
		RedisPort:     getEnv("REDIS_PORT", "6379"),
		RedisPassword: getEnv("REDIS_PASSWORD", ""),
		RedisDB:       redisDB,

		LogLevel: getEnv("LOG_LEVEL", "info"),

		JWTSecret:      getEnv("JWT_SECRET", "depguard-secret-key"),
		JWTExpireHours: jwtExpireHours,
	}
}

func getEnv(key, defaultValue string) string {
	if value, exists := os.LookupEnv(key); exists {
		return value
	}
	return defaultValue
}
