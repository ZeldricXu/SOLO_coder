package config

import "os"

type Config struct {
	DB struct {
		Host     string
		Port     string
		User     string
		Password string
		Name     string
	}
	Redis struct {
		Host string
		Port string
	}
	MinIO struct {
		Endpoint   string
		AccessKey  string
		SecretKey  string
		Bucket     string
	}
	JWT struct {
		Secret string
	}
	Port          string
	ESignProvider string
	ESignAPIKey   string
	ESignAPIURL   string
}

func Load() *Config {
	c := &Config{}

	c.DB.Host = getEnv("DB_HOST", "localhost")
	c.DB.Port = getEnv("DB_PORT", "5432")
	c.DB.User = getEnv("DB_USER", "postgres")
	c.DB.Password = getEnv("DB_PASSWORD", "")
	c.DB.Name = getEnv("DB_NAME", "onboarding")

	c.Redis.Host = getEnv("REDIS_HOST", "localhost")
	c.Redis.Port = getEnv("REDIS_PORT", "6379")

	c.MinIO.Endpoint = getEnv("MINIO_ENDPOINT", "localhost:9000")
	c.MinIO.AccessKey = getEnv("MINIO_ACCESS_KEY", "")
	c.MinIO.SecretKey = getEnv("MINIO_SECRET_KEY", "")
	c.MinIO.Bucket = getEnv("MINIO_BUCKET", "onboarding")

	c.JWT.Secret = getEnv("JWT_SECRET", "")

	c.Port = getEnv("PORT", "8080")

	c.ESignProvider = getEnv("ESIGN_PROVIDER", "")
	c.ESignAPIKey = getEnv("ESIGN_API_KEY", "")
	c.ESignAPIURL = getEnv("ESIGN_API_URL", "")

	return c
}

func (c *Config) DBConnectionString() string {
	return "host=" + c.DB.Host +
		" port=" + c.DB.Port +
		" user=" + c.DB.User +
		" password=" + c.DB.Password +
		" dbname=" + c.DB.Name +
		" sslmode=disable"
}

func (c *Config) RedisAddr() string {
	return c.Redis.Host + ":" + c.Redis.Port
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
