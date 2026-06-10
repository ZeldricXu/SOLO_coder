package config

import (
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/spf13/viper"
)

type Config struct {
	Log         LogConfig         `mapstructure:"log"`
	Server      ServerConfig      `mapstructure:"server"`
	Database    DatabaseConfig    `mapstructure:"database"`
	Redis       RedisConfig       `mapstructure:"redis"`
	MinIO       MinIOConfig       `mapstructure:"minio"`
	Vault       VaultConfig       `mapstructure:"vault"`
	Scheduler   SchedulerConfig   `mapstructure:"scheduler"`
	Plugin      PluginConfig      `mapstructure:"plugin"`
	Notification NotificationConfig `mapstructure:"notification"`
	Retention   RetentionConfig   `mapstructure:"retention"`
	LogStore    LogStoreConfig    `mapstructure:"logstore"`
	Webhook     WebhookConfig     `mapstructure:"webhook"`
	Artifact    ArtifactConfig    `mapstructure:"artifact"`
}

type LogConfig struct {
	Level  string `mapstructure:"level"`
	Format string `mapstructure:"format"`
}

type ServerConfig struct {
	Host string `mapstructure:"host"`
	Port int    `mapstructure:"port"`
	Mode string `mapstructure:"mode"`
}

type DatabaseConfig struct {
	Host         string `mapstructure:"host"`
	Port         int    `mapstructure:"port"`
	User         string `mapstructure:"user"`
	Password     string `mapstructure:"password"`
	Name         string `mapstructure:"name"`
	SSLMode      string `mapstructure:"sslmode"`
	MaxOpenConns int    `mapstructure:"max_open_conns"`
	MaxIdleConns int    `mapstructure:"max_idle_conns"`
}

type RedisConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	Password string `mapstructure:"password"`
	DB       int    `mapstructure:"db"`
	PoolSize int    `mapstructure:"pool_size"`
}

type MinIOConfig struct {
	Endpoint  string `mapstructure:"endpoint"`
	AccessKey string `mapstructure:"access_key"`
	SecretKey string `mapstructure:"secret_key"`
	Bucket    string `mapstructure:"bucket"`
	Secure    bool   `mapstructure:"secure"`
}

type VaultConfig struct {
	Addr        string        `mapstructure:"addr"`
	Token       string        `mapstructure:"token"`
	SecretPath  string        `mapstructure:"secret_path"`
	CacheTTL    time.Duration `mapstructure:"cache_ttl"`
}

type SchedulerConfig struct {
	MaxConcurrent int   `mapstructure:"max_concurrent"`
	DefaultTimeout int64 `mapstructure:"default_timeout"`
	QueueSize    int   `mapstructure:"queue_size"`
}

type PluginConfig struct {
	Dir           string `mapstructure:"dir"`
	RegistryFile  string `mapstructure:"registry_file"`
}

type NotificationConfig struct {
	DingTalk DingTalkConfig `mapstructure:"dingtalk"`
	FeiShu   FeiShuConfig   `mapstructure:"feishu"`
	Slack    SlackConfig    `mapstructure:"slack"`
	SMTP     SMTPConfig     `mapstructure:"smtp"`
}

type DingTalkConfig struct {
	Webhook string `mapstructure:"webhook"`
	Secret  string `mapstructure:"secret"`
}

type FeiShuConfig struct {
	Webhook string `mapstructure:"webhook"`
	Secret  string `mapstructure:"secret"`
}

type SlackConfig struct {
	Webhook string `mapstructure:"webhook"`
}

type SMTPConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	User     string `mapstructure:"user"`
	Password string `mapstructure:"password"`
	From     string `mapstructure:"from"`
}

type RetentionConfig struct {
	ArtifactDays int `mapstructure:"artifact_days"`
	LogDays      int `mapstructure:"log_days"`
}

type ArtifactConfig struct {
	RetentionDays int `mapstructure:"retention_days"`
	KeepLast      int `mapstructure:"keep_last"`
	MaxSizeMB     int `mapstructure:"max_size_mb"`
}

type LogStoreConfig struct {
	EnablePostgres bool   `mapstructure:"enable_postgres"`
	EnableRedis    bool   `mapstructure:"enable_redis"`
	RedisChannel   string `mapstructure:"redis_channel"`
	RetentionDays  int    `mapstructure:"retention_days"`
	BatchSize      int    `mapstructure:"batch_size"`
}

type WebhookConfig struct {
	Secret string `mapstructure:"secret"`
}

var globalConfig *Config

func Load() (*Config, error) {
	v := viper.New()
	v.SetEnvPrefix("CLOUDCI")
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	v.AutomaticEnv()

	env := GetEnv()
	v.SetConfigName(fmt.Sprintf("config.%s", env))
	v.SetConfigType("yaml")
	v.AddConfigPath(".")
	v.AddConfigPath("./config")
	v.AddConfigPath("/etc/cloudci")

	if err := v.ReadInConfig(); err != nil {
		if _, ok := err.(viper.ConfigFileNotFoundError); !ok {
			return nil, fmt.Errorf("failed to read config file: %w", err)
		}
	}

	v.SetDefault("log.level", "info")
	v.SetDefault("log.format", "json")
	v.SetDefault("server.host", "0.0.0.0")
	v.SetDefault("server.port", 8080)
	v.SetDefault("server.mode", "release")
	v.SetDefault("database.max_open_conns", 100)
	v.SetDefault("database.max_idle_conns", 10)
	v.SetDefault("database.sslmode", "disable")
	v.SetDefault("redis.db", 0)
	v.SetDefault("redis.pool_size", 50)
	v.SetDefault("minio.secure", false)
	v.SetDefault("scheduler.max_concurrent", 10)
	v.SetDefault("scheduler.default_timeout", 3600)
	v.SetDefault("scheduler.queue_size", 1000)
	v.SetDefault("plugin.dir", "./plugins")
	v.SetDefault("plugin.registry_file", "./plugins/registry.json")
	v.SetDefault("retention.artifact_days", 30)
	v.SetDefault("retention.log_days", 90)
	v.SetDefault("logstore.enable_postgres", true)
	v.SetDefault("logstore.enable_redis", true)
	v.SetDefault("logstore.redis_channel", "logs")
	v.SetDefault("logstore.retention_days", 90)
	v.SetDefault("logstore.batch_size", 100)
	v.SetDefault("notification.smtp.port", 587)
	v.SetDefault("artifact.retention_days", 30)
	v.SetDefault("artifact.keep_last", 100)
	v.SetDefault("artifact.max_size_mb", 1024)

	cfg := &Config{}
	if err := v.Unmarshal(cfg); err != nil {
		return nil, fmt.Errorf("failed to unmarshal config: %w", err)
	}

	globalConfig = cfg
	return cfg, nil
}

func GetEnv() string {
	env := os.Getenv("APP_ENV")
	if env == "" {
		env = "development"
	}
	return env
}

func Get() *Config {
	if globalConfig == nil {
		cfg, _ := Load()
		return cfg
	}
	return globalConfig
}

func (c *DatabaseConfig) DSN() string {
	return fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
		c.Host, c.Port, c.User, c.Password, c.Name, c.SSLMode)
}

func (c *RedisConfig) Addr() string {
	return fmt.Sprintf("%s:%d", c.Host, c.Port)
}
