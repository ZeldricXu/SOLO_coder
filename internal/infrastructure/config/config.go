package config

import (
	"fmt"
	"os"
	"strings"

	"github.com/spf13/viper"
)

type Config struct {
	Server   ServerConfig   `mapstructure:"server"`
	Database DatabaseConfig `mapstructure:"database"`
	Redis    RedisConfig    `mapstructure:"redis"`
	Logger   LoggerConfig   `mapstructure:"logger"`
	JWT      JWTConfig      `mapstructure:"jwt"`
	RateLimit RateLimitConfig `mapstructure:"rate_limit"`
}

type ServerConfig struct {
	Host         string `mapstructure:"host"`
	Port         int    `mapstructure:"port"`
	Mode         string `mapstructure:"mode"`
	ReadTimeout  int    `mapstructure:"read_timeout"`
	WriteTimeout int    `mapstructure:"write_timeout"`
}

type DatabaseConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	User     string `mapstructure:"user"`
	Password string `mapstructure:"password"`
	DBName   string `mapstructure:"dbname"`
	SSLMode  string `mapstructure:"sslmode"`
	TimeZone string `mapstructure:"timezone"`
}

type RedisConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	Password string `mapstructure:"password"`
	DB       int    `mapstructure:"db"`
	PoolSize int    `mapstructure:"pool_size"`
}

type LoggerConfig struct {
	Level      string `mapstructure:"level"`
	Format     string `mapstructure:"format"`
	OutputPath string `mapstructure:"output_path"`
}

type JWTConfig struct {
	Secret     string `mapstructure:"secret"`
	ExpireHours int   `mapstructure:"expire_hours"`
}

type RateLimitConfig struct {
	RequestsPerSecond int `mapstructure:"requests_per_second"`
	BurstSize         int `mapstructure:"burst_size"`
}

var AppConfig *Config

func Load(configPath string) (*Config, error) {
	v := viper.New()

	if configPath != "" {
		v.SetConfigFile(configPath)
	} else {
		v.SetConfigName("config")
		v.SetConfigType("yaml")
		v.AddConfigPath("./configs")
		v.AddConfigPath("/etc/session189")
		v.AddConfigPath(".")
	}

	v.SetEnvPrefix("SESSION189")
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	v.AutomaticEnv()

	bindEnvs(v)

	if err := v.ReadInConfig(); err != nil {
		if _, ok := err.(viper.ConfigFileNotFoundError); ok {
			fmt.Printf("Warning: Config file not found, using environment variables only\n")
		} else {
			return nil, fmt.Errorf("read config file failed: %w", err)
		}
	}

	config := &Config{}
	if err := v.Unmarshal(config); err != nil {
		return nil, fmt.Errorf("unmarshal config failed: %w", err)
	}

	AppConfig = config
	return config, nil
}

func bindEnvs(v *viper.Viper) {
	envMappings := map[string]string{
		"server.host":           "SERVER_HOST",
		"server.port":           "SERVER_PORT",
		"server.mode":           "SERVER_MODE",
		"database.host":         "DB_HOST",
		"database.port":         "DB_PORT",
		"database.user":         "DB_USER",
		"database.password":     "DB_PASSWORD",
		"database.dbname":       "DB_NAME",
		"redis.host":            "REDIS_HOST",
		"redis.port":            "REDIS_PORT",
		"redis.password":        "REDIS_PASSWORD",
		"redis.db":              "REDIS_DB",
		"jwt.secret":            "JWT_SECRET",
		"logger.level":          "LOG_LEVEL",
	}

	for key, env := range envMappings {
		if os.Getenv(env) != "" {
			v.Set(key, os.Getenv(env))
		}
	}
}

func (c *DatabaseConfig) DSN() string {
	return fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=%s TimeZone=%s",
		c.Host, c.Port, c.User, c.Password, c.DBName, c.SSLMode, c.TimeZone)
}

func (c *RedisConfig) Addr() string {
	return fmt.Sprintf("%s:%d", c.Host, c.Port)
}

func (c *ServerConfig) Addr() string {
	return fmt.Sprintf("%s:%d", c.Host, c.Port)
}
