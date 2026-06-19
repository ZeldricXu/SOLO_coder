package config

import (
	"log"
	"os"
	"strconv"
	"time"

	"github.com/joho/godotenv"
)

type Config struct {
	Server   ServerConfig
	Database DatabaseConfig
	Redis    RedisConfig
	MinIO    MinIOConfig
	Bleve    BleveConfig
	JWT      JWTConfig
	CORS     CORSConfig
	OT       OTConfig
	Tika     TikaConfig
	Snapshot SnapshotConfig
}

type SnapshotConfig struct {
	ColdBucket             string
	DefaultRetentionDays   int
	MaxConcurrentSnapshots int
	TempDir                string
}

type TikaConfig struct {
	Endpoint   string
	Timeout    time.Duration
	Username   string
	Password   string
	EnableOCR  bool
	OCRService string
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
	return "host=" + c.Host +
		" port=" + c.Port +
		" user=" + c.User +
		" password=" + c.Password +
		" dbname=" + c.DBName +
		" sslmode=" + c.SSLMode +
		" TimeZone=" + c.Timezone
}

type DatabaseConfig = PostgreSQLConfig

type RedisConfig struct {
	Addr     string
	Password string
	DB       int
	PoolSize int
}

type MinIOConfig struct {
	Endpoint  string
	AccessKey string
	SecretKey string
	UseSSL    bool
	Bucket    string
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
		Database: DatabaseConfig{
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
			Addr:     getEnv("REDIS_ADDR", "localhost:6379"),
			Password: getEnv("REDIS_PASSWORD", ""),
			DB:       getEnvInt("REDIS_DB", 0),
			PoolSize: getEnvInt("REDIS_POOL_SIZE", 100),
		},
		MinIO: MinIOConfig{
			Endpoint:  getEnv("MINIO_ENDPOINT", "localhost:9000"),
			AccessKey: getEnv("MINIO_ACCESS_KEY", "minioadmin"),
			SecretKey: getEnv("MINIO_SECRET_KEY", "minioadmin"),
			UseSSL:    getEnvBool("MINIO_USE_SSL", false),
			Bucket:    getEnv("MINIO_BUCKET", "knowledgebase"),
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
		Tika: TikaConfig{
			Endpoint:   getEnv("TIKA_ENDPOINT", "http://localhost:9998"),
			Timeout:    time.Duration(getEnvInt("TIKA_TIMEOUT", 30)) * time.Second,
			Username:   getEnv("TIKA_USERNAME", ""),
			Password:   getEnv("TIKA_PASSWORD", ""),
			EnableOCR:  getEnvBool("TIKA_ENABLE_OCR", false),
			OCRService: getEnv("TIKA_OCR_SERVICE", ""),
		},
		Snapshot: SnapshotConfig{
			ColdBucket:             getEnv("SNAPSHOT_COLD_BUCKET", "knowledgebase-snapshots"),
			DefaultRetentionDays:   getEnvInt("SNAPSHOT_DEFAULT_RETENTION_DAYS", 90),
			MaxConcurrentSnapshots: getEnvInt("SNAPSHOT_MAX_CONCURRENT", 2),
			TempDir:                getEnv("SNAPSHOT_TEMP_DIR", "./data/snapshots/tmp"),
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
