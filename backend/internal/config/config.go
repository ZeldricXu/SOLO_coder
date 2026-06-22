package config

import (
	"fmt"
	"strings"

	"github.com/spf13/viper"
)

type Config struct {
	Server      ServerConfig      `mapstructure:"server"`
	Database    DatabaseConfig    `mapstructure:"database"`
	Redis       RedisConfig       `mapstructure:"redis"`
	Kafka       KafkaConfig       `mapstructure:"kafka"`
	Cache       CacheConfig       `mapstructure:"cache"`
	SDK         SDKConfig         `mapstructure:"sdk"`
	Auth        AuthConfig        `mapstructure:"auth"`
	AutoRollback AutoRollbackConfig `mapstructure:"auto_rollback"`
}

type ServerConfig struct {
	Port int    `mapstructure:"port"`
	Mode string `mapstructure:"mode"`
}

type DatabaseConfig struct {
	Host         string `mapstructure:"host"`
	Port         int    `mapstructure:"port"`
	User         string `mapstructure:"user"`
	Password     string `mapstructure:"password"`
	DBName       string `mapstructure:"dbname"`
	SSLMode      string `mapstructure:"sslmode"`
	MaxIdleConns int    `mapstructure:"max_idle_conns"`
	MaxOpenConns int    `mapstructure:"max_open_conns"`
}

type RedisConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	Password string `mapstructure:"password"`
	DB       int    `mapstructure:"db"`
	PoolSize int    `mapstructure:"pool_size"`
}

type KafkaConfig struct {
	Brokers []string `mapstructure:"brokers"`
	Topic   string   `mapstructure:"topic"`
	GroupID string   `mapstructure:"group_id"`
}

type CacheConfig struct {
	Type    string `mapstructure:"type"`
	TTL     int    `mapstructure:"ttl"`
	MaxSize int    `mapstructure:"max_size"`
}

type SDKConfig struct {
	PollInterval          int `mapstructure:"poll_interval"`
	LongPollTimeout       int `mapstructure:"long_poll_timeout"`
	CircuitBreakerThreshold int `mapstructure:"circuit_breaker_threshold"`
	CircuitBreakerTimeout  int `mapstructure:"circuit_breaker_timeout"`
}

type AuthConfig struct {
	JWTSecret string `mapstructure:"jwt_secret"`
	JWTExpire int    `mapstructure:"jwt_expire"`
}

type AutoRollbackConfig struct {
	Enabled       bool `mapstructure:"enabled"`
	CheckInterval int  `mapstructure:"check_interval"`
	WindowMinutes int  `mapstructure:"window_minutes"`
}

var AppConfig *Config

func Load() error {
	v := viper.New()
	v.SetConfigName("config")
	v.SetConfigType("yaml")
	v.AddConfigPath(".")
	v.AddConfigPath("./config")
	v.AddConfigPath("/etc/featureflag")

	v.SetEnvPrefix("FF")
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	v.AutomaticEnv()

	if err := v.ReadInConfig(); err != nil {
		return fmt.Errorf("read config error: %w", err)
	}

	AppConfig = &Config{}
	if err := v.Unmarshal(AppConfig); err != nil {
		return fmt.Errorf("unmarshal config error: %w", err)
	}

	return nil
}

func (c *DatabaseConfig) DSN() string {
	return fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
		c.Host, c.Port, c.User, c.Password, c.DBName, c.SSLMode)
}

func (c *RedisConfig) Addr() string {
	return fmt.Sprintf("%s:%d", c.Host, c.Port)
}
