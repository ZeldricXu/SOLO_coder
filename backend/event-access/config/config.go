package config

import (
	"context"
	"time"

	"github.com/spf13/viper"
)

type Config struct {
	Server   ServerConfig
	InfluxDB InfluxDBConfig
	MySQL    MySQLConfig
	Log      LogConfig
}

type ServerConfig struct {
	Port         int
	Mode         string
	ReadTimeout  time.Duration
	WriteTimeout time.Duration
}

type InfluxDBConfig struct {
	URL    string
	Token  string
	Org    string
	Bucket string
}

type MySQLConfig struct {
	Host     string
	Port     int
	User     string
	Password string
	Database string
}

type LogConfig struct {
	FilePath string
	Level    string
}

func Load() *Config {
	viper.SetDefault("server.port", 8080)
	viper.SetDefault("server.mode", "debug")
	viper.SetDefault("server.read_timeout", 10*time.Second)
	viper.SetDefault("server.write_timeout", 10*time.Second)

	viper.SetDefault("influxdb.url", "http://localhost:8086")
	viper.SetDefault("influxdb.token", "my-token")
	viper.SetDefault("influxdb.org", "gamestats")
	viper.SetDefault("influxdb.bucket", "events")

	viper.SetDefault("mysql.host", "localhost")
	viper.SetDefault("mysql.port", 3306)
	viper.SetDefault("mysql.user", "root")
	viper.SetDefault("mysql.password", "password")
	viper.SetDefault("mysql.database", "gamestats")

	viper.SetDefault("log.filepath", "logs/app.log")
	viper.SetDefault("log.level", "info")

	viper.SetConfigName("config")
	viper.SetConfigType("yaml")
	viper.AddConfigPath(".")
	viper.AddConfigPath("/etc/gamestats/")
	viper.AutomaticEnv()

	_ = viper.ReadInConfig()

	return &Config{
		Server: ServerConfig{
			Port:         viper.GetInt("server.port"),
			Mode:         viper.GetString("server.mode"),
			ReadTimeout:  viper.GetDuration("server.read_timeout"),
			WriteTimeout: viper.GetDuration("server.write_timeout"),
		},
		InfluxDB: InfluxDBConfig{
			URL:    viper.GetString("influxdb.url"),
			Token:  viper.GetString("influxdb.token"),
			Org:    viper.GetString("influxdb.org"),
			Bucket: viper.GetString("influxdb.bucket"),
		},
		MySQL: MySQLConfig{
			Host:     viper.GetString("mysql.host"),
			Port:     viper.GetInt("mysql.port"),
			User:     viper.GetString("mysql.user"),
			Password: viper.GetString("mysql.password"),
			Database: viper.GetString("mysql.database"),
		},
		Log: LogConfig{
			FilePath: viper.GetString("log.filepath"),
			Level:    viper.GetString("log.level"),
		},
	}
}

func (c *Config) Context() context.Context {
	return context.Background()
}
