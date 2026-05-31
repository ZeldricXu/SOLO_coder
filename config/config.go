package config

import (
	"time"
)

type Config struct {
	Server    ServerConfig    `mapstructure:"server"`
	Database  DatabaseConfig  `mapstructure:"database"`
	Cache     CacheConfig     `mapstructure:"cache"`
	Logger    LoggerConfig    `mapstructure:"logger"`
	Prometheus PrometheusConfig `mapstructure:"prometheus"`
}

type ServerConfig struct {
	Host         string        `mapstructure:"host"`
	Port         int           `mapstructure:"port"`
	ReadTimeout  time.Duration `mapstructure:"read_timeout"`
	WriteTimeout time.Duration `mapstructure:"write_timeout"`
	MaxBodySize  int64         `mapstructure:"max_body_size"`
}

type DatabaseConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	User     string `mapstructure:"user"`
	Password string `mapstructure:"password"`
	DBName   string `mapstructure:"dbname"`
	SSLMode  string `mapstructure:"sslmode"`
	PoolSize int    `mapstructure:"pool_size"`
}

type CacheConfig struct {
	Redis RedisConfig   `mapstructure:"redis"`
	L1    L1CacheConfig `mapstructure:"l1"`
}

type RedisConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	Password string `mapstructure:"password"`
	DB       int    `mapstructure:"db"`
	PoolSize int    `mapstructure:"pool_size"`
}

type L1CacheConfig struct {
	MaxEntries int           `mapstructure:"max_entries"`
	TTL        time.Duration `mapstructure:"ttl"`
}

type LoggerConfig struct {
	Level  string `mapstructure:"level"`
	Format string `mapstructure:"format"`
	Output string `mapstructure:"output"`
}

type PrometheusConfig struct {
	Enabled bool   `mapstructure:"enabled"`
	Path    string `mapstructure:"path"`
}
