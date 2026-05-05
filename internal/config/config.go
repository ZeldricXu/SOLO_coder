package config

import (
	"fmt"
	"os"
	"time"

	"github.com/spf13/viper"
)

type Config struct {
	Server        ServerConfig         `mapstructure:"server"`
	MySQL         MySQLConfig          `mapstructure:"mysql"`
	Redis         RedisConfig          `mapstructure:"redis"`
	WebSocket     WebSocketConfig      `mapstructure:"websocket"`
	Season        SeasonConfig         `mapstructure:"season"`
	Ranking       RankingConfig        `mapstructure:"ranking"`
}

type ServerConfig struct {
	Port int    `mapstructure:"port"`
	Mode string `mapstructure:"mode"`
}

type MySQLConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	Username string `mapstructure:"username"`
	Password string `mapstructure:"password"`
	Database string `mapstructure:"database"`
	Charset  string `mapstructure:"charset"`
}

type RedisConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	Password string `mapstructure:"password"`
	DB       int    `mapstructure:"db"`
}

type WebSocketConfig struct {
	ReadBufferSize  int `mapstructure:"read_buffer_size"`
	WriteBufferSize int `mapstructure:"write_buffer_size"`
	WriteWait       int `mapstructure:"write_wait"`
	PongWait        int `mapstructure:"pong_wait"`
	PingPeriod      int `mapstructure:"ping_period"`
}

type SeasonConfig struct {
	AutoSwitchEnabled bool          `mapstructure:"auto_switch_enabled"`
	CheckInterval    time.Duration `mapstructure:"check_interval"`
	DefaultDuration  time.Duration `mapstructure:"default_duration"`
	ArchiveEnabled   bool          `mapstructure:"archive_enabled"`
	BackupTimeout    time.Duration `mapstructure:"backup_timeout"`
}

type RankingConfig struct {
	IncrementalUpdate   bool          `mapstructure:"incremental_update"`
	SnapshotInterval  time.Duration `mapstructure:"snapshot_interval"`
	PushAggregationEnabled bool    `mapstructure:"push_aggregation_enabled"`
	PushAggregationWindow time.Duration `mapstructure:"push_aggregation_window"`
	SameScoreAsSameRank bool        `mapstructure:"same_score_same_rank"`
}

var AppConfig *Config

func InitConfig() error {
	configPath := os.Getenv("CONFIG_PATH")
	if configPath == "" {
		configPath = "."
	}

	viper.AddConfigPath(configPath)
	viper.SetConfigName("config")
	viper.SetConfigType("yaml")

	viper.AutomaticEnv()

	viper.SetDefault("server.port", 8080)
	viper.SetDefault("server.mode", "debug")
	viper.SetDefault("mysql.host", "localhost")
	viper.SetDefault("mysql.port", 3306)
	viper.SetDefault("mysql.username", "root")
	viper.SetDefault("mysql.password", "")
	viper.SetDefault("mysql.database", "game_leaderboard")
	viper.SetDefault("mysql.charset", "utf8mb4")
	viper.SetDefault("redis.host", "localhost")
	viper.SetDefault("redis.port", 6379)
	viper.SetDefault("redis.password", "")
	viper.SetDefault("redis.db", 0)
	viper.SetDefault("websocket.read_buffer_size", 1024)
	viper.SetDefault("websocket.write_buffer_size", 1024)
	viper.SetDefault("websocket.write_wait", 10)
	viper.SetDefault("websocket.pong_wait", 60)
	viper.SetDefault("websocket.ping_period", 54)

	viper.SetDefault("season.auto_switch_enabled", true)
	viper.SetDefault("season.check_interval", "1m")
	viper.SetDefault("season.default_duration", "168h")
	viper.SetDefault("season.archive_enabled", true)
	viper.SetDefault("season.backup_timeout", "30s")

	viper.SetDefault("ranking.incremental_update", true)
	viper.SetDefault("ranking.snapshot_interval", "5m")
	viper.SetDefault("ranking.push_aggregation_enabled", true)
	viper.SetDefault("ranking.push_aggregation_window", "1s")
	viper.SetDefault("ranking.same_score_same_rank", true)

	if err := viper.ReadInConfig(); err != nil {
		if _, ok := err.(viper.ConfigFileNotFoundError); !ok {
			return err
		}
	}

	AppConfig = &Config{}
	if err := viper.Unmarshal(AppConfig); err != nil {
		return err
	}

	return nil
}

func (c *MySQLConfig) DSN() string {
	return fmt.Sprintf("%s:%s@tcp(%s:%d)/%s?charset=%s&parseTime=True&loc=Local",
		c.Username, c.Password, c.Host, c.Port, c.Database, c.Charset)
}
