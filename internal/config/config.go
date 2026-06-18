package config

import (
	"fmt"
	"log"
	"os"
	"strconv"

	"github.com/joho/godotenv"
)

type Config struct {
	Server   ServerConfig
	PostgreSQL PostgreSQLConfig
	Redis    RedisConfig
	MinIO    MinIOConfig
	Bleve    BleveConfig
	JWT      JWTConfig
	CORS     CORSConfig
	OT       OTConfig
}

type ServerConfig struct {
	Port         string
	Mode         string
	ReadTimeout  int
	WriteTimeout int
}

type PostgreSQLConfig struct {
	Host         string
	Port         string
	User         string
	Password     string
	DBName       string
	SSLMode      string
	Timezone     string
	MaxOpenConns int
	MaxIdleConns int
}

func (c PostgreSQLConfig) DSN() string {
	return fmt.Sprintf(
		"host=%s port=%s user=%s password=%s dbname=%s sslmode=%s TimeZone=%s",
		c.Host, c.Port, c.User, c.Password, c.DBName, c.SSLMode, c.Timezone,
	)
}

type RedisConfig struct {
	Host         string
	Port         string
	Password     string
	DB           int
	PoolSize     int
	MinIdleConns int
}

func (c RedisConfig) Addr() string {
	return fmt.Sprintf("%s:%s", c.Host, c.Port)
}

type MinIOConfig struct {
	Endpoint        string
	AccessKeyID     string
	SecretAccessKey string
	UseSSL          bool
	BucketName      string
	Region          string
}

type BleveConfig struct {
	IndexPath string
}

type JWTConfig struct {
	Secret     string
	ExpireHour int
	Issuer     string
}

type CORSConfig struct {
	AllowOrigins string
	AllowMethods string
	AllowHeaders string
}

type OTConfig struct {
	BufferSize    int
	FlushInterval int
	MaxVersionGap int
}

var AppConfig *Config

func Load() *Config {
	if err := godotenv.Load("configs/.env"); err != nil {
		log.Printf("Warning: .env file not found: %v", err)
	}

	AppConfig = &Config{
		Server: ServerConfig{
			Port:         getEnv("SERVER_PORT", "8080"),
			Mode:         getEnv("SERVER_MODE", "release"),
			ReadTimeout:  getEnvInt("SERVER_READ_TIMEOUT", 30),
			WriteTimeout: getEnvInt("SERVER_WRITE_TIMEOUT", 30),
		},
		PostgreSQL: PostgreSQLConfig{
			Host:         getEnv("DB_HOST", "localhost"),
			Port:         getEnv("DB_PORT", "5432"),
			User:         getEnv("DB_USER", "postgres"),
			Password:     getEnv("DB_PASSWORD", "postgres"),
			DBName:       getEnv("DB_NAME", "knowledgebase"),
			SSLMode:      getEnv("DB_SSLMODE", "disable"),
			Timezone:     getEnv("DB_TIMEZONE", "Asia/Shanghai"),
			MaxOpenConns: getEnvInt("DB_MAX_OPEN_CONNS", 100),
			MaxIdleConns: getEnvInt("DB_MAX_IDLE_CONNS", 10),
		},
		Redis: RedisConfig{
			Host:         getEnv("REDIS_HOST", "localhost"),
			Port:         getEnv("REDIS_PORT", "6379"),
			Password:     getEnv("REDIS_PASSWORD", ""),
			DB:           getEnvInt("REDIS_DB", 0),
			PoolSize:     getEnvInt("REDIS_POOL_SIZE", 100),
			MinIdleConns: getEnvInt("REDIS_MIN_IDLE_CONNS", 10),
		},
		MinIO: MinIOConfig{
			Endpoint:        getEnv("MINIO_ENDPOINT", "localhost:9000"),
			AccessKeyID:     getEnv("MINIO_ACCESS_KEY", "minioadmin"),
			SecretAccessKey: getEnv("MINIO_SECRET_KEY", "minioadmin"),
			UseSSL:          getEnvBool("MINIO_USE_SSL", false),
			BucketName:      getEnv("MINIO_BUCKET", "knowledgebase"),
			Region:          getEnv("MINIO_REGION", "us-east-1"),
		},
		Bleve: BleveConfig{
			IndexPath: getEnv("BLEVE_INDEX_PATH", "./data/bleve"),
		},
		JWT: JWTConfig{
			Secret:     getEnv("JWT_SECRET", "change-me-please-in-production"),
			ExpireHour: getEnvInt("JWT_EXPIRE_HOUR", 24),
			Issuer:     getEnv("JWT_ISSUER", "knowledgebase"),
		},
		CORS: CORSConfig{
			AllowOrigins: getEnv("CORS_ALLOW_ORIGINS", "*"),
			AllowMethods: getEnv("CORS_ALLOW_METHODS", "GET,POST,PUT,DELETE,PATCH,OPTIONS"),
			AllowHeaders: getEnv("CORS_ALLOW_HEADERS", "Origin,Content-Type,Accept,Authorization,X-Tenant-ID"),
		},
		OT: OTConfig{
			BufferSize:    getEnvInt("OT_BUFFER_SIZE", 1000),
			FlushInterval: getEnvInt("OT_FLUSH_INTERVAL", 100),
			MaxVersionGap: getEnvInt("OT_MAX_VERSION_GAP", 100),
		},
	}

	return AppConfig
}

func getEnv(key, fallback string) string {
	if value, ok := os.LookupEnv(key); ok {
		return value
	}
	return fallback
}

func getEnvInt(key string, fallback int) int {
	if value, ok := os.LookupEnv(key); ok {
		if v, err := strconv.Atoi(value); err == nil {
			return v
		}
	}
	return fallback
}

func getEnvBool(key string, fallback bool) bool {
	if value, ok := os.LookupEnv(key); ok {
		if v, err := strconv.ParseBool(value); err == nil {
			return v
		}
	}
	return fallback
}
