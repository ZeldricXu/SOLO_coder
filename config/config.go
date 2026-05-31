package config

import (
	"strconv"
	"time"
)

type Config struct {
	Server   ServerConfig   `json:"server"`
	Database DatabaseConfig `json:"database"`
	Redis    RedisConfig    `json:"redis"`
	Logger   LoggerConfig   `json:"logger"`
}

type ServerConfig struct {
	Host string `json:"host"`
	Port int    `json:"port"`
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

type LoggerConfig struct {
	Level      string `json:"level"`
	Format     string `json:"format"`
	OutputPath string `json:"output_path"`
}

func Load() *Config {
	return &Config{
		Server: ServerConfig{
			Host: "0.0.0.0",
			Port: 8080,
		},
		Database: DatabaseConfig{
			Host:     "localhost",
			Port:     5432,
			User:     "postgres",
			Password: "postgres",
			DBName:   "notification",
			SSLMode:  "disable",
		},
		Redis: RedisConfig{
			Host:     "localhost",
			Port:     6379,
			Password: "",
			DB:       0,
		},
		Logger: LoggerConfig{
			Level:      "info",
			Format:     "json",
			OutputPath: "stdout",
		},
	}
}

func (c *DatabaseConfig) DSN() string {
	return "host=" + c.Host + " port=" + strconv.Itoa(c.Port) + " user=" + c.User + " password=" + c.Password + " dbname=" + c.DBName + " sslmode=" + c.SSLMode
}

func (c *RedisConfig) Addr() string {
	return c.Host + ":" + strconv.Itoa(c.Port)
}

const (
	DefaultRetryInterval     = 30 * time.Second
	DefaultMaxRetries        = 3
	DefaultQueueSize         = 10000
	DefaultWorkerCount       = 5
	DefaultSuppressionWindow = 5 * time.Minute
	DefaultRateLimitWindow   = 1 * time.Minute
	DefaultRateLimitCount    = 10
)
